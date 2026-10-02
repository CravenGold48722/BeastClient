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

import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.BallisticSolver.Projectile;
import net.wurstclient.util.BallisticSolver.Solution;
import net.wurstclient.util.BallisticSolver.TargetPath;

/**
 * Fires the solved shot through an independent copy of vanilla's projectile
 * physics and checks that it actually meets the target.
 */
class BallisticSolverTest
{
	/** Full-charge bow arrow. */
	private static final Projectile ARROW = new Projectile(3, 0.05, 0.99);
	private static final Projectile WEAK_ARROW =
		new Projectile(0.9, 0.05, 0.99);
	private static final Projectile TRIDENT = new Projectile(2.5, 0.05, 0.99);
	/** Crossbow firework, shot at an angle: flies straight. */
	private static final Projectile FIREWORK = new Projectile(1.6, 0, 1);
	
	private static final Vec3 START = new Vec3(0.5, 65.52, 0.5);
	
	/** Vanilla: launch, then each tick move, slow down, fall. */
	private static Vec3 flyFor(Vec3 inherited, Projectile p, float yaw,
		float pitch, double ticks)
	{
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		Vec3 velocity = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad))
				.normalize().scale(p.speed()).add(inherited);
		
		Vec3 pos = START;
		int whole = (int)ticks;
		for(int i = 0; i < whole; i++)
		{
			pos = pos.add(velocity);
			velocity = velocity.scale(p.drag()).add(0, -p.gravity(), 0);
		}
		return pos.add(velocity.scale(ticks - whole));
	}
	
	private static double miss(Vec3 inherited, Projectile p, TargetPath path)
	{
		Solution s = BallisticSolver.solve(START, inherited, p, path);
		assertTrue(s.reachable(), "should be reachable");
		Vec3 projectile = flyFor(inherited, p, s.yaw(), s.pitch(), s.ticks());
		return projectile.distanceTo(path.at(s.ticks()));
	}
	
	@Test
	void testStandingTargets()
	{
		for(double distance : new double[]{4, 15, 30, 60})
			for(double height : new double[]{-6, 0, 6})
			{
				Vec3 goal = START.add(distance * 0.6, height, distance * 0.8);
				double miss = miss(Vec3.ZERO, ARROW, t -> goal);
				assertTrue(miss < 0.1, distance + " blocks away, " + height
					+ " up: missed by " + miss);
			}
	}
	
	@Test
	void testDragIsAccountedFor()
	{
		// Far enough that ignoring drag (the old formula) misses badly.
		Vec3 goal = START.add(0, 0, 80);
		assertTrue(miss(Vec3.ZERO, ARROW, t -> goal) < 0.1);
		
		// A weakly drawn bow drops a lot over a short distance.
		Vec3 near = START.add(0, 0, 12);
		assertTrue(miss(Vec3.ZERO, WEAK_ARROW, t -> near) < 0.1);
	}
	
	@Test
	void testMovingTarget()
	{
		// strafing sideways at sprint speed, 30 blocks out
		Vec3 from = START.add(0, 0, 30);
		Vec3 velocity = new Vec3(0.28, 0, 0);
		double miss = miss(Vec3.ZERO, ARROW, t -> from.add(velocity.scale(t)));
		assertTrue(miss < 0.1, "missed a strafing target by " + miss);
		
		// running toward you
		Vec3 toward = new Vec3(0, 0, -0.28);
		miss = miss(Vec3.ZERO, ARROW, t -> from.add(toward.scale(t)));
		assertTrue(miss < 0.1, "missed an approaching target by " + miss);
	}
	
	@Test
	void testShooterMovement()
	{
		// you strafe while shooting: your movement is added to the arrow
		Vec3 inherited = new Vec3(0.2, 0, 0.1);
		Vec3 goal = START.add(10, 2, 35);
		double miss = miss(inherited, ARROW, t -> goal);
		assertTrue(miss < 0.1, "missed while moving by " + miss);
	}
	
	@Test
	void testOtherProjectiles()
	{
		Vec3 goal = START.add(-20, 3, 25);
		assertTrue(miss(Vec3.ZERO, TRIDENT, t -> goal) < 0.1, "trident");
		assertTrue(miss(Vec3.ZERO, FIREWORK, t -> goal) < 0.1, "firework");
	}
	
	@Test
	void testOutOfRange()
	{
		Vec3 goal = START.add(0, 0, 400);
		Solution s = BallisticSolver.solve(START, Vec3.ZERO, ARROW, t -> goal);
		assertFalse(s.reachable());
		// best effort: somewhere around the longest-reach angle
		assertTrue(s.pitch() < -20 && s.pitch() > -60, "pitch " + s.pitch());
	}
}
