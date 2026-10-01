/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import net.wurstclient.WurstClient;
import net.wurstclient.other_feature.OtfList;

/**
 * VanillaSpoof: while text is being turned into something that goes back to
 * the server, a keybind that only a mod registered resolves the way vanilla
 * resolves an unknown keybind - to its (untranslated) name - instead of the
 * key it is bound to.
 */
@Mixin(KeybindContents.class)
public class KeybindContentsMixin
{
	@Shadow
	@Final
	private String name;
	
	@Inject(
		method = "getNestedComponent()Lnet/minecraft/network/chat/Component;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetNestedComponent(CallbackInfoReturnable<Component> cir)
	{
		// can run before Wurst has finished starting up
		OtfList otfs = WurstClient.INSTANCE.getOtfs();
		if(otfs != null && otfs.vanillaSpoofOtf.shouldHideKeybind(name))
			cir.setReturnValue(Component.translatable(name));
	}
}
