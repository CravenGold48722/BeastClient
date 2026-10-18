/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.AntiDropHack;
import net.wurstclient.hacks.DuraSwapHack;
import net.wurstclient.hacks.SilkOnlyHack;

/**
 * Mining and inventory hooks of SilkOnly, DuraSwap and AntiDrop (ported from
 * the CevAPI fork).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class BlockAndItemHooksGameModeMixin
{
	private static final String START_PREDICTION =
		"Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V";
	
	@Unique
	private boolean antiDropBypassingPlacement;
	
	// ── SilkOnly ─────────────────────────────────────────────────────────
	
	@Inject(at = @At("HEAD"),
		method = {
			"startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
			"continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"},
		cancellable = true)
	private void wurst$silkOnly(BlockPos pos, Direction direction,
		CallbackInfoReturnable<Boolean> cir)
	{
		Minecraft mc = Minecraft.getInstance();
		if(pos == null || mc.player == null || mc.level == null
			|| !WurstClient.INSTANCE.isEnabled())
			return;
		
		SilkOnlyHack silkOnly = WurstClient.INSTANCE.getHax().silkOnlyHack;
		if(silkOnly.isEnabled() && silkOnly.shouldRefuseBreak(pos,
			mc.level.getBlockState(pos), mc.player.getMainHandItem()))
			cir.setReturnValue(false);
	}
	
	// ── DuraSwap ─────────────────────────────────────────────────────────
	
	@Inject(
		method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
		at = @At(value = "INVOKE", target = START_PREDICTION, ordinal = 1))
	private void wurst$duraSwapBeforeInstaBreak(BlockPos pos,
		Direction direction, CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.isEnabled())
			duraSwap().onBeforeInstaBreakPacket(pos);
	}
	
	@Inject(
		method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
		at = @At(value = "INVOKE", target = START_PREDICTION, ordinal = 1))
	private void wurst$duraSwapBeforeBreak(BlockPos pos, Direction direction,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.isEnabled())
			duraSwap().onBeforeBreakPacket(pos);
	}
	
	@Inject(method = {
		"startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
		"continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"},
		at = @At(value = "INVOKE",
			target = START_PREDICTION,
			ordinal = 1,
			shift = At.Shift.AFTER))
	private void wurst$duraSwapAfterBreak(BlockPos pos, Direction direction,
		CallbackInfoReturnable<Boolean> cir)
	{
		duraSwap().onAfterBreakPacket();
	}
	
	private static DuraSwapHack duraSwap()
	{
		return WurstClient.INSTANCE.getHax().duraSwapHack;
	}
	
	// ── AntiDrop ─────────────────────────────────────────────────────────
	
	@Inject(at = @At("HEAD"),
		method = "handleContainerInput(IIILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
		cancellable = true)
	private void wurst$antiDropThrow(int syncId, int slotId, int button,
		ContainerInput input, Player player, CallbackInfo ci)
	{
		if(input != ContainerInput.THROW || !WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDrop.isEnabled())
			return;
		
		ItemStack stack = ItemStack.EMPTY;
		if(slotId == -999 && player.containerMenu != null
			&& player.containerMenu.containerId == syncId)
			stack = player.containerMenu.getCarried();
		else if(slotId >= 0)
		{
			if(player.containerMenu != null
				&& player.containerMenu.containerId == syncId
				&& slotId < player.containerMenu.slots.size())
			{
				Slot slot = player.containerMenu.getSlot(slotId);
				stack = slot.getItem();
				
			}else if(player.inventoryMenu.containerId == syncId
				&& slotId < player.inventoryMenu.slots.size())
				stack = player.inventoryMenu.getSlot(slotId).getItem();
		}
		
		if(antiDrop.shouldBlock(stack))
			ci.cancel();
	}
	
	/** Placing a protected block item isn't dropping it. */
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void wurst$antiDropPlacementStart(LocalPlayer player,
		InteractionHand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir)
	{
		if(player == null || !WurstClient.INSTANCE.isEnabled())
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		AntiDropHack antiDrop = hax.antiDropHack;
		ItemStack stack = player.getItemInHand(hand);
		if(!antiDrop.isEnabled() || !(stack.getItem() instanceof BlockItem)
			|| !antiDrop.isProtectedItem(stack))
			return;
		
		antiDrop.setTemporarilyBypass(true);
		antiDropBypassingPlacement = true;
	}
	
	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void wurst$antiDropPlacementEnd(LocalPlayer player,
		InteractionHand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir)
	{
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDropBypassingPlacement && !antiDrop.isTemporarilyBypassing())
			return;
		
		antiDropBypassingPlacement = false;
		antiDrop.setTemporarilyBypass(false);
	}
}
