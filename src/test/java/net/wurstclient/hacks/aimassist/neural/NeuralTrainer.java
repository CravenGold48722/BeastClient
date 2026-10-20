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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
	static final int MAX_TICKS = 600;
	static final int THREADS =
		Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
	
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
				int[] sizes = parseSizes(args.length > 1 ? args[1] : "26,8,10");
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
				BENCH_EVERY = 100;
				BENCH_GAMES = 400;
				PRIOR = 0;
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
					if(r == 0)
						r = Float.compare(sim.a.health, sim.b.health);
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
		try(OutputStream os = Files.newOutputStream(out))
		{
			new NeuralNet(sizes, params).write(os);
		}
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
		int[][] shapes = {{26, 8, 10}, {26, 16, 10}, {26, 32, 10},
			{26, 16, 8, 10}, {26, 24, 12, 10}, {26, 32, 16, 10}};
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
					(byte)(teach.attack ? 1 : 0)});
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
			
			Backprop.train(sizes, params, data, 6, 0.003F, new Random(round));
			System.out.printf("DAgger round %d (beta %.2f): %d samples, %s%n",
				round, beta, data.size, Backprop.accuracy(sizes, params, data));
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
	static float SIGMA = 0.03F;
	static float LEARNING_RATE = 0.01F;
	static double PRIOR = 0.15;
	static int BENCH_EVERY = 50;
	/** Experiments: train against one scripted player only. */
	static final int ONLY = Integer.getInteger("nn.only", -1);
	static int BENCH_GAMES = 200;
	static final float WEIGHT_DECAY = 0.001F;
	static final int HALL_SIZE = 30;
	
	/** An opponent: a scripted player (index) or a network (params). */
	record Spec(int scripted, int[] sizes, float[] params)
	{
		Policy create()
		{
			return params == null ? ScriptedPolicies.ALL[scripted].get()
				: new NetPolicy(new NeuralNet(sizes, params));
		}
		
		boolean isNet()
		{
			return params != null;
		}
	}
	
	static Spec netSpec(int[] sizes, float[] params)
	{
		return new Spec(-1, sizes, params);
	}
	
	static Result evolve(int[] sizes, float[] init, int gens, boolean verbose)
		throws Exception
	{
		int n = init.length;
		int k = ScriptedPolicies.ALL.length;
		float[] theta = init.clone();
		float[] m = new float[n], v = new float[n];
		
		// the league: scripted players first, then earlier versions of itself
		// (the imitation net stays in for good)
		List<float[]> hall = new ArrayList<>();
		hall.add(init.clone());
		double[] wins = new double[k + HALL_SIZE];
		double[] games = new double[k + HALL_SIZE];
		
		float[] best = theta.clone();
		double bestScore = -1;
		double[] bestRates = null;
		long fights = 0;
		long start = System.nanoTime();
		Random rnd = new Random(1234);
		
		for(int gen = 1; gen <= gens; gen++)
		{
			long genSeed = rnd.nextLong();
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
			
			List<Future<double[]>> jobs = new ArrayList<>(PAIRS * 2);
			for(int i = 0; i < PAIRS; i++)
				for(int sign = 1; sign >= -1; sign -= 2)
				{
					final int pair = i, sg = sign;
					jobs.add(POOL.submit(() -> {
						float[] cand = theta.clone();
						float[] eps = noise(genSeed, pair, n);
						for(int j = 0; j < n; j++)
							cand[j] += sg * SIGMA * eps[j];
						double[] scores = new double[GAMES_PER_EVAL];
						for(int g = 0; g < GAMES_PER_EVAL; g++)
							scores[g] = fitness(sizes, cand, opps[pair][g],
								genSeed * 31 + pair * 1009L + g, prior);
						return scores;
					}));
				}
			double[] fit = new double[PAIRS * 2];
			for(int i = 0; i < fit.length; i++)
			{
				double[] scores = jobs.get(i).get();
				for(int g = 0; g < GAMES_PER_EVAL; g++)
				{
					fit[i] += scores[g] / GAMES_PER_EVAL;
					int idx = oppIndex[i / 2][g];
					if(idx < 0)
						continue;
					// decaying counts: what it can beat now, not long ago
					games[idx] = games[idx] * 0.995 + 1;
					wins[idx] = wins[idx] * 0.995 + (scores[g] > 0 ? 1 : 0);
				}
			}
			fights += (long)PAIRS * 2 * GAMES_PER_EVAL;
			
			// rank-based fitness: robust to outliers and scale
			double[] rank = centeredRanks(fit);
			float[] grad = new float[n];
			for(int i = 0; i < PAIRS; i++)
			{
				double w = rank[2 * i] - rank[2 * i + 1];
				if(w == 0)
					continue;
				float[] eps = noise(genSeed, i, n);
				for(int j = 0; j < n; j++)
					grad[j] += (float)(w * eps[j]);
			}
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
				double[] rates =
					benchmarkRates(sizes, theta, init, BENCH_GAMES, gen);
				double score = mean(rates);
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
			}else if(verbose && gen % 10 == 0)
				System.out.printf("gen %4d  fitness %+.3f%n", gen, mean(fit));
		}
		return new Result(sizes, best, init, bestScore, bestRates);
	}
	
	static float[] noise(long genSeed, int pair, int n)
	{
		Random r = new Random(genSeed ^ (pair * 0x9E3779B97F4A7C15L));
		float[] eps = new float[n];
		for(int j = 0; j < n; j++)
			eps[j] = (float)r.nextGaussian();
		return eps;
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
	static double fitness(int[] sizes, float[] cand, Spec opp, long seed,
		double prior)
	{
		NetPolicy mine = new NetPolicy(new NeuralNet(sizes, cand));
		Shadowed shadowed = prior > 0 ? new Shadowed(mine) : null;
		PvpSim sim = new PvpSim(seed, shadowed != null ? shadowed : mine,
			opp.create(), MAX_TICKS);
		sim.a.onlyGoodHits = true;
		sim.b.onlyGoodHits = opp.isNet();
		int result = sim.run();
		
		SimFighter a = sim.a, b = sim.b;
		double score = (a.damageDealt - b.damageDealt) / 20 + result;
		score += 0.01 * a.sprintHits + 0.02 * a.crits;
		score += 0.03 * Math.max(0, a.maxCombo - 1)
			- 0.03 * Math.max(0, b.maxCombo - 1);
		if(shadowed != null && shadowed.total > 0)
			score += prior * shadowed.agree / (double)shadowed.total;
		return score;
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
	
	static final class NetPolicy implements Policy
	{
		final NeuralNet net;
		final float[] in = new float[NeuralObservation.SIZE];
		
		NetPolicy(NeuralNet net)
		{
			this.net = net;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			o.encode(in);
			out.decode(net.forward(in));
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
		List<Future<Double>> jobs = new ArrayList<>();
		for(int g = 0; g < games; g++)
		{
			final long s = seed * 100_000 + g;
			jobs.add(POOL.submit(() -> {
				PvpSim sim =
					new PvpSim(s, me.create(), opp.create(), MAX_TICKS);
				sim.a.onlyGoodHits = true;
				sim.b.onlyGoodHits = opp.isNet();
				int r = sim.run();
				if(r == 0)
					r = Float.compare(sim.a.health, sim.b.health);
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
			String name = i < ScriptedPolicies.NAMES.length
				? ScriptedPolicies.NAMES[i] : "Imitation";
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
