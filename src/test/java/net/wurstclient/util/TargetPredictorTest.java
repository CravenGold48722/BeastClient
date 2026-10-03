/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.BallisticSolver.Projectile;
import net.wurstclient.util.BallisticSolver.Solution;
import net.wurstclient.util.TargetPredictor.Gun;

/**
 * Shoots full-charge arrows at a simulated player and counts real hits.
 *
 * <p>
 * The target moves with vanilla's ground physics (input 0.98 * 0.1
 * acceleration, then 0.6 * 0.91 friction: 0.216 blocks/tick top speed, a few
 * ticks to turn around). The shooter only learns its position the way the
 * client does - every second tick, from the server - and optionally late by
 * the ping. Each arrow flies with vanilla's drag and gravity and hits if its
 * path crosses the target's hitbox grown by vanilla's age margin, at the
 * target's true position on that tick.
 */
class TargetPredictorTest
{
	private static final Projectile ARROW = new Projectile(3, 0.05, 0.99);
	private static final double EYE = 1.62;
	private static final int WARM_UP = 300;
	private static final int SHOTS = 150;
	
	/** A strafe: which way the A/D input points on each tick (-1, 0, 1). */
	private interface Strafe
	{
		int input(int tick);
	}
	
	private static Strafe backAndForth(int halfPeriod)
	{
		return t -> (t / halfPeriod) % 2 == 0 ? 1 : -1;
	}
	
	/** Turns around after a random 5-15 ticks each time. */
	private static Strafe randomBackAndForth(long seed)
	{
		Random random = new Random(seed);
		int[] plan = new int[WARM_UP + SHOTS * 7 + 200];
		int dir = 1;
		for(int t = 0; t < plan.length;)
		{
			int run = 5 + random.nextInt(11);
			for(int i = 0; i < run && t < plan.length; i++)
				plan[t++] = dir;
			dir = -dir;
		}
		return t -> plan[t];
	}
	
	private record Result(int hits, int shots, double msPerTick)
	{
		double rate()
		{
			return hits / (double)shots;
		}
	}
	
	/**
	 * @param gun
	 *            the gun to force, or null to let the predictor pick
	 * @param latencyTicks
	 *            round trip; positions arrive half of it late and the arrow
	 *            leaves half of it late
	 */
	private static Result shoot(Strafe strafe, double distance, Gun gun,
		int latencyTicks)
	{
		int ticks = WARM_UP + SHOTS * 7 + 120;
		// A/D while facing the shooter: the input pushes sideways to it
		double[] x = new double[ticks];
		double[] z = new double[ticks];
		z[0] = distance;
		double vx = 0;
		double vz = 0;
		for(int t = 1; t < ticks; t++)
		{
			double len = Math.hypot(x[t - 1], z[t - 1]);
			double sideX = z[t - 1] / len;
			double sideZ = -x[t - 1] / len;
			vx += strafe.input(t) * 0.098 * sideX;
			vz += strafe.input(t) * 0.098 * sideZ;
			x[t] = x[t - 1] + vx;
			z[t] = z[t - 1] + vz;
			vx *= 0.546;
			vz *= 0.546;
		}
		
		int half = latencyTicks / 2;
		// what the client knows at tick t: the server's position from half
		// the ping ago, updated every second tick
		IntUnaryOperator seen = t -> {
			int s = Math.max(0, t - half);
			return s - s % 2;
		};
		
		TargetPredictor predictor = new TargetPredictor();
		Vec3 shooter = new Vec3(0, 0, 0);
		Vec3 start = shooter.add(0, EYE - 0.1, 0);
		double flight = distance / 3;
		int hits = 0;
		int shots = 0;
		long nanos = 0;
		
		for(int t = 0; t < WARM_UP + SHOTS * 7; t++)
		{
			int k = seen.applyAsInt(t);
			Vec3 known = new Vec3(x[k], 0, z[k]);
			
			long begin = System.nanoTime();
			predictor.record(known, shooter);
			predictor.prepare(flight, latencyTicks, 0.3);
			Solution s = BallisticSolver.solve(start, Vec3.ZERO, ARROW, tt -> {
				double[] p = gun == null ? predictor.predict(tt)
					: predictor.predict(gun, tt);
				return new Vec3(p[0], 0.9, p[1]);
			});
			if(t >= WARM_UP)
				nanos += System.nanoTime() - begin;
			flight = s.ticks();
			
			if(t < WARM_UP || (t - WARM_UP) % 7 != 0)
				continue;
			
			shots++;
			if(arrowHits(s, start, x, z, t + half))
				hits++;
		}
		
		return new Result(hits, shots, nanos / 1e6 / (SHOTS * 7));
	}
	
