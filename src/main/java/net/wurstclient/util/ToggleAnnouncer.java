/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IChatComponent;
import net.wurstclient.other_feature.OtfList;
import net.wurstclient.other_features.ToggleMessagesOtf;

/**
 * Announces feature toggles on the action bar and in chat, the way Meteor
 * Client does.
 *
 * <p>
 * Action bar: {@code Toggled Spider ON}<br>
 * Chat: {@code [Beast] Toggled Spider on.}
 */
public enum ToggleAnnouncer
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	/** The "Beast" tag that prefixes every chat announcement. */
	private static final int BRAND_COLOR = BeastColors.BRAND_RED;
	private static final int BRACKET_COLOR = BeastColors.BRACKET_GRAY;
	
	/** Everything in an announcement that isn't ON/OFF or the brand tag. */
	private static final int TEXT_COLOR = 0xFFFFFF;
	
	/** The "ON" state word. */
	private static final int ON_COLOR = 0x55FF55;
	
	/** The "OFF" state word. */
	private static final int OFF_COLOR = 0xFF5555;
	
	/**
	 * The hack whose toggle line is currently sitting at the bottom of chat.
	 */
	private static Hack lastAnnouncedHack;
	
	/** Exactly what that line ended up containing, for identifying it later. */
	private static Component lastChatMessage;
	
	/**
	 * Announces that the given hack was toggled on or off.
	 *
	 * <p>
	 * Safe to call before the player exists - it simply does nothing in that
	 * case, which is what happens when saved hack states are restored during
	 * startup.
	 */
	public static void announce(Hack hack, boolean enabled)
	{
		if(MC.player == null || MC.level == null || MC.gui == null)
			return;
			
		// Saved hack states are restored during startup, before the feature
		// lists finish building. Nothing to announce at that point anyway.
		OtfList otfs = WurstClient.INSTANCE.getOtfs();
		if(otfs == null)
			return;
		
		ToggleMessagesOtf settings = otfs.toggleMessagesOtf;
		if(!settings.isEnabled())
			return;
		
		String name = hack.getName();
		
		// The action bar is a single slot, so it replaces itself for free.
		if(settings.isActionBarShown())
			showOnActionBar(name, enabled);
		
		if(settings.isChatShown())
			showInChat(hack, name, enabled);
	}
	
	/**
	 * "Toggled Spider ON" / "Toggled Spider OFF" - everything white except the
	 * state word, which is green for ON and red for OFF.
	 */
	private static void showOnActionBar(String name, boolean enabled)
	{
		MutableComponent text = Component.literal("Toggled " + name + " ")
			.withStyle(style -> style.withColor(TEXT_COLOR));
		
		text.append(Component.literal(enabled ? "ON" : "OFF").withStyle(
			style -> style.withColor(enabled ? ON_COLOR : OFF_COLOR)));
		
		MC.gui.setOverlayMessage(text, false);
	}
	
	/**
	 * "[Beast] Toggled Spider on." - Beast in red, the rest white.
	 *
	 * <p>
	 * Spamming a hack's keybind rewrites the existing line in place instead of
	 * stacking up a new one per press, so the chat just flips between "on."
	 * and "off." the way Meteor does.
	 */
	private static void showInChat(Hack hack, String name, boolean enabled)
	{
		MutableComponent message = Component.literal("[")
			.withStyle(style -> style.withColor(BRACKET_COLOR));
		
		message.append(Component.literal("Beast")
			.withStyle(style -> style.withColor(BRAND_COLOR)));
		
		message.append(Component.literal("] ")
			.withStyle(style -> style.withColor(BRACKET_COLOR)));
		
		message.append(Component.literal("Toggled " + name + " ")
			.withStyle(style -> style.withColor(TEXT_COLOR)));
		
		// The state word matches the action bar: green for on, red for off.
		message.append(Component.literal(enabled ? "on" : "off").withStyle(
			style -> style.withColor(enabled ? ON_COLOR : OFF_COLOR)));
		
		message.append(Component.literal(".")
			.withStyle(style -> style.withColor(TEXT_COLOR)));
		
		ChatComponent chat = MC.gui.getChat();
		
		// Only collapse repeats of the same hack, and only while our own
		// message is still the newest line - anything the player or the server
		// said in between deserves to stay put.
		if(hack == lastAnnouncedHack)
			removeNewestIfOurs(chat);
			
		// Bypasses ChatUtils so the Wurst prefix isn't prepended - this
		// message carries its own [Beast] tag.
		chat.addClientSystemMessage(message);
		
		lastAnnouncedHack = hack;
		lastChatMessage = newestContent(chat);
	}
	
	/**
	 * Drops the newest chat line if it is the toggle message we added last
	 * time, so the replacement takes its place.
	 */
	private static void removeNewestIfOurs(ChatComponent chat)
	{
		if(lastChatMessage == null)
			return;
		
		try
		{
			List<GuiMessage> all = IChatComponent.get(chat).getAllMessages();
			if(all.isEmpty() || !lastChatMessage.equals(all.get(0).content()))
				return;
			
			all.remove(0);
			IChatComponent.get(chat).refreshTrimmedMessages();
			
		}catch(RuntimeException e)
		{
			// If the chat internals ever change shape, fall back to simply
			// appending - a duplicated line beats a crash.
			lastChatMessage = null;
		}
	}
	
	/**
	 * The content of the newest chat line, read back after posting.
	 *
	 * <p>
	 * Read back rather than remembered, because other mixins (NoChatReports,
	 * the ChatInput event) can rewrite a message on its way in - what actually
	 * landed is the only thing worth comparing against later.
	 */
	private static Component newestContent(ChatComponent chat)
	{
		try
		{
			List<GuiMessage> all = IChatComponent.get(chat).getAllMessages();
			return all.isEmpty() ? null : all.get(0).content();
			
		}catch(RuntimeException e)
		{
			return null;
		}
	}
}
