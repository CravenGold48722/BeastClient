/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.EnumSet;
import java.util.Random;

import net.minecraft.world.entity.player.PlayerModelPart;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"SpookySkin", "spooky skin", "SkinBlinker", "skin blinker"})
public final class SkinDerpHack extends Hack implements UpdateListener
{
	private final Random random = new Random();
	/** Your own skin settings, put back when it's turned off. */
	private final EnumSet<PlayerModelPart> ownParts =
		EnumSet.noneOf(PlayerModelPart.class);
	
	public SkinDerpHack()
	{
		super("SkinDerp");
		setCategory(Category.FUN);
	}
	
	@Override
	protected void onEnable()
	{
		ownParts.clear();
		for(PlayerModelPart part : PlayerModelPart.values())
			if(MC.options.isModelPartEnabled(part))
				ownParts.add(part);
			
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		for(PlayerModelPart part : PlayerModelPart.values())
			MC.options.setModelPart(part, ownParts.contains(part));
		MC.options.broadcastOptions();
	}
	
	@Override
	public void onUpdate()
	{
		if(random.nextInt(4) != 0)
			return;
		
		for(PlayerModelPart part : PlayerModelPart.values())
			MC.options.setModelPart(part, !MC.options.isModelPartEnabled(part));
			
		// what the skin customization screen does: since 26.1, setModelPart
		// only changes the option, and nobody else would see it
		MC.options.broadcastOptions();
	}
}
