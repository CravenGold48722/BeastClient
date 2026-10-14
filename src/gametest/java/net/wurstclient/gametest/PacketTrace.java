/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;

/**
 * When the client's connection sent and received packets, while recording -
 * fed by the gametest ConnectionMixin, independent of Wurst's own events.
 *
 * <p>
 * Anticheats' timer checks compare the client's ticks with real time, kept by
 * their own pings. A client that ticks evenly still looks ahead when the
 * server goes silent for a while (busy, e.g. right after a join), so a timer
 * flag only means something next to these numbers: how evenly the client
 * ticked, and the longest the server sent nothing.
 */
public enum PacketTrace
{
	;
	
	private static volatile boolean recording;
	private static long windowStart = System.nanoTime();
	private static final ConcurrentLinkedQueue<long[]> EVENTS =
		new ConcurrentLinkedQueue<>();
	
	/** Every packet sent while recording, for the feature tests to inspect. */
	private static final ConcurrentLinkedQueue<Sent> SENT =
		new ConcurrentLinkedQueue<>();
	
	private static final long IN = 0;
	private static final long TICK_END = 1;
	
	public static void start()
	{
		EVENTS.clear();
		SENT.clear();
		windowStart = System.nanoTime();
		recording = true;
	}
	
	public static void stop()
	{
		recording = false;
		EVENTS.clear();
		SENT.clear();
	}
	
	public static void sent(Object packet)
	{
		if(!recording)
			return;
		
		long now = System.nanoTime();
		SENT.add(new Sent(now, packet));
		if(packet instanceof ServerboundClientTickEndPacket)
			EVENTS.add(new long[]{now, TICK_END});
	}
	
	/** The packets sent since the last call, oldest first. */
	public static List<Sent> takeSent()
	{
		List<Sent> sent = new ArrayList<>();
		for(Sent s; (s = SENT.poll()) != null;)
			sent.add(s);
		return sent;
	}
	
	public static void received(Object packet)
	{
		if(recording)
			EVENTS.add(new long[]{System.nanoTime(), IN});
	}
	
	/** The numbers since the last call. */
	public static Stats takeStats()
	{
		List<long[]> events = new ArrayList<>();
		for(long[] e; (e = EVENTS.poll()) != null;)
			events.add(e);
			
		// from the start of the window to its end: a server that sent nothing
		// at all was silent the whole time
		long now = System.nanoTime();
		long serverSilence = 0;
		long lastIn = windowStart;
		List<Long> ticks = new ArrayList<>();
		for(long[] e : events)
			if(e[1] == IN)
			{
				serverSilence = Math.max(serverSilence, e[0] - lastIn);
				lastIn = e[0];
				
			}else
				ticks.add(e[0]);
		serverSilence = Math.max(serverSilence, now - lastIn);
		windowStart = now;
		
		long tickGap = 0;
		int fastestSecond = ticks.isEmpty() ? 0 : 1;
		for(int i = 1, from = 0; i < ticks.size(); i++)
		{
			tickGap = Math.max(tickGap, ticks.get(i) - ticks.get(i - 1));
			while(ticks.get(i) - ticks.get(from) > 1_000_000_000L)
				from++;
			fastestSecond = Math.max(fastestSecond, i - from + 1);
		}
		
		return new Stats(serverSilence / 1_000_000, tickGap / 1_000_000,
			fastestSecond);
	}
	
	/** A packet the client sent, and when (System.nanoTime). */
	public record Sent(long nanos, Object packet)
	{}
	
	/**
	 * @param serverSilenceMs
	 *            the longest the server sent nothing
	 * @param tickGapMs
	 *            the longest between two client ticks
	 * @param fastestSecond
	 *            the most client ticks in any one second (20 = real time)
	 */
	public record Stats(long serverSilenceMs, long tickGapMs, int fastestSecond)
	{
		@Override
		public String toString()
		{
			return String.format(Locale.ROOT,
				"client: %d ticks/s at most, gaps up to %d ms; server silent up to %d ms",
				fastestSecond, tickGapMs, serverSilenceMs);
		}
	}
}
