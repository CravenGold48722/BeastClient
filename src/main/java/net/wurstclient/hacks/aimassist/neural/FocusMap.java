/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.Arrays;

/**
 * Everything around you as a few cells whose size follows how much is going
 * on (user, 2026-10-25): where many players, projectiles, webs and totem
 * pops are, the cells are small and sharp; an empty side is one big cell, or
 * nothing at all. Built the same way in PvpSim and in game.
 *
 * <p>
 * Points of interest go in with a weight (a player counts most). Starting
 * from one cell around all of them, the cell carrying the most weight is cut
 * in two along its longer side - at the widest gap in it, else the middle
 * of what's in it - until there
 * are {@link #CELLS} or nothing left to cut. The cells go to the network
 * heaviest first, in the frame toward the opponent ("along", "side"), with
 * distances squashed so far things still fit.
 *
 * <p>
 * Per cell: where its contents are (along, side), how big it is, how many
 * players / projectiles / webs it holds, the lowest health of its players
 * and whether a totem just popped there. No MC classes; one per fighter, one
 * thread.
 */
public final class FocusMap
{
	public static final int CELLS = 6, FEATURES = 8, INPUTS = CELLS * FEATURES;
	public static final int PLAYER = 0, PROJECTILE = 1, WEB = 2, TOTEM = 3;
	/** A totem pop stays on the map this long. */
	public static final int TOTEM_TICKS = 40;
	/**
	 * Webs count within this many blocks (x and z, of your feet's block)
	 * and one block up or down of your feet - in game (NeuralSpace) and in
	 * PvpSim alike.
	 */
	public static final int WEB_SCAN = 8;
	private static final double[] WEIGHT = {4, 2, 1, 2};
	private static final int MAX_POINTS = 96;
	
	// points: frame position, kind, a player's health fraction
	private final double[] pa = new double[MAX_POINTS],
		ps = new double[MAX_POINTS], ph = new double[MAX_POINTS];
	private final int[] kind = new int[MAX_POINTS];
	/** The points in cell order (sorting moves these, not the points). */
	private final int[] idx = new int[MAX_POINTS];
	/** Scratch for the merge sort. */
	private final int[] tmp = new int[MAX_POINTS];
	private int n;
	private double ax, az, cx, cz, range;
	
	// cells: index ranges into the (reordered) points, bounds
	private final int[] from = new int[CELLS], to = new int[CELLS];
	private final double[] loA = new double[CELLS], hiA = new double[CELLS],
		loS = new double[CELLS], hiS = new double[CELLS];
	private final double[] cellWeight = new double[CELLS];
	private final int[] order = new int[CELLS];
	
	/**
	 * A new map around (cx, cz), unit vector (ax, az) toward the opponent,
	 * points up to {@code range} blocks away.
	 */
	public void clear(double cx, double cz, double ax, double az, double range)
	{
		this.cx = cx;
		this.cz = cz;
		this.ax = ax;
		this.az = az;
		this.range = range;
		n = 0;
	}
	
	/** A point of interest at world x, z; health 0..1 for players. */
	public void add(double x, double z, int what, double health)
	{
		if(n >= MAX_POINTS)
			return;
		double dx = x - cx, dz = z - cz;
		if(dx * dx + dz * dz > range * range)
			return;
		pa[n] = dx * ax + dz * az;
		ps[n] = dx * -az + dz * ax;
		kind[n] = what;
		ph[n] = health;
		n++;
	}
	
