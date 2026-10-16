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

/**
 * AimAssist's Sticky turning (the "sensitivity based aim assist" video):
 * a hand flicking across a target lands on it, a hard flick leaves it, a
 * still hand on a still target turns nothing, and a strafing target stays
 * under the crosshair.
 */
public class StickyAimTest
{
	private static final double FPS = 144;
	private static final double DT = 1 / FPS;
	
	/** A player-sized hitbox about 3.5 blocks away, as seen. */
	private static final double HALF_WIDTH = 6.5;
	private static final double TOP = -14;
	private static final double BOTTOM = 14;
	
	private static final StickyAim.Strength DEFAULTS =
		new StickyAim.Strength(0.75, 5, 0.25, 1);
	private static final StickyAim.Strength OFF =
		new StickyAim.Strength(0, 5, 0, 0);
	
	/** A camera and a target, turned frame by frame. */
	private static final class Sim
	{
		final StickyAim sticky = new StickyAim();
		final StickyAim.Strength strength;
		double camYaw;
		double targetYaw;
		
		Sim(StickyAim.Strength strength, double camYaw)
		{
			this.strength = strength;
			this.camYaw = camYaw;
		}
		
		double error()
		{
			return camYaw - targetYaw;
		}
		
		void frame(double handYaw)
		{
			StickyAim.Target t = new StickyAim.Target(error(), 0, HALF_WIDTH,
				TOP, BOTTOM, targetYaw, 0);
			camYaw += sticky.step(handYaw, 0, t, strength, DT)[0];
		}
	}
	
	/**
	 * The hand sweeps 40 degrees at 300 deg/s from 30 degrees beside the
	 * target - 10 past it - and stops. Without the assist the crosshair ends
	 * beside the target; with it, on it, and it slowed down on the way in
	 * instead of stopping dead.
	 */
	@Test
	public void flickAcrossLandsOnTarget()
	{
		Sim plain = new Sim(OFF, -30);
		Sim assisted = new Sim(DEFAULTS, -30);
		int frames = (int)Math.round(40 / 300.0 * FPS);
		double handStep = 40.0 / frames;
		
		double maxDrop = 0;
		double lastSpeed = handStep;
		for(int i = 0; i < frames; i++)
		{
			plain.frame(handStep);
			double before = assisted.camYaw;
			assisted.frame(handStep);
			double speed = assisted.camYaw - before;
			maxDrop = Math.max(maxDrop, lastSpeed - speed);
			lastSpeed = speed;
		}
		
		assertTrue(Math.abs(plain.error()) > HALF_WIDTH,
			"without the assist the flick should overshoot: " + plain.error());
		assertTrue(Math.abs(assisted.error()) < HALF_WIDTH,
			"the crosshair should land on the target: " + assisted.error());
		// eased in: no single frame loses more than a third of the hand's speed
		assertTrue(maxDrop < handStep / 3,
			"the crosshair should slow down smoothly, dropped " + maxDrop
				+ " deg/frame at once");
	}
	
	/** A hard 60-degree flick away from on the target breaks free. */
	@Test
	public void hardFlickLeaves()
	{
		Sim sim = new Sim(DEFAULTS, 0);
		int frames = (int)Math.round(0.1 * FPS);
		for(int i = 0; i < frames; i++)
			sim.frame(60.0 / frames);
		
		assertTrue(sim.error() > HALF_WIDTH + 5 + 15,
			"should be well off the target, is " + sim.error());
	}
	
	/** Leaving slowly from on it, the crosshair creeps (it sticks). */
	@Test
	public void slowLeaveSticks()
	{
		Sim sim = new Sim(DEFAULTS, 0);
		// 30 deg/s for a fifth of a second: 6 degrees of hand
		int frames = (int)Math.round(0.2 * FPS);
		for(int i = 0; i < frames; i++)
			sim.frame(30 * DT);
		
		assertTrue(sim.error() < 6 * 0.4,
			"should have crept, not followed the hand: " + sim.error());
	}
	
	/** Still hand, still target, crosshair a bit off-center: no turning. */
	@Test
	public void stillHandStillTargetTurnsNothing()
	{
		for(double start : new double[]{0, 2, -4, 9, 30})
		{
			Sim sim = new Sim(DEFAULTS, start);
			for(int i = 0; i < 300; i++)
				sim.frame(0);
			assertEquals(start, sim.camYaw, 1e-9);
		}
	}
	
	/**
	 * A target strafing across the view at 40 deg/s, changing direction
	 * every 0.4 s, hand still: the crosshair stays on it.
	 */
	@Test
	public void staysOnStrafer()
	{
		Sim sim = new Sim(DEFAULTS, 0);
		double worst = 0;
		for(int i = 0; i < 3 * FPS; i++)
		{
			double dir = (int)(i * DT / 0.4) % 2 == 0 ? 1 : -1;
			sim.targetYaw += dir * 40 * DT;
			sim.frame(0);
			worst = Math.max(worst, Math.abs(sim.error()));
		}
		
		assertTrue(worst < HALF_WIDTH / 2,
			"the crosshair should stay on the strafer, got " + worst
				+ " degrees off");
	}
	
	/** The output is whole mouse counts; the fractions add up over frames. */
	@Test
	public void wholeMouseCounts()
	{
		StickyAim sticky = new StickyAim();
		double step = 0.15;
		double total = 0;
		for(int i = 0; i < 100; i++)
		{
			double[] c = sticky.toCounts(0.037, -0.02, step, 1, -1);
			assertEquals(Math.rint(c[0]), c[0]);
			assertEquals(Math.rint(c[1]), c[1]);
			total += c[0] * step;
		}
		assertEquals(3.7, total, step);
	}
}
