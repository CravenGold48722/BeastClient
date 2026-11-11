/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

public class ScriptedPoliciesTest
{
	/**
	 * Training's scripted players change their game plan in ~40% of the
	 * fights, 5-25 seconds in - and a fight that switches plays on to its
	 * end without breaking.
	 */
	@Test
	public void someChangeTheirPlanMidFight()
	{
		SplittableRandom random = new SplittableRandom(1);
		int switching = 0, fights = 2000;
		for(int i = 0; i < fights; i++)
		{
			ScriptedPolicies.Switching p =
				new ScriptedPolicies.Switching(i % ScriptedPolicies.ALL.length);
			p.reset(random.split());
			int at = p.switchAt();
			if(at == Integer.MAX_VALUE)
				continue;
			switching++;
			assertTrue(at >= 100 && at < 500, "at " + at);
		}
		double share = switching / (double)fights;
		assertTrue(Math.abs(share - ScriptedPolicies.Switching.SHARE) < 0.05,
			"share " + share);
		
		// whole fights with them, switching or not
		for(int seed = 0; seed < 20; seed++)
		{
			PvpSim sim = new PvpSim(seed, new ScriptedPolicies.SprintCombo(),
				new ScriptedPolicies.Switching(seed % 5), 1200);
			sim.run();
		}
	}
}
