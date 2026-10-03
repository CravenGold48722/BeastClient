/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import net.minecraft.world.phys.Vec3;

/**
 * Predicts where a target will be, horizontally, some ticks from now - built
 * to hit targets that keep changing direction, like a player strafing back
 * and forth, where leading by the current velocity is the worst possible
 * guess (it aims where the target will be <i>if it doesn't turn around</i>,
 * and it always turns around).
 *
 * <p>
 * The approach is the one the best Robocode guns use:
 * <ol>
 * <li><b>Pattern matching, played forward.</b> Every tick the target's
 * movement is logged relative to the shooter (sideways and toward/away). To
 * predict, the last {@value #PATTERN} ticks plus "ticks since it last turned
 * around" are compared against the whole log, and the {@value #NEIGHBORS}
 * most similar moments are replayed forward: what the target did next back
 * then is what it's likely to do next now. This learns the strafe rhythm,
 * including irregular ones.</li>
 * <li><b>Densest spot, not the average.</b> Those replays scatter the
 * predicted position. Averaging them would aim between two likely spots and
 * hit neither, so it aims at the spot where the most replays fall within
 * the target's hit width (half its width plus the projectile's hit margin),
 * centered in that range so there's room for error on both sides.</li>
 * <li><b>Virtual guns.</b> Alongside, three simpler guns predict too:
 * averaged velocity (aims at the middle of a back-and-forth), linear (the
 * current velocity, for targets running straight) and standing still. Each
 * gun's prediction is checked against where the target really was when the
 * time came, and the gun that would have hit most often lately is used.
 * So a target that changes its behaviour gets a gun that fits it.</li>
 * </ol>
 * The log covers {@value #CAPACITY} ticks, and a whole tick's work is a few
 * thousand arithmetic operations on plain arrays - microseconds.
 */
public final class TargetPredictor
{
	/** Ticks of movement remembered (a minute). */
	private static final int CAPACITY = 1200;
	
	/** Ticks of recent movement compared when matching. */
	private static final int PATTERN = 10;
	
	/** How many similar moments are replayed. */
	private static final int NEIGHBORS = 24;
	
	/** "Ticks since it turned around" counts up to this. */
	private static final int FLIP_CAP = 40;
	
	/** Sideways speed below this doesn't count as a direction. */
	private static final double DEADBAND = 0.04;
	
	/** Ticks of movement the averaged gun averages over. */
	private static final int AVERAGE_TICKS = 20;
	
	/**
	 * The server handles the shot between ticks, then moves the projectile once
	 * per tick and checks it against where the target is on that whole tick.
	 * So a projectile that reaches a point after a fractional {@code t} ticks
	 * meets the target as it is on tick ceil(t) - half a tick later on
	 * average.
	 */
	private static final double ARRIVAL_DELAY = 0.5;
	
	public enum Gun
	{
		PATTERN,
		AVERAGE,
		LINEAR,
		STILL
	}
	
	// The log, indexed by absolute tick number modulo CAPACITY.
	private final double[] px = new double[CAPACITY];
	private final double[] pz = new double[CAPACITY];
	/** Positions averaged with the previous tick (smooths the server steps). */
	private final double[] qx = new double[CAPACITY];
	private final double[] qz = new double[CAPACITY];
	/** The shooter-to-target direction at that tick (unit, horizontal). */
	private final double[] dirX = new double[CAPACITY];
	private final double[] dirZ = new double[CAPACITY];
	/** Velocity sideways / away from the shooter, blocks per tick. */
	private final double[] lat = new double[CAPACITY];
	private final double[] adv = new double[CAPACITY];
	private final int[] sinceFlip = new int[CAPACITY];
	
	/** Ticks logged so far; the newest is {@code count - 1}. */
	private int count;
	private int lastSide;
	
	// the last position the server sent, and how it got there
	private double rawX;
	private double rawZ;
	private double stepX;
	private double stepZ;
	private int ticksSinceUpdate;
	
	// this tick's best matches
	private final int[] matchIndex = new int[NEIGHBORS];
	private final double[] matchWeight = new double[NEIGHBORS];
	private int matches;
	
	// scratch for the densest-spot search
	private final double[] candLat = new double[NEIGHBORS];
	private final double[] candAdv = new double[NEIGHBORS];
	private final double[] candWeight = new double[NEIGHBORS];
	
	private double latencyTicks;
	private double baseHalfWidth = 0.3;
	
