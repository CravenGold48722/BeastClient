/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Random;
import java.util.Arrays;
import java.util.List;

/**
 * Self-play training that runs until it's told to stop: {@code train.cmd}
 * and the nightly task start it, {@code stoptrain.cmd} stops it.
 *
 * <p>
 * It picks up where the game left off - the general net AimAssist plays
 * with ({@code .minecraft/wurst/neural/aimassist_learned.bin}, which the
 * in-game learner writes too) - and trains it against nothing but itself:
 * its current self and a league of its earlier versions (kept between runs
 * in {@code trainer/hall.bin}). Every round (~300 generations, about a
 * minute) the round's best net plays the champion in 4,000 fresh fights; it
 * becomes the new champion, and what the game plays with, only if it wins
 * 53%+ of them.
 *
 * <p>
 * No teacher. The scripted fighters are only a yardstick, played after
 * each round. If the yardstick collapses (doing something TERRIBLY wrong:
 * 15 points under the champion's on average or 30 against any of them),
 * the round is thrown away and the next round has the
 * scripted fighters in its league again - the only guidance left. A net
 * that beats the champion but whose yardstick average is 5+ points below
 * the champion's isn't promoted (it beat itself by forgetting how to fight
 * anyone else); training goes on from it all the same.
 *
 * <p>
 * Stops when {@code trainer/stop} appears or at the given time of day,
 * finishing the current generation and round check first (a second or
 * two). One trainer at a time ({@code trainer/lock}).
 */
final class ContinuousTrainer
{
	static final int ROUND_GENS = Integer.getInteger("nn.round", 300);
	static final int DUEL_GAMES = 4000;
	static final double PROMOTE = 0.53;
	/** On stoptrain: at least as good as the champion is installed. */
	static final double STOP_PROMOTE = 0.5;
	/**
	 * Passing PROMOTE / STOP_PROMOTE only earns a second duel on this many
	 * fresh fights: the first 4000 are the ones it passed by, and at 50% they
	 * are a coin flip (user, 2026-11-01: a new version must not come from
	 * luck).
	 */
	static final int CONFIRM_GAMES = 16000;
	/**
	 * Standard errors above 50% the second duel needs (50.8% of 16000): a
	 * candidate no better than the champion passes it 2.3% of the time.
	 */
	static final double CONFIRM_Z = 2;
	/** Below this against the champion, the next round starts over from it. */
	static final double KEEP = 0.47;
	/** This far below the champion's yardstick: on average, any one. */
	static final double TERRIBLE_MEAN = 0.15, TERRIBLE_ONE = 0.3;
	/**
	 * Not promoted if its yardstick average is this far below the
	 * champion's: beating itself by forgetting how to fight anyone else.
	 */
	static final double FORGET = 0.05;
	
	private static final DateTimeFormatter TIME =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	
	private ContinuousTrainer()
	{}
	
