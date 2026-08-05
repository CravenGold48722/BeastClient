/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BeastColors;

@DontSaveState
@DontBlock
@SearchTags({"click gui", "WindowGUI", "window gui", "HackMenu", "hack menu"})
public final class ClickGuiHack extends Hack
{
	// Flat near-black panel, so the moving red accent gradient is what carries
	// the whole look.
	private static final float[] bgColor = {0.055F, 0.055F, 0.063F};
	
	private final ColorSetting acColor =
		new ColorSetting("Accent", "Accent color", new Color(0x0B0B0D));
	
	private static final int txtColor = BeastColors.TEXT;
	
	/** True while the screen itself is driving the hack back to disabled. */
	private boolean closingScreen;
	
	private final SliderSetting opacity = new SliderSetting("Opacity", 0.68,
		0.15, 0.85, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting ttOpacity = new SliderSetting("Tooltip opacity",
		0.75, 0.15, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting maxHeight = new SliderSetting("Max height",
		"Maximum window height\n" + "0 = no limit", 200, 0, 1000, 50,
		ValueDisplay.INTEGER);
	
	private final SliderSetting maxSettingsHeight =
		new SliderSetting("Max settings height",
			"Maximum height for settings windows\n" + "0 = no limit", 200, 0,
			1000, 50, ValueDisplay.INTEGER);
	
	public ClickGuiHack()
	{
		super("ClickGUI");
		addSetting(acColor);
		addSetting(opacity);
		addSetting(ttOpacity);
		addSetting(maxHeight);
		addSetting(maxSettingsHeight);
	}
	
	/**
	 * The hack stays enabled for as long as the screen is actually open, so
	 * that opening it announces "on" and closing it announces "off". Closing
	 * is reported back by {@link ClickGuiScreen#removed()}.
	 */
	@Override
	protected void onEnable()
	{
		if(!(MC.screen instanceof ClickGuiScreen))
			MC.setScreen(new ClickGuiScreen(WURST.getGui()));
	}
	
	/**
	 * Called by {@link ClickGuiScreen#removed()} when the screen goes away, so
	 * that closing the GUI with Esc reports the hack as off.
	 */
	public void onScreenClosed()
	{
		if(!isEnabled())
			return;
		
		closingScreen = true;
		try
		{
			Hack.markUserInitiatedToggle();
			setEnabled(false);
			
		}finally
		{
			closingScreen = false;
		}
	}
	
	@Override
	protected void onDisable()
	{
		// Reached when the GUI is dismissed by pressing the keybind again
		// rather than Esc, in which case the screen still has to be taken
		// down.
		//
		// Skipped when the screen is the thing that started this, because
		// Minecraft.setScreen() calls removed() while its screen field still
		// points at the old screen - closing it again from in here would
		// re-enter setScreen() in the middle of a setScreen() call.
		if(closingScreen)
			return;
		
		if(MC.screen instanceof ClickGuiScreen)
			MC.setScreen(null);
	}
	
	public float[] getBackgroundColor()
	{
		return bgColor.clone();
	}
	
	public float[] getAccentColor()
	{
		return acColor.getColorF();
	}
	
	public int getTextColor()
	{
		return txtColor;
	}
	
	public float getOpacity()
	{
		return opacity.getValueF();
	}
	
	public float getTooltipOpacity()
	{
		return ttOpacity.getValueF();
	}
	
	public int getMaxHeight()
	{
		return maxHeight.getValueI();
	}
	
	public int getMaxSettingsHeight()
	{
		return maxSettingsHeight.getValueI();
	}
}
