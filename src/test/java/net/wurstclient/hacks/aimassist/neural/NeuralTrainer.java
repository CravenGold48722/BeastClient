/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/**
 * Trains AimAssist's neural network in {@link PvpSim}, millions of fights
 * on all CPU cores. Not part of the mod; run with
 * {@code gradlew trainNeuralAim} (see build.gradle).
 *
 * <ol>
 * <li><b>Learn the tactics first</b> (behaviour cloning with DAgger, Ross et
 * al. 2011): the network copies Beast's Sprint combo, trained on the
 * situations it gets itself into, not just the teacher's - so it knows how
 * to recover from its own mistakes.</li>
 * <li><b>Then beat everyone</b> (OpenAI-style evolution strategies,
 * Salimans et al. 2017: antithetic noise, rank-based fitness, Adam): each
 * generation tries ~50 nudged copies of the weights, each in a dozen
 * fights, and moves the weights toward the copies that won. The opponents
 * are a league (like AlphaStar's): the scripted tacticians, earlier
 * versions of itself (so it can't forget how to beat them - self-play alone
 * goes in circles) and its current self.</li>
 * <li><b>Kept on the tactics</b>: the fitness also rewards sprint hits,
 * crits and combos, punishes being comboed, and early on rewards agreeing
 * with the teacher (fading out, like AlphaStar's pull toward its supervised
 * policy).</li>
 * </ol>
 * Evolution strategies fit this best: no gradients through 600-tick
 * fights, no value network, any fitness, and a tiny net trains fast.
 */
public final class NeuralTrainer
{
	static final int MAX_TICKS = 1200;
	
	/**
	 * Share of the training fights that are a free-for-all of
	 * {@link #FFA_PLAYERS} (docs/TRAINING.MD; the candidate against copies of
	 * its opponent, everyone for themselves). {@code -Dnn.ffa=0} = duels only.
	 */
	static final double FFA_RATE =
		Double.parseDouble(System.getProperty("nn.ffa", "0.0625"));
	static final int FFA_PLAYERS = 20;
	
	/**
	 * Now and then a "wild" opponent: the net itself with noise as big as
	 * its weights, for variety. Never two generations in a row: if it wins,
	 * it comes back the generation after next, and again, until it's beaten
	 * (random, normal, random, normal...); no new one before that. Never
	 * kept in the league; an unbeaten one carries over to the next round.
	 */
	static double WILD_RATE =
		Double.parseDouble(System.getProperty("nn.wild", "0.08"));
	static final double WILD_SHARE = 0.25, WILD_SPREAD = 1.0;
	/**
	 * Self-play still fights the scripted players this often (mostly the
	 * ones it loses to): trained only against itself it slid to 36% against
	 * SprintCombo and 29% against Counter, who fight most like real players
	 * (user, 2026-10-27). {@code -Dnn.scriptedShare=0} = pure self-play.
	 */
	static final double SCRIPTED_SHARE =
		Double.parseDouble(System.getProperty("nn.scriptedShare", "0.12"));
	/** The wild one still to beat (null = none). */
	static float[] WILD;
	static boolean wildMet;
	/** Wild opponents in the last evolve(): new ones, won the first time. */
	static int WILDS, WILDS_WON;
	
	static boolean isFfa(long seed)
	{
		long h = seed * 0x9E3779B97F4A7C15L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53 < FFA_RATE;
	}
	
	static final int THREADS = Integer.getInteger("nn.threads",
		Runtime.getRuntime().availableProcessors());
	/** Whether the SIMD forward pass is in use (see VectorKernel). */
	static final boolean SIMD = installKernel();
	
	private static boolean installKernel()
	{
		if(Boolean.getBoolean("nn.nosimd"))
			return false;
		try
		{
			VectorKernel.install();
			return true;
		}catch(Throwable t)
		{
			// no --add-modules jdk.incubator.vector: the plain pass
			return false;
		}
	}
	
	/**
	 * The share of the time spent working, the rest asleep (train lite:
	 * 0.15 of one thread); 1 = flat out.
	 */
	static final double DUTY = Math.max(0.01,
		Math.min(1, Double.parseDouble(System.getProperty("nn.duty", "1"))));
	private static long lastDutyPause = System.nanoTime();
	
