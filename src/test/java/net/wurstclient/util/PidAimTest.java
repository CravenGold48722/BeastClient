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
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

import net.minecraft.util.Mth;

/**
 * Drives the aim every frame (60 fps) against a target moving across the
 * view, the way AimAssist does, and compares the PID aim with the old
 * distance-gated ease-out. The tracking (feed-forward) is measured once per
 * tick like in the game - late, and on purpose imperfect where noted - since
 * that's what the PID has to make up for.
 */
class PidAimTest
{
	private static final double FRAME = 1 / 60.0;
	private static final double SPEED = 720;
	
	private record Run(double timeToTarget, double overshoot,
		double onTargetShare, int rotationChanges)
	{}
	
	/**
	 * @param target
	 *            the target's yaw (degrees) over time (seconds); pitch 0
	 * @param trackingAccuracy
	 *            how much of the target's real angular speed the tracking
	 *            estimate catches
	 * @param halfWidth
	 *            half the hitbox's angular width, degrees
	 * @param settleFrom
	 *            seconds after which on-target share and rotation changes
	 *            are counted
	 */
	private static Run run(boolean pid, boolean humanize,
		DoubleUnaryOperator target, double trackingAccuracy, double halfWidth,
		double seconds, double settleFrom, long seed)
	{
		HumanAim aim = new HumanAim(new Random(seed));
		long now = 1_000_000_000L;
		aim.seed(0, 0, now);
		aim.startTurn(now);
		
		double start = target.applyAsDouble(0);
		double timeToTarget = Double.NaN;
		double overshoot = 0;
		int onFrames = 0;
		int counted = 0;
		int changes = 0;
		float lastYaw = aim.getYaw();
		double trackRate = 0;
		
		int frames = (int)(seconds / FRAME);
		for(int f = 1; f <= frames; f++)
		{
			double t = f * FRAME;
			now += (long)(FRAME * 1e9);
			
			// tracking measured once per tick (every 3 frames), from the
			// target's movement during the last tick
			if(f % 3 == 0)
				trackRate =
					(target.applyAsDouble(t) - target.applyAsDouble(t - 0.05))
						/ 0.05 * trackingAccuracy;
			aim.updateTracking(trackRate, 0, FRAME);
			
			double want = target.applyAsDouble(t);
			if(pid)
				aim.pidStep((float)want, 0, FRAME, now, SPEED, halfWidth,
					humanize);
			else if(humanize)
				aim.humanStep((float)want, 0, FRAME, now, SPEED);
			else
				aim.linearStep((float)want, 0, FRAME, SPEED);
			
			double err = Mth.wrapDegrees(want - aim.getYaw());
			boolean onNow = Math.abs(err) <= halfWidth;
			if(onNow && Double.isNaN(timeToTarget))
				timeToTarget = t;
			
			// past the aim point, in the direction of the initial turn
			double past = -err * Math.signum(start);
			if(!Double.isNaN(timeToTarget))
				overshoot = Math.max(overshoot, past);
			
			if(t >= settleFrom)
			{
				counted++;
				if(onNow)
					onFrames++;
				if(Math.abs(Mth.wrapDegrees(aim.getYaw() - lastYaw)) > 1e-4)
					changes++;
			}
			lastYaw = aim.getYaw();
		}
		
		return new Run(timeToTarget, overshoot, onFrames / (double)counted,
			changes);
	}
	
	/** Strafing back and forth across the view: +-speed every period/2. */
	private static DoubleUnaryOperator strafe(double center, double speed,
		double halfPeriod)
	{
		return t -> {
			double phase = t % (2 * halfPeriod);
			double tri = phase < halfPeriod ? phase : 2 * halfPeriod - phase;
			return center + speed * (tri - halfPeriod / 2);
		};
	}
	
	private static String fmt(Run r)
	{
		return String.format(
			"land %.3fs  overshoot %.2f°  on target %3.0f%%  changes %d",
			r.timeToTarget(), r.overshoot(), r.onTargetShare() * 100,
			r.rotationChanges());
	}
	
