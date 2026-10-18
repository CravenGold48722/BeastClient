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

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.wurstclient.WurstClient;

/**
 * Tells ItemHandler (ported from the CevAPI fork) which items got picked up.
 */
@Mixin(ClientPacketListener.class)
public abstract class ItemHandlerPacketListenerMixin
{
	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void wurst$onTakeItemEntity(ClientboundTakeItemEntityPacket packet,
		CallbackInfo ci)
	{
		// the handler runs again on the client thread after the netty one
		if(WurstClient.MC.isSameThread())
			WurstClient.INSTANCE.getHax().itemHandlerHack.onItemPickup(packet);
	}
}
