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

import net.cevapi.security.ResourcePackProtector;
import net.minecraft.client.gui.Gui;

/**
 * Shows Anti-Fingerprint's queued toasts (ported from the CevAPI fork) on
 * the client thread, also while joining (no player to tick yet).
 */
@Mixin(Gui.class)
public abstract class ResourcePackToastsMixin
{
	@Inject(at = @At("TAIL"), method = "tick(Z)V")
	private void wurst$flushToasts(boolean paused, CallbackInfo ci)
	{
		ResourcePackProtector.flushToasts();
	}
}
