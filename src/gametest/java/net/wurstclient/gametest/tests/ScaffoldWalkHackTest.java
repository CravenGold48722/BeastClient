/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Godbridging with ScaffoldWalk (Client-side) in three directions - straight
 * (north), diagonal (north-east) and an odd angle (200 degrees, a staircase)
 * - from a ledge in the air, holding W: no fall, real progress in the
 * direction you faced, the line kept, the blocks selected once instead of
 * for every block, and the camera turned back afterwards.
 */
public final class ScaffoldWalkHackTest extends SingleplayerTest
{
	private static final int HEIGHT = 12;
	
	private final AtomicInteger slotPackets = new AtomicInteger();
	/** Every packet sent while bridging, by type. */
	private final Map<String, Integer> packets = new ConcurrentHashMap<>();
	private final ConnectionPacketOutputListener counter = event -> {
		if(event.getPacket() instanceof ServerboundSetCarriedItemPacket)
			slotPackets.incrementAndGet();
		packets.merge(event.getPacket().getClass().getSimpleName(), 1,
			Integer::sum);
	};
	
	/**
	 * What a player godbridging sends: movement, input, tick ends, block
	 * clicks with their swings, the hotbar switch. Nothing else.
	 */
	private static final Set<String> VANILLA_BRIDGING = Set.of("Pos", "Rot",
		"PosRot", "StatusOnly", "ServerboundClientTickEndPacket",
		"ServerboundPlayerInputPacket", "ServerboundUseItemOnPacket",
		"ServerboundSwingPacket", "ServerboundSetCarriedItemPacket",
		"ServerboundKeepAlivePacket", "ServerboundChatCommandPacket",
		// vanilla acknowledging chunks as you move into them
		"ServerboundChunkBatchReceivedPacket");
	
	public ScaffoldWalkHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing ScaffoldWalk godbridge");
		BlockPos ground =
			context.computeOnClient(mc -> mc.player.blockPosition());
		
		context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
			.add(ConnectionPacketOutputListener.class, counter));
		try
		{
			runCommand("gamemode survival");
			// away from the wall the other tests build at z+10
			bridge(ground.offset(0, HEIGHT, -4), 180, "north");
			bridge(ground.offset(-20, HEIGHT, -4), 225, "north-east");
			bridge(ground.offset(20, HEIGHT, -4), 200, "200 degrees");
			
		}finally
		{
			context.runOnClient(mc -> mc.options.keyUp.setDown(false));
			context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
				.remove(ConnectionPacketOutputListener.class, counter));
			runWurstCommand("t ScaffoldWalk off");
			runCommand(String.format("fill %d %d %d %d %d %d air",
				ground.getX() - 45, ground.getY() + HEIGHT - 1,
				ground.getZ() - 45, ground.getX() + 45,
				ground.getY() + HEIGHT - 1, ground.getZ() - 1));
			runCommand("gamemode creative");
			clearInventory();
			Vec3 home = Vec3.atBottomCenterOf(ground);
			runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f 0 0",
				home.x, ground.getY(), home.z));
			
			clearChat();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7);
		}
	}
	
	private void bridge(BlockPos start, float yaw, String name)
	{
		int laneY = start.getY() - 1;
		
		// a 3x3 ledge, you in its middle, blocks in slot 1 and something
		// else in your hand
		clearInventory();
		runCommand("item replace entity @s hotbar.0 with diamond_sword");
		runCommand("item replace entity @s hotbar.1 with stone 64");
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		runCommand(String.format("fill %d %d %d %d %d %d stone",
			start.getX() - 1, laneY, start.getZ() - 1, start.getX() + 1, laneY,
			start.getZ() + 1));
		// (block centers: "%d.5" is the wrong block for negative coordinates)
		Vec3 center = Vec3.atBottomCenterOf(start);
		runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f %s 30",
			center.x, start.getY(), center.z, yaw));
		context.waitTicks(10);
		
		slotPackets.set(0);
		runWurstCommand("t ScaffoldWalk on");
		packets.clear();
		context.runOnClient(mc -> mc.options.keyUp.setDown(true));
		// count the jumps: leaving the ground going up
		int jumps = 0;
		boolean wasUp = false;
		for(int t = 0; t < 120; t++)
		{
			context.waitTick();
			boolean up = context
				.computeOnClient(mc -> mc.player.getY() > laneY + 1 + 0.3);
			if(up && !wasUp)
				jumps++;
			wasUp = up;
		}
		context.runOnClient(mc -> mc.options.keyUp.setDown(false));
		context.waitTicks(30);
		runWurstCommand("t ScaffoldWalk off");
		
		Vec3 pos = context.computeOnClient(mc -> mc.player.position());
		float endYaw = context.computeOnClient(mc -> mc.player.getYRot());
		Vec3 dir = Vec3.directionFromRotation(0, yaw);
		Vec3 moved = pos.subtract(Vec3.atBottomCenterOf(start));
		double progress = moved.dot(dir);
		double sideways =
			moved.subtract(dir.scale(progress)).horizontalDistance();
		logger.info(
			"Godbridge {}: {} blocks along, {} off the line, y {} (lane top"
				+ " {}), yaw {}, {} jumps, {} slot packets, packets {}",
			name, progress, sideways, pos.y, laneY + 1, endYaw, jumps,
			slotPackets.get(), new TreeMap<>(packets));
		
		if(pos.y < laneY + 1 - 0.01)
			throw new RuntimeException(
				"Fell off the " + name + " bridge after " + progress);
		if(progress < 12)
			throw new RuntimeException(
				"Only " + progress + " blocks of " + name + " bridge");
		if(sideways > 1)
			throw new RuntimeException(
				name + " bridge drifted " + sideways + " off the line");
		if(Math.abs(Mth.wrapDegrees(endYaw - yaw)) > 2)
			throw new RuntimeException(
				"Camera didn't turn back to " + name + ": yaw " + endYaw);
		if(slotPackets.get() > 2)
			throw new RuntimeException(slotPackets.get()
				+ " slot changes for one " + name + " bridge");
		
		// nothing a vanilla player couldn't send while bridging
		for(String type : packets.keySet())
			if(!VANILLA_BRIDGING.contains(type))
				throw new RuntimeException(
					"Sent " + type + " while bridging " + name);
		int clicks = packets.getOrDefault("ServerboundUseItemOnPacket", 0);
		int swings = packets.getOrDefault("ServerboundSwingPacket", 0);
		if(swings != clicks)
			throw new RuntimeException(
				swings + " swings for " + clicks + " block clicks");
		if(clicks > progress * 2.5 + 4)
			throw new RuntimeException(clicks + " block clicks for " + progress
				+ " blocks of " + name + " bridge");
		if(name.equals("north") && jumps < 1)
			throw new RuntimeException("No godbridge jump in " + progress
				+ " blocks of " + name + " bridge");
	}
}
