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

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class TriggerSweepTest
{
	// a player-sized target standing at the origin
	private static final AABB TARGET = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
	
	@Test
	void inReachNow()
	{
		Vec3 eyes = new Vec3(0, 4, 0);
		assertEquals(0,
			TriggerSweep.firstInReach(eyes, Vec3.ZERO, TARGET, Vec3.ZERO, 3));
	}
	
	@Test
	void findsTheMomentAFallComesInReach()
	{
		// eyes 5.5 above the target's head... minus 2.2 per tick: in reach
		// (3) after 2.5 / 2.2 = 1.14 ticks - not during this one
		Vec3 eyes = new Vec3(0, 7.3, 0);
		Vec3 fall = new Vec3(0, -2.2, 0);
		assertEquals(-1,
			TriggerSweep.firstInReach(eyes, fall, TARGET, Vec3.ZERO, 3));
		
		// one tick later: 0.3 into the tick (0.3 / 2.2 = 0.14)
		double f = TriggerSweep.firstInReach(eyes.add(fall), fall, TARGET,
			Vec3.ZERO, 3);
		assertEquals(0.3 / 2.2, f, 1.0 / TriggerSweep.SAMPLES);
	}
	
	@Test
	void seesAWindowBetweenTwoTicks()
	{
		// a fast fall sideways past the target: out of reach at both ends of
		// the tick, in reach in between - a check at the tick boundaries
		// never sees it
		Vec3 eyes = new Vec3(-3, 3, 0);
		Vec3 step = new Vec3(6, -1, 0);
		double reachSq = 1.5 * 1.5;
		assertTrue(TriggerSweep.distanceSq(eyes, TARGET) > reachSq);
		assertTrue(TriggerSweep.distanceSq(eyes.add(step), TARGET) > reachSq);
		
		double f =
			TriggerSweep.firstInReach(eyes, step, TARGET, Vec3.ZERO, 1.5);
		assertTrue(f > 0 && f < 1, "fraction " + f);
	}
	
	@Test
	void movingTargetCounts()
	{
		// the target walks into reach while you stand still
		Vec3 eyes = new Vec3(0, 1.6, 0);
		AABB away = TARGET.move(4, 0, 0);
		double f = TriggerSweep.firstInReach(eyes, Vec3.ZERO, away,
			new Vec3(-0.5, 0, 0), 3);
		// the near side is 3.7 away, 0.7 to go at 0.5 per tick: never in
		// this tick; at 1 per tick: 0.7 of the way through
		assertEquals(-1, f);
		f = TriggerSweep.firstInReach(eyes, Vec3.ZERO, away, new Vec3(-1, 0, 0),
			3);
		assertEquals(0.7, f, 1.0 / TriggerSweep.SAMPLES);
	}
	
	@Test
	void fastEnough()
	{
		Vec3 eyes = new Vec3(0, 40, 0);
		Vec3 fall = new Vec3(0.1, -3, 0.1);
		long start = System.nanoTime();
		int n = 20_000;
		for(int i = 0; i < n; i++)
			TriggerSweep.firstInReach(eyes, fall, TARGET, Vec3.ZERO, 3);
		double us = (System.nanoTime() - start) / 1e3 / n;
		System.out.printf("TRIGGERSWEEP %.2f us per sweep%n", us);
		assertTrue(us < 100, us + " us per sweep");
	}
}
