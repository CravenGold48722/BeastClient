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

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.wurstclient.WurstClient;
import net.wurstclient.util.ConnectionLogOverlay;

/**
 * ConnectionLogOverlay (ported from the CevAPI fork): the latest log lines
 * on the connecting and disconnected screens. Draws nothing while its
 * setting is off.
 */
@Mixin(Screen.class)
public abstract class ConnectionLogScreenMixin
{
	@Inject(at = @At("TAIL"),
		method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
	private void wurst$renderConnectionLog(GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float partialTicks, CallbackInfo ci)
	{
		Object screen = this;
		if(!(screen instanceof ConnectScreen)
			&& !(screen instanceof DisconnectedScreen))
			return;
		
		if(WurstClient.INSTANCE.isEnabled())
			ConnectionLogOverlay.getInstance().render(graphics);
	}
}