	/** args: forever &lt;neural folder&gt; [stop at HH:mm] */
	static void run(String[] args) throws Exception
	{
		// half the fighters with a varied kit (PvpSim.varyKit)
		PvpSim.kitVariety =
			Double.parseDouble(System.getProperty("nn.kitVariety", "0.5"));
		Path folder = Path.of(args[1]);
		Path dir = folder.resolve("trainer");
		Files.createDirectories(dir);
		Path stopFile = dir.resolve("stop");
		
		try(FileChannel ch = FileChannel.open(dir.resolve("lock"),
			StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock lock = ch.tryLock())
		{
			if(lock == null)
			{
				System.out.println("Already training.");
				return;
			}
			Files.deleteIfExists(stopFile);
			Path log = dir.resolve("train.log");
			if(Files.exists(log) && Files.size(log) > 5_000_000)
				Files.move(log, dir.resolve("train.old.log"),
					StandardCopyOption.REPLACE_EXISTING);
			PrintStream console = System.out;
			try(OutputStream file = new FileOutputStream(log.toFile(), true))
			{
				System.setOut(new PrintStream(new Tee(console, file), true));
				Path pid = dir.resolve("pid");
				Files.writeString(pid,
					Long.toString(ProcessHandle.current().pid()));
				try
				{
					LocalDateTime until = args.length > 2
						? untilTime(LocalTime.parse(args[2])) : null;
					NeuralTrainer.STOP = () -> Files.exists(stopFile)
						|| until != null && LocalDateTime.now().isAfter(until);
					train(folder, dir, until);
				}catch(Throwable e)
				{
					// javaw has no console: the log is the only place to see it
					log("Crashed: %s", e);
					e.printStackTrace(System.out);
					throw e;
				}finally
				{
					Files.deleteIfExists(pid);
					Files.deleteIfExists(stopFile);
					System.setOut(console);
				}
			}
		}
	}
	
	private static LocalDateTime untilTime(LocalTime t)
	{
		LocalDateTime at = LocalDateTime.now().with(t);
		return at.isAfter(LocalDateTime.now()) ? at : at.plusDays(1);
	}
	
	private static void train(Path folder, Path dir, LocalDateTime until)
		throws Exception
	{
		float[] base = baseNet(dir);
		int[] sizes = NeuralPolicy.get() != null ? NeuralPolicy.get().getSizes()
			: BOOTSTRAP_SIZES;
		int shippedHash = Arrays.hashCode(base);
		Path learned = folder.resolve("aimassist_learned.bin");
		
		Champion champ = readChampion(learned, sizes, shippedHash);
		if(champ == null)
			champ = new Champion(base, 0);
		List<float[]> hall = readHall(dir.resolve("hall.bin"), sizes);
		if(hall.isEmpty())
			hall.add(champ.params.clone());
		// a net kept in the league for good (slot 0, which is never
		// replaced): v1, the best in enchanted gear so far - so that skill
		// stays something to beat (user, 2026-10-31)
		try(InputStream in = Files.newInputStream(dir.resolve("pinned.bin")))
		{
			NeuralNet pinned = NeuralNet.readAnyVersion(in);
			if(Arrays.equals(pinned.getSizes(), sizes))
				hall.set(0, pinned.getParams());
		}catch(IOException e)
		{
			// none
		}
		
		log("Training started (%d threads%s, %s), champion v%d, league of %d%s",
			NeuralTrainer.THREADS,
			NeuralTrainer.DUTY < 1 ? String.format(", lite: %.0f%% of the time",
				NeuralTrainer.DUTY * 100) : "",
			Arrays.toString(sizes), champ.version, hall.size(),
			until != null ? ", until " + until.toLocalTime() : "");
		
		// (train.ps1 passes its own: 512 x 12 full, 16 x 12 lite)
		NeuralTrainer.PAIRS = Integer.getInteger("nn.pairs", 128);
		NeuralTrainer.GAMES_PER_EVAL = Integer.getInteger("nn.games", 24);
		NeuralTrainer.SIGMA =
			Float.parseFloat(System.getProperty("nn.sigma", "0.1"));
		// 0.01 suits the old 3,232 weights; a bigger net's noisier gradient
		// estimate (same pairs) takes smaller steps, or it drifts
		NeuralTrainer.LEARNING_RATE = Float.parseFloat(
			System.getProperty("nn.lr", String.valueOf((float)(0.01 * Math
				.min(1, Math.sqrt(3232.0 / NeuralNet.paramCount(sizes)))))));
		NeuralTrainer.BENCH_EVERY = 100;
		NeuralTrainer.BENCH_GAMES = 400;
		NeuralTrainer.PRIOR = 0;
		
		float[] theta = champ.params.clone();
		// the net in training is saved after every round, so a stop, a
		// crash or a reboot loses at most one round
		Path checkpoint = dir
			.resolve("checkpoint_" + Integer.toHexString(shippedHash) + ".bin");
		try(InputStream in = Files.newInputStream(checkpoint))
		{
			NeuralNet net = NeuralNet.read(in);
			if(Arrays.equals(net.getSizes(), sizes))
			{
				theta = net.getParams();
				log("Continuing from the checkpoint of the last run");
			}
		}catch(IOException e)
		{
			// none yet
		}
		double[] champRates = NeuralTrainer.benchmarkRates(sizes, champ.params,
			null, 400, 0x7A2DL);
		double champYard = NeuralTrainer.mean(champRates);
		boolean guided = false;
		long baseSeed = System.nanoTime();
		long fights = 0, t0 = System.nanoTime();
		long ticks0 = PvpSim.SIMULATED_TICKS.sum();
		boolean beaten = false;
		for(int round = 1; !NeuralTrainer.STOP.getAsBoolean(); round++)
		{
			NeuralTrainer.SCRIPTED = guided;
			NeuralTrainer.REFERENCE = champ.params;
			NeuralTrainer.REFERENCE_NAME = "Champion v" + champ.version;
			NeuralTrainer.SEED = baseSeed + round * 0x9E3779B97F4A7C15L;
			NeuralTrainer.Result r = NeuralTrainer.evolve(sizes,
				learnFromFights(folder, sizes, theta, NeuralTrainer.SEED),
				ROUND_GENS, false, hall);
			fights += (long)NeuralTrainer.GENS_RUN * NeuralTrainer.PAIRS * 2
				* NeuralTrainer.GAMES_PER_EVAL;
			float[] cand = r.params();
			NeuralTrainer.dutyPause();
			// stoptrain: the last (part of a) round is checked right away and
			// installed if it's at least as good as the champion, so the game
			// has the newest training at its next start
			boolean stopping = NeuralTrainer.STOP.getAsBoolean();
			if(stopping && Arrays.equals(cand, champ.params))
				break;
			double promote = stopping ? STOP_PROMOTE : PROMOTE;
			
			long seed = NeuralTrainer.SEED ^ 0x5EEDL;
			double[] duelRates = duel(sizes, cand, champ.params, seed);
			double vsChamp = duelRates[0];
			// the yardstick uses the same fights for every net
			NeuralTrainer.dutyPause();
			double[] yard =
				NeuralTrainer.benchmarkRates(sizes, cand, null, 400, 0x7A2DL);
			NeuralTrainer.dutyPause();
			fights += DUEL_GAMES + 400L * yard.length;
			// measured from the champion's own: timeouts go by the trade
			// since 2026-10-26, and on it even the champion is ~45% against
			// SprintCombo
			boolean terrible =
				NeuralTrainer.mean(yard) < champYard - TERRIBLE_MEAN;
			for(int i = 0; i < yard.length; i++)
				terrible |= yard[i] < champRates[i] - TERRIBLE_ONE;
			double secs = (System.nanoTime() - t0) / 1e9;
			long ticks = PvpSim.SIMULATED_TICKS.sum() - ticks0;
			log("Round %d: vs champion v%d %.1f%% (plain %.1f%%, enchanted"
				+ " %.1f%%), yardstick %s%s, wild"
				+ " opponents %d (%d won at first, one unbeaten: %s)"
				+ " (%,.0f fights/s; %s of fighting so far, %s)", round,
				champ.version, vsChamp * 100, duelRates[1] * 100,
				duelRates[2] * 100, NeuralTrainer.fmt(yard),
				guided ? ", guided" : "", NeuralTrainer.WILDS,
				NeuralTrainer.WILDS_WON,
				NeuralTrainer.WILD != null ? "yes" : "no", fights / secs,
				NeuralTrainer.simTime(ticks),
				NeuralTrainer.simTimePerHour(ticks / secs));
			
			if(terrible)
			{
				// the only guidance: back to the champion, and the scripted
				// fighters in the league until it's fine again
				log("  TERRIBLY wrong: thrown away, next round with the"
					+ " scripted fighters");
				guided = true;
				theta = champ.params.clone();
			}else
			{
				guided = false;
				double candYard = NeuralTrainer.mean(yard);
				// no trading one kind of fight for another: v2 tied v1
				// overall but lost 41% of the enchanted ones (2026-10-31)
				boolean slid =
					Math.min(duelRates[1], duelRates[2]) < GROUP_FLOOR;
				if(vsChamp >= promote && candYard < champYard - FORGET)
					log("  not promoted: yardstick %.0f%% vs the champion's %.0f%%",
						candYard * 100, champYard * 100);
				else if(vsChamp >= promote && slid)
					log("  not promoted: plain %.1f%%, enchanted %.1f%% (each"
						+ " needs %.0f%%)", duelRates[1] * 100,
						duelRates[2] * 100, GROUP_FLOOR * 100);
				else if(vsChamp >= promote
					&& confirm(sizes, cand, champ.params, seed + 4, stopping))
				{
					// the game may have learned something meanwhile
					Champion onDisk = readChampion(learned, sizes, shippedHash);
					if(onDisk != null
						&& !Arrays.equals(onDisk.params, champ.params)
						&& duel(sizes, cand, onDisk.params,
							seed + 2)[0] < promote)
					{
						log("  the game's net (v%d) is as good: going on from it",
							onDisk.version);
						champ = onDisk;
						champRates = NeuralTrainer.benchmarkRates(sizes,
							champ.params, null, 400, 0x7A2DL);
						champYard = NeuralTrainer.mean(champRates);
						theta = champ.params.clone();
						continue;
					}
					int version = Math.max(champ.version,
						onDisk != null ? onDisk.version : 0) + 1;
					champ = new Champion(cand.clone(), version);
					champYard = candYard;
					champRates = yard;
					writeChampion(learned, champ, sizes, shippedHash);
					log("  new champion v%d (AimAssist takes it at the next"
						+ " game start or after its next fight)", version);
					beaten = Boolean.getBoolean("nn.untilBeaten");
				}
				theta = vsChamp >= KEEP ? cand.clone() : champ.params.clone();
			}
			writeHall(dir.resolve("hall.bin"), hall, sizes);
			NeuralTrainer.save(theta, sizes, checkpoint);
			deleteOtherCheckpoints(dir, checkpoint);
			log("  items per fight: %s", ItemTeacher.usage(sizes, theta));
			if(beaten)
				break;
		}
		log("Training stopped, champion v%d", champ.version);
	}
	
	/** Samples a real player needs before a copy of them is made. */
	static final int COPY_MIN = 500;
	/** At most this many copies in the league (the most watched). */
	static final int COPIES_MAX = 16;
	/** Samples kept per copy and for the imitation (the newest). */
	static final int SAMPLES_MAX = 20_000;
	/** The imitation's step: a nudge, the evolution stays the teacher. */
	static final float IMITATE_LR = 0.0005F;
	/**
	 * ECHO's advantage weighting (newgen6's config): what the next 10 ticks
	 * brought - damage dealt minus 1.75x damage taken - through e^(a / 2),
	 * capped at 30.
	 */
	static final int ADV_TICKS = 10;
	static final double ADV_TAKEN = 1.75, ADV_TEMPERATURE = 2, ADV_CAP = 30;
	
	/**
	 * The fights recorded in game (FightLog, AimAssist's "Record fights"):
	 * copies of the players fought join the league (NeuralTrainer.REAL), and
	 * the moves that paid off - yours where you played, theirs always - nudge
	 * the net toward them (ECHO's advantage-weighted imitation of real
	 * players). The evolution stays the main teacher, and a champion is still
	 * only one that beats the last. Returns the net to train from (theta
	 * itself if there are no fights; never changes theta).
	 */
	static float[] learnFromFights(Path folder, int[] sizes, float[] theta,
		long seed)
	{
		List<FightLog.Fight> fights =
			FightLog.readAll(folder.resolve("fights"));
		if(fights.isEmpty())
		{
			NeuralTrainer.REAL = List.of();
			return theta;
		}
		Random rnd = new Random(seed);
		java.util.Map<java.util.UUID, List<FightLog.Row>> byPlayer =
			new java.util.LinkedHashMap<>();
		List<float[]> xs = new ArrayList<>();
		List<byte[]> ys = new ArrayList<>();
		List<Float> ws = new ArrayList<>();
		int mine = 0, theirs = 0;
		for(FightLog.Fight f : fights)
		{
			byPlayer.computeIfAbsent(f.opponent, k -> new ArrayList<>())
				.addAll(f.theirs);
			java.util.Map<Integer, FightLog.Row> at = new java.util.HashMap<>();
			for(FightLog.Row r : f.ours)
				at.put(r.tick, r);
			for(FightLog.Row r : f.ours)
				if(r.human)
				{
					xs.add(r.x);
					ys.add(r.y);
					ws.add((float)weight(advantage(at, r.tick, true)));
					mine++;
				}
			for(FightLog.Row r : f.theirs)
			{
				xs.add(r.x);
				ys.add(r.y);
				ws.add((float)weight(advantage(at, r.tick, false)));
				theirs++;
			}
		}
		
		// copies of the players, the most watched first
		List<List<FightLog.Row>> players = new ArrayList<>(byPlayer.values());
		players.sort((a, b) -> b.size() - a.size());
		List<float[]> real = new ArrayList<>();
		for(List<FightLog.Row> rows : players)
		{
			if(rows.size() < COPY_MIN || real.size() >= COPIES_MAX)
				break;
			int from = Math.max(0, rows.size() - SAMPLES_MAX);
			int n = rows.size() - from;
			float[][] x = new float[n][];
			byte[][] y = new byte[n][];
			for(int i = 0; i < n; i++)
			{
				x[i] = rows.get(from + i).x;
				y[i] = rows.get(from + i).y;
			}
			float[] copy = theta.clone();
			Backprop.train(sizes, copy, x, y, n, 4, 0.003F,
				new Random(rnd.nextLong()));
			real.add(copy);
		}
		NeuralTrainer.REAL = List.copyOf(real);
		
		// the imitation: a random SAMPLES_MAX of everything, weights
		// normalized to a mean of 1
		int n = Math.min(xs.size(), SAMPLES_MAX);
		if(n < 200)
		{
			log("Real fights: %d, %d players copied (too few moves to imitate)",
				fights.size(), real.size());
			return theta;
		}
		int[] pick = new int[xs.size()];
		for(int i = 0; i < pick.length; i++)
			pick[i] = i;
		for(int i = pick.length - 1; i > 0; i--)
		{
			int j = rnd.nextInt(i + 1);
			int t = pick[i];
			pick[i] = pick[j];
			pick[j] = t;
		}
		float[][] x = new float[n][];
		byte[][] y = new byte[n][];
		float[] w = new float[n];
		double sum = 0;
		for(int i = 0; i < n; i++)
		{
			x[i] = xs.get(pick[i]);
			y[i] = ys.get(pick[i]);
			w[i] = ws.get(pick[i]);
			sum += w[i];
		}
		for(int i = 0; i < n; i++)
			w[i] *= n / sum;
		float[] nudged = theta.clone();
		Backprop.train(sizes, nudged, x, y, w, n, 1, IMITATE_LR,
			new Random(rnd.nextLong()));
		log("Real fights: %d, %d players copied, imitated %d moves (%d yours,"
			+ " %d theirs in all)", fights.size(), real.size(), n, mine,
			theirs);
		return nudged;
	}
	
	/**
	 * What the next ADV_TICKS brought the side that moved: damage dealt
	 * minus ADV_TAKEN x damage taken (ours: your rows; else theirs).
	 */
	static double advantage(java.util.Map<Integer, FightLog.Row> at, int tick,
		boolean ours)
	{
		FightLog.Row now = at.get(tick);
		if(now == null)
			return 0;
		FightLog.Row later = null;
		for(int t = tick + ADV_TICKS; t > tick && later == null; t--)
			later = at.get(t);
		if(later == null)
			return 0;
		double dealt = later.dealt - now.dealt, taken = later.taken - now.taken;
		return ours ? dealt - ADV_TAKEN * taken : taken - ADV_TAKEN * dealt;
	}
	
	static double weight(double advantage)
	{
		return Math.min(ADV_CAP, Math.exp(advantage / ADV_TEMPERATURE));
	}
	
	static void log(String format, Object... args)
	{
		System.out.println(LocalDateTime.now().format(TIME) + "  "
			+ String.format(format, args));
	}
	
	/** Checkpoints grown from another shipped net are of no use any more. */
	private static void deleteOtherCheckpoints(Path dir, Path keep)
		throws IOException
	{
		try(var files = Files.newDirectoryStream(dir, "checkpoint_*.bin"))
		{
			for(Path f : files)
				if(!f.getFileName().equals(keep.getFileName()))
					Files.deleteIfExists(f);
		}
	}
	
	record Champion(float[] params, int version)
	{}
	
	/** The shape of a network the trainer grows itself. */
	static final int[] BOOTSTRAP_SIZES =
		{NeuralObservation.SIZE, 48, 48, NeuralAction.OUTPUTS};
	
	/**
	 * What training starts from: the network shipped in the jar, or - if
	 * that one was made for other inputs (an older NeuralObservation, the
	 * cause of every failed start from 2026-10-19 on) - a network of its
	 * own, taught Beast's combo by imitation and kept in
	 * {@code trainer/bootstrap.bin} so the next start continues from it.
	 * Never quits for that reason again.
	 */
	static float[] baseNet(Path dir) throws Exception
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		if(shipped != null)
			return shipped.getParams();
		
		Path file = dir.resolve("bootstrap.bin");
		try(InputStream in = Files.newInputStream(file))
		{
			NeuralNet net = NeuralNet.read(in);
			if(Arrays.equals(net.getSizes(), BOOTSTRAP_SIZES))
			{
				log("The network in the jar doesn't fit this version's inputs"
					+ " (NeuralObservation %d) - continuing from the"
					+ " trainer's own (bootstrap.bin)",
					NeuralObservation.VERSION);
				return net.getParams();
			}
		}catch(IOException e)
		{
			// none yet, or for other inputs: grow a new one
		}
		
		log("The network in the jar doesn't fit this version's inputs"
			+ " (NeuralObservation %d) - growing a new one by imitation"
			+ " (a few minutes)", NeuralObservation.VERSION);
		float[] params = NeuralTrainer.imitate(BOOTSTRAP_SIZES, 6);
		NeuralTrainer.save(params, BOOTSTRAP_SIZES, file);
		return params;
	}
	
