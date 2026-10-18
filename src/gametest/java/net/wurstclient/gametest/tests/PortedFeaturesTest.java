/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.hack.Hack;

/**
 * Smoke test for the features ported from the CevAPI fork (user, 2026-10-18:
 * "ANY features ... that beast doesn't [have] should be added"): every one is
 * switched on for a few ticks and off again, with a chest and the inventory
 * opened while the container overlays are on. Any exception - in onEnable,
 * a listener or a mixin - fails the run, and the end must look clean.
 */
public final class PortedFeaturesTest extends SingleplayerTest
{
	/** Hacks that leave the world or open their own screen. */
	private static final List<String> SKIP = List.of();
	
	/** The ported hacks, by name. */
	private static final List<String> HACKS = List.of("AntiCheatDetect",
		"AntiFov", "BedESP", "BedrockStash", "Breadcrumbs", "ChestSearch",
		"DamageESP", "DurabilityHUD", "ElytraInfo", "ElytraPitch", "ElytraWalk",
		"EnchantmentHandler", "EntityCount", "FunCreepers", "GameStats",
		"LavaWaterESP", "LogoutSpots", "LootSearch", "MeasurementESP",
		"MobHealth", "MobOwners", "MobSearch", "MobView", "NecoMode",
		"PearlESP", "PotESP", "ProjectileESP", "RedstoneESP", "RenderAdjust",
		"RoofESP", "SignESP", "SkyBuildESP", "SoundMute", "SpawnerESP",
		"SpawnRadius", "StasisDetector", "TextureRotator", "TrialSpawnerESP",
		"TridentESP", "TunnelHoleStairESP", "Viewmodel", "WardenESP",
		"Waypoints", "WebhookAlert", "WorkstationESP",
		// combat and movement
		"AntiBlast", "AntiProjectile", "Backtrack", "FakeLag", "InfiniteReach",
		"Outreach", "PearlIntercept", "PearlLauncher", "ShieldSwing",
		"WindChargeKey", "AirWalk", "AntiVoid", "BedrockEscape", "BoatPhase",
		"ClutchFall", "ElytraBounce", "ElytraDive", "ElytraFlight",
		"EntityControl", "PearlDrop", "Teleport",
		// MaceAssist, for its spear features (SpearAssist)
		"MaceAssist",
		// blocks and items
		"AntiBreak", "AntiDrop", "AreaNuker", "AutoClicker", "AutoDisenchant",
		"AutoLoot", "AutoSpawnProofer", "BedBreakAura", "BookBot", "DuraSwap",
		"FastFill", "InventorySorter", "LootSorter", "MusicAura", "PlaceNBreak",
		"QuickShulker", "SignFramePT", "SilkOnly", "SourceFill", "StairMaker",
		"SuperInstaMine", "SusNoMore", "TargetPlace", "UseItemSpam", "XCarry",
		"ItemHandler");
	
	public PortedFeaturesTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing the ported features");
		
		// looking at the sky, so the ones that click or break what you look
		// at don't take the test arena apart
		runCommand("tp @s 0 -57 0 0 -90");
		context.waitTicks(3);
		
		ArrayList<String> failed = new ArrayList<>();
		for(String name : HACKS)
		{
			if(SKIP.contains(name))
				continue;
			
			String error = context.computeOnClient(mc -> {
				Hack hack = WurstClient.INSTANCE.getHax().getHackByName(name);
				if(hack == null)
					return "not registered";
				
				try
				{
					hack.setEnabled(true);
					return null;
					
				}catch(RuntimeException e)
				{
					e.printStackTrace();
					return "onEnable threw " + e;
				}
			});
			if(error != null)
			{
				failed.add(name + ": " + error);
				continue;
			}
			
			// a few ticks for its listeners, mixins and HUD
			context.waitTicks(5);
			
			error = context.computeOnClient(mc -> {
				try
				{
					WurstClient.INSTANCE.getHax().getHackByName(name)
						.setEnabled(false);
					mc.setScreen(null);
					return null;
					
				}catch(RuntimeException e)
				{
					e.printStackTrace();
					return "onDisable threw " + e;
				}
			});
			if(error != null)
				failed.add(name + ": " + error);
			
			logger.info("Ported hack OK: {}", name);
		}
		
		testContainerOverlays();
		
		if(!failed.isEmpty())
			throw new RuntimeException(
				"Ported features failed: " + String.join("; ", failed));
		
		// clean up (some of them move you)
		runCommand("tp @s 0 -57 0 0 0");
		clearInventory();
		clearChat();
		clearParticles();
		clearToasts();
		context.waitTicks(7);
	}
	
	/**
	 * Opens the inventory and a chest with ChestSearch, EnchantmentHandler
	 * and the chest preview on - their container hooks run then.
	 */
	private void testContainerOverlays()
	{
		runWurstCommand("t ChestSearch on");
		runWurstCommand("t EnchantmentHandler on");
		runCommand("setblock 0 -57 2 chest");
		runCommand("item replace block 0 -57 2 container.0 with diamond 3");
		runCommand("tp @s 0 -57 0 0 30");
		waitForBlock(0, 0, 2, net.minecraft.world.level.block.Blocks.CHEST);
		context.waitTicks(7);
		
		input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitFor(mc -> mc.screen instanceof ContainerScreen, 40);
		context.waitTicks(5);
		input.pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitTicks(3);
		
		input.pressKey(GLFW.GLFW_KEY_E);
		context.waitTicks(5);
		input.pressKey(GLFW.GLFW_KEY_ESCAPE);
		
		runWurstCommand("t ChestSearch off");
		runWurstCommand("t EnchantmentHandler off");
		runCommand("setblock 0 -57 2 air");
		runCommand("tp @s 0 -57 0 0 0");
		context.waitTicks(3);
	}
}
