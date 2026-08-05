/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"toggle messages", "notifications", "action bar", "actionbar",
	"toggle notifications", "chat notifications"})
@DontBlock
public final class ToggleMessagesOtf extends OtherFeature
{
	private final CheckboxSetting enabled = new CheckboxSetting("Enabled",
		"Announces when a hack is toggled on or off.", true);
	
	private final CheckboxSetting actionBar = new CheckboxSetting("Action bar",
		"Shows §lToggled Spider ON§r above your hotbar.", true);
	
	private final CheckboxSetting chat = new CheckboxSetting("Chat",
		"Prints §l[Beast] Toggled Spider on.§r in chat.", true);
	
	public ToggleMessagesOtf()
	{
		super("ToggleMessages",
			"Announces hack toggles on the action bar and in chat.");
		
		addSetting(enabled);
		addSetting(actionBar);
		addSetting(chat);
	}
	
	public boolean isEnabled()
	{
		return enabled.isChecked();
	}
	
	public boolean isActionBarShown()
	{
		return actionBar.isChecked();
	}
	
	public boolean isChatShown()
	{
		return chat.isChecked();
	}
}