	/** trainer/bootstrap.bin if it fits this version, else null. */
	static float[] readBootstrap(Path dir)
	{
		try(InputStream in = Files.newInputStream(dir.resolve("bootstrap.bin")))
		{
			NeuralNet net = NeuralNet.read(in);
			if(Arrays.equals(net.getSizes(), BOOTSTRAP_SIZES))
				return net.getParams();
		}catch(IOException e)
		{
			// none yet, or for other inputs
		}
		return null;
	}
	
	/**
	 * {@code export <neural folder> <out>}: writes the champion in the
	 * shipped net's format (scripts/ship-net.ps1 puts it in the jar). Exit
	 * code 2 = no champion grown from the current base yet.
	 */
	static void export(String[] args) throws Exception
	{
		Path folder = Path.of(args[1]);
		NeuralPolicy shipped = NeuralPolicy.get();
		float[] base = shipped != null ? shipped.getParams()
			: readBootstrap(folder.resolve("trainer"));
		int[] sizes = shipped != null ? shipped.getSizes() : BOOTSTRAP_SIZES;
		Champion champ = base == null ? null
			: readChampion(folder.resolve("aimassist_learned.bin"), sizes,
				Arrays.hashCode(base));
		if(champ == null)
		{
			System.out.println("Nothing to ship: no champion yet");
			System.exit(2);
		}
		if(Arrays.equals(champ.params, base))
		{
			System.out.println(
				"Nothing to ship: champion v" + champ.version + " already is");
			System.exit(2);
		}
		NeuralTrainer.save(champ.params, sizes, Path.of(args[2]));
		System.out.println("Exported champion v" + champ.version);
	}
	
