/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.wurstclient.util.Godbridge.SimResult;

/**
 * Bridges in every direction with {@link Godbridge#simulate}: vanilla's
 * movement, the one-tick click window per block, clicks only where the
 * crosshair really is and the already-sent rotation hits too. Starts from a
 * 5x5 platform, picks the stance the way ScaffoldWalk does.
 */
class GodbridgeTest
{
	private static final double EYE = 1.62;
	private static final double REACH = 4.5;
	
	static Set<Long> platform(double x, double z)
	{
		Set<Long> solid = new HashSet<>();
		int sx = (int)Math.floor(x);
		int sz = (int)Math.floor(z);
		for(int cx = sx - 2; cx <= sx + 2; cx++)
			for(int cz = sz - 2; cz <= sz + 2; cz++)
				solid.add(Godbridge.key(cx, cz));
		return solid;
	}
	
	static SimResult walk(double bridgeYaw, double x, double z, double distance)
	{
		return walk(bridgeYaw, x, z, distance, null);
	}
	
	/** With {@code jumps}: a godbridger's jump every 8-10 blocks. */
	static SimResult walk(double bridgeYaw, double x, double z, double distance,
		Random jumps)
	{
		Set<Long> solid = platform(x, z);
		double[] anchor = Godbridge.chooseAnchor(bridgeYaw, x, z);
		Godbridge.Body body = Godbridge.Body.standing(x, z);
		double stance = Godbridge.chooseStance(solid, bridgeYaw, anchor[0],
			anchor[1], body, REACH, bridgeYaw);
		return Godbridge.simulate(solid, bridgeYaw, stance, anchor[0],
			anchor[1], body, stance, Godbridge.NOMINAL_PITCH, REACH, distance,
			(int)(distance * 40), false, jumps);
	}
	
	@Test
	void bridgesEveryDirection()
	{
		Random random = new Random(1);
		int runs = 0;
		int falls = 0;
		long ticks = 0;
		long sneakTicks = 0;
		double min = 90;
		double max = 0;
		double worstSneak = 0;
		double worstSneakYaw = 0;
		StringBuilder fallList = new StringBuilder();
		
		for(int yaw = 0; yaw < 360; yaw++)
			for(int i = 0; i < 4; i++)
			{
				double x = 0.2 + random.nextDouble() * 0.6;
				double z = 0.2 + random.nextDouble() * 0.6;
				SimResult r = walk(yaw, x, z, 30);
				runs++;
				if(r.fell())
				{
					falls++;
					if(fallList.length() < 600)
						fallList.append(
							String.format(" %d(%.1f)", yaw, r.travelled()));
				}
				ticks += r.ticks();
				sneakTicks += r.sneakTicks();
				min = Math.min(min, r.minPitch());
				max = Math.max(max, r.maxPitch());
				double share = r.sneakTicks() / (double)r.ticks();
				if(share > worstSneak)
				{
					worstSneak = share;
					worstSneakYaw = yaw;
				}
			}
		
		System.out.printf(
			"GODBRIDGE %d runs, %d falls%s; sneaking %.1f%% of ticks (worst"
				+ " %.0f%% at %.0f deg); pitch %.1f-%.1f%n",
			runs, falls, fallList, 100.0 * sneakTicks / ticks, worstSneak * 100,
			worstSneakYaw, min, max);
		assertEquals(0, falls, "fell at:" + fallList);
	}
	
	@Test
	void straightAndDiagonalNeedNoSneaking()
	{
		// the classic godbridge and the diagonal one: pure clicking, the
		// way players do them
		for(int yaw : new int[]{0, 45, 90, 135, 180, 225, 270, 315})
			for(double offset : new double[]{0.3, 0.5, 0.7})
			{
				SimResult r = walk(yaw, offset, offset, 40);
				assertFalse(r.fell(), "fell at " + yaw + ", offset " + offset);
				assertEquals(0, r.sneakTicks(), "sneaked " + r.sneakTicks()
					+ " ticks at " + yaw + " deg, offset " + offset);
			}
	}
	
	@Test
	void fastEnough()
	{
		// stance choice once per bridge, planning every tick
		Random random = new Random(3);
		for(int warm = 0; warm < 20; warm++)
			walk(random.nextInt(360), 0.5, 0.5, 10);
		
		long start = System.nanoTime();
		int n = 40;
		for(int i = 0; i < n; i++)
		{
			double yaw = random.nextInt(360);
			Godbridge.chooseStance(platform(0.5, 0.5), yaw, 0.5, 0.5,
				Godbridge.Body.standing(0.5, 0.5), REACH, yaw);
		}
		double stanceMs = (System.nanoTime() - start) / 1e6 / n;
		
		start = System.nanoTime();
		long ticks = 0;
		for(int i = 0; i < n; i++)
			ticks += walk(random.nextInt(360), 0.5, 0.5, 20).ticks();
		// walk() = one stance choice + the bridge's ticks
		double tickMs =
			((System.nanoTime() - start) / 1e6 - stanceMs * n) / ticks;
		
		System.out.printf(
			"GODBRIDGE stance choice %.1f ms, plan %.3f ms/tick%n", stanceMs,
			tickMs);
		assertTrue(stanceMs < 50, "stance choice " + stanceMs + " ms");
		assertTrue(tickMs < 1, "planning " + tickMs + " ms per tick");
	}
	
	@Test
	void jumpsLikeAGodbridger()
	{
		// a jump every 8-10 blocks, the next blocks placed in the air - in
		// every direction, and still never falling
		Random random = new Random(4);
		int runs = 0;
		int falls = 0;
		long jumps = 0;
		long placed = 0;
		long ticks = 0;
		long sneakTicks = 0;
		double maxAim = 0;
		StringBuilder fallList = new StringBuilder();
		for(int yaw = 0; yaw < 360; yaw += 3)
			for(int i = 0; i < 2; i++)
			{
				SimResult r = walk(yaw, 0.2 + random.nextDouble() * 0.6,
					0.2 + random.nextDouble() * 0.6, 40, random);
				runs++;
				jumps += r.jumps();
				placed += r.placed();
				ticks += r.ticks();
				sneakTicks += r.sneakTicks();
				maxAim = Math.max(maxAim, r.maxAimSpeed());
				if(r.fell())
				{
					falls++;
					if(fallList.length() < 400)
						fallList.append(
							String.format(" %d(%.1f)", yaw, r.travelled()));
				}
			}
		
		System.out.printf(
			"GODBRIDGE jumping: %d runs, %d falls%s; a jump every %.1f blocks;"
				+ " sneaking %.1f%%; aim up to %.0f deg/s%n",
			runs, falls, fallList, placed / (double)Math.max(1, jumps),
			100.0 * sneakTicks / ticks, maxAim);
		assertEquals(0, falls, "fell at:" + fallList);
		assertTrue(jumps > runs * 2, "only " + jumps + " jumps");
	}
}
