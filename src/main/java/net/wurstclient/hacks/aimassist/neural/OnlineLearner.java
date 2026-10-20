/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/**
 * Keeps AimAssist's neural network learning while you play.
 *
 * <ol>
 * <li><b>Copy the opponent.</b> While you fight someone,
 * {@link OpponentRecorder} writes down what they saw and which keys they
 * pressed; a network of the same shape learns to play like them
 * (supervised, {@link Backprop}), starting from the general net and
 * re-trained as new moves come in.</li>
 * <li><b>Practise against the copy.</b> A background thread plays fast
 * simulated fights ({@link PvpSim}) against that copy - with the reach the
 * opponent was seen hitting from - and tunes a version of the network for
 * that player (evolution strategies, pulled toward the general net so it
 * doesn't forget how to fight). A tuned version takes over only once it
 * beats the one in use on the same fights.</li>
 * <li><b>Check it on the real thing.</b> Which net plays - general or tuned
 * - is picked by the hits each one actually landed and took against that
 * player (a discounted two-armed bandit), so a tuned net that only beats
 * the copy loses its turn.</li>
 * <li><b>Get better in general.</b> During fights (every 3rd slice) and
 * after them, the general net trains against every opponent copied so far
 * (and itself) and is kept only if it still beats the previous general
 * net.</li>
 * <li><b>Learn inside the fight.</b> Every real exchange also trains that
 * opponent's {@link FightAdapter} on the client thread (see
 * {@link #decide}), kept and saved per opponent.</li>
 * </ol>
 * Everything is saved in {@code .minecraft/wurst/neural/} (one small file
 * per opponent, by UUID), so the next fight starts adapted.
 *
 * <p>
 * Costs: one background thread at the lowest priority, working at most a
 * set share of one core and only while there's something to learn (a fight
 * in the last minute, or the general net's round after one); sleeping
 * otherwise. Disk writes only on that thread. No Minecraft classes - the
 * game side feeds it plain numbers.
 */
public final class OnlineLearner
{
	private static final int FILE_MAGIC = 0x424F5032; // "BOP2"
	private static final int FILE_MAGIC_V1 = 0x424F5031; // "BOP1"
	
	/** Moves kept per opponent (the most recent; ~2-3 minutes of fight). */
	static final int SAMPLE_CAP = 4000;
	/** Moves needed before a copy is made. */
	static final int MIN_SAMPLES = 150;
	/** New moves before the copy is re-trained. */
	static final int RETRAIN_SAMPLES = 200;
	/** Opponents kept on disk. */
	static final int MAX_SAVED = 32;
	/** A fight counts as going on for this long after the last move. */
	static final long ACTIVE_NANOS = 60_000_000_000L;
	/** After this long without a fight, every changed opponent is saved. */
	static final long IDLE_NANOS = 30_000_000_000L;
	
	// evolution strategies, small and single-threaded
	static final int PAIRS = 12;
	static final int GAMES = 6;
	static final float SIGMA = 0.05F;
	static final float LR = 0.005F;
	/** Pull toward the general net (L2). */
	static final float ANCHOR = 0.02F;
	static final int EVAL_EVERY = 20;
	static final int EVAL_GAMES = 120;
	/** Generations per opponent without new moves before it rests. */
	static final int SESSION_GENS = 400;
	static final int GENERAL_GENS = 150;
	static final int MAX_TICKS = 600;
	
	private static OnlineLearner instance;
	
	private final Path folder;
	private final int[] sizes;
	private final float[] shipped;
	private final int shippedHash;
	private volatile float[] general;
	private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
	
	private volatile boolean enabled = true;
	private volatile double duty = 0.35;
	private volatile long lastActivity = Long.MIN_VALUE / 2;
	private volatile boolean generalPending;
	private Thread thread;
	
	// stats for the status line
	private volatile long simFights;
	private volatile int generalVersion;
	
