/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.ClickGuiScreen;

/**
 * Blur: vanilla's menu blur in the background stratum of in-game screens
 * (which vanilla only gives a dark tint).
 */
@Mixin(Screen.class)
public abstract class BlurMixin
{
	@Shadow
	protected abstract void extractBlurredBackground(
		GuiGraphicsExtractor graphics);
	
	@Shadow
	public abstract boolean isInGameUi();
	
	@Inject(
		method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
	private void wurst$blur(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
			
		// vanilla blurs the other screens itself; the ClickGUI draws no
		// background at all
		Screen screen = (Screen)(Object)this;
		if(!isInGameUi() && !(screen instanceof ClickGuiScreen))
			return;
		
		if(WurstClient.INSTANCE.getHax().blurHack.shouldBlur(screen))
			extractBlurredBackground(graphics);
	}
}
