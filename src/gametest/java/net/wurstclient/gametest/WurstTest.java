/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import static net.wurstclient.gametest.WurstClientTestHelper.*;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestClientLevelContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.fabricmc.fabric.impl.client.gametest.TestSystemProperties;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.wurstclient.gametest.tests.*;

public class WurstTest implements FabricClientGameTest
{
	public static final Logger LOGGER = LoggerFactory.getLogger("Wurst Test");
	
	/** Ticks per second while the in-world tests run (vanilla: 20). */
	public static final int TICK_RATE = 60;
	
	/**
	 * {@code -Dwurst.test.only=mace,scaffold} ({@code gametest mace scaffold}):
	 * only the tests whose class name contains one of these, ignoring case.
	 * Unset: all of them. The title screen, world and inventory checks always
	 * run - everything else builds on them.
	 */
	private static final List<String> ONLY = Arrays
		.stream(System.getProperty("wurst.test.only", "")
			.toLowerCase(Locale.ROOT).split(","))
		.map(String::trim).filter(s -> !s.isEmpty()).toList();
	
	private static int selectedRun;
	
	public static final boolean IS_MOD_COMPAT_TEST =
		System.getProperty("wurst.test.withMods") != null;
	
	@Override
	public void runTest(ClientGameTestContext context)
	{
		if(!TestSystemProperties.DISABLE_NETWORK_SYNCHRONIZER)
			throw new RuntimeException("Network synchronizer is not disabled");
		
		LOGGER.info("Starting Wurst Client GameTest");
		if(!ONLY.isEmpty())
			LOGGER.info("Only running tests matching {}", ONLY);
		hideSplashTexts(context);
		waitForTitleScreenFade(context);
		
		LOGGER.info("Reached title screen");
		assertScreenshotEquals(context, "title_screen",
			"https://i.imgur.com/xSAHDXr.png");
		
		if(isSelected("AltManagerTest"))
			AltManagerTest.testAltManagerButton(context);
		if(isSelected("VanillaSpoofTest"))
		{
			VanillaSpoofTest.testTranslationSpoof(context);
			VanillaSpoofTest.testPacketFilter(context);
		}
		
		LOGGER.info("Creating test world");
		TestWorldBuilder worldBuilder = context.worldBuilder();
		worldBuilder.adjustSettings(creator -> {
			String mcVersion = SharedConstants.getCurrentVersion().name();
			creator.setName("E2E Test " + mcVersion);
			creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			creator.getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false,
				null);
			applyFlatPresetWithSmoothStone(creator);
		});
		
		try(TestSingleplayerContext spContext = worldBuilder.create())
		{
			testInWorld(context, spContext);
			LOGGER.info("Exiting test world");
		}
		
		// a typo shouldn't look like a pass
		if(!ONLY.isEmpty() && selectedRun == 0)
			throw new RuntimeException("No test matches " + ONLY);
		
		LOGGER.info("Test complete");
	}
	
	private void testInWorld(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		TestInput input = context.getInput();
		TestClientLevelContext world = spContext.getClientLevel();
		TestServerContext server = spContext.getServer();
		
		// Disable chunk fade
		context.runOnClient(mc -> mc.options.chunkSectionFadeInTime().set(0.0));
		
		// Make sure RecordingMode isn't hiding the HUD. Without this, every
		// screenshot comparison would fail on a machine that happens to have
		// OBS or a similar program running.
		runWurstCommand(context, "setmode RecordingMode mode off");
		context.runOnClient(mc -> mc.gui.getChat().clearMessages(true));
		
		runCommand(server, "time set noon");
		runCommand(server, "tp 0 -57 0");
		runCommand(server, "fill ~ ~-3 ~ ~ ~-1 ~ smooth_stone");
		runCommand(server, "fill ~-12 ~-3 ~10 ~12 ~9 ~10 smooth_stone");
		
		LOGGER.info("Loading chunks");
		context.waitTicks(2);
		world.waitForChunksRender();
		
		assertScreenshotEquals(context, "in_game",
			"https://i.imgur.com/EfzN9Cd.png");
		
		LOGGER.info("Recording debug menu");
		input.pressKey(GLFW.GLFW_KEY_F3);
		context.takeScreenshot("debug_menu");
		input.pressKey(GLFW.GLFW_KEY_F3);
		
		LOGGER.info("Checking for broken mixins");
		MixinEnvironment.getCurrentEnvironment().audit();
		
		LOGGER.info("Opening inventory");
		input.pressKey(GLFW.GLFW_KEY_E);
		assertScreenshotEquals(context, "inventory",
			"https://i.imgur.com/LyQ5FSD.png");
		input.pressKey(GLFW.GLFW_KEY_ESCAPE);
		
		runWurstCommand(context,
			"setmode WurstLogo visibility only_when_outdated");
		runWurstCommand(context, "setcheckbox HackList animations off");
		
		// Faster ticks, faster tests. Everything below waits in ticks, and the
		// per-frame aim runs on GameClock (ticks, not real time), so it turns
		// the same per tick. (The test client runs at most one tick per frame,
		// so above the frame rate this is "as fast as it renders".)
		runCommand(server, "tick rate " + TICK_RATE);
		
		// the slowest and most fragile ones first
		run(new MaceTriggerTest(context, spContext));
		run(new ScaffoldWalkHackTest(context, spContext));
		
		run(new InGameMenuTest(context, spContext));
		run(new RecordingModeTest(context, spContext));
		run(new KeyPresserTest(context, spContext));
		run(new PacketBudgetTest(context, spContext));
		
		// TODO: Open ClickGUI and Navigator
		
		// Test Wurst hacks
		run(new AutoMineHackTest(context, spContext));
		run(new FreecamHackTest(context, spContext));
		run(new NoFallHackTest(context, spContext));
		run(new NoWeatherHackTest(context, spContext));
		run(new RestockHackTest(context, spContext));
		run(new XRayHackTest(context, spContext));
		
		// Test Wurst commands
		run(new CopyItemCmdTest(context, spContext));
		run(new GiveCmdTest(context, spContext));
		run(new ModifyCmdTest(context, spContext));
		
		// TODO: Test more Wurst features
		
		// Test special cases
		run(new PistonTest(context, spContext));
	}
	
	private static boolean isSelected(String testName)
	{
		if(ONLY.isEmpty())
			return true;
		
		String name = testName.toLowerCase(Locale.ROOT);
		boolean selected = ONLY.stream().anyMatch(name::contains);
		if(selected)
			selectedRun++;
		return selected;
	}
	
	private static void run(SingleplayerTest test)
	{
		if(isSelected(test.getClass().getSimpleName()))
			test.run();
	}
	
	// because the grass texture is randomized and smooth stone isn't
	private void applyFlatPresetWithSmoothStone(WorldCreationUiState creator)
	{
		FlatLevelGeneratorSettings config = ((FlatLevelSource)creator
			.getSettings().selectedDimensions().overworld()).settings();
		
		List<FlatLayerInfo> layers =
			List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
				new FlatLayerInfo(2, Blocks.DIRT),
				new FlatLayerInfo(1, Blocks.SMOOTH_STONE));
		
		creator.updateDimensions(
			(drm, dorHolder) -> dorHolder.replaceOverworldGenerator(drm,
				new FlatLevelSource(config.withBiomeAndLayers(layers,
					config.structureOverrides(), config.getBiome()))));
	}
}
