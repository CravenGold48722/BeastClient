/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.wurstclient.WurstClient;
import net.wurstclient.mixinterface.IKeyMapping;

/**
 * Presses movement keys the way a player would, so that jumping, sprinting,
 * swimming up etc. go through vanilla's own input handling.
 *
 * <p>
 * Since 1.21.2 the client tells the server which movement keys are held
 * (ServerboundPlayerInputPacket). Calling {@code jumpFromGround()} or
 * {@code setSprinting(true)} directly produces a jump or a sprint that the
 * input packet doesn't back up. Pressing the key instead lets vanilla decide
 * whether the jump/sprint can happen at all, and sends the matching input.
 *
 * <p>
 * Down to the key (user, 2026-11-04: "the only thing possibly more realistic
 * than it should be a physical robot pressing a key"; no OS-level input -
 * Windows marks SendInput as injected). Inside the game a press is the same
 * as a finger on the key from GLFW up:
 * <ul>
 * <li>its PRESS and RELEASE go into the very callbacks vanilla handed GLFW
 * ({@code InputConstants.setupKeyboardCallbacks/setupMouseCallbacks}), at
 * the moment GLFW hands out events - right after the frame's
 * {@code glfwPollEvents} - with the key's real scancode and the modifiers
 * held, so from there it takes a real key's path: the callback, vanilla's
 * task queue, {@code KeyboardHandler.keyPress} (CreamyKeys' click sound,
 * Wurst's KeyPressEvent, every mod hooking it);</li>
 * <li>GLFW's own key state says it's down: {@code GLFW.glfwGetKey} and
 * {@code glfwGetMouseButton} ({@code GLFWMixin}, around Ixeris' cache too),
 * and {@code InputConstants.isKeyDown} (Fabric's gametest input answers that
 * one itself) - anything polling the keyboard sees it;</li>
 * <li>a used-up press comes up at the next tick's input read, so the frames
 * in between draw it down (TipTapShow reads {@code KeyMapping.isDown()} each
 * frame) - a one-tick tap used to go down and up inside one tick.</li>
 * </ul>
 * A key the user is holding anyway gets no events of its own, and
 * {@code IKeyMapping.isActuallyDown()} still reads only the user's keys.
 *
 * <p>
 * A press lasts for a number of input reads ({@code KeyboardInput.tick()},
 * once per tick), then the key goes back to what the user is actually
 * holding. Pressing during {@code onUpdate()} is seen by the same tick's
 * movement (the key's state is set at once; its events follow at the
 * frame's poll, like the rest of a real press). Pressing again before the
 * key came up (every tick, for a hold) keeps it down - no new press.
 */
