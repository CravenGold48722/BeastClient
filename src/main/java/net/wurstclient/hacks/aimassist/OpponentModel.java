/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist;

/**
 * What AimAssist has learned about one opponent (user, 2026-10-17: "make it
 * adapt as fast as possible and as smart as possible"). Updated on every
 * hit either way, so two or three of them already move it:
 * <ul>
 * <li>how it fights back - how often it answers our hit within a few ticks,
 * shrugs our knockback off (jump resets), wins the race when both hits are
 * charged, and from how far its hits land;</li>
 * <li>which combat style does better against it - a discounted UCB1 bandit
 * over the styles, scored by hits landed (crits 1.5) minus hits taken, with
 * old exchanges fading so an opponent that adapts too is followed.</li>
 * </ul>
 * Kept per opponent for the session, so a rematch starts where the last
 * fight left off. No Minecraft classes: OpponentModelTest drives it.
 */
public final class OpponentModel
{
	/** Styles, by index: AimAssist's Sprint combo and Crit trader. */
	public static final int SPRINT_COMBO = 0, CRIT_TRADER = 1;
	
	/**
	 * Weight of the newest observation in the running rates: 3 of the same
	 * take a rate from 0.5 to ~0.86.
	 */
	private static final double ALPHA = 0.35;
	
	/** How much every older exchange counts once a new one is in. */
	private final double discount;
	
	/** UCB exploration: how much an under-tried style gets the benefit. */
	private final double explore;
	
	/**
	 * How much better the other style has to look to switch: every switch
	 * restarts the combo (14 switches in a 600-tick fight without it).
	 */
	private final double switchMargin;
	
	/** Exchanges a style plays at least before the next choice. */
	private static final int MIN_DWELL = 3;
	
	private double counterRate = 0.5;
	private double knockbackShrugRate = 0.5;
	private double raceLossRate = 0.5;
	private double theirReach = 3;
	private double maxReach = 3;
	private double diveRate = 0.5;
	private int counterSamples, shrugSamples, raceSamples, reachSamples,
		diveSamples;
	
	private final double[] weight = new double[2];
	private final double[] sum = new double[2];
	private int style = SPRINT_COMBO;
	private int dwell;
	
	public OpponentModel()
	{
		this(0.95, 0.35, 0.2);
	}
	
	OpponentModel(double discount, double explore, double switchMargin)
	{
		this.discount = discount;
		this.explore = explore;
		this.switchMargin = switchMargin;
	}
	
	// ── what it does ──
	
	/** Our hit was answered within a few ticks (true) or not (false). */
	public void counterObserved(boolean countered)
	{
		counterRate = ema(counterRate, countered ? 1 : 0, counterSamples++);
	}
	
	/** Our knockback barely moved it (a jump reset, resistance) or did. */
	public void knockbackObserved(boolean shrugged)
	{
		knockbackShrugRate =
			ema(knockbackShrugRate, shrugged ? 1 : 0, shrugSamples++);
	}
	
	/** Both hits charged and in reach: it struck first (true) or we did. */
	public void raceObserved(boolean theyWon)
	{
		raceLossRate = ema(raceLossRate, theyWon ? 1 : 0, raceSamples++);
	}
	
	/** One of our hits landed (crits count 1.5: half again the damage). */
	public void ourHitLanded(boolean crit)
	{
		ourHitLanded(crit, style);
	}
	
	/**
	 * One of our hits landed while playing this style - a fixed style the
	 * user picked counts for that style too.
	 */
	public void ourHitLanded(boolean crit, int played)
	{
		score(crit ? 1.5 : 1, played);
	}
	
	/** It hit us, from this far (its eyes to our hitbox). */
	public void hitTaken(double reach)
	{
		hitTaken(reach, style);
	}
	
	/** It hit us while we played this style. */
	public void hitTaken(double reach, int played)
	{
		hitTaken(reach, played, false);
	}
	
	/**
	 * It hit us while we played this style, from this far, diving onto us
	 * (falling from above - a crit) or not.
	 */
	public void hitTaken(double reach, int played, boolean dive)
	{
		theirReach = ema(theirReach, reach, reachSamples++);
		// the longest it has hit from, slowly forgotten: a server-side bot
		// (VexBot) crits from up to 3.5, beyond vanilla's 3
		maxReach = Math.max(reach, maxReach - 0.01);
		diveRate = ema(diveRate, dive ? 1 : 0, diveSamples++);
		score(-1, played);
	}
	
	/** The longest reach it has hit us from lately (vanilla's 3 at first). */
	public double maxReach()
	{
		return maxReach;
	}
	
	/** How many of its hits are crit dives onto us. */
	public double diveRate()
	{
		return diveRate;
	}
	
	private static double ema(double old, double value, int samples)
	{
		// the first sample replaces the 0.5 guess outright
		return samples == 0 ? value : old + ALPHA * (value - old);
	}
	
	// ── what to do about it ──
	
	/** The style to play now. */
	public int style()
	{
		return style;
	}
	
	/**
	 * How far beyond its reach to wait while our hit charges: wider the more
	 * races it wins (0.6 to 1.4 blocks).
	 */
	public double reachMargin()
	{
		return 0.6 + 0.8 * raceLossRate;
	}
	
	public double counterRate()
	{
		return counterRate;
	}
	
	public double knockbackShrugRate()
	{
		return knockbackShrugRate;
	}
	
	public double raceLossRate()
	{
		return raceLossRate;
	}
	
	public double theirReach()
	{
		return theirReach;
	}
	
	/** The style's average score per exchange, recent ones counting most. */
	public double mean(int s)
	{
		return weight[s] == 0 ? 0 : sum[s] / weight[s];
	}
	
	private void score(double value, int played)
	{
		// older exchanges fade - the opponent may have changed since
		for(int s = 0; s < 2; s++)
		{
			weight[s] *= discount;
			sum[s] *= discount;
		}
		weight[played] += 1;
		sum[played] += value;
		
		if(++dwell >= MIN_DWELL)
			choose();
	}
	
	private void choose()
	{
		int other = 1 - style;
		if(value(other) > value(style) + switchMargin)
		{
			style = other;
			dwell = 0;
		}
	}
	
	/**
	 * The style's discounted UCB value: its results so far (shrunk toward
	 * an even exchange while few) plus the benefit of the doubt for a style
	 * little tried lately. Only results count - a guess from what the
	 * opponent showed of itself (races won, counters, jump resets -> crit
	 * trading) pointed the wrong way against VexBot, which beats Crit trader
	 * 28:17 and loses to the sprint combo.
	 */
	private double value(int s)
	{
		double total = weight[0] + weight[1] + 1;
		return sum[s] / (weight[s] + 0.5)
			+ explore * Math.sqrt(Math.log(total) / (weight[s] + 0.25));
	}
}
