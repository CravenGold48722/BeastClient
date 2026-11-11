/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.GameType;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

/**
 * A bigger tab list with exact pings, your own and your friends' names
 * colored, and game modes. Idea from Meteor Client's BetterTab, written for
 * Beast; hooked in {@code BetterTabMixin}.
 */
@SearchTags({"better tab", "tab list", "player list", "ping"})
public final class BetterTabHack extends Hack
{
	private final SliderSetting size = new SliderSetting("Size",
		"How many players the tab list shows (vanilla: 80).", 100, 1, 1000, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting columnHeight =
		new SliderSetting("Column height", "How many players go in one column.",
			20, 1, 100, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting highlightSelf =
		new CheckboxSetting("Highlight self", "Colors your own name.", true);
	private final ColorSetting selfColor = new ColorSetting("Self color",
		"Your name's color.", new Color(250, 130, 30));
	private final CheckboxSetting highlightFriends = new CheckboxSetting(
		"Highlight friends", "Colors your friends' names.", true);
	private final ColorSetting friendColor = new ColorSetting("Friend color",
		"Your friends' names' color.", new Color(0, 200, 255));
	private final CheckboxSetting exactPing = new CheckboxSetting("Exact ping",
		"Shows the ping in milliseconds instead of bars.", true);
	private final CheckboxSetting gameMode = new CheckboxSetting("Game mode",
		"Shows each player's game mode after the name.", false);
	
	public BetterTabHack()
	{
		super("BetterTab", "Improves the tab list.", false);
		setCategory(Category.RENDER);
		addSetting(size);
		addSetting(columnHeight);
		addSetting(highlightSelf);
		addSetting(selfColor);
		addSetting(highlightFriends);
		addSetting(friendColor);
		addSetting(exactPing);
		addSetting(gameMode);
	}
	
	public long getSize(long vanilla)
	{
		return isEnabled() ? size.getValueI() : vanilla;
	}
	
	public int getColumnHeight()
	{
		return columnHeight.getValueI();
	}
	
	public boolean isExactPing()
	{
		return isEnabled() && exactPing.isChecked();
	}
	
	public Component getName(PlayerInfo info, Component vanilla)
	{
		if(!isEnabled())
			return vanilla;
		
		Component name = vanilla;
		String profileName = info.getProfile().name();
		Integer color = null;
		if(highlightSelf.isChecked() && MC.player != null
			&& info.getProfile().id().equals(MC.player.getGameProfile().id()))
			color = selfColor.getColorI();
		else if(highlightFriends.isChecked()
			&& WURST.getFriends().contains(profileName))
			color = friendColor.getColorI();
		
		if(color != null)
		{
			String plain = ChatFormatting.stripFormatting(name.getString());
			name = Component.literal(plain).setStyle(
				name.getStyle().withColor(TextColor.fromRgb(color & 0xFFFFFF)));
		}
		
		if(gameMode.isChecked())
		{
			GameType type = info.getGameMode();
			String mode = type == null ? "?" : switch(type)
			{
				case SURVIVAL -> "S";
				case CREATIVE -> "C";
				case ADVENTURE -> "A";
				case SPECTATOR -> "Sp";
			};
			MutableComponent withMode = Component.empty().append(name);
			name = withMode.append(" [" + mode + "]");
		}
		
		return name;
	}
}
