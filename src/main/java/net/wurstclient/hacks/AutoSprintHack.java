/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.KeyPresser;

@SearchTags({"auto sprint"})
public final class AutoSprintHack extends Hack implements UpdateListener
{
	private final CheckboxSetting allDirections =
		new CheckboxSetting("Omnidirectional Sprint",
			"Sprint in all directions, not just forward.", true);
	
	private final CheckboxSetting hungry = new CheckboxSetting("Hungry Sprint",
		"Sprint even on low hunger.", false);
	
	public AutoSprintHack()
	{
		super("AutoSprint");
		setCategory(Category.MOVEMENT);
		addSetting(allDirections);
		addSetting(hungry);
		
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		// Hold the real sprint key, like a player with Toggle Sprint on.
		// Vanilla decides when a sprint can start or has to stop (and sends
		// the matching input), instead of us forcing setSprinting(true).
		// Omnidirectional and Hungry Sprint work by loosening those vanilla
		// checks in LocalPlayerMixin, so they still apply.
		KeyPresser.press(MC.options.keySprint);
	}
	
	public boolean shouldOmniSprint()
	{
		return isEnabled() && allDirections.isChecked();
	}
	
	public boolean shouldSprintHungry()
	{
		return isEnabled() && hungry.isChecked();
	}
}