	/** Sleeps long enough that the work since the last call keeps DUTY. */
	static void dutyPause()
	{
		if(DUTY >= 1 || STOP.getAsBoolean())
			return;
		long work = System.nanoTime() - lastDutyPause;
		long sleep = (long)(work * (1 / DUTY - 1)) / 1_000_000;
		try
		{
			Thread.sleep(Math.min(sleep, 120_000));
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		lastDutyPause = System.nanoTime();
	}
	
	/** Every fighter adapts mid-fight (FightAdapter), like in game. */
	/** Training fights against a far stronger opponent (makeBrute). */
	static final double NETHERITE_SHARE = 0.15;
	
	static final boolean ADAPT =
		Boolean.parseBoolean(System.getProperty("nn.adapt", "true"));
	
	static final ExecutorService POOL =
		Executors.newFixedThreadPool(THREADS, r -> {
			Thread t = new Thread(r, "NeuralTrainer");
			t.setDaemon(true);
			return t;
		});
	
	public static void main(String[] args) throws Exception
	{
		Locale.setDefault(Locale.ROOT);
		String mode = args.length > 0 ? args[0] : "train";
		switch(mode)
		{
			case "sweep" -> sweep(
				args.length > 1 ? Integer.parseInt(args[1]) : 150);
			case "train" ->
			{
				int[] sizes =
					parseSizes(args.length > 1 ? args[1] : "50,32,32,19");
				int gens = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
				Path out = Path.of(args.length > 3 ? args[3]
					: "src/main/resources/assets/wurst/neural/aimassist_pvp.bin");
				// the long run: more and noisier candidates, more fights each
				PAIRS = Integer.getInteger("nn.pairs", 128);
				GAMES_PER_EVAL = Integer.getInteger("nn.games", 24);
				SIGMA = Float.parseFloat(System.getProperty("nn.sigma", "0.1"));
				LEARNING_RATE =
					Float.parseFloat(System.getProperty("nn.lr", "0.01"));
				BENCH_EVERY = 100;
				BENCH_GAMES = 400;
				Result r = trainFull(sizes, gens, 6, false);
				System.out.println("Best: " + fmt(r.winRates));
				save(r.params, sizes, out);
				System.out.println("Saved " + out);
			}
			case "probe" -> probe();
			case "bench" -> bench();
			case "forever" -> ContinuousTrainer.run(args);
			case "export" -> ContinuousTrainer.export(args);
			case "echo" -> EchoPolicy.calibrate(args);
			case "teach" -> ItemTeacher.teach(args);
			case "widen" -> ContinuousTrainer.widen(args);
			case "selfplay" ->
			{
				// from random weights, against nothing but itself and its
				// earlier versions: no teacher, no scripted opponents
				int[] sizes =
					parseSizes(args.length > 1 ? args[1] : "50,32,32,19");
				int gens = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
				Path out = Path.of(args.length > 3 ? args[3]
					: "src/main/resources/assets/wurst/neural/aimassist_pvp.bin");
				PAIRS = Integer.getInteger("nn.pairs", 128);
				GAMES_PER_EVAL = Integer.getInteger("nn.games", 24);
				SIGMA = Float.parseFloat(System.getProperty("nn.sigma", "0.1"));
				LEARNING_RATE =
					Float.parseFloat(System.getProperty("nn.lr", "0.01"));
				BENCH_EVERY = Integer.getInteger("nn.bench", 100);
				BENCH_GAMES = 400;
				PRIOR = 0;
				SCRIPTED = false;
				String ref = System.getProperty("nn.reference");
				if(ref != null)
					try(var is = Files.newInputStream(Path.of(ref)))
					{
						REFERENCE = NeuralNet.read(is).getParams();
					}
				Result r =
					evolve(sizes, xavier(sizes, new Random(42)), gens, false);
				System.out.println("Best: " + fmt(r.winRates));
				save(r.params, sizes, out);
				System.out.println("Saved " + out);
			}
			case "resume" ->
			{
				// more self-play from a saved net (it becomes the league's
				// first member and the "Imitation" benchmark column)
				Path in = Path.of(args[1]);
				int gens = Integer.parseInt(args[2]);
				Path out = Path.of(args.length > 3 ? args[3] : args[1]);
				NeuralNet start;
				try(var is = Files.newInputStream(in))
				{
					start = NeuralNet.read(is);
				}
				PAIRS = Integer.getInteger("nn.pairs", 128);
				GAMES_PER_EVAL = Integer.getInteger("nn.games", 24);
				SIGMA = Float.parseFloat(System.getProperty("nn.sigma", "0.1"));
				LEARNING_RATE =
					Float.parseFloat(System.getProperty("nn.lr", "0.01"));
				BENCH_EVERY = Integer.getInteger("nn.bench", 100);
				BENCH_GAMES = 400;
				PRIOR = 0;
				// -Dnn.scripted=false: pure self-play (the scripted fighters
				// only as a yardstick, the start net as "OldNet")
				SCRIPTED = !"false".equals(System.getProperty("nn.scripted"));
				if(!SCRIPTED)
					REFERENCE = start.getParams().clone();
				Result r = evolve(start.getSizes(), start.getParams().clone(),
					gens, false);
				System.out.println("Best: " + fmt(r.winRates));
				save(r.params, r.sizes, out);
				System.out.println("Saved " + out);
			}
			default -> throw new IllegalArgumentException(mode);
		}
		POOL.shutdownNow();
	}
	
	/** Simulator speed: the shipped net against itself, one core, then all. */
	static void bench() throws Exception
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		int[] sizes;
		float[] params;
		if(shipped != null)
		{
			sizes = shipped.getSizes();
			params = shipped.getParams();
		}else
		{
			// (inputs changed since the shipped net: a random one, same cost)
			sizes = new int[]{NeuralObservation.SIZE, 12, NeuralAction.OUTPUTS};
			params = new float[NeuralNet.paramCount(sizes)];
			java.util.Random r = new java.util.Random(1);
			for(int i = 0; i < params.length; i++)
				params[i] = (float)(r.nextGaussian() * 0.3);
		}
		// -Dnn.benchSizes=60,32,32,19: another size's cost (random weights)
		String benchSizes = System.getProperty("nn.benchSizes");
		if(benchSizes != null)
		{
			sizes = parseSizes(benchSizes);
			params = new float[NeuralNet.paramCount(sizes)];
			java.util.Random r = new java.util.Random(1);
			for(int i = 0; i < params.length; i++)
				params[i] = (float)(r.nextGaussian() * 0.3);
		}
		System.out.println("SIMD forward pass: " + (SIMD ? "on" : "off")
			+ ", sizes " + Arrays.toString(sizes) + ", adapting " + ADAPT);
		// -Dnn.benchFitness=true: training's own fights (FFA, terrain,
		// brutes, the rules' bonus), on every thread, 20 s
		if(Boolean.getBoolean("nn.benchFitness"))
		{
			Spec opp = netSpec(sizes, params);
			final int[] fs = sizes;
			final float[] fp = params;
			long end = System.nanoTime() + 20_000_000_000L,
				t0 = System.nanoTime();
			java.util.concurrent.atomic.AtomicLong fights =
				new java.util.concurrent.atomic.AtomicLong(),
				ticks = new java.util.concurrent.atomic.AtomicLong();
			long before = PvpSim.SIMULATED_TICKS.sum();
			List<Future<?>> jobs = new ArrayList<>();
			for(int t = 0; t < THREADS; t++)
			{
				final long seed = t * 1_000_000L;
				jobs.add(POOL.submit(() -> {
					long g = seed;
					while(System.nanoTime() < end)
					{
						fitness(fs, fp, opp, g++, 0);
						fights.incrementAndGet();
					}
				}));
			}
			for(Future<?> f : jobs)
				f.get();
			double secs = (System.nanoTime() - t0) / 1e9;
			double perSec = (PvpSim.SIMULATED_TICKS.sum() - before) / secs;
			System.out.printf(
				"training fights, %d threads: %.0f fights/s, %s%n", THREADS,
				fights.get() / secs, simTimePerHour(perSec));
			return;
		}
		for(int round = 0; round < 3; round++)
		{
			long ticks = 0, t0 = System.nanoTime();
			int fights = 4000;
			for(int g = 0; g < fights; g++)
			{
				PvpSim sim =
					new PvpSim(g, new NetPolicy(new NeuralNet(sizes, params)),
						new NetPolicy(new NeuralNet(sizes, params)), MAX_TICKS);
				// arenas like training's
				sim.useTerrain(
					Terrain.random(new SplittableRandom(g ^ 0x7E44L)));
				sim.run();
				ticks += sim.tick;
			}
			double secs = (System.nanoTime() - t0) / 1e9;
			System.out.printf(
				"1 core: %,.0f ticks/s, %,.0f fights/s (%.0f ns a tick)%n",
				ticks / secs, fights / secs, secs * 1e9 / ticks);
		}
		// every thread fighting for 10 s, the way training does
		java.util.concurrent.atomic.AtomicLong total =
			new java.util.concurrent.atomic.AtomicLong();
		long end = System.nanoTime() + 10_000_000_000L;
		long t0 = System.nanoTime();
		List<Future<?>> jobs = new ArrayList<>();
		for(int t = 0; t < THREADS; t++)
		{
			final long seed = t * 1_000_000L;
			final int[] fs = sizes;
			final float[] fp = params;
			jobs.add(POOL.submit(() -> {
				long g = seed, ticks = 0;
				while(System.nanoTime() < end)
				{
					PvpSim sim =
						new PvpSim(g++, new NetPolicy(new NeuralNet(fs, fp)),
							new NetPolicy(new NeuralNet(fs, fp)), MAX_TICKS);
					sim.useTerrain(
						Terrain.random(new SplittableRandom(g ^ 0x7E44L)));
					sim.run();
					ticks += sim.tick;
				}
				total.addAndGet(ticks);
			}));
		}
		for(Future<?> f : jobs)
			f.get();
		double secs = (System.nanoTime() - t0) / 1e9;
		double perSec = total.get() / secs;
		System.out.printf("%d threads: %,.0f ticks/s = %s%n", THREADS, perSec,
			simTimePerHour(perSec));
	}
	
