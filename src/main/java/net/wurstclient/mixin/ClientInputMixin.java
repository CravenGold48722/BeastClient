/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.wurstclient.hacks.AutoTotemHack;

@Mixin(ClientInput.class)
public class ClientInputMixin
{
	@Shadow
	protected Vec2 moveVector;
	
	/**
	 * Blanks the whole input state while AutoTotem is putting a totem back in
	 * your offhand, so nothing you are holding down survives the swap.
	 *
	 * <p>
	 * This runs after vanilla has read the keyboard, and overwrites the result
	 * rather than the keys themselves, which means movement, jumping,
	 * sneaking and sprinting all go away for those ticks without the key
	 * bindings being disturbed.
	 */
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void onTick(CallbackInfo ci)
	{
		if(!AutoTotemHack.isInputFrozen())
			return;
		
		ClientInput input = (ClientInput)(Object)this;
		input.keyPresses = Input.EMPTY;
		moveVector = Vec2.ZERO;
	}
}