	/**
	 * Makes the hidden layers wider and gives the nets the observation's
	 * new inputs, without forgetting anything (args: widen, the neural
	 * folder, the shipped net's file, the width): new neurons get small
	 * random inputs and no say in anything yet (zero weights out), new
	 * inputs zero weights in, so every widened net still plays exactly as
	 * before - training gives them their part. The shipped net, the
	 * training's checkpoint and the league are widened alike, from any
	 * older observation version.
	 */
	static void widen(String[] args) throws Exception
	{
		Path folder = Path.of(args[1]), asset = Path.of(args[2]);
		int width = Integer.parseInt(args[3]);
		Path dir = folder.resolve("trainer");
		NeuralNet shipped;
		try(InputStream in = Files.newInputStream(asset))
		{
			shipped = NeuralNet.readAnyVersion(in);
		}
		int[] from = shipped.getSizes(), to = from.clone();
		to[0] = NeuralObservation.SIZE;
		to[to.length - 1] = NeuralAction.OUTPUTS;
		for(int l = 1; l < to.length - 1; l++)
			to[l] = width;
		float[] base = shipped.getParams();
		Random random = new Random(0x51DE);
		
		float[] checkpoint = null;
		try(InputStream in = Files.newInputStream(dir.resolve("checkpoint_"
			+ Integer.toHexString(Arrays.hashCode(base)) + ".bin")))
		{
			NeuralNet net = NeuralNet.readAnyVersion(in);
			if(Arrays.equals(net.getSizes(), from))
				checkpoint = net.getParams();
		}catch(IOException e)
		{
			// none
		}
		List<float[]> hall = new ArrayList<>();
		try(InputStream in = Files.newInputStream(dir.resolve("hall.bin")))
		{
			DataInputStream din =
				new DataInputStream(new BufferedInputStream(in));
			int n = din.readInt();
			for(int i = 0; i < n; i++)
			{
				NeuralNet net = NeuralNet.readAnyVersion(din);
				if(Arrays.equals(net.getSizes(), from))
					hall.add(net.getParams());
			}
		}catch(IOException e)
		{
			// none
		}
		
		float[] wide = widen(from, to, base, random);
		// narrower only drops neurons that have no say yet: check it
		NeuralNet before = new NeuralNet(from, base),
			after = new NeuralNet(to, wide);
		double worst = 0;
		for(int k = 0; k < 200; k++)
		{
			float[] x = new float[to[0]];
			for(int i = 0; i < x.length; i++)
				x[i] = i < from[0] ? (float)random.nextGaussian() : 0;
			float[] a = before.forward(Arrays.copyOf(x, from[0])).clone();
			float[] b = after.forward(x);
			for(int i = 0; i < a.length; i++)
				worst = Math.max(worst, Math.abs(a[i] - b[i]));
		}
		if(worst > 1e-3)
			throw new IllegalStateException(
				"resizing would change the net (by " + worst + ")");
		NeuralTrainer.save(wide, to, asset);
		if(checkpoint != null)
			NeuralTrainer.save(widen(from, to, checkpoint, random), to,
				dir.resolve("checkpoint_"
					+ Integer.toHexString(Arrays.hashCode(wide)) + ".bin"));
		List<float[]> wideHall = new ArrayList<>();
		for(float[] p : hall)
			wideHall.add(widen(from, to, p, random));
		writeHall(dir.resolve("hall.bin"), wideHall, to);
		// and the league's pinned net
		Path pinnedFile = dir.resolve("pinned.bin");
		try(InputStream in = Files.newInputStream(pinnedFile))
		{
			NeuralNet pinned = NeuralNet.readAnyVersion(in);
			if(Arrays.equals(pinned.getSizes(), from))
				NeuralTrainer.save(widen(from, to, pinned.getParams(), random),
					to, pinnedFile);
		}catch(IOException e)
		{
			// none
		}
		// the champion too, with its number (it was read as v0 after a
		// resize: aimassist_learned.bin no longer fit)
		Path learned = folder.resolve("aimassist_learned.bin");
		String champion = "none";
		try(InputStream in = Files.newInputStream(learned))
		{
			DataInputStream din =
				new DataInputStream(new BufferedInputStream(in));
			din.readInt();
			NeuralNet net = NeuralNet.readAnyVersion(din);
			int version = din.readInt();
			if(Arrays.equals(net.getSizes(), from))
			{
				writeChampion(learned,
					new Champion(widen(from, to, net.getParams(), random),
						version),
					to, Arrays.hashCode(wide));
				champion = "v" + version;
			}
		}catch(IOException e)
		{
			// none
		}
		System.out.println("Widened " + Arrays.toString(from) + " -> "
			+ Arrays.toString(to) + " (checkpoint: " + (checkpoint != null)
			+ ", league: " + hall.size() + ", champion: " + champion + ")");
	}
	
