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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;

/**
 * VanillaSpoof: the anvil fills its name box from the item's name and can
 * send that text back in a rename packet. If a server named the item with a
 * mod translation key, a modded client would send the translated text and
 * give itself away; resolve it the way a vanilla client would instead.
 */
@Mixin(AnvilScreen.class)
public class AnvilScreenMixin
{
	@WrapOperation(method = {
		"slotChanged(Lnet/minecraft/world/inventory/AbstractContainerMenu;ILnet/minecraft/world/item/ItemStack;)V",
		"onNameChanged(Ljava/lang/String;)V"},
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
	private String wrapGetString(Component component,
		Operation<String> original)
	{
		return WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf
			.withVanillaTranslations(() -> original.call(component));
	}
}
