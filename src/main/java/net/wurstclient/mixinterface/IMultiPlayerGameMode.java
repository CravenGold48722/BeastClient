/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public interface IMultiPlayerGameMode
{
	public void windowClick_PICKUP(int slot);
	
	public void windowClick_QUICK_MOVE(int slot);
	
	public void windowClick_THROW(int slot);
	
	public void windowClick_SWAP(int from, int to);
	
	public void rightClickItem();
	
	public void rightClickBlock(BlockPos pos, Direction side, Vec3 hitVec);
	
	/**
	 * Tells the server about a changed hotbar selection right away, the same
	 * way vanilla does before an attack or item use. Sends nothing if the
	 * server already knows the slot, so it can't produce duplicate slot
	 * packets.
	 */
	public void syncSelectedSlot();
	
	public void sendPlayerActionC2SPacket(
		ServerboundPlayerActionPacket.Action action, BlockPos blockPos,
		Direction direction);
	
	public void sendPlayerInteractBlockPacket(InteractionHand hand,
		BlockHitResult blockHitResult);
}
