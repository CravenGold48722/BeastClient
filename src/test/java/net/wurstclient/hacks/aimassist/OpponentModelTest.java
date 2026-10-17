/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * How fast AimAssist's opponent model adapts (user, 2026-10-17: "as fast as
 * possible and as smart as possible"), against simulated opponents: each
 * exchange is a hit landed or taken, with the odds depending on the style
 * in play.
 */
public class OpponentModelTest
{
	/**
	 * Plays exchanges against an opponent where the given style wins its
	 * exchanges with these odds; returns the style played per exchange.
	 */
	private static int[] play(OpponentModel model, double sprintWins,
		double critWins, int exchanges, Random random)
	{
		int[] played = new int[exchanges];
		for(int i = 0; i < exchanges; i++)
		{
			int style = model.style();
			played[i] = style;
			double wins =
				style == OpponentModel.CRIT_TRADER ? critWins : sprintWins;
			if(random.nextDouble() < wins)
				model.ourHitLanded(false);
			else
				model.hitTaken(2.8);
		}
		return played;
	}
	
	/** Share of these exchanges played with the style. */
	private static double share(int[] played, int from, int to, int style)
	{
		int n = 0;
		for(int i = from; i < to; i++)
			if(played[i] == style)
				n++;
		return n / (double)(to - from);
	}
	
	/**
	 * Crit trading is clearly better here (70% vs 30%): after a dozen
	 * exchanges - one fight's first ~10 seconds - it plays mostly that.
	 */
	@Test
	public void findsTheBetterStyleQuickly()
	{
		double total = 0;
		for(int seed = 1; seed <= 50; seed++)
		{
			int[] played =
				play(new OpponentModel(), 0.3, 0.7, 40, new Random(seed));
			total += share(played, 12, 40, OpponentModel.CRIT_TRADER);
		}
		double avg = total / 50;
		assertTrue(avg > 0.75,
			"after 12 exchanges it played crit trader only " + avg);
	}
	
	/**
	 * The opponent adapts: the better style turns around after 30 exchanges.
	 * Within about 15 more it follows.
	 */
	@Test
	public void followsAnOpponentThatChanges()
	{
		double total = 0;
		for(int seed = 1; seed <= 50; seed++)
		{
			OpponentModel model = new OpponentModel();
			Random random = new Random(seed);
			play(model, 0.3, 0.7, 30, random);
			int[] after = play(model, 0.7, 0.3, 40, random);
			total += share(after, 15, 40, OpponentModel.SPRINT_COMBO);
		}
		double avg = total / 50;
		assertTrue(avg > 0.65, "after the turn it played the new better"
			+ " style only " + avg + " of the time");
	}
	
	/** Equally good styles: no getting stuck flipping every exchange. */
	@Test
	public void noFlippingBetweenEqualStyles()
	{
		OpponentModel model = new OpponentModel();
		int[] played = play(model, 0.5, 0.5, 60, new Random(7));
		int switches = 0;
		for(int i = 1; i < played.length; i++)
			if(played[i] != played[i - 1])
				switches++;
		assertTrue(switches <= 20, switches + " switches in 60 exchanges");
	}
	
	/**
	 * Slightly better beats switching back and forth: every switch restarts
	 * the combo (a VexBot fight had 14 in 600 ticks).
	 */
	@Test
	public void fewSwitchesWhenOneIsSlightlyBetter()
	{
		int total = 0;
		for(int seed = 1; seed <= 50; seed++)
		{
			int[] played =
				play(new OpponentModel(), 0.55, 0.45, 60, new Random(seed));
			for(int i = 1; i < played.length; i++)
				if(played[i] != played[i - 1])
					total++;
		}
		double avg = total / 50.0;
		assertTrue(avg <= 8, avg + " switches in 60 exchanges on average");
	}
	
	/**
	 * What it showed of itself leans toward crit trading (it wins races,
	 * answers our hits and jump-resets - VexBot), but the sprint combo
	 * scores better: the results decide, not the lean.
	 */
	@Test
	public void resultsOutweighWhatItShowed()
	{
		double total = 0;
		for(int seed = 1; seed <= 50; seed++)
		{
			OpponentModel model = new OpponentModel();
			for(int i = 0; i < 3; i++)
			{
				model.raceObserved(true);
				model.counterObserved(true);
				model.knockbackObserved(true);
			}
			int[] played = play(model, 0.6, 0.4, 40, new Random(seed));
			total += share(played, 12, 40, OpponentModel.SPRINT_COMBO);
		}
		double avg = total / 50;
		assertTrue(avg > 0.7, "played the better sprint combo only " + avg);
	}
	
	/** Three answered hits in a row: the rate knows. */
	@Test
	public void learnsCountersInThreeHits()
	{
		OpponentModel model = new OpponentModel();
		for(int i = 0; i < 3; i++)
			model.counterObserved(true);
		assertTrue(model.counterRate() > 0.85, "" + model.counterRate());
	}
	
	/** Lost races widen the margin it waits outside their reach. */
	@Test
	public void lostRacesWidenTheMargin()
	{
		OpponentModel model = new OpponentModel();
		double before = model.reachMargin();
		for(int i = 0; i < 3; i++)
			model.raceObserved(true);
		assertTrue(model.reachMargin() > before + 0.2,
			before + " -> " + model.reachMargin());
	}
}
