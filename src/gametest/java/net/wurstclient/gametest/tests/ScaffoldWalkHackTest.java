/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.Locale;
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
	private final ConnectionPacketOutputListener counter = event -> {
		if(event.getPacket() instanceof ServerboundSetCarriedItemPacket)
			slotPackets.incrementAndGet();
	};
	
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
		context.runOnClient(mc -> mc.options.keyUp.setDown(true));
		context.waitTicks(120);
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
				+ " {}), yaw {}, {} slot packets",
			name, progress, sideways, pos.y, laneY + 1, endYaw,
			slotPackets.get());
		
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
	}
}