	/** How often each gun would have hit lately, 0-1. */
	private final double[] rating = new double[Gun.values().length];
	private final ArrayList<PendingCheck> pending = new ArrayList<>();
	
	private record PendingCheck(int baseTick, int dueTick, double[] x,
		double[] z, double halfWidth)
	{}
	
	public void reset()
	{
		count = 0;
		lastSide = 0;
		stepX = stepZ = 0;
		ticksSinceUpdate = 0;
		matches = 0;
		pending.clear();
		Arrays.fill(rating, 0);
	}
	
	/**
	 * Logs one tick. Call once per tick with where the server has the target
	 * (not the client's smoothed position, which lags 2-3 ticks behind) and
	 * where the shooter is.
	 */
	public void record(Vec3 target, Vec3 shooter)
	{
		int i = count;
		int s = slot(i);
		double[] now = fillIn(target);
		px[s] = now[0];
		pz[s] = now[1];
		
		double dx = now[0] - shooter.x;
		double dz = now[1] - shooter.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		dirX[s] = len < 1e-6 ? 1 : dx / len;
		dirZ[s] = len < 1e-6 ? 0 : dz / len;
		
		if(i == 0)
		{
			qx[s] = now[0];
			qz[s] = now[1];
			lat[s] = adv[s] = 0;
			sinceFlip[s] = 0;
			
		}else
		{
			int p = slot(i - 1);
			qx[s] = (now[0] + px[p]) / 2;
			qz[s] = (now[1] + pz[p]) / 2;
			
			// velocity over two ticks, since the server sends most
			// entities' positions only every second tick
			int back = Math.min(2, i);
			int b = slot(i - back);
			double vx = (now[0] - px[b]) / back;
			double vz = (now[1] - pz[b]) / back;
			lat[s] = vx * -dirZ[s] + vz * dirX[s];
			adv[s] = vx * dirX[s] + vz * dirZ[s];
			
			int side = lat[s] > DEADBAND ? 1 : lat[s] < -DEADBAND ? -1 : 0;
			if(side != 0 && side != lastSide)
			{
				sinceFlip[s] = 0;
				lastSide = side;
			}else
				sinceFlip[s] = Math.min(sinceFlip[p] + 1, FLIP_CAP);
		}
		
		count++;
		checkPending();
	}
	
	/**
	 * Gets ready to predict for this tick: finds the matching moments and
	 * lets each virtual gun take a practice shot.
	 *
	 * @param flightTicks
	 *            roughly how long the projectile will fly (last tick's
	 *            solution is fine)
	 * @param latencyTicks
	 *            the round trip to the server in ticks - what we see is half
	 *            of it old, and the shot arrives half of it late
	 * @param baseHalfWidth
	 *            half the target's width
	 */
	public void prepare(double flightTicks, double latencyTicks,
		double baseHalfWidth)
	{
		this.latencyTicks = latencyTicks;
		this.baseHalfWidth = baseHalfWidth;
		findMatches(flightTicks + latencyTicks + 2);
		
		// practice shots, checked once their time has come
		int horizon =
			(int)Math.round(flightTicks + ARRIVAL_DELAY + latencyTicks);
		if(count == 0 || horizon < 1)
			return;
		
		Gun[] guns = Gun.values();
		double[] x = new double[guns.length];
		double[] z = new double[guns.length];
		for(Gun gun : guns)
		{
			double[] off = predictOffset(gun, flightTicks);
			x[gun.ordinal()] = off[0];
			z[gun.ordinal()] = off[1];
		}
		pending.add(new PendingCheck(count - 1, count - 1 + horizon, x, z,
			hitHalfWidth(flightTicks)));
	}
	
	/** The gun that would have hit most often lately. */
	public Gun getBestGun()
	{
		// The ratings are noisy (about +-7% over the ~25 shots they remember),
		// so a simpler gun has to be clearly better than pattern matching to
		// take over - switching on noise costs more hits than it wins.
		Gun best = Gun.PATTERN;
		double bar = rating[Gun.PATTERN.ordinal()] + 0.1;
		for(Gun gun : Gun.values())
			if(rating[gun.ordinal()] > bar)
			{
				best = gun;
				bar = rating[gun.ordinal()];
			}
		return best;
	}
	
	public double getRating(Gun gun)
	{
		return rating[gun.ordinal()];
	}
	
