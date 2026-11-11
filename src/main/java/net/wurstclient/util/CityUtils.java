/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Comparator;
import java.util.stream.StreamSupport;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.WurstClient;

/**
 * Crystal-PvP helpers for "citying" a player: breaking one of the blast-proof
 * blocks around their feet so a crystal can reach them. Idea from Meteor
 * Client's {@code EntityUtils.getCityBlock}.
 */
public enum CityUtils
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	/** The closest other player within range that isn't a friend. */
	public static Player findTarget(double range)
	{
		double rangeSq = range * range;
		return StreamSupport.stream(MC.level.players().spliterator(), false)
			.filter(p -> p != MC.player && p.isAlive() && !p.isSpectator())
			.filter(p -> !WurstClient.INSTANCE.getFriends().isFriend(p))
			.filter(p -> MC.player.distanceToSqr(p) <= rangeSq)
			.min(Comparator.comparingDouble(p -> MC.player.distanceToSqr(p)))
			.orElse(null);
	}
	
	/**
	 * The closest surround block (within 6 blocks of you) that can be mined
	 * to city this player, or null if none.
	 */
	public static BlockPos getCityBlock(Player player)
	{
		if(player == null)
			return null;
		
		BlockPos feet = player.blockPosition();
		BlockPos best = null;
		double bestDistSq = 6 * 6;
		for(Direction side : Direction.Plane.HORIZONTAL)
		{
			BlockPos pos = feet.relative(side);
			if(!isCityable(MC.level.getBlockState(pos).getBlock()))
				continue;
			
			double distSq =
				MC.player.getEyePosition().distanceToSqr(pos.getCenter());
			if(distSq < bestDistSq)
			{
				bestDistSq = distSq;
				best = pos;
			}
		}
		
		return best;
	}
	
	/** Blast-proof but mineable, the blocks people surround with. */
	public static boolean isCityable(Block block)
	{
		return block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
			|| block == Blocks.NETHERITE_BLOCK || block == Blocks.RESPAWN_ANCHOR
			|| block == Blocks.ANCIENT_DEBRIS;
	}
}
