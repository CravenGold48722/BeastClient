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
	/**
	 * The general net beating a copy at least this often: no tuned net for
	 * that player (nothing real left to learn from the copy).
	 */
	static final double SATURATED = 0.85;
	/** Generations per opponent without new moves before it rests. */
	/**
	 * Real full hits (ours + theirs, the general net playing) before a tuned
	 * net may be made, and how far the copy's hit share may be off theirs.
	 */
	static final int MIN_REAL_HITS = 60, REAL_HIT_WINDOW = 120;
	/**
	 * The last 30 seconds of their recording (one sample a tick), compared
	 * with the 30-second windows before it (2+ needed), see
	 * {@link #styleChanged}; when they changed, the copy plays only the
	 * recent.
	 */
	static final int RECENT_SAMPLES = 600, STYLE_MIN_SCORED = 300,
		STYLE_WINDOWS = 4;
	/**
	 * A spread of at least 0.16 nats: single fights of one fighter vary that
	 * much (4 windows understate it - SprintCombo +0.38 against a spread of
	 * 0.067, +0.51 against 0.131), so a change is 0.56+ up; the switches
	 * between Aggressor and Counter were +0.74 and +1.04 (2026-11-02).
	 */
	static final double STYLE_SD = 3.5, STYLE_MIN_SD = 0.16;
	/**
	 * The window after one that stood out only has to stay up: the copy has
	 * already learned from the first (Spammer -> SprintCombo: +2.69 nats,
	 * then +0.42 at a spread of 0.16; the windows after a lone spike were
	 * at -0.2 to -0.7 spreads, 2026-11-03).
	 */
	static final double STYLE_STAY_SD = 2;
	static final double FIDELITY = 0.06;
	static final int SESSION_GENS = 400;
	static final int GENERAL_GENS = 150;
	static final int MAX_TICKS = 1200;
	
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
	
	/** The net decide() ran last for this player (NeuralHistory reads it). */
	public NeuralNet netOf(Profile p)
	{
		return p.adapter.net();
	}
	
	/**
	 * Takes the trainer's champion from {@code aimassist_learned.bin} if it's
	 * newer than the general net in use (client thread, when AimAssist is
	 * switched on - one small file).
	 */
	public void refreshGeneral()
	{
		adoptNewerGeneral();
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
		/**
		 * What you and they wore last time (AimAssist reads it off both
		 * players): the fights against their copy are in that gear (null =
		 * PvpSim's default).
		 */
		volatile PvpSim.Gear myGear, theirGear;
		
		public void setGear(PvpSim.Gear mine, PvpSim.Gear theirs)
		{
			myGear = mine;
			theirGear = theirs;
		}
		
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
			surprises[next] = Float.NaN;
			next = (next + 1) % SAMPLE_CAP;
			size = Math.min(size + 1, SAMPLE_CAP);
			newSamples++;
			seen++;
			lastSeen = System.nanoTime();
		}
		
		/** The samples, oldest first. */
		int copySamples(float[][] x, byte[][] y)
		{
			return copySamples(x, y, null);
		}
		
		/** ... and how surprising each was to the copy (NaN: not scored). */
		synchronized int copySamples(float[][] x, byte[][] y, float[] s)
		{
			int first = size < SAMPLE_CAP ? 0 : next;
			for(int i = 0; i < size; i++)
			{
				int k = (first + i) % SAMPLE_CAP;
				x[i] = xs[k];
				y[i] = ys[k];
				if(s != null)
					s[i] = surprises[k];
			}
			newSamples = 0;
			return size;
		}
		
		/**
		 * How surprising each new sample was to the copy as it was before
		 * it learned from them (learner thread; the scoring outside the
		 * lock, the client thread adds samples).
		 */
		void scoreNew(NeuralNet copy)
		{
			int[] slots = new int[SAMPLE_CAP];
			float[][] x = new float[SAMPLE_CAP][];
			byte[][] y = new byte[SAMPLE_CAP][];
			int n = 0;
			synchronized(this)
			{
				for(int i = 0; i < size; i++)
					if(Float.isNaN(surprises[i]))
					{
						slots[n] = i;
						x[n] = xs[i];
						y[n++] = ys[i];
					}
			}
			float[] s = new float[n];
			for(int i = 0; i < n; i++)
				s[i] = (float)Backprop.surprise(copy.forward(x[i]), y[i]);
			synchronized(this)
			{
				for(int i = 0; i < n; i++)
					if(xs[slots[i]] == x[i])
						surprises[slots[i]] = s[i];
			}
		}
		
		/** Forgets all but the newest n samples (they changed their style). */
		synchronized void keepNewest(int n)
		{
			if(n >= size)
				return;
			int first = size < SAMPLE_CAP ? 0 : next;
			float[][] x = new float[n][];
			byte[][] y = new byte[n][];
			float[] s = new float[n];
			for(int i = 0; i < n; i++)
			{
				int k = (first + size - n + i) % SAMPLE_CAP;
				x[i] = xs[k];
				y[i] = ys[k];
				s[i] = surprises[k];
			}
			Arrays.fill(xs, null);
			Arrays.fill(ys, null);
			System.arraycopy(x, 0, xs, 0, n);
			System.arraycopy(y, 0, ys, 0, n);
			System.arraycopy(s, 0, surprises, 0, n);
			size = n;
			next = n % SAMPLE_CAP;
		}
		
		private final float[] surprises = new float[SAMPLE_CAP];
		
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
		
		/** Real full hits while the general net played: ours, theirs. */
		volatile int realOurHits, realTheirHits;
		/** How often they changed their style (the copy forgot the rest). */
		volatile int styleChanges;
		/** Learner thread: when the style was last checked (seen). */
		long styleCheckSeen;
		/**
		 * Learner thread: where the last window that stood out began (seen),
		 * -1 without one - the next window decides.
		 */
		long styleSuspectSeen = -1;
		/** Learner thread: what that window was compared with. */
		double[] styleSuspectBase;
		
		/**
		 * A real full hit, ours or theirs (client thread): how the general
		 * net really does against them, to check the copy against.
		 */
		public void noteHit(boolean ours)
		{
			if(usingAdapted && adapted != null)
				return;
			// the last REAL_HIT_WINDOW only: how they fight now
			if(realOurHits + realTheirHits >= REAL_HIT_WINDOW)
				if(realHitRing[realHitNext])
					realOurHits--;
				else
					realTheirHits--;
			realHitRing[realHitNext] = ours;
			realHitNext = (realHitNext + 1) % REAL_HIT_WINDOW;
			if(ours)
				realOurHits++;
			else
				realTheirHits++;
		}
		
		private final boolean[] realHitRing = new boolean[REAL_HIT_WINDOW];
		private int realHitNext;
		
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
		/** The general net against this copy (NaN: not measured yet). */
		double baseScore = Double.NaN;
		/** The general net's share of the hits against this copy (NaN). */
		double copyShare = Double.NaN;
		
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
	/** What you wore in the last fight (the general net's own fights). */
	private volatile PvpSim.Gear lastMyGear;
	
	/** What you wear now (AimAssist, every few seconds of a fight). */
	public void noteMyGear(PvpSim.Gear gear)
	{
		lastMyGear = gear;
	}
	
	void adaptStep(Profile p, Random rnd)
	{
		if(p.clone == null || p.newSamples >= RETRAIN_SAMPLES)
		{
			trainClone(p, rnd);
			if(p.session != null)
			{
				p.session.idleGens = 0;
				p.session.bestScore = Double.NaN;
				p.session.baseScore = Double.NaN;
				p.session.copyShare = Double.NaN;
			}
			return;
		}
		
		float[] base = general;
		if(p.session == null)
			p.session = new Session(p.adapted != null ? p.adapted : base, base);
		Session s = p.session;
		float[] clone = p.clone;
		double reach = p.reach;
		// in what both of you wear (the general net stands in for them)
		PvpSim.Gear mine = p.myGear, theirs = p.theirGear;
		
		// only tune against a copy that fights like them. v4 vs Counter: the
		// general net won 88% of the real fights but 64% against the copy;
		// its tuned net beat the copy 68% and the general net >= 50%, and
		// fell to 78% against the real one (2026-11-02)
		if(!copyFaithful(p, s, mine, theirs))
			return;
		
		for(int i = 0; i < 4; i++)
		{
			esGeneration(s, rnd, (seed, cand) -> {
				SplittableRandom r = new SplittableRandom(seed);
				// mostly the copy, sometimes the general net (stay sound)
				boolean vsClone = r.nextInt(4) != 0;
				return fight(cand, vsClone ? clone : base, vsClone, reach, seed,
					mine, theirs);
			});
			s.idleGens++;
		}
		
		if(s.gen % EVAL_EVERY < 4)
		{
			// the same fights for both, so luck can't pick the winner
			long seeds = p.id.getMostSignificantBits() ^ p.cloneVersion;
			float[] current = p.adapted != null ? p.adapted : base;
			if(Double.isNaN(s.bestScore))
				s.bestScore = winRate(current, clone, true, reach, EVAL_GAMES,
					seeds, mine, theirs);
			double score = winRate(s.theta, clone, true, reach, EVAL_GAMES,
				seeds, mine, theirs);
			// and it must still hold its own against the general net: one
			// that only beats the copy exploits where the copy plays wrong.
			// Against Counter (copy 86% beaten, the real one 63%) every
			// tuned net won 88-93% against the copy, lost 38-50% to the
			// general net and 48-59% to the real one (2026-10-29)
			// Nor when the general net already beats the copy this clearly:
			// all a tuned net can still find is where the copy plays wrong
			// (v2 vs Counter: general 90.5% vs the real one, its tuned net
			// 83%, though it held its own against the general net -
			// 2026-10-31). The general net plays on.
			if(Double.isNaN(s.baseScore))
				s.baseScore = current == base ? s.bestScore : winRate(base,
					clone, true, reach, EVAL_GAMES, seeds, mine, theirs);
			if(score > s.bestScore + 0.02 && s.baseScore < SATURATED
				&& winRate(s.theta, base, false, PvpSim.REACH, EVAL_GAMES,
					seeds, mine, theirs) >= 0.5)
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
	
	/**
	 * Whether the copy fights like the real player: the general net's share
	 * of the full hits against it is within {@link #FIDELITY} of what it got
	 * in the real fights ({@link #MIN_REAL_HITS} of them first).
	 */
	boolean copyFaithful(Profile p, Session s, PvpSim.Gear mine,
		PvpSim.Gear theirs)
	{
		int ours = p.realOurHits, real = ours + p.realTheirHits;
		if(real < MIN_REAL_HITS)
			return false;
		if(Double.isNaN(s.copyShare))
			s.copyShare = hitShare(general, p.clone, p.reach, EVAL_GAMES,
				p.id.getMostSignificantBits() ^ p.cloneVersion, mine, theirs);
		return Math.abs(s.copyShare - ours / (double)real) <= FIDELITY;
	}
	
	/** A's share of the full hits against a copy, both sides' counted. */
	double hitShare(float[] mine, float[] copy, double theirReach, int games,
		long seed, PvpSim.Gear myGear, PvpSim.Gear theirGear)
	{
		int[][] hits = new int[games][];
		runFights(games, g -> {
			PvpSim sim =
				new PvpSim(seed * 1_000_003L + g, new NetPolicy(sizes, mine),
					NetPolicy.copy(sizes, copy), MAX_TICKS);
			sim.a.onlyGoodHits = true;
			sim.b.reach = theirReach;
			sim.setGear(myGear, theirGear);
			sim.run();
			hits[g] = new int[]{sim.a.hits, sim.b.hits};
			return 0;
		});
		simFights += games;
		long a = 0, all = 0;
		for(int[] h : hits)
		{
			a += h[0];
			all += h[0] + h[1];
		}
		return all == 0 ? 0.5 : a / (double)all;
	}
	
	/**
	 * How much more surprising their moves in [before, n) were to the copy
	 * than in the windows of {@link #styleBase}, in those windows' spread
	 * (at least {@link #STYLE_MIN_SD}); NaN without a base. Each move is
	 * scored before the copy learned it, so the situation they were in is
	 * accounted for. A switch between two fighters is a step up that stays.
	 * Counting how often they pressed each key took the same fighter for a
	 * changed one: Aggressor sprinted on 19-64% of the ticks of its
	 * 30-second windows (2026-11-02).
	 */
	static double styleZ(float[] s, int before, int n, double[] base)
	{
		double[] r = surpriseSum(s, before, n);
		if(base == null || r[1] < STYLE_MIN_SCORED)
			return Double.NaN;
		// fewer scored moves than an average window: noisier
		double sd = Math.max(STYLE_MIN_SD,
			base[1] * Math.sqrt(Math.max(1, base[2] / r[1])));
		double off = r[0] / r[1] - base[0];
		lastStyleOff = off;
		lastStyleSd = sd;
		return off / sd;
	}
	
	/**
	 * Their 30-second windows in the 2 minutes before baseEnd: mean surprise,
	 * its spread and the moves scored in an average window - null without 3
	 * of them.
	 */
	static double[] styleBase(float[] s, int baseEnd)
	{
		double sum = 0, sq = 0, count = 0;
		int m = 0;
		// the last 2 minutes before it: older windows were scored by a copy
		// still learning (a fresh one's surprise fell from ~2 to ~0.5 nats)
		for(int to = baseEnd; to - RECENT_SAMPLES >= 0
			&& to > baseEnd - STYLE_WINDOWS * RECENT_SAMPLES; to -=
				RECENT_SAMPLES)
		{
			double[] c = surpriseSum(s, to - RECENT_SAMPLES, to);
			if(c[1] < STYLE_MIN_SCORED)
				continue;
			double v = c[0] / c[1];
			sum += v;
			sq += v * v;
			count += c[1];
			m++;
		}
		// two windows' spread means nothing (Aggressor: 0.012, then +0.147
		// called a change in its 3rd fight)
		if(m < 3)
			return null;
		double mean = sum / m;
		return new double[]{mean,
			Math.sqrt(Math.max(0, (sq - m * mean * mean) / (m - 1))),
			count / m};
	}
	
	/** The last check's numbers (tests). */
	static volatile double lastStyleOff, lastStyleSd;
	
	/** {sum, count} of the scored samples in [from, to). */
	private static double[] surpriseSum(float[] s, int from, int to)
	{
		double sum = 0;
		int count = 0;
		for(int i = from; i < to; i++)
			if(!Float.isNaN(s[i]))
			{
				sum += s[i];
				count++;
			}
		return new double[]{sum, count};
	}
	
	/** Learns to play like the opponent from the recorded moves. */
	void trainClone(Profile p, Random rnd)
	{
		// how the new moves surprised the copy, before it learns them
		if(p.clone != null)
			p.scoreNew(new NeuralNet(sizes, p.clone));
		float[][] x = new float[SAMPLE_CAP][];
		byte[][] y = new byte[SAMPLE_CAP][];
		float[] s = new float[SAMPLE_CAP];
		int n = p.copySamples(x, y, s);
		float[] params = (p.clone != null ? p.clone : general).clone();
		// a player who changed their style: copy how they play now (the last
		// 30 seconds) and forget the rest, not the mix of both
		// once per 30+ seconds of new moves: all of them since the last check
		// against moves already checked - the first fight after a switch
		// landed in what the next check compared with otherwise (Spammer ->
		// SprintCombo, spread 0.16 -> 0.76, missed, 2026-11-03)
		long seen = p.seen;
		int fresh = (int)Math.min(n, seen - p.styleCheckSeen);
		int from = n;
		boolean changed = false;
		if(fresh >= RECENT_SAMPLES)
		{
			int before = n - fresh;
			// a change is a step up that stays: one window standing out isn't
			// one (SprintCombo's copy, +0.875 nats for 30 s against v5 and
			// nothing changed, 2026-11-03) - the next one has to stay up
			// against the same moves before them too
			// (kept from then: the moves before it may have left the ring)
			boolean suspect = p.styleSuspectSeen >= 0;
			double[] base = suspect ? p.styleSuspectBase : styleBase(s, before);
			double z = styleZ(s, before, n, base);
			p.styleCheckSeen = seen;
			if(Double.isNaN(z) || z < (suspect ? STYLE_STAY_SD : STYLE_SD))
				p.styleSuspectSeen = -1;
			else if(!suspect)
			{
				p.styleSuspectSeen = seen - fresh;
				p.styleSuspectBase = base;
			}else
			{
				changed = true;
				from = Math.max(0, n - (int)(seen - p.styleSuspectSeen));
			}
		}
		if(changed)
		{
			// both windows (and any moves between them)
			p.keepNewest(n - from);
			p.styleSuspectSeen = -1;
			p.styleChanges++;
			Backprop.train(sizes, params, Arrays.copyOfRange(x, from, n),
				Arrays.copyOfRange(y, from, n), n - from, 6, 0.003F,
				new Random(rnd.nextLong()));
		}else
			Backprop.train(sizes, params, x, y, n, p.clone == null ? 8 : 3,
				0.003F, new Random(rnd.nextLong()));
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
				// itself in your gear; each copy in your and their gear
				if(pick == pool.size())
					return fight(cand, base, false, PvpSim.REACH, seed,
						lastMyGear, lastMyGear);
				Profile p = pool.get(pick);
				return fight(cand, p.clone, true, p.reach, seed, p.myGear,
					p.theirGear);
			});
		if(s.gen < GENERAL_GENS)
			return true;
		
		generalSession = null;
		long seeds = 0x5EED_0000L + generalVersion;
		if(general != base || adoptNewerGeneral() || winRate(s.theta, base,
			false, PvpSim.REACH, 200, seeds, lastMyGear, lastMyGear) < 0.5)
			return true;
		double before = 0, after = 0;
		for(Profile p : pool)
		{
			before += winRate(base, p.clone, true, p.reach, 60, seeds, p.myGear,
				p.theirGear);
			after += winRate(s.theta, p.clone, true, p.reach, 60, seeds,
				p.myGear, p.theirGear);
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
	 * Fights played at once: 1 in game (the learner has a CPU budget), every
	 * core in the tests. Each fight has its own seed and the results are
	 * added up in the same order, so it's the same either way.
	 */
	static volatile int parallelism = 1;
	private static java.util.concurrent.ForkJoinPool fightPool;
	
	private static synchronized java.util.concurrent.ForkJoinPool fightPool()
	{
		if(fightPool == null
			|| fightPool.getParallelism() != Math.max(1, parallelism))
			fightPool =
				new java.util.concurrent.ForkJoinPool(Math.max(1, parallelism));
		return fightPool;
	}
	
	/** Runs job(0..count-1), in parallel if parallelism says so. */
	private static double[] runFights(int count,
		java.util.function.IntToDoubleFunction job)
	{
		double[] r = new double[count];
		if(parallelism <= 1)
		{
			for(int k = 0; k < count; k++)
				r[k] = job.applyAsDouble(k);
			return r;
		}
		fightPool().submit(() -> java.util.stream.IntStream.range(0, count)
			.parallel().forEach(k -> r[k] = job.applyAsDouble(k))).join();
		return r;
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
		float[][] cands = new float[PAIRS * 2][];
		for(int i = 0; i < PAIRS; i++)
		{
			eps[i] = noise(genSeed, i, n);
			for(int sign = 0; sign < 2; sign++)
			{
				float sg = sign == 0 ? 1 : -1;
				float[] cand = cands[2 * i + sign] = new float[n];
				for(int j = 0; j < n; j++)
					cand[j] = s.theta[j] + sg * SIGMA * eps[i][j];
			}
		}
		double[] scores = runFights(PAIRS * 2 * GAMES, k -> {
			int c = k / GAMES, g = k % GAMES;
			return fitness.eval(genSeed * 31 + c / 2 * 1009L + g, cands[c]);
		});
		for(int c = 0; c < PAIRS * 2; c++)
		{
			double sum = 0;
			for(int g = 0; g < GAMES; g++)
				sum += scores[c * GAMES + g];
			fit[c] = sum / GAMES;
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
		return fight(mine, theirs, theirsIsCopy, theirReach, seed, null, null);
	}
	
	/** ... in this gear (null: PvpSim's default). */
	double fight(float[] mine, float[] theirs, boolean theirsIsCopy,
		double theirReach, long seed, PvpSim.Gear myGear, PvpSim.Gear theirGear)
	{
		// a copy of a player plays like they did, without an adapter
		PvpSim sim = new PvpSim(seed, new NetPolicy(sizes, mine),
			theirsIsCopy ? NetPolicy.copy(sizes, theirs)
				: new NetPolicy(sizes, theirs, true),
			MAX_TICKS);
		sim.a.onlyGoodHits = true;
		// a copied player hits however they like, from where they hit
		sim.b.onlyGoodHits = !theirsIsCopy;
		sim.b.reach = theirReach;
		sim.setGear(myGear, theirGear);
		return sim.score(sim.run());
	}
	
	/** Win rate of A (a draw goes by health; even = half). */
	double winRate(float[] mine, float[] theirs, boolean theirsIsCopy,
		double theirReach, int games, long seed)
	{
		return winRate(mine, theirs, theirsIsCopy, theirReach, games, seed,
			null, null);
	}
	
	/** ... in this gear (null: PvpSim's default). */
	double winRate(float[] mine, float[] theirs, boolean theirsIsCopy,
		double theirReach, int games, long seed, PvpSim.Gear myGear,
		PvpSim.Gear theirGear)
	{
		double[] results = runFights(games, g -> {
			PvpSim sim =
				new PvpSim(seed * 1_000_003L + g, new NetPolicy(sizes, mine),
					theirsIsCopy ? NetPolicy.copy(sizes, theirs)
						: new NetPolicy(sizes, theirs, true),
					MAX_TICKS);
			sim.a.onlyGoodHits = true;
			sim.b.onlyGoodHits = !theirsIsCopy;
			sim.b.reach = theirReach;
			sim.setGear(myGear, theirGear);
			int r = sim.run();
			r = sim.outcome(r);
			return r > 0 ? 1 : r < 0 ? 0 : 0.5;
		});
		double sum = 0;
		for(double r : results)
			sum += r;
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
	public static final class NetPolicy implements PvpSim.Batchable
	{
		private final FightAdapter adapter;
		private final KeySmoother smoother = new KeySmoother();
		private final MoveRules moveRules = new MoveRules();
		private final NeuralHistory history = new NeuralHistory();
		private final float[] in = new float[NeuralObservation.SIZE];
		private double lastCandy;
		private boolean copy;
		/** A copy's draws for its one-off actions. */
		private SplittableRandom copyRandom = new SplittableRandom(0);
		
		public NetPolicy(int[] sizes, float[] params)
		{
			this(sizes, params, true);
		}
		
		public NetPolicy(int[] sizes, float[] params, boolean adapting)
		{
			adapter = new FightAdapter(sizes, params, 0, adapting);
		}
		
		/**
		 * A copy of a player: their keys as recorded, without our key
		 * smoothing and item rules. With them, a copy of Counter ate on 20%
		 * of the ticks (it 6%), kept eating when it was closed in on and
		 * landed 2 sprint hits a fight to its 10 (2026-10-27).
		 */
		public static NetPolicy copy(int[] sizes, float[] params)
		{
			NetPolicy p = new NetPolicy(sizes, params, false);
			p.copy = true;
			return p;
		}
		
		/**
		 * Yes with the chance the training's weighted logit stands for: a
		 * label of 1 counted {@code pos} times shifts it by ln(pos).
		 */
		private boolean draw(float logit, float pos)
		{
			double z = logit - Math.log(pos);
			return copyRandom.nextDouble() < 1 / (1 + Math.exp(-z));
		}
		
		/** A copy's held W/S and A/D (1, 0, -1). */
		private int heldForward, heldStrafe;
		
		/**
		 * A copy's movement key: held like a player holds one (a fresh draw
		 * every ~{@link #KEY_RUN} ticks, or when the held one has become
		 * unlikely), drawn by the net's odds. Always the likeliest, a copy
		 * of Counter strafed 90% of the ticks to its 67% - it can't see
		 * Counter's strafe timer, so A and D are near even and argmax picks
		 * one of them every tick (2026-11-02).
		 */
		private int drawKey(float[] output, int from, int held)
		{
			float a = output[from], b = output[from + 1], c = output[from + 2];
			float max = Math.max(a, Math.max(b, c));
			double ea = Math.exp(a - max), eb = Math.exp(b - max),
				ec = Math.exp(c - max), sum = ea + eb + ec;
			double pHeld = (held > 0 ? ea : held < 0 ? ec : eb) / sum;
			if(pHeld >= KEY_KEEP && copyRandom.nextInt(KEY_RUN) != 0)
				return held;
			double r = copyRandom.nextDouble() * sum;
			return r < ea ? 1 : r < ea + eb ? 0 : -1;
		}
		
		static final int KEY_RUN = 6;
		static final double KEY_KEEP = 0.2;
		
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
			// one draw for both: the fight's random stream stays as it was
			long keySeed = random.nextLong();
			smoother.reset(keySeed);
			moveRules.reset(keySeed ^ 0x6D6F7665L);
			history.reset();
			lastCandy = 0;
			if(copy)
			{
				copyRandom = new SplittableRandom(random.nextLong());
				heldForward = heldStrafe = 0;
			}
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			finish(me, o, out, adapter.forward(prepare(me, o)));
		}
		
		@Override
		public float[] prepare(SimFighter me, NeuralObservation o)
		{
			// the candy since last tick, squashed like AimAssist does it
			double candy = me.candy.total();
			if(candy != lastCandy)
			{
				adapter.reward(Candy.squash(candy - lastCandy));
				lastCandy = candy;
			}
			history.context(o.context, o.memoryLong);
			o.encode(in);
			return in;
		}
		
		@Override
		public NeuralNet batchNet()
		{
			return adapter.net();
		}
		
		@Override
		public void finish(SimFighter me, NeuralObservation o, NeuralAction out,
			float[] output)
		{
			out.decode(output);
			history.push(adapter.net());
			if(copy)
			{
				// one-off actions drawn as often as the player did them: the
				// training counts a missed one 3-4x, so "> 0" fired far too
				// often - a copy of Aggressor swung on 92% of the ticks it
				// could hit, Aggressor on 17% (2026-11-02)
				out.forward = heldForward = drawKey(output, 0, heldForward);
				out.strafe = heldStrafe = drawKey(output, 3, heldStrafe);
				out.jump = draw(output[6], Backprop.POS_JUMP);
				out.attack = draw(output[9], Backprop.POS_ATTACK);
				out.web = draw(output[10], Backprop.POS_ITEM);
				out.pot = draw(output[12], Backprop.POS_ITEM);
				out.water = draw(output[14], Backprop.POS_ITEM);
				out.pearlIn = draw(output[15], Backprop.POS_ITEM);
				out.pearlOut = draw(output[16], Backprop.POS_ITEM);
				out.windJump = draw(output[17], Backprop.POS_ITEM);
				out.windHit = draw(output[18], Backprop.POS_ITEM);
				return;
			}
			smoother.apply(out);
			ItemRules.apply(o, out);
			moveRules.apply(o, out);
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
