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

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.wurstclient.WurstClient;

/**
 * EntityControl: ridden mobs jump at least as high as a good horse. In
 * 26.1.2 mobs and horses get their jump power from LivingEntity.
 */
@Mixin(LivingEntity.class)
public abstract class EntityControlJumpMixin
{
	@Inject(method = {"getJumpPower()F", "getJumpPower(F)F"},
		at = @At("RETURN"),
		cancellable = true)
	private void forceJumpStrength(CallbackInfoReturnable<Float> cir)
	{
		if(!((Object)this instanceof Mob)
			|| !WurstClient.INSTANCE.getHax().entityControlHack
				.shouldEnforceJumpStrength())
			return;
		
		cir.setReturnValue(Math.max(cir.getReturnValueF(), 0.7F));
	}
}
