/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.util.Mth;

/**
 * Sensitivity-based aim assist, the console kind: you turn the camera with
 * the mouse, and near a target the assist only changes what your mouse
 * movement does. Nothing turns while your hand and the target are still.
 * <ul>
 * <li><b>Friction:</b> near the target your mouse turns the camera less -
 * slightly less while closing in (the crosshair eases onto it instead of
 * flying past) and a lot less while leaving it (it sticks; a deliberate
 * flick still breaks free).</li>
 * <li><b>Magnetism:</b> while you move the mouse toward the target, part of
 * that movement is bent toward it, never past the aim point.</li>
 * <li><b>Tracking:</b> while the crosshair is on the target, the camera
 * follows the target's movement across the view - its strafing and yours -
 * so it stays on.</li>
 * </ul>
 * Works in degrees per frame; {@link #toCounts} turns the result back into
 * whole mouse counts, so every turn is one a mouse could make.
 */
public final class StickyAim
{
	/** Tracking never turns faster than this, degrees per second. */
	private static final double MAX_TRACK_SPEED = 720;
	
	private double lastNeededYaw;
	private double lastNeededPitch;
	private boolean hasLast;
	
	private double carryX;
	private double carryY;
	
	/**
	 * Where the target is in this frame, relative to the camera.
	 *
	 * @param errorYaw
	 *            camera yaw minus the yaw to the aim point, wrapped
	 * @param errorPitch
	 *            camera pitch minus the pitch to the aim point
	 * @param halfWidth
	 *            the hitbox's half width as seen, degrees
	 * @param top
	 *            the hitbox's top relative to the aim point's pitch
	 *            (negative: above it)
	 * @param bottom
	 *            the hitbox's bottom relative to it (positive)
	 * @param neededYaw
	 *            the yaw to the aim point, for tracking its movement
	 * @param neededPitch
	 *            the pitch to the aim point
	 */
	public record Target(double errorYaw, double errorPitch, double halfWidth,
		double top, double bottom, double neededYaw, double neededPitch)
	{
		/** How far outside the hitbox the crosshair is, degrees. */
		public double outside()
		{
			double x = Math.max(0, Math.abs(errorYaw) - halfWidth);
			double y = errorPitch < top ? top - errorPitch
				: errorPitch > bottom ? errorPitch - bottom : 0;
			return Math.hypot(x, y);
		}
	}
	
	/**
	 * @param stickiness
	 *            0-1, how much slower the mouse turns while leaving the
	 *            target from on it
	 * @param zone
	 *            degrees outside the hitbox where the assist fades out
	 * @param magnetism
	 *            0-1, the share of your mouse movement bent toward the target
	 * @param tracking
	 *            0-1, how much of the target's movement across the view the
	 *            camera follows while on it
	 */
	public record Strength(double stickiness, double zone, double magnetism,
		double tracking)
	{}
	
