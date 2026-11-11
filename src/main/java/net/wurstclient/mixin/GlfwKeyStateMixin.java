/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.wurstclient.util.KeyPresser;

/**
 * GLFW's own key state: a key held through {@link KeyPresser} is down to
 * {@code glfwGetKey}/{@code glfwGetMouseButton}, for mods that ask GLFW
 * directly. A wrap of the whole method, so Ixeris' cached answer (a HEAD
 * hook) is covered too.
 */
@Mixin(value = GLFW.class, remap = false)
public class GlfwKeyStateMixin
{
	@WrapMethod(method = "glfwGetKey(JI)I")
	private static int onGlfwGetKey(long window, int key,
		Operation<Integer> original)
	{
		int state = original.call(window, key);
		return state == GLFW.GLFW_RELEASE && KeyPresser.isVirtuallyDown(key)
			? GLFW.GLFW_PRESS : state;
	}
	
	@WrapMethod(method = "glfwGetMouseButton(JI)I")
	private static int onGlfwGetMouseButton(long window, int button,
		Operation<Integer> original)
	{
		int state = original.call(window, button);
		return state == GLFW.GLFW_RELEASE
			&& KeyPresser.isVirtuallyDownMouse(button) ? GLFW.GLFW_PRESS
				: state;
	}
}
