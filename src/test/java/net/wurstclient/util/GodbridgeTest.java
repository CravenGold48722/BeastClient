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
 * Walks a simulated godbridge tick by tick: the position at the start of each
 * tick, the pitch the camera reached during the frames before it, a click
 * only where the crosshair really is on the last block's side face and your
 * feet are still level with the bridge, a fall otherwise.
 */
class GodbridgeTest
{
	private static final double EYE = 1.62;
	private static final double HORIZONTAL = Math.cos(Math.toRadians(45));
	
	private record Result(int placed, boolean fell, double minPitch,
		double maxPitch)
	{}
	
	private static Result walk(double startPast, double speed, int blocks,
		boolean adaptive)
	{
		double past = startPast;
		double previous = past - speed;
		Godbridge.Planner planner = new Godbridge.Planner();
		// the camera during this tick, and the rotation sent at the end of the
		// last one - a click needs both on the face
		double pitch = Godbridge.NOMINAL_PITCH;
		double sent = pitch;
		double min = pitch;
		double max = pitch;
		int placed = 0;
		
		for(int tick = 0; placed < blocks && tick < blocks * 20; tick++)
		{
			if(!Godbridge.feetLevel(past, previous))
				return new Result(placed, true, min, max);
			
			if(Godbridge.hitsFace(past, EYE, HORIZONTAL, pitch)
				&& Godbridge.hitsFace(past, EYE, HORIZONTAL, sent))
			{
				placed++;
				past -= 1;
				previous -= 1;
			}
			
			// sent at the end of this tick; then aimed during the frames
			// before the next one
			sent = pitch;
			if(adaptive)
				pitch = planner.pitch(past, speed, EYE, HORIZONTAL,
					Godbridge.NOMINAL_PITCH);
			min = Math.min(min, pitch);
			max = Math.max(max, pitch);
			
			previous = past;
			past += speed;
		}
		
		return new Result(placed, false, min, max);
	}
	
	@Test
	void neverFalls()
	{
		double worstLow = Godbridge.NOMINAL_PITCH;
		double worstHigh = Godbridge.NOMINAL_PITCH;
		for(double speed = 0.10; speed <= 0.2401; speed += 0.005)
			for(double start = -1; start < 0; start += 0.013)
			{
				Result r = walk(start, speed, 60, true);
				assertFalse(r.fell(), "fell after " + r.placed()
					+ " blocks at speed " + speed + ", start " + start);
				worstLow = Math.min(worstLow, r.minPitch());
				worstHigh = Math.max(worstHigh, r.maxPitch());
			}
		
		System.out.printf("pitch range %.2f - %.2f%n", worstLow, worstHigh);
		// players use 75.0-75.8; the nudges stay close to that
		assertTrue(worstLow > 73 && worstHigh < 78,
			"pitch " + worstLow + " - " + worstHigh);
	}
	
	@Test
	void fixedPitchFallsSometimes()
	{
		// what players run into without the nudges (why they jump every few
		// blocks)
		int falls = 0;
		int runs = 0;
		for(double start = -1; start < 0; start += 0.013)
		{
			runs++;
			if(walk(start, 0.216, 30, false).fell())
				falls++;
		}
		System.out.println("fixed 75.4: fell in " + falls + "/" + runs);
		assertTrue(falls > 0);
	}
}