	/**
	 * Applies the assist to one frame of mouse movement.
	 *
	 * @param userYaw
	 *            the yaw your mouse would turn this frame, degrees
	 * @param userPitch
	 *            the pitch it would turn
	 * @param t
	 *            the target, or null for none (passes your movement through)
	 * @param dt
	 *            seconds since the last frame
	 * @return {yaw, pitch} to turn this frame, degrees
	 */
	public double[] step(double userYaw, double userPitch, Target t, Strength s,
		double dt)
	{
		if(t == null)
		{
			hasLast = false;
			return new double[]{userYaw, userPitch};
		}
		
		double trackYaw = 0;
		double trackPitch = 0;
		if(hasLast)
		{
			trackYaw = Mth.wrapDegrees(t.neededYaw() - lastNeededYaw);
			trackPitch = t.neededPitch() - lastNeededPitch;
		}
		lastNeededYaw = t.neededYaw();
		lastNeededPitch = t.neededPitch();
		hasLast = true;
		
		double outside = t.outside();
		// 1 on the hitbox, fading to 0 at the zone's edge
		double near = 1 - smoothstep(outside / Math.max(s.zone(), 1e-6));
		if(near <= 0)
			return new double[]{userYaw, userPitch};
			
		// Friction, per axis: leaving the aim point is slowed by the full
		// stickiness, closing in by less - the crosshair eases onto the
		// target and stops there instead of flying past.
		double yaw =
			userYaw * friction(userYaw, t.errorYaw(), t.halfWidth(), near, s);
		double pitch = userPitch * friction(userPitch, t.errorPitch(),
			(t.bottom() - t.top()) / 2, near, s);
		
		// Tracking: follow the target across the view while on it, fading
		// out over the zone like the friction (fading out from the hitbox's
		// edge left the crosshair trailing a strafer by most of its width).
		// Its own pace, so a still target and a still hand turn nothing.
		double maxTrack = MAX_TRACK_SPEED * dt;
		double movedYaw = Mth.clamp(trackYaw, -maxTrack, maxTrack);
		double movedPitch = Mth.clamp(trackPitch, -maxTrack, maxTrack);
		yaw += movedYaw * s.tracking() * near;
		pitch += movedPitch * s.tracking() * near;
		
		// Magnetism: part of the movement, bent toward the aim point - your
		// hand's while closing in (pulling back while you leave made the
		// target impossible to leave at a steady hand speed), and the
		// target's while it moves across the view on the crosshair (tracking
		// alone trails behind a strafer until it slips off). Never past the
		// aim point, nothing while both are still.
		boolean closing =
			userYaw * t.errorYaw() + userPitch * t.errorPitch() < 0;
		double drive = (closing ? Math.hypot(userYaw, userPitch) * near : 0)
			+ Math.hypot(movedYaw, movedPitch) * near;
		// the error after this frame's turn and the target's move
		double errYaw = t.errorYaw() + yaw - movedYaw;
		double errPitch = t.errorPitch() + pitch - movedPitch;
		double error = Math.hypot(errYaw, errPitch);
		if(drive > 0 && error > 1e-6)
		{
			double pull = Math.min(drive * s.magnetism(), error);
			yaw -= errYaw / error * pull;
			pitch -= errPitch / error * pull;
		}
		
		return new double[]{yaw, pitch};
	}
	
	/**
	 * @param half
	 *            the hitbox's half size on this axis, degrees
	 */
	private static double friction(double move, double error, double half,
		double near, Strength s)
	{
		// moving the same way as the error points makes it bigger: leaving
		if(move * error > 0)
			return 1 - s.stickiness() * near;
			
		// Closing in: half the stickiness at the hitbox's edge, growing to
		// all of it at the aim point - where leaving starts. (Half, then
		// full the moment the aim point was crossed stopped the crosshair
		// dead there, a third of its speed gone in one frame.)
		double center = 1 - smoothstep(Math.abs(error) / Math.max(half, 1e-6));
		return 1 - s.stickiness() * (0.5 + 0.5 * center) * near;
	}
	
	private static double smoothstep(double x)
	{
		if(x <= 0)
			return 0;
		if(x >= 1)
			return 1;
		return x * x * (3 - 2 * x);
	}
	
	/**
	 * Turns degrees into whole mouse counts, keeping the leftover fraction
	 * for the next frame - a slowed-down mouse is a lower-DPI mouse, never a
	 * rotation off the mouse's grid.
	 *
	 * @param step
	 *            degrees per mouse count
	 * @param yawSign
	 *            -1 with inverted horizontal mouse, else 1
	 * @param pitchSign
	 *            -1 with inverted vertical mouse, else 1
	 * @return {x, y} mouse counts
	 */
	public double[] toCounts(double yaw, double pitch, double step, int yawSign,
		int pitchSign)
	{
		double x = yaw / step * yawSign + carryX;
		double y = pitch / step * pitchSign + carryY;
		double rx = Math.round(x);
		double ry = Math.round(y);
		carryX = x - rx;
		carryY = y - ry;
		return new double[]{rx, ry};
	}
	
	public void reset()
	{
		hasLast = false;
		carryX = 0;
		carryY = 0;
	}
}
