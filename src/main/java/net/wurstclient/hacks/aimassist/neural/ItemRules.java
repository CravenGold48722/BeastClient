/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

/**
 * The item moves that can't wait for the network to learn them (user,
 * 2026-10-26: a real fight lost with full health on the other side - stuck
 * mining webs, eating at one heart, webs thrown all over the field). Laid
 * over the network's keys the same way in game (AimAssist) and in PvpSim
 * (every network fighter), so training learns the rest around them.
 */
public final class ItemRules
{
	private ItemRules()
	{}
	
	/** No strafing further away than this (blocks to their hitbox). */
	static final double STRAFE_RANGE = 5;
	
	public static void apply(NeuralObservation o, NeuralAction act)
	{
		// no A/D out of fighting range: nothing to dodge there, and it only
		// slows the way in (user, 2026-10-31: it circled one way the whole
		// fight, 89-100% of the ticks beyond 6 blocks too)
		if(o.dist > STRAFE_RANGE)
			act.strafe = 0;
			
		// stuck in a web: water washes it away at once; the sword took up
		// to 1.5 s in that fight, with their hits coming in
		if(o.myInWeb && o.waterLeft > 0)
			act.water = true;
		
		boolean pearl =
			o.pearlsLeft > 0 && o.pearlCooldown == 0 && !o.pearlFlying;
		// low and they're on you: away first, nothing else this tick (eating
		// came first in the item order, so this never happened - in game it
		// ate at one heart next to them); a bite half done or more is finished,
		// one just started (eating from afar when they closed in) is dropped
		if(pearl && o.myHealth < 0.35 && o.dist < 5 && o.gapplesLeft > 0
			&& !o.opInWeb && o.myEating < 0.5)
		{
			act.pearlOut = true;
			act.eat = act.pot = act.web = act.shield = false;
			return;
		}
		// eat: finish what was started; low, with a moment for it (away, or
		// already flying away on the pearl) - one after another while still
		// low; too hungry to sprint (6 food or less: no sprint hits, no
		// fast regeneration) when there's a moment
		if(o.gapplesLeft > 0 && (o.myEating > 0
			|| o.myHealth < 0.5 && (o.dist > 4.5 || o.pearlFlying)
			|| o.myFood <= 6 && o.dist > 4.5))
			act.eat = true;
			
		// a web only where it catches them: at the feet of one standing
		// close, not in the open (they walked around it, and it caught us)
		if(act.web && (o.dist > 4.5 || !o.opGround || o.opInWeb))
			act.web = false;
	}
}
