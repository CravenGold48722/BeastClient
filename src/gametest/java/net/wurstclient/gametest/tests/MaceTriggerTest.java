/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.Locale;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * MaceAssist's trigger bot at riptide speed: diving straight down at a target
 * at 3.5 blocks per tick (like a riptide in the rain), it has to land the
 * mace hit before you hit the ground. The test only holds the dive speed;
 * aiming and hitting are MaceAssist's.
 */
public final class MaceTriggerTest extends SingleplayerTest
{
	private static final int HEIGHT = 25;
	private static final double DIVE_SPEED = 3.5;
	private static final float HEALTH = 1024;
	
	public MaceTriggerTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing MaceAssist trigger bot at riptide speed");
		BlockPos ground =
			context.computeOnClient(mc -> mc.player.blockPosition());
		BlockPos target = ground.offset(6, 0, -6);
		Vec3 spot = Vec3.atBottomCenterOf(target);
		
		try
		{
			clearInventory();
			runCommand("item replace entity @s hotbar.0 with mace");
			context
				.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			runCommand(String.format(Locale.ROOT,
				"summon husk %.1f %d %.1f {NoAI:1b,Silent:1b,Health:%.0ff,"
					+ "attributes:[{id:\"minecraft:max_health\",base:%.0f}]}",
				spot.x, target.getY(), spot.z, HEALTH, HEALTH));
			runWurstCommand("setcheckbox MaceAssist Mace_trigger_bot on");
			runWurstCommand("setcheckbox MaceAssist Mace_aim_assist on");
			runWurstCommand("t MaceAssist on");
			
			// above it, a little to the side, looking down
			runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f 0 89",
				spot.x + 0.4, target.getY() + HEIGHT, spot.z + 0.4));
			context.waitTicks(5);
			
			// dive at riptide speed until landing
			boolean landed = false;
			for(int t = 0; t < 80 && !landed; t++)
			{
				context.runOnClient(mc -> {
					Vec3 v = mc.player.getDeltaMovement();
					mc.player.setDeltaMovement(v.x, -DIVE_SPEED, v.z);
				});
				context.waitTick();
				landed = context.computeOnClient(mc -> mc.player.onGround());
			}
			context.waitTicks(5);
			
			float health = server.computeOnServer(s -> {
				AABB area = new AABB(target).inflate(3);
				return s.overworld()
					.getEntitiesOfClass(Husk.class, area, h -> true).stream()
					.findFirst().map(Husk::getHealth).orElse(HEALTH);
			});
			logger.info("Riptide-speed mace dive: target health {} of {}",
				health, HEALTH);
			if(health >= HEALTH)
				throw new RuntimeException(
					"The trigger bot never hit during a riptide-speed dive");
			
		}finally
		{
			runWurstCommand("t MaceAssist off");
			runWurstCommand("setcheckbox MaceAssist Mace_trigger_bot off");
			runWurstCommand("setcheckbox MaceAssist Mace_aim_assist off");
			runCommand("kill @e[type=husk]");
			context.waitTicks(25); // death animation
			runCommand("kill @e[type=!player]"); // its drops
			clearInventory();
			Vec3 home = Vec3.atBottomCenterOf(ground);
			runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f 0 0",
				home.x, ground.getY(), home.z));
			clearChat();
			clearParticles();
			clearToasts();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7);
		}
	}
}
