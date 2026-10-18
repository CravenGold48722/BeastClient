/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.renderadjust;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.wurstclient.WurstClient;

/**
 * RenderAdjust's "no fog": removes the Overworld's foggy overlay (including
 * rain fog), the way NoFog does.
 */
@Mixin(AtmosphericFogEnvironment.class)
public class RenderAdjustAtmosphericFogMixin
{
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void onSetupFog(FogData data, Camera camera, ClientLevel world,
		float viewDistance, DeltaTracker tickCounter, CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.getHax().renderAdjustHack.shouldDisableFog())
			return;
		
		data.environmentalStart = 1000000;
		data.environmentalEnd = 1000000;
	}
}
