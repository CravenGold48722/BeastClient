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
 * Drives {@link HumanAim} with a fake clock, the way the hacks do every frame
 * (or every tick), and checks the turn itself.
 */
class HumanAimTest
{
	private static final double MAX_SPEED = 720;
	
	/** Result of one simulated turn. */
	private record Turn(double settleMs, double peakSpeed, double overshoot,
		double worstAfterSettle, double trailing, double firstMoveMs)
	{}
	
	/**
	 * Turns from yaw 0 toward a target starting at {@code start} degrees that
	 * moves across the view at {@code omega} degrees per second.
	 */
	private static Turn simulate(double fps, double start, double omega)
	{
		HumanAim aim = new HumanAim(new Random(7));
		long now = 1_000_000_000L;
		long step = (long)(1_000_000_000L / fps);
		aim.seed(0, 0, now);
		aim.startTurn(now);
		
		double target = start;
		double settle = -1, peak = 0, overshoot = 0, firstMove = -1;
		double worstAfterSettle = 0;
		double sumTrail = 0;
		int trailSamples = 0;
		
		for(int i = 1; i <= fps * 3; i++)
		{
			now += step;
			double t = i / fps;
			target += omega / fps;
			
			float before = aim.getYaw();
			float dt = aim.takeDt(now);
			aim.updateTracking(omega, 0, dt);
			aim.humanStep((float)Mth.wrapDegrees(target), 0, dt, now,
				MAX_SPEED);
			
			double moved = Mth.wrapDegrees(aim.getYaw() - before);
			if(moved != 0 && firstMove < 0)
				firstMove = t * 1000;
			peak = Math.max(peak, Math.abs(moved) * fps);
			
			double left = Mth.wrapDegrees(target - aim.getYaw());
			// both axes - the curved path can still be off vertically when
			// the yaw has arrived
			double off = aim.angleTo((float)Mth.wrapDegrees(target), 0);
			if(off <= 2 && settle < 0)
				settle = t * 1000;
			else if(settle >= 0 && omega == 0)
				worstAfterSettle = Math.max(worstAfterSettle, off);
			
			// past the target, in the direction of the turn
			if(omega == 0)
				overshoot = Math.max(overshoot, -left * Math.signum(start));
			
			if(t > 1.5)
			{
				sumTrail += Math.abs(left);
				trailSamples++;
			}
		}
		
		return new Turn(settle, peak, overshoot, worstAfterSettle,
			sumTrail / trailSamples, firstMove);
	}
	
	@Test
	void testTurnSettlesWithoutOvershoot()
	{
		// (not exactly 180: that is ambiguous, either way round is shortest)
		for(double start : new double[]{10, 45, 170, -120})
		{
			Turn turn = simulate(144, start, 0);
			assertTrue(turn.settleMs() > 0 && turn.settleMs() < 600,
				start + " deg settled after " + turn.settleMs() + "ms");
			// Once on the target it stays there - no bouncing back out.
			assertTrue(turn.worstAfterSettle() <= 2.5, start + " deg: drifted "
				+ turn.worstAfterSettle() + " after settling");
			
			// The path is deliberately curved, so the aim can swing a little
			// past the target sideways while lining up the other axis - but
			// only a little.
			assertTrue(turn.overshoot() <= 1.5,
				start + " deg swung past by " + turn.overshoot());
		}
	}
	
	@Test
	void testTopSpeedIsRespected()
	{
		// tremor rides on top of the capped speed, so allow a little
		Turn turn = simulate(144, 170, 0);
		assertTrue(turn.peakSpeed() <= MAX_SPEED * 1.15,
			"peak speed " + turn.peakSpeed());
		assertTrue(turn.peakSpeed() >= MAX_SPEED * 0.8,
			"never got near top speed: " + turn.peakSpeed());
	}
	
	@Test
	void testReactionDelay()
	{
		Turn turn = simulate(144, 90, 0);
		assertTrue(turn.firstMoveMs() >= 50 && turn.firstMoveMs() <= 160,
			"started moving after " + turn.firstMoveMs() + "ms");
	}
	
	@Test
	void testFrameRateIndependent()
	{
		Turn frames = simulate(144, 90, 0);
		Turn ticks = simulate(20, 90, 0);
		assertEquals(frames.settleMs(), ticks.settleMs(), 75, "144 fps: "
			+ frames.settleMs() + "ms, 20 Hz: " + ticks.settleMs() + "ms");
	}
	
	@Test
	void testKeepsUpWithMovingTarget()
	{
		for(double omega : new double[]{40, 100, 300})
		{
			Turn turn = simulate(144, 30, omega);
			// one frame of the target's own motion is unavoidable here
			double oneFrame = omega / 144;
			assertTrue(turn.trailing() <= oneFrame + 0.5,
				omega + " deg/s target: trailing by " + turn.trailing());
		}
	}
	
	@Test
	void testLinearStepIsConstantSpeed()
	{
		HumanAim aim = new HumanAim(new Random(1));
		aim.seed(0, 0, 0);
		aim.linearStep(90, 0, 0.05, MAX_SPEED);
		assertEquals(36, aim.getYaw(), 1e-4);
	}
}
