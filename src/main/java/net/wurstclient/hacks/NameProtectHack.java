/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IChatComponent;

@SearchTags({"name protect"})
public final class NameProtectHack extends Hack
{
	public NameProtectHack()
	{
		super("NameProtect");
		setCategory(Category.RENDER);
	}
	
	// Chat lines are laid out once, when a message comes in, and drawn from
	// that layout afterwards: without this, names already in chat stayed
	// visible after turning it on (and hidden after turning it off).
	@Override
	protected void onEnable()
	{
		refreshChat();
	}
	
	@Override
	protected void onDisable()
	{
		refreshChat();
	}
	
	private void refreshChat()
	{
		if(MC.gui != null)
			IChatComponent.get(MC.gui.getChat()).refreshTrimmedMessages();
	}
	
	public String protect(String string)
	{
		if(!isEnabled() || MC.player == null)
			return string;
		
		String me = MC.getUser().getName();
		if(string.contains(me))
			return string.replace(me, "\u00a7oMe\u00a7r");
		
		int i = 0;
		for(PlayerInfo info : MC.player.connection.getOnlinePlayers())
		{
			i++;
			String name =
				info.getProfile().name().replaceAll("\u00a7(?:\\w|\\d)", "");
			
			if(string.contains(name))
				return string.replace(name, "\u00a7oPlayer" + i + "\u00a7r");
		}
		
		for(AbstractClientPlayer player : MC.level.players())
		{
			i++;
			String name = player.getName().getString();
			
			if(string.contains(name))
				return string.replace(name, "\u00a7oPlayer" + i + "\u00a7r");
		}
		
		return string;
	}
}
