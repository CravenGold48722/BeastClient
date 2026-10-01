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

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.wurstclient.util.KeyPresser;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends ClientInput
{
	@Inject(method = "tick()V", at = @At("HEAD"))
	private void onTickHead(CallbackInfo ci)
	{
		KeyPresser.beforeInputRead();
	}
	
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void onTickTail(CallbackInfo ci)
	{
		KeyPresser.afterInputRead();
	}
}
