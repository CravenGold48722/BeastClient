/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * What the network decides each tick - the keys a player would press:
 * W/S, A/D, jump, sprint, sneak, whether to hit, and the items of the kit:
 * a cobweb at their feet, the shield, a buff potion (Strength II, else
 * Speed II) at your own feet, a golden apple, a water bucket out of a web,
 * an ender pearl toward them or away, and a wind charge at your feet (a
 * launch) or at them. The aim itself isn't the network's: AimAssist's
 * humanized aim turns the camera (onto the web's block, the ground or the
 * pearl's arc).
 *
 * <p>
 * Outputs: 3 scores for W / nothing / S (the highest wins), 3 for A /
 * nothing / D, then one each for jump, sprint, sneak, attack, web, shield,
 * potion, golden apple, water, pearl in, pearl out, wind jump and wind hit
 * (pressed when above 0).
 */
public final class NeuralAction
{
	/**
	 * Memory: values the network writes each tick and reads back as inputs
	 * the next (NeuralObservation.memory) - what it can carry from one tick
	 * to the next, like a plan in progress. -1..1 (tanh).
	 */
	public static final int MEMORY = 4;
	public static final int OUTPUTS = 19 + MEMORY;
	public final float[] memory = new float[MEMORY];
	
	/** 1 = W, -1 = S, 0 = neither. */
	public int forward;
	/** 1 = A (left), -1 = D (right), 0 = neither. */
	public int strafe;
	public boolean jump;
	public boolean sprint;
	public boolean sneak;
	public boolean attack;
	/** Put a cobweb at their feet. */
	public boolean web;
	/** Hold the shield up. */
	public boolean shield;
	/** Throw a buff splash potion at your feet (Strength, else Speed). */
	public boolean pot;
	/** Eat (keep eating) a golden apple. */
	public boolean eat;
	/** Water bucket on the web you're stuck in. */
	public boolean water;
	/** Ender pearl to just short of them, or far away from them. */
	public boolean pearlIn, pearlOut;
	/** Wind charge at your own feet (a launch), or at them. */
	public boolean windJump, windHit;
	
	public void decode(float[] out)
	{
		forward = 1 - argmax3(out[0], out[1], out[2]);
		strafe = 1 - argmax3(out[3], out[4], out[5]);
		jump = out[6] > 0;
		sprint = out[7] > 0;
		sneak = out[8] > 0;
		attack = out[9] > 0;
		web = out[10] > 0;
		shield = out[11] > 0;
		pot = out[12] > 0;
		eat = out[13] > 0;
		water = out[14] > 0;
		pearlIn = out[15] > 0;
		pearlOut = out[16] > 0;
		windJump = out[17] > 0;
		windHit = out[18] > 0;
		for(int i = 0; i < MEMORY; i++)
			memory[i] = NeuralNet.tanh(out[19 + i]);
	}
	
	public void set(NeuralAction other)
	{
		forward = other.forward;
		strafe = other.strafe;
		jump = other.jump;
		sprint = other.sprint;
		sneak = other.sneak;
		attack = other.attack;
		web = other.web;
		shield = other.shield;
		pot = other.pot;
		eat = other.eat;
		water = other.water;
		pearlIn = other.pearlIn;
		pearlOut = other.pearlOut;
		windJump = other.windJump;
		windHit = other.windHit;
		System.arraycopy(other.memory, 0, memory, 0, MEMORY);
	}
	
	public void clear()
	{
		forward = 0;
		strafe = 0;
		jump = false;
		sprint = false;
		sneak = false;
		attack = false;
		web = false;
		shield = false;
		pot = false;
		eat = false;
		water = false;
		pearlIn = false;
		pearlOut = false;
		windJump = false;
		windHit = false;
		java.util.Arrays.fill(memory, 0);
	}
	
	private static int argmax3(float a, float b, float c)
	{
		if(a >= b && a >= c)
			return 0;
		return b >= c ? 1 : 2;
	}
}
