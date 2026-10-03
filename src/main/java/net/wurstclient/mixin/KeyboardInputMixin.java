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
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ScaffoldWalkHack;
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
		
		// ScaffoldWalk's godbridge: your keys keep meaning the direction you
		// were facing, turned into the keys that walk that way now (S plus
		// A or D) - also what the server sees in the input packet.
		WurstClient wurstClient = WurstClient.INSTANCE;
		if(wurstClient.isEnabled() && wurstClient.getHax() != null)
		{
			Input remapped =
				wurstClient.getHax().scaffoldWalkHack.modifyInput(keyPresses);
			if(remapped != null)
			{
				keyPresses = remapped;
				moveVector = ScaffoldWalkHack.moveVectorOf(remapped);
			}
		}
		
		// While the inventory that InventoryOpener opened for a hack's clicks
		// is up, there is no movement input at all - just like after pressing
		// E. Otherwise a hack that holds keys itself (Follow, FightBot, path
		// walking, ...) would keep you walking with the inventory open, which
		// is exactly what anti-cheats look for while you click. InvWalk is the
		// one exception, since moving in the inventory is its whole point.
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst.getInventoryOpener() == null
			|| !wurst.getInventoryOpener().isOpenedByUs())
			return;
		
		if(wurst.isEnabled() && wurst.getHax().invWalkHack.isEnabled())
			return;
		
		keyPresses = Input.EMPTY;
		moveVector = Vec2.ZERO;
	}
}
