/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class MoveRulesTest
{
	private static NeuralObservation at(double dist)
	{
		NeuralObservation o = new NeuralObservation();
		o.myHealth = 1;
		o.dist = dist;
		o.gapplesLeft = 64;
		return o;
	}
	
	private static int forward(MoveRules rules, NeuralObservation o, int want)
	{
		NeuralAction a = new NeuralAction();
		a.forward = want;
		rules.apply(o, a);
		return a.forward;
	}
	
	@Test
	public void noWalkingAwayFromSomeoneStandingThere()
	{
		MoveRules rules = new MoveRules();
		for(int t = 0; t < 100; t++)
			assertEquals(1, forward(rules, at(6), -1), "6 blocks out: W");
		NeuralObservation leaving = at(5);
		leaving.opAlong = -0.2;
		assertEquals(1, forward(rules, leaving, -1), "they back off: W");
	}
	
	@Test
	public void backingOffWithAReason()
	{
		MoveRules rules = new MoveRules();
		// inside their reach: the network's own spacing
		for(int t = 0; t < 100; t++)
			assertEquals(-1, forward(rules, at(3), -1));
		// they sprint at you
		NeuralObservation charge = at(6);
		charge.opAlong = 0.25;
		assertEquals(-1, forward(rules, charge, -1));
		// low, with something to eat
		NeuralObservation low = at(6);
		low.myHealth = 0.3;
		assertEquals(-1, forward(rules, low, -1));
		low.gapplesLeft = 0;
		assertEquals(1, forward(rules, low, -1), "nothing to eat: W");
		// W and no key are never touched
		assertEquals(0, forward(rules, at(6), 0));
		assertEquals(1, forward(rules, at(6), 1));
	}
	
	@Test
	public void bothStrafeSides()
	{
		MoveRules rules = new MoveRules();
		rules.reset(3);
		int left = 0, right = 0, run = 0, longest = 0, last = 0;
		for(int t = 0; t < 2000; t++)
		{
			// a network that only ever wants one side
			NeuralAction a = new NeuralAction();
			a.strafe = 1;
			rules.apply(at(3), a);
			left += a.strafe > 0 ? 1 : 0;
			right += a.strafe < 0 ? 1 : 0;
			run = a.strafe == last ? run + 1 : 1;
			last = a.strafe;
			longest = Math.max(longest, run);
		}
		assertTrue(longest <= MoveRules.RUN_MAX, "longest run " + longest);
		assertTrue(Math.abs(left - right) < 300, left + " vs " + right);
		
		// its own switches stay its own
		MoveRules own = new MoveRules();
		for(int t = 0; t < 60; t++)
		{
			int want = t / 5 % 2 == 0 ? 1 : -1;
			NeuralAction a = new NeuralAction();
			a.strafe = want;
			own.apply(at(3), a);
			assertEquals(want, a.strafe, "tick " + t);
		}
	}
}
