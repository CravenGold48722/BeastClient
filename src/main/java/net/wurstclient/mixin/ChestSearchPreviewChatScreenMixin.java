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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.WurstClient;
import net.wurstclient.hud.ChestSearchMousePreview;

/**
 * ChestSearch's chest preview stays visible, and can be dragged, while the
 * chat is open (the in-game HUD doesn't draw it then).
 */
@Mixin(ChatScreen.class)
public abstract class ChestSearchPreviewChatScreenMixin
{
	private static ChestSearchMousePreview wurst$preview()
	{
		return WurstClient.INSTANCE.isEnabled()
			? WurstClient.INSTANCE.getHud().getChestSearchMousePreview() : null;
	}
	
	@Inject(at = @At("TAIL"),
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
	private void wurst$renderPreview(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		ChestSearchMousePreview preview = wurst$preview();
		if(preview != null)
			preview.render(context);
	}
	
	// ChatScreen has no mouseReleased of its own; the preview notices the
	// button going up while rendering.
	
	@Inject(at = @At("HEAD"),
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		cancellable = true)
	private void wurst$clickPreview(MouseButtonEvent context,
		boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
	{
		ChestSearchMousePreview preview = wurst$preview();
		if(preview != null && preview.handleMouseClick(context.x(), context.y(),
			context.button()))
			cir.setReturnValue(true);
	}
}
