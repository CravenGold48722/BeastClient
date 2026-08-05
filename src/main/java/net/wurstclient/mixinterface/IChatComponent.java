/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import java.util.List;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

/**
 * Gives access to the chat's internal message list, so a message can be
 * replaced rather than piling another line on top of it.
 */
public interface IChatComponent
{
	/**
	 * The backing list of chat messages, newest first. Mutating it requires a
	 * follow-up {@link #refreshTrimmedMessages()} to rebuild what is actually
	 * drawn.
	 */
	public default List<GuiMessage> getAllMessages()
	{
		return wurst_getAllMessages();
	}
	
	/**
	 * Rebuilds the wrapped, line-split view of the chat from the message list.
	 */
	public default void refreshTrimmedMessages()
	{
		wurst_refreshTrimmedMessages();
	}
	
	public static IChatComponent get(ChatComponent chat)
	{
		return (IChatComponent)chat;
	}
	
	/**
	 * @deprecated Use {@link #getAllMessages()} instead.
	 */
	@Deprecated
	public List<GuiMessage> wurst_getAllMessages();
	
	/**
	 * @deprecated Use {@link #refreshTrimmedMessages()} instead.
	 */
	@Deprecated
	public void wurst_refreshTrimmedMessages();
}
