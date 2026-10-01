/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.IdentityHashMap;
import java.util.Iterator;
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
	 */
	public static void press(KeyMapping key, int ticks)
	{
		Press press = PRESSES.get(key);
		if(press == null)
		{
			press = new Press(key.isDown());
			PRESSES.put(key, press);
		}
		
		press.readsLeft = Math.max(press.readsLeft, ticks);
		IKeyMapping.get(key).setDownIgnoringToggle(true);
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
		for(KeyMapping key : PRESSES.keySet())
			IKeyMapping.get(key).setDownIgnoringToggle(true);
	}
	
	/**
	 * Called right after vanilla read the movement keys.
	 */
	public static void afterInputRead()
	{
		Iterator<Map.Entry<KeyMapping, Press>> itr =
			PRESSES.entrySet().iterator();
		
		while(itr.hasNext())
		{
			Map.Entry<KeyMapping, Press> entry = itr.next();
			Press press = entry.getValue();
			if(--press.readsLeft > 0)
				continue;
			
			itr.remove();
			restore(entry.getKey(), press);
		}
	}
	
	private static void restore(KeyMapping key, Press press)
	{
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
		private final boolean wasDown;
		private int readsLeft;
		
		private Press(boolean wasDown)
		{
			this.wasDown = wasDown;
		}
	}
}
