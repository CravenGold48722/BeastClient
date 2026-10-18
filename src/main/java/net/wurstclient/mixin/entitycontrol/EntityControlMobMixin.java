/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.entitycontrol;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Mob;
import net.wurstclient.WurstClient;

/**
 * EntityControl: any mob counts as saddled, so it can be steered.
 */
@Mixin(Mob.class)
public abstract class EntityControlMobMixin
{
	@Inject(method = "isSaddled()Z", at = @At("HEAD"), cancellable = true)
	private void forceSaddled(CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.getHax().entityControlHack
			.shouldEnforceSaddled())
			cir.setReturnValue(true);
	}
}