	@Test
	void printComparison()
	{
		record Case(String name, DoubleUnaryOperator target, double tracking,
			double halfWidth)
		{}
		Case[] cases = {new Case("90° turn, still", t -> 90, 1, 3),
			new Case("moving 80°/s, tracking 70%", t -> 40 + 80 * t, 0.7, 2),
			new Case("A/D 40°/s every 0.4s", strafe(30, 40, 0.4), 1, 2.5),
			new Case("A/D 60°/s every 0.25s", strafe(30, 60, 0.25), 1, 2.5)};
		
		for(Case c : cases)
			for(boolean humanize : new boolean[]{true, false})
			{
				Run old = run(false, humanize, c.target(), c.tracking(),
					c.halfWidth(), 4, 1, 3);
				Run pid = run(true, humanize, c.target(), c.tracking(),
					c.halfWidth(), 4, 1, 3);
				System.out.printf("%-28s %-9s OLD %s%n", c.name(),
					humanize ? "human" : "plain", fmt(old));
				System.out.printf("%-28s %-9s PID %s%n", "", "", fmt(pid));
			}
	}
	
	@Test
	void landsFastWithoutOvershoot()
	{
		for(long seed = 1; seed <= 5; seed++)
			for(boolean humanize : new boolean[]{true, false})
			{
				Run r = run(true, humanize, t -> 90, 1, 3, 2, 1, seed);
				assertTrue(r.timeToTarget() < 0.45,
					"took " + r.timeToTarget() + "s");
				assertTrue(r.overshoot() < 1.5,
					"overshot by " + r.overshoot() + "°");
			}
	}
	
	@Test
	void restsOnStillTarget()
	{
		// once on a still target, the aim stops moving - no stream of tiny
		// corrections (each would be a rotation packet)
		Run r = run(true, true, t -> 90, 1, 3, 3, 1.5, 1);
		assertEquals(1, r.onTargetShare());
		assertTrue(r.rotationChanges() <= 3,
			r.rotationChanges() + " rotation changes");
	}
	
	@Test
	void makesUpForTrackingLag()
	{
		// the integral removes the steady lag the old aim trailed with
		Run old = run(false, true, t -> 40 + 80 * t, 0.7, 2, 4, 1, 1);
		Run pid = run(true, true, t -> 40 + 80 * t, 0.7, 2, 4, 1, 1);
		assertTrue(pid.onTargetShare() >= 0.95,
			"PID on target " + pid.onTargetShare());
		assertTrue(pid.onTargetShare() >= old.onTargetShare());
	}
	
	@Test
	void staysOnStrafer()
	{
		for(long seed = 1; seed <= 3; seed++)
		{
			Run old = run(false, true, strafe(30, 40, 0.4), 1, 2.5, 4, 1, seed);
			Run pid = run(true, true, strafe(30, 40, 0.4), 1, 2.5, 4, 1, seed);
			assertTrue(pid.onTargetShare() >= 0.9,
				"PID on strafer " + pid.onTargetShare());
			assertTrue(pid.onTargetShare() >= old.onTargetShare() - 0.02, "PID "
				+ pid.onTargetShare() + " vs old " + old.onTargetShare());
		}
	}
	
	@Test
	void noFlickAfterFrameHitch()
	{
		// A target moving fast across the view (knocked up close by), 144
		// fps, and one 150ms frame (a lag spike). The aim must not jump far
		// ahead of the target right after - it used to lead by the motion of
		// the whole hitch, a flick in the target's direction.
		for(int mode = 0; mode < 3; mode++)
		{
			double frame = 1 / 144.0;
			double rate = 150;
			HumanAim aim = new HumanAim(new Random(1));
			long now = 1_000_000_000L;
			aim.seed(0, 0, now);
			aim.startTurn(now);
			
			double t = 0;
			double maxLead = 0;
			for(int f = 1; t < 1.5; f++)
			{
				double dt = f == 144 ? 0.15 : frame;
				t += dt;
				now += (long)(dt * 1e9);
				aim.updateTracking(rate, 0, dt);
				
				float want = (float)(rate * t);
				switch(mode)
				{
					case 0 -> aim.pidStep(want, 0, dt, now, SPEED, 2, true);
					case 1 -> aim.humanStep(want, 0, dt, now, SPEED);
					default -> aim.linearStep(want, 0, dt, SPEED);
				}
				
				if(f >= 144)
					maxLead =
						Math.max(maxLead, Mth.wrapDegrees(aim.getYaw() - want));
			}
			
			assertTrue(maxLead < 3,
				"mode " + mode + " led the target by " + maxLead + "°");
		}
	}
}
