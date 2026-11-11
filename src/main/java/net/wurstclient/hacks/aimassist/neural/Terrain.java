/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * PvpSim's arena (user, 2026-10-25): 2 chunks across, a height per block
 * column - flat, or with hills (1-block steps), walls, pillars and pits -
 * and a wall all around. Solid from far below up to the column's height; no
 * overhangs. No MC classes.
 *
 * <p>
 * Players move through it like Entity.move: down first, then the larger of
 * x and z, then the other, each axis stopped by any column of its 0.6-wide
 * box that's higher than its feet. Heights are whole blocks, so the 0.6 step
 * up never climbs one - a block takes a jump (1.25 high), like in game.
 */
public final class Terrain implements TerrainSense.Heights
{
	/** Blocks across; the arena is x, z in [-HALF, HALF). */
	public static final int SIZE = 32, HALF = SIZE / 2;
	/** The arena's edge. */
	static final int EDGE = 40;
	private static final double EPS = 1e-7, HALF_WIDTH = 0.3;
	
	/**
	 * Heights with PAD columns of edge wall all around, so the lookups near
	 * the arena (TerrainSense's rays, 12 blocks) need one range check.
	 */
	private static final int PAD = 16, WIDTH = SIZE + 2 * PAD,
		OFFSET = HALF + PAD;
	private final int[] height = new int[WIDTH * WIDTH];
	
	private Terrain()
	{
		Arrays.fill(height, EDGE);
		for(int x = -HALF; x < HALF; x++)
			for(int z = -HALF; z < HALF; z++)
				height[(x + OFFSET) * WIDTH + z + OFFSET] = 0;
	}
	
	boolean flat = true;
	
	public static Terrain flat()
	{
		return new Terrain();
	}
	
	/**
	 * A random arena: a third flat (most duel arenas are), the rest with
	 * hills, walls, pillars and pits - with the middle, where fights start,
	 * kept walkable.
	 */
	public static Terrain random(SplittableRandom r)
	{
		Terrain t = new Terrain();
		if(r.nextInt(3) == 0)
			return t;
		t.flat = false;
		for(int i = r.nextInt(4); i > 0; i--)
		{
			// a hill in 1-block steps
			int cx = r.nextInt(-HALF + 3, HALF - 3),
				cz = r.nextInt(-HALF + 3, HALF - 3);
			int radius = 3 + r.nextInt(5), peak = 1 + r.nextInt(4);
			for(int x = cx - radius; x <= cx + radius; x++)
				for(int z = cz - radius; z <= cz + radius; z++)
				{
					double d =
						Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
					int h = (int)Math.round(peak * (1 - d / (radius + 1)));
					if(h > 0)
						t.raise(x, z, h);
				}
		}
		for(int i = r.nextInt(4); i > 0; i--)
		{
			// a wall, 2-3 high
			boolean alongX = r.nextBoolean();
			int len = 4 + r.nextInt(8), h = 2 + r.nextInt(2);
			int x0 = r.nextInt(-HALF + 1, HALF - 1),
				z0 = r.nextInt(-HALF + 1, HALF - 1);
			for(int k = 0; k < len; k++)
				t.raise(alongX ? x0 + k : x0, alongX ? z0 : z0 + k,
					t.at(alongX ? x0 + k : x0, alongX ? z0 : z0 + k) + h);
		}
		for(int i = r.nextInt(5); i > 0; i--)
		{
			// a pillar
			int x = r.nextInt(-HALF + 1, HALF - 1),
				z = r.nextInt(-HALF + 1, HALF - 1);
			t.raise(x, z, t.at(x, z) + 2 + r.nextInt(3));
		}
		for(int i = r.nextInt(3); i > 0; i--)
		{
			// a pit, 3-6 deep
			int w = 2 + r.nextInt(3), d = 2 + r.nextInt(3);
			int x0 = r.nextInt(-HALF + 1, HALF - w),
				z0 = r.nextInt(-HALF + 1, HALF - d);
			int depth = 3 + r.nextInt(4);
			for(int x = x0; x < x0 + w; x++)
				for(int z = z0; z < z0 + d; z++)
					t.set(x, z, -depth);
		}
		// where the fights start: open ground
		for(int x = -3; x <= 3; x++)
			for(int z = -3; z <= 3; z++)
				t.set(x, z, 0);
		return t;
	}
	
