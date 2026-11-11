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

/** MaceAssist's aim giving way to the mouse and hesitating (AimYield). */
public class AimYieldTest
{
	/** Plays frames; returns the last pull. */
	private static double run(AimYield y, int fps, double seconds,
		double mouseRate, double aimRate, double needRate, double[] need,
		long[] nowMs)
	{
		double dt = 1.0 / fps;
		double pull = 1;
		for(int i = 0; i < Math.round(seconds * fps); i++)
		{
			need[0] += needRate * dt;
			nowMs[0] += Math.round(dt * 1000);
			pull =
				y.update(mouseRate * dt, aimRate * dt, need[0], dt, nowMs[0]);
		}
		return pull;
	}
	
	@Test
	public void dragAgainstLetsGoThenComesBack()
	{
		for(int fps : new int[]{30, 60, 240})
		{
			AimYield y = new AimYield();
			double[] need = {0};
			long[] now = {1000};
			// you drag left at 60 degrees/s against a pull to the right
			double pull = run(y, fps, 0.15, -60, 60, 0, need, now);
			assertTrue(pull < 0.2, fps + " fps: still pulling " + pull);
			assertTrue(y.isDragging());
			
			// you let go: back within ~150 ms + the rise
			pull = run(y, fps, 0.1, 0, 60, 0, need, now);
			assertTrue(pull < 0.5, fps + " fps: came back too soon " + pull);
			pull = run(y, fps, 0.5, 0, 60, 0, need, now);
			assertTrue(pull > 0.9, fps + " fps: didn't come back " + pull);
			assertFalse(y.isDragging());
		}
	}
	
	@Test
	public void movingWithItIsNotDragging()
	{
		AimYield y = new AimYield();
		double pull = run(y, 60, 1, 60, 60, 0, new double[]{0}, new long[]{0});
		assertEquals(1, pull, 1e-6);
		assertFalse(y.isDragging());
	}
	
	@Test
	public void hesitatesWhenTheTargetTurnsAround()
	{
		AimYield y = new AimYield();
		double[] need = {0};
		long[] now = {1000};
		// following a target sliding right at 30 degrees/s
		assertEquals(1, run(y, 60, 0.5, 0, 30, 30, need, now), 1e-3);
		// it turns around: "hold on"
		double pull = run(y, 60, 0.2, 0, -30, -30, need, now);
		assertTrue(pull < 0.2, "didn't hesitate: " + pull);
		// then follows again (the hold is 300-400 ms)
		pull = run(y, 60, 0.5, 0, -30, -30, need, now);
		assertTrue(pull > 0.9, "kept hesitating: " + pull);
		// and a quick turn back right after doesn't make it stutter
		pull = run(y, 60, 0.15, 0, 30, 30, need, now);
		assertTrue(pull > 0.9, "stuttered: " + pull);
	}
	
	/**
	 * The whole loop (CameraAim.assistYaw's order): on a still target, a
	 * hand drags the camera off at 100 degrees/s for half a second, then lets
	 * go. It gets away (AimAssist's aim at full pull held a 100 degrees/s drag
	 * to a 1.5 degree creep) and comes back without swinging past.
	 */
	@Test
	public void pullAwayThenBack()
	{
		HumanAim aim = new HumanAim();
		AimYield yield = new AimYield();
		int fps = 60;
		double dt = 1.0 / fps;
		long ns = 1_000_000_000L;
		aim.seed(0, 0, ns);
		double lastStep = 0;
		double minYaw = 0;
		for(int i = 0; i < fps * 2; i++)
		{
			ns += (long)(dt * 1e9);
			double mouse = i < fps / 2 ? -100 * dt : 0;
			aim.shift((float)mouse, 0);
			double gain = yield.update(mouse, lastStep, 0, dt, ns / 1_000_000L);
			if(yield.isDragging())
				aim.clearIntegral();
			aim.setPullGain(gain);
			float before = aim.getYaw();
			aim.pidStep(0, aim.getPitch(), dt, ns,
				Math.max(340, 8 * Math.abs(before)), 5, false);
			lastStep = aim.getYaw() - before;
			minYaw = Math.min(minYaw, aim.getYaw());
			if(i == fps / 2 - 1)
				assertTrue(aim.getYaw() < -35,
					"didn't get away: " + aim.getYaw() + " of -50");
		}
		assertTrue(minYaw < -35);
		assertEquals(0, aim.getYaw(), 1, "didn't come back");
		// never past the target on the other side
		assertTrue(aim.getYaw() > -1 && aim.getYaw() < 0.5,
			"swung past: " + aim.getYaw());
	}
	
	@Test
	public void stillTargetNeverHesitates()
	{
		AimYield y = new AimYield();
		double pull = run(y, 60, 2, 0, 20, 0, new double[]{0}, new long[]{0});
		assertEquals(1, pull, 1e-6);
	}
}
