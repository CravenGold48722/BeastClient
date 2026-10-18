/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.renderadjust;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.RenderAdjustHack;

/**
 * RenderAdjust's fog color and render distance fog.
 */
@Mixin(FogRenderer.class)
public class RenderAdjustFogRendererMixin
{
	@Inject(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
			opcode = Opcodes.PUTFIELD,
			shift = At.Shift.AFTER))
	private void onSetupFog(Camera camera, int renderDistanceInChunks,
		DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
		CallbackInfoReturnable<FogData> cir, @Local FogData fog)
	{
		RenderAdjustHack renderAdjust =
			WurstClient.INSTANCE.getHax().renderAdjustHack;
		if(renderAdjust.shouldAdjustFogColor())
			fog.color.set(renderAdjust.getFogColor(fog.color));
		
		if(!renderAdjust.shouldDisableFog())
			return;
		
		fog.renderDistanceStart = 1000000;
		fog.renderDistanceEnd = 1000000;
	}
}