	/** Game time in a human unit: "41 days", "3.2 years". */
	static String simTime(long ticks)
	{
		double days = ticks / 20.0 / 86400;
		if(days < 1)
			return String.format("%.1f hours", days * 24);
		if(days < 365.25)
			return String.format("%.0f days", days);
		return String.format("%.1f years", days / 365.25);
	}
	
	/**
	 * How much game time a real hour of training is at this many simulated
	 * ticks a second (each tick both fighters play; 20 ticks = 1 s).
	 */
	static String simTimePerHour(double ticksPerSecond)
	{
		double years = ticksPerSecond * 3600 / 20 / (365.25 * 24 * 3600);
		return String.format("%.1f years of fighting per real hour (x%,.0f)",
			years, ticksPerSecond / 20);
	}
	
	/** The scripted players against each other: sanity and speed. */
	static void probe()
	{
		int k = ScriptedPolicies.ALL.length;
		long t = System.nanoTime();
		long ticks = 0;
		int games = 0;
		for(int i = 0; i < k; i++)
		{
			StringBuilder sb = new StringBuilder(
				String.format("%-12s", ScriptedPolicies.NAMES[i]));
			for(int j = 0; j < k; j++)
			{
				int wins = 0;
				long hits = 0, sprintHits = 0, crits = 0, len = 0;
				for(int g = 0; g < 400; g++)
				{
					PvpSim sim = new PvpSim(g * 31L + i * 7 + j,
						ScriptedPolicies.ALL[i].get(),
						ScriptedPolicies.ALL[j].get(), MAX_TICKS);
					int r = sim.run();
					r = sim.outcome(r);
					wins += r > 0 ? 2 : r == 0 ? 1 : 0;
					hits += sim.a.hits;
					sprintHits += sim.a.sprintHits;
					crits += sim.a.crits;
					len += sim.tick;
				}
				ticks += len;
				games += 400;
				sb.append(String.format(" %3.0f%% (%.1fh %.1fs %.1fc %3dt)",
					wins / 8.0, hits / 400.0, sprintHits / 400.0, crits / 400.0,
					len / 400));
			}
			System.out.println(sb);
		}
		double secs = (System.nanoTime() - t) / 1e9;
		System.out.printf(
			"%d fights, %,d ticks in %.1f s on one core: %,.0f ticks/s (%,.0fx real time)%n",
			games, ticks, secs, ticks / secs, ticks / secs / 20);
	}
	
	static int[] parseSizes(String s)
	{
		return Arrays.stream(s.split(",")).mapToInt(Integer::parseInt)
			.toArray();
	}
	
	static void save(float[] params, int[] sizes, Path out) throws IOException
	{
		Files.createDirectories(out.getParent());
		// written next to it and moved over: a kill mid-write never
		// leaves a broken file behind
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		try(OutputStream os = Files.newOutputStream(tmp))
		{
			new NeuralNet(sizes, params).write(os);
		}
		Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
			java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}
	
	record Result(int[] sizes, float[] params, float[] bcParams,
		double benchmark, double[] winRates)
	{}
	
	// ── architecture sweep ───────────────────────────────────────────────
	
	/**
	 * Trains each candidate shape for the same number of generations, then
	 * has them all play each other and the scripted players: the smallest
	 * net that's about as good as the best one is the one to ship.
	 */
	static void sweep(int gens) throws Exception
	{
		int I = NeuralObservation.SIZE, O = NeuralAction.OUTPUTS;
		int[][] shapes = {{I, 8, O}, {I, 12, O}, {I, 24, O}, {I, 16, 8, O},
			{I, 24, 12, O}, {I, 32, 16, O}};
		List<Result> results = new ArrayList<>();
		for(int[] s : shapes)
		{
			System.out.println("=== " + Arrays.toString(s) + " ("
				+ NeuralNet.paramCount(s) + " parameters)");
			results.add(trainFull(s, gens, 4, false));
		}
		
		// round robin between the trained nets
		int n = results.size();
		double[] cross = new double[n];
		for(int i = 0; i < n; i++)
			for(int j = 0; j < n; j++)
				if(i != j)
				{
					Result ri = results.get(i), rj = results.get(j);
					cross[i] += winRate(netSpec(ri.sizes, ri.params),
						netSpec(rj.sizes, rj.params), 300, 7000L * i + j)
						/ (n - 1);
				}
			
		System.out.println();
		System.out.println(
			"shape                params  ns/tick  vsScripted  vsOtherNets  score");
		for(int i = 0; i < n; i++)
		{
			Result r = results.get(i);
			double score = (r.benchmark + cross[i]) / 2;
			System.out.printf("%-20s %6d %8.0f %10.3f %12.3f %6.3f%n",
				Arrays.toString(r.sizes), NeuralNet.paramCount(r.sizes),
				nanosPerForward(r.sizes, r.params), r.benchmark, cross[i],
				score);
		}
	}
	
	static double nanosPerForward(int[] sizes, float[] params)
	{
		NeuralNet net = new NeuralNet(sizes, params);
		float[] in = new float[NeuralObservation.SIZE];
		Random rnd = new Random(1);
		for(int i = 0; i < in.length; i++)
			in[i] = rnd.nextFloat() * 2 - 1;
		float sink = 0;
		for(int i = 0; i < 200_000; i++)
			sink += net.forward(in)[0];
		long t = System.nanoTime();
		int runs = 2_000_000;
		for(int i = 0; i < runs; i++)
		{
			in[i & 15] += 1e-7F;
			sink += net.forward(in)[0];
		}
		double ns = (System.nanoTime() - t) / (double)runs;
		if(sink == 42)
			System.out.print("");
		return ns;
	}
	
