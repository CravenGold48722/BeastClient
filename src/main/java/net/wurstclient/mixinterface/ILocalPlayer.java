/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

public interface ILocalPlayer
{
	public boolean isTouchingWaterBypass();
	
	/**
	 * Sets vanilla's counter for the once-per-20-ticks position packet
	 * (ElytraWalk).
	 */
	public void elytraWalk$setPositionReminder(int value);
}
