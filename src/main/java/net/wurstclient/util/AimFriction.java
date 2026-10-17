/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

/**
 * Slows your own mouse down near the target while an aim assist pulls the
 * camera onto it, like the reference video (2026-10-16): pulling away from
 * the target, the crosshair first only creeps (0.4-2 degrees a frame at 30
 * fps), then a real flick breaks free - and once you stop, the pull brings
 * it back. Your mouse is never locked.
 *
 * <p>
 * Per axis: leaving the aim point is slowed by the full stickiness, closing
 * in by half of it at the hitbox's edge, growing to all of it at the aim
 * point (half, then full the moment the aim point was crossed stopped the
 * crosshair dead there). Fades out over {@link #ZONE} degrees outside the
 * hitbox. {@link #toCounts} turns the result back into whole mouse counts,
 * carrying the fraction, so a slowed-down mouse is a lower-DPI mouse and
 * never a rotation off the mouse's grid.
 */
public final class AimFriction
{
	/** Degrees outside the hitbox over which the slowdown fades out. */
	public static final double ZONE = 5;
	
	private double carryX;
	private double carryY;
	
	/**
	 * Where the target is relative to the camera.
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
	 */
	public record Target(double errorYaw, double errorPitch, double halfWidth,
		double top, double bottom)
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
	 * Your mouse movement for this frame, slowed near the target.
	 *
	 * @param stickiness
	 *            0-1, how much slower the mouse turns while leaving the
	 *            target from on it
	 * @return {yaw, pitch} in degrees
	 */
	public static double[] slow(double userYaw, double userPitch, Target t,
		double stickiness)
	{
		// Along the way, a slice at a time: a big jump of the mouse in one
		// frame (a flick at a low frame rate) is slowed only while the
		// crosshair is still near the target, not all of it by how close it
		// was at the start - a 60 degree flick in 3 bursts got 5 degrees off.
		int slices = (int)Math.min(MAX_SLICES,
			Math.ceil(Math.hypot(userYaw, userPitch) / SLICE));
		if(slices == 0)
			return new double[]{0, 0};
		double sliceYaw = userYaw / slices;
		double slicePitch = userPitch / slices;
		
		double errorYaw = t.errorYaw();
		double errorPitch = t.errorPitch();
		double yaw = 0;
		double pitch = 0;
		for(int i = 0; i < slices; i++)
		{
			Target now = new Target(errorYaw, errorPitch, t.halfWidth(),
				t.top(), t.bottom());
			double near = 1 - smoothstep(now.outside() / ZONE);
			double y = sliceYaw;
			double p = slicePitch;
			if(near > 0)
			{
				y *= factor(sliceYaw, errorYaw, t.halfWidth(), near,
					stickiness);
				p *= factor(slicePitch, errorPitch, (t.bottom() - t.top()) / 2,
					near, stickiness);
			}
			yaw += y;
			pitch += p;
			errorYaw += y;
			errorPitch += p;
		}
		return new double[]{yaw, pitch};
	}
	
	/** Degrees of mouse movement per slice (see slow). */
	private static final double SLICE = 0.25;
	private static final int MAX_SLICES = 2000;
	
	private static double factor(double move, double error, double half,
		double near, double stickiness)
	{
		// moving the same way as the error points makes it bigger: leaving
		if(move * error > 0)
			return 1 - stickiness * near;
		
		double center = 1 - smoothstep(Math.abs(error) / Math.max(half, 1e-6));
		return 1 - stickiness * (0.5 + 0.5 * center) * near;
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
	 * for the next frame.
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
		carryX = 0;
		carryY = 0;
	}
}
