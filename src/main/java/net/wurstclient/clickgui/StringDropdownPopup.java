/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.util.BeastFont;

/**
 * The list of a {@link StringDropdownSetting}, like {@link ComboBoxPopup}
 * for enums. Shows at most {@link #MAX_ROWS} options; the mouse wheel isn't
 * needed for the short lists the ported features use, the rest are cut off
 * with "...".
 */
public final class StringDropdownPopup extends Popup
{
	private static final ClickGui GUI = WurstClient.INSTANCE.getGui();
	private static final Font TR = BeastFont.get();
	private static final int MAX_ROWS = 16;
	
	private final StringDropdownSetting setting;
	private final int popupWidth;
	
	public StringDropdownPopup(Component owner, StringDropdownSetting setting,
		int popupWidth)
	{
		super(owner);
		this.setting = setting;
		this.popupWidth = popupWidth;
		
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
		
		setX(owner.getWidth() - getWidth());
		setY(owner.getHeight());
	}
	
	private List<String> getShownValues()
	{
		List<String> values = setting.getValues().stream()
			.filter(v -> !v.equals(setting.getSelected())).toList();
		return values.size() > MAX_ROWS ? values.subList(0, MAX_ROWS) : values;
	}
	
	@Override
	public void handleMouseClick(int mouseX, int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		int row = (mouseY - getY()) / 11;
		List<String> values = getShownValues();
		if(row < 0 || row >= values.size())
			return;
		
		setting.setSelected(values.get(row));
		close();
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering =
			mouseX >= x1 && mouseY >= y1 && mouseX < x2 && mouseY < y2;
		if(hovering)
			GUI.setTooltip("");
		
		ClickGui.drawOutline(context, x1, y1, x2, y2);
		
		int yi1 = y1;
		for(String value : getShownValues())
		{
			int yi2 = yi1 + 11;
			boolean hValue = hovering && mouseY >= yi1 && mouseY < yi2;
			context.fill(x1, yi1, x2, yi2, GUI.getFillColor(hValue));
			context.guiRenderState.up();
			ClickGui.drawAccentText(context, TR, value, x1 + 2, yi1 + 2);
			yi1 = yi2;
		}
	}
	
	@Override
	public int getDefaultWidth()
	{
		return popupWidth + 15;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return Math.max(1, getShownValues().size()) * 11;
	}
}
