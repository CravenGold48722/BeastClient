/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

/**
 * Boss bars with the same name become one bar with a counter. Idea from
 * Meteor Client's BossStack, written for Beast; hooked in
 * {@code BossStackMixin}. RenderAdjust's "Hide boss bars" still hides them
 * all.
 */
@SearchTags({"boss stack", "boss bar", "bossbar"})
public final class BossStackHack extends Hack
{
	private final CheckboxSetting stack = new CheckboxSetting("Stack",
		"Shows bars with the same name once, with a count.", true);
	private final CheckboxSetting hideName = new CheckboxSetting("Hide names",
		"Hides the text above each boss bar.", false);
	private final SliderSetting spacing = new SliderSetting("Bar spacing",
		"The space between boss bars (vanilla: 9).", 9, 0, 20, 1,
		ValueDisplay.INTEGER);
	
	/** How many bars each shown bar stands for (this frame). */
	private final Map<LerpingBossEvent, Integer> counts = new WeakHashMap<>();
	
	public BossStackHack()
	{
		super("BossStack", "Stacks boss bars with the same name into one.",
			false);
		setCategory(Category.RENDER);
		addSetting(stack);
		addSetting(hideName);
		addSetting(spacing);
	}
	
	@Override
	protected void onDisable()
	{
		counts.clear();
	}
	
	public Iterator<LerpingBossEvent> filterBars(
		Iterator<LerpingBossEvent> bars)
	{
		counts.clear();
		if(!isEnabled() || !stack.isChecked())
			return bars;
		
		LinkedHashMap<String, LerpingBossEvent> byName = new LinkedHashMap<>();
		bars.forEachRemaining(bar -> {
			LerpingBossEvent first =
				byName.putIfAbsent(bar.getName().getString(), bar);
			if(first != null)
				counts.merge(first, 2, (old, two) -> old + 1);
		});
		return byName.values().iterator();
	}
	
	public Component getBarName(LerpingBossEvent bar, Component name)
	{
		if(!isEnabled())
			return name;
		
		if(hideName.isChecked())
			return Component.empty();
		
		Integer count = counts.get(bar);
		return count == null ? name : name.copy().append(" x" + count);
	}
	
	public int getSpacing(int vanilla)
	{
		return isEnabled() ? spacing.getValueI() : vanilla;
	}
}
