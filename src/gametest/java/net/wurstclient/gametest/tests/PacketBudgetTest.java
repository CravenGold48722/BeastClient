/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Packet budget: with nothing to do (standing still, no targets), hacks that
 * run every tick must not send anything a plain idle client doesn't.
 *
 * <p>
 * First measures what the game itself sends while idle (tick-end markers,
 * position reminders, keep-alives...), then turns on groups of always-running
 * hacks and compares, packet type by packet type. A hack that flaps a key,
 * re-sends its slot, nudges the rotation or clicks the inventory while idle
 * shows up here as extra packets.
 */
public final class PacketBudgetTest extends SingleplayerTest
{
	private static final int TICKS = 60;
	
	/**
	 * Packets a hack may legitimately add once while idle: AutoSprint
	 * pressing sprint changes the reported input once.
	 */
	private static final Map<String, Integer> ALLOWED_EXTRA =
		Map.of("ServerboundPlayerInputPacket", 1);
	
	private final Map<String, Integer> counts = new ConcurrentHashMap<>();
	
	private final ConnectionPacketOutputListener counter = event -> counts
		.merge(event.getPacket().getClass().getSimpleName(), 1, Integer::sum);
	
	public PacketBudgetTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing packet budget of idle hacks");
		
		// nothing to aim at or fight
		runCommand("gamerule spawn_mobs false");
		runCommand("kill @e[type=!player]");
		context.waitTicks(5);
		
		context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
			.add(ConnectionPacketOutputListener.class, counter));
		
		try
		{
			Map<String, Integer> baseline = measure();
			logger.info("Idle baseline over {} ticks: {}", TICKS, baseline);
			
			checkGroup(baseline, "AutoSprint", "AimAssist", "MaceAssist",
				"BowAimbot", "AutoTotem", "AutoSword");
			checkGroup(baseline, "Killaura");
			checkGroup(baseline, "KillauraLegit");
			checkGroup(baseline, "FightBot");
			
			// a target that can't move or die (creative hits ignore
			// Invulnerable), off to the side so the aim
			// has to turn
			runCommand("tp @s ~ ~ ~ 0 0");
			runCommand(
				"summon husk ~2 ~ ~3 {NoAI:1b,Silent:1b,Health:1024f,attributes:[{id:\"minecraft:max_health\",base:1024}]}");
			context.waitTicks(5);
			checkEngaged("KillauraLegit");
			checkEngaged("Killaura");
			checkEngaged("AimAssist");
			
		}finally
		{
			context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
				.remove(ConnectionPacketOutputListener.class, counter));
			runCommand("gamerule spawn_mobs true");
			runCommand("kill @e[type=husk]");
			context.waitTicks(25); // death animation
			runCommand("kill @e[type=!player]"); // its drops
			runCommand("tp @s ~ ~ ~ 0 0");
			
			// toggle announcements (chat + action bar)
			clearChat();
			clearParticles();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7); // for the teleport rotation to arrive
			waitForHandSwing();
		}
	}
	
	private void checkGroup(Map<String, Integer> baseline, String... hacks)
	{
		for(String hack : hacks)
			runWurstCommand("t " + hack + " on");
		context.waitTicks(5);
		
		Map<String, Integer> measured = measure();
		
		for(String hack : hacks)
			runWurstCommand("t " + hack + " off");
		context.waitTicks(5);
		
		String group = String.join(", ", hacks);
		logger.info("{} over {} ticks: {}", group, TICKS, measured);
		
		for(Map.Entry<String, Integer> e : measured.entrySet())
		{
			int base = baseline.getOrDefault(e.getKey(), 0);
			int allowed = base + ALLOWED_EXTRA.getOrDefault(e.getKey(), 0)
			// keep-alives and the like come at their own pace
				+ Math.max(1, base / 4);
			if(e.getValue() > allowed)
				throw new RuntimeException(group + " sent " + e.getValue() + " "
					+ e.getKey() + " while idle, the idle game sends " + base);
		}
	}
	
	/**
	 * With a still target: the first half covers turning onto it, the second
	 * half is steady state. Once the crosshair is on a target that doesn't
	 * move, a hand doesn't keep turning - so neither should the rotation
	 * packets. Attacks must each come with exactly one swing, and nothing
	 * may touch the hotbar or inventory.
	 */
	private void checkEngaged(String hack)
	{
		// start facing away from it, so every hack has to turn
		runCommand("tp @s ~ ~ ~ 0 0");
		context.waitTicks(7);
		
		runWurstCommand("t " + hack + " on");
		Map<String, Integer> turning = measure();
		Map<String, Integer> steady = measure();
		runWurstCommand("t " + hack + " off");
		context.waitTicks(5);
		
		logger.info("{} turning onto a target: {}", hack, turning);
		logger.info("{} on target: {}", hack, steady);
		
		int rotations =
			steady.getOrDefault("Rot", 0) + steady.getOrDefault("PosRot", 0);
		if(rotations > 3)
			throw new RuntimeException(hack + " sent " + rotations
				+ " rotation packets while already on a still target");
		
		for(Map<String, Integer> m : List.of(turning, steady))
		{
			for(String forbidden : new String[]{
				"ServerboundSetCarriedItemPacket",
				"ServerboundContainerClickPacket", "ServerboundUseItemPacket"})
				if(m.containsKey(forbidden))
					throw new RuntimeException(
						hack + " sent " + forbidden + " while attacking");
				
			int attacks = m.getOrDefault("ServerboundInteractPacket", 0)
				+ m.getOrDefault("ServerboundAttackPacket", 0);
			int swings = m.getOrDefault("ServerboundSwingPacket", 0);
			if(swings != attacks)
				throw new RuntimeException(hack + " sent " + swings
					+ " swings for " + attacks + " attacks");
		}
	}
	
	private Map<String, Integer> measure()
	{
		counts.clear();
		context.waitTicks(TICKS);
		return new TreeMap<>(counts);
	}
}