	OnlineLearner(Path folder, int[] sizes, float[] shipped)
	{
		this.folder = folder;
		this.sizes = sizes.clone();
		this.shipped = shipped.clone();
		shippedHash = Arrays.hashCode(shipped);
		general = this.shipped;
	}
	
	/**
	 * The learner for the shipped network, saving in {@code folder}. Null if
	 * the shipped network couldn't be loaded.
	 */
	public static synchronized OnlineLearner get(Path folder)
	{
		if(instance != null)
			return instance;
		NeuralPolicy shippedPolicy = NeuralPolicy.get();
		if(shippedPolicy == null)
			return null;
		instance = new OnlineLearner(folder, shippedPolicy.getSizes(),
			shippedPolicy.getParams());
		return instance;
	}
	
	// ── the game's side (client thread) ──────────────────────────────────
	
	public Profile profile(UUID id)
	{
		return profiles.computeIfAbsent(id, Profile::new);
	}
	
	/** The weights to play this opponent with right now. */
	public float[] paramsFor(Profile p)
	{
		float[] a = p.adapted;
		return p.usingAdapted && a != null ? a : general;
	}
	
	/**
	 * This tick's network outputs against this opponent: the net to play
	 * them with ({@link #paramsFor}) plus what its {@link FightAdapter} has
	 * learned in the fights with them so far, still learning (client thread).
	 */
	public float[] decide(Profile p, float[] input)
	{
		float[] params = paramsFor(p);
		FightAdapter a = p.adapter;
		if(a == null)
			a = p.adapter = new FightAdapter(sizes, params,
				p.id.getLeastSignificantBits() ^ System.nanoTime(), true);
		float[] loaded = p.loadedDelta;
		if(loaded != null)
		{
			p.loadedDelta = null;
			if(a.getTests() == 0)
				a.setDelta(loaded);
		}
		a.setBase(params);
		return a.forward(input);
	}
	
	/** The general net's weights right now (never changed in place). */
	public float[] generalParams()
	{
		return general;
	}
	
	public int[] getSizes()
	{
		return sizes.clone();
	}
	
