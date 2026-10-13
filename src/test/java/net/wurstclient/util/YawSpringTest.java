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
 * MaceAssist's sideways-only aim ({@link HumanAim#springYawStep}), driven
 * every frame at 60 fps: smooth (no jump in speed from one frame to the
 * next), never past the target, the pitch untouched, the speed setting
 * respected, and a moving target followed closely.
 */
class YawSpringTest
{
	private static final double FRAME = 1 / 60.0;
	private static final long FRAME_NS = (long)(FRAME * 1e9);
	
	private static HumanAim start(float yaw, float pitch)
	{
		HumanAim aim = new HumanAim(new Random(1));
		long now = 1_000_000_000L;
		aim.seed(yaw, pitch, now);
		aim.startTurn(now);
		return aim;
	}
	
	@Test
	void landsWithoutOvershootAndKeepsPitch()
	{
		for(double speed : new double[]{15, 180, 720, 1800})
		{
			HumanAim aim = start(0, 37.5F);
			long now = 1_000_000_000L;
			double overshoot = 0;
			for(int f = 1; f <= 600; f++)
			{
				now += FRAME_NS;
				aim.updateTracking(0, 0, FRAME);
				aim.springYawStep(90, FRAME, now, speed, false);
				overshoot = Math.max(overshoot, aim.getYaw() - 90);
				assertEquals(37.5F, aim.getPitch(), 0, "pitch moved");
			}
			
			assertEquals(90, aim.getYaw(), 0.05, "not there at " + speed);
			assertEquals(0, overshoot, 1e-4, "overshoot at " + speed);
		}
	}
	
	/** No snapping: the speed changes gradually, never jumps. */
	@Test
	void speedChangesSmoothly()
	{
		for(double speed : new double[]{180, 720, 1800})
		{
			HumanAim aim = start(0, 0);
			long now = 1_000_000_000L;
			double lastYaw = 0;
			double lastSpeed = 0;
			double maxJump = 0;
			double fastest = 0;
			for(int f = 1; f <= 120; f++)
			{
				now += FRAME_NS;
				aim.springYawStep(120, FRAME, now, speed, false);
				double v = (aim.getYaw() - lastYaw) / FRAME;
				maxJump = Math.max(maxJump, Math.abs(v - lastSpeed));
				fastest = Math.max(fastest, v);
				lastYaw = aim.getYaw();
				lastSpeed = v;
			}
			
			// (yaw is a float: a hair of rounding)
			assertTrue(fastest <= speed * 1.0001,
				"faster than " + speed + ": " + fastest);
			// starting from rest, the first frame can't be anywhere near full
			// speed: no frame changes the speed by more than a third of the
			// top speed (an instant start would be all of it)
			assertTrue(maxJump < speed / 3 + 1,
				"speed jumped by " + maxJump + " at " + speed);
		}
	}
	
	/** 1800 degrees/s really is fast: a 90-degree turn in well under 0.2s. */
	@Test
	void fastSettingTurnsFast()
	{
		HumanAim aim = start(0, 0);
		long now = 1_000_000_000L;
		int frames = 0;
		while(Math.abs(Mth.wrapDegrees(90 - aim.getYaw())) > 1 && frames < 600)
		{
			now += FRAME_NS;
			aim.springYawStep(90, FRAME, now, 1800, false);
			frames++;
		}
		assertTrue(frames * FRAME < 0.2, "took " + frames * FRAME + "s");
	}
	
	/** A target sliding sideways at 40 degrees/s: followed within 2 deg. */
	@Test
	void followsAMovingTarget()
	{
		HumanAim aim = start(0, 0);
		long now = 1_000_000_000L;
		double worst = 0;
		double rate = 0;
		for(int f = 1; f <= 240; f++)
		{
			double t = f * FRAME;
			now += FRAME_NS;
			double target = 10 + 40 * t;
			// measured once per tick, like in the game
			if(f % 3 == 0)
				rate = 40;
			aim.updateTracking(rate, 0, FRAME);
			aim.springYawStep((float)target, FRAME, now, 180, false);
			if(t > 1)
				worst = Math.max(worst,
					Math.abs(Mth.wrapDegrees(target - aim.getYaw())));
		}
		assertTrue(worst < 2, "lagged " + worst + " degrees");
	}
	
	/**
	 * Picking the turn up again after holding still: no new reaction delay,
	 * and from where the camera was left.
	 */
	@Test
	void holdResumesWithoutReactionDelay()
	{
		HumanAim aim = start(0, 0);
		long now = 1_000_000_000L;
		for(int f = 1; f <= 60; f++)
		{
			now += FRAME_NS;
			aim.springYawStep(30, FRAME, now, 180, true);
		}
		
		// on the target for a while; meanwhile the camera ended up at 10/5
		for(int f = 1; f <= 30; f++)
		{
			now += FRAME_NS;
			aim.hold(10, 5, now);
		}
		assertEquals(10, aim.getYaw(), 1e-6);
		assertEquals(5, aim.getPitch(), 1e-6);
		
		now += FRAME_NS;
		aim.springYawStep(30, aim.takeDt(now), now, 180, true);
		assertFalse(aim.isReacting(now));
		assertTrue(aim.getYaw() - 10 > 0.01,
			"didn't move on the first frame after the hold");
	}
}
