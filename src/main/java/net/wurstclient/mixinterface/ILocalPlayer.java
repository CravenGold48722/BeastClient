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
	 * Tells vanilla which position the server really got last, and how many
	 * ticks ago - after a hack held back its movement packets (RemoteView).
	 */
	public void wurst$setLastSentPosition(double x, double y, double z,
		boolean onGround, int ticksAgo);
}