	/** Called every tick a fight is recorded: keeps the thread working. */
	public void noteActivity()
	{
		lastActivity = System.nanoTime();
		generalPending = true;
		start();
	}
	
	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}
	
	/** The share of one core the thread may use, 0.05-1. */
	public void setDuty(double duty)
	{
		this.duty = Math.max(0.05, Math.min(1, duty));
	}
	
	public String getStatus(Profile p)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("%,d moves seen", p.seen));
		if(p.clone != null)
			sb.append(", copy v").append(p.cloneVersion);
		if(p.adapted != null)
			sb.append(String.format(", tuned v%d (%.0f%% vs copy)",
				p.adaptedVersion, p.adaptedScore * 100));
		sb.append(p.usingAdapted && p.adapted != null ? ", playing tuned"
			: ", playing general");
		sb.append(String.format(", general v%d, %,d sim fights", generalVersion,
			simFights));
		return sb.toString();
	}
	
	private synchronized void start()
	{
		if(thread != null)
			return;
		thread = new Thread(this::run, "Beast neural learner");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}
	
	/** One opponent: what was seen of them and what was learned. */
	public static final class Profile implements OpponentRecorder.Sink
	{
		final UUID id;
		public final OpponentRecorder recorder = new OpponentRecorder();
		
		private final float[][] xs = new float[SAMPLE_CAP][];
		private final byte[][] ys = new byte[SAMPLE_CAP][];
		private int size, next;
		volatile int newSamples;
		volatile long seen;
		volatile long lastSeen = Long.MIN_VALUE / 2;
		
		/** Network weights that play like this opponent. */
		volatile float[] clone;
		volatile int cloneVersion;
		/** The general net tuned against the copy. */
		volatile float[] adapted;
		volatile int adaptedVersion;
		volatile double adaptedScore;
		/** How far they were seen hitting from (eyes to hitbox). */
		volatile double reach = PvpSim.REACH;
		volatile boolean dirty;
		
		/** Whether the tuned net plays (client thread decides). */
		volatile boolean usingAdapted;
		
		/** Learns during the fights themselves (client thread only). */
		FightAdapter adapter;
		/** What the adapter has learned, for saving. */
		volatile float[] delta;
		/** From the file, for the adapter (if it hasn't learned yet). */
		volatile float[] loadedDelta;
		private final double[] rewardSum = new double[2];
		private final double[] pulls = new double[2];
		private int sinceSwitch;
		
		// learner thread only
		Session session;
		
		Profile(UUID id)
		{
			this.id = id;
		}
		
		@Override
		public synchronized void add(float[] x, byte[] y)
		{
			xs[next] = x;
			ys[next] = y;
			next = (next + 1) % SAMPLE_CAP;
			size = Math.min(size + 1, SAMPLE_CAP);
			newSamples++;
			seen++;
			lastSeen = System.nanoTime();
		}
		
		synchronized int copySamples(float[][] x, byte[][] y)
		{
			for(int i = 0; i < size; i++)
			{
				x[i] = xs[i];
				y[i] = ys[i];
			}
			newSamples = 0;
			return size;
		}
		
		public synchronized int sampleCount()
		{
			return size;
		}
		
		/** How far they were seen hitting from. */
		public double getReach()
		{
			return reach;
		}
		
		/** The tuned net's version if it's the one playing, else 0. */
		public int playingTunedVersion()
		{
			return usingAdapted && adapted != null ? adaptedVersion : 0;
		}
		
		/** A hit of theirs on us (a reach sample: their eyes to our hitbox). */
		public void noteTheirReach(double r)
		{
			// the longest seen, slowly forgotten, never below vanilla
			double cur = reach;
			reach = Math.max(PvpSim.REACH,
				Math.min(4.5, Math.max(r, cur - (cur - PvpSim.REACH) * 0.05)));
		}
		
		/**
		 * A real exchange: +1 our hit (1.5 a crit), -1 theirs. Picks the
		 * net for the next exchanges (discounted UCB1: real results decide
		 * whether the tuned net or the general one plays this opponent).
		 */
		public void noteResult(double reward)
		{
			// the in-fight adapter learns from every exchange
			FightAdapter a = adapter;
			if(a != null)
			{
				long tests = a.getTests();
				a.reward(reward);
				if(a.getTests() != tests)
				{
					delta = a.getDelta();
					dirty = true;
				}
			}
			
			int arm = usingAdapted ? 1 : 0;
			for(int i = 0; i < 2; i++)
			{
				rewardSum[i] *= 0.9;
				pulls[i] *= 0.9;
			}
			rewardSum[arm] += reward;
			pulls[arm] += 1;
			if(++sinceSwitch < 2)
				return;
			sinceSwitch = 0;
			if(adapted == null)
			{
				usingAdapted = false;
				return;
			}
			if(pulls[1] < 1)
			{
				usingAdapted = true;
				return;
			}
			if(pulls[0] < 1)
			{
				usingAdapted = false;
				return;
			}
			double total = Math.log(pulls[0] + pulls[1]);
			double u0 = rewardSum[0] / pulls[0]
				+ Math.sqrt(2 * Math.max(0, total) / pulls[0]);
			double u1 = rewardSum[1] / pulls[1]
				+ Math.sqrt(2 * Math.max(0, total) / pulls[1]);
			usingAdapted = u1 >= u0;
		}
		
		/** A new tuned net: give it a fresh try on the real opponent. */
		void resetAdaptedArm()
		{
			rewardSum[1] *= 0.5;
			pulls[1] *= 0.5;
		}
	}
	
	/** Optimizer state of one training run (learner thread). */
	static final class Session
	{
		final float[] theta, anchor, m, v;
		int gen, idleGens;
		double bestScore = Double.NaN;
		
		Session(float[] start, float[] anchor)
		{
			theta = start.clone();
			this.anchor = anchor;
			m = new float[theta.length];
			v = new float[theta.length];
		}
	}
	
	// ── the background thread ────────────────────────────────────────────
	
	private void run()
	{
		load();
		Random rnd = new Random();
		while(true)
			try
			{
				if(!enabled)
				{
					Thread.sleep(1000);
					continue;
				}
				long t0 = System.nanoTime();
				boolean worked = step(rnd);
				if(!worked)
				{
					saveDirty(false);
					Thread.sleep(500);
					continue;
				}
				long w = System.nanoTime() - t0;
				long sleepNanos = (long)(w * (1 / duty - 1));
				Thread.sleep(sleepNanos / 1_000_000,
					(int)(sleepNanos % 1_000_000));
			}catch(InterruptedException e)
			{
				return;
			}catch(Throwable t)
			{
				// never let a learning bug take the game down
				t.printStackTrace();
				try
				{
					Thread.sleep(5000);
				}catch(InterruptedException e)
				{
					return;
				}
			}
	}
	
	/** One slice of work (~50-200 ms). Returns false if there was none. */
	boolean step(Random rnd)
	{
		long now = System.nanoTime();
		
		// the opponent being fought (most recent first)
		Profile best = null;
		for(Profile p : profiles.values())
		{
			if(now - p.lastSeen > ACTIVE_NANOS)
				continue;
			if(p.sampleCount() < MIN_SAMPLES)
				continue;
			boolean wants = p.clone == null || p.newSamples >= RETRAIN_SAMPLES
				|| p.session == null || p.session.idleGens < SESSION_GENS;
			if(wants && (best == null || p.lastSeen > best.lastSeen))
				best = p;
		}
		// during a fight every 3rd slice goes to the general net, so it
		// learns from this opponent while the fight is still on
		boolean general = generalSession != null || generalPending;
		if(best != null && !(general && ++slices % 3 == 0))
		{
			adaptStep(best, rnd);
			return true;
		}
		
		if(general)
		{
			if(now - lastActivity > IDLE_NANOS)
				saveDirty(true);
			return generalSlice(rnd);
		}
		return false;
	}
	
	private int slices;
	
	void adaptStep(Profile p, Random rnd)
	{
		if(p.clone == null || p.newSamples >= RETRAIN_SAMPLES)
		{
			trainClone(p, rnd);
			if(p.session != null)
			{
				p.session.idleGens = 0;
				p.session.bestScore = Double.NaN;
			}
			return;
		}
		
		float[] base = general;
		if(p.session == null)
			p.session = new Session(p.adapted != null ? p.adapted : base, base);
		Session s = p.session;
		float[] clone = p.clone;
		double reach = p.reach;
		
		for(int i = 0; i < 4; i++)
		{
			esGeneration(s, rnd, (seed, cand) -> {
				SplittableRandom r = new SplittableRandom(seed);
				// mostly the copy, sometimes the general net (stay sound)
				boolean vsClone = r.nextInt(4) != 0;
				return fight(cand, vsClone ? clone : base, vsClone, reach,
					seed);
			});
			s.idleGens++;
		}
		
		if(s.gen % EVAL_EVERY < 4)
		{
			// the same fights for both, so luck can't pick the winner
			long seeds = p.id.getMostSignificantBits() ^ p.cloneVersion;
			float[] current = p.adapted != null ? p.adapted : base;
			if(Double.isNaN(s.bestScore))
				s.bestScore =
					winRate(current, clone, true, reach, EVAL_GAMES, seeds);
			double score =
				winRate(s.theta, clone, true, reach, EVAL_GAMES, seeds);
			if(score > s.bestScore + 0.02)
			{
				s.bestScore = score;
				p.adapted = s.theta.clone();
				p.adaptedVersion++;
				p.adaptedScore = score;
				p.resetAdaptedArm();
				p.dirty = true;
			}
		}
	}
	
	/** Learns to play like the opponent from the recorded moves. */
	void trainClone(Profile p, Random rnd)
	{
		float[][] x = new float[SAMPLE_CAP][];
		byte[][] y = new byte[SAMPLE_CAP][];
		int n = p.copySamples(x, y);
		float[] params = (p.clone != null ? p.clone : general).clone();
		Backprop.train(sizes, params, x, y, n, p.clone == null ? 8 : 3, 0.003F,
			new Random(rnd.nextLong()));
		p.clone = params;
		p.cloneVersion++;
		p.dirty = true;
	}
	
	/**
	 * A few generations of the general net's round: against every copied
	 * opponent and itself (both adapting mid-fight, a copy doesn't). A
	 * round ends after {@link #GENERAL_GENS} and is kept only if it beats
	 * the previous general net and does at least as well against the
	 * copies. Runs during fights and after them.
	 */
	private Session generalSession;
	
	boolean generalSlice(Random rnd)
	{
		if(generalSession == null)
		{
			generalPending = false;
			// the trainer (train.cmd, the nightly task) may have written a
			// better one meanwhile
			if(adoptNewerGeneral() || copiedOpponents().isEmpty())
				return false;
			generalSession = new Session(general, shipped);
			generalBase = general;
		}
		Session s = generalSession;
		float[] base = generalBase;
		List<Profile> pool = copiedOpponents();
		for(int i = 0; i < 4 && s.gen < GENERAL_GENS; i++)
			esGeneration(s, rnd, (seed, cand) -> {
				SplittableRandom r = new SplittableRandom(seed);
				int pick = r.nextInt(pool.size() + 1);
				if(pick == pool.size())
					return fight(cand, base, false, PvpSim.REACH, seed);
				Profile p = pool.get(pick);
				return fight(cand, p.clone, true, p.reach, seed);
			});
		if(s.gen < GENERAL_GENS)
			return true;
		
		generalSession = null;
		long seeds = 0x5EED_0000L + generalVersion;
		if(general != base || adoptNewerGeneral()
			|| winRate(s.theta, base, false, PvpSim.REACH, 200, seeds) < 0.5)
			return true;
		double before = 0, after = 0;
		for(Profile p : pool)
		{
			before += winRate(base, p.clone, true, p.reach, 60, seeds);
			after += winRate(s.theta, p.clone, true, p.reach, 60, seeds);
		}
		if(after < before || adoptNewerGeneral())
			return true;
		general = s.theta.clone();
		generalVersion++;
		saveGeneral();
		return true;
	}
	
	private float[] generalBase;
	
	private List<Profile> copiedOpponents()
	{
		List<Profile> pool = new ArrayList<>();
		for(Profile p : profiles.values())
			if(p.clone != null)
				pool.add(p);
		return pool;
	}
	
	interface Fitness
	{
		double eval(long seed, float[] cand);
	}
	
	/**
	 * One generation of OpenAI-ES: antithetic pairs on the same fights,
	 * centered ranks, Adam, plus a pull toward the anchor.
	 */
	void esGeneration(Session s, Random rnd, Fitness fitness)
	{
		int n = s.theta.length;
		long genSeed = rnd.nextLong();
		double[] fit = new double[PAIRS * 2];
		float[][] eps = new float[PAIRS][];
		float[] cand = new float[n];
		for(int i = 0; i < PAIRS; i++)
		{
			eps[i] = noise(genSeed, i, n);
			for(int sign = 0; sign < 2; sign++)
			{
				float sg = sign == 0 ? 1 : -1;
				for(int j = 0; j < n; j++)
					cand[j] = s.theta[j] + sg * SIGMA * eps[i][j];
				double sum = 0;
				for(int g = 0; g < GAMES; g++)
					sum += fitness.eval(genSeed * 31 + i * 1009L + g, cand);
				fit[2 * i + sign] = sum / GAMES;
			}
		}
		simFights += PAIRS * 2L * GAMES;
		
		double[] rank = centeredRanks(fit);
		float[] grad = new float[n];
		for(int i = 0; i < PAIRS; i++)
		{
			double w = rank[2 * i] - rank[2 * i + 1];
			for(int j = 0; j < n; j++)
				grad[j] += (float)(w * eps[i][j]);
		}
		s.gen++;
		for(int j = 0; j < n; j++)
		{
			float g = grad[j] / (2 * PAIRS * SIGMA)
				- ANCHOR * (s.theta[j] - s.anchor[j]);
			s.m[j] = 0.9F * s.m[j] + 0.1F * g;
			s.v[j] = 0.999F * s.v[j] + 0.001F * g * g;
			float mh = s.m[j] / (1 - (float)Math.pow(0.9, s.gen));
			float vh = s.v[j] / (1 - (float)Math.pow(0.999, s.gen));
			s.theta[j] += LR * mh / ((float)Math.sqrt(vh) + 1e-8F);
		}
	}
	
	/**
	 * One simulated fight, scored for A like the offline trainer: damage
	 * traded, the win, sprint hits, crits and combos.
	 */
	double fight(float[] mine, float[] theirs, boolean theirsIsCopy,
		double theirReach, long seed)
	{
		// a copy of a player plays like they did, without an adapter
		PvpSim sim = new PvpSim(seed, new NetPolicy(sizes, mine),
			new NetPolicy(sizes, theirs, !theirsIsCopy), MAX_TICKS);
		sim.a.onlyGoodHits = true;
		// a copied player hits however they like, from where they hit
		sim.b.onlyGoodHits = !theirsIsCopy;
		sim.b.reach = theirReach;
		int result = sim.run();
		SimFighter a = sim.a, b = sim.b;
		double score = (a.damageDealt - b.damageDealt) / 20 + result;
		score += 0.01 * a.sprintHits + 0.02 * a.crits;
		score += 0.03 * Math.max(0, a.maxCombo - 1)
			- 0.03 * Math.max(0, b.maxCombo - 1);
		return score;
	}
	
	/** Win rate of A (a draw goes by health; even = half). */
	double winRate(float[] mine, float[] theirs, boolean theirsIsCopy,
		double theirReach, int games, long seed)
	{
		double sum = 0;
		for(int g = 0; g < games; g++)
		{
			PvpSim sim =
				new PvpSim(seed * 1_000_003L + g, new NetPolicy(sizes, mine),
					new NetPolicy(sizes, theirs, !theirsIsCopy), MAX_TICKS);
			sim.a.onlyGoodHits = true;
			sim.b.onlyGoodHits = !theirsIsCopy;
			sim.b.reach = theirReach;
			int r = sim.run();
			if(r == 0)
				r = Float.compare(sim.a.health, sim.b.health);
			sum += r > 0 ? 1 : r < 0 ? 0 : 0.5;
		}
		simFights += games;
		return sum / games;
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
	 * A network playing in the simulator - adapting during the fight with a
	 * {@link FightAdapter}, like AimAssist does in game.
	 */
	public static final class NetPolicy implements Policy
	{
		private final FightAdapter adapter;
		private final float[] in = new float[NeuralObservation.SIZE];
		private int lastHits, lastCrits, lastHurtTick;
		
		public NetPolicy(int[] sizes, float[] params)
		{
			this(sizes, params, true);
		}
		
		public NetPolicy(int[] sizes, float[] params, boolean adapting)
		{
			adapter = new FightAdapter(sizes, params, 0, adapting);
		}
		
		/** What it learned in earlier fights (a series against one player). */
		public NetPolicy withDelta(float[] delta)
		{
			adapter.setDelta(delta);
			return this;
		}
		
		public FightAdapter getAdapter()
		{
			return adapter;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			adapter.restart(random.nextLong());
			lastHits = lastCrits = 0;
			lastHurtTick = PvpSim.NEVER;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			// the hits since last tick, scored like AimAssist scores them
			if(me.hits != lastHits)
			{
				int crits = me.crits - lastCrits;
				int hits = me.hits - lastHits;
				adapter.reward(hits + 0.5 * crits);
				lastHits = me.hits;
				lastCrits = me.crits;
			}
			if(me.lastHurtTick != lastHurtTick)
			{
				adapter.reward(-1);
				lastHurtTick = me.lastHurtTick;
			}
			o.encode(in);
			out.decode(adapter.forward(in));
		}
	}
	
	// ── saving (learner thread only) ─────────────────────────────────────
	
	private Path generalFile()
	{
		return folder.resolve("aimassist_learned.bin");
	}
	
	private Path opponentFile(UUID id)
	{
		return folder.resolve("opponents").resolve(id + ".bin");
	}
	
	/**
	 * The general net from last time (if it grew from this shipped net)
	 * and every saved opponent.
	 */
	void load()
	{
		if(folder == null)
			return;
		try(InputStream in = Files.newInputStream(generalFile()))
		{
			DataInputStream din =
				new DataInputStream(new java.io.BufferedInputStream(in));
			int hash = din.readInt();
			NeuralNet net = NeuralNet.read(din);
			if(hash == shippedHash && Arrays.equals(net.getSizes(), sizes))
			{
				general = net.getParams();
				generalVersion = din.readInt();
			}
		}catch(IOException e)
		{
			// none yet, or from another version: start from the shipped net
		}
		
		Path dir = folder.resolve("opponents");
		if(!Files.isDirectory(dir))
			return;
		try(DirectoryStream<Path> files =
			Files.newDirectoryStream(dir, "*.bin"))
		{
			for(Path f : files)
				loadOpponent(f);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void loadOpponent(Path f)
	{
		String name = f.getFileName().toString();
		UUID id;
		try
		{
			id = UUID.fromString(name.substring(0, name.length() - 4));
		}catch(IllegalArgumentException e)
		{
			return;
		}
		try(InputStream in = Files.newInputStream(f))
		{
			DataInputStream din =
				new DataInputStream(new java.io.BufferedInputStream(in));
			int magic = din.readInt();
			if(magic != FILE_MAGIC && magic != FILE_MAGIC_V1)
				return;
			boolean v1 = magic == FILE_MAGIC_V1;
			int hash = din.readInt();
			double reach = din.readDouble();
			long seen = din.readLong();
			NeuralNet clone =
				v1 || din.readBoolean() ? NeuralNet.read(din) : null;
			NeuralNet adapted = din.readBoolean() ? NeuralNet.read(din) : null;
			float[] delta = null;
			if(!v1)
			{
				int n = din.readInt();
				if(n > 0 && n <= 1024)
				{
					delta = new float[n];
					for(int i = 0; i < n; i++)
						delta[i] = din.readFloat();
				}
			}
			if(clone != null && !Arrays.equals(clone.getSizes(), sizes))
				return;
			
			Profile p = profile(id);
			if(p.clone == null && clone != null)
			{
				p.clone = clone.getParams();
				p.cloneVersion = 1;
			}
			if(delta != null && p.delta == null)
			{
				p.delta = delta;
				p.loadedDelta = delta;
			}
			// a tuned net only fits the general net it was tuned from
			if(p.adapted == null && adapted != null && hash == shippedHash)
			{
				p.adapted = adapted.getParams();
				p.adaptedVersion = 1;
			}
			p.reach = Math.max(p.reach, reach);
			p.seen += seen;
		}catch(IOException e)
		{
			// a broken file: ignore it, it gets overwritten
		}
	}
	
	/**
	 * Takes the general net on disk if it's a later version than ours (the
	 * trainer's champion), so saving ours doesn't overwrite it.
	 */
	private boolean adoptNewerGeneral()
	{
		if(folder == null)
			return false;
		try(InputStream in = Files.newInputStream(generalFile()))
		{
			DataInputStream din =
				new DataInputStream(new java.io.BufferedInputStream(in));
			int hash = din.readInt();
			NeuralNet net = NeuralNet.read(din);
			int version = din.readInt();
			if(hash != shippedHash || !Arrays.equals(net.getSizes(), sizes)
				|| version <= generalVersion)
				return false;
			general = net.getParams();
			generalVersion = version;
			return true;
		}catch(IOException e)
		{
			return false;
		}
	}
	
	/** Saves changed opponents (all of them, or only quiet ones). */
	void saveDirty(boolean all)
	{
		if(folder == null)
			return;
		long now = System.nanoTime();
		boolean saved = false;
		for(Profile p : profiles.values())
		{
			if(!p.dirty || p.clone == null && p.delta == null)
				continue;
			if(!all && now - p.lastSeen < 10_000_000_000L)
				continue;
			p.dirty = false;
			saveOpponent(p);
			saved = true;
		}
		if(saved)
			pruneSaved();
	}
	
	private void saveOpponent(Profile p)
	{
		Path f = opponentFile(p.id);
		try
		{
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			try(OutputStream out = Files.newOutputStream(tmp))
			{
				DataOutputStream dout =
					new DataOutputStream(new java.io.BufferedOutputStream(out));
				dout.writeInt(FILE_MAGIC);
				dout.writeInt(shippedHash);
				dout.writeDouble(p.reach);
				dout.writeLong(p.seen);
				float[] c = p.clone;
				dout.writeBoolean(c != null);
				if(c != null)
					new NeuralNet(sizes, c).write(dout);
				float[] a = p.adapted;
				dout.writeBoolean(a != null);
				if(a != null)
					new NeuralNet(sizes, a).write(dout);
				float[] d = p.delta != null ? p.delta : p.loadedDelta;
				dout.writeInt(d != null ? d.length : 0);
				if(d != null)
					for(float x : d)
						dout.writeFloat(x);
				dout.flush();
			}
			Files.move(tmp, f,
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	/** Keeps the most recently fought {@link #MAX_SAVED} opponents. */
	private void pruneSaved()
	{
		Path dir = folder.resolve("opponents");
		List<Path> files = new ArrayList<>();
		try(DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.bin"))
		{
			s.forEach(files::add);
		}catch(IOException e)
		{
			return;
		}
		if(files.size() <= MAX_SAVED)
			return;
		files.sort(Comparator.comparingLong(OnlineLearner::modified));
		for(int i = 0; i < files.size() - MAX_SAVED; i++)
			try
			{
				Path f = files.get(i);
				String name = f.getFileName().toString();
				profiles.remove(
					UUID.fromString(name.substring(0, name.length() - 4)));
				Files.deleteIfExists(f);
			}catch(IOException | IllegalArgumentException e)
			{
				// leave it
			}
	}
	
	private static long modified(Path f)
	{
		try
		{
			return Files.getLastModifiedTime(f).toMillis();
		}catch(IOException e)
		{
			return 0;
		}
	}
	
	private void saveGeneral()
	{
		if(folder == null)
			return;
		try
		{
			Files.createDirectories(folder);
			Path tmp = folder.resolve("aimassist_learned.bin.tmp");
			try(OutputStream out = Files.newOutputStream(tmp))
			{
				DataOutputStream dout =
					new DataOutputStream(new java.io.BufferedOutputStream(out));
				dout.writeInt(shippedHash);
				new NeuralNet(sizes, general).write(dout);
				dout.writeInt(generalVersion);
				dout.flush();
			}
			Files.move(tmp, generalFile(),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
}