	static float[] widen(int[] from, int[] to, float[] p, Random random)
	{
		float[] q = new float[NeuralNet.paramCount(to)];
		// the attention (after the layers) as it was
		System.arraycopy(p, NeuralNet.layerCount(from), q,
			NeuralNet.layerCount(to), NeuralHistory.PARAMS);
		int[] fo = Backprop.offsets(from), too = Backprop.offsets(to);
		int last = to.length - 1;
		for(int l = 1; l <= last; l++)
		{
			int fIn = from[l - 1], fOut = from[l], tIn = to[l - 1],
				tOut = to[l];
			double scale = 0.5 / Math.sqrt(tIn);
			for(int o = 0; o < tOut; o++)
			{
				for(int i = 0; i < tIn; i++)
				{
					float w;
					if(o < fOut)
						// an old neuron: as it was, deaf to the new ones
						w = i < fIn ? p[fo[l] + o * fIn + i] : 0;
					else
						w = (float)(random.nextGaussian() * scale);
					q[too[l] + o * tIn + i] = w;
				}
				q[too[l] + tIn * tOut + o] =
					o < fOut ? p[fo[l] + fIn * fOut + o] : 0;
			}
		}
		return q;
	}
	
	/** Each kind of fight's least win rate for a promotion. */
	static final double GROUP_FLOOR = 0.48;
	
