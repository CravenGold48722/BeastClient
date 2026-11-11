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

/** Small cells where a lot is going on, big ones where little is. */
public final class FocusMapTest
{
	@Test
	public void busyAreasGetSmallCells()
	{
		FocusMap m = new FocusMap();
		// facing +x; a crowd ahead-left, one web far behind
		m.clear(0, 0, 1, 0, 32);
		for(int i = 0; i < 6; i++)
			m.add(8 + i * 0.7, 4 + (i % 3) * 0.6, FocusMap.PLAYER, 0.5);
		m.add(9, 5, FocusMap.PROJECTILE, 0);
		m.add(-20, 0, FocusMap.WEB, 0);
		float[] out = new float[FocusMap.INPUTS];
		m.write(out, 0);
		
		int F = FocusMap.FEATURES;
		// the heaviest cells are the crowd's: ahead, small, with players
		assertTrue(out[0] > 0, "ahead " + out[0]);
		assertTrue(out[3] > 0);
		// the lone web is one cell of its own, the lightest one used
		int web = -1;
		for(int c = 0; c < FocusMap.CELLS; c++)
			if(out[c * F + 5] > 0)
				web = c;
		assertTrue(web >= 0, "web cell");
		assertEquals(0, out[web * F + 3], "no players with the web");
		assertTrue(out[web * F] < 0, "behind");
		for(int c = 0; c < FocusMap.CELLS; c++)
			if(c != web && out[c * F + 3] > 0)
				assertTrue(out[c * F + 2] < FocusMap.squash(3),
					"crowd cell " + c + " too big: " + out[c * F + 2]);
	}
	
	@Test
	public void nothingAroundIsAllZeros()
	{
		FocusMap m = new FocusMap();
		m.clear(0, 0, 1, 0, 32);
		m.add(100, 100, FocusMap.PLAYER, 1); // out of range
		float[] out = new float[FocusMap.INPUTS];
		m.write(out, 0);
		for(float f : out)
			assertEquals(0, f);
	}
}
