/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.wurstclient.hacks.ChestSearchHack;
import net.wurstclient.hacks.LootSearchHack;
import net.wurstclient.lootsearch.LootSearchUtil;
import net.wurstclient.util.ChatUtils;

/**
 * Records what's in a container the player opens, for ChestSearch. One of
 * these lives as long as the container screen. Written for Beast; the CevAPI
 * fork does the same inside its container screen mixin.
 *
 * <p>
 * The container is the block under the crosshair when the screen opened (or
 * the one below it, for a block on top of a chest). Double chests are stored
 * with both halves as their bounds.
 */
public final class ChestScreenRecording
{
	private final AbstractContainerMenu menu;
	private final List<Integer> slotOrder = new ArrayList<>();
	private final String serverIp;
	private final String dimension;
	private final BlockPos clickedPos;
	private final BlockPos chestPos;
	private final ChestRecorder.Bounds bounds;
	private final ChestRecorder recorder;
	private boolean finished;
	
	private ChestScreenRecording(AbstractContainerMenu menu, int containerSlots,
		BlockPos clickedPos, BlockPos chestPos, ChestRecorder.Bounds bounds,
		ChestRecorder recorder)
	{
		this.menu = menu;
		for(int i = 0; i < Math.min(containerSlots, menu.slots.size()); i++)
			slotOrder.add(i);
		this.clickedPos = clickedPos;
		this.chestPos = chestPos;
		this.bounds = bounds;
		this.recorder = recorder;
		serverIp = WurstClient.MC.getCurrentServer() != null
			? WurstClient.MC.getCurrentServer().ip : null;
		dimension = WurstClient.MC.level.dimension().identifier().toString();
	}
	
	/**
	 * Starts recording the container screen that just opened, or returns null
	 * if ChestSearch is off or it's not clear which block was opened.
	 */
	public static ChestScreenRecording start(AbstractContainerMenu menu,
		int containerSlots)
	{
		if(!WurstClient.INSTANCE.isEnabled() || WurstClient.MC.level == null)
			return null;
		
		ChestSearchHack hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		String dimension =
			WurstClient.MC.level.dimension().identifier().toString();
		
		BlockPos clicked = getOpenedContainerPos();
		if(clicked != null)
		{
			// a highlighted search result: show which slot it's in, and
			// remove its marker now that it was found
			if(SlotHighlighter.INSTANCE.tryActivate(dimension, clicked) < 0)
				SlotHighlighter.INSTANCE.tryActivate(dimension,
					normalize(clicked)[0]);
			ChestSearchScreen.clearDecorations(dimension, clicked);
		}
		
		if(hack.isOffMode() || clicked == null)
			return null;
		
		ChestConfig config = new ChestConfig();
		if(!config.enabled)
			return null;
		
		BlockPos[] normalized = normalize(clicked);
		BlockPos chestPos = normalized[0];
		ChestRecorder.Bounds bounds = ChestRecorder.Bounds.of(normalized[1],
			normalized[2], getFacingName(getState(chestPos)));
		ChestRecorder recorder =
			new ChestRecorder(new File(config.dbPath), config);
		
		ChestScreenRecording recording = new ChestScreenRecording(menu,
			containerSlots, clicked, chestPos, bounds, recorder);
		if(hack.isAutomaticMode())
			recorder.onChestOpened(recording.serverIp, dimension,
				clicked.getX(), clicked.getY(), clicked.getZ(), menu,
				recording.slotOrder.size(), recording.slotOrder, bounds);
		
		return recording;
	}
	
	/**
	 * Records the container's current contents right away (the "Scan"
	 * button in ChestSearch's manual mode).
	 */
	public void scanNow()
	{
		List<ItemStack> stacks = getContents();
		recorder.recordFromStacksWithSlotOrder(serverIp, dimension,
			clickedPos.getX(), clickedPos.getY(), clickedPos.getZ(), stacks,
			slotOrder, bounds);
		ChatUtils.message("Chest scanned at " + chestPos.toShortString());
	}
	
