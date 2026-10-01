/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings.filters;

import net.minecraft.world.entity.Entity;
import net.wurstclient.EntitySpeedTracker;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.filterlists.EntityFilterList.EntityFilter;

/**
 * Filters out anything that moved faster than a player can, measured from
 * the positions the server sends (see {@link EntitySpeedTracker}). Catches
 * the anti-cheat bots some servers teleport around behind you.
 */
public final class FilterSpeedSetting extends SliderSetting
	implements EntityFilter
{
	public FilterSpeedSetting(String description, double value)
	{
		super("Filter speed", description, value, 0, 200, 5,
			ValueDisplay.INTEGER.withSuffix(" blocks/s").withLabel(0, "off"));
	}
	
	@Override
	public boolean test(Entity e)
	{
		return WurstClient.INSTANCE.getEntitySpeedTracker()
			.getRecentTopSpeed(e) <= getValue();
	}
	
	@Override
	public boolean isFilterEnabled()
	{
		return getValue() > 0;
	}
	
	@Override
	public Setting getSetting()
	{
		return this;
	}
	
	public static FilterSpeedSetting genericCombat(double value)
	{
		return new FilterSpeedSetting("Won't target anything that moved"
			+ " faster than this in the last "
			+ EntitySpeedTracker.MEMORY_TICKS / 20 + " seconds.\n\n"
			+ "Some servers (e.g. CubeCraft) put a flying anti-cheat bot behind"
			+ " you and teleport it around to see if you hit it. A teleport"
			+ " moves it far faster than anything legit.\n\n"
			+ "For reference, the fastest a player can move in vanilla is"
			+ " about 78 blocks/s (falling at terminal velocity); an elytra"
			+ " dive reaches about 68, a sprint is about 5.6.\n\n"
			+ "Speed is measured from the raw positions the server sends, not"
			+ " the smoothed ones you see, so short teleports are caught too."
			+ " An ender pearl counts as a teleport.", value);
	}
}
