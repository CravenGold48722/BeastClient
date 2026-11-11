/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ItemPhysicsHack;

/** ItemPhysics: replaces the hover and the spin of dropped items. */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemPhysicsMixin
{
	private static final String SUBMIT =
		"submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";
	
	private static ItemPhysicsHack wurst$itemPhysics()
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return null;
		
		ItemPhysicsHack hack = WurstClient.INSTANCE.getHax().itemPhysicsHack;
		return hack.isEnabled() ? hack : null;
	}
	
	@WrapOperation(method = SUBMIT,
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V",
			ordinal = 0))
	private void wurst$groundInsteadOfHover(PoseStack pose, float x, float y,
		float z, Operation<Void> original,
		@Local(argsOnly = true) ItemEntityRenderState state)
	{
		ItemPhysicsHack hack = wurst$itemPhysics();
		if(hack == null)
			original.call(pose, x, y, z);
		else
			hack.translate(pose, state);
	}
	
	@WrapOperation(method = SUBMIT,
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V",
			ordinal = 0))
	private void wurst$layDownInsteadOfSpin(PoseStack pose,
		Quaternionfc rotation, Operation<Void> original,
		@Local(argsOnly = true) ItemEntityRenderState state)
	{
		ItemPhysicsHack hack = wurst$itemPhysics();
		if(hack == null)
			original.call(pose, rotation);
		else
			hack.rotate(pose, state);
	}
}