	/**
	 * How far the target will have moved horizontally (x, z) by the time a
	 * projectile fired now and flying {@code flightTicks} gets there, using
	 * the best gun.
	 */
	/**
	 * How far ahead of what we see the target has to be predicted, for a
	 * projectile flying {@code flightTicks}: the flight, the half tick until
	 * the server checks the hit, and the round trip.
	 */
	public static double lookAhead(double flightTicks, double latencyTicks)
	{
		return flightTicks + ARRIVAL_DELAY + latencyTicks;
	}
	
	/**
	 * Where the target will be horizontally (x, z) by the time a projectile
	 * fired now and flying {@code flightTicks} gets there, using the best gun.
	 */
	public double[] predict(double flightTicks)
	{
		return predict(getBestGun(), flightTicks);
	}
	
	public double[] predict(Gun gun, double flightTicks)
	{
		double[] off = predictOffset(gun, flightTicks);
		if(count == 0)
			return off;
		
		int s = slot(count - 1);
		return new double[]{px[s] + off[0], pz[s] + off[1]};
	}
	
	public double[] predictOffset(double flightTicks)
	{
		return predictOffset(getBestGun(), flightTicks);
	}
	
	public double[] predictOffset(Gun gun, double flightTicks)
	{
		if(count < 2)
			return new double[]{0, 0};
		
		double t = lookAhead(flightTicks, latencyTicks);
		return switch(gun)
		{
			case PATTERN -> patternOffset(t, hitHalfWidth(flightTicks));
			case AVERAGE -> velocityOffset(AVERAGE_TICKS, t);
			case LINEAR -> velocityOffset(4, t);
			case STILL -> new double[]{0, 0};
		};
	}
	
	/**
	 * Half the width that counts as a hit: vanilla grows the target's hitbox
	 * by the projectile's age / 20, up to 0.3 (ProjectileUtil.computeMargin).
	 */
	private double hitHalfWidth(double flightTicks)
	{
		return baseHalfWidth + Math.clamp((flightTicks - 2) / 20, 0, 0.3);
	}
	
	private double[] velocityOffset(int ticks, double t)
	{
		int n = count - 1;
		int back = Math.min(ticks, n);
		int b = slot(n - back);
		int s = slot(n);
		return new double[]{(px[s] - px[b]) / back * t,
			(pz[s] - pz[b]) / back * t};
	}
	
	private void findMatches(double horizon)
	{
		matches = 0;
		int n = count - 1;
		int future = (int)Math.ceil(horizon);
		int oldest = Math.max(0, count - CAPACITY) + PATTERN + 2;
		int newest = n - future;
		if(newest < oldest)
			return;
		
		double[] bestDist = new double[NEIGHBORS];
		int sn = slot(n);
		
		for(int j = oldest; j <= newest; j++)
		{
			double d = 0;
			for(int k = 0; k < PATTERN; k++)
			{
				int a = slot(n - k);
				int b = slot(j - k);
				double dl = lat[a] - lat[b];
				double da = adv[a] - adv[b];
				// the latest ticks matter most
				d += (dl * dl + da * da) * (1 - 0.5 * k / PATTERN);
			}
			// 0.05 blocks/tick average difference = 1
			d /= 0.0025 * PATTERN;
			
			// where in the strafe it is (just turned vs. been going a while)
			double f = (sinceFlip[sn] - sinceFlip[slot(j)]) / 6.0;
			d += f * f;
			
			// slight preference for recent behaviour
			d += 0.5 * (n - j) / CAPACITY;
			
			insertMatch(j, d, bestDist);
		}
		
		for(int i = 0; i < matches; i++)
			matchWeight[i] = 1 / (1 + bestDist[i]);
	}
	
	/** Keeps the {@value #NEIGHBORS} closest matches, sorted by distance. */
	private void insertMatch(int j, double d, double[] bestDist)
	{
		if(matches == NEIGHBORS && d >= bestDist[NEIGHBORS - 1])
			return;
		
		int i = Math.min(matches, NEIGHBORS - 1);
		while(i > 0 && bestDist[i - 1] > d)
		{
			bestDist[i] = bestDist[i - 1];
			matchIndex[i] = matchIndex[i - 1];
			i--;
		}
		bestDist[i] = d;
		matchIndex[i] = j;
		if(matches < NEIGHBORS)
			matches++;
	}
	
