/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.nicewurst;

import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.world.phys.Vec3;

/**
 * The features ported from the CevAPI fork ask this class whether its "Nice
 * Wurst" build (ESP only on visible targets) is active. Beast has no such
 * build, so everything passes through unchanged.
 */
public enum NiceWurstModule
{
	;
	
	public static boolean isActive()
	{
		return false;
	}
	
	public static boolean enforceDepthTest(boolean depthTest)
	{
		return depthTest;
	}
	
	public static DisplayMode enforceTextLayer(DisplayMode layer)
	{
		return layer;
	}
	
	public static boolean shouldRenderTarget(Vec3 point)
	{
		return true;
	}
}
