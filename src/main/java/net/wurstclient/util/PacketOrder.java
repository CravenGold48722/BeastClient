/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.player.Input;

/**
 * Keeps hotbar slot changes in the order a real client sends them.
 *
 * <p>
 * Vanilla only ever changes the held slot <i>before</i> attacking or using
 * within a tick (hotbar keys are handled first, and attack/use send any
 * pending slot change ahead of themselves). A slot change <i>after</i> an
 * attack, use, release, sprint/sneak change, etc. in the same tick never
 * happens in vanilla - and Grim's PacketOrderE flags exactly that ("changed
 * held item slot during another conflicting action"). Swap-back tricks like
 * MaceAssist's attribute swapping did it all the time.
 *
 * <p>
 * This tracks, from the packets actually going out, whether such an action
 * has been sent since the last tick end (the client's tick-end packet, which
 * is also where Grim's tick ends). While one has, the slot change is held
 * back ({@code MultiPlayerGameModeMixin}) and vanilla sends it itself at the
 * start of the next tick, in MultiPlayerGameMode.tick(), before anything
 * else.
 */
public enum PacketOrder
{
	;
	
	private static volatile boolean actedThisTick;
	private static boolean lastShift;
	private static boolean lastSprint;
	
	/** Called for every packet the client sends. */
	public static void onSent(Packet<?> packet)
	{
		if(packet instanceof ServerboundClientTickEndPacket)
		{
			actedThisTick = false;
			return;
		}
		
		// attacking, interacting, right-clicking (items and blocks), and
		// sprinting, gliding, leaving a bed, mount jumps, opening a mount's
		// inventory
		if(packet instanceof ServerboundAttackPacket
			|| packet instanceof ServerboundInteractPacket
			|| packet instanceof ServerboundUseItemPacket
			|| packet instanceof ServerboundUseItemOnPacket
			|| packet instanceof ServerboundPlayerCommandPacket)
		{
			actedThisTick = true;
			return;
		}
		
		// releasing a drawn bow, an eaten item, a raised shield...
		if(packet instanceof ServerboundPlayerActionPacket action && action
			.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM)
		{
			actedThisTick = true;
			return;
		}
		
		// starting or stopping to sneak or sprint
		if(packet instanceof ServerboundPlayerInputPacket inputPacket)
		{
			Input input = inputPacket.input();
			if(input.shift() != lastShift || input.sprint() != lastSprint)
				actedThisTick = true;
			lastShift = input.shift();
			lastSprint = input.sprint();
		}
	}
	
	/**
	 * Whether a slot change can go out right now without coming after a
	 * conflicting action in the same tick. If not, it goes out at the start
	 * of the next tick - so anything that needs the new item in hand on the
	 * server (an attack or use right after switching) should wait a tick too.
	 */
	public static boolean canChangeSlotNow()
	{
		return !actedThisTick;
	}
}