	// ── the whole pipeline ───────────────────────────────────────────────
	
	static Result trainFull(int[] sizes, int gens, int daggerRounds,
		boolean verbose) throws Exception
	{
		long start = System.nanoTime();
		float[] bc = imitate(sizes, daggerRounds);
		System.out.printf("Imitation done in %.0f s%n",
			(System.nanoTime() - start) / 1e9);
		double[] bcRates = benchmarkRates(sizes, bc, null, 300, 99);
		System.out.println("Imitation net vs scripted: " + fmt(bcRates));
		
		Result r = evolve(sizes, bc, gens, verbose);
		System.out.printf("Total %.0f s%n", (System.nanoTime() - start) / 1e9);
		return r;
	}
	
	// ── step 1: imitation (DAgger) ───────────────────────────────────────
	
	static final int SAMPLE_CAP = 300_000;
	
	static final class Dataset
	{
		final float[][] x = new float[SAMPLE_CAP][];
		final byte[][] y = new byte[SAMPLE_CAP][];
		int size, seen;
		final Random random = new Random(7);
		
		/** Reservoir sampling once full, so every round stays represented. */
		synchronized void add(float[] obs, byte[] label)
		{
			seen++;
			if(size < SAMPLE_CAP)
			{
				x[size] = obs;
				y[size] = label;
				size++;
				return;
			}
			int j = random.nextInt(seen);
			if(j < SAMPLE_CAP)
			{
				x[j] = obs;
				y[j] = label;
			}
		}
	}
	
	/** The teacher's choice on every tick, played by teacher or student. */
	static final class DaggerPolicy implements Policy
	{
		final Policy teacher = new ScriptedPolicies.SprintCombo();
		final Policy student;
		final double beta;
		final Dataset data;
		final NeuralAction teach = new NeuralAction();
		SplittableRandom random;
		
		DaggerPolicy(Policy student, double beta, Dataset data)
		{
			this.student = student;
			this.beta = beta;
			this.data = data;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			teacher.reset(random.split());
			if(student != null)
				student.reset(random.split());
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			teacher.act(me, o, teach);
			if(random.nextInt(2) == 0)
			{
				float[] x = new float[NeuralObservation.SIZE];
				o.encode(x);
				data.add(x, new byte[]{(byte)(1 - teach.forward),
					(byte)(1 - teach.strafe), (byte)(teach.jump ? 1 : 0),
					(byte)(teach.sprint ? 1 : 0), (byte)(teach.sneak ? 1 : 0),
					(byte)(teach.attack ? 1 : 0), (byte)(teach.web ? 1 : 0),
					(byte)(teach.shield ? 1 : 0), (byte)(teach.pot ? 1 : 0),
					(byte)(teach.eat ? 1 : 0), (byte)(teach.water ? 1 : 0),
					(byte)(teach.pearlIn ? 1 : 0),
					(byte)(teach.pearlOut ? 1 : 0),
					(byte)(teach.windJump ? 1 : 0),
					(byte)(teach.windHit ? 1 : 0)});
			}
			if(student == null || random.nextDouble() < beta)
				out.set(teach);
			else
				student.act(me, o, out);
		}
	}
	
	static float[] imitate(int[] sizes, int rounds) throws Exception
	{
		float[] params = xavier(sizes, new Random(42));
		Dataset data = new Dataset();
		for(int round = 0; round < rounds; round++)
		{
			double beta = round == 0 ? 1 : Math.pow(0.5, round);
			float[] snapshot = params.clone();
			List<Future<?>> jobs = new ArrayList<>();
			int games = 400;
			for(int g = 0; g < games; g++)
			{
				final int game = g;
				final int r = round;
				jobs.add(POOL.submit(() -> {
					Policy student = r == 0 ? null
						: new NetPolicy(new NeuralNet(sizes, snapshot));
					DaggerPolicy p = new DaggerPolicy(student, beta, data);
					Policy opp =
						ScriptedPolicies.ALL[game % ScriptedPolicies.ALL.length]
							.get();
					PvpSim sim =
						new PvpSim(1_000_003L * r + game, p, opp, MAX_TICKS);
					sim.a.onlyGoodHits = true;
					sim.run();
				}));
			}
			for(Future<?> f : jobs)
				f.get();
			
			Backprop.train(sizes, params, data.x, data.y, data.size, 6, 0.003F,
				new Random(round));
			System.out.printf("DAgger round %d (beta %.2f): %d samples, %s%n",
				round, beta, data.size,
				Backprop.accuracy(sizes, params, data.x, data.y, data.size));
		}
		return params;
	}
	
	static float[] xavier(int[] sizes, Random random)
	{
		float[] p = new float[NeuralNet.paramCount(sizes)];
		int k = 0;
		for(int l = 1; l < sizes.length; l++)
		{
			double limit = Math.sqrt(6.0 / (sizes[l] + sizes[l - 1]));
			for(int i = 0; i < sizes[l] * sizes[l - 1]; i++)
				p[k++] = (float)((random.nextDouble() * 2 - 1) * limit);
			k += sizes[l];
		}
		return p;
	}
	
	// ── step 2: evolution strategies with a league ───────────────────────
	
	static int PAIRS = 48;
	static int GAMES_PER_EVAL = 12;
	/** Fights per job (a generation's end waits for the slowest job). */
	static final int CHUNK_GAMES = 4;
	static float SIGMA = 0.03F;
	static float LEARNING_RATE = 0.01F;
	static double PRIOR = 0.15;
	static int BENCH_EVERY = 50;
	/** Experiments: train against one scripted player only. */
	static final int ONLY = Integer.getInteger("nn.only", -1);
	static int BENCH_GAMES = 200;
	static final float WEIGHT_DECAY = 0.001F;
	static final int HALL_SIZE = 30;
	/**
	 * Whether the scripted fighters are in the league. Off in self-play:
	 * then they're only a yardstick in the benchmark, never trained against.
	 */
	static boolean SCRIPTED = true;
	/** Self-play: a fixed net to measure progress against (the old one). */
	static float[] REFERENCE;
	/** REFERENCE in the benchmark line (ContinuousTrainer: the champion). */
	static String REFERENCE_NAME = "OldNet";
	/** Where a run's noise and opponents come from (new each round). */
	static long SEED = 1234;
	/** Checked every generation: true ends the run early. */
	static volatile BooleanSupplier STOP = () -> false;
	/** Generations the last {@link #evolve} ran (fewer if stopped). */
	static int GENS_RUN;
	
