/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Godbridging with ScaffoldWalk (Client-side): standing on a ledge in the
 * air, facing north, holding W - the bridge has to go north, in a straight
 * line, without falling, switching to the blocks once instead of for every
 * block. Letting go of W turns the camera back to north.
 */
public final class ScaffoldWalkHackTest extends SingleplayerTest
{
	private static final int HEIGHT = 12;
	
	public ScaffoldWalkHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing ScaffoldWalk godbridge");
		
		BlockPos start = context
			.computeOnClient(mc -> mc.player.blockPosition().above(HEIGHT));
		int laneY = start.getY() - 1;
		
		AtomicInteger slotPackets = new AtomicInteger();
		ConnectionPacketOutputListener counter = event -> {
			if(event.getPacket() instanceof ServerboundSetCarriedItemPacket)
				slotPackets.incrementAndGet();
		};
		
		try
		{
			// a 2-block ledge, you on its north end, facing north (yaw 180),
			// blocks in slot 1 and something else in your hand
			runCommand("gamemode survival");
			clearInventory();
			runCommand("item replace entity @s hotbar.0 with diamond_sword");
			runCommand("item replace entity @s hotbar.1 with stone 64");
			context
				.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			runCommand(String.format("fill %d %d %d %d %d %d stone",
				start.getX(), laneY, start.getZ(), start.getX(), laneY,
				start.getZ() + 1));
			runCommand(String.format("tp @s %d.5 %d %d.3 180 30", start.getX(),
				start.getY(), start.getZ()));
			context.waitTicks(10);
			
			context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
				.add(ConnectionPacketOutputListener.class, counter));
			runWurstCommand("t ScaffoldWalk on");
			context.runOnClient(mc -> mc.options.keyUp.setDown(true));
			context.waitTicks(120);
			context.runOnClient(mc -> mc.options.keyUp.setDown(false));
			context.waitTicks(30);
			
			// count the bridge: blocks north of the ledge, in a line
			int length = 0;
			for(int i = 1; i <= 40; i++)
			{
				BlockPos pos =
					new BlockPos(start.getX(), laneY, start.getZ() - i);
				if(!context.computeOnClient(
					mc -> mc.level.getBlockState(pos).is(Blocks.STONE)))
					break;
				length++;
			}
			
			double y = context.computeOnClient(mc -> mc.player.getY());
			float yaw = context.computeOnClient(mc -> mc.player.getYRot());
			double x = context.computeOnClient(mc -> mc.player.getX());
			logger.info(
				"Godbridge: {} blocks north, y {} (lane top {}), x {}, yaw {},"
					+ " {} slot packets",
				length, y, laneY + 1, x, yaw, slotPackets.get());
			
			if(y < laneY + 1 - 0.01)
				throw new RuntimeException(
					"Fell off the bridge after " + length + " blocks");
			if(length < 15)
				throw new RuntimeException(
					"Bridge only " + length + " blocks long");
			if(Math.abs(x - (start.getX() + 0.5)) > 0.5)
				throw new RuntimeException("Drifted off the lane: x " + x);
			if(Math.abs(Mth.wrapDegrees(yaw - 180)) > 2)
				throw new RuntimeException(
					"Camera didn't turn back to north: yaw " + yaw);
			if(slotPackets.get() > 2)
				throw new RuntimeException(
					slotPackets.get() + " slot changes for one bridge");
			
		}finally
		{
			context.runOnClient(mc -> mc.options.keyUp.setDown(false));
			context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
				.remove(ConnectionPacketOutputListener.class, counter));
			runWurstCommand("t ScaffoldWalk off");
			runCommand(String.format("fill %d %d %d %d %d %d air",
				start.getX() - 2, laneY, start.getZ() - 45, start.getX() + 2,
				laneY, start.getZ() + 1));
			runCommand("gamemode creative");
			clearInventory();
			runCommand(String.format("tp @s %d.5 %d %d.5 0 0", start.getX(),
				start.getY() - HEIGHT, start.getZ()));
			
			clearChat();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7);
		}
	}
}
