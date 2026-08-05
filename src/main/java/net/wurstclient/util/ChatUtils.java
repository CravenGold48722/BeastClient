/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.List;
import java.util.StringJoiner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.WurstClient;

public enum ChatUtils
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	/**
	 * The client tag exactly as it reads once rendered.
	 *
	 * <p>
	 * Features that need to recognise the client's own messages compare
	 * against this. They can't use a formatted version: the tag is styled with
	 * real RGB colors rather than legacy codes, so nothing shows up in the
	 * message text.
	 */
	public static final String PLAIN_PREFIX = "[Beast] ";
	
	/**
	 * The client tag using legacy formatting codes, for the few screens that
	 * build their title by string concatenation.
	 *
	 * <p>
	 * \u00a7c is the nearest legacy red to {@link BeastColors#BRAND_RED} but
	 * not an
	 * exact match, so prefer {@link #prefix()} anywhere a Component will do.
	 */
	public static final String WURST_PREFIX =
		"\u00a77[\u00a7cBeast\u00a77]\u00a7r ";
	private static final String WARNING_PREFIX =
		"\u00a7c[\u00a76\u00a7lWARNING\u00a7c]\u00a7r ";
	private static final String ERROR_PREFIX =
		"\u00a7c[\u00a74\u00a7lERROR\u00a7c]\u00a7r ";
	private static final String SYNTAX_ERROR_PREFIX =
		"\u00a74Syntax error:\u00a7r ";
	
	private static boolean enabled = true;
	
	public static void setEnabled(boolean enabled)
	{
		ChatUtils.enabled = enabled;
	}
	
	/**
	 * The "[Beast] " tag, in the same shades of red and gray as the hack
	 * toggle announcements.
	 */
	public static MutableComponent prefix()
	{
		MutableComponent prefix = Component.literal("[")
			.withStyle(style -> style.withColor(BeastColors.BRACKET_GRAY));
		
		prefix.append(Component.literal("Beast")
			.withStyle(style -> style.withColor(BeastColors.BRAND_RED)));
		
		prefix.append(Component.literal("] ")
			.withStyle(style -> style.withColor(BeastColors.BRACKET_GRAY)));
		
		return prefix;
	}
	
	public static void component(Component component)
	{
		if(!enabled)
			return;
		
		ChatComponent chatHud = MC.gui.getChat();
		chatHud.addClientSystemMessage(prefix().append(component));
	}
	
	public static void message(String message)
	{
		component(Component.literal(message));
	}
	
	public static void warning(String message)
	{
		message(WARNING_PREFIX + message);
	}
	
	public static void error(String message)
	{
		message(ERROR_PREFIX + message);
	}
	
	public static void syntaxError(String message)
	{
		message(SYNTAX_ERROR_PREFIX + message);
	}
	
	public static String getAsString(GuiMessage.Line visible)
	{
		return getAsString(visible.content());
	}
	
	public static String getAsString(FormattedCharSequence text)
	{
		JustGiveMeTheStringVisitor visitor = new JustGiveMeTheStringVisitor();
		text.accept(visitor);
		return visitor.toString();
	}
	
	public static final String wrapText(String text, int width)
	{
		return wrapText(text, width, Style.EMPTY);
	}
	
	public static final String wrapText(String text, int width, Style style)
	{
		List<FormattedText> lines =
			MC.font.getSplitter().splitLines(text, width, Style.EMPTY);
		
		StringJoiner joiner = new StringJoiner("\n");
		lines.stream().map(FormattedText::getString)
			.forEach(s -> joiner.add(s));
		
		return joiner.toString();
	}
}
