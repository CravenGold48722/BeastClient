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

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.hud.ClientMessageOverlay;

/**
 * ClientChatOverlay (ported from the CevAPI fork): client messages it takes
 * go to its own panel instead of the chat. Does nothing while the hack is
 * off (the overlay's capture methods check that).
 */
@Mixin(ChatComponent.class)
public abstract class ClientChatOverlayMixin
{
	@Inject(at = @At("HEAD"),
		method = "addClientSystemMessage(Lnet/minecraft/network/chat/Component;)V",
		cancellable = true)
	private void wurst$captureClientMessage(Component message, CallbackInfo ci)
	{
		ClientMessageOverlay overlay = ClientMessageOverlay.getInstance();
		// always consumed, so the set of marked messages can't grow
		boolean beastMessage = overlay.consumeWurstClientMessage(message);
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		boolean captured =
			beastMessage ? overlay.captureWurstClientMessage(message)
				: overlay.captureSingleArgMessage(message);
		if(captured)
			ci.cancel();
	}
}
