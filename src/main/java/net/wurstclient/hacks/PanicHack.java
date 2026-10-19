/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;

import net.wurstclient.Category;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"legit", "disable"})
@DontBlock
public final class PanicHack extends Hack implements UpdateListener
{
	public PanicHack()
	{
		super("Panic");
		setCategory(Category.OTHER);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	/** What the last panic turned off, for .panic restore (this session). */
	private final ArrayList<String> turnedOff = new ArrayList<>();
	
	@Override
	public void onUpdate()
	{
		ArrayList<String> now = new ArrayList<>();
		for(Hack hack : WURST.getHax().getAllHax())
			if(hack.isEnabled() && hack != this)
			{
				now.add(hack.getName());
				hack.setEnabled(false);
			}
		
		if(!now.isEmpty())
		{
			turnedOff.clear();
			turnedOff.addAll(now);
		}
		
		setEnabled(false);
	}
	
	/**
	 * Turns back on what the last panic turned off (.panic restore, ported
	 * from the CevAPI fork).
	 */
	public int restoreSavedHacks()
	{
		int restored = 0;
		for(String name : turnedOff)
		{
			Hack hack = WURST.getHax().getHackByName(name);
			if(hack == null || hack.isEnabled())
				continue;
			
			hack.setEnabled(true);
			if(hack.isEnabled())
				restored++;
		}
		
		turnedOff.clear();
		return restored;
	}
}
