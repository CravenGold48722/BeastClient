/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.wurstclient.events.UpdateListener;

/**
 * Actually opens the inventory before a hack clicks around in it, and closes
 * it again afterwards, instead of clicking slots with no inventory open.
 *
 * <p>
 * Opening the inventory screen is what a player does with E: it lets go of
 * the movement keys (so you stop walking and sprinting, which anti-cheats
 * check for while clicking), and closing it the way Esc does sends the
 * container-close packet that real clients always send after clicking.
 */
public final class InventoryOpener implements UpdateListener
{
	/** Ticks without a click before the inventory is closed again. */
	private static final int CLOSE_DELAY = 3;
	
	private static final Minecraft MC = WurstClient.MC;
	
	private boolean openedByUs;
	private int idleTicks;
	
	/**
	 * Called before every click on a slot of the player's own inventory.
	 */
	public void beforeInventoryClick()
	{
		if(MC.player == null)
			return;
		
		Screen screen = MC.screen;
		if(isInventoryScreen(screen))
		{
			idleTicks = 0;
			return;
		}
		
		// Something else is open (chat, a menu, another container). A player
		// couldn't open the inventory from there either, so don't throw away
		// that screen - the click goes out as it always has.
		if(screen != null)
			return;
			
		// Creative inventory clicks are handled by the client anyway, and
		// vanilla redirects InventoryScreen to the creative one.
		if(MC.player.hasInfiniteMaterials())
			return;
		
		// Same thing vanilla does when E is pressed.
		MC.getTutorial().onOpenInventory();
		MC.setScreen(new InventoryScreen(MC.player));
		openedByUs = true;
		idleTicks = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(!openedByUs)
			return;
		
		// closed by the player or replaced by something else
		if(!isInventoryScreen(MC.screen))
		{
			openedByUs = false;
			return;
		}
		
		if(++idleTicks < CLOSE_DELAY)
			return;
		
		// Same as pressing Esc: sends the close packet, then closes the screen.
		MC.screen.onClose();
		openedByUs = false;
	}
	
	private static boolean isInventoryScreen(Screen screen)
	{
		return screen instanceof InventoryScreen
			|| screen instanceof CreativeModeInventoryScreen;
	}
}
