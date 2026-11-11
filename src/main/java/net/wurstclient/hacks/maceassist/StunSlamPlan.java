/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.maceassist;

/**
 * The mace half of a stun slam, tick by tick, kept free of Minecraft so it
 * can be tested on its own (StunSlamPlanTest).
 *
 * <p>
 * A stun slam is two ticks (XCOW, "Stun Slamming Is Easy, Actually", 0:46
 * and 4:30; Corzen01's spear-mace course, #14): tick N axe key + click - the
 * axe hit disables the shield - and tick N+1 mace key + click - the smash
 * lands. Both clicks in tick N are only a stun: the second one goes out with
 * the axe too. The axe hit is the click that started it (MaceAssistHack only
 * selects the axe); this plan fires the mace from tick N+1 on, at the first
 * tick a hit could be verified, and gives up when the smash can't happen any
 * more.
 *
 * <p>
 * The slot always ends up back on the one you started with, exactly once:
 * right after the mace hit, or - while the hit is waiting for your aim -
 * straight away, so you aren't left holding the axe.
 */
public final class StunSlamPlan
{
	/**
	 * Ticks after the axe hit that the mace hit may still go out. The shield
	 * stays disabled for seconds; this is about the smash - a fall that hasn't
	 * reached the target in half a second isn't this slam any more.
	 */
	public static final int GIVE_UP_TICKS = 10;
	
	public enum Kind
	{
		/** Nothing to do this tick. */
		NONE,
		/** Select {@link Action#slot()}, attack, swing, select returnSlot. */
		FIRE_MACE,
		/** Select {@link Action#slot()} - the plan is waiting or over. */
		RETURN;
	}
	
	public record Action(Kind kind, int slot, int returnSlot)
	{
		static final Action NONE = new Action(Kind.NONE, -1, -1);
	}
	
	private boolean active;
	private int ticksSinceAxe;
	private int startSlot = -1;
	private int maceSlot = -1;
	private boolean returned;
	
	/**
	 * Call in the tick the axe hit goes out.
	 *
	 * @param startSlot
	 *            the slot to end up on (what you held before the axe).
	 * @param maceSlot
	 *            the mace to slam with, or -1 for a shield break alone (no
	 *            smash possible): the slot just goes back next tick.
	 */
	public void begin(int startSlot, int maceSlot)
	{
		active = true;
		ticksSinceAxe = 0;
		this.startSlot = startSlot;
		this.maceSlot = maceSlot;
		returned = false;
	}
	
	/** The mace's slot, or -1 for a shield break alone. */
	public int maceSlot()
	{
		return maceSlot;
	}
	
	/** A slam is between its axe hit and its mace hit (or giving up). */
	public boolean isActive()
	{
		return active;
	}
	
	/**
	 * You hit with a mace yourself during the slam (mace key + click): no
	 * second mace hit from the plan, only the slot going back.
	 */
	public void userMaced()
	{
		if(active)
			maceSlot = -1;
	}
	
	public void reset()
	{
		active = false;
		ticksSinceAxe = 0;
		startSlot = -1;
		maceSlot = -1;
		returned = false;
	}
	
	/**
	 * Once per tick, from the tick after {@link #begin} on.
	 *
	 * @param canChangeSlot
	 *            a slot change would still reach the server before this
	 *            tick's actions (PacketOrder.canChangeSlotNow()).
	 * @param hitVerifiable
	 *            a hit on the target right now passes HitCheck.
	 * @param targetValid
	 *            the target is alive and in your world.
	 * @param smashPossible
	 *            in the air, not gliding, fallen far enough for a smash.
	 * @param maceInSlot
	 *            the mace's slot still holds a mace.
	 */
	public Action tick(boolean canChangeSlot, boolean hitVerifiable,
		boolean targetValid, boolean smashPossible, boolean maceInSlot)
	{
		if(!active)
			return Action.NONE;
		
		ticksSinceAxe++;
		
		if(!targetValid || !smashPossible || !maceInSlot
			|| ticksSinceAxe > GIVE_UP_TICKS)
		{
			active = false;
			return returned ? Action.NONE
				: new Action(Kind.RETURN, startSlot, -1);
		}
		
		if(canChangeSlot && hitVerifiable)
		{
			active = false;
			return new Action(Kind.FIRE_MACE, maceSlot, startSlot);
		}
		
		// waiting for the aim (or for a tick where the mace would reach the
		// server before the hit) - with what you started with in hand
		if(returned)
			return Action.NONE;
		
		returned = true;
		return new Action(Kind.RETURN, startSlot, -1);
	}
}
