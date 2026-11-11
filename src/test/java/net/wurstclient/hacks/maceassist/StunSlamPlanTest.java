/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.maceassist;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.wurstclient.hacks.maceassist.StunSlamPlan.Action;
import net.wurstclient.hacks.maceassist.StunSlamPlan.Kind;

/**
 * The mace half of a stun slam: one mace hit at the first verifiable tick
 * after the axe, the slot back where it started exactly once, and no hit
 * once the smash can't happen.
 */
public class StunSlamPlanTest
{
	private static final int START = 0, MACE = 8;
	
	/**
	 * Runs ticks until the plan is over; each row: change, hit, target, smash,
	 * mace.
	 */
	private static List<Action> run(StunSlamPlan plan, boolean[]... ticks)
	{
		List<Action> out = new ArrayList<>();
		for(boolean[] t : ticks)
		{
			Action a = plan.tick(t[0], t[1], t[2], t[3], t[4]);
			if(a.kind() != Kind.NONE)
				out.add(a);
		}
		return out;
	}
	
	private static boolean[] t(boolean change, boolean hit)
	{
		return new boolean[]{change, hit, true, true, true};
	}
	
	private static StunSlamPlan begun()
	{
		StunSlamPlan plan = new StunSlamPlan();
		plan.begin(START, MACE);
		return plan;
	}
	
	/** The ideal stun slam: the mace goes out the very next tick. */
	@Test
	public void maceTheNextTick()
	{
		StunSlamPlan plan = begun();
		Action a = plan.tick(true, true, true, true, true);
		assertEquals(new Action(Kind.FIRE_MACE, MACE, START), a);
		assertFalse(plan.isActive());
		assertEquals(Kind.NONE, plan.tick(true, true, true, true, true).kind());
	}
	
	/**
	 * Not in reach yet: back to the start item at once (not holding the axe
	 * while waiting), then the mace when the hit lines up - one return only.
	 */
	@Test
	public void waitsForAimWithoutHoldingTheAxe()
	{
		StunSlamPlan plan = begun();
		List<Action> acts =
			run(plan, t(true, false), t(true, false), t(true, true));
		assertEquals(List.of(new Action(Kind.RETURN, START, -1),
			new Action(Kind.FIRE_MACE, MACE, START)), acts);
	}
	
	/** A click of yours went out first this tick: the mace waits a tick. */
	@Test
	public void waitsForASlotChangeThatReachesTheServerFirst()
	{
		StunSlamPlan plan = begun();
		List<Action> acts = run(plan, t(false, true), t(true, true));
		assertEquals(List.of(new Action(Kind.RETURN, START, -1),
			new Action(Kind.FIRE_MACE, MACE, START)), acts);
	}
	
	/** Never in reach: gives up after GIVE_UP_TICKS, returned once, no hit. */
	@Test
	public void givesUp()
	{
		StunSlamPlan plan = begun();
		List<Action> acts = new ArrayList<>();
		for(int i = 0; i < StunSlamPlan.GIVE_UP_TICKS + 5; i++)
		{
			Action a = plan.tick(true, false, true, true, true);
			if(a.kind() != Kind.NONE)
				acts.add(a);
		}
		assertEquals(List.of(new Action(Kind.RETURN, START, -1)), acts);
		assertFalse(plan.isActive());
	}
	
	/** Landed (no smash any more) before the hit: just the return. */
	@Test
	public void landedBeforeTheHit()
	{
		StunSlamPlan plan = begun();
		Action a = plan.tick(true, true, true, false, true);
		assertEquals(new Action(Kind.RETURN, START, -1), a);
		assertFalse(plan.isActive());
	}
	
	/** The target died while waiting (already returned): nothing more. */
	@Test
	public void targetGoneAfterReturning()
	{
		StunSlamPlan plan = begun();
		List<Action> acts = run(plan, t(true, false),
			new boolean[]{true, true, false, true, true});
		assertEquals(List.of(new Action(Kind.RETURN, START, -1)), acts);
		assertFalse(plan.isActive());
	}
	
	/** The mace was moved out of its slot: no hit with whatever is there. */
	@Test
	public void maceMovedAway()
	{
		StunSlamPlan plan = begun();
		Action a = plan.tick(true, true, true, true, false);
		assertEquals(new Action(Kind.RETURN, START, -1), a);
	}
	
	/**
	 * A shield break alone (on the ground, no mace): the axe stays in hand
	 * for the tick of its hit, then back - no mace, no third hit.
	 */
	@Test
	public void shieldBreakAloneJustReturns()
	{
		StunSlamPlan plan = new StunSlamPlan();
		plan.begin(START, -1);
		// the hack reports "no mace in slot -1"
		Action a = plan.tick(true, true, true, false, false);
		assertEquals(new Action(Kind.RETURN, START, -1), a);
		assertFalse(plan.isActive());
	}
	
	/** You did the mace hit yourself: no second one, just the return. */
	@Test
	public void userMacedThemselves()
	{
		StunSlamPlan plan = begun();
		plan.userMaced();
		// the hack reports "no mace in slot -1"
		Action a = plan.tick(true, true, true, true, false);
		assertEquals(new Action(Kind.RETURN, START, -1), a);
		assertFalse(plan.isActive());
	}
	
	/** reset() (world change, disable) leaves nothing pending. */
	@Test
	public void resetClears()
	{
		StunSlamPlan plan = begun();
		plan.reset();
		assertFalse(plan.isActive());
		assertEquals(Kind.NONE, plan.tick(true, true, true, true, true).kind());
	}
}
