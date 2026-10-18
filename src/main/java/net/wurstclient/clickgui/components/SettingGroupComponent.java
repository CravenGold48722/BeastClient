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
import net.minecraft.util.Mth;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.Window;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.BeastFont;
import net.wurstclient.util.ChatUtils;

/**
 * A row that opens a {@link SettingGroup}'s settings in their own window next
 * to the settings window, the way a hack's row opens its settings.
 */
public final class SettingGroupComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = BeastFont.get();
	
	private final SettingGroup group;
	private Window popup;
	
	public SettingGroupComponent(SettingGroup group, boolean allowPopout)
	{
		this.group = Objects.requireNonNull(group);
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT
			&& mouseButton != GLFW.GLFW_MOUSE_BUTTON_RIGHT)
			return;
		
		if(isPopupOpen())
		{
			popup.close();
			popup = null;
			return;
		}
		
		popup = new Window(group.getName());
		for(Setting child : group.getChildren())
		{
			Component c = child.getComponent();
			if(c != null)
				popup.add(c);
		}
		
		popup.setClosable(true);
		popup.setMinimizable(false);
		popup.setMaxHeight(200);
		popup.pack();
		placePopup();
		GUI.addWindow(popup);
	}
	
	private boolean isPopupOpen()
	{
		return popup != null && !popup.isClosing();
	}
	
	private void placePopup()
	{
		Window parent = getParent();
		com.mojang.blaze3d.platform.Window mcWindow = MC.getWindow();
		int scaledWidth = mcWindow.getGuiScaledWidth();
		
		int x = parent.getX() + parent.getDisplayWidth() + 5;
		if(x + popup.getWidth() > scaledWidth)
			x = parent.getX() - popup.getWidth() - 5;
		
		int scroll = parent.isScrollingEnabled() ? parent.getScrollOffset() : 0;
		int y = parent.getY() + 12 + getY() + scroll;
		if(y + popup.getHeight() > mcWindow.getGuiScaledHeight())
			y -= popup.getHeight() - 14;
		
		popup.setX(Mth.clamp(x, 0, scaledWidth));
		popup.setY(Mth.clamp(y, 0, mcWindow.getGuiScaledHeight()));
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
		
		if(hovering)
			GUI.setTooltip(ChatUtils.wrapText(group.getDescription(), 200));
		
		context.fill(x1, y1, x2, y2, GUI.getFillColor(hovering));
		ClickGui.drawOutline(context, x1, y1, x2, y2);
		
		context.guiRenderState.up();
		ClickGui.drawAccentText(context, TR, group.getName(), x1 + 2, y1 + 2);
		String arrow = isPopupOpen() ? "<" : ">";
		ClickGui.drawAccentText(context, TR, arrow, x2 - TR.width(arrow) - 2,
			y1 + 2);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(group.getName()) + TR.width(" >") + 6;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
