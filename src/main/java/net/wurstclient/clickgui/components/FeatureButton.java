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
import net.wurstclient.util.Anim;
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
	private final Anim enabledAnim = new Anim(14);
	
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
		if(hasSettings && (mouseX > getX() + getWidth() - getArrowWidth() - 1
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
			settingsWindow = new SettingsWindow(feature, getParent(), this);
			GUI.addWindow(settingsWindow);
			
		}else
		{
			settingsWindow.close();
			settingsWindow = null;
		}
	}
	
	/**
	 * The settings arrow on the right, slimmer in a narrow (bar) row so the
	 * name has more room (right-click opens the settings too).
	 */
	private int getArrowWidth()
	{
		return getWidth() < 60 ? 7 : 11;
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
		int x3 = hasSettings ? x2 - getArrowWidth() : x2;
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hFeature = hovering && mouseX < x3;
		boolean hSettings = hovering && mouseX >= x3;
		
		if(hFeature)
			GUI.setTooltip(feature.getWrappedDescription(200));
		
		// 0 = off, 1 = on, gliding between the two when toggled
		float on = Anim.ease(enabledAnim.update(feature.isEnabled() ? 1 : 0));
		
		// row - an enabled feature sits on solid dark red
		int rowColor = BeastColors.lerpColor(
			GUI.getFillColor(hFeature), hFeature
				? BeastColors.SELECTED_TINT_HOVER : BeastColors.SELECTED_TINT,
			on);
		context.fill(x1, y1, x3, y2, rowColor);
		if(hasSettings)
			context.fill(x3, y1, x2, y2, GUI.getFillColor(hSettings));
		
		context.guiRenderState.up();
		
		// a gray hairline around every row, no red outline
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
			BeastColors.ROW_BORDER);
		if(hasSettings)
			RenderUtils.drawBorder2D(context, x3, y1, x2, y2,
				BeastColors.ROW_BORDER);
		
		// enabled: an accent bar on the left edge, growing from the middle
		if(on > 0.01F)
		{
			float half = (y2 - y1) / 2F * on;
			float middle = (y1 + y2) / 2F;
			RenderUtils.fillGradient2D(context, x1, middle - half, x1 + 2,
				middle + half, 1F);
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
		
		// full size like the bar's tabs, squeezed only if it doesn't fit
		ClickGui.pushFittedText(context, TR, name, tx, ty, x3 - 2 - tx);
		
		// (cross-faded while toggling; vanilla draws text with an alpha near 0
		// fully opaque, so the faint one is left out)
		if(on < 0.97F)
			RenderUtils.drawGradientText(context, TR, name, 0, 0, 1 - on,
				false);
		if(on > 0.03F)
			context.text(TR, name, 0, 0,
				BeastColors.withOpacity(BeastColors.SELECTED_RED, on), false);
		context.pose().popMatrix();
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
