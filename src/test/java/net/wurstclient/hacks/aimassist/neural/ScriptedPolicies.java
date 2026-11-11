/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.SplittableRandom;
import java.util.function.Supplier;

import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/**
 * Hand-written sword PvP players for the trainer: the teacher the network
 * first learns the basics from (Beast's own Sprint combo) and sparring
 * partners that each fight a known way, so self-play doesn't drift into
 * habits that only beat itself. They see the same observation the network
 * does.
 */
public final class ScriptedPolicies
{
	private ScriptedPolicies()
	{}
	
	public static final String[] NAMES =
		{"SprintCombo", "CritTrader", "Aggressor", "Spammer", "Counter"};
	
	@SuppressWarnings("unchecked")
	public static final Supplier<Policy>[] ALL =
		new Supplier[]{SprintCombo::new, CritTrader::new, Aggressor::new,
			Spammer::new, Counter::new};
	
	/**
	 * A scripted player with its own habits, new every fight (user,
	 * 2026-10-27: it has to adapt as well as ECHO does - against five
	 * fighters that always play the same way, reading the opponent paid
	 * off too little to be learned): where it likes to stand, which way it
	 * strafes, how jumpy it is, how long it waits with a ready hit, whether
	 * it sneaks while charging and how far it backs off after a hit. Only
	 * in training; the yardstick plays the plain ones.
	 */
	public static final class Styled implements Policy
	{
		private final Policy inner;
		private SplittableRandom random;
		private double spacing, strafeBias, jumpiness, sneakCharging;
		private int hitDelay, backoff, preferred, waiting, backTicks;
		
		public Styled(Policy inner)
		{
			this.inner = inner;
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			inner.reset(random);
			spacing = (random.nextDouble() - 0.5) * 1.2;
			strafeBias = random.nextDouble() * 0.6;
			preferred = random.nextBoolean() ? 1 : -1;
			jumpiness =
				random.nextDouble() < 0.5 ? 0 : random.nextDouble() * 0.15;
			sneakCharging =
				random.nextDouble() < 0.7 ? 0 : random.nextDouble() * 0.8;
			hitDelay = random.nextInt(4);
			backoff = random.nextInt(5);
			waiting = backTicks = 0;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			// it sees the distances shifted: so it keeps its own spacing
			double dist = o.dist, reach = o.theirReach;
			o.dist = Math.max(0, dist - spacing);
			o.theirReach = Math.max(0, reach - spacing);
			inner.act(me, o, out);
			o.dist = dist;
			o.theirReach = reach;
			
			if(random.nextDouble() < strafeBias)
				out.strafe = preferred;
			if(o.myGround && dist < 4 && random.nextDouble() < jumpiness)
				out.jump = true;
			if(o.myChargeTicks > 3 && random.nextDouble() < sneakCharging)
				out.sneak = true;
			if(backTicks > 0)
			{
				backTicks--;
				out.forward = -1;
				out.sprint = false;
			}
			// a ready hit held for a few ticks (it still has to land)
			if(out.attack)
			{
				if(waiting < hitDelay)
				{
					waiting++;
					out.attack = false;
				}else
				{
					waiting = 0;
					backTicks = backoff;
				}
			}else if(!goodHit(o))
				waiting = 0;
		}
	}
	
	/**
	 * A styled scripted player that changes its game plan partway through
	 * {@link #SHARE} of the fights: another fighter with other habits takes
	 * over after 5-25 seconds, half the time going back to the first one
	 * later (user, 2026-11-02: "a real person can change strategies
	 * mid-match" - the net has to notice it in its history and answer it,
	 * not keep playing what worked a minute ago).
	 */
	public static final class Switching implements Policy
	{
		static final double SHARE = 0.4;
		private final int first;
		private final Styled a;
		private Styled b;
		private Policy current;
		private int tick, switchAt, backAt;
		
		public Switching(int first)
		{
			this.first = first;
			a = new Styled(ALL[first].get());
		}
		
		@Override
		public void reset(SplittableRandom random)
		{
			a.reset(random);
			current = a;
			tick = 0;
			switchAt = backAt = Integer.MAX_VALUE;
			if(random.nextDouble() >= SHARE)
				return;
			int other =
				(first + 1 + random.nextInt(ALL.length - 1)) % ALL.length;
			b = new Styled(ALL[other].get());
			b.reset(random);
			switchAt = 100 + random.nextInt(400);
			if(random.nextBoolean())
				backAt = switchAt + 100 + random.nextInt(300);
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			tick++;
			if(tick == switchAt)
				current = b;
			else if(tick == backAt)
				current = a;
			current.act(me, o, out);
		}
		
