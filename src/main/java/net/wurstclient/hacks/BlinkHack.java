/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.FakePlayerEntity;

/**
 * Holds back everything the client sends - movement, but also the answers
 * to the server's pings and keep-alives - and lets it out, in order, when
 * turned off. To the server that's a lag spike and a client catching up
 * afterwards, which anticheats forgive (Grim and Intave time movement by
 * those ping answers).
 *
 * <p>
 * Holding back only the movement, as Blink used to, is what gives a blink
 * away: the client keeps answering pings while it "isn't moving", and the
 * burst afterwards is then ahead of the client's own clock (Grim Timer,
 * Intave protocol checks, setbacks).
 *
 * <p>
 * It holds them at the connection itself, so nothing sent some other way
 * slips through, and it starts and stops right after a tick end: toggled in
 * the middle of a tick (.blink from chat), a tick's movement went out and its
 * tick end was held - something lag never does (Intave counts movement
 * packets between tick ends). Everything held goes out at once on the next
 * tick, like the burst after a real lag spike (Grim and Intave both accept
 * it; sending it from inside the tick end itself was a Grim TickTimer flag).
 */
@DontSaveState
@SearchTags({"LagSwitch", "lag switch"})
public final class BlinkHack extends Hack
	implements UpdateListener, ConnectionPacketOutputListener
{
	private final SliderSetting limit = new SliderSetting("Limit",
		"Automatically restarts Blink once the given number of packets have"
			+ " been held back.\n\n0 = no limit",
		0, 0, 500, 1, ValueDisplay.INTEGER.withLabel(0, "disabled"));
	
	/**
	 * Servers drop a client that hasn't answered a keep-alive in 15
	 * seconds; restart well before that.
	 */
	private static final int MAX_TICKS = 200;
	
	/** Ticks' worth of held packets let out per tick: in practice all. */
	private static final int CATCH_UP = 1000;
	
	// packets can be sent from the network thread too (keep-alive answers),
	// so both queues are only touched while holding this lock
	private final Object lock = new Object();
	private final ArrayDeque<Packet<?>> held = new ArrayDeque<>();
	private final ArrayDeque<Packet<?>> releasing = new ArrayDeque<>();
	private boolean listening;
	
	// Holding starts and stops right after a tick-end packet: toggled in the
	// middle of a tick (.blink from chat), a tick's movement went out and its
	// tick end was held - something lag never does (Intave flagged it).
	private boolean holding;
	private boolean pendingHold;
	private boolean pendingRelease;
	
	private FakePlayerEntity fakePlayer;
	private int ticks;
	
	public BlinkHack()
	{
		super("Blink");
		setCategory(Category.MOVEMENT);
		addSetting(limit);
	}
	
	@Override
	public String getRenderName()
	{
		int size;
		synchronized(lock)
		{
			size = held.size();
		}
		if(limit.getValueI() == 0)
			return getName() + " [" + size + "]";
		return getName() + " [" + size + "/" + limit.getValueI() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		fakePlayer = new FakePlayerEntity();
		ticks = 0;
		
		synchronized(lock)
		{
			pendingHold = true;
		}
		
		// still letting out the last blink: keep listening
		if(!listening)
		{
			EVENTS.add(UpdateListener.class, this);
			EVENTS.add(ConnectionPacketOutputListener.class, this);
			listening = true;
		}
	}
	
	@Override
	protected void onDisable()
	{
		if(fakePlayer != null)
			fakePlayer.despawn();
		
		// let it out after this tick's tick end, then over the next ticks
		synchronized(lock)
		{
			pendingHold = false;
			if(holding)
				pendingRelease = true;
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(isEnabled())
		{
			ticks++;
			int size;
			synchronized(lock)
			{
				size = held.size();
			}
			
			boolean tooMany =
				limit.getValueI() > 0 && size >= limit.getValueI();
			if(tooMany || ticks >= MAX_TICKS)
			{
				setEnabled(false);
				setEnabled(true);
			}
		}
		
		releaseSome();
	}
	
	/**
	 * Sends up to {@link #CATCH_UP} ticks' worth of what's waiting; once
	 * nothing is waiting and Blink is off, stops listening.
	 */
	private void releaseSome()
	{
		if(MC.player == null)
		{
			synchronized(lock)
			{
				releasing.clear();
				held.clear();
				holding = false;
				pendingRelease = false;
			}
			stopListeningIfDone();
			return;
		}
		
		for(int tickEnds = 0; tickEnds < CATCH_UP;)
		{
			Packet<?> packet;
			synchronized(lock)
			{
				packet = releasing.pollFirst();
			}
			if(packet == null)
				break;
			
			sending = true;
			try
			{
				MC.player.connection.getConnection().send(packet);
				
			}finally
			{
				sending = false;
			}
			
			if(packet instanceof ServerboundClientTickEndPacket)
				tickEnds++;
		}
		
		stopListeningIfDone();
	}
	
	/** Our own sends from {@link #releaseSome()} go straight out. */
	private boolean sending;
	
	private void stopListeningIfDone()
	{
		boolean done;
		synchronized(lock)
		{
			done = releasing.isEmpty() && !holding && !pendingHold;
		}
		if(!done || isEnabled() || !listening)
			return;
		
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(ConnectionPacketOutputListener.class, this);
		listening = false;
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		if(sending && MC.isSameThread())
			return;
		
		Packet<?> packet = event.getPacket();
		boolean tickEnd = packet instanceof ServerboundClientTickEndPacket;
		synchronized(lock)
		{
			if(holding)
			{
				event.cancel();
				held.addLast(packet);
				
				// the tick is complete: from here on, let it out
				if(tickEnd && pendingRelease)
				{
					releasing.addAll(held);
					held.clear();
					holding = false;
					pendingRelease = false;
					// restarted (Limit): hold again right away
					if(pendingHold)
					{
						holding = true;
						pendingHold = false;
					}
				}
			}
		}
		
		// let out from the next tick's onUpdate (sending it right here,
		// inside the tick end, was a Grim TickTimer flag)
		if(event.isCancelled() || packet == null)
			return;
		
		synchronized(lock)
		{
			
			// still letting out the last blink: queue behind it, so nothing
			// overtakes it
			if(!releasing.isEmpty())
			{
				event.cancel();
				releasing.addLast(packet);
			}
			
			// this tick went out whole: hold from the next one
			if(tickEnd && pendingHold)
			{
				holding = true;
				pendingHold = false;
			}
		}
	}
	
	public void cancel()
	{
		// the server never sees the movement, but the pings still need
		// answers: everything else goes out, the movement doesn't
		synchronized(lock)
		{
			held.removeIf(p -> p instanceof ServerboundMovePlayerPacket);
		}
		fakePlayer.resetPlayerPosition();
		setEnabled(false);
	}
}