	/** An opponent: a scripted player (index) or a network (params). */
	/**
	 * An opponent: a scripted player (index), a network (params), or a copy
	 * of a real player (copy: plays only what it learned from them, hits
	 * however they did).
	 */
	record Spec(int scripted, int[] sizes, float[] params, boolean copy,
		boolean styled)
	{
		Spec(int scripted, int[] sizes, float[] params)
		{
			this(scripted, sizes, params, false, false);
		}
		
		Spec(int scripted, int[] sizes, float[] params, boolean copy)
		{
			this(scripted, sizes, params, copy, false);
		}
		
		Policy create()
		{
			if(params == null)
				return styled ? new ScriptedPolicies.Switching(scripted)
					: ScriptedPolicies.ALL[scripted].get();
			return copy ? OnlineLearner.NetPolicy.copy(sizes, params)
				: NetPolicy.take(sizes, params);
		}
		
		boolean isNet()
		{
			return params != null;
		}
	}
	
	/**
	 * Copies of the real players fought in game (FightLog, made each round
	 * by ContinuousTrainer) and how often one is the opponent: as often as
	 * self-play (league + scripted) once there are any (user, 2026-10-29).
	 */
	static volatile List<float[]> REAL = List.of();
	static final double REAL_SHARE =
		Double.parseDouble(System.getProperty("nn.realShare", "0.5"));
	
	/** The net with noise about as big as its own weights. */
	static float[] wildling(float[] theta, Random rnd)
	{
		double sq = 0;
		for(float w : theta)
			sq += w * w;
		double scale = Math.sqrt(sq / theta.length) * WILD_SPREAD;
		float[] wild = theta.clone();
		for(int j = 0; j < wild.length; j++)
			wild[j] += (float)(rnd.nextGaussian() * scale);
		return wild;
	}
	
	static Spec netSpec(int[] sizes, float[] params)
	{
		return new Spec(-1, sizes, params);
	}
	
	static Result evolve(int[] sizes, float[] init, int gens, boolean verbose)
		throws Exception
	{
		return evolve(sizes, init, gens, verbose, null);
	}
	
