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

/** A widened net plays exactly like the one it was made from. */
public final class WidenTest
{
	@Test
	public void widenedNetComputesTheSame()
	{
		// an older observation (10 inputs fewer), half as wide
		int[] from = {NeuralObservation.SIZE - 10, 32, 32,
			NeuralAction.OUTPUTS - NeuralAction.MEMORY};
		int[] to = {NeuralObservation.SIZE, 64, 64, NeuralAction.OUTPUTS};
		Random random = new Random(3);
		float[] p = new float[NeuralNet.paramCount(from)];
		for(int i = 0; i < p.length; i++)
			p[i] = (float)random.nextGaussian() * 0.3F;
		NeuralNet small = new NeuralNet(from, p);
		// the old net sees the first inputs; the new ones it never had
		// must not change a thing
		NeuralNet wide =
			new NeuralNet(to, ContinuousTrainer.widen(from, to, p, random));
		for(int k = 0; k < 50; k++)
		{
			float[] x = new float[NeuralObservation.SIZE];
			for(int i = 0; i < x.length; i++)
				x[i] = (float)random.nextGaussian();
			float[] old = java.util.Arrays.copyOf(x, from[0]);
			// the new memory outputs only add on
			assertArrayEquals(small.forward(old).clone(),
				java.util.Arrays.copyOf(wide.forward(x), from[3]), 1e-4F);
		}
	}
}
