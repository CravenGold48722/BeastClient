/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.Window;
import net.wurstclient.clickgui.components.FeatureButton;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BeastFont;

/**
 * The ClickGUI with the real mouse: a middle click marks a hack as Important
 * (saved, listed in the Important window), a right click opens and closes
 * its settings, a middle click in the Important window unmarks it again.
 * Leaves {@code clickgui_settings.png} and {@code clickgui_rows.png} in the
 * screenshots folder to look at.
 */
public final class ClickGuiTest extends SingleplayerTest
{
	private static final List<String> OPENED =
		List.of("Important", "Movement", "Combat");
	
	private final Map<Window, int[]> savedPositions = new HashMap<>();
	
	public ClickGuiTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		// k ( * are wider in the BeastFont glyphs than in vanilla's
		String sample = "kkkk((**";
		int[] widths = context.computeOnClient(mc -> new int[]{
			BeastFont.get().width(sample), mc.font.width(sample)});
		logger.info("UI font width of \"" + sample + "\": " + widths[0]
			+ " (vanilla " + widths[1] + ")");
		if(widths[0] == widths[1])
			throw new RuntimeException(
				"The UI font measures like vanilla's - BeastFont not loaded?");
		
		logger.info("Opening the ClickGUI");
		context.runOnClient(mc -> hax().clickGuiHack.setEnabled(true));
		context.waitFor(mc -> mc.screen instanceof ClickGuiScreen);
		// the three windows side by side, nothing else in the way
		context.runOnClient(mc -> {
			for(Window window : gui().getWindows())
			{
				savedPositions.put(window, new int[]{window.getActualX(),
					window.getActualY(), window.isInvisible() ? 1 : 0});
				if(!OPENED.contains(window.getTitle()))
					window.setInvisible(true);
			}
			
			int x = 5;
			for(String title : OPENED)
			{
				Window window = findWindow(title);
				window.setMinimized(false);
				window.setX(x);
				window.setY(5);
				x += window.getWidth() + 20;
			}
		});
		context.waitTicks(2);
		
		logger.info("Marking AimAssist as Important with a middle click");
		Hack aimAssist = hax().aimAssistHack;
		clickRow("Combat", aimAssist, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
		context.waitTicks(1);
		if(!aimAssist.isImportant())
			throw fail("Middle click didn't mark AimAssist");
		if(findRow("Important", aimAssist) == null)
			throw fail("AimAssist isn't in the Important window");
		if(!readImportantFile().contains("\"AimAssist\""))
			throw fail("important-hacks.json doesn't list AimAssist");
		
		logger.info("Opening its settings with a right click");
		clickRow("Important", aimAssist, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitTicks(1);
		if(context
			.computeOnClient(mc -> findWindow("AimAssist Settings")) == null)
			throw fail("Right click didn't open the settings");
		
		context.takeScreenshot("clickgui_settings");
		
		logger.info("Closing them with another right click");
		clickRow("Important", aimAssist, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitTicks(1);
		if(context
			.computeOnClient(mc -> findWindow("AimAssist Settings")) != null)
			throw fail("Right click didn't close the settings");
		
		// an enabled hack, to see that style too (no toggle message)
		context.runOnClient(mc -> hax().autoSprintHack.setEnabled(true));
		input.setCursorPos(0, context
			.computeOnClient(mc -> (double)mc.getWindow().getHeight() - 1));
		context.waitTicks(2);
		context.takeScreenshot("clickgui_rows");
		
		logger.info("Unmarking AimAssist in the Important window");
		context.runOnClient(mc -> hax().autoSprintHack.setEnabled(false));
		clickRow("Important", aimAssist, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
		context.waitTicks(1);
		if(aimAssist.isImportant())
			throw fail("Middle click didn't unmark AimAssist");
		if(readImportantFile().contains("\"AimAssist\""))
			throw fail("important-hacks.json still lists AimAssist");
		
		context.runOnClient(mc -> {
			Window settings = findWindow("AimAssist Settings");
			if(settings != null)
				settings.close();
			savedPositions.forEach((window, pos) -> {
				window.setX(pos[0]);
				window.setY(pos[1]);
				window.setInvisible(pos[2] == 1);
				if(OPENED.contains(window.getTitle()))
					window.setMinimized(true);
			});
			hax().clickGuiHack.setEnabled(false);
		});
		context.waitFor(mc -> mc.screen == null);
		input.setCursorPos(0, 0);
		context.waitTicks(2);
	}
	
	/** Saves a screenshot of the GUI as it is before failing. */
	private RuntimeException fail(String message)
	{
		context.takeScreenshot("clickgui_failed");
		return new RuntimeException(message);
	}
	
	/** Clicks the hack's row in the given window, left of its arrow. */
	private void clickRow(String windowTitle, Hack hack, int button)
	{
		double[] pos = context.computeOnClient(mc -> {
			Window window = findWindow(windowTitle);
			Component row = findRow(windowTitle, hack);
			if(window == null || row == null)
				throw new RuntimeException(
					hack.getName() + " isn't in " + windowTitle);
			
			// scroll it into view
			int top = row.getY() + window.getScrollOffset();
			if(top < 0 || top + row.getHeight() > window.getHeight() - 13)
				window.setScrollOffset(-row.getY());
			
			double scale = mc.getWindow().getGuiScale();
			double x = window.getX() + row.getX() + 8;
			double y = window.getY() + 13 + window.getScrollOffset()
				+ row.getY() + row.getHeight() / 2.0;
			return new double[]{x * scale, y * scale};
		});
		input.setCursorPos(pos[0], pos[1]);
		context.waitTicks(1);
		input.pressMouse(button);
	}
	
	private static Component findRow(String windowTitle, Hack hack)
	{
		Window window = findWindow(windowTitle);
		if(window == null)
			return null;
		
		for(int i = 0; i < window.countChildren(); i++)
			if(window.getChild(i) instanceof FeatureButton button
				&& button.getFeature() == hack)
				return button;
			
		return null;
	}
	
	private static Window findWindow(String title)
	{
		for(Window window : gui().getWindows())
			if(window.getTitle().equals(title) && !window.isClosing())
				return window;
			
		return null;
	}
	
	private String readImportantFile()
	{
		Path path = context.computeOnClient(mc -> mc.gameDirectory.toPath())
			.resolve("wurst").resolve("important-hacks.json");
		try
		{
			return Files.exists(path) ? Files.readString(path) : "";
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private static ClickGui gui()
	{
		return WurstClient.INSTANCE.getGui();
	}
	
	private static net.wurstclient.hack.HackList hax()
	{
		return WurstClient.INSTANCE.getHax();
	}
}
