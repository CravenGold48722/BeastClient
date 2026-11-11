/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * The reward ("candy", user's rules in docs/TRAINING.MD), the same in the
 * simulator and in real fights. A streak of good hits, each within 5 s of
 * the last and none taken in between, pays more per hit: plain charged hits
 * 1, 2, 3, 4..., crits 1, 2, 3, 5, 8... (Fibonacci), sprint hits 1, 2, 4,
 * 8... A hit taken halves the candy and takes 5 more. 10 s away from the
 * fight (not near, no damage either way) costs 1 a second, being stuck in a
 * web 10 a second; trapping the opponent in your web gives 10 and 1 a second
 * it stays in. Every gain is scaled by health / 20 and rounded to the
 * nearest half. Turning the camera faster than 1800 degrees a second resets
 * it to 0. No MC classes.
 */
public final class Candy
{
	public static final int REGULAR = 0, CRIT = 1, SPRINT = 2;
	public static final int STREAK_TICKS = 100, IDLE_TICKS = 200;
	/** "Near the opponent": within this many blocks. */
	public static final double NEAR = 6;
	public static final double MAX_TURN_SPEED = 1800;
	private static final int NEVER = -1_000_000;
	
	private double total;
	private int streak;
	private int lastHitTick = NEVER, lastActiveTick;
	private int stuckTicks, trapTicks, idleTicks;
	private int lastTrapTick = NEVER;
	private boolean jumpPaid;
	/**
	 * A crit on a webbed opponent within this many ticks of webbing them
	 * pays WEB_CRIT_BONUS more; falling at a webbed opponent within reach
	 * (a jump for the crit) pays JUMP_BONUS once a jump (user, 2026-10-25).
	 */
	public static final int WEB_CRIT_TICKS = 40;
	public static final double WEB_CRIT_BONUS = 5, JUMP_BONUS = 1;
	/** "Within reach" for JUMP_BONUS. */
	public static final double JUMP_REACH = 3.5;
	
	/** A new fight. */
	public void reset(int tick)
	{
		total = 0;
		streak = stuckTicks = trapTicks = idleTicks = 0;
		lastHitTick = lastTrapTick = NEVER;
		jumpPaid = false;
		lastActiveTick = tick;
	}
	
	public double total()
	{
		return total;
	}
	
	public int streak()
	{
		return streak;
	}
	
	/**
	 * A good (charged) hit of the given type landed - on an opponent stuck
	 * in a web or not (see {@link #base(int, int, boolean)}).
	 */
	public void hit(int type, int tick, float health, boolean webbed)
	{
		if(tick - lastHitTick > STREAK_TICKS)
			streak = 0;
		streak++;
		lastHitTick = tick;
		lastActiveTick = tick;
		gain(base(type, streak, webbed), health);
		if(webbed && type == CRIT && tick - lastTrapTick <= WEB_CRIT_TICKS)
			gain(WEB_CRIT_BONUS, health);
	}
	
	/**
	 * What the n-th hit of a streak is worth before the health scaling. On
	 * a webbed opponent (user, 2026-10-25) crits are what to go for: they
	 * double every hit like sprint hits normally do, sprint hits only grow
	 * like plain ones (in a web the knockback is wasted anyway), and a plain
	 * hit is worth half.
	 */
	public static double base(int type, int n, boolean webbed)
	{
		n = Math.min(n, 60);
		if(webbed)
			return switch(type)
			{
				case CRIT -> Math.pow(2, n - 1);
				case SPRINT -> n;
				default -> n / 2.0;
			};
		return switch(type)
		{
			case CRIT ->
			{
				// 1, 2, 3, 5, 8, ...
				double x = 1, y = 2;
				for(int i = 1; i < n; i++)
				{
					double z = x + y;
					x = y;
					y = z;
				}
				yield x;
			}
			case SPRINT -> Math.pow(2, n - 1);
			default -> n;
		};
	}
	
	/** We took damage: halved, minus 5, and the streak ends. */
	public void hurt(int tick)
	{
		total = total / 2 - 5;
		streak = 0;
		lastHitTick = NEVER;
		lastActiveTick = tick;
	}
	
	/** We dealt damage that wasn't a good hit (still not idle). */
	public void active(int tick)
	{
		lastActiveTick = tick;
	}
	
	/** We just trapped the opponent in our web. */
	public void trapped(int tick, float health)
	{
		lastTrapTick = tick;
		gain(10, health);
	}
	
	/** The camera turned this many degrees per second. */
	public void turned(double degreesPerSecond)
	{
		if(degreesPerSecond > MAX_TURN_SPEED)
			total = 0;
	}
	
	/**
	 * Once a tick: how near the nearest opponent is, whether we're stuck in
	 * a web and whether an opponent is stuck in ours.
	 */
	public void tick(int tick, double nearestDist, boolean stuck,
		boolean opponentTrapped, boolean fallingAtWebbed, float health)
	{
		if(fallingAtWebbed)
		{
			if(!jumpPaid)
				gain(JUMP_BONUS, health);
			jumpPaid = true;
		}else
			jumpPaid = false;
		
		if(nearestDist <= NEAR)
			lastActiveTick = tick;
		
		if(stuck && ++stuckTicks >= 20)
		{
			stuckTicks = 0;
			total -= 10;
		}else if(!stuck)
			stuckTicks = 0;
		
		if(opponentTrapped && ++trapTicks >= 20)
		{
			trapTicks = 0;
			gain(1, health);
		}else if(!opponentTrapped)
			trapTicks = 0;
		
		if(tick - lastActiveTick > IDLE_TICKS)
		{
			if(++idleTicks >= 20)
			{
				idleTicks = 0;
				total -= 1;
			}
		}else
			idleTicks = 0;
	}
	
	private void gain(double candy, float health)
	{
		double scaled = candy * Math.max(0, Math.min(20, health)) / 20;
		total += Math.round(scaled * 2) / 2.0;
	}
	
	/**
	 * A candy change squashed for the in-fight adapter (a sprint combo's
	 * 2^n would swamp its step size).
	 */
	public static double squash(double delta)
	{
		return Math.signum(delta) * Math.log1p(Math.abs(delta));
	}
}
