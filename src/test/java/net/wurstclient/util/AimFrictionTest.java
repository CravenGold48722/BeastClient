/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** AimAssist's Stickiness: your mouse, slowed down near the target. */
public class AimFrictionTest
{
	private static final double HALF_WIDTH = 6.5;
	
	private static AimFriction.Target at(double errorYaw)
	{
		return new AimFriction.Target(errorYaw, 0, HALF_WIDTH, -14, 14);
	}
	
	/** Leaving from on the target: slowed by the full stickiness. */
	@Test
	public void leavingIsSlowed()
	{
		double[] out = AimFriction.slow(0.01, 0, at(0.5), 0.75);
		assertEquals(0.0025, out[0], 1e-9);
	}
	
	/** Closing in at the hitbox's edge: slowed by half as much. */
	@Test
	public void closingIsSlowedLess()
	{
		double[] out = AimFriction.slow(0.01, 0, at(-HALF_WIDTH), 0.75);
		assertEquals(0.01 * (1 - 0.375), out[0], 1e-6);
	}
	
	/** Closing in and leaving meet at the aim point (no dead stop). */
	@Test
	public void continuousAtAimPoint()
	{
		double closing = AimFriction.slow(0.01, 0, at(-1e-6), 0.75)[0];
		double leaving = AimFriction.slow(0.01, 0, at(1e-6), 0.75)[0];
		assertEquals(leaving, closing, 1e-6);
	}
	
	/**
	 * A flick that arrives in one big burst (a low frame rate) is slowed only
	 * while it's still near the target: the same as in small steps.
	 */
	@Test
	public void burstSameAsSmallSteps()
	{
		double burst = AimFriction.slow(60, 0, at(0), 0.75)[0];
		double steps = 0;
		for(int i = 0; i < 600; i++)
			steps += AimFriction.slow(0.1, 0, at(steps), 0.75)[0];
		assertEquals(steps, burst, 0.5);
		// hitbox and zone at a quarter speed and fading, the rest full
		assertTrue(burst > 30, "a 60 degree flick only got " + burst);
	}
	
	/** Further out than the zone: your mouse at full speed. */
	@Test
	public void untouchedOutsideZone()
	{
		double[] out = AimFriction.slow(3, -2,
			at(HALF_WIDTH + AimFriction.ZONE + 0.1), 0.75);
		assertEquals(3, out[0], 1e-9);
		assertEquals(-2, out[1], 1e-9);
	}
	
	/** The output is whole mouse counts; the fractions add up over frames. */
	@Test
	public void wholeMouseCounts()
	{
		AimFriction friction = new AimFriction();
		double step = 0.15;
		double total = 0;
		for(int i = 0; i < 100; i++)
		{
			double[] c = friction.toCounts(0.037, -0.02, step, 1, -1);
			assertEquals(Math.rint(c[0]), c[0]);
			assertEquals(Math.rint(c[1]), c[1]);
			total += c[0] * step;
		}
		assertEquals(3.7, total, step);
	}
}
