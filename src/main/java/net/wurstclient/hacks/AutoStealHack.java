/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.util.Util;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto steal", "ChestStealer", "chest stealer",
	"steal store buttons", "Steal/Store buttons"})
public final class AutoStealHack extends Hack
{
	private final SliderSetting delay = new SliderSetting("Delay",
		"Delay between moving stacks of items.\n"
			+ "Should be at least 70ms for NoCheat+ servers.",
		100, 0, 500, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting buttons = new CheckboxSetting(
		"Steal/Store buttons",
		"§lSteal§r: shift-double-click an empty slot in the"
			+ " container (not in your own inventory) to take everything.\n\n"
			+ "§lStore§r: the button at the top of the container puts"
			+ " your inventory in.\n\n"
			+ "Works whether or not AutoSteal itself is enabled.",
		true);
	
	/** Same window vanilla uses for a double-click. */
	private static final long DOUBLE_CLICK_MS = 250;
	
	private AbstractContainerScreen<?> lastEmptyClickScreen;
	private long lastEmptyClickTime;
	
	private final CheckboxSetting reverseSteal =
		new CheckboxSetting("Reverse steal order", false);
	
	private Thread thread;
	
	public AutoStealHack()
	{
		super("AutoSteal");
		setCategory(Category.ITEMS);
		addSetting(buttons);
		addSetting(delay);
		addSetting(reverseSteal);
	}
	
	public void steal(AbstractContainerScreen<?> screen, int rows)
	{
		startClickingSlots(screen, 0, rows * 9, true);
	}
	
	public void store(AbstractContainerScreen<?> screen, int rows)
	{
		startClickingSlots(screen, rows * 9, rows * 9 + 36, false);
	}
	
	private void startClickingSlots(AbstractContainerScreen<?> screen, int from,
		int to, boolean steal)
	{
		if(thread != null && thread.isAlive())
			thread.interrupt();
		
		thread = Thread.ofPlatform().name("AutoSteal")
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace()).daemon()
			.start(() -> shiftClickSlots(screen, from, to, steal));
	}
	
	private void shiftClickSlots(AbstractContainerScreen<?> screen, int from,
		int to, boolean steal)
	{
		List<Slot> slots = IntStream.range(from, to)
			.mapToObj(i -> screen.getMenu().slots.get(i)).toList();
		
		if(reverseSteal.isChecked() && steal)
			slots = slots.reversed();
			
		// This thread only does the waiting. Reading the slots and clicking
		// them happens on the game thread, which owns the container, the
		// screen and the connection - doing that from here raced the game
		// thread updating the same container.
		for(Slot slot : slots)
			try
			{
				if(!MC.submit(() -> isStillOpen(screen) && slot.hasItem())
					.join())
					continue;
				
				Thread.sleep(delay.getValueI());
				
				boolean clicked = MC.submit(() -> {
					// closed or replaced while we were waiting
					if(!isStillOpen(screen))
						return false;
					
					if(slot.hasItem())
						screen.slotClicked(slot, slot.index, 0,
							ContainerInput.QUICK_MOVE);
					return true;
				}).join();
				
				if(!clicked)
					break;
				
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
	}
	
	private boolean isStillOpen(AbstractContainerScreen<?> screen)
	{
		return MC.screen == screen && MC.player != null;
	}
	
	public boolean areButtonsVisible()
	{
		return buttons.isChecked();
	}
	
	/**
	 * The steal shortcut: shift-left-click an empty slot of the container
	 * twice in a row, quickly. Slots of the player's own inventory don't
	 * count.
	 *
	 * <p>
	 * Every click that matches (shift, left button, empty container slot,
	 * nothing on the cursor) is swallowed. Vanilla would send a click packet
	 * for it that does nothing on the server, so this way the shortcut itself
	 * sends no packets at all.
	 *
	 * @param hoveredSlot
	 *            the slot under the mouse
	 * @param rows
	 *            rows of container slots; the player's inventory starts after
	 * @return {@code true} if the click was used up by the shortcut
	 */
	public boolean onContainerClick(AbstractContainerScreen<?> screen,
		Slot hoveredSlot, MouseButtonEvent event, int rows)
	{
		if(!buttons.isChecked())
			return false;
		
		if(event.button() != 0 || !event.hasShiftDown())
			return false;
		
		if(hoveredSlot == null || hoveredSlot.hasItem()
			|| hoveredSlot.index >= rows * 9
			|| hoveredSlot.container == MC.player.getInventory())
			return false;
		
		if(!screen.getMenu().getCarried().isEmpty())
			return false;
		
		long now = Util.getMillis();
		if(screen == lastEmptyClickScreen
			&& now - lastEmptyClickTime <= DOUBLE_CLICK_MS)
		{
			lastEmptyClickScreen = null;
			steal(screen, rows);
			
		}else
		{
			lastEmptyClickScreen = screen;
			lastEmptyClickTime = now;
		}
		
		return true;
	}
	
	// See ContainerScreenMixin and ShulkerBoxScreenMixin
}
