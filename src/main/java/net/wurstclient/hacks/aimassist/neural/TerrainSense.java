/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * What the network knows of the ground around it, the same from PvpSim's
 * {@link Terrain} and from the real world (AimAssist): the heights of a
 * 5 x 5 patch around you, and in 8 directions how far the next wall (too
 * high to jump onto) and the next drop (3+ blocks down) are - all in the
 * frame toward the opponent. Also plans ender pearls over it. No MC classes.
 */
public final class TerrainSense
{
	/** Ground heights: the simulator's columns, or the real blocks. */
	public interface Heights
	{
		/**
		 * The top of the ground at x, z for a player whose feet are at
		 * feetY: the highest solid block surface at or below feetY + 2.25
		 * (lower if there's none within 8 blocks).
		 */
		double heightAt(double x, double z, double feetY);
	}
	
	public static final int PATCH = 5, DIRECTIONS = 8;
	public static final int INPUTS = PATCH * PATCH + 2 * DIRECTIONS;
	/** How far the rays look, blocks. */
	public static final double RAY = 12;
	/**
	 * The far view (observation version 12, user 2026-10-27: see across an
	 * arena): the same layout, coarser - a 5 x 5 patch 6 blocks apart (24
	 * blocks across) and rays to 48 blocks in steps of 2.
	 */
	public static final double FAR_RAY = 48, FAR_STEP = 2, FAR_PATCH_STEP = 6;
	/** Higher than this (from your feet) can't be jumped onto: a wall. */
	static final double WALL = 1.25;
	/** Lower than this is a drop worth avoiding. */
	static final double DROP = -3;
	private static final double PATCH_STEP = 1.5;
	private static final double[] DIR_COS = new double[DIRECTIONS],
		DIR_SIN = new double[DIRECTIONS];
	static
	{
		for(int d = 0; d < DIRECTIONS; d++)
		{
			DIR_COS[d] = Math.cos(d * Math.PI * 2 / DIRECTIONS);
			DIR_SIN[d] = Math.sin(d * Math.PI * 2 / DIRECTIONS);
		}
	}
	
	/**
	 * Writes the inputs to out[at .. at + INPUTS): (ax, az) is the unit
	 * vector toward the opponent.
	 */
	public static void sense(Heights h, double x, double y, double z, double ax,
		double az, float[] out, int at)
	{
		sense(h, x, y, z, ax, az, out, at, PATCH_STEP, RAY, 1);
	}
	
	/** The far view's inputs (FAR_*), the same layout as sense()'s. */
	public static void senseFar(Heights h, double x, double y, double z,
		double ax, double az, float[] out, int at)
	{
		sense(h, x, y, z, ax, az, out, at, FAR_PATCH_STEP, FAR_RAY, FAR_STEP);
	}
	
	private static void sense(Heights h, double x, double y, double z,
		double ax, double az, float[] out, int at, double patchStep, double ray,
		double rayStep)
	{
		double sx = -az, sz = ax;
		int i = at;
		int r = PATCH / 2;
		for(int a = -r; a <= r; a++)
			for(int s = -r; s <= r; s++)
			{
				double px = x + (a * ax + s * sx) * patchStep,
					pz = z + (a * az + s * sz) * patchStep;
				double rel = h.heightAt(px, pz, y) - y;
				out[i++] = (float)Math.max(-2, Math.min(2, rel / 2));
			}
		for(int d = 0; d < DIRECTIONS; d++)
		{
			double ca = DIR_COS[d], sa = DIR_SIN[d];
			// direction d: 0 = toward them, 2 = to your left, 4 = away
			double dx = ax * ca + sx * sa, dz = az * ca + sz * sa;
			double wall = ray, drop = ray;
			for(double t = rayStep; t <= ray; t += rayStep)
			{
				double rel = h.heightAt(x + dx * t, z + dz * t, y) - y;
				if(wall == ray && rel >= WALL)
					wall = t;
				if(drop == ray && rel <= DROP)
					drop = t;
				if(wall < ray && drop < ray)
					break;
			}
			out[i++] = (float)(wall / ray);
			out[i++] = (float)(drop / ray);
		}
	}
	