	/**
	 * The promotion duel: A's win rate against B overall, in plain diamond
	 * and in enchanted gear - half the fights each (user, 2026-10-31: a net
	 * may learn a plain-only trick, but not give up enchanted fights for it).
	 */
	/**
	 * Whether cand really beats the champion: CONFIRM_GAMES fresh fights,
	 * CONFIRM_Z standard errors above 50%, each kind of fight GROUP_FLOOR.
	 * Not when train lite stops: that many fights on one thread would run
	 * into the next scheduled start - the next run goes on from cand and
	 * checks it then.
	 */
	static boolean confirm(int[] sizes, float[] cand, float[] champ, long seed,
		boolean stopping) throws Exception
	{
		if(stopping && NeuralTrainer.DUTY < 1)
		{
			log("  not promoted yet: lite is too slow to confirm it now, the"
				+ " next run goes on from it");
			return false;
		}
		double[] r = duel(sizes, cand, champ, seed, CONFIRM_GAMES);
		double need = 0.5 + CONFIRM_Z * 0.5 / Math.sqrt(CONFIRM_GAMES);
		boolean ok = r[0] >= need && Math.min(r[1], r[2]) >= GROUP_FLOOR;
		log("  %s: %.1f%% in %,d more fights (plain %.1f%%, enchanted"
			+ " %.1f%%; needs %.1f%%, each kind %.0f%%)",
			ok ? "confirmed" : "not promoted, not really better", r[0] * 100,
			CONFIRM_GAMES, r[1] * 100, r[2] * 100, need * 100,
			GROUP_FLOOR * 100);
		return ok;
	}
	
