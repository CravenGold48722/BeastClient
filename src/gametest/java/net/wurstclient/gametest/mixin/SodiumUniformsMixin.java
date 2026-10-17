/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium (0.9.2) with Fabric's test screenshots: a screenshot draws one more
 * frame with GameRenderer.render alone, without the extract step - where
 * Sodium's setupTerrain marks its terrain uniforms for a fresh write
 * (prepareFrame). Its endFrame had already dropped the last ones, so the
 * screenshot frame threw "Global terrain uniforms have not been updated".
 * Marking them for a fresh write at the end of every frame too lets that
 * extra frame write its own; normal frames write them once, as before.
 * Without Sodium (-PnoPerfMods) there's nothing to mix into.
 */
@Pseudo
@Mixin(
	targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager",
	remap = false)
public abstract class SodiumUniformsMixin
{
	@Shadow
	private boolean hasUpdatedThisFrame;
	
	@Inject(method = "endFrame", at = @At("TAIL"), require = 0)
	private void onEndFrame(CallbackInfo ci)
	{
		hasUpdatedThisFrame = false;
	}
}
