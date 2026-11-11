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

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.wurstclient.WurstClient;

/** Hides vanilla's block outline while BlockSelection draws its own. */
@Mixin(LevelRenderer.class)
public abstract class BlockSelectionMixin
{
	@Inject(at = @At("HEAD"),
		method = "renderBlockOutline(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;ZLnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
		cancellable = true)
	private void wurst$hideOutline(MultiBufferSource.BufferSource buffers,
		PoseStack poseStack, boolean translucent, LevelRenderState state,
		CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.isEnabled()
			&& WurstClient.INSTANCE.getHax().blockSelectionHack.isEnabled())
			ci.cancel();
	}
}
