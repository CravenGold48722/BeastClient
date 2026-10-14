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
 * The ClickGUI with the real mouse: tabs in the bar along the top open and
 * close their dropdown, a middle click marks a hack as Important (saved,
 * listed in the Important dropdown), a right click opens and closes its
 * settings, a middle click in the Important dropdown unmarks it again.
 * Leaves {@code wurstlogo_inverted.png}, {@code clickgui_bar.png},
 * {@code clickgui_settings.png} and {@code clickgui_rows.png} in the
 * screenshots folder to look at.
 */
public final class ClickGuiTest extends SingleplayerTest
{
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
		
		// the version text in the opposite gray of the sky behind it
		runWurstCommand("setmode WurstLogo visibility always");
		context.waitTicks(2);
		context.takeScreenshot("wurstlogo_inverted");
		runWurstCommand("setmode WurstLogo visibility only_when_outdated");
		
		logger.info("Opening the ClickGUI");
		context.runOnClient(mc -> hax().clickGuiHack.setEnabled(true));
		context.waitFor(mc -> mc.screen instanceof ClickGuiScreen);
		context.waitTicks(2);
		checkBar();
		
		logger.info("Opening Combat from its tab");
		clickTab("Combat");
		checkDropdown("Combat");
		context.takeScreenshot("clickgui_bar");
		
		dragTabTest();
		dragWindowTest();
		
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
		
		logger.info("Closing Combat and opening Important");
		clickTab("Combat");
		if(context.computeOnClient(mc -> !findWindow("Combat").isMinimized()))
			throw fail("Clicking the tab again didn't close Combat");
		clickTab("Important");
		checkDropdown("Important");
		
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
		
