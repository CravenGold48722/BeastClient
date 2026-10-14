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
import net.wurstclient.settings.Setting;
import net.wurstclient.util.BeastFont;

public abstract class AbstractListEditButton extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = BeastFont.get();
	
	private final String buttonText = "Edit...";
	private final int buttonWidth = TR.width(buttonText);
	
	protected abstract void openScreen();
	
	protected abstract String getText();
	
	protected abstract Setting getSetting();
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(mouseX < getX() + getWidth() - buttonWidth - 4)
			return;
		
		openScreen();
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int x3 = x2 - buttonWidth - 4;
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseX < x3;
		boolean hBox = hovering && mouseX >= x3;
		
		if(hText)
			GUI.setTooltip(getSetting().getWrappedDescription(200));
		
		// button
		context.fill(x3, y1, x2, y2, getFillColor(hBox));
		ClickGui.drawOutline(context, x3, y1, x2, y2);
		
		// text
		context.guiRenderState.up();
		ClickGui.drawAccentText(context, TR, getText(), x1, y1 + 2);
		ClickGui.drawAccentText(context, TR, buttonText, x3 + 2, y1 + 2);
	}
	
	private int getFillColor(boolean hovering)
	{
		return GUI.getFillColor(hovering);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(getText()) + buttonWidth + 6;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
