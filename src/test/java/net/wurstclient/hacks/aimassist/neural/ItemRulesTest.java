/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class ItemRulesTest
{
	private static NeuralObservation healthy()
	{
		NeuralObservation o = new NeuralObservation();
		o.myHealth = 1;
		o.dist = 3;
		o.waterLeft = 2;
		o.pearlsLeft = 16;
		o.gapplesLeft = 64;
		o.opGround = true;
		return o;
	}
	
	@Test
	public void waterWhenWebbedEvenOnTheGround()
	{
		NeuralObservation o = healthy();
		o.myInWeb = true;
		o.myGround = true;
		NeuralAction act = new NeuralAction();
		ItemRules.apply(o, act);
		assertTrue(act.water);
	}
	
	@Test
	public void pearlsOutWhenLowAndCloseThenEatsWhenFar()
	{
		NeuralObservation o = healthy();
		o.myHealth = 0.25;
		NeuralAction act = new NeuralAction();
		ItemRules.apply(o, act);
		assertTrue(act.pearlOut);
		assertFalse(act.eat);
		
		o.dist = 12;
		o.pearlCooldown = 20;
		act = new NeuralAction();
		ItemRules.apply(o, act);
		assertFalse(act.pearlOut);
		assertTrue(act.eat);
	}
	
	@Test
	public void pearlFirstEvenIfTheNetWantsToEat()
	{
		NeuralObservation o = healthy();
		o.myHealth = 0.25;
		NeuralAction act = new NeuralAction();
		act.eat = act.pot = true;
		ItemRules.apply(o, act);
		assertTrue(act.pearlOut);
		assertFalse(act.eat);
		assertFalse(act.pot);
		
		// a bite just started (eating from afar, they closed in): away
		o.myEating = 0.2;
		act = new NeuralAction();
		ItemRules.apply(o, act);
		assertTrue(act.pearlOut);
		assertFalse(act.eat);
		
		// a bite half done: finished first
		o.myEating = 0.5;
		act = new NeuralAction();
		ItemRules.apply(o, act);
		assertFalse(act.pearlOut);
		assertTrue(act.eat);
	}
	
	@Test
	public void eatsWhileThePearlFliesAndWhileStillLow()
	{
		NeuralObservation o = healthy();
		o.myHealth = 0.25;
		o.pearlFlying = true;
		NeuralAction act = new NeuralAction();
		ItemRules.apply(o, act);
		assertFalse(act.pearlOut);
		assertTrue(act.eat);
		
		// landed far away, one apple down (absorption) but still low
		o.pearlFlying = false;
		o.pearlCooldown = 10;
		o.dist = 15;
		o.absorption = 0.2;
		o.myHealth = 0.4;
		act = new NeuralAction();
		ItemRules.apply(o, act);
		assertTrue(act.eat);
	}
	
	@Test
	public void eatsWhenTooHungryToSprint()
	{
		NeuralObservation o = healthy();
		o.myFood = 6;
		o.dist = 8;
		NeuralAction act = new NeuralAction();
		ItemRules.apply(o, act);
		assertTrue(act.eat);
		
		o.dist = 3;
		act = new NeuralAction();
		ItemRules.apply(o, act);
		assertFalse(act.eat);
	}
	
	@Test
	public void noWebsIntoTheOpen()
	{
		NeuralObservation o = healthy();
		o.dist = 8;
		NeuralAction act = new NeuralAction();
		act.web = true;
		ItemRules.apply(o, act);
		assertFalse(act.web);
		
		o.dist = 3;
		act.web = true;
		ItemRules.apply(o, act);
		assertTrue(act.web);
	}
}
