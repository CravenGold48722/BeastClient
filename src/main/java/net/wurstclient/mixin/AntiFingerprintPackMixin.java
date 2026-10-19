/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.cevapi.security.ResourcePackProtector;
import net.cevapi.security.ResourcePackProtector.PolicyResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.wurstclient.WurstClient;

/**
 * Anti-Fingerprint (ported from the CevAPI fork): decides what happens to a
 * server's resource pack before vanilla does. Injected after vanilla's
 * thread check, so it runs once, on the client thread.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class AntiFingerprintPackMixin
{
	@Shadow
	@Final
	protected Minecraft minecraft;
	
	@Shadow
	@Final
	protected Connection connection;
	
	@Inject(
		method = "handleResourcePackPush(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
			shift = At.Shift.AFTER),
		cancellable = true)
	private void wurst$onResourcePackPush(
		ClientboundResourcePackPushPacket packet, CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		try
		{
			PolicyResult result = ResourcePackProtector.evaluate(packet);
			if(ResourcePackProtector.applyDecision(result, connection,
				minecraft))
				ci.cancel();
			
		}catch(RuntimeException e)
		{
			e.printStackTrace();
		}
	}
}
