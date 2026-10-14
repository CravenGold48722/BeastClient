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
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.Anim;
import net.wurstclient.util.BeastFont;

/** A checkbox setting: its name, and a switch on the right. */
public final class CheckboxComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = BeastFont.get();
	
	private final CheckboxSetting setting;
	private final Anim checkedAnim = new Anim(16);
	
	public CheckboxComponent(CheckboxSetting setting)
	{
		this.setting = setting;
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
			setting.setChecked(!setting.isChecked());
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			setting.setChecked(setting.isCheckedByDefault());
			break;
		}
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY);
		if(hovering)
			GUI.setTooltip(getTooltip());
		
		if(setting.isLocked())
			hovering = false;
		
		// the whole row lights up under the mouse, it's all clickable
		if(hovering)
			context.fill(x1, y1, x2, y2, GUI.getFillColor(true));
		
		context.guiRenderState.up();
		
		ClickGuiIcons.drawSwitch(context, x2 - ClickGuiIcons.SWITCH_WIDTH - 1,
			y1 + 2, Anim.ease(checkedAnim.update(setting.isChecked() ? 1 : 0)),
			hovering, setting.isLocked());
		
		ClickGui.drawAccentText(context, TR, setting.getName(), x1 + 2, y1 + 2);
	}
	
	private String getTooltip()
	{
		String tooltip = setting.getWrappedDescription(200);
		if(setting.isLocked())
		{
			tooltip += "\n\nThis checkbox is locked to ";
			tooltip += setting.isChecked() + ".";
		}
		
		return tooltip;
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(setting.getName()) + ClickGuiIcons.SWITCH_WIDTH + 8;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
