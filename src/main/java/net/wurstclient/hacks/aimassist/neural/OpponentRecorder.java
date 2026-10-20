/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

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
 * sprint and sneak from their entity flags, hits from their swings. Ticks
 * where a key can't be read - in the air (momentum, not keys), during
 * knockback - are left unknown (-1) and the training skips them.
 *
 * <p>
 * Movement shows a tick or two after the key, so the movement keys go with
 * what they saw two ticks earlier, the other keys with one tick earlier.
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
	private int ticks;
	private boolean wasOnGround;
	
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
	 * @param swung
	 *            whether they started a swing this tick
	 */
	public void record(NeuralObservation theirView, double mx, double my,
		double mz, float yaw, boolean onGround, int hurtTime, boolean sprinting,
		boolean crouching, boolean swung, Sink sink)
	{
		boolean prevGround = wasOnGround;
		wasOnGround = onGround;
		float[] now = views[ticks % 3];
		theirView.encode(now);
		ticks++;
		if(ticks < 3)
			return;
		
		float[] oneAgo = views[(ticks - 2) % 3];
		float[] twoAgo = views[(ticks - 3) % 3];
		boolean hurt = hurtTime > 0;
		
		// W/S and A/D: velocity in their own frame (forward = (-sin, cos),
		// left = (cos, sin), like PvpSim's movement)
		if(onGround && prevGround && !hurt)
		{
			double r = Math.toRadians(yaw);
			double sin = Math.sin(r), cos = Math.cos(r);
			double fwd = -mx * sin + mz * cos;
			double left = mx * cos + mz * sin;
			byte[] y = {key(fwd), key(left), -1, -1, -1, -1};
			sink.add(twoAgo.clone(), y);
		}
		
		byte jump = -1;
		if(!hurt && prevGround)
			jump = (byte)(!onGround && my > 0.1 ? 1 : onGround ? 0 : -1);
		byte[] y = {-1, -1, jump, (byte)(sprinting ? 1 : 0),
			(byte)(crouching ? 1 : 0), (byte)(swung ? 1 : 0)};
		sink.add(oneAgo.clone(), y);
	}
	
	/** 0 = positive key (W / A), 1 = none, 2 = negative key (S / D). */
	private static byte key(double v)
	{
		return (byte)(v > MOVE_THRESHOLD ? 0 : v < -MOVE_THRESHOLD ? 2 : 1);
	}
}
