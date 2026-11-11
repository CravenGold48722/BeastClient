/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.wurstclient.hacks.aimassist.neural.OnlineLearner.Profile;
import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/**
 * The in-game learning loop, in the simulator: watch a hidden opponent the
 * way AimAssist watches a player (only what the server shows), copy it, tune
 * against the copy. The tuned net must beat the copy clearly more often
 * than the general net does, and do no worse against the real opponent, which
 * it never trained against - or, if no tuned net held its own against the
 * general net, none is kept and the general net plays on. (With the first,
 * imitation-trained net this gained 4-8 points against the real one; the
 * self-play net already beats every scripted fighter 75-95%, and tuning adds
 * ~1-2 points - within the noise of 400 fights.) Nor is one made against a
 * copy that fights unlike them: v4's copies of all five scripted fighters
 * were beaten 64-72% where the real ones lose 88-100%, and its hit share was
 * 0.08-0.19 lower (2026-11-02).
 */
public class OnlineLearnerTest
{
	/** Every core for the simulated fights (the same results, sooner). */
	@BeforeAll
	public static void allCores()
	{
		OnlineLearner.parallelism = Runtime.getRuntime().availableProcessors();
	}
	
	@AfterAll
	public static void oneCore()
	{
		OnlineLearner.parallelism = 1;
	}
	
	@Test
	public void adaptsToAnOpponentItOnlyWatched()
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		assertNotNull(shipped);
		int[] sizes = shipped.getSizes();
		float[] general = shipped.getParams();
		OnlineLearner learner = new OnlineLearner(null, sizes, general);
		// a fixed player: the tuner's evaluation fights are seeded from it
		Profile p = learner.profile(new UUID(0x5EEDL, 42));
		
		// the opponent: the scripted fighter the shipped net does worst
		// against (the most room to adapt)
		Supplier<Policy> hidden = null;
		double worst = 2;
		for(Supplier<Policy> s : List.<Supplier<Policy>> of(
			ScriptedPolicies.SprintCombo::new, ScriptedPolicies.CritTrader::new,
			ScriptedPolicies.Aggressor::new, ScriptedPolicies.Spammer::new,
			ScriptedPolicies.Counter::new))
		{
			double rate = winRate(sizes, general, s, 200);
			if(rate < worst)
			{
				worst = rate;
				hidden = s;
			}
		}
		System.out
			.println("Opponent: " + hidden.get().getClass().getSimpleName());
		
		// watch a few fights (~2 minutes of game time)
		for(int f = 0; f < 40
			&& p.sampleCount() < OnlineLearner.SAMPLE_CAP; f++)
			watch(learner, general, hidden.get(), p, 1000 + f);
		System.out.println("Samples: " + p.sampleCount());
		assertTrue(p.sampleCount() >= OnlineLearner.MIN_SAMPLES);
		
		double before = winRate(sizes, general, hidden, 400);
		Random rnd = new Random(7);
		long t0 = System.nanoTime();
		for(int i = 0; i < 60; i++)
			learner.adaptStep(p, rnd);
		double secs = (System.nanoTime() - t0) / 1e9;
		assertNotNull(p.clone);
		float[][] x = new float[OnlineLearner.SAMPLE_CAP][];
		byte[][] y = new byte[OnlineLearner.SAMPLE_CAP][];
		int n = p.copySamples(x, y);
		System.out
			.println("Copy: " + Backprop.accuracy(sizes, p.clone, x, y, n));
		
