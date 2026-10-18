/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.util.BeastFont;

/**
 * A plain clickable button row, for {@link net.wurstclient.settings
 * .ButtonSetting} and the windows of the features ported from the CevAPI fork.
 */
public final class ButtonComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = BeastFont.get();
	
	private final Supplier<String> text;
	private final Runnable action;
	
	public ButtonComponent(String text, Runnable action)
	{
		this(() -> text, action);
	}
	
	public ButtonComponent(Supplier<String> text, Runnable action)
	{
		this.text = text;
		this.action = action;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(isHovering((int)mouseX, (int)mouseY))
			action.run();
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int y1 = getY();
		int x2 = x1 + getWidth();
		int y2 = y1 + getHeight();
		boolean hovering = isHovering(mouseX, mouseY);
		
		context.fill(x1, y1, x2, y2, GUI.getFillColor(hovering));
		ClickGui.drawOutline(context, x1, y1, x2, y2);
		
		String label = text.get();
		context.guiRenderState.up();
		ClickGui.drawAccentText(context, TR, label,
			(x1 + x2 - TR.width(label)) / 2, y1 + 2);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(text.get()) + 8;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
