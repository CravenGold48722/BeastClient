/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import net.wurstclient.hacks.aimassist.neural.OnlineLearner.NetPolicy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;

/**
 * The in-fight adapter, in the simulator: series of fights against one
 * opponent, the adapter's offset carried from fight to fight like AimAssist
 * keeps it per player. It must do no worse than the same net without it
 * (same fights), learn something (the offset moves), and stay cheap.
 */
public class FightAdapterTest
{
	static int SERIES = Integer.getInteger("nn.series", 40),
		FIGHTS = Integer.getInteger("nn.fights", 8);
	
	@Test
	public void adaptsDuringFightsWithoutLosingGround()
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		assertNotNull(shipped);
		int[] sizes = shipped.getSizes();
		float[] params = shipped.getParams();
		
		double sumOn = 0, sumOff = 0;
		int n = 0;
		long tests = 0;
		for(Supplier<Policy> opp : opponents(sizes, params))
		{
			double[] on = series(sizes, params, opp, true);
			double[] off = series(sizes, params, opp, false);
			System.out.printf(
				"%-12s adapting %.1f%% (later fights %.1f%%),"
					+ " fixed %.1f%% (later %.1f%%)%n",
				opp.get().getClass().getSimpleName(), on[0] * 100, on[1] * 100,
				off[0] * 100, off[1] * 100);
			sumOn += on[0];
			sumOff += off[0];
			tests += (long)on[2];
			n++;
		}
		System.out.printf("Mean: adapting %.1f%%, fixed %.1f%%; %d tests%n",
			sumOn / n * 100, sumOff / n * 100, tests);
		assertTrue(tests > 0, "never adapted");
		assertTrue(sumOn / n > sumOff / n - 0.02,
			"adapting " + sumOn / n + " vs fixed " + sumOff / n);
	}
	
	@Test
	public void staysFastAndKeepsItsOffset()
	{
		NeuralPolicy shipped = NeuralPolicy.get();
		int[] sizes = shipped.getSizes();
		FightAdapter a = new FightAdapter(sizes, shipped.getParams(), 1, true);
		float[] in = new float[NeuralObservation.SIZE];
		for(int i = 0; i < 200_000; i++)
		{
			in[i % in.length] = (i % 7) / 7F - 0.5F;
			a.forward(in);
			if(i % 20 == 0)
				a.reward(i % 40 == 0 ? 1 : -1);
		}
		long t0 = System.nanoTime();
		int reps = 1_000_000;
		for(int i = 0; i < reps; i++)
		{
			a.forward(in);
			if(i % 20 == 0)
				a.reward(1);
		}
		double ns = (System.nanoTime() - t0) / (double)reps;
		System.out.printf("%.0f ns a decision%n", ns);
		assertTrue(ns < 5000, ns + " ns");
		
		float[] d = a.getDelta();
		FightAdapter b = new FightAdapter(sizes, shipped.getParams(), 2, true);
		b.setDelta(d);
		assertEquals(java.util.Arrays.toString(d),
			java.util.Arrays.toString(b.getDelta()));
	}
	
	/** Win rate overall, in the second half of each series, tests done. */
	static double[] series(int[] sizes, float[] params,
		Supplier<Policy> opponent, boolean adapting)
	{
		double sum = 0, later = 0;
		long tests = 0;
		for(int s = 0; s < SERIES; s++)
		{
			float[] delta = null;
			for(int f = 0; f < FIGHTS; f++)
			{
				NetPolicy me =
					new NetPolicy(sizes, params, adapting).withDelta(delta);
				PvpSim sim =
					new PvpSim(77_000L + s * 101 + f, me, opponent.get(), 600);
				sim.a.onlyGoodHits = true;
				int r = sim.run();
				if(r == 0)
					r = Float.compare(sim.a.health, sim.b.health);
				double w = r > 0 ? 1 : r < 0 ? 0 : 0.5;
				sum += w;
				if(f >= FIGHTS / 2)
					later += w;
				delta = me.getAdapter().getDelta();
				tests += me.getAdapter().getTests();
			}
		}
		return new double[]{sum / (SERIES * FIGHTS),
			later / (SERIES * (FIGHTS / 2)), tests};
	}
	
	static List<Supplier<Policy>> opponents(int[] sizes, float[] params)
	{
		return List.of(ScriptedPolicies.SprintCombo::new,
			ScriptedPolicies.CritTrader::new, ScriptedPolicies.Aggressor::new,
			ScriptedPolicies.Spammer::new, ScriptedPolicies.Counter::new,
			() -> new NetPolicy(sizes, params, false));
	}
}