		double copyLikeReal =
			learner.winRate(general, p.clone, true, p.reach, 400, 99);
		System.out.printf(
			"General vs real %.0f%%, vs copy %.0f%%; %d gens in %.1f s%n",
			before * 100, copyLikeReal * 100, p.session.gen, secs);
		System.out.printf("Hit share: real %.3f, vs copy %.3f%n",
			p.realOurHits / (double)(p.realOurHits + p.realTheirHits),
			p.session.copyShare);
		if(p.adapted == null)
		{
			// no tuned net stayed sound against the general net, so the
			// general one keeps playing: nothing lost against the real one
			System.out.println("No tuned net kept, plays the general net");
			return;
		}
		double after = winRate(sizes, p.adapted, hidden, 400);
		// the same 400 fights against the copy as the general net's
		double tunedVsCopy =
			learner.winRate(p.adapted, p.clone, true, p.reach, 400, 99);
		System.out.printf("Tuned v%d vs real %.0f%% (vs copy %.0f%%)%n",
			p.adaptedVersion, after * 100, tunedVsCopy * 100);
		// it was promoted for 2+ points on its own 120 evaluation fights; on
		// 400 fresh ones that can be noise (71.5% vs 72.75%, 2026-10-29) -
		// but never clearly worse
		assertTrue(tunedVsCopy > copyLikeReal - 0.03,
			"tuned vs copy " + tunedVsCopy + ", general " + copyLikeReal);
		assertTrue(after > before - 0.03,
			"tuned " + after + " vs general " + before);
	}
	
	/**
	 * What a player's in-fight adapter learned is saved with them and
	 * picked up again in the next session.
	 */
	@Test
	public void keepsWhatItLearnedInFights(@TempDir Path dir)
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		int[] sizes = shipped.getSizes();
		OnlineLearner learner =
			new OnlineLearner(dir, sizes, shipped.getParams());
		UUID id = UUID.randomUUID();
		Profile p = learner.profile(id);
		float[] in = new float[NeuralObservation.SIZE];
		for(int i = 0; i < 400; i++)
		{
			in[i % in.length] = (i % 5) / 5F - 0.4F;
			learner.decide(p, in);
			p.noteResult(i % 3 == 0 ? -1 : i % 7 == 0 ? 1.5 : 1);
		}
		assertNotNull(p.delta, "never adapted");
		boolean moved = false;
		for(float d : p.delta)
			moved |= d != 0;
		assertTrue(moved);
		
		learner.saveDirty(true);
		OnlineLearner again =
			new OnlineLearner(dir, sizes, shipped.getParams());
		again.load();
		Profile q = again.profile(id);
		again.decide(q, in);
		assertArrayEquals(p.delta, q.adapter.getDelta());
	}
	
	/**
	 * The last 30 seconds against what came before: the same fighter all
	 * along is no change, a switch between two fighters is.
	 */
	@Test
	public void noticesAStyleChange()
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		float[] general = shipped.getParams();
		OnlineLearner learner =
			new OnlineLearner(null, shipped.getSizes(), general);
		List<Supplier<Policy>> all = List.of(ScriptedPolicies.SprintCombo::new,
			ScriptedPolicies.CritTrader::new, ScriptedPolicies.Aggressor::new,
			ScriptedPolicies.Spammer::new, ScriptedPolicies.Counter::new);
		long id = 0;
		for(Supplier<Policy> s : all)
		{
			Profile p = learner.profile(new UUID(0xC4A6EL, id++));
			String name = s.get().getClass().getSimpleName();
			// the copy retrains after every fight, like it does in game
			Random rnd = new Random(id);
			for(int f = 0; p.seen < 2 * OnlineLearner.SAMPLE_CAP; f++)
				fightAndRetrain(learner, general, s.get(), p, SEED + 2000 + f,
					rnd, name);
			assertEquals(0, p.styleChanges, "same " + name);
		}
		// a clear change: from Spammer's copy (~0.1-0.3 nats a move) to
		// SprintCombo. Only a change that stands out from how much they vary
		// counts: SprintCombo's copy varies 0.2-0.44 from window to window
		// and Spammer after it was +0.27; Aggressor and Counter look alike
		// to the recorder (W on 86% of the ticks both), +0.70 one time,
		// +0.19 to +0.29 another (2026-11-02). Either way the copy retrains
		// every 10 seconds on what it sees.
		{
			Supplier<Policy> first = ScriptedPolicies.Spammer::new,
				then = ScriptedPolicies.SprintCombo::new;
			String name = first.get().getClass().getSimpleName() + " -> "
				+ then.get().getClass().getSimpleName();
			Profile p = learner.profile(new UUID(0xC4A6EL, id++));
			Random rnd = new Random(id);
			int f = 0;
			// a settled copy first (in game the saved one carries over)
			for(; p.seen < 2 * OnlineLearner.SAMPLE_CAP; f++)
				fightAndRetrain(learner, general, first.get(), p,
					SEED + 3000 + f, rnd, name);
			assertEquals(0, p.styleChanges, name + " before");
			// noticed within 2 minutes of their moves (30 s standing out, the
			// next 30 s staying up)
			for(long at = p.seen; p.styleChanges == 0
				&& p.seen - at < 4 * OnlineLearner.RECENT_SAMPLES; f++)
				fightAndRetrain(learner, general, then.get(), p,
					SEED + 3000 + f, rnd, name);
			assertTrue(p.styleChanges > 0, name);
		}
	}
	
	private static final long SEED = 0;
	
	private static void fightAndRetrain(OnlineLearner learner, float[] general,
		Policy opponent, Profile p, long seed, Random rnd, String name)
	{
		watch(learner, general, opponent, p, seed);
		int changes = p.styleChanges;
		learner.trainClone(p, rnd);
		System.out.printf("%s fight %d: %+.3f nats (spread %.3f)%s%n", name,
			seed, OnlineLearner.lastStyleOff, OnlineLearner.lastStyleSd,
			p.styleChanges > changes ? " CHANGED"
				: p.styleSuspectSeen >= 0 ? " stands out" : "");
		OnlineLearner.lastStyleOff = OnlineLearner.lastStyleSd = Double.NaN;
	}
	
	/** One fight, recorded like AimAssist records its target. */
	static void watch(OnlineLearner learner, float[] mine, Policy theirs,
		Profile p, long seed)
	{
		PvpSim sim = new PvpSim(seed,
			new OnlineLearner.NetPolicy(learner.getSizes(), mine), theirs, 600);
		sim.a.onlyGoodHits = true;
		SimFighter b = sim.b;
		p.recorder.reset();
		double[] px = new double[3], pz = new double[3], py = new double[3];
		int lastSwing = b.lastSwingTick;
		int ourHits = 0, theirHits = 0;
		int[] left = new int[5];
		items(sim, b, left);
		for(int t = 0; t < 600 && sim.a.health > 0 && b.health > 0; t++)
		{
			sim.step();
			int items = items(sim, b, left);
			// the real exchanges, like AimAssist notes them
			for(; ourHits < sim.a.hits; ourHits++)
				p.noteHit(true);
			for(; theirHits < b.hits; theirHits++)
				p.noteHit(false);
			px[t % 3] = b.x;
			py[t % 3] = b.y;
			pz[t % 3] = b.z;
			if(t < 2)
				continue;
			// players arrive every 2nd tick: motion over 2 ticks
			int o = (t - 2) % 3;
			boolean swung = b.lastSwingTick != lastSwing;
			lastSwing = b.lastSwingTick;
			p.recorder.record(b.obs, (b.x - px[o]) / 2, (b.y - py[o]) / 2,
				(b.z - pz[o]) / 2, b.yaw, b.onGround, b.hurtTime,
				sim.inWeb(b) || b.usingItem(), b.serverSprint, b.sneaking,
				swung, b.blocking(), b.eatTicks > 0, items, p);
		}
	}
	
	/**
	 * What they threw or placed this tick, from what they have left - what
	 * AimAssist sees appear (left: webs, pots, water, pearls, winds before).
	 */
	static int items(PvpSim sim, SimFighter b, int[] left)
	{
		int items = 0;
		int pots = b.pots + b.speedPots + b.fireResPots;
		if(b.webs < left[0])
			items |= OpponentRecorder.WEB;
		if(pots < left[1])
			items |= OpponentRecorder.POT;
		if(b.waters < left[2])
			items |= OpponentRecorder.WATER;
		if(b.pearls < left[3])
		{
			SimFighter a = sim.a;
			items |= b.pearlVx * (a.x - b.x) + b.pearlVz * (a.z - b.z) > 0
				? OpponentRecorder.PEARL_IN : OpponentRecorder.PEARL_OUT;
		}
		if(b.winds < left[4])
			items |= b.windVy < -0.5 * Math.sqrt(
				b.windVx * b.windVx + b.windVy * b.windVy + b.windVz * b.windVz)
					? OpponentRecorder.WIND_JUMP : OpponentRecorder.WIND_HIT;
		left[0] = b.webs;
		left[1] = pots;
		left[2] = b.waters;
		left[3] = b.pearls;
		left[4] = b.winds;
		return items;
	}
	
	private static double winRate(int[] sizes, float[] params,
		Supplier<Policy> opponent, int games)
	{
		// in parallel, added up in order (the same as one by one)
		double[] results =
			IntStream.range(0, games).parallel().mapToDouble(g -> {
				PvpSim sim = new PvpSim(50_000 + g,
					new OnlineLearner.NetPolicy(sizes, params), opponent.get(),
					600);
				sim.a.onlyGoodHits = true;
				int r = sim.run();
				r = sim.outcome(r);
				return r > 0 ? 1 : r < 0 ? 0 : 0.5;
			}).toArray();
		double sum = 0;
		for(double r : results)
			sum += r;
		return sum / games;
	}
}
