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

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.wurstclient.WurstClient;

/**
 * Tells the features ported from the CevAPI fork that the connection to the
 * server ended, and why.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class DisconnectHooksMixin
{
	@Inject(at = @At("TAIL"),
		method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V")
	private void wurst$onDisconnect(DisconnectionDetails details,
		CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax() == null)
			return;
		
		WurstClient.INSTANCE.getHax().kickForensicsHack
			.onDisconnected(details.reason());
		WurstClient.INSTANCE.getHax().webhookAlertHack
			.onDisconnected(details.reason());
	}
}
