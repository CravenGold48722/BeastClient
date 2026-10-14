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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.wurstclient.util.LastServerRememberer;

/**
 * Every join goes through here - the server list and direct connect, but
 * also Quick Play from the launcher, Reconnect buttons and other mods. Only
 * the first two used to be remembered, and .sv then crashed.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin
{
	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void onStartConnecting(Screen parent, Minecraft mc,
		ServerAddress address, ServerData serverData, boolean quickPlay,
		TransferState transferState, CallbackInfo ci)
	{
		if(serverData != null)
			LastServerRememberer.setLastServer(serverData);
	}
}