	// ── ender pearls ─────────────────────────────────────────────────────
	
	/**
	 * Where a pearl thrown from the eyes (x, y, z) with this velocity lands
	 * on this ground: {x, y, z}, or null if it flies off for over 10 s.
	 * ThrowableProjectile's tick: gravity 0.03, drag 0.99, then the move; it
	 * stops at the first column higher than it.
	 */
	public static double[] pearlLanding(Heights h, double x, double y, double z,
		double vx, double vy, double vz, double feetY)
	{
		for(int t = 0; t < 200; t++)
		{
			vy = (vy - PvpSim.PEARL_GRAVITY) * 0.99;
			vx *= 0.99;
			vz *= 0.99;
			double nx = x + vx, ny = y + vy, nz = z + vz;
			double ground = h.heightAt(nx, nz, Math.max(ny, feetY));
			if(ny <= ground)
			{
				// onto the ground there, or against a wall: just before it
				if(ground - y > 0.5)
					return new double[]{x, h.heightAt(x, z, y), z};
				return new double[]{nx, ground, nz};
			}
			x = nx;
			y = ny;
			z = nz;
		}
		return null;
	}
	
	/**
	 * The best pearl throw, {yaw, pitch} in degrees (MC's: yaw 0 = +z, pitch
	 * down positive), out of 16 directions x 6 pitches, each landing worked
	 * out over this ground. Out (to heal): as far from every player as it
	 * gets, never into a drop; in: as near to a point just short of the
	 * target as it gets.
	 */
	public static double[] planPearl(Heights h, double eyeX, double eyeY,
		double eyeZ, double feetY, double vx, double vy, double vz, boolean in,
		double targetX, double targetZ, double[] playersX, double[] playersZ,
		int players)
	{
		double bestScore = Double.NEGATIVE_INFINITY, bestYaw = 0,
			bestPitch = -30;
		double dx = targetX - eyeX, dz = targetZ - eyeZ;
		double dist = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
		double wantX = targetX - dx / dist * PvpSim.PEARL_SHORT,
			wantZ = targetZ - dz / dist * PvpSim.PEARL_SHORT;
		// the 16 directions start straight at the target, so pearling in
		// throws at them, not up to 11 degrees to one side
		double towardYaw = Math.toDegrees(Math.atan2(-dx, dz));
		for(int d = 0; d < 16; d++)
		{
			double yaw = towardYaw + d * 22.5;
			double ry = Math.toRadians(yaw);
			for(double pitch : new double[]{-45, -30, -20, -10, 0, 15})
			{
				double rp = Math.toRadians(pitch);
				double cp = Math.cos(rp) * PvpSim.PEARL_SPEED;
				double[] land = pearlLanding(h, eyeX, eyeY - 0.1, eyeZ,
					-Math.sin(ry) * cp + vx,
					-Math.sin(rp) * PvpSim.PEARL_SPEED + vy,
					Math.cos(ry) * cp + vz, feetY);
				if(land == null)
					continue;
				double score;
				if(in)
				{
					double ex = land[0] - wantX, ez = land[2] - wantZ;
					score = -Math.sqrt(ex * ex + ez * ez);
				}else
				{
					double nearest = Double.MAX_VALUE;
					for(int p = 0; p < players; p++)
					{
						double px = land[0] - playersX[p],
							pz = land[2] - playersZ[p];
						nearest = Math.min(nearest, px * px + pz * pz);
					}
					score = Math.sqrt(nearest);
					// not into a pit or off a cliff
					if(land[1] - feetY <= DROP)
						score -= 100;
				}
				if(score > bestScore)
				{
					bestScore = score;
					bestYaw = yaw;
					bestPitch = pitch;
				}
			}
		}
		return new double[]{bestYaw, bestPitch};
	}
}
