/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ECHO's inputs: the signs, the look ray, the opponent model, the pool. */
public class EchoFeaturesTest
{
	@Test
	public void aimErrorsHaveTheRightSigns()
	{
		// facing +z (yaw 0); -x is to your right (yaw 90)
		assertEquals(0, EchoFeatures.yawError(0, 0, 0, 0, 5), 0.5);
		assertEquals(90, EchoFeatures.yawError(0, 0, 0, -5, 0), 0.5);
		assertEquals(-90, EchoFeatures.yawError(0, 0, 0, 5, 0), 0.5);
		// wraps: 350 degrees the other way is 10
		assertEquals(-10,
			EchoFeatures.yawError(0, 0, 0, -5 * Math.sin(Math.toRadians(-10)),
				5 * Math.cos(Math.toRadians(-10))),
			0.5);
		// something below you: look down (+)
		assertTrue(EchoFeatures.pitchError(0, 1.62, 0, 0, 0, 0, 3) > 20);
	}
	
	@Test
	public void theLookRayHitsTheBox()
	{
		double[] look = new double[3];
		EchoFeatures.look(0, 0, look);
		// straight at a player 2.5 blocks ahead
		assertTrue(EchoFeatures.rayHitsBox(0, 1.62, 0, look[0], look[1],
			look[2], 0, 0, 2.8, 1.8, 3));
		// out of reach
		assertFalse(EchoFeatures.rayHitsBox(0, 1.62, 0, look[0], look[1],
			look[2], 0, 0, 3.6, 1.8, 3));
		// beside the line
		assertFalse(EchoFeatures.rayHitsBox(0, 1.62, 0, look[0], look[1],
			look[2], 1, 0, 2.5, 1.8, 3));
		// looking at the sky
		EchoFeatures.look(0, -80, look);
		assertFalse(EchoFeatures.rayHitsBox(0, 1.62, 0, look[0], look[1],
			look[2], 0, 0, 2.5, 1.8, 3));
	}
	
	@Test
	public void theModelLearnsTheirStyle()
	{
		EchoFeatures echo = new EchoFeatures();
		echo.reset(new EchoFeatures.OpponentModel());
		NeuralObservation o = new NeuralObservation();
		o.dist = 2;
		o.sinceTheirHit = 1000;
		for(int t = 0; t < 2000; t++)
		{
			// always walking in, swinging every 10 ticks
			o.opAlong = 0.2;
			o.opSwung = t % 10 == 0;
			echo.update(o);
		}
		assertTrue(o.mAggression > 0.9, "aggression " + o.mAggression);
		assertEquals(2, o.mSpacing, 0.1);
		assertEquals(10, o.mInterval, 0.5);
		assertEquals(0, o.dDist, 1e-9);
	}
	
	@Test
	public void historyAttendsToTheLastTicks()
	{
		NeuralHistory h = new NeuralHistory();
		float[] ctx = new float[NeuralHistory.WIDTH],
			ctxLong = new float[NeuralHistory.WIDTH];
		h.context(ctx, ctxLong);
		for(float c : ctx)
			assertEquals(0, c);
		int[] sizes = {2, NeuralHistory.IN, 1};
		float[] p = new float[NeuralNet.paramCount(sizes)];
		int att = NeuralNet.layerCount(sizes);
		System.arraycopy(NeuralHistory.initialParams(), 0, p, att,
			NeuralHistory.PARAMS);
		// a net whose first layer is all biases of 0.5
		for(int o = 0; o < NeuralHistory.IN; o++)
			p[2 * NeuralHistory.IN + o] = 0.5F;
		NeuralNet net = new NeuralNet(sizes, p);
		for(int t = 0; t < 200; t++)
		{
			net.forwardPlain(new float[2]);
			h.push(net);
		}
		assertEquals(NeuralHistory.TICKS, h.size());
		assertEquals(10, h.seconds());
		h.context(ctx, ctxLong);
		// every stored tick is the same: whatever the heads attend to, the
		// mix is that tick's value projection
		float x = NeuralNet.tanh(0.5F);
		for(int j = 0; j < NeuralHistory.WIDTH; j++)
		{
			float v = 0;
			for(int c = 0; c < NeuralHistory.IN; c++)
				v += p[att + NeuralHistory.WV + j * NeuralHistory.IN + c] * x;
			assertEquals(v, ctx[j], 1e-4);
			assertEquals(v, ctxLong[j], 1e-4);
		}
		// the same player again: the minutes stay, the seconds go
		h.resetFight();
		assertEquals(0, h.size());
		assertEquals(10, h.seconds());
		assertEquals(Math.exp(-3), NeuralHistory.exp(-3), 1e-4);
		assertEquals(1, NeuralHistory.exp(0), 1e-4);
	}
	
	/** The trainer's SIMD loops give exactly what the game's plain ones do. */
	@Test
	public void simdHistoryIsThePlainOne()
	{
		int[] sizes = {4, NeuralHistory.IN, 2};
		float[] p = new float[NeuralNet.paramCount(sizes)];
		java.util.Random r = new java.util.Random(3);
		for(int i = 0; i < p.length; i++)
			p[i] = (float)r.nextGaussian();
		NeuralNet net = new NeuralNet(sizes, p);
		NeuralHistory plain = new NeuralHistory(), simd = new NeuralHistory();
		float[] a = new float[NeuralHistory.WIDTH],
			b = new float[NeuralHistory.WIDTH];
		float[] al = new float[NeuralHistory.WIDTH],
			bl = new float[NeuralHistory.WIDTH];
		VectorKernel.HistoryOps ops = new VectorKernel.HistoryOps();
		for(int t = 0; t < 300; t++)
		{
			float[] in = new float[4];
			for(int i = 0; i < 4; i++)
				in[i] = (float)r.nextGaussian();
			net.forwardPlain(in);
			NeuralHistory.setOps(null);
			plain.push(net);
			plain.context(a, al);
			NeuralHistory.setOps(ops);
			simd.push(net);
			simd.context(b, bl);
			NeuralHistory.setOps(null);
			org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, 0F);
			org.junit.jupiter.api.Assertions.assertArrayEquals(al, bl, 0F);
		}
		// (the same either way; the trainer's tests run with SIMD)
		NeuralHistory.setOps(ops);
	}
}
