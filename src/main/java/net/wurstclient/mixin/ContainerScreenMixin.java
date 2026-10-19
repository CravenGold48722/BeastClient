/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestScreenRecording;
import net.wurstclient.chestsearch.SlotHighlighter;
import net.wurstclient.hacks.AutoStealHack;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.wurstclient.hacks.RemoteEnderChestHack;

@Mixin(ContainerScreen.class)
public abstract class ContainerScreenMixin
	extends AbstractContainerScreen<ChestMenu>
{
	@Shadow
	@Final
	private int containerRows;
	
	@Unique
	private final AutoStealHack autoSteal =
		WurstClient.INSTANCE.getHax().autoStealHack;
	
	@Unique
	private ChestScreenRecording chestRecording;
	
	public ContainerScreenMixin(WurstClient wurst, ChestMenu container,
		Inventory playerInventory, Component name)
	{
		super(container, playerInventory, name);
	}
	
	@Override
	public void init()
	{
		super.init();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
			
		// Steal has no button anymore: shift-double-click an empty container
		// slot instead (see mouseClicked below).
		if(autoSteal.areButtonsVisible())
			addRenderableWidget(Button
				.builder(Component.literal("Store"),
					b -> autoSteal.store(this, containerRows))
				.bounds(leftPos + imageWidth - 56, topPos + 4, 50, 12).build());
		
		if(autoSteal.isEnabled())
			autoSteal.steal(this, containerRows);
		
		// ChestSearch (ported from the CevAPI fork)
		if(chestRecording == null)
			chestRecording =
				ChestScreenRecording.start(menu, containerRows * 9);
		if(chestRecording != null
			&& WurstClient.INSTANCE.getHax().chestSearchHack.isManualMode())
			addRenderableWidget(Button
				.builder(Component.literal("Scan"),
					b -> chestRecording.scanNow())
				.bounds(leftPos + 6, topPos + 4, 50, 12).build());
	}
	
	/**
	 * RemoteEnderChest (ported from the CevAPI fork): E and Esc hide its
	 * ender chest instead of closing it, its toggle key brings it back.
	 */
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(WurstClient.INSTANCE.isEnabled() && RemoteEnderChestHack
			.isLinkedScreen((AbstractContainerScreen<?>)(Object)this))
		{
			RemoteEnderChestHack remote =
				WurstClient.INSTANCE.getHax().remoteEnderChestHack;
			int key = event.key();
			if(key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_E)
			{
				if(remote.shouldSwapInventoryKey())
				{
					remote.setEnabled(false);
					return true;
				}
				
				RemoteEnderChestHack.hideLinkedGui();
				return true;
			}
			
			String keyName = InputConstants.getKey(event).getName();
			if(keyName.equalsIgnoreCase(remote.getToggleGuiKeyName()))
			{
				RemoteEnderChestHack.toggleLinkedGui();
				return true;
			}
		}
		
		return super.keyPressed(event);
	}
	
	@Override
	public void removed()
	{
		SlotHighlighter.INSTANCE.clearActive();
		if(chestRecording != null)
			chestRecording.finish();
		
		super.removed();
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(WurstClient.INSTANCE.isEnabled() && autoSteal.onContainerClick(this,
			hoveredSlot, event, containerRows))
			return true;
		
		return super.mouseClicked(event, doubleClick);
	}
}
