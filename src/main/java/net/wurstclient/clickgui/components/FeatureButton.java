/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.Objects;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.Feature;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.SettingsWindow;
import net.wurstclient.clickgui.Window;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.util.BeastColors;
import net.wurstclient.util.BeastFont;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

/**
 * One feature's row: left click toggles it (or opens its settings if it has
 * nothing to toggle), the arrow on the right or a right click opens its
 * settings, a middle click marks a hack as Important.
 */
public final class FeatureButton extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = BeastFont.get();
	
	/** Space the golden shield takes in front of an Important hack's name. */
	private static final int SHIELD_WIDTH = 10;
	
	private final Feature feature;
	private final boolean hasSettings;
	
	private Window settingsWindow;
	
	public FeatureButton(Feature feature)
	{
		this.feature = Objects.requireNonNull(feature);
		hasSettings = !feature.getSettings().isEmpty();
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			handleLeftClick(mouseX);
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			if(hasSettings)
				toggleSettingsWindow();
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_MIDDLE:
			if(feature instanceof Hack hack)
				hack.setImportant(!hack.isImportant());
			break;
		}
	}
	
	private void handleLeftClick(double mouseX)
	{
		if(hasSettings && (mouseX > getX() + getWidth() - 12
			|| feature.getPrimaryAction().isEmpty()))
		{
			toggleSettingsWindow();
			return;
		}
		
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		if(tooManyHax.isEnabled() && tooManyHax.isBlocked(feature))
		{
			ChatUtils.error(feature.getName() + " is blocked by TooManyHax.");
			return;
		}
		
		feature.doPrimaryAction();
	}
	
	public Feature getFeature()
	{
		return feature;
	}
	
	private boolean isSettingsWindowOpen()
	{
		return settingsWindow != null && !settingsWindow.isClosing();
	}
	
	private void toggleSettingsWindow()
	{
		if(!isSettingsWindowOpen())
		{
			settingsWindow = new SettingsWindow(feature, getParent(), getY());
			GUI.addWindow(settingsWindow);
			
		}else
		{
			settingsWindow.close();
			settingsWindow = null;
		}
	}
	
	private boolean isImportant()
	{
		return feature instanceof Hack hack && hack.isImportant();
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int x3 = hasSettings ? x2 - 11 : x2;
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hFeature = hovering && mouseX < x3;
		boolean hSettings = hovering && mouseX >= x3;
		
		if(hFeature)
			GUI.setTooltip(feature.getWrappedDescription(200));
		
		boolean enabled = feature.isEnabled();
		
		// row - an enabled feature sits on solid dark red
		int rowColor;
		if(enabled)
			rowColor = hFeature ? BeastColors.SELECTED_TINT_HOVER
				: BeastColors.SELECTED_TINT;
		else
			rowColor = GUI.getFillColor(hFeature);
		context.fill(x1, y1, x3, y2, rowColor);
		if(hasSettings)
			context.fill(x3, y1, x2, y2, GUI.getFillColor(hSettings));
		
		context.guiRenderState.up();
		
		// outlines: the accent wave around enabled features, a hairline
		// around the rest
		if(enabled)
		{
			ClickGui.drawAccentBorder(context, x1, y1, x2, y2);
			if(hasSettings)
				RenderUtils.drawGradientVLine2D(context, x3, y1, y2, 1F);
			
			// accent bar on the left edge
			RenderUtils.fillGradient2D(context, x1, y1, x1 + 2, y2, 1F);
			
		}else
		{
			RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
				BeastColors.ROW_BORDER);
			if(hasSettings)
				RenderUtils.drawBorder2D(context, x3, y1, x2, y2,
					BeastColors.ROW_BORDER);
		}
		
		// arrow
		if(hasSettings)
			ClickGuiIcons.drawMinimizeArrow(context, x3, y1 + 0.5F, x2,
				y2 - 0.5F, hSettings, !isSettingsWindowOpen());
		
		// name, behind a golden shield if Important
		int tx = x1 + 5;
		int ty = y1 + 2;
		if(isImportant())
		{
			ClickGuiIcons.drawShield(context, tx - 1, y1 + 2);
			tx += SHIELD_WIDTH;
		}
		
		// An enabled feature is labelled in flat light red rather than the
		// moving gradient, so that "this is on" stays readable at a glance.
		String name = feature.getName();
		if(enabled)
			context.text(TR, name, tx, ty, BeastColors.SELECTED_RED, false);
		else
			ClickGui.drawAccentText(context, TR, name, tx, ty);
	}
	
	@Override
	public int getDefaultWidth()
	{
		int width = TR.width(feature.getName()) + 9;
		if(isImportant())
			width += SHIELD_WIDTH;
		if(hasSettings)
			width += 11;
		return width;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
