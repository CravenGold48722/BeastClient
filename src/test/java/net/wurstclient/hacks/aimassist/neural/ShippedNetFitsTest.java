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

import java.io.DataInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * The network in the jar must be made for this version's inputs and
 * outputs. Changing {@link NeuralObservation} or {@link NeuralAction}
 * without retraining used to ship a jar whose AimAssist net didn't load and
 * whose trainer quit at every start (2026-10-19 to 10-24) - this fails the
 * build instead, with what to do.
 */
public class ShippedNetFitsTest
{
	@Test
	public void shippedNetFitsTheInputsAndOutputs() throws Exception
	{
		try(InputStream in =
			NeuralPolicy.class.getResourceAsStream(NeuralPolicy.RESOURCE))
		{
			assertNotNull(in, "missing " + NeuralPolicy.RESOURCE);
			DataInputStream din = new DataInputStream(in);
			din.readInt(); // magic
			int version = din.readInt();
			int[] sizes = new int[din.readInt()];
			for(int i = 0; i < sizes.length; i++)
				sizes[i] = din.readInt();
			
			String fix = " - retrain and ship a new net: gradlew"
				+ " trainNeuralAim -PnnArgs=\"train " + NeuralObservation.SIZE
				+ ",32,32," + NeuralAction.OUTPUTS
				+ "\" (see docs/TRAINING-GUIDE.md)";
			assertEquals(NeuralObservation.VERSION, version,
				"shipped net is for NeuralObservation v" + version + fix);
			assertEquals(NeuralObservation.SIZE, sizes[0],
				"shipped net's input count" + fix);
			assertEquals(NeuralAction.OUTPUTS, sizes[sizes.length - 1],
				"shipped net's output count" + fix);
		}
	}
}
