/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.BetterTabHack;

/** BetterTab's hooks (idea from Meteor Client). */
@Mixin(PlayerTabOverlay.class)
public abstract class BetterTabMixin
{
	@Shadow
	protected abstract List<PlayerInfo> getPlayerInfos();
	
	private static BetterTabHack wurst$betterTab()
	{
		return WurstClient.INSTANCE.getHax().betterTabHack;
	}
	
	@ModifyConstant(method = "getPlayerInfos()Ljava/util/List;",
		constant = @Constant(longValue = 80L))
	private long wurst$size(long vanilla)
	{
		return wurst$betterTab().getSize(vanilla);
	}
	
	@Inject(at = @At("RETURN"),
		method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
		cancellable = true)
	private void wurst$name(PlayerInfo info,
		CallbackInfoReturnable<Component> cir)
	{
		cir.setReturnValue(
			wurst$betterTab().getName(info, cir.getReturnValue()));
	}
	
	@ModifyArg(method = "extractRenderState",
		at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"),
		index = 0)
	private int wurst$widerForPing(int width)
	{
		return wurst$betterTab().isExactPing() ? width + 30 : width;
	}
	
	@Inject(method = "extractRenderState",
		at = @At(value = "INVOKE",
			target = "Ljava/lang/Math;min(II)I",
			shift = At.Shift.BEFORE))
	private void wurst$columns(CallbackInfo ci,
		@Local(name = "rows") LocalIntRef rows,
		@Local(name = "cols") LocalIntRef cols)
	{
		BetterTabHack betterTab = wurst$betterTab();
		if(!betterTab.isEnabled())
			return;
		
		int total = getPlayerInfos().size();
		int newRows = total;
		int newCols = 1;
		while(newRows > betterTab.getColumnHeight())
		{
			newCols++;
			newRows = (total + newCols - 1) / newCols;
		}
		
		rows.set(newRows);
		cols.set(newCols);
	}
	
	@Inject(at = @At("HEAD"),
		method = "extractPingIcon(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIILnet/minecraft/client/multiplayer/PlayerInfo;)V",
		cancellable = true)
	private void wurst$exactPing(GuiGraphicsExtractor graphics, int slotWidth,
		int xo, int yo, PlayerInfo info, CallbackInfo ci)
	{
		if(!wurst$betterTab().isExactPing())
			return;
		
		Font font = Minecraft.getInstance().font;
		int latency = Mth.clamp(info.getLatency(), 0, 9999);
		int color = latency < 150 ? 0xFF00E970
			: latency < 300 ? 0xFFE7D020 : 0xFFD74238;
		String text = latency + "ms";
		graphics.text(font, text, xo + slotWidth - font.width(text), yo, color);
		ci.cancel();
	}
}
