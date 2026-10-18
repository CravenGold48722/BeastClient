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

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ViewmodelHack;

/**
 * Viewmodel's hooks. The CevAPI fork hooks 26.3's
 * FirstPersonHandsAndItemsRenderer; in 26.1.2 the same steps are in
 * ItemInHandRenderer.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ViewmodelItemInHandRendererMixin
{
	private static final String RENDER_ARM_WITH_ITEM =
		"renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";
	private static final String RENDER_MAP_HAND =
		"renderMapHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;)V";
	private static final String RENDER_PLAYER_ARM =
		"renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;)V";
	private static final String RENDER_ONE_HANDED_MAP =
		"renderOneHandedMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFLnet/minecraft/world/entity/HumanoidArm;FLnet/minecraft/world/item/ItemStack;)V";
	
	@Inject(method = RENDER_ARM_WITH_ITEM, at = @At("HEAD"), cancellable = true)
	private void onRenderArmWithItem(AbstractClientPlayer player,
		float partialTicks, float pitch, InteractionHand hand,
		float swingProgress, ItemStack stack, float equipProgress,
		PoseStack matrices, SubmitNodeCollector collector, int light,
		CallbackInfo ci)
	{
		if(viewmodel().shouldHide(getArm(player, hand)))
			ci.cancel();
	}
	
	@Inject(method = RENDER_ARM_WITH_ITEM,
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
	private void onRenderItem(AbstractClientPlayer player, float partialTicks,
		float pitch, InteractionHand hand, float swingProgress, ItemStack stack,
		float equipProgress, PoseStack matrices, SubmitNodeCollector collector,
		int light, CallbackInfo ci)
	{
		viewmodel().applyTransform(getArm(player, hand), matrices);
	}
	
	@Inject(method = RENDER_MAP_HAND, at = @At("HEAD"), cancellable = true)
	private void onRenderMapHand(PoseStack matrices,
		SubmitNodeCollector collector, int light, HumanoidArm arm,
		CallbackInfo ci)
	{
		if(viewmodel().shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(method = RENDER_MAP_HAND,
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
			shift = At.Shift.AFTER))
	private void onRenderMapHandPush(PoseStack matrices,
		SubmitNodeCollector collector, int light, HumanoidArm arm,
		CallbackInfo ci)
	{
		viewmodel().applyTransform(arm, matrices);
	}
	
	@Inject(method = RENDER_PLAYER_ARM, at = @At("HEAD"), cancellable = true)
	private void onRenderPlayerArmHead(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		float swingProgress, HumanoidArm arm, CallbackInfo ci)
	{
		if(viewmodel().shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(method = RENDER_PLAYER_ARM,
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getPlayerRenderer(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;"))
	private void onRenderPlayerArm(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		float swingProgress, HumanoidArm arm, CallbackInfo ci)
	{
		viewmodel().applyTransform(arm, matrices);
	}
	
	@Inject(method = RENDER_ONE_HANDED_MAP,
		at = @At("HEAD"),
		cancellable = true)
	private void onRenderOneHandedMap(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		HumanoidArm arm, float swingProgress, ItemStack stack, CallbackInfo ci)
	{
		if(viewmodel().shouldHide(arm))
			ci.cancel();
	}
	
	@Inject(method = RENDER_ONE_HANDED_MAP,
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderMap(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/item/ItemStack;)V"))
	private void onRenderOneHandedMapItem(PoseStack matrices,
		SubmitNodeCollector collector, int light, float equippedProgress,
		HumanoidArm arm, float swingProgress, ItemStack stack, CallbackInfo ci)
	{
		viewmodel().applyTransform(arm, matrices);
	}
	
	private static ViewmodelHack viewmodel()
	{
		return WurstClient.INSTANCE.getHax().viewmodelHack;
	}
	
	private static HumanoidArm getArm(AbstractClientPlayer player,
		InteractionHand hand)
	{
		HumanoidArm mainArm = player.getMainArm();
		return hand == InteractionHand.MAIN_HAND ? mainArm
			: mainArm.getOpposite();
	}
}
