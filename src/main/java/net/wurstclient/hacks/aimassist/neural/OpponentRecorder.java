/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.Arrays;

/**
 * Watches an opponent and writes down, tick by tick, what they saw and which
 * keys they must have pressed - training data for a network that plays like
 * them (the online learner's copy of the opponent).
 *
 * <p>
 * Nobody sends us another player's keys, so they're read back from what the
 * server does show: W/S and A/D from their velocity relative to where they
 * face (on the ground a held key keeps you moving at ~0.2 blocks/tick, so
 * the direction gives it away), jumps from leaving the ground upward,
 * sprint and sneak from their entity flags, hits from their swings, a
 * raised shield and eating from the item they're using, webs, water,
 * potions, pearls and wind charges from what appears (see {@link #WEB}). Ticks
 * where a key can't be read - in the air (momentum, not keys), during
 * knockback - are left unknown (-1) and the training skips them.
 *
 * <p>
 * Movement shows a tick or two after the key, so the movement keys go with
 * what they saw two ticks earlier, the other keys with one tick earlier -
 * except hits: a swing comes with the view it was made from (the reach and
 * aim that let it land are that tick's), and only ticks they could have
 * hit count (out of reach or aim a click does nothing in the simulator).
 * Paired with the tick before and counted on every tick, swings were ~1% of
 * the samples, mostly with a view that couldn't hit yet: a copy of Counter
 * learned to hardly ever hit (2 swings a fight against its 10) and lost 89%
 * where the real one lost 66% (2026-10-27).
 * No Minecraft classes: the simulator's test feeds it too.
 */
public final class OpponentRecorder
{
	/** Blocks/tick along a direction that counts as a held key. */
	static final double MOVE_THRESHOLD = 0.06;
	
	public interface Sink
	{
		void add(float[] x, byte[] y);
	}
	
	private final float[][] views = new float[3][NeuralObservation.SIZE];
	/**
	 * Items they used this tick ({@code items}): a cobweb or water placed, a
	 * potion, a pearl toward you or away, a wind charge at their feet or at
	 * you. Unrecorded, a copy used them the way the general net's raw outputs
	 * say (no item rules): a copy of Counter put 24 webs a fight to its 18
	 * and the general net landed half its hits (2026-11-02).
	 */
	public static final int WEB = 1, POT = 2, WATER = 4, PEARL_IN = 8,
		PEARL_OUT = 16, WIND_JUMP = 32, WIND_HIT = 64;
	
	/** The labels of each view in {@link #views}, filled in over 3 ticks. */
	private final byte[][] labels = new byte[3][15];
	private int ticks;
	private boolean wasOnGround, wasSlowed;
	
	/** Starts over (a different target, or a gap in the recording). */
	public void reset()
	{
		ticks = 0;
	}
	
	/**
	 * One tick of the opponent.
	 *
	 * @param theirView
	 *            what they see this tick (their side of the fight)
	 * @param mx
	 *            their movement per tick, x (the server's positions,
	 *            averaged over two ticks - players are sent every second
	 *            tick)
	 * @param my
	 *            their movement per tick, y
	 * @param mz
	 *            their movement per tick, z
	 * @param yaw
	 *            where they face, degrees
	 * @param slowed
	 *            in a cobweb or using an item (their keys don't show)
	 * @param swung
	 *            whether they started a swing this tick
	 * @param shielding
	 *            whether they're holding a shield up
	 * @param eating
	 *            whether they're eating or drinking
	 * @param items
	 *            what they threw or placed this tick ({@link #WEB} ...)
	 */
	public void record(NeuralObservation theirView, double mx, double my,
		double mz, float yaw, boolean onGround, int hurtTime, boolean slowed,
		boolean sprinting, boolean crouching, boolean swung, boolean shielding,
		boolean eating, int items, Sink sink)
	{
		boolean prevGround = wasOnGround;
		wasOnGround = onGround;
		boolean prevSlowed = wasSlowed;
		wasSlowed = slowed;
		// one sample per view, sent once its movement shows (two ticks on):
		// a sample for each kind of label tripled the samples, so the copy
		// saw only a minute of fighting
		theirView.encode(views[ticks % 3]);
		byte[] now = labels[ticks % 3];
		Arrays.fill(now, (byte)-1);
		// hits and thrown/placed items come with the view they were made from
		now[5] = (byte)(theirView.canHit ? swung ? 1 : 0 : -1);
		now[6] = bit(items, WEB);
		now[8] = bit(items, POT);
		now[10] = bit(items, WATER);
		now[11] = bit(items, PEARL_IN);
		now[12] = bit(items, PEARL_OUT);
		now[13] = bit(items, WIND_JUMP);
		now[14] = bit(items, WIND_HIT);
		ticks++;
		if(ticks < 2)
			return;
		
		// the other keys show a tick later
		byte[] oneAgo = labels[(ticks - 2) % 3];
		boolean hurt = hurtTime > 0;
		if(!hurt && prevGround)
			oneAgo[2] = (byte)(!onGround && my > 0.1 ? 1 : onGround ? 0 : -1);
		oneAgo[3] = (byte)(sprinting ? 1 : 0);
		oneAgo[4] = (byte)(crouching ? 1 : 0);
		oneAgo[7] = (byte)(shielding ? 1 : 0);
		oneAgo[9] = (byte)(eating ? 1 : 0);
		if(ticks < 3)
			return;
			
		// W/S and A/D: velocity in their own frame (forward = (-sin, cos),
		// left = (cos, sin), like PvpSim's movement) - not in a web or using
		// an item (a held key barely moves you): read as "no key", 75% of a
		// webbing Counter's "none" labels were W or S held in a web, 10%
		// while eating (2026-11-02)
		byte[] twoAgo = labels[(ticks - 3) % 3];
		if(onGround && prevGround && !hurt && !slowed && !prevSlowed)
		{
			double r = Math.toRadians(yaw);
			double sin = Math.sin(r), cos = Math.cos(r);
			twoAgo[0] = key(-mx * sin + mz * cos);
			twoAgo[1] = key(mx * cos + mz * sin);
		}
		sink.add(views[(ticks - 3) % 3].clone(), twoAgo.clone());
	}
	
	private static byte bit(int items, int bit)
	{
		return (byte)((items & bit) != 0 ? 1 : 0);
	}
	
	/** 0 = positive key (W / A), 1 = none, 2 = negative key (S / D). */
	private static byte key(double v)
	{
		return (byte)(v > MOVE_THRESHOLD ? 0 : v < -MOVE_THRESHOLD ? 2 : 1);
	}
}
