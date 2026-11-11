/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Iterator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;

/** BossStack's hooks (idea from Meteor Client). */
@Mixin(BossHealthOverlay.class)
public abstract class BossStackMixin
{
	@ModifyExpressionValue(method = "extractRenderState",
		at = @At(value = "INVOKE",
			target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
	private Iterator<LerpingBossEvent> wurst$stackBars(
		Iterator<LerpingBossEvent> original)
	{
		return WurstClient.INSTANCE.getHax().bossStackHack.filterBars(original);
	}
	
	@ModifyExpressionValue(method = "extractRenderState",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
	private Component wurst$barName(Component original,
		@Local LerpingBossEvent bar)
	{
		return WurstClient.INSTANCE.getHax().bossStackHack.getBarName(bar,
			original);
	}
	
	@ModifyConstant(method = "extractRenderState",
		constant = @Constant(intValue = 9, ordinal = 1))
	private int wurst$barSpacing(int vanilla)
	{
		return WurstClient.INSTANCE.getHax().bossStackHack.getSpacing(vanilla);
	}
}
