/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.renderadjust;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.RenderAdjustHack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * RenderAdjust's sky: hides it or recolors it.
 */
@Mixin(LevelRenderer.class)
public class RenderAdjustLevelRendererMixin
{
	@Shadow
	@Final
	private LevelRenderState levelRenderState;
	
	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void onAddSkyPass(FrameGraphBuilder frameGraphBuilder,
		CameraRenderState cameraState, GpuBufferSlice fogBuffer,
		CallbackInfo ci)
	{
		RenderAdjustHack renderAdjust =
			WurstClient.INSTANCE.getHax().renderAdjustHack;
		if(renderAdjust.shouldDisableSky())
		{
			ci.cancel();
			return;
		}
		
		if(!renderAdjust.shouldAdjustSkyColor())
			return;
		
		// 26.1.2 keeps the sky color as an int, 26.3 as a vector
		int rgb = levelRenderState.skyRenderState.skyColor;
		Vector3fc mixed =
			renderAdjust.applySkyColor(new Vector3f((rgb >> 16 & 0xFF) / 255F,
				(rgb >> 8 & 0xFF) / 255F, (rgb & 0xFF) / 255F));
		levelRenderState.skyRenderState.skyColor =
			rgb & 0xFF000000 | channel(mixed.x()) << 16
				| channel(mixed.y()) << 8 | channel(mixed.z());
	}
	
	private static int channel(float value)
	{
		return Math.round(Math.clamp(value, 0F, 1F) * 255F);
	}
}