	/** Vanilla arrow flight against the target's true positions. */
	private static boolean arrowHits(Solution s, Vec3 start, double[] x,
		double[] z, int launchTick)
	{
		Vec3 pos = start;
		Vec3 v = BallisticSolver.launchVelocity(s.yaw(), s.pitch(), 3);
		for(int k = 1; k < 100 && launchTick + k < x.length; k++)
		{
			Vec3 next = pos.add(v);
			double margin = Math.clamp((k - 2) / 20.0, 0, 0.3);
			double tx = x[launchTick + k];
			double tz = z[launchTick + k];
			AABB box = new AABB(tx - 0.3, 0, tz - 0.3, tx + 0.3, 1.8, tz + 0.3)
				.inflate(margin);
			if(box.contains(pos) || box.clip(pos, next).isPresent())
				return true;
			if(next.horizontalDistance() > Math.hypot(tx, tz) + 2)
				return false;
			
			pos = next;
			v = v.scale(0.99).add(0, -0.05, 0);
		}
		return false;
	}
	
	private static void report(String name, double distance, int latency,
		Strafe strafe)
	{
		StringBuilder line = new StringBuilder(String.format(
			"%-22s %3.0f blocks %2d ping-ticks:", name, distance, latency));
		for(Gun gun : Gun.values())
			line.append(String.format(" %s %3.0f%%", gun,
				shoot(strafe, distance, gun, latency).rate() * 100));
		Result chosen = shoot(strafe, distance, null, latency);
		line.append(String.format("  | CHOSEN %3.0f%%  (%.3f ms/tick)",
			chosen.rate() * 100, chosen.msPerTick()));
		System.out.println(line);
	}
	
	@Test
	void printComparison()
	{
		for(double d : new double[]{15, 30, 45})
			for(int lat : new int[]{0, 4})
			{
				report("A/D every 4 ticks", d, lat, backAndForth(4));
				report("A/D every 8 ticks", d, lat, backAndForth(8));
				report("A/D every 15 ticks", d, lat, backAndForth(15));
				report("A/D random 5-15", d, lat, randomBackAndForth(42));
				report("circling one way", d, lat, t -> 1);
				report("standing still", d, lat, t -> 0);
			}
	}
	
	@Test
	void hitsRegularStrafing()
	{
		for(int halfPeriod : new int[]{4, 6, 8, 10, 15, 20})
			for(double d : new double[]{15, 30, 45})
			{
				Result old = shoot(backAndForth(halfPeriod), d, Gun.LINEAR, 0);
				Result now = shoot(backAndForth(halfPeriod), d, null, 0);
				assertTrue(now.rate() >= 0.9,
					"A/D every " + halfPeriod + " ticks at " + d
						+ " blocks: hit " + now.rate() + " (linear lead "
						+ old.rate() + ")");
			}
	}
	
	@Test
	void hitsRegularStrafingWithPing()
	{
		for(int halfPeriod : new int[]{4, 8, 15})
		{
			Result now = shoot(backAndForth(halfPeriod), 30, null, 4);
			assertTrue(now.rate() >= 0.85, "A/D every " + halfPeriod
				+ " ticks, 200 ms ping: hit " + now.rate());
		}
	}
	
	@Test
	void beatsLinearOnRandomStrafing()
	{
		for(long seed : new long[]{1, 2, 3})
		{
			Strafe strafe = randomBackAndForth(seed);
			Result old = shoot(strafe, 30, Gun.LINEAR, 0);
			Result now = shoot(strafe, 30, null, 0);
			assertTrue(now.rate() >= old.rate() + 0.2, "random strafing, seed "
				+ seed + ": " + now.rate() + " vs linear " + old.rate());
		}
	}
	
	@Test
	void stillHitsRunnersAndStandingTargets()
	{
		assertTrue(shoot(t -> 1, 30, null, 0).rate() >= 0.95);
		assertTrue(shoot(t -> 0, 30, null, 0).rate() >= 0.95);
	}
	
	@Test
	void fastEnough()
	{
		Result r = shoot(randomBackAndForth(7), 45, null, 4);
		// a tick is 50 ms; this has to be a tiny part of it
		assertTrue(r.msPerTick() < 1, "took " + r.msPerTick() + " ms/tick");
	}
}
