/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.wurstclient.gametest.PacketTrace;
import net.wurstclient.gametest.WurstTest;

/**
 * Logs why a connection ends, on either side. A disconnect in the middle of
 * a test is otherwise silent - and the integrated server, parked by the test
 * harness between ticks, never confirms shutting down, so the whole run
 * hangs with no hint why.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin
{
	@Shadow
	public abstract PacketFlow getReceiving();
	
	@Inject(
		method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
		at = @At("HEAD"))
	private void onDisconnect(DisconnectionDetails details, CallbackInfo ci)
	{
		WurstTest.LOGGER.warn("Connection ({}) disconnected: {}",
			getReceiving() == PacketFlow.CLIENTBOUND ? "client" : "server",
			details.reason().getString());
	}
	
	@Inject(method = "doSendPacket", at = @At("HEAD"))
	private void onDoSendPacket(Packet<?> packet,
		ChannelFutureListener listener, boolean flush, CallbackInfo ci)
	{
		if(getReceiving() == PacketFlow.CLIENTBOUND)
			PacketTrace.sent(packet);
	}
	
	@Inject(
		method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
		at = @At("HEAD"))
	private void onChannelRead0(ChannelHandlerContext context, Packet<?> packet,
		CallbackInfo ci)
	{
		if(getReceiving() == PacketFlow.CLIENTBOUND)
			PacketTrace.received(packet);
	}
	
	@Inject(method = "exceptionCaught", at = @At("HEAD"))
	private void onExceptionCaught(ChannelHandlerContext context,
		Throwable throwable, CallbackInfo ci)
	{
		WurstTest.LOGGER.error("Connection ({}) failed",
			getReceiving() == PacketFlow.CLIENTBOUND ? "client" : "server",
			throwable);
	}
}
