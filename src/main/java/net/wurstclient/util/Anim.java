/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

/**
 * A value that glides towards its target every frame (exponential ease-out,
 * by real time), for the UI's animations. The first update snaps to the
 * target, so nothing animates the first time it's drawn.
 */
public final class Anim
{
	private static volatile boolean instant;
	
	private final float speed;
	private float value;
	private long lastNanos;
	
	/**
	 * @param speed
	 *            how fast it closes the gap, per second: about 95% of it in
	 *            3 / speed seconds
	 */
	public Anim(float speed)
	{
		this.speed = speed;
	}
	
	/** Moves towards the target for the time since the last update. */
	public float update(float target)
	{
		long now = System.nanoTime();
		if(lastNanos == 0 || instant)
			value = target;
		else
		{
			float dt = Math.min((now - lastNanos) / 1e9F, 0.25F);
			value += (target - value) * (1 - (float)Math.exp(-speed * dt));
			if(Math.abs(target - value) < 0.002F)
				value = target;
		}
		
		lastNanos = now;
		return value;
	}
	
	public float get()
	{
		return value;
	}
	
	/** Jumps to the value, e.g. where a dragged tab was let go. */
	public void snap(float value)
	{
		this.value = value;
		lastNanos = System.nanoTime();
	}
	
	/** Smoothstep of a 0-1 progress, for easing in and out. */
	public static float ease(float t)
	{
		float c = Math.max(0, Math.min(1, t));
		return c * c * (3 - 2 * c);
	}
	
	/**
	 * Every animation jumps straight to its end. For the gametests, whose
	 * screenshots would otherwise catch them halfway.
	 */
	public static void setInstant(boolean instant)
	{
		Anim.instant = instant;
	}
}
