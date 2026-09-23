/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.other_features.RecordingModeOtf;

public final class IngameHUD implements GUIRenderListener
{
	private final WurstLogo wurstLogo = new WurstLogo();
	private final HackListHUD hackList = new HackListHUD();
	private TabGui tabGui;
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		if(tabGui == null)
			tabGui = new TabGui();
		
		ClickGui clickGui = WurstClient.INSTANCE.getGui();
		RecordingModeOtf recordingMode =
			WurstClient.INSTANCE.getOtfs().recordingModeOtf;
		
		clickGui.updateColors();
		
		if(!recordingMode.shouldHideLogo())
			wurstLogo.render(context);
		
		if(!recordingMode.shouldHideHackList())
			hackList.render(context, partialTicks);
		
		if(!recordingMode.shouldHideTabGui())
			tabGui.render(context, partialTicks);
		
		// pinned windows
		if(!(WurstClient.MC.screen instanceof ClickGuiScreen)
			&& !recordingMode.shouldHidePinnedWindows())
			clickGui.renderPinnedWindows(context, partialTicks);
	}
	
	public HackListHUD getHackList()
	{
		return hackList;
	}
}