	/**
	 * Saves the final contents when the screen closes.
	 */
	public void finish()
	{
		if(finished)
			return;
		finished = true;
		
		ChestSearchHack hack = WurstClient.INSTANCE.getHax().chestSearchHack;
		if(!hack.isAutomaticMode())
			return;
		
		List<ItemStack> stacks = getContents();
		if(stacks.stream().allMatch(ItemStack::isEmpty))
			return;
		
		recorder.recordFromStacksWithSlotOrder(serverIp, dimension,
			chestPos.getX(), chestPos.getY(), chestPos.getZ(), stacks,
			slotOrder, bounds);
		
		if(hack.isRecordNotificationEnabled())
			ChatUtils.message("Chest recorded at position " + chestPos.getX()
				+ "," + chestPos.getY() + "," + chestPos.getZ());
		
		LootSearchHack lootSearch =
			WurstClient.INSTANCE.getHax().lootSearchHack;
		if(!lootSearch.shouldCompareOpenedChests()
			|| LootSearchUtil.findFileForServer(serverIp) == null)
			return;
		
		boolean match =
			LootSearchUtil.compareStacksWithLoot(serverIp, dimension,
				chestPos.getX(), chestPos.getY(), chestPos.getZ(), stacks);
		if(!match && hack.isLootMismatchNotificationEnabled())
			ChatUtils.message("Chest does not match loot table.");
	}
	
	private List<ItemStack> getContents()
	{
		ArrayList<ItemStack> stacks = new ArrayList<>(slotOrder.size());
		for(int index : slotOrder)
			stacks.add(menu.slots.get(index).getItem().copy());
		return stacks;
	}
	
	private static BlockPos getOpenedContainerPos()
	{
		if(!(WurstClient.MC.hitResult instanceof BlockHitResult hit))
			return null;
		
		BlockPos pos = hit.getBlockPos();
		if(isTrackableContainer(getState(pos)))
			return pos;
		
		if(isTrackableContainer(getState(pos.below())))
			return pos.below();
		
		return null;
	}
	
	/**
	 * Returns {chosen position, bounds min, bounds max}. For a double chest,
	 * the chosen position is the half with items in it (the first half if
	 * both or neither have any).
	 */
	private static BlockPos[] normalize(BlockPos pos)
	{
		BlockState state = getState(pos);
		BlockPos other = findConnectedChest(pos, state);
		if(other == null)
			return new BlockPos[]{pos, pos, pos};
		
		BlockPos min = new BlockPos(Math.min(pos.getX(), other.getX()),
			pos.getY(), Math.min(pos.getZ(), other.getZ()));
		BlockPos max = new BlockPos(Math.max(pos.getX(), other.getX()),
			pos.getY(), Math.max(pos.getZ(), other.getZ()));
		BlockPos chosen = hasItems(max) && !hasItems(min) ? max : min;
		return new BlockPos[]{chosen, min, max};
	}
	
	private static boolean hasItems(BlockPos pos)
	{
		if(!(WurstClient.MC.level
			.getBlockEntity(pos) instanceof net.minecraft.world.Container inv))
			return false;
		
		for(int i = 0; i < inv.getContainerSize(); i++)
			if(!inv.getItem(i).isEmpty())
				return true;
			
		return false;
	}
	
	private static BlockPos findConnectedChest(BlockPos pos, BlockState state)
	{
		if(!(state.getBlock() instanceof ChestBlock))
			return null;
		
		ChestType type = state.getValue(ChestBlock.TYPE);
		if(type == ChestType.SINGLE)
			return null;
		
		Direction facing = state.getValue(ChestBlock.FACING);
		Direction offset = type == ChestType.LEFT ? facing.getClockWise()
			: facing.getCounterClockWise();
		BlockPos candidate = pos.relative(offset);
		BlockState other = getState(candidate);
		if(!(other.getBlock() instanceof ChestBlock)
			|| other.getValue(ChestBlock.FACING) != facing
			|| other.getValue(ChestBlock.TYPE) != type.getOpposite())
			return null;
		
		return candidate;
	}
	
	private static boolean isTrackableContainer(BlockState state)
	{
		Block block = state.getBlock();
		return block instanceof ChestBlock || block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof EnderChestBlock
			|| block instanceof DecoratedPotBlock;
	}
	
	private static String getFacingName(BlockState state)
	{
		if(state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
			return state.getValue(BlockStateProperties.HORIZONTAL_FACING)
				.getSerializedName();
		
		if(state.hasProperty(BlockStateProperties.FACING))
			return state.getValue(BlockStateProperties.FACING)
				.getSerializedName();
		
		return null;
	}
	
	private static BlockState getState(BlockPos pos)
	{
		return WurstClient.MC.level.getBlockState(pos);
	}
}
