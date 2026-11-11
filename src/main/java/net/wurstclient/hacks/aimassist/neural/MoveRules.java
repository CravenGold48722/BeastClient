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
 * The two movement habits the network wouldn't unlearn (user, 2026-11-10:
 * "fix the walking backwards and the one sided strafing"):
 * <ul>
 * <li>walking away: S on 38-51% of the ticks, seconds of backing off 4-8
 * blocks out while the opponent stood still - in reach 43% of the time, half
 * the hits it could have had (Blingcapy, both rounds lost by a few HP). Out
 * of their reach S now needs a reason: they're coming at you (spacing
 * against a charge) or you're low with gapples to eat. Otherwise W - walk
 * in. Inside their reach S stays the network's: the step back after a hit
 * while the sword recharges is worth 7 points against the scripted fighters
 * (limiting it there cost 81% vs 88%; this rule costs nothing: 88.3% both).
 * </li>
 * <li>one side: A/D one way for a whole fight (D 100% one fight, A 100% the
 * next - AimAssist picks the mirror image per fight, the net circles one way
 * in its own frame; the strafe bonus for runs under 25 ticks didn't change
 * it). A run in one direction now ends after 15-40 ticks, then the other
 * side - in PvpSim slightly better than one side (90.6% vs 88.3%).</li>
 * </ul>
 * Laid over the keys the same way in game (AimAssist) and in PvpSim (every
 * network fighter, after {@link KeySmoother} and {@link ItemRules}), so
 * training learns to play with them.
 */
public final class MoveRules
{
	/** Beyond this (blocks to their hitbox), S needs a reason. */
	static final double BACK_RANGE = 3.5;
	/** They "come at you" faster than this (blocks/tick toward you). */
	static final double CLOSING = 0.1;
	/** Ticks one strafe direction is held at most (random in range). */
	static final int RUN_MIN = 15, RUN_MAX = 40;
	/** Ticks without A/D after which the next press starts a fresh run. */
	static final int STRAFE_IDLE = 10;
	
	private SplittableRandom random = new SplittableRandom(0);
	/** +1 or -1: the side the network's strafe is pressed on. */
	private int side;
	private int lastStrafe, strafeRun, runLimit, strafeIdle;
	
	public MoveRules()
	{
		reset(0);
	}
	
	public void reset(long seed)
	{
		random = new SplittableRandom(seed);
		side = 1;
		lastStrafe = strafeRun = strafeIdle = 0;
		runLimit = nextRun();
	}
	
	/** Changes {@code a} to the movement keys actually pressed. */
	public void apply(NeuralObservation o, NeuralAction a)
	{
		if(a.forward < 0 && !mayBackOff(o))
			a.forward = 1;
		applyStrafe(a);
	}
	
	private static boolean mayBackOff(NeuralObservation o)
	{
		return o.dist <= BACK_RANGE || o.opAlong > CLOSING
			|| o.gapplesLeft > 0 && (o.myHealth < 0.5 || o.myEating > 0);
	}
	
	private void applyStrafe(NeuralAction a)
	{
		if(a.strafe == 0)
		{
			if(++strafeIdle > STRAFE_IDLE)
				lastStrafe = strafeRun = 0;
			return;
		}
		strafeIdle = 0;
		
		int s = a.strafe * side;
		if(s != lastStrafe)
		{
			strafeRun = 1;
			runLimit = nextRun();
		}else if(++strafeRun > runLimit)
		{
			// held one way long enough: the other side, while the network
			// keeps its own idea of when to strafe
			side = -side;
			s = -s;
			strafeRun = 1;
			runLimit = nextRun();
		}
		a.strafe = s;
		lastStrafe = s;
	}
	
	private int nextRun()
	{
		return RUN_MIN + random.nextInt(RUN_MAX - RUN_MIN + 1);
	}
}
