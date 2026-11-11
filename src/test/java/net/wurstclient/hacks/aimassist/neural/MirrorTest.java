/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The mirror image swaps left and right and nothing else. */
public class MirrorTest
{
	@Test
	public void mirrorSwapsSides()
	{
		NeuralObservation o = new NeuralObservation();
		o.mySide = 0.2;
		o.dist = 3;
		o.lastStrafe = 1;
		for(int k = 0; k < o.terrain.length; k++)
			o.terrain[k] = k / 100F;
		o.focus[1] = 0.5F;
		o.focus[0] = 0.7F;
		o.aimYawErr = 10;
		o.opAimYawErr = -5;
		o.aimPitchErr = 3;
		float[] a = new float[NeuralObservation.SIZE],
			b = new float[NeuralObservation.SIZE];
		o.encode(a);
		o.mirrored = true;
		o.encode(b);
		
		assertEquals(a[0], b[0]); // distance
		assertEquals(-a[4], b[4]); // your side
		int e = NeuralObservation.SIZE - NeuralObservation.GEAR
			- NeuralObservation.LONG - NeuralObservation.FAR
			- NeuralObservation.CONTEXT - NeuralObservation.ECHO;
		int t = e - NeuralObservation.TAIL - TerrainSense.INPUTS;
		int f = t - FocusMap.INPUTS;
		assertEquals(a[f], b[f]); // a cell's "along"
		assertEquals(-a[f + 1], b[f + 1]); // its side
		// patch: the corner on one side swaps with the other one
		assertEquals(a[t + 4], b[t]);
		assertEquals(a[t + 2], b[t + 2]); // straight ahead stays
		int dirs = t + TerrainSense.PATCH * TerrainSense.PATCH;
		assertEquals(a[dirs], b[dirs]); // toward them
		assertEquals(a[dirs + 2 * 2], b[dirs + 2 * 6]); // left <-> right
		assertEquals(a[dirs + 2 * 2 + 1], b[dirs + 2 * 6 + 1]);
		assertEquals(a[dirs + 2 * 4], b[dirs + 2 * 4]); // away
		// ECHO's: signed yaw errors flip, the pitch error doesn't
		assertEquals(-a[e + 2], b[e + 2]);
		assertEquals(a[e + 3], b[e + 3]);
		assertEquals(-a[e + 4], b[e + 4]);
		int changed = 0;
		for(int i = 0; i < a.length; i++)
			if(a[i] != b[i])
				changed++;
		// your side, last strafe, 1 focus value, 20 patch, 12 rays, 2 yaw
		// errors
		assertEquals(1 + 1 + 1 + 20 + 12 + 2, changed);
	}
}
