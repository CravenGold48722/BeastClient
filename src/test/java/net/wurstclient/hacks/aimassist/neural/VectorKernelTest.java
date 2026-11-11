/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The trainer's SIMD pass must be bit for bit the plain one. */
public final class VectorKernelTest
{
	@Test
	public void sameAsPlain()
	{
		Random r = new Random(7);
		int I = NeuralObservation.SIZE, O = NeuralAction.OUTPUTS;
		int[][] shapes =
			{{I, 12, O}, {I, 8, O}, {I, 32, 16, O}, {5, 3, 2}, {I, 17, O}};
		VectorKernel kernel = new VectorKernel();
		for(int[] sizes : shapes)
		{
			float[] params = new float[NeuralNet.paramCount(sizes)];
			for(int i = 0; i < params.length; i++)
				params[i] = (float)(r.nextGaussian() * 0.4);
			NeuralNet net = new NeuralNet(sizes, params);
			float[] in = new float[sizes[0]];
			for(int k = 0; k < 500; k++)
			{
				for(int i = 0; i < in.length; i++)
					in[i] = (float)(r.nextGaussian() * 1.5);
				// biases move between passes (FightAdapter), weights in
				// place now and then (Backprop)
				if(k % 50 == 0)
				{
					params[r.nextInt(params.length)] += 0.1F;
					NeuralNet.weightsChanged();
				}
				params[params.length - 1
					- r.nextInt(sizes[sizes.length - 1])] += 0.01F;
				
				float[] plain = net.forwardPlain(in).clone();
				float[] simd = kernel.forward(net, in).clone();
				assertArrayEquals(plain, simd, 0F);
			}
		}
	}
	
	/**
	 * Two nets with the same weights and their own biases in one pass
	 * (free-for-all): each gets what forwardPlain gives it, inputs with
	 * zeros in different places included.
	 */
	@Test
	public void pairSameAsPlain()
	{
		Random r = new Random(9);
		int[] sizes = {NeuralObservation.SIZE, 48, 48, NeuralAction.OUTPUTS};
		VectorKernel kernel = new VectorKernel();
		float[] shared = new float[NeuralNet.paramCount(sizes)];
		for(int i = 0; i < shared.length; i++)
			shared[i] = (float)(r.nextGaussian() * 0.4);
		float[] pa = shared.clone(), pb = shared.clone();
		NeuralNet a = new NeuralNet(sizes, pa), b = new NeuralNet(sizes, pb);
		a.sharedWeights = b.sharedWeights = shared;
		float[] inA = new float[sizes[0]], inB = new float[sizes[0]];
		for(int k = 0; k < 300; k++)
		{
			for(int i = 0; i < inA.length; i++)
			{
				inA[i] = r.nextInt(3) == 0 ? 0 : (float)r.nextGaussian();
				inB[i] = r.nextInt(3) == 0 ? 0 : (float)r.nextGaussian();
			}
			// own biases (FightAdapter moves them)
			pa[pa.length - 1 - r.nextInt(sizes[3])] += 0.05F;
			pb[pb.length - 1 - r.nextInt(sizes[3])] -= 0.05F;
			float[] plainA = a.forwardPlain(inA).clone();
			float[] h1A = a.getActivations(1).clone();
			float[] plainB = b.forwardPlain(inB).clone();
			float[] h1B = b.getActivations(1).clone();
			kernel.forwardPair(a, inA, b, inB);
			assertArrayEquals(plainA, a.getActivations(3), 0F);
			assertArrayEquals(plainB, b.getActivations(3), 0F);
			assertArrayEquals(h1A, a.getActivations(1), 0F);
			assertArrayEquals(h1B, b.getActivations(1), 0F);
		}
	}
}
