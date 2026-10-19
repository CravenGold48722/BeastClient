/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.EnchantmentHandlerHack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.wurstclient.chestsearch.SlotHighlighter;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.wurstclient.hacks.ChestSearchHack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.hacks.AntiDropHack;
import net.wurstclient.hacks.QuickShulkerHack;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.wurstclient.hacks.AutoTraderHack;
import net.wurstclient.hacks.NbtSizeCounterHack;

/**
 * Overlays that features ported from the CevAPI fork draw on container
 * screens (chests, the inventory...), and the mouse input they take there.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerOverlayHooksMixin extends Screen
{
	@Shadow
	protected int leftPos;
	
	@Shadow
	protected int topPos;
	
	@Shadow
	@Final
	protected AbstractContainerMenu menu;
	
	@Shadow
	protected Slot hoveredSlot;
	
	@Shadow
	@Final
	protected int imageWidth;
	
	private ContainerOverlayHooksMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/** QuickShulker's and AutoTrader's buttons, above the container. */
	@Inject(at = @At("TAIL"), method = "init()V")
	private void wurst$addButtons(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		// AutoTrader's button on villager trade screens
		if((Object)this instanceof MerchantScreen)
		{
			AutoTraderHack autoTrader =
				WurstClient.INSTANCE.getHax().autoTraderHack;
			addRenderableWidget(Button
				.builder(Component.literal("AutoTrader"),
					b -> autoTrader.triggerFromGui())
				.bounds(leftPos + imageWidth - 90, topPos - 20, 80, 16)
				.build());
			return;
		}
		
		QuickShulkerHack quickShulker =
			WurstClient.INSTANCE.getHax().quickShulkerHack;
		if(!quickShulker.isEnabled() || !quickShulker.hasUsableShulker())
			return;
		
		Button button = Button
			.builder(Component.literal("QuickShulker"),
				b -> quickShulker.triggerFromGui())
			.bounds(leftPos + imageWidth - 90, topPos - 20, 80, 16).build();
		button.active = !quickShulker.isBusy();
		addRenderableWidget(button);
	}
	
	/** AntiDrop: no throwing protected items out of a container. */
	@Inject(at = @At("HEAD"),
		method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
		cancellable = true)
	private void wurst$antiDropSlotClick(Slot slot, int slotId, int button,
		ContainerInput input, CallbackInfo ci)
	{
		if(input != ContainerInput.THROW && slotId != -999
			|| !WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDrop.isEnabled())
			return;
		
		ItemStack stack = slotId == -999 ? menu.getCarried()
			: slot != null ? slot.getItem() : ItemStack.EMPTY;
		if(antiDrop.shouldBlock(stack))
			ci.cancel();
	}
	
	private AbstractContainerScreen<?> wurst$self()
	{
		return (AbstractContainerScreen<?>)(Object)this;
	}
	
	private static NbtSizeCounterHack wurst$nbtSizeCounter()
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return null;
		
		NbtSizeCounterHack hack =
			WurstClient.INSTANCE.getHax().nbtSizeCounterHack;
		return hack.isEnabled() ? hack : null;
	}
	
	private static EnchantmentHandlerHack wurst$enchantHandler()
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return null;
		
		EnchantmentHandlerHack hack =
			WurstClient.INSTANCE.getHax().enchantmentHandlerHack;
		return hack.isEnabled() ? hack : null;
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		cancellable = true)
	private void wurst$onMouseClicked(MouseButtonEvent context,
		boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
	{
		wurst$forgetHighlightIfHovered(context.x(), context.y());
		
		// QuickShulker: middle click a shulker box to look inside
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
			&& WurstClient.INSTANCE.isEnabled() && hoveredSlot != null
			&& WurstClient.INSTANCE.getHax().quickShulkerHack
				.openShulkerInventory(hoveredSlot))
		{
			cir.setReturnValue(true);
			return;
		}
		
		NbtSizeCounterHack nbtSize = wurst$nbtSizeCounter();
		if(nbtSize != null && nbtSize.handleMouseClick(context))
		{
			cir.setReturnValue(true);
			return;
		}
		
		if(WurstClient.INSTANCE.isEnabled()
			&& WurstClient.INSTANCE.getHud().getChestSearchMousePreview()
				.handleMouseClick(context.x(), context.y(), context.button()))
		{
			cir.setReturnValue(true);
			return;
		}
		
		EnchantmentHandlerHack enchantHack = wurst$enchantHandler();
		if(enchantHack != null && enchantHack.handleMouseClick(wurst$self(),
			context.x(), context.y(), context.button()))
			cir.setReturnValue(true);
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseScrolled(DDDD)Z",
		cancellable = true)
	private void wurst$onMouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.isEnabled()
			&& WurstClient.INSTANCE.getHud().getChestSearchMousePreview()
				.handleMouseScroll(mouseX, mouseY, verticalAmount))
		{
			cir.setReturnValue(true);
			return;
		}
		
		EnchantmentHandlerHack enchantHack = wurst$enchantHandler();
		if(enchantHack != null && enchantHack.handleMouseScroll(wurst$self(),
			mouseX, mouseY, verticalAmount))
			cir.setReturnValue(true);
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
		cancellable = true)
	private void wurst$onMouseReleased(MouseButtonEvent context,
		CallbackInfoReturnable<Boolean> cir)
	{
		NbtSizeCounterHack nbtSize = wurst$nbtSizeCounter();
		if(nbtSize != null && nbtSize.handleMouseRelease())
		{
			cir.setReturnValue(true);
			return;
		}
		
		if(WurstClient.INSTANCE.isEnabled() && WurstClient.INSTANCE.getHud()
			.getChestSearchMousePreview().handleMouseRelease(context.button()))
		{
			cir.setReturnValue(true);
			return;
		}
		
		EnchantmentHandlerHack enchantHack = wurst$enchantHandler();
		if(enchantHack != null && enchantHack.handleMouseRelease(wurst$self(),
			context.x(), context.y(), context.button()))
			cir.setReturnValue(true);
	}
	
	@Inject(at = @At("HEAD"), method = "removed()V")
	private void wurst$onRemoved(CallbackInfo ci)
	{
		SlotHighlighter.INSTANCE.clearActive();
		
		NbtSizeCounterHack nbtSize = wurst$nbtSizeCounter();
		if(nbtSize != null)
			nbtSize.onContainerScreenClosed(wurst$self());
	}
	
	@Inject(at = @At("TAIL"),
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
	private void wurst$renderOverlays(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		wurst$drawSlotHighlight(context, mouseX, mouseY);
		
		EnchantmentHandlerHack enchantHack = wurst$enchantHandler();
		if(enchantHack != null)
			enchantHack.renderOnHandledScreen(wurst$self(), context,
				partialTicks);
		
		NbtSizeCounterHack nbtSize = wurst$nbtSizeCounter();
		if(nbtSize != null)
			nbtSize.renderOnHandledScreen(wurst$self(), context);
		
		if(WurstClient.INSTANCE.isEnabled())
			WurstClient.INSTANCE.getHud().getChestSearchMousePreview()
				.render(context);
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z",
		cancellable = true)
	private void wurst$onMouseDragged(MouseButtonEvent context, double dragX,
		double dragY, CallbackInfoReturnable<Boolean> cir)
	{
		NbtSizeCounterHack nbtSize = wurst$nbtSizeCounter();
		if(nbtSize != null && nbtSize.handleMouseDrag(context))
			cir.setReturnValue(true);
	}
	
	/**
	 * Fills the slot that ChestSearch or EnchantmentHandler points at (the
	 * item you searched for, or the one picked in the overlay).
	 */
	private void wurst$drawSlotHighlight(GuiGraphicsExtractor context,
		int mouseX, int mouseY)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		int slotIndex = -1;
		int color = 0;
		
		EnchantmentHandlerHack ench = wurst$enchantHandler();
		if(ench != null && ench.isSlotHighlightEnabled()
			&& SlotHighlighter.INSTANCE.isEnchantmentHandlerActive())
		{
			slotIndex = SlotHighlighter.INSTANCE.getEnchantmentHandlerSlot();
			color = ench.getSlotHighlightColor();
		}
		
		// a remembered ChestSearch selection wins while its chest is open
		ChestSearchHack chestSearch =
			WurstClient.INSTANCE.getHax().chestSearchHack;
		if(chestSearch.isSlotHighlightEnabled()
			&& SlotHighlighter.INSTANCE.isChestSearchActive())
		{
			slotIndex = SlotHighlighter.INSTANCE.getChestSearchSlot();
			color = chestSearch.getSlotHighlightColorARGB();
		}
		
		Slot slot = wurst$findSlot(slotIndex);
		if(slot == null)
			return;
		
		int x = leftPos + slot.x;
		int y = topPos + slot.y;
		context.fill(x, y, x + 16, y + 16, color);
		
		if(mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16)
			wurst$forgetHighlight();
	}
	
	/** The slot by menu index, or else by its index in its container. */
	private Slot wurst$findSlot(int index)
	{
		if(index < 0)
			return null;
		
		if(index < menu.slots.size())
			return menu.slots.get(index);
		
		for(Slot slot : menu.slots)
			if(slot.getContainerSlot() == index)
				return slot;
			
		return null;
	}
	
	private void wurst$forgetHighlightIfHovered(double mouseX, double mouseY)
	{
		int index = SlotHighlighter.INSTANCE.getChestSearchSlot();
		if(index < 0)
			index = SlotHighlighter.INSTANCE.getEnchantmentHandlerSlot();
		
		Slot slot = wurst$findSlot(index);
		if(slot == null)
			return;
		
		int x = leftPos + slot.x;
		int y = topPos + slot.y;
		if(mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16)
			wurst$forgetHighlight();
	}
	
	private static void wurst$forgetHighlight()
	{
		SlotHighlighter highlighter = SlotHighlighter.INSTANCE;
		if(highlighter.isChestSearchActive())
		{
			ChestSearchScreen.forgetSlotSelection(
				highlighter.getChestSearchDimension(),
				highlighter.getChestSearchPos(),
				highlighter.getChestSearchSlot());
			highlighter.forgetChestSearchSelection();
			
		}else if(highlighter.isStickyActive())
			highlighter.clearEnchantmentHandlerActive();
	}
}