	/** The cells, heaviest first, into out[at .. at + INPUTS). */
	public void write(float[] out, int at)
	{
		Arrays.fill(out, at, at + INPUTS, 0);
		if(n == 0)
			return;
		
		for(int k = 0; k < n; k++)
			idx[k] = k;
		int cells = 1;
		from[0] = 0;
		to[0] = n;
		bounds(0);
		while(cells < CELLS)
		{
			// the heaviest cell that still has two points to split
			int best = -1;
			double bestW = 0;
			for(int c = 0; c < cells; c++)
				if(to[c] - from[c] >= 2 && cellWeight[c] > bestW)
				{
					bestW = cellWeight[c];
					best = c;
				}
			if(best < 0)
				break;
			boolean alongAxis = hiA[best] - loA[best] >= hiS[best] - loS[best];
			int mid = splitAtMedian(from[best], to[best], alongAxis);
			from[cells] = mid;
			to[cells] = to[best];
			to[best] = mid;
			bounds(best);
			bounds(cells);
			cells++;
		}
		
		// heaviest first (insertion sort: a handful of cells, no garbage)
		for(int c = 0; c < cells; c++)
		{
			int j = c;
			for(; j > 0 && cellWeight[order[j - 1]] < cellWeight[c]; j--)
				order[j] = order[j - 1];
			order[j] = c;
		}
		for(int slot = 0; slot < cells; slot++)
		{
			int c = order[slot], i = at + slot * FEATURES;
			double sumA = 0, sumS = 0, low = 1;
			int players = 0, projectiles = 0, webs = 0;
			boolean totem = false;
			for(int k = from[c]; k < to[c]; k++)
			{
				int j = idx[k];
				sumA += pa[j];
				sumS += ps[j];
				switch(kind[j])
				{
					case PLAYER ->
					{
						players++;
						low = Math.min(low, ph[j]);
					}
					case PROJECTILE -> projectiles++;
					case WEB -> webs++;
					default -> totem = true;
				}
			}
			int m = to[c] - from[c];
			out[i] = squash(sumA / m);
			out[i + 1] = squash(sumS / m);
			out[i + 2] = squash(Math.max(hiA[c] - loA[c], hiS[c] - loS[c]));
			out[i + 3] = Math.min(players, 4) / 4F;
			out[i + 4] = Math.min(projectiles, 4) / 4F;
			out[i + 5] = Math.min(webs, 8) / 8F;
			out[i + 6] = players > 0 ? (float)low : 1;
			out[i + 7] = totem ? 1 : -1;
		}
	}
	
	/** Distance in blocks to about -3..3: exact near you, compressed far. */
	static float squash(double blocks)
	{
		// (log(1 + x): log1p's extra precision near 0 doesn't matter here,
		// and it was 7% of the simulator's time)
		return (float)(Math.signum(blocks)
			* Math.log(1 + Math.abs(blocks) / 4));
	}
	
	private void bounds(int c)
	{
		double la = Double.MAX_VALUE, ha = -Double.MAX_VALUE,
			ls = Double.MAX_VALUE, hs = -Double.MAX_VALUE, w = 0;
		for(int k = from[c]; k < to[c]; k++)
		{
			int j = idx[k];
			double a = pa[j], b = ps[j];
			if(a < la)
				la = a;
			if(a > ha)
				ha = a;
			if(b < ls)
				ls = b;
			if(b > hs)
				hs = b;
			w += WEIGHT[kind[j]];
		}
		loA[c] = la;
		hiA[c] = ha;
		loS[c] = ls;
		hiS[c] = hs;
		cellWeight[c] = w;
	}
	
	/**
	 * Reorders points [lo, hi) by one coordinate and returns where to cut:
	 * at the widest gap between neighbours if it's a clear one (a lone thing
	 * far from a crowd gets a cell of its own), else in the middle.
	 */
	private int splitAtMedian(int lo, int hi, boolean alongAxis)
	{
		// the indices sorted by the coordinate, ties kept in order
		double[] key = alongAxis ? pa : ps;
		sort(key, lo, hi);
		int gapAt = -1;
		double gap = 0;
		for(int i = lo + 1; i < hi; i++)
		{
			double g = key[idx[i]] - key[idx[i - 1]];
			if(g > gap)
			{
				gap = g;
				gapAt = i;
			}
		}
		if(gap > 0.25 * (key[idx[hi - 1]] - key[idx[lo]]))
			return gapAt;
		return lo + (hi - lo) / 2;
	}
	
	/**
	 * Stable: insertion sort for a few points, a merge sort for many (an
	 * FFA's webs filled the map, and insertion sort's n^2 was 9% of the
	 * trainer's time) - the same order either way.
	 */
	private void sort(double[] key, int lo, int hi)
	{
		if(hi - lo <= 16)
		{
			for(int i = lo + 1; i < hi; i++)
			{
				int moving = idx[i];
				double k = key[moving];
				int j = i;
				for(; j > lo && key[idx[j - 1]] > k; j--)
					idx[j] = idx[j - 1];
				idx[j] = moving;
			}
			return;
		}
		int mid = (lo + hi) >>> 1;
		sort(key, lo, mid);
		sort(key, mid, hi);
		if(key[idx[mid - 1]] <= key[idx[mid]])
			return;
		System.arraycopy(idx, lo, tmp, lo, hi - lo);
		int i = lo, j = mid;
		for(int k = lo; k < hi; k++)
			if(j >= hi || i < mid && key[tmp[i]] <= key[tmp[j]])
				idx[k] = tmp[i++];
			else
				idx[k] = tmp[j++];
	}
}
