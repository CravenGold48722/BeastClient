/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Works out where to aim a projectile so it meets a moving target, by
 * simulating the projectile the way the game does, tick by tick:
 * <ol>
 * <li>launch: {@code speed} along the look direction, plus the shooter's own
 * movement (vanilla's Projectile.shootFromRotation),</li>
 * <li>every tick: move, then slow down by {@code drag}, then fall by
 * {@code gravity} (AbstractArrow.tick).</li>
 * </ol>
 * Then it solves for the low-arc angle that reaches the target's position at
 * the moment the projectile gets there, and repeats with the target's
 * predicted position for that moment until the two agree.
 */
public enum BallisticSolver
{
	;
	
	/** How a projectile flies, all per tick. */
	public record Projectile(double speed, double gravity, double drag)
	{}
	
	/** Where the target will be, {@code ticks} from now. */
	public interface TargetPath
	{
		Vec3 at(double ticks);
	}
	
	/**
	 * @param reachable
	 *            false if the projectile can't get there at all; the angles
	 *            are then the closest it can get (its longest reach)
	 */
	public record Solution(float yaw, float pitch, double ticks,
		boolean reachable)
	{}
	
	/** Simulated flight up to a given horizontal distance. */
	private record Reach(Vec3 point, double ticks)
	{}
	
	/** Longest flight that is simulated, in ticks. */
	private static final int MAX_TICKS = 200;
	
	/**
	 * @param start
	 *            where the projectile spawns
	 * @param inherited
	 *            what the shooter's movement adds to the launch velocity,
	 *            per tick
	 */
	public static Solution solve(Vec3 start, Vec3 inherited, Projectile p,
		TargetPath target)
	{
		double ticks = target.at(0).distanceTo(start) / p.speed();
		Solution solution = null;
		
		// Aim at where the target will be when the projectile arrives - which
		// depends on the aim, so go back and forth until they agree.
		for(int i = 0; i < 10; i++)
		{
			solution = solveFixed(start, inherited, p, target.at(ticks));
			if(!solution.reachable())
				return solution;
			
			boolean settled = Math.abs(solution.ticks() - ticks) < 0.01;
			ticks = solution.ticks();
			if(settled)
				break;
		}
		
		return solution;
	}
	
	/** Aim that hits a target standing still at {@code goal}. */
	public static Solution solveFixed(Vec3 start, Vec3 inherited, Projectile p,
		Vec3 goal)
	{
		float yaw = yawTo(start, goal);
		Solution best = null;
		
		// The shooter's sideways movement pushes the projectile off the line
		// it was aimed along, so correct the yaw by the sideways miss and try
		// again.
		for(int i = 0; i < 6; i++)
		{
			best = solvePitch(start, inherited, p, goal, yaw);
			if(!best.reachable())
				return best;
			
			Reach reach = simulate(start, inherited, p, yaw, best.pitch(),
				horizontalDistance(start, goal), goal.y);
			if(reach == null)
				return best;
			
			float miss = Mth
				.wrapDegrees(yawTo(start, goal) - yawTo(start, reach.point()));
			if(Math.abs(miss) < 0.005)
				break;
			
			yaw = Mth.wrapDegrees(yaw + miss);
		}
		
		return best;
	}
	
	/**
	 * Finds the lowest-arc pitch at the given yaw that reaches the goal's
	 * height at the goal's horizontal distance.
	 */
	private static Solution solvePitch(Vec3 start, Vec3 inherited, Projectile p,
		Vec3 goal, float yaw)
	{
		double distance = horizontalDistance(start, goal);
		
		// Coarse scan upward from straight down for the first elevation that
		// gets high enough - that's the low arc. Too low to even get there
		// counts as below.
		double below = -89.9;
		double above = Double.NaN;
		double bestHeight = Double.NEGATIVE_INFINITY;
		double bestElevation = 45;
		Reach bestReach = null;
		
		for(double elevation = -89.9; elevation <= 89.9; elevation += 2.5)
		{
			Reach reach = simulate(start, inherited, p, yaw, (float)-elevation,
				distance, goal.y);
			double height =
				reach == null ? Double.NEGATIVE_INFINITY : reach.point().y;
			
			if(height > bestHeight)
			{
				bestHeight = height;
				bestElevation = elevation;
				bestReach = reach;
			}
			
			if(height >= goal.y)
			{
				above = elevation;
				break;
			}
			
			below = elevation;
		}
		
		// Can't get there: aim for the longest reach instead.
		if(Double.isNaN(above))
			return new Solution(yaw, (float)-bestElevation,
				bestReach == null ? 0 : bestReach.ticks(), false);
		
		// Narrow it down.
		Reach reach = null;
		for(int i = 0; i < 40; i++)
		{
			double mid = (below + above) / 2;
			reach = simulate(start, inherited, p, yaw, (float)-mid, distance,
				goal.y);
			if(reach != null && reach.point().y >= goal.y)
				above = mid;
			else
				below = mid;
		}
		
		reach =
			simulate(start, inherited, p, yaw, (float)-above, distance, goal.y);
		return new Solution(yaw, (float)-above,
			reach == null ? 0 : reach.ticks(), reach != null);
	}
	
	/**
	 * Flies the projectile until it has covered {@code distance}
	 * horizontally. Returns where it is at that moment and when, or null if it
	 * falls far below {@code goalY} (or runs out of time) first.
	 */
	private static Reach simulate(Vec3 start, Vec3 inherited, Projectile p,
		float yaw, float pitch, double distance, double goalY)
	{
		Vec3 velocity = launchVelocity(yaw, pitch, p.speed()).add(inherited);
		Vec3 pos = start;
		double covered = 0;
		
		for(int tick = 0; tick < MAX_TICKS; tick++)
		{
			Vec3 next = pos.add(velocity);
			double nextCovered = horizontalDistance(start, next);
			
			if(nextCovered >= distance)
			{
				double f = nextCovered == covered ? 1
					: (distance - covered) / (nextCovered - covered);
				return new Reach(pos.add(next.subtract(pos).scale(f)),
					tick + f);
			}
			
			// falling away below the goal - it isn't coming back up
			if(next.y < goalY - 64 && velocity.y < 0)
				return null;
			
			pos = next;
			covered = nextCovered;
			velocity = velocity.scale(p.drag()).add(0, -p.gravity(), 0);
		}
		
		return null;
	}
	
	/**
	 * The launch direction scaled to {@code speed}, the way
	 * Projectile.shootFromRotation() computes it (without the random
	 * inaccuracy, which can't be aimed for).
	 */
	public static Vec3 launchVelocity(float yaw, float pitch, double speed)
	{
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		double x = -Math.sin(yawRad) * Math.cos(pitchRad);
		double y = -Math.sin(pitchRad);
		double z = Math.cos(yawRad) * Math.cos(pitchRad);
		return new Vec3(x, y, z).normalize().scale(speed);
	}
	
	private static float yawTo(Vec3 from, Vec3 to)
	{
		return (float)Mth.wrapDegrees(
			Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90);
	}
	
	private static double horizontalDistance(Vec3 a, Vec3 b)
	{
		double dx = b.x - a.x;
		double dz = b.z - a.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
