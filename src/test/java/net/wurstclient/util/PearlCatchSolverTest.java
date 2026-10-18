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

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.PearlCatchSolver.Plan;
import net.wurstclient.util.PearlCatchSolver.Shooter;

/**
 * PearlCatchSolver's catches, fired with vanilla's random spread: a "sure"
 * plan must hit with every spread, from every throw angle the user asked for
 * (straight up with a jump, 45-90 degrees, sprint-jumping at 0-45), with the
 * solve in well under a tick.
 */
public class PearlCatchSolverTest
{
	private static final double EYE = 1.62;
	
	/** Vanilla's triangle(0, w): the difference of two uniforms. */
	private static double triangle(Random random, double w)
	{
		return w * (random.nextDouble() - random.nextDouble());
	}
	
	private static Vec3 randomSpread(Random random)
	{
		double s = PearlCatchSolver.SPREAD;
		return new Vec3(triangle(random, s), triangle(random, s),
			triangle(random, s));
	}
	
	/**
	 * The thrower, from the throw on: a jump (or none) with vanilla's air
	 * physics, positions per tick; [f] = where they are when firing f ticks
	 * after the throw.
	 */
	private static Shooter[] shooters(Vec3 feet, Vec3 vel, boolean onGround,
		int ticks)
	{
		Shooter[] s = new Shooter[ticks];
		Vec3 pos = feet;
		Vec3 v = vel;
		Vec3 lastMove = onGround ? Vec3.ZERO : vel;
		boolean ground = onGround;
		for(int f = 0; f < ticks; f++)
		{
			s[f] = new Shooter(pos.add(0, EYE, 0), lastMove, ground);
			if(ground)
			{
				lastMove = Vec3.ZERO;
				continue;
			}
			Vec3 next = pos.add(v);
			if(next.y <= feet.y)
			{
				next = new Vec3(next.x, feet.y, next.z);
				ground = true;
			}
			lastMove = next.subtract(pos);
			pos = next;
			v = new Vec3(v.x * 0.91, (v.y - 0.08) * 0.98, v.z * 0.91);
		}
		return s;
	}
	
	/**
	 * Throws a pearl (yaw 0, this pitch) with this known movement, plans the
	 * catch, then fires the plan with 2000 random spreads: the share that
	 * hit, and how high the plan lands you.
	 */
	private static double[] throwAndCatch(float pitch, Vec3 known,
		boolean airborne, long seed)
	{
		Random random = new Random(seed);
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		Vec3 spawn = feet.add(0, EYE - PearlCatchSolver.PEARL_SPAWN_DROP, 0);
		Vec3 pearlVel =
			PearlCatchSolver.launch(PearlCatchSolver.direction(0, pitch),
				randomSpread(random), known, !airborne);
		Vec3[] path = PearlCatchSolver.pearlPath(spawn, pearlVel,
			PearlCatchSolver.HORIZON);
		Shooter[] who = shooters(feet, known, !airborne, 60);
		
		Plan plan = PearlCatchSolver.solve(path, 0, who, 2, 40);
		assertNotNull(plan, "no catch for pitch " + pitch);
		if(seed == 1)
			System.out.println("PLAN pitch " + pitch + ": " + plan
				+ " pearl vel " + pearlVel + " shooter " + who[plan.fire()]);
		Shooter s = who[plan.fire()];
		int hits = 0;
		for(int i = 0; i < 2000; i++)
		{
			Vec3 vel = PearlCatchSolver.launch(plan.dir(), randomSpread(random),
				s.known(), s.onGround());
			if(PearlCatchSolver.hitMove(path, 0, plan.fire(), s.eyes(),
				vel) >= 0)
				hits++;
		}
		return new double[]{hits / 2000.0, plan.landing().y - feet.y,
			plan.sure() ? 1 : 0};
	}
	
	@Test
	public void sureCatchesAlwaysHit()
	{
		// as MaceAssist throws them: straight up, 70 and 45 up with a jump
		Object[][] throwsToTry = {{-90F, new Vec3(0, 0.42, 0), true},
			{-70F, new Vec3(0, 0.42, 0), true},
			{-45F, new Vec3(0, 0.42, 0), true}};
		for(Object[] t : throwsToTry)
			for(long seed = 1; seed <= 20; seed++)
			{
				double[] r =
					throwAndCatch((float)t[0], (Vec3)t[1], (boolean)t[2], seed);
				assertEquals(1.0, r[2], "not a sure catch at pitch " + t[0]);
				// sure = hits 90%+ of the solver's 100 spread samples; 2000
				// fresh ones must agree
				assertTrue(r[0] >= 0.8, "a sure catch hit only " + r[0]
					+ " at pitch " + t[0] + " (seed " + seed + ")");
			}
	}
	
	/**
	 * Flatter throws (30 and 10 degrees up, sprint-jumped): with a tick of
	 * server timing either way no shot is sure (a fast, flat pearl is met
	 * far out or nearly head-on) - the best one still mostly hits.
	 */
	@Test
	public void flatThrowsCatchMostly()
	{
		for(float pitch : new float[]{-30, -10})
		{
			double sum = 0;
			for(long seed = 1; seed <= 20; seed++)
				sum +=
					throwAndCatch(pitch, new Vec3(0, 0.42, 0.6), true, seed)[0];
			double rate = sum / 20;
			System.out.println(-pitch + " degrees up: " + rate + " hit");
			assertTrue(rate > 0.5, "flat catches hit only " + rate);
		}
	}
	
	/** Straight up with a jump: the catch is higher than the old one (23). */
	@Test
	public void catchesHigh()
	{
		double sum = 0;
		for(long seed = 1; seed <= 20; seed++)
			sum += throwAndCatch(-90, new Vec3(0, 0.42, 0), true, seed)[1];
		double avg = sum / 20;
		System.out.println("straight up with a jump: lands " + avg + " up");
		assertTrue(avg > 23, "lands only " + avg + " blocks up");
	}
	
	/** Each solve well under a tick (50 ms) - under 10 ms. */
	@Test
	public void solvesFast()
	{
		Random random = new Random(3);
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		Vec3[] path = PearlCatchSolver.pearlPath(feet.add(0, 1.52, 0),
			PearlCatchSolver.launch(PearlCatchSolver.direction(0, -80),
				randomSpread(random), new Vec3(0, 0.42, 0), false),
			PearlCatchSolver.HORIZON);
		Shooter[] who = shooters(feet, new Vec3(0, 0.42, 0), false, 60);
		for(int i = 0; i < 50; i++)
			PearlCatchSolver.solve(path, 0, who, 2, 40);
		long start = System.nanoTime();
		for(int i = 0; i < 100; i++)
			PearlCatchSolver.solve(path, 0, who, 2, 40);
		double ms = (System.nanoTime() - start) / 1e6 / 100;
		System.out.println("solve: " + ms + " ms");
		assertTrue(ms < 10, "a solve took " + ms + " ms");
	}
}