	private double[] patternOffset(double t, double halfWidth)
	{
		int n = count - 1;
		int sn = slot(n);
		
		// replay each match: where it went next, turned into the current
		// sideways/away directions
		int c = 0;
		for(int i = 0; i < matches; i++)
		{
			int j = matchIndex[i];
			if(j + t > n)
				continue;
			
			double fx = positionAt(qx, j + t) - qx[slot(j)];
			double fz = positionAt(qz, j + t) - qz[slot(j)];
			int sj = slot(j);
			candLat[c] = fx * -dirZ[sj] + fz * dirX[sj];
			candAdv[c] = fx * dirX[sj] + fz * dirZ[sj];
			candWeight[c] = matchWeight[i];
			c++;
		}
		
		// not enough history yet
		if(c < 3)
			return velocityOffset(AVERAGE_TICKS, t);
		
		// the sideways spot that the most replays fall within reach of
		double bestScore = -1;
		double bestLat = 0;
		for(int a = 0; a < c; a++)
		{
			double score = 0;
			for(int b = 0; b < c; b++)
				if(Math.abs(candLat[b] - candLat[a]) <= halfWidth)
					score += candWeight[b];
				
			if(score > bestScore)
			{
				bestScore = score;
				bestLat = candLat[a];
			}
		}
		
		// center on those replays, and take their average toward/away
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		double advSum = 0;
		double weightSum = 0;
		for(int b = 0; b < c; b++)
		{
			if(Math.abs(candLat[b] - bestLat) > halfWidth)
				continue;
			
			min = Math.min(min, candLat[b]);
			max = Math.max(max, candLat[b]);
			advSum += candAdv[b] * candWeight[b];
			weightSum += candWeight[b];
		}
		double latOff = (min + max) / 2;
		double advOff = advSum / weightSum;
		
		return new double[]{latOff * -dirZ[sn] + advOff * dirX[sn],
			latOff * dirX[sn] + advOff * dirZ[sn]};
	}
	
	/**
	 * The server sends most entities' positions only every second tick (some
	 * every third), so in between, the client still has the old one - up to
	 * a tick or two of movement behind. Those ticks are filled in by
	 * continuing the last step, so the log, and every prediction's starting
	 * point, is where the target really is now. A position that stays the
	 * same for longer means it has stopped.
	 */
	private double[] fillIn(Vec3 target)
	{
		if(count == 0 || target.x != rawX || target.z != rawZ)
		{
			if(count > 0)
			{
				int gap = ticksSinceUpdate + 1;
				stepX = (target.x - rawX) / gap;
				stepZ = (target.z - rawZ) / gap;
			}
			rawX = target.x;
			rawZ = target.z;
			ticksSinceUpdate = 0;
			return new double[]{target.x, target.z};
		}
		
		ticksSinceUpdate++;
		if(ticksSinceUpdate > 2)
			return new double[]{target.x, target.z};
		
		return new double[]{target.x + stepX * ticksSinceUpdate,
			target.z + stepZ * ticksSinceUpdate};
	}
	
	/** A logged (smoothed) coordinate at a fractional tick. */
	private double positionAt(double[] q, double tick)
	{
		int i = (int)Math.floor(tick);
		double f = tick - i;
		double a = q[slot(i)];
		if(f < 1e-9)
			return a;
		return a + (q[slot(i + 1)] - a) * f;
	}
	
	/** Scores the practice shots whose time has come. */
	private void checkPending()
	{
		int n = count - 1;
		for(Iterator<PendingCheck> itr = pending.iterator(); itr.hasNext();)
		{
			PendingCheck check = itr.next();
			if(check.dueTick() > n)
				continue;
			
			itr.remove();
			if(check.dueTick() == n && check.baseTick() > n - CAPACITY)
				score(check);
		}
	}
	
	/**
	 * Would each gun's practice shot have hit? Compares how far the target
	 * really moved since the shot with how far the gun said it would.
	 * Sideways misses must be within the hit width; toward/away misses matter
	 * much less, since the projectile flies through the target's whole depth.
	 */
	private void score(PendingCheck check)
	{
		int b = slot(check.baseTick());
		int s = slot(check.dueTick());
		double mx = px[s] - px[b];
		double mz = pz[s] - pz[b];
		
		for(int g = 0; g < rating.length; g++)
		{
			double ex = mx - check.x()[g];
			double ez = mz - check.z()[g];
			double latErr = Math.abs(ex * -dirZ[b] + ez * dirX[b]);
			double advErr = Math.abs(ex * dirX[b] + ez * dirZ[b]);
			boolean hit =
				latErr <= check.halfWidth() && advErr <= check.halfWidth() + 1;
			rating[g] = rating[g] * 0.96 + (hit ? 0.04 : 0);
		}
	}
	
	private static int slot(int tick)
	{
		return Math.floorMod(tick, CAPACITY);
	}
}