	static double[] duel(int[] sizes, float[] a, float[] b, long seed)
		throws Exception
	{
		return duel(sizes, a, b, seed, DUEL_GAMES);
	}
	
	static double[] duel(int[] sizes, float[] a, float[] b, long seed,
		int games) throws Exception
	{
		double share = PvpSim.plainShare;
		try
		{
			PvpSim.plainShare = 1;
			double plain =
				NeuralTrainer.winRate(NeuralTrainer.netSpec(sizes, a),
					NeuralTrainer.netSpec(sizes, b), games / 2, seed);
			PvpSim.plainShare = 0;
			double enchanted =
				NeuralTrainer.winRate(NeuralTrainer.netSpec(sizes, a),
					NeuralTrainer.netSpec(sizes, b), games / 2, seed + 1);
			return new double[]{(plain + enchanted) / 2, plain, enchanted};
		}finally
		{
			PvpSim.plainShare = share;
		}
	}
	
	/**
	 * The game's general net, in OnlineLearner's format (shipped net's
	 * hash, net, version) - null if missing or grown from another shipped
	 * net.
	 */
	static Champion readChampion(Path file, int[] sizes, int shippedHash)
	{
		try(InputStream in = Files.newInputStream(file))
		{
			DataInputStream din =
				new DataInputStream(new BufferedInputStream(in));
			int hash = din.readInt();
			NeuralNet net = NeuralNet.read(din);
			int version = din.readInt();
			if(!Arrays.equals(net.getSizes(), sizes))
				return null;
			// the champion of a different shipped net - unless it is the
			// shipped net now (shipped by ship-net.ps1): then it keeps its
			// number (v1 was counted as v0 again after its ship, 2026-10-29)
			if(hash != shippedHash
				&& Arrays.hashCode(net.getParams()) != shippedHash)
				return null;
			return new Champion(net.getParams(), version);
		}catch(IOException e)
		{
			return null;
		}
	}
	