		/** The tick the other fighter takes over (MAX_VALUE: never). */
		int switchAt()
		{
			return switchAt;
		}
	}
	
	/** Blocks/tick a sprint covers once going. */
	private static final double SPRINT_STEP = 0.28;
	
	private static int approachTicks(double dist)
	{
		return dist <= PvpSim.REACH ? 0
			: (int)Math.ceil((dist - PvpSim.REACH + 0.1) / SPRINT_STEP);
	}
	
	private static boolean goodHit(NeuralObservation o)
	{
		return o.canHit && o.myChargeTicks == 0 && (o.mySprint || o.critReady);
	}
	
	/**
	 * The items, the way a player uses them: a golden apple when low and
	 * well out of reach (kept eating unless they close in), a healing potion
	 * at your feet when low and they're too far to share it, a cobweb at
	 * their feet when they're on the ground close by and your hit is a
	 * while off, the shield while yours charges and theirs is ready.
	 */
	static void useItems(NeuralObservation o, NeuralAction out)
	{
		if(o.myEating > 0 ? o.dist > 3.5 : o.myHealth < 0.5 && o.gapplesLeft > 0
			&& o.dist > 5 && !o.regenerating)
		{
			out.eat = true;
			out.attack = false;
			out.sprint = false;
			out.forward = -1;
			return;
		}
		// buffs once they run low (away from the fight)
		if(o.potsLeft > 0 && o.myStrength < 0.1 && o.dist > 4)
		{
			out.pot = true;
			out.attack = false;
			return;
		}
		if(o.websLeft > 0 && !o.opInWeb && o.opGround && o.dist < 4
			&& o.myChargeTicks >= 5)
		{
			out.web = true;
			return;
		}
		if(o.hasShield && o.myChargeTicks >= 7 && o.opChargeTicks <= 3
			&& o.theirReach < PvpSim.REACH + 0.5)
			out.shield = true;
	}
	
	/**
	 * Beast's AimAssist Auto combo (Sprint combo), from the combo guides:
	 * hold W, hit, let go; S-tap when they come at you, W-tap when they back
	 * off; stay out of their reach while charging and set off just in time
	 * to arrive charged; jump reset when hit (S-tap when too close); in the
	 * air W while rising, let go while falling for the crit.
	 */
	public static final class SprintCombo implements Policy
	{
		private SplittableRandom random;
		private int resetTicks;
		private boolean resetS;
		private int strafe, strafeTicks;
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			resetTicks = 0;
			strafe = 0;
			strafeTicks = 0;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			out.clear();
			if(--strafeTicks <= 0)
			{
				// strafing 90% of the time, a new side every 8-20 ticks
				strafe =
					random.nextInt(10) == 0 ? 0 : random.nextBoolean() ? 1 : -1;
				strafeTicks = 8 + random.nextInt(13);
			}
			out.strafe = strafe;
			
			if(o.myHurt == 10 && o.myGround)
			{
				if(o.dist >= 2)
				{
					out.forward = 1;
					out.sprint = true;
					out.jump = true;
					resetTicks = 0;
					return;
				}
				resetTicks = 2;
				resetS = true;
			}
			
			if(resetTicks > 0)
			{
				resetTicks--;
				out.forward = resetS ? -1 : 0;
			}else if(!o.myGround)
			{
				// rising: keep going; falling: let go for the crit
				if(o.myVy > 0)
				{
					out.forward = 1;
					out.sprint = true;
				}
			}else
			{
				int lead = 3 + approachTicks(o.dist);
				if(o.myChargeTicks <= lead)
				{
					out.forward = 1;
					out.sprint = true;
				}else if(o.theirReach < PvpSim.REACH + 1 && o.opAlong >= -0.05)
					out.forward = -1;
			}
			
