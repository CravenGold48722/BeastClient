/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.lwjgl.glfw.GLFWCharCallbackI;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWDropCallbackI;
import org.lwjgl.glfw.GLFWIMEStatusCallbackI;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWPreeditCallbackI;
import org.lwjgl.glfw.GLFWScrollCallbackI;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.wurstclient.util.KeyPresser;

/**
 * KeyPresser's presses as real keys (see {@link KeyPresser}): the callbacks
 * vanilla hands GLFW are where its events go, and a held key reads as held
 * to anything polling the keyboard.
 *
 * <p>
 * Wraps of the whole methods, other mods' hooks included: Fabric's gametest
 * input answers {@code isKeyDown} at HEAD and cancels, and skips setting up
 * the callbacks, so HEAD/RETURN hooks never ran there.
 */
@Mixin(InputConstants.class)
public class InputConstantsMixin
{
	@WrapMethod(method = "isKeyDown")
	private static boolean onIsKeyDown(Window window, int key,
		Operation<Boolean> original)
	{
		return original.call(window, key) || KeyPresser.isVirtuallyDown(key);
	}
	
	@WrapMethod(method = "setupKeyboardCallbacks")
	private static void onSetupKeyboardCallbacks(Window window,
		GLFWKeyCallbackI keyCallback, GLFWCharCallbackI charCallback,
		GLFWPreeditCallbackI preeditCallback,
		GLFWIMEStatusCallbackI imeStatusCallback, Operation<Void> original)
	{
		KeyPresser.setKeyCallback(keyCallback);
		original.call(window, keyCallback, charCallback, preeditCallback,
			imeStatusCallback);
	}
	
	@WrapMethod(method = "setupMouseCallbacks")
	private static void onSetupMouseCallbacks(Window window,
		GLFWCursorPosCallbackI cursorPosCallback,
		GLFWMouseButtonCallbackI mouseButtonCallback,
		GLFWScrollCallbackI scrollCallback, GLFWDropCallbackI dropCallback,
		Operation<Void> original)
	{
		KeyPresser.setMouseButtonCallback(mouseButtonCallback);
		original.call(window, cursorPosCallback, mouseButtonCallback,
			scrollCallback, dropCallback);
	}
}
