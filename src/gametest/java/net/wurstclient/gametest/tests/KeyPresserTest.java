/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.wurstclient.WurstClient;
import net.wurstclient.events.KeyPressListener;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.util.KeyPresser;

public final class KeyPresserTest extends SingleplayerTest
{
	public KeyPresserTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing KeyPresser");
		
		// Presses that run out while the player ticks. Letting a press expire
		// used to crash the game a few seconds after joining a world.
		context.runOnClient(mc -> {
			KeyPresser.press(mc.options.keyJump);
			KeyPresser.press(mc.options.keySprint, 3);
		});
		context.waitTicks(5);
		
		if(context.computeOnClient(
			mc -> mc.options.keyJump.isDown() || mc.options.keySprint.isDown()))
			throw new RuntimeException("KeyPresser didn't let go of a key");
		
		context.waitFor(mc -> mc.player.onGround());
		context.waitTick();
		
		testLooksLikeARealKey();
		testSharedKey();
	}
	
	/**
	 * Sprint on W, like the user's binds (2026-11-04): a sprint press must not
	 * press W - W is forward too, so every sprint press walked forward, and
	 * SnappyTappy (keys from the keyboard state each tick) or closing a
	 * screen kept it walking after the user let go.
	 */
	private void testSharedKey()
	{
		InputConstants.Key oldSprint = context.computeOnClient(
			mc -> IKeyMapping.get(mc.options.keySprint).getBoundKey());
		context.runOnClient(mc -> {
			mc.options.keySprint
				.setKey(IKeyMapping.get(mc.options.keyUp).getBoundKey());
			KeyMapping.resetMapping();
			KeyPresser.press(mc.options.keySprint, 3);
		});
		
		boolean wPolled = context.computeOnClient(
			mc -> InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_W));
		context.waitTick();
		boolean forward =
			context.computeOnClient(mc -> mc.options.keyUp.isDown());
		context.waitTicks(5);
		boolean forwardLater =
			context.computeOnClient(mc -> mc.options.keyUp.isDown());
		
		context.runOnClient(mc -> {
			mc.options.keySprint.setKey(oldSprint);
			KeyMapping.resetMapping();
		});
		
		if(wPolled)
			throw new RuntimeException(
				"A sprint press on W pressed W itself (the forward key)");
		if(forward || forwardLater)
			throw new RuntimeException(
				"A sprint press on W held the forward key down");
	}
	
	/**
	 * What key-display and key-sound mods see (user, 2026-11-04): a press
	 * comes in through vanilla's GLFW key callback at the frame's poll and on
	 * to its key handler (CreamyKeys hooks it - so does Wurst's
	 * KeyPressEvent), the key is still down in the frames after the tick
	 * that used it (TipTapShow draws KeyMapping.isDown() each frame), and
	 * GLFW's own key state and the keyboard polling read it held - while
	 * isActuallyDown() still reads only the user's keys.
	 */
	private void testLooksLikeARealKey()
	{
		List<Integer> actions = Collections.synchronizedList(new ArrayList<>());
		KeyPressListener listener = e -> {
			if(e.getKeyCode() == GLFW.GLFW_KEY_SPACE)
				actions.add(e.getAction());
		};
		
		context.runOnClient(mc -> {
			WurstClient.INSTANCE.getEventManager().add(KeyPressListener.class,
				listener);
			KeyPresser.press(mc.options.keyJump);
		});
		
		boolean polled = context.computeOnClient(mc -> InputConstants
			.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_SPACE));
		boolean glfwState = context
			.computeOnClient(mc -> GLFW.glfwGetKey(mc.getWindow().handle(),
				GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS);
		boolean counted = context.computeOnClient(
			mc -> IKeyMapping.get(mc.options.keyJump).isActuallyDown());
		
		// the tick that reads it, then a frame: still down
		context.waitTick();
		boolean downAfter =
			context.computeOnClient(mc -> mc.options.keyJump.isDown());
		
		context.waitTicks(3);
		boolean upLater =
			!context.computeOnClient(mc -> mc.options.keyJump.isDown());
		// click counters (BoxHud, TipTapShow, lazykeys...) count it too
		boolean clicked =
			context.computeOnClient(mc -> mc.options.keyJump.consumeClick());
		context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
			.remove(KeyPressListener.class, listener));
		
		if(!actions.equals(List.of(GLFW.GLFW_PRESS, GLFW.GLFW_RELEASE)))
			throw new RuntimeException(
				"A KeyPresser tap didn't go through vanilla's key handler as"
					+ " one press and one release: " + actions);
		if(!polled)
			throw new RuntimeException(
				"Polling the keyboard didn't see the held key");
		if(!glfwState)
			throw new RuntimeException("GLFW's own key state wasn't down");
		if(counted)
			throw new RuntimeException(
				"isActuallyDown() took KeyPresser's press for the user's");
		if(!downAfter)
			throw new RuntimeException(
				"A tap was let go before a frame could show it");
		if(!upLater)
			throw new RuntimeException("The tap was never let go");
		if(!clicked)
			throw new RuntimeException(
				"The tap didn't count as a click of the key");
		
		context.waitFor(mc -> mc.player.onGround());
		context.waitTick();
	}
}
