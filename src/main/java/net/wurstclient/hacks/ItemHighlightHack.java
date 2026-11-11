/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.ItemListSetting;

/**
 * Colors the slots holding chosen items in every inventory and container.
 * Idea from Meteor Client's ItemHighlight, written for Beast; drawn by
 * {@code ContainerOverlayHooksMixin}.
 */
@SearchTags({"item highlight", "highlight items"})
public final class ItemHighlightHack extends Hack
{
	private final ItemListSetting items = new ItemListSetting("Items",
		"The items to highlight.", "minecraft:totem_of_undying",
		"minecraft:enchanted_golden_apple", "minecraft:end_crystal");
	private final ColorSetting color = new ColorSetting("Color",
		"The slot color (drawn half see-through).", new Color(225, 25, 255));
	
	public ItemHighlightHack()
	{
		super("ItemHighlight",
			"Highlights chosen items in inventories and containers.", false);
		setCategory(Category.RENDER);
		addSetting(items);
		addSetting(color);
	}
	
	/** The fill color for a slot holding this stack, or 0 for none. */
	public int getSlotColor(ItemStack stack)
	{
		if(!isEnabled() || stack.isEmpty() || !items.contains(stack.getItem()))
			return 0;
		
		return color.getColorI(0x50);
	}
}
