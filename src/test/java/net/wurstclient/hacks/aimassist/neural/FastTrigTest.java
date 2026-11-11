/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

public final class FastTrigTest
{
	private static final double TOLERANCE = 5e-8;
	
	@Test
	public void acos()
	{
		for(int i = 0; i <= 200_000; i++)
		{
			double x = -1 + i / 100_000.0;
			assertEquals(Math.acos(x), FastTrig.acos(x), TOLERANCE, "x=" + x);
		}
	}
	
	@Test
	public void atan2()
	{
		Random r = new Random(3);
		for(int i = 0; i < 400_000; i++)
		{
			double y = r.nextGaussian() * Math.pow(10, r.nextInt(7) - 3);
			double x = r.nextGaussian() * Math.pow(10, r.nextInt(7) - 3);
			if(i % 50 == 0)
				x = 0;
			if(i % 70 == 0)
				y = 0;
			assertEquals(Math.atan2(y, x), FastTrig.atan2(y, x), TOLERANCE,
				"y=" + y + " x=" + x);
		}
	}
}
