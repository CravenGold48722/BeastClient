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

/**
 * AimAssist's humanized aim against the reference video (user, 2026-10-16:
 * "make the aim like the video"): its cleanest return onto the zombie, 45
 * degrees, measured from the zombie's position frame by frame at 30 fps
 * (FOV 70) - 11 degrees a frame at first, then half of what's left every
 * frame, onto the target without overshooting.
 */
public class VideoAimTest
{
	/** Degrees left to the target, every 1/30 s, from 9.267 s on. */
	private static final double[] VIDEO =
		{44.9, 42.5, 31.4, 19.9, 9.5, 5.2, 2.4, 1.2, 0.6, -0.1};
	
	/** The hitbox's half width as seen from about 3 blocks, degrees. */
	private static final double TOLERANCE = 5.7;
	
	private static final double FPS = 144;
	
	/** AimAssist's auto speed for a turn this size (see applyAim). */
	private static double speed(HumanAim aim)
	{
		return Math.max(340, 8 * aim.angleTo(0, 0));
	}
	
	/**
	 * Frame by frame within 3 degrees of the video (the first frame aside:
	 * there the player's flick away was still turning around), with any
	 * drifting speed the humanized hand picks.
	 */
	@Test
	public void returnMatchesVideo()
	{
		for(int seed = 1; seed <= 20; seed++)
		{
			double[] ours = run(seed, 45, 0, VIDEO.length);
			for(int i = 2; i < VIDEO.length; i++)
				assertEquals(VIDEO[i], ours[i], 3,
					"seed " + seed + ", frame " + i + " (1/30 s each)");
		}
	}
	
	/** It lands on the aim point and stays there, never past it. */
	@Test
	public void noOvershoot()
	{
		for(int seed = 1; seed <= 20; seed++)
			for(float start : new float[]{10, 45, 90, 180})
			{
				double[] ours = run(seed, start, 0, 30);
				for(double left : ours)
					assertTrue(left > -0.5, "seed " + seed + " from " + start
						+ ": overshot to " + left);
				assertTrue(Math.abs(ours[ours.length - 1]) < 0.6,
					"seed " + seed + " from " + start + ": still "
						+ ours[ours.length - 1] + " off after a second");
			}
	}
	
	/** A big turn gets more than 340 deg/s, like a long flick. */
	@Test
	public void bigTurnsAreQuicker()
	{
		double[] ours = run(1, 180, 0, 30);
		// within the hitbox after half a second
		assertTrue(ours[15] < TOLERANCE, "180 degrees took longer than"
			+ " half a second: " + ours[15] + " left");
	}
	
	/** Degrees of yaw left every 1/30 s. */
	private static double[] run(int seed, float yaw, float pitch, int frames)
	{
		HumanAim aim = new HumanAim(new Random(seed));
		long now = 1_000_000_000L;
		aim.seed(yaw, pitch, now);
		long step = (long)(1e9 / FPS);
		int perFrame = (int)(FPS / 30);
		double[] out = new double[frames];
		for(int i = 0; i < frames * perFrame; i++)
		{
			if(i % perFrame == 0)
				out[i / perFrame] = aim.getYaw();
			now += step;
			aim.pidStep(0, 0, 1 / FPS, now, speed(aim), TOLERANCE, true);
		}
		return out;
	}
}
