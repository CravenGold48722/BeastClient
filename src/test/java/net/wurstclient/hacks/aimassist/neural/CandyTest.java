/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/** The reward rules of docs/TRAINING.MD and the free-for-all. */
public final class CandyTest
{
	@Test
	public void streaksScale()
	{
		assertStreak(Candy.REGULAR, 1 + 2 + 3 + 4 + 5);
		assertStreak(Candy.CRIT, 1 + 2 + 3 + 5 + 8);
		assertStreak(Candy.SPRINT, 1 + 2 + 4 + 8 + 16);
	}
	
	@Test
	public void webbedOpponentsPayForCrits()
	{
		assertStreak(Candy.CRIT, 1 + 2 + 4 + 8 + 16, true);
		assertStreak(Candy.SPRINT, 1 + 2 + 3 + 4 + 5, true);
		assertStreak(Candy.REGULAR, 0.5 + 1 + 1.5 + 2 + 2.5, true);
	}
	
	@Test
	public void critsRightAfterWebbingAndJumpsPayMore()
	{
		Candy c = new Candy();
		c.reset(0);
		c.trapped(0, 20); // 10
		c.hit(Candy.CRIT, 30, 20, true); // 1 + 5
		c.hit(Candy.CRIT, 60, 20, true); // 2, too late for the bonus
		assertEquals(18, c.total());
		for(int t = 61; t < 70; t++) // one jump: paid once
			c.tick(t, 2, false, true, true, 20);
		c.tick(70, 2, false, true, false, 20);
		c.tick(71, 2, false, true, true, 20); // the next one
		assertEquals(20, c.total());
	}
	
	private static void assertStreak(int type, double total)
	{
		assertStreak(type, total, false);
	}
	
	private static void assertStreak(int type, double total, boolean webbed)
	{
		Candy c = new Candy();
		for(int i = 0; i < 5; i++)
			c.hit(type, i * 20, 20, webbed);
		assertEquals(total, c.total());
	}
	
	@Test
	public void streakEndsAfterFiveSecondsOrAHit()
	{
		Candy c = new Candy();
		c.hit(Candy.SPRINT, 0, 20, false);
		c.hit(Candy.SPRINT, 101, 20, false);
		assertEquals(2, c.total());
		c.hit(Candy.SPRINT, 120, 20, false);
		assertEquals(4, c.total());
		c.hurt(130);
		assertEquals(4 / 2 - 5, c.total());
		c.hit(Candy.SPRINT, 140, 20, false);
		assertEquals(-3 + 1, c.total());
	}
	
	@Test
	public void healthScalesToHalves()
	{
		Candy c = new Candy();
		c.trapped(0, 14);
		assertEquals(7, c.total());
		Candy d = new Candy();
		for(int i = 0; i < 3; i++)
			d.hit(Candy.REGULAR, i, 20, false);
		d.hit(Candy.REGULAR, 3, 10, false); // 4th hit at 5 hearts: 4 * 0.5
		assertEquals(6 + 2, d.total());
		Candy e = new Candy();
		e.hit(Candy.REGULAR, 0, 7, false); // 0.35 -> 0.5
		assertEquals(0.5, e.total());
	}
	
	@Test
	public void websIdleAndTurning()
	{
		Candy c = new Candy();
		for(int t = 1; t <= 40; t++)
			c.tick(t, 3, true, false, false, 20);
		assertEquals(-20, c.total());
		Candy d = new Candy();
		for(int t = 1; t <= 200 + 40; t++)
			d.tick(t, 20, false, false, false, 20);
		assertEquals(-2, d.total(), 1);
		Candy e = new Candy();
		e.hit(Candy.SPRINT, 0, 20, false);
		e.turned(1799);
		assertEquals(1, e.total());
		e.turned(1801);
		assertEquals(0, e.total());
	}
	
	@Test
	public void freeForAllEndsWithOneStanding()
	{
		Policy[] players = new Policy[20];
		for(int i = 0; i < players.length; i++)
			players[i] = new ScriptedPolicies.SprintCombo();
		PvpSim sim = new PvpSim(42, players, 20 * 60 * 5);
		int result = sim.run();
		int alive = 0;
		for(SimFighter f : sim.fighters)
			if(f.health > 0)
				alive++;
		assertTrue(alive == 1 || sim.tick >= 20 * 60 * 5,
			alive + " alive at tick " + sim.tick);
		assertEquals(result == 1, sim.a.health > 0 && alive == 1);
		int hits = 0;
		for(SimFighter f : sim.fighters)
			hits += f.hits;
		assertTrue(hits > 50, "only " + hits + " hits");
	}
}
