/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.events.UpdateListener;

/**
 * Measures how fast every entity really moves, from the positions the server
 * sends rather than the smoothed position the client renders.
 *
 * <p>
 * The client glides other entities toward each new server position over a
 * few ticks, which would spread a teleport out into a believable-looking
 * run. The target of that glide is the raw last position the server
 * reported, so a jump between two of those is measured as what it is.
 *
 * <p>
 * Entities are tracked by UUID, so an anti-cheat bot that is removed and
 * re-added somewhere else a moment later is caught as a teleport too.
 */
public final class EntitySpeedTracker implements UpdateListener
{
	private static final Minecraft MC = WurstClient.MC;
	
	/**
	 * The server only sends a position update every 2-3 ticks per entity
	 * (players 2, most mobs 3). Spreading a displacement over more ticks than
	 * that would make a teleport after standing still look slow.
	 */
	private static final int MAX_UPDATE_INTERVAL = 3;
	
	/** How long a measured speed counts, in ticks. */
	public static final int MEMORY_TICKS = 40;
	
	/** An entity gone this long and back somewhere else counts as moved. */
	private static final int MAX_GONE_TICKS = 5;
	
	private final Map<UUID, Track> tracks = new HashMap<>();
	private ClientLevel lastLevel;
	private long tick;
	
	@Override
	public void onUpdate()
	{
		ClientLevel level = MC.level;
		if(level != lastLevel)
		{
			tracks.clear();
			lastLevel = level;
		}
		
		if(level == null)
			return;
		
		tick++;
		
		for(Entity e : level.entitiesForRendering())
		{
			if(e == MC.player)
				continue;
			
			Vec3 pos = getLatestServerPos(e);
			Track track = tracks.get(e.getUUID());
			
			if(track == null)
			{
				tracks.put(e.getUUID(), new Track(pos, tick));
				continue;
			}
			
			boolean wasGone = tick - track.lastSeenTick > 1;
			if(wasGone && tick - track.lastSeenTick > MAX_GONE_TICKS)
			{
				// gone long enough that where it went says nothing
				track.reset(pos, tick);
				continue;
			}
			
			track.lastSeenTick = tick;
			if(pos.equals(track.lastPos))
				continue;
			
			long ticks =
				Math.clamp(tick - track.lastMoveTick, 1, MAX_UPDATE_INTERVAL);
			double blocksPerSecond = pos.distanceTo(track.lastPos) / ticks * 20;
			track.record(blocksPerSecond, tick);
			track.lastPos = pos;
			track.lastMoveTick = tick;
		}
		
		// forget entities that have been gone for a while
		if(tick % 100 == 0)
			tracks.values().removeIf(t -> tick - t.lastSeenTick > 100);
	}
	
	/**
	 * Where the server last put the entity. While the client is still gliding
	 * it there, that's the glide's target; otherwise it's simply its position.
	 * Every kind of server move ends up here - relative moves, position syncs
	 * and teleport packets alike (teleports skip the position codec, so
	 * {@code getPositionCodec().getBase()} would miss them).
	 */
	private static Vec3 getLatestServerPos(Entity e)
	{
		InterpolationHandler interpolation = e.getInterpolation();
		if(interpolation != null && interpolation.hasActiveInterpolation())
			return interpolation.position();
		
		return e.position();
	}
	
	/**
	 * The highest speed, in blocks per second, measured for this entity in the
	 * last {@link #MEMORY_TICKS} ticks.
	 */
	public double getRecentTopSpeed(Entity e)
	{
		Track track = tracks.get(e.getUUID());
		if(track == null)
			return 0;
		
		return track.getTopSpeed(tick);
	}
	
	private static final class Track
	{
		private Vec3 lastPos;
		private long lastMoveTick;
		private long lastSeenTick;
		private final ArrayDeque<double[]> speeds = new ArrayDeque<>();
		
		private Track(Vec3 pos, long tick)
		{
			reset(pos, tick);
		}
		
		private void reset(Vec3 pos, long tick)
		{
			lastPos = pos;
			lastMoveTick = tick;
			lastSeenTick = tick;
			speeds.clear();
		}
		
		private void record(double speed, long tick)
		{
			speeds.addLast(new double[]{tick, speed});
			prune(tick);
		}
		
		private double getTopSpeed(long tick)
		{
			prune(tick);
			double top = 0;
			for(double[] s : speeds)
				top = Math.max(top, s[1]);
			return top;
		}
		
		private void prune(long tick)
		{
			Iterator<double[]> itr = speeds.iterator();
			while(itr.hasNext())
				if(tick - (long)itr.next()[0] > MEMORY_TICKS)
					itr.remove();
				else
					break;
		}
	}
}
