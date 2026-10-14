/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.screens.EditSliderScreen;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.BeastColors;
import net.wurstclient.util.BeastFont;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.text.WText;

public final class SliderComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = BeastFont.get();
	private static final int TEXT_HEIGHT = 11;
	
	private final SliderSetting setting;
	private boolean dragging;
	
	public SliderComponent(SliderSetting setting)
	{
		this.setting = setting;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		boolean hasControlDown = context.hasControlDown();
		if(mouseY < getY() + 11)
			return;
		
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			if(hasControlDown)
				MC.setScreen(new EditSliderScreen(MC.screen, setting));
			else
				dragging = true;
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			setting.setValue(setting.getDefaultValue());
			break;
		}
	}
	
	private void handleDragging(int mouseX, int x3, int x4)
	{
		if(!dragging)
			return;
		
		if(!GUI.isLeftMouseButtonPressed())
		{
			dragging = false;
			return;
		}
		
		double sliderStartX = x3;
		double sliderWidth = x4 - x3;
		double mousePercentage = (mouseX - sliderStartX) / sliderWidth;
		
		double min = setting.getMinimum();
		double range = setting.getRange();
		double value = min + range * mousePercentage;
		
		setting.setValue(value);
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int x3 = x1 + 3;
		int x4 = x2 - 3;
		int y1 = getY();
		int y2 = y1 + getHeight();
		int y3 = y1 + TEXT_HEIGHT;
		int y4 = y3 + 4;
		int y5 = y4 + 3;
		
		handleDragging(mouseX, x3, x4);
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseY < y3;
		boolean hSlider = hovering && mouseY >= y3 || dragging;
		
		boolean grayedOut = setting.isDisabled() || setting.isLocked();
		
		if(hText)
			GUI.setTooltip(getTextTooltip());
		else if(hSlider && !dragging)
			GUI.setTooltip(getSliderTooltip());
		
		if(grayedOut)
		{
			hovering = false;
			hSlider = false;
		}
		
		// the slider part lights up under the mouse
		if(hSlider)
			context.fill(x1, y3, x2, y2, GUI.getFillColor(true));
		
		context.guiRenderState.up();
		
		// track
		RenderUtils.fill2D(context, x3, y4, x4, y5, BeastColors.ROW_BORDER);
		
		// values outside the usable range
		if(!grayedOut && setting.isLimited())
		{
			double ratio = (x4 - x3) / setting.getRange();
			float xl1 = (float)(x3
				+ ratio * (setting.getUsableMin() - setting.getMinimum()));
			float xl2 = (float)(x4
				+ ratio * (setting.getUsableMax() - setting.getMaximum()));
			RenderUtils.fill2D(context, x3, y4, xl1, y5, 0xFF4A1A1E);
			RenderUtils.fill2D(context, xl2, y4, x4, y5, 0xFF4A1A1E);
		}
		
		// filled up to the knob with the accent wave
		float xk = x3 + (x4 - x3) * (float)setting.getPercentage();
		if(grayedOut)
			RenderUtils.fill2D(context, x3, y4, xk, y5, 0xFF4A2226);
		else
			RenderUtils.fillGradient2D(context, x3, y4, xk, y5, 1F);
		
		context.guiRenderState.up();
		
		// knob
		int knobColor;
		if(grayedOut)
			knobColor = 0xFF5A5A5E;
		else
			knobColor = hSlider ? 0xFFFFFFFF : BeastColors.TEXT;
		RenderUtils.fill2D(context, xk - 2, y4 - 2, xk + 2, y5 + 2, knobColor);
		RenderUtils.drawBorder2D(context, xk - 2, y4 - 2, xk + 2, y5 + 2,
			0x80101010);
		
		// text
		String name = setting.getName();
		String value = setting.getValueString();
		int valueWidth = TR.width(value);
		ClickGui.drawAccentText(context, TR, name, x1 + 2, y1 + 2);
		ClickGui.drawAccentText(context, TR, value, x2 - valueWidth - 2,
			y1 + 2);
	}
	
	private String getTextTooltip()
	{
		String tooltip = setting.getWrappedDescription(200);
		
		if(setting.isDisabled())
			tooltip += "\n\nThis slider is disabled.";
		else if(setting.isLocked())
		{
			tooltip += "\n\nThis slider is locked to ";
			tooltip += setting.getValueString() + ".";
		}
		
		return tooltip;
	}
	
	private String getSliderTooltip()
	{
		return WText
			.translated("gui.wurst.generic.ctrl_left_click_for_precise_input")
			.append(WText.literal("\n"))
			.append(WText.translated("gui.wurst.generic.right_click_to_reset"))
			.toString();
	}
	
	@Override
	public int getDefaultWidth()
	{
		int nameWidth = TR.width(setting.getName());
		int valueWidth = TR.width(setting.getValueString());
		return nameWidth + valueWidth + 10;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return TEXT_HEIGHT * 2;
	}
}
