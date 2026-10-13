/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.wurstclient.WurstClient;

/**
 * A nanosecond clock that runs at the game's speed: client ticks that have
 * actually run, plus how far the current frame is into the next one, at 50ms
 * per tick. At the normal 20 ticks per second it runs with real time.
 *
 * <p>
 * For per-frame aiming whose speeds and delays are tuned per second of play.
 * On real time, a faster tick rate ({@code /tick rate}) would make every turn
 * take more ticks, and ScaffoldWalk's planner, which predicts the camera tick
 * by tick, would be wrong. Counting the ticks that ran (rather than scaling
 * real time by the tick rate) also stays right when the game can't keep up
 * with the tick rate - the test client runs at most one tick per frame.
 */
public enum GameClock
{
	;
	
	private static final long NS_PER_TICK = 50_000_000L;
	
	private static long ticks;
	private static long lastNs;
	
	/** Called at the start of every client tick. */
	public static void onClientTick()
	{
		ticks++;
	}
	
	/** Game-time nanoseconds; only differences between calls mean anything. */
	public static long nanoTime()
	{
		float partial =
			WurstClient.MC.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		long now = ticks * NS_PER_TICK
			+ (long)(Math.min(Math.max(partial, 0), 1) * NS_PER_TICK);
		
		// never backwards (a frame's partial tick can lag a tick that ran)
		lastNs = Math.max(lastNs, now);
		return lastNs;
	}
}
