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
import org.lwjgl.glfw.GLFW;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * MaceAssist's aim and trigger bot on dives too fast to see coming:
 * <ul>
 * <li>diving straight down at a target at 3.5 blocks per tick (like a riptide
 * in the rain), it has to land the mace hit before you hit the ground;</li>
 * <li>falling from 10,000 blocks up with the gravity attribute at 50 (the
 * last ticks are several hundred blocks each, so the server never sees you
 * anywhere near the target in the air), starting off to its side and looking
 * away from it, it still has to hit it.</li>
 * </ul>
 * The test only sets up the fall and the pitch (the mace aim only turns
 * sideways); turning sideways onto the target and hitting are MaceAssist's.
 */
public final class MaceTriggerTest extends SingleplayerTest
{
	private static final int HEIGHT = 40;
	private static final double DIVE_SPEED = 3.5;
	private static final int HEAVY_HEIGHT = 10_000;
	private static final double HEAVY_GRAVITY = 50;
	private static final float HEALTH = 1024;
	
	public MaceTriggerTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		testRiptideDive();
		testHeavyFall();
		testCobweb();
		testPearlCatchHeight();
		testPearlChain();
	}
	
	/**
	 * A pearl catch from a throw like the user's (70 degrees up, not
	 * straight up): MaceAssist turns onto the pearl and its wind charge hits
	 * it, which puts you where the pearl was. "Straight up" missed it (0
	 * blocks; now 22.5). Delays past the slider's 5 ticks missed: at 25-30
	 * blocks the wind charge's own random spread is as wide as the pearl.
	 */
	private void testPearlCatchHeight()
	{
		logger.info("Testing MaceAssist pearl catch height");
		Vec3 home = context.computeOnClient(mc -> mc.player.position());
		StringBuilder heights = new StringBuilder();
		double atDefault = 0;
		try
		{
			runWurstCommand("t MaceAssist on");
			for(int delay : new int[]{2, 5})
			{
				runCommand("gamemode survival");
				runCommand("effect give @s resistance 60 4 true");
				clearInventory();
				runCommand(
					"item replace entity @s hotbar.3 with ender_pearl 16");
				runCommand(
					"item replace entity @s hotbar.5 with wind_charge 64");
				context.runOnClient(
					mc -> mc.player.getInventory().setSelectedSlot(3));
				runWurstCommand(
					"setslider MaceAssist Pearl_catch_delay " + delay);
				runCommand(String.format(Locale.ROOT,
					"tp @s %.3f %.3f %.3f 0 -70", home.x, home.y, home.z));
				context.waitTicks(10);
				
				input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
				double best = 0;
				for(int t = 0; t < 80; t++)
				{
					context.waitTick();
					best = Math.max(best,
						context.computeOnClient(mc -> mc.player.getY())
							- home.y);
				}
				heights.append(String.format(Locale.ROOT, " delay %d: %.1f,",
					delay, best));
				if(delay == 2)
					atDefault = best;
				runCommand("kill @e[type=ender_pearl]");
				context.waitFor(mc -> mc.player.onGround(), 400);
			}
			logger.info("Pearl catch from a 70-degree throw, highest point:{}",
				heights);
			if(atDefault < 10)
				throw new RuntimeException(String.format(Locale.ROOT,
					"The pearl catch only went %.1f blocks up", atDefault));
			
		}finally
		{
			runWurstCommand("t MaceAssist off");
			runWurstCommand("setslider MaceAssist Pearl_catch_delay 2");
			runCommand("kill @e[type=ender_pearl]");
			runCommand("kill @e[type=wind_charge]");
			context.waitFor(mc -> mc.player.onGround(), 400);
			runCommand("gamemode creative");
			runCommand("effect clear @s");
			clearInventory();
			context
				.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			runCommand(String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f 0 0",
				home.x, home.y, home.z));
			clearChat();
			clearParticles();
			clearToasts();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(10);
		}
	}
	
	/**
	 * Chaining (user, 2026-10-17): one right-click with a pearl, looking up
	 * past the catch angle, starts it; MaceAssist then turns up, throws and
	 * catches pearl after pearl by itself, and a second right-click ends it
	 * (no pearl thrown after that). Logs how high each catch went.
	 */
	private void testPearlChain()
	{
		logger.info("Testing MaceAssist pearl catch chaining");
		Vec3 home = context.computeOnClient(mc -> mc.player.position());
		try
		{
			clearInventory();
			runCommand("gamemode survival");
			runCommand("effect give @s resistance 120 4 true");
			runCommand("effect give @s regeneration 120 4 true");
			runCommand("item replace entity @s hotbar.0 with diamond_sword");
			runCommand("item replace entity @s hotbar.3 with ender_pearl 16");
			runCommand("item replace entity @s hotbar.5 with wind_charge 64");
			context
				.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
			runCommand("tp @s ~ ~ ~ 0 -70");
			runWurstCommand("setcheckbox MaceAssist Chaining on");
			runWurstCommand("t MaceAssist on");
			context.waitTicks(7);
			double startY = context.computeOnClient(mc -> mc.player.getY());
			
			input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			// three pearls thrown without another click, highest point of each
			// stretch logged
			double[] best = new double[1];
			int thrown = 0;
			StringBuilder heights = new StringBuilder();
			for(int t = 0; t < 400 && thrown < 3; t++)
			{
				context.waitTick();
				double y = context.computeOnClient(mc -> mc.player.getY());
				best[0] = Math.max(best[0], y - startY);
				int left = context.computeOnClient(
					mc -> mc.player.getInventory().getItem(3).getCount());
				if(16 - left > thrown)
				{
					heights
						.append(String.format(Locale.ROOT, " %.1f", best[0]));
					thrown = 16 - left;
					best[0] = 0;
				}
			}
			logger.info(
				"Pearl chain: {} pearls thrown by one click; highest"
					+ " point before each throw (blocks over the start):{}",
				thrown, heights);
			if(thrown < 3)
				throw new RuntimeException("Chaining threw only " + thrown
					+ " pearls in 400 ticks (should keep going)");
			// the catches between the throws took us up
			if(!heights.toString().matches(".*\\b([1-9][0-9])\\.\\d.*"))
				throw new RuntimeException(
					"No catch of the chain went 10+ blocks up:" + heights);
			
			// a click of yours ends it
			input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			context.waitTicks(10);
			int atStop = context.computeOnClient(
				mc -> mc.player.getInventory().getItem(3).getCount());
			context.waitTicks(80);
			int after = context.computeOnClient(
				mc -> mc.player.getInventory().getItem(3).getCount());
			logger.info("Pearl chain: {} pearls left at the stop click, {} 80"
				+ " ticks later", atStop, after);
			if(after != atStop)
				throw new RuntimeException(
					"Chaining kept throwing after the right-click that ends it");
			
		}finally
		{
			runWurstCommand("t MaceAssist off");
			runWurstCommand("setcheckbox MaceAssist Chaining off");
			runCommand("kill @e[type=ender_pearl]");
			runCommand("kill @e[type=wind_charge]");
			context.waitFor(mc -> mc.player.onGround(), 400);
			runCommand("gamemode creative");
			runCommand("effect clear @s");
			clearInventory();
			context
				.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			runCommand(String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f 0 0",
				home.x, home.y, home.z));
			clearChat();
			clearParticles();
			clearToasts();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(25);
		}
	}
	
	/**
	 * Right-clicking a block with a sword, with cobwebs and wind charges in
	 * the hotbar: a cobweb goes down, the sword is back in hand, and no wind
	 * charge is fired.
	 */
	private void testCobweb()
	{
		logger.info("Testing MaceAssist cobweb on right click");
		try
		{
			clearInventory();
			runCommand("item replace entity @s hotbar.0 with diamond_sword");
			runCommand("item replace entity @s hotbar.4 with cobweb 8");
			runCommand("item replace entity @s hotbar.5 with wind_charge 8");
			context
				.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			// a block two ahead, looked at from its side
			runCommand("setblock ~ ~ ~2 smooth_stone");
			runCommand("tp @s ~ ~ ~ 0 30");
			runWurstCommand("t MaceAssist on");
			context.waitTicks(7);
			
			input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			waitForBlock(0, 0, 1, Blocks.COBWEB);
			context.waitTicks(4);
			
			int slot = context.computeOnClient(
				mc -> mc.player.getInventory().getSelectedSlot());
			int windCharges = context.computeOnClient(
				mc -> mc.player.getInventory().getItem(5).getCount());
			logger.info("Cobweb placed; selected slot {}, wind charges {}",
				slot, windCharges);
			if(slot != 0)
				throw new RuntimeException(
					"Not back on the sword after the cobweb: slot " + slot);
			if(windCharges != 8)
				throw new RuntimeException(
					"A wind charge was fired instead of / besides the cobweb");
			
		}finally
		{
			runWurstCommand("t MaceAssist off");
			runCommand("setblock ~ ~ ~1 air");
			runCommand("setblock ~ ~ ~2 air");
			clearInventory();
			runCommand("tp @s ~ ~ ~ 0 0");
			clearChat();
			clearParticles();
			clearToasts();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7);
		}
	}
	
	private void testRiptideDive()
	{
		logger.info("Testing MaceAssist trigger bot at riptide speed");
		BlockPos ground =
			context.computeOnClient(mc -> mc.player.blockPosition());
		BlockPos target = ground.offset(6, 0, -6);
		Vec3 spot = Vec3.atBottomCenterOf(target);
		
		try
		{
			setUp(spot, target);
			
			// 1.5 blocks to its side (one tick in reach on the way down),
			// facing away from it sideways; the pitch is the player's - the
			// mace aim only turns sideways - and 37 degrees down hits it from
			// that tick
			runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f 0 37",
				spot.x + 1.5, target.getY() + HEIGHT, spot.z));
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
			
			float health = getTargetHealth(target);
			logger.info("Riptide-speed mace dive: target health {} of {}",
				health, HEALTH);
			if(health >= HEALTH)
				throw new RuntimeException(
					"The trigger bot never hit during a riptide-speed dive");
			
		}finally
		{
			cleanUp(ground);
		}
	}
	
	private void testHeavyFall()
	{
		logger.info("Testing MaceAssist on a {}-block fall at gravity {}",
			HEAVY_HEIGHT, HEAVY_GRAVITY);
		BlockPos ground =
			context.computeOnClient(mc -> mc.player.blockPosition());
		BlockPos target = ground.offset(6, 0, -6);
		Vec3 spot = Vec3.atBottomCenterOf(target);
		
		try
		{
			setUp(spot, target);
			runCommand(String.format(Locale.ROOT,
				"attribute @s minecraft:gravity base set %.1f", HEAVY_GRAVITY));
			// vanilla caps the attribute at 1 (falls ~49 blocks per tick)
			context.waitTicks(2);
			logger.info("Effective gravity: {}",
				(Object)context.computeOnClient(mc -> mc.player.getGravity()));
			
			// 2.1 blocks to its side (in reach once down there), facing away
			// from it sideways; the player's pitch: 48 degrees up, at the
			// husk floating 3 blocks above the floor
			runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f 0 -48",
				spot.x + 1.5, target.getY() + HEAVY_HEIGHT, spot.z + 1.5));
			
			// (right after the tp you still count as on the ground)
			context.waitFor(mc -> !mc.player.onGround(), 20);
			context.waitFor(mc -> mc.player.onGround(), 400);
			context.waitTicks(10);
			
			float health = getTargetHealth(target);
			logger.info(
				"Gravity-{} fall from {} blocks: target health {} of {}",
				HEAVY_GRAVITY, HEAVY_HEIGHT, health, HEALTH);
			if(health >= HEALTH)
				throw new RuntimeException("MaceAssist never hit after a "
					+ HEAVY_HEIGHT + "-block fall at gravity " + HEAVY_GRAVITY);
			
		}finally
		{
			runCommand("attribute @s minecraft:gravity base set 0.08");
			cleanUp(ground);
		}
	}
	
	/** A mace in hand, a sturdy still husk at the target, MaceAssist on. */
	private void setUp(Vec3 spot, BlockPos target)
	{
		clearInventory();
		runCommand("item replace entity @s hotbar.0 with mace");
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		runCommand(String.format(Locale.ROOT,
			"summon husk %.1f %d %.1f {NoAI:1b,Silent:1b,PersistenceRequired:1b,"
				+ "Health:%.0ff,"
				+ "attributes:[{id:\"minecraft:max_health\",base:%.0f}]}",
			spot.x, target.getY(), spot.z, HEALTH, HEALTH));
		runWurstCommand("setcheckbox MaceAssist Mace_trigger_bot on");
		runWurstCommand("setcheckbox MaceAssist Mace_aim_assist on");
		runWurstCommand("setslider MaceAssist Aim_speed 1800");
		runWurstCommand("t MaceAssist on");
	}
	
	private float getTargetHealth(BlockPos target)
	{
		return server.computeOnServer(s -> {
			AABB area = new AABB(target).inflate(3);
			return s.overworld().getEntitiesOfClass(Husk.class, area, h -> true)
				.stream().findFirst().map(Husk::getHealth).orElse(HEALTH);
		});
	}
	
	private void cleanUp(BlockPos ground)
	{
		runWurstCommand("t MaceAssist off");
		runWurstCommand("setslider MaceAssist Aim_speed 15");
		runWurstCommand("setcheckbox MaceAssist Mace_trigger_bot off");
		runWurstCommand("setcheckbox MaceAssist Mace_aim_assist off");
		runCommand("kill @e[type=husk]");
		context.waitTicks(25); // death animation
		runCommand("kill @e[type=!player]"); // its drops
		clearInventory();
		Vec3 home = Vec3.atBottomCenterOf(ground);
		runCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f 0 0", home.x,
			ground.getY(), home.z));
		clearChat();
		clearParticles();
		clearToasts();
		context.runOnClient(
			mc -> mc.gui.setOverlayMessage(Component.empty(), false));
		context.waitTicks(7);
	}
}
