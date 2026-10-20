/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * What the neural network sees of a sword fight, one tick at a time. Filled
 * in by AimAssist from the game and by the trainer's simulator from its own
 * state - the same fields, the same meaning, encoded by the same code - so
 * the net plays in the game what it learned in the simulator.
 *
 * <p>
 * Everything is relative to the line from you to the opponent ("along" =
 * toward them, "side" = to your left of that line), so the net doesn't have
 * to learn the same fight in every compass direction. Inputs are scaled to
 * about -1..1: unscaled inputs of very different sizes are the most common
 * reason a small network trains badly.
 */
public final class NeuralObservation
{
	/** Bumped whenever the inputs change; old weights then won't load. */
	public static final int VERSION = 1;
	
	public static final int SIZE = 26;
	
	/** Your eyes to the nearest point of their hitbox (your reach). */
	public double dist;
	/** Their eyes to the nearest point of your hitbox (their reach). */
	public double theirReach;
	/** Their feet minus your feet, blocks. */
	public double dy;
	
	/** Your velocity: toward them, to your left, up (blocks/tick). */
	public double myAlong, mySide, myVy;
	/** Their velocity: toward you, to your left, up (blocks/tick). */
	public double opAlong, opSide, opVy;
	
	public boolean myGround, opGround;
	
	/** Ticks until a full (90%) hit, yours and theirs (estimated). */
	public int myChargeTicks, opChargeTicks;
	
	/** hurtTime, 10 right after a hit, counting down. */
	public int myHurt, opHurt;
	
	/** The sprint state the server has. */
	public boolean mySprint, opSprint;
	
	/** Health as a fraction of max health. */
	public double myHealth, opHealth;
	
	/** A hit now would be a crit (falling, not sprinting). */
	public boolean critReady;
	
	/** A hit now would land (reach, crosshair, line of sight). */
	public boolean canHit;
	
	/** Degrees between where you look and their hitbox center. */
	public double aimError;
	
	/** How squarely they look at you: 1 = straight at you, -1 = away. */
	public double opFacing;
	
	/** Ticks since your last hit on them, and theirs on you. */
	public int sinceMyHit, sinceTheirHit;
	
	public boolean sneaking;
	
	public void encode(float[] out)
	{
		int i = 0;
		out[i++] = clamp(dist / 4, 0, 2);
		out[i++] = clamp(theirReach / 4, 0, 2);
		out[i++] = clamp(dy / 2, -1.5, 1.5);
		out[i++] = clamp(myAlong * 4, -2, 2);
		out[i++] = clamp(mySide * 4, -2, 2);
		out[i++] = clamp(myVy * 2, -2, 2);
		out[i++] = clamp(opAlong * 4, -2, 2);
		out[i++] = clamp(opSide * 4, -2, 2);
		out[i++] = clamp(opVy * 2, -2, 2);
		out[i++] = myGround ? 1 : -1;
		out[i++] = opGround ? 1 : -1;
		out[i++] = clamp(myChargeTicks / 12.0, 0, 1);
		out[i++] = clamp(opChargeTicks / 12.0, 0, 1);
		out[i++] = clamp(myHurt / 10.0, 0, 1);
		out[i++] = clamp(opHurt / 10.0, 0, 1);
		out[i++] = mySprint ? 1 : -1;
		out[i++] = opSprint ? 1 : -1;
		out[i++] = clamp(myHealth, 0, 1);
		out[i++] = clamp(opHealth, 0, 1);
		out[i++] = critReady ? 1 : -1;
		out[i++] = canHit ? 1 : -1;
		out[i++] = clamp(aimError / 20, 0, 2);
		out[i++] = clamp(opFacing, -1, 1);
		out[i++] = clamp(sinceMyHit / 20.0, 0, 1);
		out[i++] = clamp(sinceTheirHit / 20.0, 0, 1);
		out[i++] = sneaking ? 1 : -1;
	}
	
	private static float clamp(double v, double min, double max)
	{
		return (float)(v < min ? min : v > max ? max : v);
	}
	
	/**
	 * Their ticks until a 90% hit from their last swing (players with a
	 * sword: 12.5 ticks a full charge) - what AimAssist estimates in game.
	 */
	public static int opChargeFromSwing(int ticksSinceSwing, double cooldown)
	{
		return Math.max(0,
			(int)Math.ceil(0.9 * cooldown - 0.5 - ticksSinceSwing));
	}
}