			out.attack = goodHit(o);
			if(out.attack)
			{
				resetTicks = 2;
				resetS = o.dist < 2.3 || o.opAlong > 0.05;
			}
			useItems(o, out);
		}
	}
	
	/**
	 * The ECHO PvP AI's style: at the edge of reach, strafing, jumps so it
	 * falls as the hit charges (a crit), hits from ~3 blocks, then backs off
	 * with a sneak.
	 */
	public static final class CritTrader implements Policy
	{
		private SplittableRandom random;
		private int strafe = 1, strafeTicks, backTicks;
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			strafe = random.nextBoolean() ? 1 : -1;
			strafeTicks = 0;
			backTicks = 0;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			out.clear();
			if(--strafeTicks <= 0)
			{
				strafe = -strafe;
				strafeTicks = 15 + random.nextInt(11);
			}
			out.strafe = strafe;
			
			if(backTicks > 0)
			{
				backTicks--;
				out.forward = -1;
				out.sneak = true;
				return;
			}
			
			if(o.myChargeTicks > 10)
			{
				// charging: at the edge of their reach
				if(o.dist > 3.8)
				{
					out.forward = 1;
					out.sprint = true;
				}else if(o.dist < 3.3)
					out.forward = -1;
			}else
			{
				// a jump in, so it falls (a crit) as the hit charges - W let
				// go on the way down, or the sprint (no crit) goes on
				out.forward = o.myGround || o.myVy > 0 ? 1 : 0;
				out.sprint = o.myGround && o.dist > 3.5;
				if(o.myGround && o.dist < 4)
					out.jump = true;
			}
			
			out.attack = goodHit(o);
			if(out.attack)
				backTicks = 3;
		}
	}
	
	/**
	 * Like VexBot: always pressing forward and sprinting, jump-resets,
	 * jumps for crits, strafes in the combo and holds a ready hit now and
	 * then until the other one swings (hit select).
	 */
	public static final class Aggressor implements Policy
	{
		private SplittableRandom random;
		private int strafe, strafeTicks, held;
		private boolean selecting;
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			strafe = 0;
			strafeTicks = 0;
			held = 0;
			selecting = false;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			out.clear();
			if(--strafeTicks <= 0)
			{
				strafe = random.nextInt(3) - 1;
				strafeTicks = 8 + random.nextInt(13);
			}
			out.strafe = strafe;
			out.forward = 1;
			out.sprint = o.myGround || o.myVy > 0;
			
			if(o.myGround && (o.myHurt == 10 || o.myChargeTicks <= 4
				&& o.dist < 3.5 && random.nextInt(10) < 3))
				out.jump = true;
			
			useItems(o, out);
			if(!goodHit(o) || out.eat || out.pot)
			{
				held = 0;
				selecting = false;
				return;
			}
			if(held == 0)
				selecting = o.opChargeTicks <= 2 && random.nextInt(100) < 45;
			if(selecting && held < 3 && o.opHurt == 0)
			{
				held++;
				return;
			}
			out.attack = true;
			held = 0;
		}
	}
	
	/** Holds W and clicks fast - a weak player to punish. */
	public static final class Spammer implements Policy
	{
		private SplittableRandom random;
		private int strafe, strafeTicks;
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			strafeTicks = 0;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			out.clear();
			if(--strafeTicks <= 0)
			{
				strafe = random.nextInt(3) - 1;
				strafeTicks = 5 + random.nextInt(20);
			}
			out.strafe = strafe;
			out.forward = 1;
			out.sprint = true;
			out.attack = o.canHit && o.myChargeTicks <= 8;
		}
	}
	
	/**
	 * Waits just outside reach and punishes whoever walks in, backing off
	 * after each hit.
	 */
	public static final class Counter implements Policy
	{
		private SplittableRandom random;
		private int strafe, strafeTicks, backTicks;
		
		@Override
		public void reset(SplittableRandom random)
		{
			this.random = random;
			strafeTicks = 0;
			backTicks = 0;
		}
		
		@Override
		public void act(SimFighter me, NeuralObservation o, NeuralAction out)
		{
			out.clear();
			if(--strafeTicks <= 0)
			{
				strafe = random.nextInt(3) - 1;
				strafeTicks = 10 + random.nextInt(15);
			}
			out.strafe = strafe;
			
			if(backTicks > 0)
			{
				backTicks--;
				out.forward = -1;
			}else if(o.dist > 3.6)
			{
				out.forward = 1;
				out.sprint = true;
			}else if(o.dist < 3.1 && o.myChargeTicks > 0)
				out.forward = -1;
			else if(o.myChargeTicks == 0)
			{
				out.forward = 1;
				out.sprint = true;
			}
			
			out.attack = goodHit(o);
			if(out.attack)
				backTicks = 4;
			useItems(o, out);
		}
	}
}