	/**
	 * @param hall
	 *            earlier versions of itself to play against, kept and
	 *            updated across calls (null: a new one, starting with init)
	 */
	static Result evolve(int[] sizes, float[] init, int gens, boolean verbose,
		List<float[]> hall) throws Exception
	{
		int n = init.length;
		int k = SCRIPTED ? ScriptedPolicies.ALL.length : 0;
		float[] theta = init.clone();
		float[] m = new float[n], v = new float[n];
		
		// the league: scripted players first, then earlier versions of itself
		// (the first one stays in for good)
		if(hall == null)
		{
			hall = new ArrayList<>();
			hall.add(init.clone());
		}
		double[] wins = new double[k + HALL_SIZE];
		double[] games = new double[k + HALL_SIZE];
		// the scripted players in self-play (SCRIPTED_SHARE)
		int sk = ScriptedPolicies.ALL.length;
		double[] sWins = new double[sk], sGames = new double[sk];
		
		float[] best = theta.clone();
		double bestScore = -1;
		double[] bestRates = null;
		long fights = 0;
		long start = System.nanoTime();
		Random rnd = new Random(SEED);
		boolean wildLast = false;
		WILDS = WILDS_WON = 0;
		GENS_RUN = 0;
		// Guided ES: the directions the last generations' steps went
		float[][] past = new float[GUIDE_K][];
		int pastCount = 0;
		
		for(int gen = 1; gen <= gens; gen++)
		{
			dutyPause();
			if(STOP.getAsBoolean())
			{
				if(bestScore < 0)
					best = theta.clone();
				break;
			}
			GENS_RUN++;
			long genSeed = rnd.nextLong();
			final float[][] basis =
				orthonormal(past, Math.min(pastCount, GUIDE_K), n);
			double prior = PRIOR * Math.max(0, 1 - gen / (0.25 * gens));
			
			// Prioritized fictitious self-play (AlphaStar): opponents it
			// still loses to come up more often, beaten ones rarely
			int pool = k + hall.size();
			double[] weight = new double[pool];
			double total = 0;
			for(int i = 0; i < pool; i++)
			{
				double wr = (wins[i] + 1) / (games[i] + 2);
				weight[i] = (1 - wr) * (1 - wr) + 0.02;
				total += weight[i];
			}
			double[] sWeight = new double[sk];
			double sTotal = 0;
			for(int i = 0; i < sk; i++)
			{
				double wr = (sWins[i] + 1) / (sGames[i] + 2);
				sWeight[i] = (1 - wr) * (1 - wr) + 0.02;
				sTotal += sWeight[i];
			}
			
			// a wild one, never right after one: the unbeaten one again, or
			// now and then a new one
			float[] wild = null;
			if(!wildLast && ONLY < 0)
			{
				if(WILD == null && rnd.nextDouble() < WILD_RATE)
				{
					WILD = wildling(theta, rnd);
					wildMet = false;
					WILDS++;
				}
				wild = WILD;
			}
			wildLast = wild != null;
			Spec wildSpec = wild != null ? netSpec(sizes, wild) : null;
			int wildGames = 0, wildWins = 0;
			
			// the same opponents and fights for +noise and -noise
			int[][] oppIndex = new int[PAIRS][GAMES_PER_EVAL];
			Spec[][] opps = new Spec[PAIRS][GAMES_PER_EVAL];
			float[] current = theta.clone();
			for(int i = 0; i < PAIRS; i++)
				for(int g = 0; g < GAMES_PER_EVAL; g++)
				{
					if(ONLY >= 0)
					{
						oppIndex[i][g] = ONLY;
						opps[i][g] = new Spec(ONLY, null, null);
						continue;
					}
					if(wildSpec != null && rnd.nextDouble() < WILD_SHARE)
					{
						oppIndex[i][g] = -2;
						opps[i][g] = wildSpec;
						continue;
					}
					List<float[]> real = REAL;
					if(!real.isEmpty() && rnd.nextDouble() < REAL_SHARE)
					{
						oppIndex[i][g] = -3 - sk;
						opps[i][g] = new Spec(-1, sizes,
							real.get(rnd.nextInt(real.size())), true);
						continue;
					}
					if(!SCRIPTED && rnd.nextDouble() < SCRIPTED_SHARE)
					{
						double u = rnd.nextDouble() * sTotal;
						int s = 0;
						while(s < sk - 1 && (u -= sWeight[s]) > 0)
							s++;
						oppIndex[i][g] = -3 - s;
						// a style of its own each fight (Styled)
						opps[i][g] = new Spec(s, null, null, false, true);
						continue;
					}
					if(rnd.nextDouble() < 0.15)
					{
						oppIndex[i][g] = -1;
						opps[i][g] = netSpec(sizes, current);
						continue;
					}
					double u = rnd.nextDouble() * total;
					int idx = 0;
					while(idx < pool - 1 && (u -= weight[idx]) > 0)
						idx++;
					oppIndex[i][g] = idx;
					opps[i][g] = idx < k ? new Spec(idx, null, null)
						: netSpec(sizes, hall.get(idx - k));
				}
			
			BRUTE = theta.clone();
			// jobs of a few fights each: with one job per candidate (24
			// fights) most threads waited for the last few at the end of a
			// generation (2026-10-30) - same fights, same order of adding up
			int chunks = (GAMES_PER_EVAL + CHUNK_GAMES - 1) / CHUNK_GAMES;
			List<Future<double[]>> jobs = new ArrayList<>(PAIRS * 2 * chunks);
			for(int i = 0; i < PAIRS; i++)
				for(int sign = 1; sign >= -1; sign -= 2)
					for(int c = 0; c < chunks; c++)
					{
						final int pair = i, sg = sign, from = c * CHUNK_GAMES,
							to = Math.min(GAMES_PER_EVAL, from + CHUNK_GAMES);
						jobs.add(POOL.submit(() -> {
							float[] cand = theta.clone();
							float[] eps = noise(genSeed, pair, n, basis);
							for(int j = 0; j < n; j++)
								cand[j] += sg * SIGMA * eps[j];
							double[] part = new double[to - from];
							for(int g = from; g < to; g++)
								part[g - from] =
									fitness(sizes, cand, opps[pair][g],
										genSeed * 31 + pair * 1009L + g, prior);
							return part;
						}));
					}
			double[] fit = new double[PAIRS * 2];
			for(int i = 0; i < fit.length; i++)
			{
				double[] scores = new double[GAMES_PER_EVAL];
				for(int c = 0; c < chunks; c++)
				{
					double[] part = jobs.get(i * chunks + c).get();
					System.arraycopy(part, 0, scores, c * CHUNK_GAMES,
						part.length);
				}
				for(int g = 0; g < GAMES_PER_EVAL; g++)
				{
					fit[i] += scores[g] / GAMES_PER_EVAL;
					int idx = oppIndex[i / 2][g];
					if(idx == -2)
					{
						wildGames++;
						if(scores[g] > 0)
							wildWins++;
					}
					if(idx <= -3 && idx > -3 - sk)
					{
						int s = -3 - idx;
						sGames[s] = sGames[s] * 0.995 + 1;
						sWins[s] = sWins[s] * 0.995 + (scores[g] > 0 ? 1 : 0);
					}
					if(idx < 0)
						continue;
					// decaying counts: what it can beat now, not long ago
					games[idx] = games[idx] * 0.995 + 1;
					wins[idx] = wins[idx] * 0.995 + (scores[g] > 0 ? 1 : 0);
				}
			}
			fights += (long)PAIRS * 2 * GAMES_PER_EVAL;
			
			if(wild != null)
			{
				// beaten (won at least half): gone; else it comes back
				boolean wildWon = wildGames > 0 && wildWins * 2 < wildGames;
				if(wildWon && !wildMet)
					WILDS_WON++;
				wildMet = true;
				if(!wildWon)
					WILD = null;
			}
			
			// rank-based fitness: robust to outliers and scale
			double[] rank = centeredRanks(fit);
			float[] grad = new float[n];
			for(int i = 0; i < PAIRS; i++)
			{
				double w = rank[2 * i] - rank[2 * i + 1];
				if(w == 0)
					continue;
				float[] eps = noise(genSeed, i, n, basis);
				for(int j = 0; j < n; j++)
					grad[j] += (float)(w * eps[j]);
			}
			past[pastCount++ % GUIDE_K] = grad.clone();
			// Adam (ascent) with weight decay; the step shrinks toward the
			// end (cosine) so it settles instead of jittering
			float lr = (float)(LEARNING_RATE
				* (0.15 + 0.85 * 0.5 * (1 + Math.cos(Math.PI * gen / gens))));
			for(int j = 0; j < n; j++)
			{
				float g =
					grad[j] / (2 * PAIRS * SIGMA) - WEIGHT_DECAY * theta[j];
				m[j] = 0.9F * m[j] + 0.1F * g;
				v[j] = 0.999F * v[j] + 0.001F * g * g;
				float mh = m[j] / (1 - (float)Math.pow(0.9, gen));
				float vh = v[j] / (1 - (float)Math.pow(0.999, gen));
				theta[j] += lr * mh / ((float)Math.sqrt(vh) + 1e-8F);
			}
			
			if(gen % 10 == 0)
			{
				int slot;
				if(hall.size() < HALL_SIZE)
				{
					hall.add(theta.clone());
					slot = hall.size() - 1;
				}else
				{
					// replace the one it beats most easily
					slot = 1;
					for(int i = 2; i < hall.size(); i++)
						if(weight[k + i] < weight[k + slot])
							slot = i;
					hall.set(slot, theta.clone());
				}
				wins[k + slot] = games[k + slot] = 0;
			}
			
			if(gen % BENCH_EVERY == 0 || gen == gens)
			{
				double[] rates = benchmarkRates(sizes, theta,
					SCRIPTED ? init : REFERENCE, BENCH_GAMES, gen);
				// self-play picks its best by what it was trained against:
				// the old net and its own recent versions
				double score = SCRIPTED ? mean(rates)
					: selfPlayScore(sizes, theta, hall, rates, gen);
				if(score > bestScore)
				{
					bestScore = score;
					best = theta.clone();
					bestRates = rates;
				}
				double secs = (System.nanoTime() - start) / 1e9;
				System.out.printf(
					"gen %4d  fitness %+.3f  vs %s  score %.3f%s  %,d fights, %.0f fights/s%n",
					gen, mean(fit), fmt(rates), score,
					score == bestScore ? " *" : "", fights, fights / secs);
			}else if(gen % 10 == 0)
				System.out.printf(
					"gen %4d  fitness %+.3f  %,d fights, %.0f fights/s%n", gen,
					mean(fit), fights,
					fights / ((System.nanoTime() - start) / 1e9));
		}
		return new Result(sizes, best, init, bestScore, bestRates);
	}
	
