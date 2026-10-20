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
 * W/S, A/D, jump, sprint, sneak, and whether to hit. The aim itself isn't
 * the network's: AimAssist's humanized aim turns the camera.
 *
 * <p>
 * Outputs: 3 scores for W / nothing / S (the highest wins), 3 for A /
 * nothing / D, then one each for jump, sprint, sneak and attack (pressed
 * when above 0).
 */
public final class NeuralAction
{
	public static final int OUTPUTS = 10;
	
	/** 1 = W, -1 = S, 0 = neither. */
	public int forward;
	/** 1 = A (left), -1 = D (right), 0 = neither. */
	public int strafe;
	public boolean jump;
	public boolean sprint;
	public boolean sneak;
	public boolean attack;
	
	public void decode(float[] out)
	{
		forward = 1 - argmax3(out[0], out[1], out[2]);
		strafe = 1 - argmax3(out[3], out[4], out[5]);
		jump = out[6] > 0;
		sprint = out[7] > 0;
		sneak = out[8] > 0;
		attack = out[9] > 0;
	}
	
	public void set(NeuralAction other)
	{
		forward = other.forward;
		strafe = other.strafe;
		jump = other.jump;
		sprint = other.sprint;
		sneak = other.sneak;
		attack = other.attack;
	}
	
	public void clear()
	{
		forward = 0;
		strafe = 0;
		jump = false;
		sprint = false;
		sneak = false;
		attack = false;
	}
	
	private static int argmax3(float a, float b, float c)
	{
		if(a >= b && a >= c)
			return 0;
		return b >= c ? 1 : 2;
	}
}
