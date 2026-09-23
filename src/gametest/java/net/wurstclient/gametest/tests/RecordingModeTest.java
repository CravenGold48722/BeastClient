/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Checks that RecordingMode can be switched on and off from Minecraft's own
 * options screen, without opening any Wurst GUI.
 */
public final class RecordingModeTest extends SingleplayerTest
{
	private static final String LABEL = "Recording Mode: ";
	
	public RecordingModeTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		runWurstCommand("setmode RecordingMode mode off");
		
		logger.info("Opening Minecraft's options screen");
		context.runOnClient(
			mc -> mc.setScreen(new OptionsScreen(mc.screen, mc.options, true)));
		context.waitTick();
		
		assertButtonSays("Off");
		
		logger.info("Cycling RecordingMode with the vanilla options button");
		clickRecordingModeButton();
		assertButtonSays("Auto");
		assertModeIs("Auto");
		
		clickRecordingModeButton();
		assertButtonSays("Always");
		assertModeIs("Always");
		if(!context.computeOnClient(
			mc -> WurstClient.INSTANCE.getOtfs().recordingModeOtf.isActive()))
			failWithScreenshot("recording_mode_not_active",
				"RecordingMode isn't active",
				"Set the mode to Always, but the feature didn't turn itself on.");
		
		clickRecordingModeButton();
		assertButtonSays("Off");
		assertModeIs("Off");
		
		input.pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitTick();
	}
	
	private void clickRecordingModeButton()
	{
		Button button = findRecordingModeButton();
		if(button == null)
			failWithScreenshot("recording_mode_button_missing",
				"No Recording Mode button",
				"Minecraft's options screen has no button labeled \"" + LABEL
					+ "...\", so RecordingMode can't be toggled there.");
		
		context.runOnClient(mc -> button
			.onPress(new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)));
		context.waitTick();
	}
	
	private Button findRecordingModeButton()
	{
		return context.computeOnClient(mc -> {
			if(!(mc.screen instanceof OptionsScreen screen))
				return null;
			
			for(GuiEventListener child : screen.children())
				if(child instanceof Button button
					&& button.getMessage().getString().startsWith(LABEL))
					return button;
				
			return null;
		});
	}
	
	private void assertButtonSays(String mode)
	{
		Button button = findRecordingModeButton();
		if(button == null)
			failWithScreenshot("recording_mode_button_missing",
				"No Recording Mode button",
				"Minecraft's options screen has no button labeled \"" + LABEL
					+ "...\", so RecordingMode can't be toggled there.");
		
		String message =
			context.computeOnClient(mc -> button.getMessage().getString());
		if(!message.equals(LABEL + mode))
			failWithScreenshot("recording_mode_wrong_label",
				"Wrong Recording Mode button label", "Expected \"" + LABEL
					+ mode + "\", found \"" + message + "\".");
	}
	
	private void assertModeIs(String mode)
	{
		String actual = context.computeOnClient(
			mc -> WurstClient.INSTANCE.getOtfs().recordingModeOtf
				.getButtonText());
		if(!actual.equals(LABEL + mode))
			throw new RuntimeException("Expected RecordingMode to be " + mode
				+ ", but it is \"" + actual + "\"");
	}
}
