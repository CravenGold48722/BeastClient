/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.RecordingModeOtf;

/**
 * Adds the Recording Mode button to Minecraft's own options screen, so that
 * the client can be un-hidden without opening any Beast-specific GUI.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen
{
	@Unique
	private static final int BUTTON_WIDTH = 130;
	
	@Unique
	private Button recordingModeButton;
	
	private OptionsScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	@Inject(method = "init()V", at = @At("TAIL"))
	private void onInit(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		RecordingModeOtf otf = WurstClient.INSTANCE.getOtfs().recordingModeOtf;
		
		recordingModeButton = Button
			.builder(Component.literal(otf.getButtonText()),
				this::onRecordingModeButtonPressed)
			.tooltip(Tooltip.create(Component.literal(otf.getStatusText())))
			.width(BUTTON_WIDTH).build();
		
		positionRecordingModeButton();
		addRenderableWidget(recordingModeButton);
	}
	
	@Inject(method = "repositionElements()V", at = @At("TAIL"))
	private void onRepositionElements(CallbackInfo ci)
	{
		// This screen only re-arranges its layout instead of rebuilding its
		// widgets, so our button has to move itself.
		if(recordingModeButton != null)
			positionRecordingModeButton();
	}
	
	@Unique
	private void positionRecordingModeButton()
	{
		int x = 4;
		int y = height - 24;
		
		// Move up a row rather than covering the Done button on narrow screens
		if(x + BUTTON_WIDTH > width / 2 - 100)
			y -= 24;
		
		recordingModeButton.setPosition(x, y);
	}
	
	@Unique
	private void onRecordingModeButtonPressed(Button button)
	{
		RecordingModeOtf otf = WurstClient.INSTANCE.getOtfs().recordingModeOtf;
		otf.cycleMode();
		
		button.setMessage(Component.literal(otf.getButtonText()));
		button
			.setTooltip(Tooltip.create(Component.literal(otf.getStatusText())));
	}
}
