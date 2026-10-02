/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
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
 * A press lasts for a number of input reads ({@code KeyboardInput.tick()},
 * once per tick), then the key goes back to what the user is actually
 * holding. Pressing during {@code onUpdate()} is seen by the same tick's
 * movement.
 */
public enum KeyPresser
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	/** Key -> number of input reads it stays pressed for. */
	private static final Map<KeyMapping, Press> PRESSES =
		new IdentityHashMap<>();
	
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
	 * Called right before vanilla reads the movement keys. Re-asserts held
	 * keys in case a real key event let go of them in between.
	 */
	public static void beforeInputRead()
	{
		if(!canPressNow())
			return;
		
		// copy, in case pressing a key leads to a press()/release()
		for(KeyMapping key : new ArrayList<>(PRESSES.keySet()))
		{
			Press press = PRESSES.get(key);
			if(press != null)
				assertDown(key, press);
		}
	}
	
	/**
	 * Called right after vanilla read the movement keys.
	 */
	public static void afterInputRead()
	{
		// waiting presses aren't used up while a screen is open
		if(!canPressNow())
			return;
			
		// Work on a copy: IdentityHashMap entries die as soon as they're
		// removed (getKey() then throws), and restoring a key can lead to a
		// new press() that changes the map mid-loop.
		for(KeyMapping key : new ArrayList<>(PRESSES.keySet()))
		{
			Press press = PRESSES.get(key);
			if(press == null || --press.readsLeft > 0)
				continue;
			
			PRESSES.remove(key);
			restore(key, press);
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
		
		IKeyMapping.get(key).setDownIgnoringToggle(true);
	}
	
	private static void restore(KeyMapping key, Press press)
	{
		// never actually pressed, so there's nothing to undo
		if(press.wasDown == null)
			return;
			
		// Toggle Sprint / Toggle Sneak: the key's state is the toggle, not
		// the physical key, so put back whatever it was before.
		boolean down = isInToggleMode(key) ? press.wasDown
			: IKeyMapping.get(key).isActuallyDown();
		IKeyMapping.get(key).setDownIgnoringToggle(down);
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
	}
}