	/** Flat with a wall this high along the column x = bx (for tests). */
	static Terrain wallAt(int bx, int h)
	{
		Terrain t = new Terrain();
		t.flat = false;
		for(int z = -HALF; z < HALF; z++)
			t.set(bx, z, h);
		return t;
	}
	
	/** The column's height (top of its highest block). */
	public int at(int bx, int bz)
	{
		int ix = bx + OFFSET, iz = bz + OFFSET;
		if(Integer.compareUnsigned(ix, WIDTH) >= 0
			|| Integer.compareUnsigned(iz, WIDTH) >= 0)
			return EDGE;
		return height[ix * WIDTH + iz];
	}
	
	@Override
	public double heightAt(double x, double z, double feetY)
	{
		return at((int)Math.floor(x), (int)Math.floor(z));
	}
	
	private void set(int bx, int bz, int h)
	{
		if(bx >= -HALF && bx < HALF && bz >= -HALF && bz < HALF)
			height[(bx + OFFSET) * WIDTH + bz + OFFSET] = h;
	}
	
	private void raise(int bx, int bz, int h)
	{
		set(bx, bz, Math.max(at(bx, bz), h));
	}
	
	/** The highest column under a player's box at x, z. */
	public double groundUnder(double x, double z)
	{
		int x0 = (int)Math.floor(x - HALF_WIDTH + EPS),
			x1 = (int)Math.floor(x + HALF_WIDTH - EPS);
		int z0 = (int)Math.floor(z - HALF_WIDTH + EPS),
			z1 = (int)Math.floor(z + HALF_WIDTH - EPS);
		int h = Integer.MIN_VALUE;
		for(int bx = x0; bx <= x1; bx++)
			for(int bz = z0; bz <= z1; bz++)
				h = Math.max(h, at(bx, bz));
		return h;
	}
	
	/**
	 * Entity.move of a player box: out = {x, y, z, onGround (1/0),
	 * horizontal collision on x (1/0), on z (1/0)}.
	 */
	public void move(double x, double y, double z, double dx, double dy,
		double dz, double[] out)
	{
		boolean onGround = false;
		// y first: down onto the highest column under the box
		double floor = groundUnder(x, z);
		if(dy <= 0 && y + dy <= floor + EPS && y >= floor - EPS)
		{
			dy = floor - y;
			onGround = true;
		}else if(dy < 0 && y + dy < floor)
		{
			dy = floor - y;
			onGround = true;
		}
		y += dy;
		
		// then the larger horizontal axis, then the other
		boolean xFirst = Math.abs(dx) >= Math.abs(dz);
		boolean hitX = false, hitZ = false;
		for(int pass = 0; pass < 2; pass++)
		{
			boolean onX = (pass == 0) == xFirst;
			double d = onX ? dx : dz;
			if(d == 0)
				continue;
			// in steps under half a block, so no column is skipped
			int steps = (int)Math.ceil(Math.abs(d) / 0.45);
			double step = d / steps;
			for(int s = 0; s < steps; s++)
			{
				double nx = onX ? x + step : x, nz = onX ? z : z + step;
				if(groundUnder(nx, nz) > y + EPS)
				{
					// stopped at the column's face
					if(onX)
					{
						hitX = true;
						x = step > 0
							? Math.floor(nx + HALF_WIDTH) - HALF_WIDTH - EPS
							: Math.floor(nx - HALF_WIDTH) + 1 + HALF_WIDTH
								+ EPS;
						if(groundUnder(x, z) > y + EPS)
							x -= step;
					}else
					{
						hitZ = true;
						z = step > 0
							? Math.floor(nz + HALF_WIDTH) - HALF_WIDTH - EPS
							: Math.floor(nz - HALF_WIDTH) + 1 + HALF_WIDTH
								+ EPS;
						if(groundUnder(x, z) > y + EPS)
							z -= step;
					}
					break;
				}
				x = nx;
				z = nz;
			}
		}
		out[0] = x;
		out[1] = y;
		out[2] = z;
		out[3] = onGround ? 1 : 0;
		out[4] = hitX ? 1 : 0;
		out[5] = hitZ ? 1 : 0;
	}
	
	@Override
	public String toString()
	{
		return flat ? "flat" : "terrain " + Arrays.hashCode(height);
	}
}
