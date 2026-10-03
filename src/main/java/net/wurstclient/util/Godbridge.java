/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

/**
 * The geometry of a godbridge, the way players do it: walking backward along
 * the bridge (S plus A or D), facing back at 45 degrees to it, pitch about
 * 75.5, never sneaking, clicking the side face of the last block the moment
 * the crosshair slips over its top edge.
 *
 * <p>
 * The catch, and why players have to jump every 8-10 blocks: the crosshair
 * only clears the top edge once you are ~0.3 blocks past it - right where
 * you start to fall - so there is about one tick per block to click in. The
 * position at the start of each tick jumps ahead by ~0.22 blocks, and a fixed
 * pitch misses that window now and then. Here the pitch is nudged by about
 * a degree for each block (players call it aiming a pixel above or below
 * the edge), so the window is hit every time.
 *
 * <p>
 * All distances are in blocks, measured along the bridge direction:
 * {@code past} is how far your center is past the front face of the last
 * block. {@code eyeAboveTop} is your eye height above the bridge's top
 * surface. {@code horizontal} is how much of a horizontal look step goes
 * back toward the bridge (cos 45 = 0.707 when facing back at 45 degrees).
 */
public enum Godbridge
{
	;
	
	/** The middle of the 75.0-75.8 range players use. */
	public static final double NOMINAL_PITCH = 75.4;
	
	/**
	 * How far past the edge your center can get while still standing on the
	 * block (half the player's width).
	 */
	public static final double SUPPORT = 0.3;
	
	/** Keeps the click this far inside the face's top and bottom edges. */
	private static final double DEPTH_MARGIN = 0.1;
	
	/**
	 * Whether a ray at this pitch, from this position, hits the last block's
	 * side face (rather than its top, or the air below it).
	 */
	public static boolean hitsFace(double past, double eyeAboveTop,
		double horizontal, double pitchDeg)
	{
		if(past <= 0)
			return false;
		
		// how deep below the top the ray is when it reaches the face
		double depth = past / horizontal * Math.tan(Math.toRadians(pitchDeg))
			- eyeAboveTop;
		return depth >= 0 && depth <= 1;
	}
	
	/**
	 * Whether your feet are still level with the bridge at a tick that starts
	 * at {@code past}, after a tick that started at {@code previousPast}. The
	 * tick you walk off the edge, collisions still keep you at the same
	 * height (vertical movement is resolved first, while you're still on the
	 * block); you only drop during the tick after.
	 */
	public static boolean feetLevel(double past, double previousPast)
	{
		return past <= SUPPORT || previousPast <= SUPPORT;
	}
	
	/** The pitch closest to {@code nominal} that clicks the face at past. */
	private static double pitchFor(double past, double eyeAboveTop,
		double horizontal, double nominal)
	{
		double minTan = horizontal * (eyeAboveTop + DEPTH_MARGIN) / past;
		double maxTan = horizontal * (eyeAboveTop + 1 - DEPTH_MARGIN) / past;
		double tan = Math.tan(Math.toRadians(nominal));
		tan = Math.max(minTan, Math.min(maxTan, tan));
		return Math.toDegrees(Math.atan(tan));
	}
	
	/**
	 * Plans the pitch for each block: which tick to click in, and the pitch
	 * that lands that click on the face - the nominal pitch when that
	 * already works, otherwise the closest one that does.
	 *
	 * <p>
	 * The plan is made early (right after the previous block, a few ticks
	 * ahead) and kept until that click: a click is only made when the
	 * rotation the server already has hits too, and that is the one sent at
	 * the end of the tick before, aimed during the frames before that.
	 * Re-planning for a later tick at the last moment left too little time
	 * for both (a fall every ~15 blocks in the gametest).
	 */
	public static final class Planner
	{
		private boolean committed;
		private double committedPast;
		private double committedPitch;
		
		public void reset()
		{
			committed = false;
		}
		
		/**
		 * @param past
		 *            where this tick starts
		 * @param speed
		 *            how far you move along the bridge per tick
		 * @return the pitch to aim with during the frames until the next
		 *         tick
		 */
		public double pitch(double past, double speed, double eyeAboveTop,
			double horizontal, double nominal)
		{
			// still on the way to the planned click (a placed block moves
			// the face a block ahead, so past drops by 1: plan the next one)
			if(committed && past <= committedPast + 1e-6
				&& past > committedPast - 0.9)
				return committedPitch;
			
			committed = false;
			if(speed < 0.02)
				return nominal;
				
			// The ticks that start with your feet still level: up to the
			// first one past the edge of support. Plan for the last two of
			// them, as far ahead as possible (3 ticks, else 2, else 1), and
			// whichever needs the smaller nudge.
			double last = past;
			if(last <= SUPPORT)
				last += Math.floor((SUPPORT - last) / speed + 1) * speed;
			
			for(int lead = 3; lead >= 1; lead--)
			{
				double earliest = past + lead * speed - 1e-9;
				double bestPast = Double.NaN;
				double best = Double.NaN;
				for(double p : new double[]{last, last - speed})
				{
					if(p < earliest || p <= 0.02)
						continue;
					
					double pitch =
						pitchFor(p, eyeAboveTop, horizontal, nominal);
					if(Double.isNaN(best)
						|| Math.abs(pitch - nominal) < Math.abs(best - nominal))
					{
						best = pitch;
						bestPast = p;
					}
				}
				
				if(!Double.isNaN(best))
				{
					committed = true;
					committedPast = bestPast;
					committedPitch = best;
					return best;
				}
			}
			
			return nominal;
		}
	}
}
