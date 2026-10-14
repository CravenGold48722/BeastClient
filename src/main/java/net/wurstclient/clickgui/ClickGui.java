/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.components.FeatureButton;
import net.wurstclient.clickgui.components.HintComponent;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.BeastColors;
import net.wurstclient.util.BeastFont;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.json.JsonUtils;

public final class ClickGui
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Minecraft MC = WurstClient.MC;
	
	private final ArrayList<Window> windows = new ArrayList<>();
	private final ArrayList<Popup> popups = new ArrayList<>();
	private final Path windowsFile;
	
	private static final String IMPORTANT = "Important";
	private Window importantWindow;
	
	/**
	 * The order the tabs get the middle of the bar in. The ones at the end
	 * go to its ends.
	 */
	private static final List<String> BY_IMPORTANCE =
		List.of(IMPORTANT, "Combat", "Render", "Movement", "Blocks", "Items",
			"Other", "Chat", "Fun", "UI Settings");
	
	/** Version of the bar layout in windows.json. */
	private static final String LAYOUT = "_layout";
	private static final int LAYOUT_VERSION = 2;
	
	/** One row of tabs in the bar along the top. */
	private static final int TAB_HEIGHT = 13;
	private static final int TAB_PADDING = 5;
	private final ArrayList<Tab> tabs = new ArrayList<>();
	private int barHeight = TAB_HEIGHT;
	
	/** A window's tab in the bar; the window hangs below it when open. */
	private record Tab(Window window, int x, int y, int width)
	{
		boolean contains(int mouseX, int mouseY)
		{
			return mouseX >= x && mouseX < x + width && mouseY >= y
				&& mouseY < y + TAB_HEIGHT;
		}
	}
	
	private float[] bgColor = new float[3];
	private float[] acColor = new float[3];
	private int txtColor;
	private float opacity;
	private float ttOpacity;
	private int maxSettingsHeight;
	
	private String tooltip = "";
	
	private boolean leftMouseButtonPressed;
	
	public ClickGui(Path windowsFile)
	{
		this.windowsFile = windowsFile;
	}
	
	public void init()
	{
		updateColors();
		
		importantWindow = new Window(IMPORTANT);
		fillImportantWindow();
		windows.add(importantWindow);
		
		LinkedHashMap<Category, Window> windowMap = new LinkedHashMap<>();
		for(Category category : Category.values())
			windowMap.put(category, new Window(category.getName()));
		
		ArrayList<Feature> features = new ArrayList<>();
		features.addAll(WURST.getHax().getAllHax());
		features.addAll(WURST.getCmds().getAllCmds());
		features.addAll(WURST.getOtfs().getAllOtfs());
		
		for(Feature f : features)
			if(f.getCategory() != null)
				windowMap.get(f.getCategory()).add(new FeatureButton(f));
			
		windows.addAll(windowMap.values());
		
		Window uiSettings = new Window("UI Settings");
		uiSettings.add(new FeatureButton(WURST.getOtfs().wurstLogoOtf));
		uiSettings.add(new FeatureButton(WURST.getOtfs().hackListOtf));
		uiSettings.add(new FeatureButton(WURST.getOtfs().keybindManagerOtf));
		uiSettings.add(new FeatureButton(WURST.getOtfs().wurstOptionsOtf));
		uiSettings.add(new FeatureButton(WURST.getOtfs().recordingModeOtf));
		ClickGuiHack clickGuiHack = WURST.getHax().clickGuiHack;
		Stream<Setting> settings = clickGuiHack.getSettings().values().stream();
		settings.map(Setting::getComponent).forEach(c -> uiSettings.add(c));
		windows.add(uiSettings);
		
		for(Window window : windows)
		{
			window.setMinimized(true);
			window.setColumns(true);
		}
		
		windows.add(WurstClient.INSTANCE.getHax().radarHack.getWindow());
		
		int x = 5;
		int y = 5;
		int scaledWidth = MC.getWindow().getGuiScaledWidth();
		for(Window window : windows)
		{
			window.pack();
			
			if(x + window.getWidth() + 5 > scaledWidth)
			{
				x = 5;
				y += 18;
			}
			
			window.setX(x);
			window.setY(y);
			x += window.getWidth() + 5;
		}
		
		JsonObject json;
		try(BufferedReader reader = Files.newBufferedReader(windowsFile))
		{
			json = JsonParser.parseReader(reader).getAsJsonObject();
			
		}catch(NoSuchFileException e)
		{
			saveWindows();
			return;
			
		}catch(Exception e)
		{
			System.out.println("Failed to load " + windowsFile.getFileName());
			e.printStackTrace();
			
			saveWindows();
			return;
		}
		
		// windows.json from before the bar: all closed (several dropdowns
		// that were open would cover each other below the bar)
		boolean oldLayout = !(json.get(LAYOUT) instanceof JsonObject layout
			&& layout.get("version") instanceof JsonElement version
			&& version.isJsonPrimitive()
			&& version.getAsJsonPrimitive().isNumber()
			&& version.getAsInt() >= LAYOUT_VERSION);
		
		for(Window window : windows)
		{
			JsonElement jsonWindow = json.get(window.getTitle());
			if(jsonWindow == null || !jsonWindow.isJsonObject())
				continue;
			
			// the bar places its own windows
			if(!window.hasColumns())
			{
				JsonElement jsonX = jsonWindow.getAsJsonObject().get("x");
				if(jsonX.isJsonPrimitive()
					&& jsonX.getAsJsonPrimitive().isNumber())
					window.setX(jsonX.getAsInt());
				
				JsonElement jsonY = jsonWindow.getAsJsonObject().get("y");
				if(jsonY.isJsonPrimitive()
					&& jsonY.getAsJsonPrimitive().isNumber())
					window.setY(jsonY.getAsInt());
			}
			
			JsonElement jsonMinimized =
				jsonWindow.getAsJsonObject().get("minimized");
			if(jsonMinimized.isJsonPrimitive()
				&& jsonMinimized.getAsJsonPrimitive().isBoolean()
				&& !(oldLayout && window.hasColumns()))
				window.setMinimized(jsonMinimized.getAsBoolean());
			
			// only Radar's window is pinned, and it always is
			JsonElement jsonPinned = jsonWindow.getAsJsonObject().get("pinned");
			if(window.isPinnable() && jsonPinned.isJsonPrimitive()
				&& jsonPinned.getAsJsonPrimitive().isBoolean())
				window.setPinned(jsonPinned.getAsBoolean());
		}
		
		saveWindows();
	}
	
	/**
	 * The tabs side by side in the bar along the top, centred, the most
	 * important in the middle and the rest further out the less important
	 * they are. Tabs that don't fit go to a second row of the bar (the less
	 * important ones), centred the same way. The windows hang below the bar.
	 */
	private void layOutTabs(int scaledWidth)
	{
		ArrayList<Window> ranked = new ArrayList<>();
		for(String title : BY_IMPORTANCE)
			for(Window window : windows)
				if(window.hasColumns() && window.getTitle().equals(title))
					ranked.add(window);
		for(Window window : windows)
			if(window.hasColumns() && !ranked.contains(window))
				ranked.add(window);
			
		Font tr = BeastFont.get();
		tabs.clear();
		int row = 0;
		int next = 0;
		while(next < ranked.size())
		{
			// as many as fit, most important first, from the middle out
			ArrayDeque<Window> inRow = new ArrayDeque<>();
			int rowWidth = 0;
			for(int i = 0; next < ranked.size(); i++, next++)
			{
				Window window = ranked.get(next);
				int width = tr.width(window.getTitle()) + 2 * TAB_PADDING;
				if(i > 0 && rowWidth + width > scaledWidth)
					break;
				
				if(i % 2 == 1)
					inRow.addFirst(window);
				else
					inRow.addLast(window);
				rowWidth += width;
			}
			
			int x = Math.max(0, (scaledWidth - rowWidth) / 2);
			for(Window window : inRow)
			{
				int width = tr.width(window.getTitle()) + 2 * TAB_PADDING;
				tabs.add(new Tab(window, x, row * TAB_HEIGHT, width));
				x += width;
			}
			
			row++;
		}
		
		barHeight = Math.max(1, row) * TAB_HEIGHT;
		
		// open, a window's content starts right below the bar (its own,
		// undrawn, title row is the bar's last row)
		for(Tab tab : tabs)
			tab.window().setY(barHeight - 13);
	}
	
	/**
	 * Puts an open window below its tab, or ending at its tab's right edge
	 * when it would run off the screen there.
	 */
	private void placeBelowTab(Tab tab, int scaledWidth)
	{
		Window window = tab.window();
		int x = tab.x();
		if(x + window.getWidth() > scaledWidth)
			x = tab.x() + tab.width() - window.getWidth();
		window.setX(Math.max(0, x));
	}
	
	/** Height of the bar along the top, as of the last frame. */
	public int getBarHeight()
	{
		return barHeight;
	}
	
	/**
	 * The window's tab in the bar as {x, y, width, height}, as of the last
	 * frame, or null if it has none.
	 */
	public int[] getTabBounds(Window window)
	{
		Tab tab = getTab(window);
		return tab == null ? null
			: new int[]{tab.x(), tab.y(), tab.width(), TAB_HEIGHT};
	}
	
	private Tab getTab(Window window)
	{
		for(Tab tab : tabs)
			if(tab.window() == window)
				return tab;
		return null;
	}
	
	/** Opens or closes a tab's window and brings it to the front. */
	private void toggleTab(Tab tab)
	{
		Window window = tab.window();
		window.setMinimized(!window.isMinimized());
		windows.remove(window);
		windows.add(window);
		closeInvalidPopups();
		saveWindows();
	}
	
	private void renderBar(GuiGraphicsExtractor context, int mouseX, int mouseY)
	{
		int scaledWidth = MC.getWindow().getGuiScaledWidth();
		context.guiRenderState.up();
		context.fill(0, 0, scaledWidth, barHeight, BeastColors.BAR_BG);
		
		Font tr = BeastFont.get();
		for(Tab tab : tabs)
		{
			int x1 = tab.x();
			int y1 = tab.y();
			int x2 = x1 + tab.width();
			int y2 = y1 + TAB_HEIGHT;
			boolean open = !tab.window().isMinimized();
			boolean hovering = tab.contains(mouseX, mouseY);
			
			if(hovering)
				context.fill(x1, y1, x2, y2, BeastColors.PANEL_HOVER);
			else if(open)
				context.fill(x1, y1, x2, y2, BeastColors.PANEL_BG_LIGHT);
			
			if(hovering)
				tooltip = "";
			
			// a little red line under the title of an open one
			if(open)
				RenderUtils.fillGradient2D(context, x1 + 3, y2 - 2, x2 - 3,
					y2 - 1, 1F);
			
			drawAccentText(context, tr, tab.window().getTitle(),
				x1 + TAB_PADDING, y1 + 3);
		}
	}
	
	/**
	 * Lists the Important hacks (golden shield, middle-click to mark) in
	 * their own window, alphabetically, or a hint while there are none.
	 */
	private void fillImportantWindow()
	{
		while(importantWindow.countChildren() > 0)
			importantWindow.remove(0);
		
		for(Hack hack : WURST.getHax().getAllHax())
			if(hack.isImportant())
				importantWindow.add(new FeatureButton(hack));
			
		if(importantWindow.countChildren() == 0)
			importantWindow.add(new HintComponent("Middle-click a hack"));
	}
	
	/** Called when a hack is marked or unmarked as Important. */
	public void updateImportantWindow(Hack hack)
	{
		if(importantWindow == null)
			return;
		
		fillImportantWindow();
		
		// the shield makes the hack's row wider wherever it's listed
		for(Window window : windows)
			for(int i = 0; i < window.countChildren(); i++)
				if(window == importantWindow
					|| window.getChild(i) instanceof FeatureButton button
						&& button.getFeature() == hack)
				{
					window.pack();
					break;
				}
			
		closeInvalidPopups();
	}
	
	private void saveWindows()
	{
		JsonObject json = new JsonObject();
		JsonObject layout = new JsonObject();
		layout.addProperty("version", LAYOUT_VERSION);
		json.add(LAYOUT, layout);
		
		for(Window window : windows)
		{
			if(window.isClosable())
				continue;
			
			JsonObject jsonWindow = new JsonObject();
			jsonWindow.addProperty("x", window.getActualX());
			jsonWindow.addProperty("y", window.getActualY());
			jsonWindow.addProperty("minimized", window.isMinimized());
			jsonWindow.addProperty("pinned", window.isPinned());
			json.add(window.getTitle(), jsonWindow);
		}
		
		try(BufferedWriter writer = Files.newBufferedWriter(windowsFile))
		{
			JsonUtils.PRETTY_GSON.toJson(json, writer);
			
		}catch(IOException e)
		{
			System.out.println("Failed to save " + windowsFile.getFileName());
			e.printStackTrace();
		}
	}
	
	public void handleMouseClick(MouseButtonEvent context)
	{
		int mouseX = (int)context.x();
		int mouseY = (int)context.y();
		int mouseButton = context.button();
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = true;
		
		boolean popupClicked =
			handlePopupMouseClick(mouseX, mouseY, mouseButton);
		
		// the bar is in front of everything but popups
		if(!popupClicked && mouseY >= 0 && mouseY < barHeight)
		{
			if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				for(Tab tab : tabs)
					if(tab.contains(mouseX, mouseY))
					{
						toggleTab(tab);
						break;
					}
				
			return;
		}
		
		if(!popupClicked)
		{
			handleWindowMouseClick(mouseX, mouseY, mouseButton, context);
			closeInvalidPopups();
		}
		
		windows.removeIf(Window::isClosing);
	}
	
	public void handleMouseRelease(double mouseX, double mouseY,
		int mouseButton)
	{
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = false;
	}
	
	public void handleMouseScroll(double mouseX, double mouseY, double delta)
	{
		int dWheel = (int)delta * 4;
		if(dWheel == 0)
			return;
		
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			
			if(!window.isScrollingEnabled() || window.isMinimized()
				|| window.isInvisible())
				continue;
			
			if(mouseX < window.getX() || mouseY < window.getY() + 13)
				continue;
			if(mouseX >= window.getX() + window.getWidth()
				|| mouseY >= window.getY() + window.getHeight())
				continue;
			
			int scroll = window.getScrollOffset() + dWheel;
			scroll = Math.min(scroll, 0);
			scroll = Math.max(scroll,
				-window.getInnerHeight() + window.getHeight() - 13);
			window.setScrollOffset(scroll);
			closeInvalidPopups();
			break;
		}
	}
	
	public void handleNavigatorMouseClick(double cMouseX, double cMouseY,
		int mouseButton, Window window, MouseButtonEvent context)
	{
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
			leftMouseButtonPressed = true;
		
		handleComponentMouseClick(window, cMouseX, cMouseY, mouseButton,
			context);
		
		closeInvalidPopups();
	}
	
	public void closePopupsOutsideArea(Window window, int x1, int y1, int x2,
		int y2)
	{
		for(Popup popup : popups)
		{
			Component owner = popup.getOwner();
			if(owner.getParent() == window
				&& !isComponentVisibleWithinBounds(owner, x1, y1, x2, y2))
				popup.close();
		}
		
		popups.removeIf(Popup::isClosing);
	}
	
	public boolean handlePopupMouseClick(double mouseX, double mouseY,
		int mouseButton)
	{
		closeInvalidPopups();
		
		for(int i = popups.size() - 1; i >= 0; i--)
		{
			Popup popup = popups.get(i);
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			
			int x0 = parent.getX() + owner.getX();
			int y0 =
				parent.getY() + 13 + parent.getScrollOffset() + owner.getY();
			
			int x1 = x0 + popup.getX();
			int y1 = y0 + popup.getY();
			int x2 = x1 + popup.getWidth();
			int y2 = y1 + popup.getHeight();
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			int cMouseX = (int)(mouseX - x0);
			int cMouseY = (int)(mouseY - y0);
			popup.handleMouseClick(cMouseX, cMouseY, mouseButton);
			
			popups.remove(i);
			popups.add(popup);
			closeInvalidPopups();
			return true;
		}
		
		return false;
	}
	
	private void closeInvalidPopups()
	{
		for(Popup popup : popups)
		{
			Window parent = popup.getOwner().getParent();
			if(parent == null || parent.isClosing()
				|| !isPopupOwnerVisible(popup))
				popup.close();
		}
		
		popups.removeIf(Popup::isClosing);
	}
	
	private boolean isPopupOwnerVisible(Popup popup)
	{
		Component owner = popup.getOwner();
		Window parent = owner.getParent();
		if(parent == null || parent.isInvisible() || parent.isMinimized())
			return false;
		
		int x1 = parent.getX();
		int y1 = parent.getY() + 13;
		int x2 = x1 + parent.getWidth();
		int y2 = parent.getY() + parent.getHeight();
		return isComponentVisibleWithinBounds(owner, x1, y1, x2, y2);
	}
	
	private boolean isComponentVisibleWithinBounds(Component c, int x1, int y1,
		int x2, int y2)
	{
		Window parent = c.getParent();
		int cx1 = parent.getX() + c.getX();
		int cy1 = parent.getY() + 13 + parent.getScrollOffset() + c.getY();
		int cx2 = cx1 + c.getWidth();
		int cy2 = cy1 + c.getHeight();
		return cx2 > x1 && cx1 < x2 && cy2 > y1 && cy1 < y2;
	}
	
	private void handleWindowMouseClick(int mouseX, int mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			if(window.isInvisible())
				continue;
			
			int x1 = window.getX();
			int y1 = window.getY();
			int x2 = x1 + window.getWidth();
			int y2 = y1 + window.getHeight();
			int y3 = y1 + 13;
			
			if(mouseX < x1 || mouseY < y1)
				continue;
			if(mouseX >= x2 || mouseY >= y2)
				continue;
			
			if(mouseY < y3)
				handleTitleBarMouseClick(window, mouseX, mouseY, mouseButton);
			else if(!window.isMinimized())
			{
				window.validate();
				
				int cMouseX = mouseX - x1;
				int cMouseY = mouseY - y3;
				
				if(window.isScrollingEnabled() && mouseX >= x2 - 3)
					handleScrollbarMouseClick(window, cMouseX, cMouseY,
						mouseButton);
				else
				{
					if(window.isScrollingEnabled())
						cMouseY -= window.getScrollOffset();
					
					handleComponentMouseClick(window, cMouseX, cMouseY,
						mouseButton, context);
				}
				
			}else
				continue;
			
			windows.remove(i);
			windows.add(window);
			break;
		}
	}
	
	private void handleTitleBarMouseClick(Window window, int mouseX, int mouseY,
		int mouseButton)
	{
		if(mouseButton != 0)
			return;
		
		if(mouseY < window.getY() + 2 || mouseY >= window.getY() + 11)
		{
			window.startDragging(mouseX, mouseY);
			return;
		}
		
		int x3 = window.getX() + window.getWidth();
		
		if(window.isClosable())
		{
			x3 -= 11;
			if(mouseX >= x3 && mouseX < x3 + 9)
			{
				window.close();
				return;
			}
		}
		
		if(window.isPinnable())
		{
			x3 -= 11;
			if(mouseX >= x3 && mouseX < x3 + 9)
			{
				window.setPinned(!window.isPinned());
				saveWindows();
				return;
			}
		}
		
		if(window.isMinimizable())
		{
			x3 -= 11;
			if(mouseX >= x3 && mouseX < x3 + 9)
			{
				window.setMinimized(!window.isMinimized());
				saveWindows();
				return;
			}
		}
		
		window.startDragging(mouseX, mouseY);
	}
	
	private void handleScrollbarMouseClick(Window window, int mouseX,
		int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(mouseX >= window.getWidth() - 1)
			return;
		
		double outerHeight = window.getHeight() - 13;
		double innerHeight = window.getInnerHeight();
		double maxScrollbarHeight = outerHeight - 2;
		int scrollbarY =
			(int)(outerHeight * (-window.getScrollOffset() / innerHeight) + 1);
		int scrollbarHeight =
			(int)(maxScrollbarHeight * outerHeight / innerHeight);
		
		if(mouseY < scrollbarY || mouseY >= scrollbarY + scrollbarHeight)
			return;
		
		window.startDraggingScrollbar(window.getY() + 13 + mouseY);
	}
	
	private void handleComponentMouseClick(Window window, double mouseX,
		double mouseY, int mouseButton, MouseButtonEvent context)
	{
		for(int i2 = window.countChildren() - 1; i2 >= 0; i2--)
		{
			Component c = window.getChild(i2);
			
			if(mouseX < c.getX() || mouseY < c.getY())
				continue;
			if(mouseX >= c.getX() + c.getWidth()
				|| mouseY >= c.getY() + c.getHeight())
				continue;
			
			c.handleMouseClick(mouseX, mouseY, mouseButton, context);
			break;
		}
	}
	
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		updateColors();
		
		layOutTabs(MC.getWindow().getGuiScaledWidth());
		
		Matrix3x2fStack matrixStack = context.pose();
		matrixStack.pushMatrix();
		
		tooltip = "";
		for(Window window : windows)
		{
			if(window.isInvisible())
				continue;
			
			// a closed tab window is just its tab in the bar
			if(window.hasColumns() && window.isMinimized())
				continue;
			
			// dragging
			if(window.isDragging())
				if(leftMouseButtonPressed)
					window.dragTo(mouseX, mouseY);
				else
				{
					window.stopDragging();
					saveWindows();
				}
			
			// scrollbar dragging
			if(window.isDraggingScrollbar())
				if(leftMouseButtonPressed)
					window.dragScrollbarTo(mouseY);
				else
					window.stopDraggingScrollbar();
				
			context.guiRenderState.up();
			renderWindow(context, window, mouseX, mouseY, partialTicks);
		}
		
		renderBar(context, mouseX, mouseY);
		renderPopups(context, mouseX, mouseY);
		renderTooltip(context, mouseX, mouseY);
		
		matrixStack.popMatrix();
	}
	
	public void renderPopups(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		closeInvalidPopups();
		
		Matrix3x2fStack matrixStack = context.pose();
		for(Popup popup : popups)
		{
			Component owner = popup.getOwner();
			Window parent = owner.getParent();
			
			int x1 = parent.getX() + owner.getX();
			int y1 =
				parent.getY() + 13 + parent.getScrollOffset() + owner.getY();
			
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y1);
			context.guiRenderState.up();
			
			int cMouseX = mouseX - x1;
			int cMouseY = mouseY - y1;
			popup.render(context, cMouseX, cMouseY);
			
			matrixStack.popMatrix();
		}
	}
	
	public void renderTooltip(GuiGraphicsExtractor context, int mouseX,
		int mouseY)
	{
		if(tooltip.isEmpty())
			return;
		
		String[] lines = tooltip.split("\n");
		Font tr = BeastFont.get();
		
		int tw = 0;
		int th = lines.length * tr.lineHeight;
		for(String line : lines)
		{
			int lw = tr.width(line);
			if(lw > tw)
				tw = lw;
		}
		int sw = MC.screen.width;
		int sh = MC.screen.height;
		
		int xt1 = mouseX + tw + 11 <= sw ? mouseX + 8 : mouseX - tw - 8;
		int xt2 = xt1 + tw + 3;
		int yt1 = mouseY + th - 2 <= sh ? mouseY - 4 : mouseY - th - 4;
		int yt2 = yt1 + th + 2;
		
		context.guiRenderState.up();
		
		// background
		context.fill(xt1, yt1, xt2, yt2,
			RenderUtils.toIntColor(bgColor, ttOpacity));
		
		// outline
		drawOutline(context, xt1, yt1, xt2, yt2);
		
		// text
		context.guiRenderState.up();
		for(int i = 0; i < lines.length; i++)
			drawAccentText(context, tr, lines[i], xt1 + 2,
				yt1 + 2 + i * tr.lineHeight);
	}
	
	public void renderPinnedWindows(GuiGraphicsExtractor context,
		float partialTicks)
	{
		for(Window window : windows)
		{
			if(!window.isPinned() || window.isInvisible())
				continue;
			
			context.guiRenderState.up();
			renderWindow(context, window, Integer.MIN_VALUE, Integer.MIN_VALUE,
				partialTicks);
		}
	}
	
	public void updateColors()
	{
		ClickGuiHack clickGui = WURST.getHax().clickGuiHack;
		
		opacity = clickGui.getOpacity();
		ttOpacity = clickGui.getTooltipOpacity();
		bgColor = clickGui.getBackgroundColor();
		txtColor = clickGui.getTextColor();
		maxSettingsHeight = clickGui.getMaxSettingsHeight();
		
		if(WurstClient.INSTANCE.getHax().rainbowUiHack.isEnabled())
			acColor = RenderUtils.getRainbowColor();
		else
			acColor = clickGui.getAccentColor();
	}
	
	private void renderWindow(GuiGraphicsExtractor context, Window window,
		int mouseX, int mouseY, float partialTicks)
	{
		// a tab window's title is its tab in the bar: only the dropdown
		// below it is drawn here
		boolean inBar = window.hasColumns();
		if(!window.isMinimized())
		{
			// dropdowns end above the bottom of the screen and wrap into
			// columns instead of scrolling
			if(inBar)
				window.setMaxHeight(Math.max(60,
					MC.getWindow().getGuiScaledHeight() - window.getY() - 4));
			else if(window instanceof SettingsWindow)
				window.setMaxHeight(maxSettingsHeight);
			window.validate();
			
			Tab tab = inBar ? getTab(window) : null;
			if(tab != null)
				placeBelowTab(tab, MC.getWindow().getGuiScaledWidth());
		}
		
		int x1 = window.getX();
		int y1 = window.getY();
		int x2 = x1 + window.getWidth();
		int y2 = y1 + window.getHeight();
		int y3 = y1 + 13;
		
		int windowBgColor = RenderUtils.toIntColor(bgColor, opacity);
		
		Matrix3x2fStack matrixStack = context.pose();
		
		if(window.isMinimized())
			y2 = y3;
		
		// drop shadow, lifting the window off the world behind it
		int yShadow = inBar ? y3 : y1;
		context.fill(x1 + 2, y2, x2 + 2, y2 + 2, BeastColors.SHADOW);
		context.fill(x2, yShadow + 2, x2 + 2, y2, BeastColors.SHADOW);
		
		if(mouseX >= x1 && mouseY >= y1 && mouseX < x2 && mouseY < y2)
			tooltip = "";
		
		if(!window.isMinimized())
		{
			// solid window background, everything else is drawn on top
			context.fill(x1, y3, x2, y2, windowBgColor);
			
			// scrollbar: a dark track with the accent gradient as the thumb,
			// so it reads as part of the same moving wave as the borders
			if(window.isScrollingEnabled())
			{
				int xs1 = x2 - 3;
				int xs2 = xs1 + 2;
				
				double outerHeight = y2 - y3;
				double innerHeight = window.getInnerHeight();
				double maxScrollbarHeight = outerHeight - 2;
				double scrollbarY =
					outerHeight * (-window.getScrollOffset() / innerHeight) + 1;
				double scrollbarHeight =
					maxScrollbarHeight * outerHeight / innerHeight;
				
				int ys3 = y3 + (int)scrollbarY;
				int ys4 = ys3 + (int)scrollbarHeight;
				
				context.fill(xs1, y3 + 1, xs2, y2 - 1, BeastColors.ROW_BORDER);
				
				boolean hovering = mouseX >= xs1 && mouseY >= ys3
					&& mouseX < xs2 && mouseY < ys4;
				RenderUtils.fillGradient2D(context, xs1, ys3, xs2, ys4,
					hovering ? 1F : 0.75F);
			}
			
			int y4 = y3 + window.getScrollOffset();
			
			context.enableScissor(x1, y3, x2, y2);
			context.guiRenderState.up();
			
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y4);
			
			// render children
			int cMouseX = mouseX - x1;
			int cMouseY = mouseY - y4;
			for(int i = 0; i < window.countChildren(); i++)
				window.getChild(i).render(context, cMouseX, cMouseY,
					partialTicks);
			
			matrixStack.popMatrix();
			context.disableScissor();
		}
		
		if(inBar)
			return;
		
		// title bar buttons
		int x3 = x2;
		int y4 = y1 + 2;
		int y5 = y3 - 2;
		boolean hoveringY = mouseY >= y4 && mouseY < y5;
		if(window.isClosable())
		{
			x3 -= 11;
			int x4 = x3 + 9;
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			renderTitleBarButton(context, x3, y4, x4, y5, hovering);
			ClickGuiIcons.drawCross(context, x3, y4, x4, y5, hovering);
		}
		
		if(window.isMinimizable())
		{
			x3 -= 11;
			int x4 = x3 + 9;
			boolean hovering = hoveringY && mouseX >= x3 && mouseX < x4;
			renderTitleBarButton(context, x3, y4, x4, y5, hovering);
			ClickGuiIcons.drawMinimizeArrow(context, x3, y4, x4, y5, hovering,
				window.isMinimized());
		}
		
		// title bar background
		// above & below buttons
		int titleBgColor = BeastColors.BAR_BG;
		context.fill(x3, y1, x2, y4, titleBgColor);
		context.fill(x3, y5, x2, y3, titleBgColor);
		
		// title bar background
		// behind title
		context.fill(x1, y1, x3, y3, titleBgColor);
		
		// window title
		Font tr = BeastFont.get();
		String title = tr.substrByWidth(
			net.minecraft.network.chat.Component.literal(window.getTitle()),
			x3 - x1 - 4).getString();
		context.guiRenderState.up();
		drawAccentText(context, tr, title, x1 + 3, y1 + 3);
	}
	
	private void renderTitleBarButton(GuiGraphicsExtractor context, int x1,
		int y1, int x2, int y2, boolean hovering)
	{
		int x3 = x2 + 2;
		
		// button background
		int buttonBgColor = hovering ? BeastColors.PANEL_HOVER
			: RenderUtils.toIntColor(bgColor, opacity);
		context.fill(x1, y1, x2, y2, buttonBgColor);
		
		// background between buttons
		context.fill(x2, y1, x3, y2, BeastColors.BAR_BG);
	}
	
	public float[] getBgColor()
	{
		return bgColor;
	}
	
	public float[] getAcColor()
	{
		return acColor;
	}
	
	public int getTxtColor()
	{
		return txtColor;
	}
	
	/**
	 * Fill of a control (row, box, button): solid, a step lighter than the
	 * window behind it, and lighter again under the mouse.
	 */
	public int getFillColor(boolean hovering)
	{
		return hovering ? BeastColors.PANEL_HOVER : BeastColors.PANEL_BG_LIGHT;
	}
	
	/**
	 * Draws a one-pixel neutral gray hairline around a control or tooltip.
	 * Nothing in the ClickGUI has a red outline (user, 2026-10-14).
	 */
	public static void drawOutline(GuiGraphicsExtractor context, float x1,
		float y1, float x2, float y2)
	{
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
			BeastColors.ROW_BORDER);
	}
	
	/**
	 * Draws a label in the accent gradient. Every accent label in the ClickGUI
	 * goes through here.
	 */
	public static int drawAccentText(GuiGraphicsExtractor context, Font font,
		String text, int x, int y)
	{
		return RenderUtils.drawGradientText(context, font, text, x, y, 1F,
			false);
	}
	
	public float getOpacity()
	{
		return opacity;
	}
	
	public float getTooltipOpacity()
	{
		return ttOpacity;
	}
	
	public void setTooltip(String tooltip)
	{
		this.tooltip = Objects.requireNonNull(tooltip);
	}
	
	public void addWindow(Window window)
	{
		windows.add(window);
	}
	
	public List<Window> getWindows()
	{
		return Collections.unmodifiableList(windows);
	}
	
	public void addPopup(Popup popup)
	{
		popups.add(popup);
	}
	
	public boolean isLeftMouseButtonPressed()
	{
		return leftMouseButtonPressed;
	}
}