	static double selfPlayScore(int[] sizes, float[] theta, List<float[]> hall,
		double[] rates, int gen) throws Exception
	{
		Spec me = netSpec(sizes, theta);
		double sum = 0;
		int count = 0;
		// the last 6 hall entries (the first is the random start)
		for(int i = Math.max(1, hall.size() - 6); i < hall.size(); i++)
		{
			sum += winRate(me, netSpec(sizes, hall.get(i)), 100, gen * 7L + i);
			count++;
		}
		double vsHall = count == 0 ? 0.5 : sum / count;
		if(REFERENCE == null)
			return vsHall;
		if(SCRIPTED_SHARE <= 0)
			return (vsHall + rates[rates.length - 1]) / 2;
		// it trains against the scripted players too, so they count
		double scripted = 0;
		for(int i = 0; i < rates.length - 1; i++)
			scripted += rates[i] / (rates.length - 1);
		return (vsHall + rates[rates.length - 1] + scripted) / 3;
	}
	
	/**
	 * Guided ES (Maheswaranathan et al. 2019): half of each perturbation's
	 * variance goes along the last GUIDE_K steps the search took. In 11,000+
	 * dimensions most random directions don't matter; the recent steps' do,
	 * so every fight says more about the ones that do. The same size
	 * overall (|eps|^2 = n on average) and still evaluated by fighting, so a
	 * bad guide costs nothing but speed. {@code -Dnn.guideK=0} = plain ES.
	 */
	static final int GUIDE_K = Integer.getInteger("nn.guideK", 8);
	static final double GUIDE_ALPHA = 0.5;
	
	static float[] noise(long genSeed, int pair, int n, float[][] basis)
	{
		Random r = new Random(genSeed ^ (pair * 0x9E3779B97F4A7C15L));
		float[] eps = new float[n];
		for(int j = 0; j < n; j++)
			eps[j] = (float)r.nextGaussian();
		if(basis == null)
			return eps;
		float a = (float)Math.sqrt(GUIDE_ALPHA);
		float b = (float)Math.sqrt((1 - GUIDE_ALPHA) * n / basis.length);
		for(int j = 0; j < n; j++)
			eps[j] *= a;
		for(float[] u : basis)
		{
			float w = (float)r.nextGaussian() * b;
			for(int j = 0; j < n; j++)
				eps[j] += w * u[j];
		}
		return eps;
	}
	
	/** An orthonormal basis of the first {@code count} vectors (or null). */
	static float[][] orthonormal(float[][] vectors, int count, int n)
	{
		List<double[]> basis = new ArrayList<>();
		for(int q = 0; q < count; q++)
		{
			double[] v = new double[n];
			for(int j = 0; j < n; j++)
				v[j] = vectors[q][j];
			for(double[] u : basis)
			{
				double dot = 0;
				for(int j = 0; j < n; j++)
					dot += v[j] * u[j];
				for(int j = 0; j < n; j++)
					v[j] -= dot * u[j];
			}
			double norm = 0;
			for(double x : v)
				norm += x * x;
			norm = Math.sqrt(norm);
			if(norm < 1e-9)
				continue;
			for(int j = 0; j < n; j++)
				v[j] /= norm;
			basis.add(v);
		}
		if(basis.isEmpty())
			return null;
		float[][] out = new float[basis.size()][n];
		for(int q = 0; q < out.length; q++)
			for(int j = 0; j < n; j++)
				out[q][j] = (float)basis.get(q)[j];
		return out;
	}
	
	static double[] centeredRanks(double[] x)
	{
		Integer[] idx = new Integer[x.length];
		for(int i = 0; i < idx.length; i++)
			idx[i] = i;
		Arrays.sort(idx, (p, q) -> Double.compare(x[p], x[q]));
		double[] r = new double[x.length];
		for(int i = 0; i < idx.length; i++)
			r[idx[i]] = i / (double)(x.length - 1) - 0.5;
		return r;
	}
	
	/**
	 * One fight's worth to the candidate (fighter A): damage traded, the
	 * win, and the sword PvP tactics - sprint hits, crits, combos held,
	 * combos taken - plus agreeing with the teacher early on.
	 */
	/**
	 * Who plays the brute this generation: the net before the noise, as
	 * skilled as the candidate but not the candidate itself - played by the
	 * candidate, every improvement also made its brute stronger and was
	 * punished for it (null: the candidate).
	 */
	static volatile float[] BRUTE;
	
	static double fitness(int[] sizes, float[] cand, Spec opp, long seed,
		double prior)
	{
		NetPolicy mine = NetPolicy.take(sizes, cand);
		Shadowed shadowed = prior > 0 ? new Shadowed(mine) : null;
		Policy[] players = new Policy[isFfa(seed) ? FFA_PLAYERS : 2];
		RuleShadow rules = new RuleShadow(shadowed != null ? shadowed : mine);
		players[0] = rules;
		// now and then one of them is far stronger (PvpSim.makeBrute): fights
		// it should rather run from (pearl out, eat, come back); it plays
		// as well as the candidate itself (user, 2026-10-26)
		SplittableRandom r = new SplittableRandom(seed ^ 0x5EEDL);
		int brute = r.nextDouble() < NETHERITE_SHARE
			? 1 + r.nextInt(players.length - 1) : -1;
		for(int i = 1; i < players.length; i++)
			players[i] =
				i == brute ? NetPolicy.take(sizes, BRUTE != null ? BRUTE : cand)
					: opp.create();
		PvpSim sim = new PvpSim(seed, players, MAX_TICKS);
		sim.a.onlyGoodHits = true;
		for(int i = 1; i < players.length; i++)
			sim.fighters[i].onlyGoodHits =
				i == brute || opp.isNet() && !opp.copy();
		// an arena: flat a third of the time, else hills, walls, pits
		sim.useTerrain(Terrain.random(new SplittableRandom(seed ^ 0x7E44L)));
		if(brute > 0)
			sim.makeBrute(sim.fighters[brute]);
		int result = sim.run();
		double score = sim.score(result);
		// the nets back to this thread's idle ones, for its next fight
		NetPolicy.give(mine);
		for(int i = 1; i < players.length; i++)
			if(players[i] instanceof NetPolicy np)
				NetPolicy.give(np);
		if(shadowed != null && shadowed.total > 0)
			score += prior * shadowed.agree / (double)shadowed.total;
		// only in a win: up to 6.5 points a fight with most fights drawn,
		// raising the shield and throwing pearls whenever the rule said so
		// outweighed winning and it slid from 84% to 27% against
		// SprintCombo (2026-10-26)
		return score + (result > 0 ? rules.bonus() : 0);
	}
	
	/**
	 * Keeps the item skills the user asked for (2026-10-25) from fading -
	 * they come up a few times a fight, too rarely for the noise of evolution
	 * to keep them on its own: how often, when ItemTeacher's rule says so,
	 * the network washes a web away mid-air, pots up and raises its shield.
	 */
	static final class RuleShadow implements Policy
	{
		/**
		 * water, pot, shield, pearl, wind charge (squashed candy units; a win
		 * is 10).
		 */
		static final double[] WEIGHT = {1, 2, 1, 1.5, 1};
		final Policy main;
		final NeuralAction rule = new NeuralAction();
		final int[] fired = new int[WEIGHT.length],
			agreed = new int[WEIGHT.length];
		
