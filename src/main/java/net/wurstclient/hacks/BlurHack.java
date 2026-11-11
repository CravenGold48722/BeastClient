/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

/**
 * Blurs the world behind in-game screens, which vanilla only does on the
 * menus. Idea from Meteor Client's Blur (its own shader); this one uses
 * vanilla's menu blur, so its strength is the "Menu Background Blur"
 * option. Hooked in {@code BlurMixin}.
 */
@SearchTags({"blur", "background blur", "gui blur"})
public final class BlurHack extends Hack
{
	private final CheckboxSetting inventories = new CheckboxSetting(
		"Inventories", "Blurs behind inventories and containers.", true);
	private final CheckboxSetting chat =
		new CheckboxSetting("Chat", "Blurs behind the chat.", false);
	private final CheckboxSetting clickGui =
		new CheckboxSetting("ClickGUI", "Blurs behind Beast's ClickGUI.", true);
	private final CheckboxSetting other = new CheckboxSetting("Other screens",
		"Blurs behind every other in-game screen (pause menu, signs...).",
		true);
	
	public BlurHack()
	{
		super("Blur", "Blurs the world behind in-game screens.", false);
		setCategory(Category.RENDER);
		addSetting(inventories);
		addSetting(chat);
		addSetting(clickGui);
		addSetting(other);
	}
	
	public boolean shouldBlur(Screen screen)
	{
		if(!isEnabled() || MC.level == null)
			return false;
		
		if(screen instanceof AbstractContainerScreen<?>)
			return inventories.isChecked();
		if(screen instanceof ChatScreen)
			return chat.isChecked();
		if(screen instanceof ClickGuiScreen)
			return clickGui.isChecked();
		
		return other.isChecked();
	}
}
