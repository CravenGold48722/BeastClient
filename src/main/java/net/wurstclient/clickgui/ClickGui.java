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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import com.google.gson.JsonArray;
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
import net.wurstclient.util.Anim;
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
	 * The default order of the bar's slots, from the middle out: Important in
	 * the middle, the less used ones at the ends.
	 */
	private static final List<String> BY_IMPORTANCE =
		List.of(IMPORTANT, "Combat", "Render", "Movement", "Blocks", "Items",
			"Other", "Chat", "Fun", "UI Settings");
	
	/** Version of the bar layout in windows.json. */
	private static final String LAYOUT = "_layout";
	private static final int LAYOUT_VERSION = 4;
	
	/** The bar along the top: one row of equal slots, one tab in each. */
	private static final int TAB_HEIGHT = 13;
	private final ArrayList<Window> slots = new ArrayList<>();
	private final HashMap<Window, Tab> tabs = new HashMap<>();
	private float slotWidth;
	
	private Tab pressedTab;
	private int pressX;
	private float dragOffsetX;
	private boolean draggingTab;
	private int dragStartX;
	private int dragStartY;
	
	/**
	 * A bar window's tab: slides into its slot, underlined while its window
	 * is the one in front.
	 */
	private static final class Tab
	{
		private final Window window;
		private final Anim x = new Anim(14);
		private final Anim underline = new Anim(14);
		
		private Tab(Window window)
		{
			this.window = window;
		}
		
		private boolean contains(float slotWidth, int mouseX, int mouseY)
		{
			float x1 = x.get();
			return mouseX >= x1 && mouseX < x1 + slotWidth && mouseY >= 0
				&& mouseY < TAB_HEIGHT;
		}
	}
	
	private float[] bgColor = new float[3];
	private float[] acColor = new float[3];
	private int txtColor;
	private float opacity;
	private float ttOpacity;
	private int maxHeight;
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
		
		// tabs in the bar instead of minimize buttons
		for(Window window : windows)
		{
			window.setMinimized(true);
			window.setMinimizable(false);
			window.setInBar(true);
		}
		setDefaultSlotOrder(windows);
		
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
		
		// windows.json from before this bar: all closed, below their tabs,
		// in the default order
		JsonObject layout = json.get(LAYOUT) instanceof JsonObject o ? o : null;
		int version =
			layout != null && layout.get("version") instanceof JsonElement v
				&& v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()
					? v.getAsInt() : 0;
		boolean oldLayout = version < LAYOUT_VERSION;
		// the order is kept from version 3 (the slots); version 4 (normal-size
		// windows, hidden until clicked) starts with every section hidden
		if(version >= 3)
			loadSlotOrder(layout);
		
		for(Window window : windows)
		{
			JsonElement jsonWindow = json.get(window.getTitle());
			if(jsonWindow == null || !jsonWindow.isJsonObject()
				|| oldLayout && window.isInBar())
				continue;
			
			JsonElement jsonX = jsonWindow.getAsJsonObject().get("x");
			if(jsonX.isJsonPrimitive() && jsonX.getAsJsonPrimitive().isNumber())
				window.setX(jsonX.getAsInt());
			
			JsonElement jsonY = jsonWindow.getAsJsonObject().get("y");
			if(jsonY.isJsonPrimitive() && jsonY.getAsJsonPrimitive().isNumber())
				window.setY(jsonY.getAsInt());
			
			JsonElement jsonMinimized =
				jsonWindow.getAsJsonObject().get("minimized");
			if(jsonMinimized.isJsonPrimitive()
				&& jsonMinimized.getAsJsonPrimitive().isBoolean())
				window.setMinimized(jsonMinimized.getAsBoolean());
			
			JsonElement jsonDocked = jsonWindow.getAsJsonObject().get("docked");
			if(window.isInBar() && jsonDocked != null
				&& jsonDocked.isJsonPrimitive()
				&& jsonDocked.getAsJsonPrimitive().isBoolean())
				window.setDocked(jsonDocked.getAsBoolean());
			
			// only Radar's window is pinned, and it always is
			JsonElement jsonPinned = jsonWindow.getAsJsonObject().get("pinned");
			if(window.isPinnable() && jsonPinned.isJsonPrimitive()
				&& jsonPinned.getAsJsonPrimitive().isBoolean())
				window.setPinned(jsonPinned.getAsBoolean());
		}
		
		saveWindows();
	}
	
	/**
	 * The bar windows into the slots from the middle out, most important
	 * first.
	 */
	private void setDefaultSlotOrder(List<Window> barWindows)
	{
		ArrayList<Window> ranked = new ArrayList<>();
		for(String title : BY_IMPORTANCE)
			for(Window window : barWindows)
				if(window.getTitle().equals(title))
					ranked.add(window);
		for(Window window : barWindows)
			if(!ranked.contains(window))
				ranked.add(window);
			
		ArrayDeque<Window> order = new ArrayDeque<>();
		for(int i = 0; i < ranked.size(); i++)
			if(i % 2 == 1)
				order.addFirst(ranked.get(i));
			else
				order.addLast(ranked.get(i));
			
		slots.clear();
		slots.addAll(order);
		tabs.clear();
		for(Window window : slots)
			tabs.put(window, new Tab(window));
	}
	
	private void loadSlotOrder(JsonObject layout)
	{
		if(!(layout.get("order") instanceof JsonArray order))
			return;
		
		ArrayList<Window> loaded = new ArrayList<>();
		for(JsonElement element : order)
		{
			if(!element.isJsonPrimitive())
				continue;
			
			for(Window window : slots)
				if(window.getTitle().equals(element.getAsString())
					&& !loaded.contains(window))
					loaded.add(window);
		}
		
		for(Window window : slots)
			if(!loaded.contains(window))
				loaded.add(window);
			
		slots.clear();
		slots.addAll(loaded);
	}
	
	/**
	 * Slides every tab towards its slot; the dragged one follows the mouse.
	 */
	private void layOutTabs(int scaledWidth, int mouseX)
	{
		slotWidth = scaledWidth / (float)Math.max(1, slots.size());
		
		if(pressedTab != null && !leftMouseButtonPressed)
			pressedTab = null;
		if(pressedTab != null && !draggingTab && Math.abs(mouseX - pressX) > 3)
			draggingTab = true;
		if(pressedTab == null)
			draggingTab = false;
		
		for(int i = 0; i < slots.size(); i++)
		{
			Tab tab = tabs.get(slots.get(i));
			if(draggingTab && tab == pressedTab)
				tab.x.snap(Math.max(0,
					Math.min(scaledWidth - slotWidth, mouseX - dragOffsetX)));
			else
				tab.x.update(i * slotWidth);
		}
		
		sizeBarWindows();
	}
	
	/**
	 * Bar windows are normal windows at their normal size (user, like the
	 * inspiration's top bar: hidden until their title is clicked, free to
	 * overlap). {@link Window#getScale} stays 1; the scaled drawing and the
	 * mouse conversion are still there from the fit-all-ten layout.
	 */
	private void sizeBarWindows()
	{
		for(Window window : slots)
		{
			window.setScale(1);
			window.setWidth(window.getPackedWidth());
		}
	}
	
	/** Puts the dragged tab into the slot it's over, swapping. */
	private void dropTab()
	{
		Window window = pressedTab.window;
		int from = slots.indexOf(window);
		float center = pressedTab.x.get() + slotWidth / 2;
		int to =
			Math.max(0, Math.min(slots.size() - 1, (int)(center / slotWidth)));
		if(from >= 0 && to != from)
		{
			Collections.swap(slots, from, to);
			saveWindows();
		}
	}
	
	/**
	 * A docked window opens right below its tab, ending at the right edge
	 * of the screen at the latest.
	 */
	private void placeBelowTab(Window window)
	{
		Tab tab = tabs.get(window);
		if(tab == null || !window.isDocked() || window.isDragging())
			return;
		
		// below its tab, touching the bar, on the screen
		int scaledWidth = MC.getWindow().getGuiScaledWidth();
		window.setX(Math.max(0, Math.min(Math.round(tab.x.get()),
			scaledWidth - window.getDisplayWidth())));
		window.setY(TAB_HEIGHT);
	}
	
	/** Height of the bar along the top. */
	public int getBarHeight()
	{
		return TAB_HEIGHT;
	}
	
	/**
	 * The window's tab in the bar as {x, y, width, height}, as of the last
	 * frame, or null if it has none.
	 */
	public int[] getTabBounds(Window window)
	{
		Tab tab = tabs.get(window);
		if(tab == null)
			return null;
		
		int x1 = Math.round(tab.x.get());
		int x2 = Math.round(tab.x.get() + slotWidth);
		return new int[]{x1, 0, x2 - x1, TAB_HEIGHT};
	}
	
	/** The bar windows by slot, left to right. */
	public List<Window> getSlotOrder()
	{
		return Collections.unmodifiableList(slots);
	}
	
	/** Opens or closes a tab's window and brings it to the front. */
	private void toggleTab(Tab tab)
	{
		Window window = tab.window;
		window.setMinimized(!window.isMinimized());
		bringToFront(window);
		closeInvalidPopups();
		saveWindows();
	}
	
	private void bringToFront(Window window)
	{
		windows.remove(window);
		windows.add(window);
	}
	
	/** The open bar window in front of the others, if any. */
	private Window getFrontBarWindow()
	{
		for(int i = windows.size() - 1; i >= 0; i--)
		{
			Window window = windows.get(i);
			if(window.isInBar() && !window.isMinimized())
				return window;
		}
		
		return null;
	}
	
	private void renderBar(GuiGraphicsExtractor context, int mouseX, int mouseY)
	{
		int scaledWidth = MC.getWindow().getGuiScaledWidth();
		context.guiRenderState.up();
		context.fill(0, 0, scaledWidth, TAB_HEIGHT, BeastColors.BAR_BG);
		
		Window front = getFrontBarWindow();
		for(Window window : slots)
		{
			Tab tab = tabs.get(window);
			if(!(draggingTab && tab == pressedTab))
				renderTab(context, tab, front, mouseX, mouseY);
		}
		
		// the dragged one on top
		if(draggingTab && pressedTab != null)
		{
			context.guiRenderState.up();
			renderTab(context, pressedTab, front, mouseX, mouseY);
		}
	}
	
	private void renderTab(GuiGraphicsExtractor context, Tab tab, Window front,
		int mouseX, int mouseY)
	{
		float x1 = tab.x.get();
		float x2 = x1 + slotWidth;
		boolean dragged = draggingTab && tab == pressedTab;
		boolean hovering = dragged
			|| pressedTab == null && tab.contains(slotWidth, mouseX, mouseY);
		
		if(hovering)
		{
			RenderUtils.fill2D(context, x1, 0, x2, TAB_HEIGHT,
				BeastColors.PANEL_HOVER);
			tooltip = "";
		}else if(tab.window.getOpenProgress() > 0)
			RenderUtils.fill2D(context, x1, 0, x2, TAB_HEIGHT,
				BeastColors.withOpacity(BeastColors.PANEL_BG_LIGHT,
					tab.window.getOpenProgress()));
		
		// the title, centred, squeezed if the slot is too narrow for it
		Font tr = BeastFont.get();
		String title = tab.window.getTitle();
		int textWidth = tr.width(title);
		float scale = Math.min(1, (slotWidth - 4) / textWidth);
		float textX = x1 + (slotWidth - textWidth * scale) / 2;
		pushFittedText(context, tr, title, textX, 2.5F, slotWidth - 4);
		drawAccentText(context, tr, title, 0, 0);
		context.pose().popMatrix();
		
		// a little red line under the title of the one in front, growing
		// from the middle
		float underline = Anim.ease(
			tab.underline.update(tab.window == front && !dragged ? 1 : 0));
		if(underline > 0.01F)
		{
			float half = (textWidth * scale + 4) / 2 * underline;
			float middle = textX + textWidth * scale / 2;
			RenderUtils.fillGradient2D(context, middle - half, TAB_HEIGHT - 2,
				middle + half, TAB_HEIGHT - 1, 1F);
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
		JsonArray order = new JsonArray();
		for(Window window : slots)
			order.add(window.getTitle());
		layout.add("order", order);
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
			if(window.isInBar())
				jsonWindow.addProperty("docked", window.isDocked());
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
		
		// the bar is in front of everything but popups: a tab opens its window
		// when let go, or is dragged into another slot
		if(!popupClicked && mouseY >= 0 && mouseY < TAB_HEIGHT)
		{
			if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
				for(Tab tab : tabs.values())
					if(tab.contains(slotWidth, mouseX, mouseY))
					{
						pressedTab = tab;
						pressX = mouseX;
						dragOffsetX = mouseX - tab.x.get();
						draggingTab = false;
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
		if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT && pressedTab != null)
		{
			// let go before a frame noticed the drag
			if(!draggingTab && Math.abs(mouseX - pressX) > 3)
			{
				draggingTab = true;
				pressedTab.x.snap(Math.max(0,
					Math.min(MC.getWindow().getGuiScaledWidth() - slotWidth,
						(float)mouseX - dragOffsetX)));
			}
			
			if(draggingTab)
				dropTab();
			else
				toggleTab(pressedTab);
			pressedTab = null;
			draggingTab = false;
		}
		
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
			
			int wx = toWindowX(window, mouseX);
			int wy = toWindowY(window, mouseY);
			if(wx < window.getX() || wy < window.getY() + 13)
				continue;
			if(wx >= window.getX() + window.getWidth()
				|| wy >= window.getY() + window.getHeight())
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
			
			// closed (or closing) bar windows are just their tab
			if(window.isInBar() && window.isMinimized())
				continue;
			
			// in the window's own coordinates (a shrunk window is scaled)
			int wx = toWindowX(window, mouseX);
			int wy = toWindowY(window, mouseY);
			
			int x1 = window.getX();
			int y1 = window.getY();
			int x2 = x1 + window.getWidth();
			int y2 = y1 + window.getHeight();
			int y3 = y1 + 13;
			
			if(wx < x1 || wy < y1)
				continue;
			if(wx >= x2 || wy >= y2)
				continue;
			
			if(wy < y3)
			{
				// a shrunk window has no title bar buttons, and dragging
				// works in screen coordinates
				if(window.getScale() != 1)
				{
					if(mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)
						startDragging(window, mouseX, mouseY);
				}else
					handleTitleBarMouseClick(window, mouseX, mouseY,
						mouseButton);
			}else if(!window.isMinimized())
			{
				window.validate();
				
				int cMouseX = wx - x1;
				int cMouseY = wy - y3;
				
				if(window.isScrollingEnabled() && wx >= x2 - 3)
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
			startDragging(window, mouseX, mouseY);
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
		
		startDragging(window, mouseX, mouseY);
	}
	
	private void startDragging(Window window, int mouseX, int mouseY)
	{
		dragStartX = window.getActualX();
		dragStartY = window.getActualY();
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
		
		layOutTabs(MC.getWindow().getGuiScaledWidth(), mouseX);
		
		Matrix3x2fStack matrixStack = context.pose();
		matrixStack.pushMatrix();
		
		tooltip = "";
		for(Window window : windows)
		{
			if(window.isInvisible())
				continue;
			
			// a closed bar window is just its tab (once it has slid shut)
			if(window.isInBar() && window.updateOpenProgress() <= 0)
				continue;
			
			// dragging
			if(window.isDragging())
				if(leftMouseButtonPressed)
				{
					window.dragTo(mouseX, mouseY);
					// moved by hand: it opens where it was left from now on
					if(window.getActualX() != dragStartX
						|| window.getActualY() != dragStartY)
						window.setDocked(false);
					
				}else
				{
					window.stopDragging();
					saveWindows();
				}
			
			// scrollbar dragging
			if(window.isDraggingScrollbar())
				if(leftMouseButtonPressed)
					window.dragScrollbarTo(toWindowY(window, mouseY));
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
		maxHeight = clickGui.getMaxHeight();
		maxSettingsHeight = clickGui.getMaxSettingsHeight();
		
		if(WurstClient.INSTANCE.getHax().rainbowUiHack.isEnabled())
			acColor = RenderUtils.getRainbowColor();
		else
			acColor = clickGui.getAccentColor();
	}
	
	private void renderWindow(GuiGraphicsExtractor context, Window window,
		int mouseX, int mouseY, float partialTicks)
	{
		// a bar window opens and closes by sliding down from its title bar;
		// while it's closing, the mouse doesn't reach it any more
		boolean inBar = window.isInBar();
		float open = inBar ? Anim.ease(window.getOpenProgress()) : 1;
		boolean minimized = window.isMinimized() && !inBar;
		if(inBar && window.isMinimized())
		{
			mouseX = Integer.MIN_VALUE;
			mouseY = Integer.MIN_VALUE;
		}
		
		if(!minimized)
		{
			// one column, scrolling past "Max height" or the bottom of the
			// screen, whichever comes first
			int scaledHeight = MC.getWindow().getGuiScaledHeight();
			if(inBar)
			{
				int y = window.isDocked() && !window.isDragging() ? TAB_HEIGHT
					: window.getY();
				int limit = Math.max(40,
					Math.round((scaledHeight - y - 4) / window.getScale()));
				// "Max height" is on the screen, not in the shrunk window
				window.setMaxHeight(maxHeight > 0
					? Math.min(Math.round(maxHeight / window.getScale()), limit)
					: limit);
			}else if(window instanceof SettingsWindow)
				window.setMaxHeight(maxSettingsHeight);
			else
				window.setMaxHeight(maxHeight);
			window.validate();
			
			if(inBar)
				placeBelowTab(window);
		}
		
		int x1 = window.getX();
		int y1 = window.getY();
		int x2 = x1 + window.getWidth();
		int y2 = y1 + window.getHeight();
		int y3 = y1 + 13;
		
		int windowBgColor = RenderUtils.toIntColor(bgColor, opacity);
		
		Matrix3x2fStack matrixStack = context.pose();
		
		// a shrunk window: drawn scaled around its top left corner, with the
		// mouse converted into its own, unscaled coordinates
		float scale = window.getScale();
		boolean scaled = scale != 1;
		if(scaled)
		{
			matrixStack.pushMatrix();
			matrixStack.translate(x1, y1);
			matrixStack.scale(scale);
			matrixStack.translate(-x1, -y1);
			if(mouseX != Integer.MIN_VALUE)
			{
				mouseX = toWindowX(window, mouseX);
				mouseY = toWindowY(window, mouseY);
			}
		}
		
		if(minimized)
			y2 = y3;
		
		boolean revealing = open < 1;
		if(revealing)
			context.enableScissor(x1, y1, x2 + 2,
				y1 + Math.round((y2 + 2 - y1) * open));
		
		// drop shadow, lifting the window off the world behind it
		context.fill(x1 + 2, y2, x2 + 2, y2 + 2, BeastColors.SHADOW);
		context.fill(x2, y1 + 2, x2 + 2, y2, BeastColors.SHADOW);
		
		if(mouseX >= x1 && mouseY >= y1 && mouseX < x2 && mouseY < y2)
			tooltip = "";
		
		if(!minimized)
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
		
		// window title, squeezed if it doesn't fit
		Font tr = BeastFont.get();
		context.guiRenderState.up();
		pushFittedText(context, tr, window.getTitle(), x1 + 3, y1 + 3,
			x3 - x1 - 5);
		drawAccentText(context, tr, window.getTitle(), 0, 0);
		context.pose().popMatrix();
		
		if(revealing)
			context.disableScissor();
		
		if(scaled)
			matrixStack.popMatrix();
	}
	
	/** The mouse X in a (shrunk) window's own coordinates. */
	private static int toWindowX(Window window, double mouseX)
	{
		int x1 = window.getX();
		return (int)Math.floor(x1 + (mouseX - x1) / window.getScale());
	}
	
	/** The mouse Y in a (shrunk) window's own coordinates. */
	private static int toWindowY(Window window, double mouseY)
	{
		int y1 = window.getY();
		return (int)Math.floor(y1 + (mouseY - y1) / window.getScale());
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
	 * Pushes the pose so that text drawn at (0, 0) starts at (x, y) and fits
	 * in maxWidth: full size if it fits (the same size as the bar's tabs),
	 * otherwise narrower at full height down to 60% width, and only past that
	 * smaller as well, centred on the line. Pop it with
	 * {@code context.pose().popMatrix()}.
	 */
	public static void pushFittedText(GuiGraphicsExtractor context, Font font,
		String text, float x, float y, float maxWidth)
	{
		float fit = Math.min(1, maxWidth / Math.max(1, font.width(text)));
		float height = Math.min(1, fit / 0.6F);
		Matrix3x2fStack pose = context.pose();
		pose.pushMatrix();
		pose.translate(x, y + (8 - 8 * height) / 2);
		pose.scale(fit, height);
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