		RuleShadow(Policy main)
		{
			this.main = main;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			main.reset(random);
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			main.act(me, o, out);
			rule.clear();
			ItemTeacher.items(o, rule);
			count(0, rule.water, out.water);
			count(1, rule.pot, out.pot);
			count(2, rule.shield, out.shield);
			count(3, rule.pearlIn || rule.pearlOut,
				rule.pearlIn && out.pearlIn || rule.pearlOut && out.pearlOut);
			count(4, rule.windJump || rule.windHit,
				rule.windJump && out.windJump || rule.windHit && out.windHit);
		}
		
		private void count(int i, boolean should, boolean did)
		{
			if(!should)
				return;
			fired[i]++;
			if(did)
				agreed[i]++;
		}
		
		double bonus()
		{
			double b = 0;
			for(int i = 0; i < WEIGHT.length; i++)
				if(fired[i] > 0)
					b += WEIGHT[i] * agreed[i] / fired[i];
			return b;
		}
	}
	
	/** Counts how often the network does what the teacher would. */
	static final class Shadowed implements Policy
	{
		final Policy main;
		final Policy teacher = new ScriptedPolicies.SprintCombo();
		final NeuralAction teach = new NeuralAction();
		int agree, total;
		
		Shadowed(Policy main)
		{
			this.main = main;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			main.reset(random.split());
			teacher.reset(random.split());
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			main.act(me, o, out);
			teacher.act(me, o, teach);
			total += 3;
			if(out.forward == teach.forward)
				agree++;
			if(out.sprint == teach.sprint)
				agree++;
			if(out.attack == teach.attack)
				agree++;
		}
	}
	
	/**
	 * A network fighter, adapting during the fight like in game
	 * ({@link FightAdapter}; {@code -Dnn.adapt=false} turns that off).
	 */
	static final class NetPolicy implements PvpSim.Batchable
	{
		/**
		 * Per thread: idle policies by their weights (the same array), reused
		 * fight after fight. Building them anew - the history ring, the
		 * adapter's copy of the weights - was 90% of the trainer's
		 * allocation (2026-10-30). take() hands each one out once, give()
		 * takes it back after the fight.
		 */
		private static final ThreadLocal<IdentityHashMap<float[], ArrayDeque<NetPolicy>>> FREE =
			ThreadLocal.withInitial(IdentityHashMap::new);
		
		final OnlineLearner.NetPolicy policy;
		final float[] params;
		
		NetPolicy(NeuralNet net)
		{
			this(net.getSizes(), net.getParams());
		}
		
		private NetPolicy(int[] sizes, float[] params)
		{
			this.params = params;
			policy = new OnlineLearner.NetPolicy(sizes, params, ADAPT);
		}
		
		/** One of these weights, as if new (reset() comes with the fight). */
		static NetPolicy take(int[] sizes, float[] params)
		{
			ArrayDeque<NetPolicy> idle = FREE.get().get(params);
			NetPolicy p = idle != null ? idle.poll() : null;
			if(p == null)
				return new NetPolicy(sizes, params);
			p.policy.getAdapter().clearOffset();
			return p;
		}
		
		/** Back for the next fight (every new candidate's weights are new). */
		static void give(NetPolicy p)
		{
			IdentityHashMap<float[], ArrayDeque<NetPolicy>> free = FREE.get();
			if(free.size() >= 64 && !free.containsKey(p.params))
				free.clear();
			free.computeIfAbsent(p.params, k -> new ArrayDeque<>()).add(p);
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			policy.reset(random);
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			policy.act(me, o, out);
		}
		
		@Override
		public float[] prepare(SimFighter me, NeuralObservation o)
		{
			return policy.prepare(me, o);
		}
		
		@Override
		public NeuralNet batchNet()
		{
			return policy.batchNet();
		}
		
		@Override
		public void finish(SimFighter me, NeuralObservation o, NeuralAction out,
			float[] output)
		{
			policy.finish(me, o, out, output);
		}
	}
	
	// ── benchmarks ───────────────────────────────────────────────────────
	
	/** Win rate (a draw counts half) against each scripted player and vs. */
	static double[] benchmarkRates(int[] sizes, float[] params, float[] vs,
		int games, long seed) throws Exception
	{
		int k = ScriptedPolicies.ALL.length;
		double[] rates = new double[vs == null ? k : k + 1];
		Spec me = netSpec(sizes, params);
		for(int i = 0; i < k; i++)
			rates[i] =
				winRate(me, new Spec(i, null, null), games, seed * 100 + i);
		if(vs != null)
			rates[k] = winRate(me, netSpec(sizes, vs), games, seed * 100 + k);
		return rates;
	}
	
	static double winRate(Spec me, Spec opp, int games, long seed)
		throws Exception
	{
		return winRate(me, opp::create, opp.isNet(), games, seed);
	}
	
	/** Against any policy (ECHO); oppNet: only sprint hits and crits. */
	static double winRate(Spec me, Supplier<Policy> opp, boolean oppNet,
		int games, long seed) throws Exception
	{
		List<Future<Double>> jobs = new ArrayList<>();
		for(int g = 0; g < games; g++)
		{
			final long s = seed * 100_000 + g;
			jobs.add(POOL.submit(() -> {
				PvpSim sim = new PvpSim(s, me.create(), opp.get(), MAX_TICKS);
				sim.a.onlyGoodHits = true;
				sim.b.onlyGoodHits = oppNet;
				sim.useTerrain(
					Terrain.random(new SplittableRandom(s ^ 0x7E44L)));
				int r = sim.run();
				r = sim.outcome(r);
				return r > 0 ? 1.0 : r < 0 ? 0.0 : 0.5;
			}));
		}
		double sum = 0;
		for(Future<Double> f : jobs)
			sum += f.get();
		return sum / games;
	}
	
	static String fmt(double[] rates)
	{
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < rates.length; i++)
		{
			String name =
				i < ScriptedPolicies.NAMES.length ? ScriptedPolicies.NAMES[i]
					: SCRIPTED ? "Imitation" : REFERENCE_NAME;
			sb.append(String.format("%s %.0f%% ", name, rates[i] * 100));
		}
		return sb.toString().trim();
	}
	
	static double mean(double[] x)
	{
		double s = 0;
		for(double d : x)
			s += d;
		return s / x.length;
	}
}
