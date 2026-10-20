/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

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
 * it never trained against. (With the first, imitation-trained net this
 * gained 4-8 points against the real one; the self-play net already beats
 * every scripted fighter 75-95%, and tuning adds ~1-2 points - within the
 * noise of 400 fights.)
 */
public class OnlineLearnerTest
{
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
		assertNotNull(p.adapted, "never found a net that beats the copy");
		double after = winRate(sizes, p.adapted, hidden, 400);
		// the same 400 fights against the copy as the general net's
		double tunedVsCopy =
			learner.winRate(p.adapted, p.clone, true, p.reach, 400, 99);
		System.out.printf("Tuned v%d vs real %.0f%% (vs copy %.0f%%)%n",
			p.adaptedVersion, after * 100, tunedVsCopy * 100);
		// it was promoted for 2+ points on its own 120 evaluation fights;
		// on 400 fresh ones it has to be better, by less
		assertTrue(tunedVsCopy > copyLikeReal,
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
	
	/** One fight, recorded like AimAssist records its target. */
	private static void watch(OnlineLearner learner, float[] mine,
		Policy theirs, Profile p, long seed)
	{
		PvpSim sim = new PvpSim(seed,
			new OnlineLearner.NetPolicy(learner.getSizes(), mine), theirs, 600);
		sim.a.onlyGoodHits = true;
		SimFighter b = sim.b;
		p.recorder.reset();
		double[] px = new double[3], pz = new double[3], py = new double[3];
		int lastSwing = b.lastSwingTick;
		for(int t = 0; t < 600 && sim.a.health > 0 && b.health > 0; t++)
		{
			sim.step();
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
				b.serverSprint, b.sneaking, swung, p);
		}
	}
	
	private static double winRate(int[] sizes, float[] params,
		Supplier<Policy> opponent, int games)
	{
		double sum = 0;
		for(int g = 0; g < games; g++)
		{
			PvpSim sim = new PvpSim(50_000 + g,
				new OnlineLearner.NetPolicy(sizes, params), opponent.get(),
				600);
			sim.a.onlyGoodHits = true;
			int r = sim.run();
			if(r == 0)
				r = Float.compare(sim.a.health, sim.b.health);
			sum += r > 0 ? 1 : r < 0 ? 0 : 0.5;
		}
		return sum / games;
	}
}
