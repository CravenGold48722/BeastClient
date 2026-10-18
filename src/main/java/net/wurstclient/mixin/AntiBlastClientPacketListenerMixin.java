/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AntiBlastHack;

/**
 * AntiBlast: replaces the knockback an explosion packet gives the player.
 */
@Mixin(ClientPacketListener.class)
public abstract class AntiBlastClientPacketListenerMixin
{
	@Inject(
		method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
		at = @At(value = "INVOKE",
			target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"),
		cancellable = true)
	private void wurst$antiBlastKnockback(ClientboundExplodePacket packet,
		CallbackInfo ci)
	{
		AntiBlastHack antiBlast = WurstClient.INSTANCE.getHax().antiBlastHack;
		LocalPlayer player = Minecraft.getInstance().player;
		if(!antiBlast.isEnabled() || player == null)
			return;
		
		Optional<Vec3> knockback = packet.playerKnockback();
		if(knockback.isPresent())
		{
			Vec3 vec = knockback.get();
			Vec3 adjusted = antiBlast.modifyKnockback(vec.x, vec.y, vec.z);
			player.push(adjusted.x, adjusted.y, adjusted.z);
		}
		
		ci.cancel();
	}
}
