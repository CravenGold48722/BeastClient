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
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

/**
 * Join, chunk and block-update hooks of the features ported from the CevAPI
 * fork (InstantCommand, JoinDrop, NewerNewChunks). All at TAIL/RETURN, so
 * they run once, on the client thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class PortedPacketListenerHooksMixin
{
	@Inject(
		method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
		at = @At("TAIL"))
	private void wurst$onJoin(ClientboundLoginPacket packet, CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		hax.instantCommandHack.onServerJoin();
		hax.joinDropHack.onServerJoin();
	}
	
	@Inject(
		method = "handleLevelChunkWithLight(Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;)V",
		at = @At("RETURN"))
	private void wurst$onChunk(ClientboundLevelChunkWithLightPacket packet,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().newerNewChunksHack
			.onChunkDataPacket(packet.getX(), packet.getZ());
	}
	
	@Inject(
		method = "handleBlockUpdate(Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;)V",
		at = @At("TAIL"))
	private void wurst$onBlockUpdate(ClientboundBlockUpdatePacket packet,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().newerNewChunksHack
			.afterUpdateBlock(packet.getPos());
	}
	
	@Inject(
		method = "handleChunkBlocksUpdate(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V",
		at = @At("TAIL"))
	private void wurst$onSectionUpdate(
		ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci)
	{
		packet.runUpdates(
			(pos, state) -> WurstClient.INSTANCE.getHax().newerNewChunksHack
				.afterChunkDeltaUpdate(pos, state));
	}
}
