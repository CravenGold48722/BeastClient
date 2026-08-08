/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.options;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.ZoomOtf;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.WurstColors;

public class ZoomManagerScreen extends Screen implements PressAKeyCallback
{
	private Screen prevScreen;
	private Button scrollButton;
	
	public ZoomManagerScreen(Screen par1GuiScreen)
	{
		super(Component.literal(""));
		prevScreen = par1GuiScreen;
	}
	
	@Override
	public void init()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		ZoomOtf zoom = wurst.getOtfs().zoomOtf;
		SliderSetting level = zoom.getLevelSetting();
		SliderSetting scrollSpeed = zoom.getScrollSpeedSetting();
		CheckboxSetting scroll = zoom.getScrollSetting();
		
		addRenderableWidget(Button
			.builder(Component.literal("Back"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 - 100, height / 4 + 184 - 16, 200, 20).build());
		
		addRenderableWidget(Button
			.builder(
				Component.literal("Zoom Key: ")
					.append(zoom.getTranslatedKeybindName()),
				b -> minecraft.setScreen(new PressAKeyScreen(this)))
			.bounds(width / 2 - 79, height / 4 + 24 - 16, 158, 20).build());
		
		addSliderButtons(level, height / 4 + 72 - 16);
		addSliderButtons(scrollSpeed, height / 4 + 128 - 16);
		
		addRenderableWidget(
			scrollButton = Button
				.builder(
					Component.literal(
						"Use Mouse Wheel: " + onOrOff(scroll.isChecked())),
					b -> toggleScroll())
				.bounds(width / 2 - 79, height / 4 + 152 - 16, 158, 20)
				.build());
	}
	
	/**
	 * The shared More / Less / Default row that sits under a slider's label.
	 */
	private void addSliderButtons(SliderSetting setting, int y)
	{
		addRenderableWidget(Button
			.builder(Component.literal("More"), b -> setting.increaseValue())
			.bounds(width / 2 - 79, y, 50, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Less"), b -> setting.decreaseValue())
			.bounds(width / 2 - 25, y, 50, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Default"),
				b -> setting.setValue(setting.getDefaultValue()))
			.bounds(width / 2 + 29, y, 50, 20).build());
	}
	
	private void toggleScroll()
	{
		ZoomOtf zoom = WurstClient.INSTANCE.getOtfs().zoomOtf;
		CheckboxSetting scroll = zoom.getScrollSetting();
		
		scroll.setChecked(!scroll.isChecked());
		scrollButton.setMessage(Component
			.literal("Use Mouse Wheel: " + onOrOff(scroll.isChecked())));
	}
	
	private String onOrOff(boolean on)
	{
		return on ? "ON" : "OFF";
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(prevScreen);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		ZoomOtf zoom = WurstClient.INSTANCE.getOtfs().zoomOtf;
		SliderSetting level = zoom.getLevelSetting();
		SliderSetting scrollSpeed = zoom.getScrollSpeedSetting();
		
		context.centeredText(font, "Zoom Manager", width / 2, 40,
			CommonColors.WHITE);
		context.text(font, "Zoom Level: " + level.getValueString(),
			width / 2 - 75, height / 4 + 44, WurstColors.VERY_LIGHT_GRAY);
		context.text(font, "Scroll Speed: " + scrollSpeed.getValueString(),
			width / 2 - 75, height / 4 + 100, WurstColors.VERY_LIGHT_GRAY);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void setKey(String key)
	{
		WurstClient.INSTANCE.getOtfs().zoomOtf.setBoundKey(key);
		// Button text updates automatically because going back to this screen
		// calls init(). Might be different in older MC versions.
	}
}
