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

import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.wurstclient.WurstClient;

/**
 * EntityControl: a ridden horse follows your controls even when untamed.
 */
@Mixin(AbstractHorse.class)
public abstract class EntityControlHorseMixin
{
	@Inject(method = "isMobControlled()Z", at = @At("HEAD"), cancellable = true)
	private void forceMobControlled(CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.getHax().entityControlHack
			.shouldEnforceMobControlled())
			cir.setReturnValue(true);
	}
}
