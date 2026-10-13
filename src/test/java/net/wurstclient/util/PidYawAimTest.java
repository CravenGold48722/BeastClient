/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.util.Mth;

/**
 * The pieces MaceAssist's aim adds to the PID aim: the sideways-only turn of
 * its approach and holding still while the crosshair is on the target.
 */
class PidYawAimTest
{
	private static final double FRAME = 1 / 60.0;
	
	/**
	 * Sideways only: the yaw lands on the target without passing it, and the
	 * pitch doesn't move at all - not even from the feed-forward, which a
	 * setpoint following the camera's own pitch would keep feeding.
	 */
	@Test
	void yawOnlyTurnKeepsPitch()
	{
		for(boolean humanize : new boolean[]{false, true})
			for(long seed = 0; seed < 20; seed++)
			{
				HumanAim aim = new HumanAim(new Random(seed));
				long now = 1_000_000_000L;
				aim.seed(0, 37.5F, now);
				aim.startTurn(now);
				
				double overshoot = 0;
				for(int f = 1; f <= 120; f++)
				{
					now += (long)(FRAME * 1e9);
					aim.updateTracking(0, 0, FRAME);
					aim.pidStepYaw(60, FRAME, now, 720, 0.5, humanize);
					overshoot = Math.max(overshoot, aim.getYaw() - 60);
					assertEquals(37.5F, aim.getPitch(), 0,
						"pitch moved, seed " + seed);
				}
				
				assertEquals(60, aim.getYaw(), 1, "yaw not on target");
				assertTrue(overshoot < 0.5,
					"overshoot " + overshoot + ", seed " + seed);
			}
	}
	
	/**
	 * Yaw first: on a turn that's as far up/down as sideways, the yaw closes
	 * much faster than the pitch, and both still land.
	 */
	@Test
	void yawFirstTurnsSidewaysFirst()
	{
		for(boolean yawFirst : new boolean[]{false, true})
		{
			HumanAim aim = new HumanAim(new Random(3));
			long now = 1_000_000_000L;
			aim.seed(0, 0, now);
			aim.startTurn(now);
			
			double pitchWhenYawDone = Double.NaN;
			for(int f = 1; f <= 180; f++)
			{
				now += (long)(FRAME * 1e9);
				aim.pidStep(40, 40, FRAME, now, 720, 1, false, yawFirst);
				if(Double.isNaN(pitchWhenYawDone) && aim.getYaw() > 38)
					pitchWhenYawDone = aim.getPitch();
			}
			
			assertEquals(40, aim.getYaw(), 0.5);
			assertEquals(40, aim.getPitch(), 0.5);
			if(yawFirst)
				assertTrue(pitchWhenYawDone < 25,
					"pitch " + pitchWhenYawDone + " when the yaw was done");
			else
				assertTrue(pitchWhenYawDone > 35,
					"pitch " + pitchWhenYawDone + " without yaw first");
		}
	}
	
	/**
	 * Picking the turn up again after holding still: it moves at once (no new
	 * reaction delay) and from where the camera was left, not from where the
	 * aim was before the hold.
	 */
	@Test
	void holdResumesWithoutReactionDelay()
	{
		HumanAim aim = new HumanAim(new Random(1));
		long now = 1_000_000_000L;
		aim.seed(0, 0, now);
		aim.startTurn(now);
		for(int f = 1; f <= 60; f++)
		{
			now += (long)(FRAME * 1e9);
			aim.pidStep(30, 0, FRAME, now, 720, 1, true);
		}
		
		// on the target for a while; meanwhile the camera ended up at 10/5
		for(int f = 1; f <= 30; f++)
		{
			now += (long)(FRAME * 1e9);
			aim.hold(10, 5, now);
		}
		assertEquals(10, aim.getYaw(), 1e-6);
		assertEquals(5, aim.getPitch(), 1e-6);
		
		now += (long)(FRAME * 1e9);
		aim.pidStep(30, 0, aim.takeDt(now), now, 720, 1, true);
		assertFalse(aim.isReacting(now));
		assertTrue(Mth.wrapDegrees(aim.getYaw() - 10) > 0.1,
			"didn't move on the first frame after the hold");
	}
}
