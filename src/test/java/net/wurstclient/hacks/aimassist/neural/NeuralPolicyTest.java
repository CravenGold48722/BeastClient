/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The network that ships in the jar: it loads, it's cheap, and in the
 * simulator it beats every scripted fighter - Beast's own Sprint combo
 * included.
 */
public class NeuralPolicyTest
{
	@Test
	public void shippedNetBeatsTheScriptedFighters() throws Exception
	{
		NeuralPolicy policy = NeuralPolicy.get();
		assertNotNull(policy, "couldn't load " + NeuralPolicy.RESOURCE);
		
		int[] sizes = policy.getSizes();
		float[] params = netParams();
		double[] rates =
			NeuralTrainer.benchmarkRates(sizes, params, null, 400, 4242);
		System.out.println("Shipped net " + java.util.Arrays.toString(sizes)
			+ " vs scripted: " + NeuralTrainer.fmt(rates));
		for(int i = 0; i < rates.length; i++)
			assertTrue(rates[i] > 0.55, "lost to " + ScriptedPolicies.NAMES[i]
				+ ": " + NeuralTrainer.fmt(rates));
			
		// and it plays both sides of a mirror match evenly (a sanity check
		// of the simulator: no side has an edge)
		double mirror =
			NeuralTrainer.winRate(NeuralTrainer.netSpec(sizes, params),
				NeuralTrainer.netSpec(sizes, params), 400, 99);
		assertTrue(Math.abs(mirror - 0.5) < 0.08, "mirror " + mirror);
	}
	
	@Test
	public void aDecisionTakesMicroseconds() throws Exception
	{
		NeuralPolicy policy = NeuralPolicy.get();
		assertNotNull(policy);
		double ns =
			NeuralTrainer.nanosPerForward(policy.getSizes(), netParams());
		System.out.printf("%.0f ns per decision%n", ns);
		// one decision a tick = 20 a second
		assertTrue(ns < 5_000, ns + " ns");
	}
	
	private static float[] netParams() throws Exception
	{
		try(var in =
			NeuralPolicy.class.getResourceAsStream(NeuralPolicy.RESOURCE))
		{
			return NeuralNet.read(in).getParams();
		}
	}
}