		clickTab("Important");
		context.runOnClient(mc -> {
			for(Window window : gui().getWindows())
				if(window.isInBar())
				{
					window.setMinimized(true);
					window.setDocked(true);
				}
			hax().clickGuiHack.setEnabled(false);
		});
		context.waitFor(mc -> mc.screen == null);
		input.setCursorPos(0, 0);
		context.waitTicks(2);
	}
	
	/**
	 * Every tab inside the bar, on the screen, not overlapping another,
	 * Important in the middle.
	 */
	private void checkBar()
	{
		String problem = context.computeOnClient(mc -> {
			int width = mc.getWindow().getGuiScaledWidth();
			int barHeight = gui().getBarHeight();
			List<int[]> tabs =
				gui().getWindows().stream().filter(Window::isInBar)
					.map(w -> gui().getTabBounds(w)).toList();
			if(tabs.contains(null))
				return "a window has no tab";
			
			for(int[] a : tabs)
			{
				if(a[0] < 0 || a[0] + a[2] > width || a[1] < 0
					|| a[1] + a[3] > barHeight)
					return "a tab is outside the bar: x " + a[0] + ", y "
						+ a[1];
				for(int[] b : tabs)
					if(a != b && a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
						&& a[1] < b[1] + b[3] && b[1] < a[1] + a[3])
						return "two tabs overlap at x " + a[0];
			}
			
			int[] important = gui().getTabBounds(findWindow("Important"));
			int middle = important[0] + important[2] / 2;
			if(Math.abs(middle - width / 2) > width / 6)
				return "Important isn't in the middle (" + middle + " of "
					+ width + ")";
			return null;
		});
		if(problem != null)
			throw fail(problem);
	}
	
	/**
	 * The window is open, right below its tab (docked), one column, and
	 * ends above the bottom of the screen (scrolling if it has to).
	 */
	private void checkDropdown(String title)
	{
		String problem = context.computeOnClient(mc -> {
			int width = mc.getWindow().getGuiScaledWidth();
			int height = mc.getWindow().getGuiScaledHeight();
			Window window = findWindow(title);
			if(window.isMinimized())
				return "clicking its tab didn't open " + title;
			window.validate();
			if(window.getY() != gui().getBarHeight() + 2)
				return title + " doesn't open right below the bar";
			int[] tab = gui().getTabBounds(window);
			if(window.getX() != Math.min(tab[0], width - window.getWidth()))
				return title + " doesn't open below its tab";
			for(int i = 0; i < window.countChildren(); i++)
				if(window.getChild(i).getX() != 2)
					return title + " isn't one column";
			if(window.getX() < 0 || window.getX() + window.getWidth() > width
				|| window.getY() + window.getHeight() > height)
				return title + " goes off the screen";
			return null;
		});
		if(problem != null)
			throw fail(problem);
	}
	
	/**
	 * Drags Combat's tab onto Render's slot: they swap, it's saved, and
	 * the other tabs stay where they were. Then back.
	 */
	private void dragTabTest()
	{
		logger.info("Dragging Combat's tab onto Render's slot");
		List<Window> before =
			context.computeOnClient(mc -> List.copyOf(gui().getSlotOrder()));
		dragTab("Combat", "Render");
		List<Window> after =
			context.computeOnClient(mc -> List.copyOf(gui().getSlotOrder()));
		int combat = before.indexOf(findWindow("Combat"));
		int render = before.indexOf(findWindow("Render"));
		for(int i = 0; i < before.size(); i++)
		{
			Window expected = i == combat ? before.get(render)
				: i == render ? before.get(combat) : before.get(i);
			if(after.get(i) != expected)
				throw fail("Dragging the tab didn't swap Combat and Render: "
					+ after.stream().map(Window::getTitle).toList());
		}
		String saved = readWindowsFile();
		if(saved.indexOf("\"Render\",") > saved.indexOf("\"Combat\","))
			throw fail("windows.json doesn't have the new order");
		context.takeScreenshot("clickgui_tab_dragged");
		
		dragTab("Combat", "Render");
		List<Window> back =
			context.computeOnClient(mc -> List.copyOf(gui().getSlotOrder()));
		if(!back.equals(before))
			throw fail("Dragging it back didn't restore the order");
	}
	
	/**
	 * Drags the open Combat window by its title bar: it moves, its tab
	 * doesn't, and it opens there again after being closed.
	 */
	private void dragWindowTest()
	{
		logger.info("Dragging the Combat window");
		Window combat = context.computeOnClient(mc -> findWindow("Combat"));
		int[] tabBefore =
			context.computeOnClient(mc -> gui().getTabBounds(combat));
		int[] start = context.computeOnClient(
			mc -> new int[]{combat.getX() + 10, combat.getY() + 6});
		drag(start[0], start[1], start[0] + 30, start[1] + 25);
		int[] moved = context
			.computeOnClient(mc -> new int[]{combat.getX(), combat.getY()});
		if(moved[0] != start[0] - 10 + 30 || moved[1] != start[1] - 6 + 25)
			throw fail("The window didn't follow the mouse: " + moved[0] + ", "
				+ moved[1]);
		if(context.computeOnClient(mc -> combat.isDocked()))
			throw fail("A dragged window is still docked to its tab");
		if(!java.util.Arrays.equals(tabBefore,
			context.computeOnClient(mc -> gui().getTabBounds(combat))))
			throw fail("Dragging the window moved its tab");
		
		clickTab("Combat");
		clickTab("Combat");
		int[] reopened = context
			.computeOnClient(mc -> new int[]{combat.getX(), combat.getY()});
		if(!java.util.Arrays.equals(moved, reopened))
			throw fail("The window didn't reopen where it was left");
		
		// docked again for the rest of the test
		context.runOnClient(mc -> combat.setDocked(true));
		context.waitTicks(2);
	}
	
	/** Drags one tab onto another's slot with the real mouse. */
	private void dragTab(String title, String onto)
	{
		int[] from = context
			.computeOnClient(mc -> gui().getTabBounds(findWindow(title)));
		int[] to =
			context.computeOnClient(mc -> gui().getTabBounds(findWindow(onto)));
		drag(from[0] + from[2] / 2, from[1] + from[3] / 2, to[0] + to[2] / 2,
			to[1] + to[3] / 2);
	}
	
	/** Holds the left button at (x1, y1), moves to (x2, y2), lets go. */
	private void drag(int x1, int y1, int x2, int y2)
	{
		double scale =
			context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		input.setCursorPos(x1 * scale, y1 * scale);
		context.waitTicks(1);
		input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(1);
		for(int i = 1; i <= 4; i++)
		{
			input.setCursorPos((x1 + (x2 - x1) * i / 4.0) * scale,
				(y1 + (y2 - y1) * i / 4.0) * scale);
			context.waitTicks(1);
		}
		input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}
	
	private String readWindowsFile()
	{
		Path path = context.computeOnClient(mc -> mc.gameDirectory.toPath())
			.resolve("wurst").resolve("windows.json");
		try
		{
			return Files.readString(path);
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	/** Saves a screenshot of the GUI as it is before failing. */
	private RuntimeException fail(String message)
	{
		context.takeScreenshot("clickgui_failed");
		return new RuntimeException(message);
	}
	
	/** Left-clicks the window's tab in the bar. */
	private void clickTab(String title)
	{
		double[] pos = context.computeOnClient(mc -> {
			int[] tab = gui().getTabBounds(findWindow(title));
			double scale = mc.getWindow().getGuiScale();
			return new double[]{(tab[0] + tab[2] / 2.0) * scale,
				(tab[1] + tab[3] / 2.0) * scale};
		});
		input.setCursorPos(pos[0], pos[1]);
		context.waitTicks(1);
		input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
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
