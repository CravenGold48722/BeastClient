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

import com.mojang.blaze3d.systems.RenderSystem;

import net.wurstclient.util.KeyPresser;

@Mixin(RenderSystem.class)
public class RenderSystemMixin
{
	/**
	 * Right after GLFW polled the OS for input: when a real key's events
	 * reach the game's callbacks, KeyPresser's go there too.
	 */
	@Inject(method = "pollEvents", at = @At("RETURN"))
	private static void onPollEvents(CallbackInfo ci)
	{
		KeyPresser.onEventsPolled();
	}
}
