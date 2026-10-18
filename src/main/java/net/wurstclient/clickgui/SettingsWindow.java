/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import java.util.stream.Stream;

import net.minecraft.util.Mth;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.Setting;

public final class SettingsWindow extends Window
{
	public SettingsWindow(Feature feature, Window parent, Component button)
	{
		super(feature.getName() + " Settings");
		
		Stream<Setting> settings = feature.getSettings().values().stream();
		settings.filter(Setting::isVisibleInGui).map(Setting::getComponent)
			.filter(java.util.Objects::nonNull).forEach(this::add);
		
		setClosable(true);
		setMinimizable(false);
		setMaxHeight(200);
		pack();
		
		setInitialPosition(parent, button);
	}
	
	/**
	 * Right of the window, else left of it, else - a wide dropdown on a small
	 * screen - right or left of the button's own column.
	 */
	private void setInitialPosition(Window parent, Component button)
	{
		int scroll = parent.isScrollingEnabled() ? parent.getScrollOffset() : 0;
		// (the parent may be shrunk: its buttons are where it draws them)
		float scale = parent.getScale();
		int y =
			parent.getY() + Math.round((12 + button.getY() + scroll) * scale);
		
		com.mojang.blaze3d.platform.Window mcWindow =
			WurstClient.MC.getWindow();
		int scaledWidth = mcWindow.getGuiScaledWidth();
		int buttonX1 = parent.getX() + Math.round(button.getX() * scale);
		int buttonX2 = buttonX1 + Math.round(button.getWidth() * scale);
		
		int x = parent.getX() + parent.getDisplayWidth() + 5;
		if(x + getWidth() > scaledWidth)
			x = parent.getX() - getWidth() - 5;
		if(x < 0)
			x = buttonX2 + 5;
		if(x + getWidth() > scaledWidth)
			x = buttonX1 - getWidth() - 5;
		if(y + getHeight() > mcWindow.getGuiScaledHeight())
			y -= getHeight() - 14;
		
		x = Mth.clamp(x, 0, scaledWidth);
		y = Mth.clamp(y, 0, mcWindow.getGuiScaledHeight());
		
		setX(x);
		setY(y);
	}
}
