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
 * under 60% on average or under 40% against any of them, where the
 * champion is at ~85%), the round is thrown away and the next round has the
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
	/** Below this against the champion, the next round starts over from it. */
	static final double KEEP = 0.47;
	static final double TERRIBLE_MEAN = 0.6, TERRIBLE_MIN = 0.4;
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
		NeuralPolicy shippedPolicy = NeuralPolicy.get();
		int[] sizes = shippedPolicy.getSizes();
		int shippedHash = Arrays.hashCode(shippedPolicy.getParams());
		Path learned = folder.resolve("aimassist_learned.bin");
		
		Champion champ = readChampion(learned, sizes, shippedHash);
		if(champ == null)
			champ = new Champion(shippedPolicy.getParams(), 0);
		List<float[]> hall = readHall(dir.resolve("hall.bin"), sizes);
		if(hall.isEmpty())
			hall.add(champ.params.clone());
		
		log("Training started (%d threads%s, %s), champion v%d, league of %d%s",
			NeuralTrainer.THREADS,
			NeuralTrainer.DUTY < 1 ? String.format(", lite: %.0f%% of the time",
				NeuralTrainer.DUTY * 100) : "",
			Arrays.toString(sizes), champ.version, hall.size(),
			until != null ? ", until " + until.toLocalTime() : "");
		
		NeuralTrainer.PAIRS = Integer.getInteger("nn.pairs", 128);
		NeuralTrainer.GAMES_PER_EVAL = Integer.getInteger("nn.games", 24);
		NeuralTrainer.SIGMA =
			Float.parseFloat(System.getProperty("nn.sigma", "0.1"));
		NeuralTrainer.LEARNING_RATE =
			Float.parseFloat(System.getProperty("nn.lr", "0.01"));
		NeuralTrainer.BENCH_EVERY = 100;
		NeuralTrainer.BENCH_GAMES = 400;
		NeuralTrainer.PRIOR = 0;
		
		float[] theta = champ.params.clone();
		double champYard = NeuralTrainer.mean(NeuralTrainer
			.benchmarkRates(sizes, champ.params, null, 400, 0x7A2DL));
		boolean guided = false;
		long baseSeed = System.nanoTime();
		long fights = 0, t0 = System.nanoTime();
		for(int round = 1; !NeuralTrainer.STOP.getAsBoolean(); round++)
		{
			NeuralTrainer.SCRIPTED = guided;
			NeuralTrainer.REFERENCE = champ.params;
			NeuralTrainer.SEED = baseSeed + round * 0x9E3779B97F4A7C15L;
			NeuralTrainer.Result r =
				NeuralTrainer.evolve(sizes, theta, ROUND_GENS, false, hall);
			fights += (long)NeuralTrainer.GENS_RUN * NeuralTrainer.PAIRS * 2
				* NeuralTrainer.GAMES_PER_EVAL;
			float[] cand = r.params();
			NeuralTrainer.dutyPause();
			
			long seed = NeuralTrainer.SEED ^ 0x5EEDL;
			double vsChamp = NeuralTrainer.winRate(
				NeuralTrainer.netSpec(sizes, cand),
				NeuralTrainer.netSpec(sizes, champ.params), DUEL_GAMES, seed);
			// the yardstick uses the same fights for every net
			NeuralTrainer.dutyPause();
			double[] yard =
				NeuralTrainer.benchmarkRates(sizes, cand, null, 400, 0x7A2DL);
			NeuralTrainer.dutyPause();
			fights += DUEL_GAMES + 400L * yard.length;
			double min = Arrays.stream(yard).min().orElse(0);
			boolean terrible =
				NeuralTrainer.mean(yard) < TERRIBLE_MEAN || min < TERRIBLE_MIN;
			double secs = (System.nanoTime() - t0) / 1e9;
			log("Round %d: vs champion v%d %.1f%%, yardstick %s%s (%,.0f fights/s)",
				round, champ.version, vsChamp * 100, NeuralTrainer.fmt(yard),
				guided ? ", guided" : "", fights / secs);
			
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
				if(vsChamp >= PROMOTE && candYard < champYard - FORGET)
					log("  not promoted: yardstick %.0f%% vs the champion's %.0f%%",
						candYard * 100, champYard * 100);
				else if(vsChamp >= PROMOTE)
				{
					// the game may have learned something meanwhile
					Champion onDisk = readChampion(learned, sizes, shippedHash);
					if(onDisk != null
						&& !Arrays.equals(onDisk.params, champ.params)
						&& NeuralTrainer.winRate(
							NeuralTrainer.netSpec(sizes, cand),
							NeuralTrainer.netSpec(sizes, onDisk.params),
							DUEL_GAMES, seed + 2) < PROMOTE)
					{
						log("  the game's net (v%d) is as good: going on from it",
							onDisk.version);
						champ = onDisk;
						champYard =
							NeuralTrainer.mean(NeuralTrainer.benchmarkRates(
								sizes, champ.params, null, 400, 0x7A2DL));
						theta = champ.params.clone();
						continue;
					}
					int version = Math.max(champ.version,
						onDisk != null ? onDisk.version : 0) + 1;
					champ = new Champion(cand.clone(), version);
					champYard = candYard;
					writeChampion(learned, champ, sizes, shippedHash);
					log("  new champion v%d (AimAssist takes it at the next"
						+ " game start or after its next fight)", version);
				}
				theta = vsChamp >= KEEP ? cand.clone() : champ.params.clone();
			}
			writeHall(dir.resolve("hall.bin"), hall, sizes);
		}
		log("Training stopped, champion v%d", champ.version);
	}
	
	private static void log(String format, Object... args)
	{
		System.out.println(LocalDateTime.now().format(TIME) + "  "
			+ String.format(format, args));
	}
	
	record Champion(float[] params, int version)
	{}
	
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
			if(hash != shippedHash || !Arrays.equals(net.getSizes(), sizes))
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
