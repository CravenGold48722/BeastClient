/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.util.Objects;

import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.NavigatorHack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.util.ToggleAnnouncer;

public abstract class Hack extends Feature
{
	private final String name;
	private final String description;
	private Category category;
	
	private boolean enabled;
	private final boolean stateSaved =
		!getClass().isAnnotationPresent(DontSaveState.class);
	
	/**
	 * Set immediately before a toggle that the user themselves asked for, and
	 * consumed by the very next {@link #setEnabled(boolean)} call.
	 *
	 * <p>
	 * Only user-initiated toggles get announced. Hacks routinely drive each
	 * other - AimAssist's auto-combo flips AutoSprint on and off every single
	 * tick to reset sprint between hits - and announcing that would bury the
	 * chat under hundreds of messages a minute.
	 *
	 * <p>
	 * Consuming it on entry (rather than at the end) is what keeps a hack that
	 * switches itself back off inside {@code onEnable} - ClickGUI and Navigator
	 * both do - from announcing an instant "on" followed by "off".
	 */
	private static boolean userInitiatedToggle;
	
	/**
	 * Marks the next hack toggle as something the user asked for, so that it
	 * gets announced. Called from the ClickGUI, TabGUI, Navigator, keybinds and
	 * commands.
	 */
	public static void markUserInitiatedToggle()
	{
		userInitiatedToggle = true;
	}
	
	public Hack(String name)
	{
		this.name = Objects.requireNonNull(name);
		description = "description.wurst.hack." + name.toLowerCase();
		addPossibleKeybind(name, "Toggle " + name);
		
		if(name.contains(" "))
			throw new IllegalArgumentException(
				"Feature name must not contain spaces: " + name);
	}
	
	@Override
	public final String getName()
	{
		return name;
	}
	
	/**
	 * Returns the name of the hack to be displayed in HackList.
	 *
	 * <p>
	 * WARNING: This method can be called while <code>MC.player</code> is null.
	 */
	public String getRenderName()
	{
		return name;
	}
	
	@Override
	public final String getDescription()
	{
		return WURST.translate(description);
	}
	
	public final String getDescriptionKey()
	{
		return description;
	}
	
	@Override
	public final Category getCategory()
	{
		return category;
	}
	
	protected final void setCategory(Category category)
	{
		this.category = category;
	}
	
	@Override
	public final boolean isEnabled()
	{
		return enabled;
	}
	
	public final void setEnabled(boolean enabled)
	{
		// Always consumed on entry, even when the toggle is then rejected, so
		// the marker can never leak into an unrelated toggle later on.
		boolean announce = userInitiatedToggle;
		userInitiatedToggle = false;
		
		if(this.enabled == enabled)
			return;
		
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		if(enabled && tooManyHax.isEnabled() && tooManyHax.isBlocked(this))
			return;
		
		this.enabled = enabled;
		
		// NavigatorHack and ClickGuiHack open a screen and immediately switch
		// themselves back off, so they stay out of the HackList.
		if(!(this instanceof NavigatorHack || this instanceof ClickGuiHack))
			WURST.getHud().getHackList().updateState(this);
			
		// Every hack announces its toggle - but only when the user is the one
		// who asked for it.
		if(announce)
			ToggleAnnouncer.announce(this, enabled);
		
		if(enabled)
			onEnable();
		else
			onDisable();
		
		if(stateSaved)
			WURST.getHax().saveEnabledHax();
	}
	
	@Override
	public final String getPrimaryAction()
	{
		return enabled ? "Disable" : "Enable";
	}
	
	@Override
	public final void doPrimaryAction()
	{
		// This is only ever reached by a user clicking or selecting the
		// feature in the ClickGUI, TabGUI or Navigator.
		markUserInitiatedToggle();
		setEnabled(!enabled);
	}
	
	public final boolean isStateSaved()
	{
		return stateSaved;
	}
	
	protected void onEnable()
	{
		
	}
	
	protected void onDisable()
	{
		
	}
}
