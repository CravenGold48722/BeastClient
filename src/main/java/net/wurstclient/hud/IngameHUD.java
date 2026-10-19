/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.itemhandler.ItemHandlerHud;
import net.wurstclient.other_features.RecordingModeOtf;
import net.wurstclient.util.HackPerformanceOverlay;

public final class IngameHUD implements GUIRenderListener
{
	private final WurstLogo wurstLogo = new WurstLogo();
	private final HackListHUD hackList = new HackListHUD();
	private TabGui tabGui;
	
	// HUDs of the features ported from the CevAPI fork
	private DurabilityHud durabilityHud;
	private ElytraInfoHud elytraInfoHud;
	private GameStatsHud gameStatsHud;
	private ItemHandlerHud itemHandlerHud;
	private final ChestSearchMousePreview chestSearchMousePreview =
		new ChestSearchMousePreview();
	
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
		
		renderPortedHuds(context, partialTicks);
		
		// pinned windows
		if(!(WurstClient.MC.screen instanceof ClickGuiScreen)
			&& !recordingMode.shouldHidePinnedWindows())
			clickGui.renderPinnedWindows(context, partialTicks);
	}
	
	private void renderPortedHuds(GuiGraphicsExtractor context,
		float partialTicks)
	{
		if(durabilityHud == null)
		{
			HackList hax = WurstClient.INSTANCE.getHax();
			durabilityHud = new DurabilityHud(hax.durabilityHudHack);
			elytraInfoHud = new ElytraInfoHud(hax.elytraInfoHack);
			gameStatsHud = new GameStatsHud(hax.gameStatsHack);
			itemHandlerHud = new ItemHandlerHud();
		}
		
		durabilityHud.render(context);
		elytraInfoHud.render(context);
		elytraInfoHud.renderElytraPitchStatus(context);
		gameStatsHud.render(context);
		itemHandlerHud.render(context, partialTicks);
		ClientMessageOverlay.getInstance().render(context);
		HackPerformanceOverlay.getInstance().render(context);
		
		Screen screen = WurstClient.MC.screen;
		if(!(screen instanceof ChatScreen)
			&& !(screen instanceof AbstractContainerScreen<?>))
			chestSearchMousePreview.render(context);
	}
	
	public HackListHUD getHackList()
	{
		return hackList;
	}
	
	public ChestSearchMousePreview getChestSearchMousePreview()
	{
		return chestSearchMousePreview;
	}
	
	public GameStatsHud getGameStatsHud()
	{
		return gameStatsHud;
	}
}