	static void writeChampion(Path file, Champion c, int[] sizes,
		int shippedHash) throws IOException
	{
		Path tmp = file.resolveSibling(file.getFileName() + ".trainer.tmp");
		try(OutputStream out = Files.newOutputStream(tmp))
		{
			DataOutputStream dout =
				new DataOutputStream(new BufferedOutputStream(out));
			dout.writeInt(shippedHash);
			new NeuralNet(sizes, c.params).write(dout);
			dout.writeInt(c.version);
			dout.flush();
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
	
	static List<float[]> readHall(Path file, int[] sizes)
	{
		List<float[]> hall = new ArrayList<>();
		try(InputStream in = Files.newInputStream(file))
		{
			DataInputStream din =
				new DataInputStream(new BufferedInputStream(in));
			int n = din.readInt();
			for(int i = 0; i < n; i++)
			{
				NeuralNet net = NeuralNet.read(din);
				if(Arrays.equals(net.getSizes(), sizes))
					hall.add(net.getParams());
			}
		}catch(IOException e)
		{
			// none yet, or another version: a new league
		}
		return hall;
	}
	
	static void writeHall(Path file, List<float[]> hall, int[] sizes)
		throws IOException
	{
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try(OutputStream out = Files.newOutputStream(tmp))
		{
			DataOutputStream dout =
				new DataOutputStream(new BufferedOutputStream(out));
			dout.writeInt(hall.size());
			for(float[] p : hall)
				new NeuralNet(sizes, p).write(dout);
			dout.flush();
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
	
	/** Writes to the console and the log file. */
	private static final class Tee extends OutputStream
	{
		private final OutputStream a, b;
		
		Tee(OutputStream a, OutputStream b)
		{
			this.a = a;
			this.b = b;
		}
		
		@Override
		public void write(int c) throws IOException
		{
			a.write(c);
			b.write(c);
		}
		
		@Override
		public void write(byte[] buf, int off, int len) throws IOException
		{
			a.write(buf, off, len);
			b.write(buf, off, len);
		}
		
		@Override
		public void flush() throws IOException
		{
			a.flush();
			b.flush();
		}
	}
}
