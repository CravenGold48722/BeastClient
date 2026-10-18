/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.wurstclient.WurstClient;

/**
 * Raw key and mouse state, for the features ported from the CevAPI fork. That
 * fork is on Minecraft 26.3, which reads input through SDL; 26.1.2 still uses
 * GLFW, so the button and key codes here are GLFW's (the same values as
 * {@link InputConstants}).
 */
public enum SdlUtils
{
	;
	
	public static boolean isMouseButtonPressed(int button)
	{
		long window = WurstClient.MC.getWindow().handle();
		return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
	}
	
	public static int getMouseButtonState(int button)
	{
		return isMouseButtonPressed(button) ? InputConstants.PRESS
			: InputConstants.RELEASE;
	}
	
	public static int getKeyState(int key)
	{
		return InputConstants.isKeyDown(WurstClient.MC.getWindow(), key)
			? InputConstants.PRESS : InputConstants.RELEASE;
	}
}
