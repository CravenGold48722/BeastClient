/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import java.util.SplittableRandom;

/**
 * Keeps the network's held keys human: a key it presses or lets go of stays
 * that way for a few ticks (a little random each time), the way a finger
 * does - W/S 2-3 ticks, A/D 3-6, sneak 2-3, sprint 2, the shield 3-5. Jumps
 * and attacks are single presses and go through as they are; so do the
 * items, which have their own timing.
 *
 * <p>
 * Used everywhere the network plays (the simulator's training fights and
 * in game), so the weights are trained for what it can actually do.
 */
public final class KeySmoother
{
	private int forward, strafe;
	private boolean sprint, sneak, shield;
	private int holdForward, holdStrafe, holdSprint, holdSneak, holdShield;
	private SplittableRandom random = new SplittableRandom(0);
	
	public void reset(long seed)
	{
		random = new SplittableRandom(seed);
		forward = strafe = 0;
		sprint = sneak = shield = false;
		holdForward = holdStrafe = holdSprint = holdSneak = holdShield = 0;
	}
	
	/** Changes {@code a} to the keys actually held this tick. */
	public void apply(NeuralAction a)
	{
		// each hold counts the ticks still to come after the change
		if(holdForward > 0)
		{
			holdForward--;
			a.forward = forward;
		}else if(a.forward != forward)
		{
			forward = a.forward;
			holdForward = 1 + random.nextInt(2);
		}
		
		if(holdStrafe > 0)
		{
			holdStrafe--;
			a.strafe = strafe;
		}else if(a.strafe != strafe)
		{
			strafe = a.strafe;
			holdStrafe = 2 + random.nextInt(4);
		}
		
		if(holdSprint > 0)
		{
			holdSprint--;
			a.sprint = sprint;
		}else if(a.sprint != sprint)
		{
			sprint = a.sprint;
			holdSprint = 1;
		}
		
		if(holdSneak > 0)
		{
			holdSneak--;
			a.sneak = sneak;
		}else if(a.sneak != sneak)
		{
			sneak = a.sneak;
			holdSneak = 1 + random.nextInt(2);
		}
		
		if(holdShield > 0)
		{
			holdShield--;
			a.shield = shield;
		}else if(a.shield != shield)
		{
			shield = a.shield;
			holdShield = 2 + random.nextInt(3);
		}
	}
}
