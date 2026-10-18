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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.PearlLauncherHack;

/**
 * PearlLauncher gets ready right before a pearl is thrown.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class PearlLauncherGameModeMixin
{
	@Inject(
		method = "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
		at = @At("HEAD"))
	private void wurst$pearlLauncherUseItem(Player player, InteractionHand hand,
		CallbackInfoReturnable<InteractionResult> cir)
	{
		if(!(player instanceof LocalPlayer localPlayer)
			|| !WurstClient.INSTANCE.isEnabled())
			return;
		
		PearlLauncherHack pearlLauncher =
			WurstClient.INSTANCE.getHax().pearlLauncherHack;
		if(pearlLauncher.isEnabled())
			pearlLauncher.preparePearlUse(localPlayer, hand);
	}
}