public enum KeyPresser
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	/** Key -> number of input reads it stays pressed for. */
	private static final Map<KeyMapping, Press> PRESSES =
		new IdentityHashMap<>();
	
	/** Events waiting for GLFW's next poll, in order (render thread). */
	private static final ArrayList<Runnable> QUEUED = new ArrayList<>();
	
	/** The callbacks vanilla gave GLFW: where a real key's events go. */
	private static volatile GLFWKeyCallbackI keyCallback;
	private static volatile GLFWMouseButtonCallbackI mouseButtonCallback;
	
	/**
	 * Keys and mouse buttons held through this class, as GLFW's state
	 * queries report them (they can come from Ixeris' thread).
	 */
	private static volatile Set<Integer> virtualKeys = Set.of();
	private static volatile Set<Integer> virtualButtons = Set.of();
	
	/**
	 * {@code -Dwurst.keypresser.events=false}: only the bindings' state, no
	 * key-level events or GLFW state (the old way) - for telling a bug in
	 * the key-level path apart from one elsewhere.
	 */
	private static final boolean KEY_EVENTS =
		!"false".equals(System.getProperty("wurst.keypresser.events"));
	
	/** Set while reading the user's own keys: no virtual presses then. */
	private static volatile boolean readingPhysical;
	
	/**
	 * Presses the key for the next input read, i.e. a single tap.
	 */
	public static void press(KeyMapping key)
	{
		press(key, 1);
	}
	
	/**
	 * Holds the key for the next {@code ticks} input reads.
	 *
	 * <p>
	 * While a screen is open, the press waits: vanilla lets go of every key
	 * when a screen opens and doesn't take movement input until it closes,
	 * so jumping or sprinting with e.g. the inventory open would be something
	 * no real client does. A press made from chat (like {@code .jump}) goes
	 * through right after the chat closes.
	 */
	public static void press(KeyMapping key, int ticks)
	{
		Press press = PRESSES.computeIfAbsent(key, k -> new Press());
		press.readsLeft = Math.max(press.readsLeft, ticks);
		// pressed again before it came up: it just stays down
		press.comingUp = false;
		
		if(canPressNow())
			assertDown(key, press);
	}
	
	/**
	 * Lets go of a key pressed through this class right away.
	 */
	public static void release(KeyMapping key)
	{
		Press press = PRESSES.remove(key);
		if(press != null)
			restore(key, press);
	}
	
	/**
	 * Called right before vanilla reads the movement keys - after this
	 * tick's {@code onUpdate()}s, so a key pressed again there stays down.
	 * Lets go of the keys whose press ended last tick and re-asserts the
	 * held ones in case a real key event let go of them in between.
	 */
	public static void beforeInputRead()
	{
		if(!canPressNow())
		{
			// vanilla let go of every key when the screen opened
			boolean changed = false;
			for(Press press : PRESSES.values())
			{
				changed |= press.eventDown;
				press.eventDown = false;
			}
			if(changed)
				updateVirtualState();
			return;
		}
		
		// copy, in case pressing a key leads to a press()/release()
		for(KeyMapping key : new ArrayList<>(PRESSES.keySet()))
		{
			Press press = PRESSES.get(key);
			if(press == null)
				continue;
			
			if(press.comingUp)
			{
				PRESSES.remove(key);
				restore(key, press);
			}else
				assertDown(key, press);
		}
	}
	
	/**
	 * Called right after vanilla read the movement keys. A press that's used
	 * up stays down until the next tick's read (see beforeInputRead()).
	 */
	public static void afterInputRead()
	{
		// waiting presses aren't used up while a screen is open
		if(!canPressNow())
			return;
		
		for(Press press : PRESSES.values())
			if(--press.readsLeft <= 0)
				press.comingUp = true;
	}
	
	/**
	 * Called right after GLFW polled the OS for events
	 * ({@code RenderSystem.pollEvents}): the moment a real key's events
	 * reach the game's callbacks. Hands over the queued ones.
	 */
	public static void onEventsPolled()
	{
		if(QUEUED.isEmpty())
			return;
		
		ArrayList<Runnable> events = new ArrayList<>(QUEUED);
		QUEUED.clear();
		for(Runnable event : events)
			event.run();
	}
	
	/** The callbacks vanilla registers with GLFW (InputConstantsMixin). */
	public static void setKeyCallback(GLFWKeyCallbackI callback)
	{
		keyCallback = callback;
	}
	
	public static void setMouseButtonCallback(GLFWMouseButtonCallbackI callback)
	{
		mouseButtonCallback = callback;
	}
	
	/** Whether GLFW should report this key held (GLFWMixin). */
	public static boolean isVirtuallyDown(int keyCode)
	{
		return !readingPhysical && virtualKeys.contains(keyCode);
	}
	
	/** Whether GLFW should report this mouse button held (GLFWMixin). */
	public static boolean isVirtuallyDownMouse(int button)
	{
		return !readingPhysical && virtualButtons.contains(button);
	}
	
	/** What the user's own fingers are doing, without any virtual press. */
	public static boolean isPhysicallyDown(KeyMapping key)
	{
		return readPhysical(() -> IKeyMapping.get(key).isActuallyDown());
	}
	
	/**
	 * Runs the read with this class's presses hidden from GLFW's state.
	 */
	public static boolean readPhysical(BooleanSupplier read)
	{
		boolean was = readingPhysical;
		readingPhysical = true;
		try
		{
			return read.getAsBoolean();
		}finally
		{
			readingPhysical = was;
		}
	}
	
	/**
	 * No screen open, or InvWalk is on (which lets you move with screens
	 * open anyway).
	 */
	private static boolean canPressNow()
	{
		if(MC.screen == null)
			return true;
		
		WurstClient wurst = WurstClient.INSTANCE;
		return wurst.isEnabled() && wurst.getHax() != null
			&& wurst.getHax().invWalkHack.isEnabled();
	}
	
	private static void assertDown(KeyMapping key, Press press)
	{
		// remember what to go back to the first time the key is pressed, not
		// while the press was still waiting behind a screen
		if(press.wasDown == null)
			press.wasDown = key.isDown();
			
		// The key goes down, events and all - only with no screen open: with
		// one (InvWalk) a real key goes to the screen, where space would
		// click its focused button. And only when no other key binding shares
		// the key: pressing it for real presses that one too. (The user's
		// sprint is on W, their forward key: every sprint press walked
		// forward, and SnappyTappy kept W held from the keyboard state - you
		// let go of W and kept walking, 2026-11-04.)
		if(!press.eventDown && MC.screen == null && !isPhysicallyDown(key)
			&& !key.isUnbound() && !isKeyShared(key) && KEY_EVENTS)
		{
			press.eventDown = true;
			updateVirtualState();
			queueEvent(key, GLFW.GLFW_PRESS);
		}
		
		// This tick sees it now. (Toggle Sprint/Sneak: its PRESS flips the
		// toggle when it arrives - the press itself is what holds it here.)
		IKeyMapping.get(key).setDownIgnoringToggle(true);
	}
	
	private static void restore(KeyMapping key, Press press)
	{
		if(press.eventDown)
		{
			press.eventDown = false;
			updateVirtualState();
			if(!isPhysicallyDown(key))
				queueEvent(key, GLFW.GLFW_RELEASE);
		}
		
		// never actually pressed, so there's nothing to undo
		if(press.wasDown == null)
			return;
			
		// Toggle Sprint / Toggle Sneak: the key's state is the toggle, not
		// the physical key, so put back whatever it was before.
		boolean down =
			isInToggleMode(key) ? press.wasDown : isPhysicallyDown(key);
		IKeyMapping.get(key).setDownIgnoringToggle(down);
	}
	
	/** Whether another key binding is on the same key as this one. */
	private static boolean isKeyShared(KeyMapping key)
	{
		InputConstants.Key bound = IKeyMapping.get(key).getBoundKey();
		for(KeyMapping other : MC.options.keyMappings)
			if(other != key && !other.isUnbound()
				&& IKeyMapping.get(other).getBoundKey().equals(bound))
				return true;
		return false;
	}
	
	/** Republishes which keys/buttons GLFW should report held. */
	private static void updateVirtualState()
	{
		Set<Integer> keys = new HashSet<>();
		Set<Integer> buttons = new HashSet<>();
		for(Map.Entry<KeyMapping, Press> e : PRESSES.entrySet())
		{
			if(!e.getValue().eventDown)
				continue;
			
			InputConstants.Key k = IKeyMapping.get(e.getKey()).getBoundKey();
			if(k.getType() == InputConstants.Type.KEYSYM)
				keys.add(k.getValue());
			else if(k.getType() == InputConstants.Type.MOUSE)
				buttons.add(k.getValue());
		}
		virtualKeys = Set.copyOf(keys);
		virtualButtons = Set.copyOf(buttons);
	}
	
	/**
	 * What GLFW's callback gets for a real key: the key, its scancode and
	 * the modifier keys held at that moment - delivered at the next poll.
	 */
	private static void queueEvent(KeyMapping key, int action)
	{
		Window window = MC.getWindow();
		long handle = window.handle();
		InputConstants.Key bound = IKeyMapping.get(key).getBoundKey();
		int code = bound.getValue();
		int mods = heldModifiers(window);
		
		switch(bound.getType())
		{
			case KEYSYM:
			{
				int scancode = GLFW.glfwGetKeyScancode(code);
				QUEUED.add(
					() -> deliverKey(handle, code, scancode, action, mods));
				break;
			}
			
			case SCANCODE:
			QUEUED.add(() -> deliverKey(handle, GLFW.GLFW_KEY_UNKNOWN, code,
				action, mods));
			break;
			
			case MOUSE:
			QUEUED.add(() -> deliverButton(handle, code, action, mods));
			break;
			
			default:
			break;
		}
	}
	
	private static void deliverKey(long handle, int key, int scancode,
		int action, int mods)
	{
		GLFWKeyCallbackI callback = keyCallback;
		if(callback != null)
			callback.invoke(handle, key, scancode, action, mods);
		else
			// what vanilla's callback does
			MC.execute(() -> MC.keyboardHandler.keyPress(handle, action,
				new KeyEvent(key, scancode, mods)));
	}
	
	private static void deliverButton(long handle, int button, int action,
		int mods)
	{
		GLFWMouseButtonCallbackI callback = mouseButtonCallback;
		if(callback != null)
			callback.invoke(handle, button, action, mods);
		else
			MC.execute(() -> MC.mouseHandler.onButton(handle,
				new MouseButtonInfo(button, mods), action));
	}
	
	private static int heldModifiers(Window window)
	{
		int mods = 0;
		if(isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
			|| isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT))
			mods |= GLFW.GLFW_MOD_SHIFT;
		if(isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
			|| isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL))
			mods |= GLFW.GLFW_MOD_CONTROL;
		if(isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
			|| isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT))
			mods |= GLFW.GLFW_MOD_ALT;
		if(isKeyDown(window, GLFW.GLFW_KEY_LEFT_SUPER)
			|| isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SUPER))
			mods |= GLFW.GLFW_MOD_SUPER;
		return mods;
	}
	
	/** Physical keys plus the ones held through this class. */
	private static boolean isKeyDown(Window window, int code)
	{
		return InputConstants.isKeyDown(window, code);
	}
	
	private static boolean isInToggleMode(KeyMapping key)
	{
		Options options = MC.options;
		if(key == options.keySprint)
			return options.toggleSprint().get();
		if(key == options.keyShift)
			return options.toggleCrouch().get();
		return false;
	}
	
	private static final class Press
	{
		/** Key state before the press; null until it's actually pressed. */
		private Boolean wasDown;
		private int readsLeft;
		/** Used up; lets go at the next input read unless pressed again. */
		private boolean comingUp;
		/** Sent down (events queued) and not up yet. */
		private boolean eventDown;
	}
}
