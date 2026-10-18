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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.FunCreepersHack;

/**
 * FunCreepers' party explosions. Injected after the packet has been handed
 * over to the client thread, so it runs once and on the right thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class FunCreepersClientPacketListenerMixin
{
	private static final String HANDLE_EXPLOSION =
		"handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V";
	
	@Inject(method = HANDLE_EXPLOSION,
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
			shift = At.Shift.AFTER))
	private void wurst$funCreepersParticles(ClientboundExplodePacket packet,
		CallbackInfo ci)
	{
		Vec3 center = packet.center();
		FunCreepersHack funCreepers =
			WurstClient.INSTANCE.getHax().funCreepersHack;
		if(funCreepers.shouldPartyifyExplosion(center))
			funCreepers.spawnPartyEffects(center);
	}
	
	@Redirect(method = HANDLE_EXPLOSION,
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
	private void wurst$funCreepersSound(ClientLevel level, double x, double y,
		double z, SoundEvent sound, SoundSource source, float volume,
		float pitch, boolean useDistanceDelay, ClientboundExplodePacket packet)
	{
		FunCreepersHack funCreepers =
			WurstClient.INSTANCE.getHax().funCreepersHack;
		if(funCreepers.shouldPartyifyExplosion(packet.center()))
		{
			SoundEvent replacement = funCreepers.getReplacementExplosionSound();
			if(replacement != null)
			{
				level.playLocalSound(x, y, z, replacement,
					funCreepers.getReplacementSoundSource(),
					funCreepers.getReplacementVolume(),
					funCreepers.getReplacementPitch(), useDistanceDelay);
				return;
			}
		}
		
		level.playLocalSound(x, y, z, sound, source, volume, pitch,
			useDistanceDelay);
	}
}
