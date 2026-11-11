/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.SplittableRandom;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import net.wurstclient.hacks.aimassist.neural.PvpSim.Policy;
import net.wurstclient.hacks.aimassist.neural.PvpSim.SimFighter;

/** The simulator against known vanilla numbers. */
public class PvpSimTest
{
	private static Policy scripted(
		BiConsumer<NeuralObservation, NeuralAction> logic)
	{
		return new Policy()
		{
			@Override
			public void reset(SplittableRandom random)
			{}
			
			@Override
			public void act(SimFighter me, NeuralObservation o,
				NeuralAction out)
			{
				out.clear();
				logic.accept(o, out);
			}
		};
	}
	
	private static final Policy IDLE = scripted((o, a) -> {});
	
	private static PvpSim duel(Policy a, Policy b, double dist)
	{
		PvpSim sim = new PvpSim(1, a, b, 600);
		// bare vanilla: no armor, a plain sword, no axe
		sim.setGear(new PvpSim.Gear(0, 0, 0, PvpSim.SWORD_DAMAGE, 0, 0, 0),
			new PvpSim.Gear(0, 0, 0, PvpSim.SWORD_DAMAGE, 0, 0, 0));
		sim.b.x = dist + 0.6;
		sim.b.z = 0;
		for(SimFighter f : new SimFighter[]{sim.a, sim.b})
		{
			f.viewDelay = 0;
			f.aimNoise = 0;
			f.aimRate = 60;
			f.attackTicker = 20;
			f.hasAxe = false;
			f.x = f == sim.a ? 0 : dist + 0.6;
			f.z = 0;
		}
		sim.a.yaw = PvpSim.needYaw(sim.a, sim.b.x, sim.b.z);
		sim.b.yaw = PvpSim.needYaw(sim.b, sim.a.x, sim.a.z);
		for(sim.tick = -3; sim.tick <= 0; sim.tick++)
		{
			sim.a.record();
			sim.b.record();
		}
		sim.tick = 0;
		return sim;
	}
	
	/**
	 * The trainer's varied kits: every item, the shield and the totem are
	 * sometimes there and sometimes not - and without kitVariety (tests,
	 * benchmarks) always the full kit.
	 */
	@Test
	public void kitsVaryOnlyWhenAskedTo()
	{
		int fights = 200;
		int noWebs = 0, noWind = 0, noPots = 0, noShield = 0, totems = 0,
			plain = 0, knockbackI = 0;
		PvpSim.kitVariety = 1;
		try
		{
			for(int i = 0; i < fights; i++)
			{
				PvpSim sim = new PvpSim(i, IDLE, IDLE, 10);
				SimFighter f = sim.a;
				// never Knockback II (user, 2026-10-30), I now and then
				for(SimFighter g : new SimFighter[]{sim.a, sim.b})
					assertTrue(g.knockbackBonus == 0
						|| g.knockbackBonus == PvpSim.KNOCKBACK_I);
				knockbackI += f.knockbackBonus > 0 ? 1 : 0;
				if(sim.plain)
				{
					plain++;
					// plain diamond: no enchantments, a sword, nothing else
					assertEquals(0, f.knockbackBonus);
					assertEquals(0, f.sharpness);
					assertEquals(0, f.webs + f.pots + f.gapples + f.winds);
					assertFalse(f.hasShield || f.hasAxe);
				}
				noWebs += f.webs == 0 ? 1 : 0;
				noWind += f.winds == 0 ? 1 : 0;
				noPots += f.pots == 0 ? 1 : 0;
				noShield += f.hasShield ? 0 : 1;
				totems += f.totem ? 1 : 0;
			}
		}finally
		{
			PvpSim.kitVariety = 0;
		}
		assertTrue(knockbackI > 0 && knockbackI < fights / 5,
			"Knockback I " + knockbackI);
		for(int n : new int[]{noWebs, noWind, noPots, noShield, totems, plain})
			assertTrue(n > fights / 10 && n < fights * 9 / 10, noWebs + " "
				+ noWind + " " + noPots + " " + noShield + " " + totems);
		
		PvpSim fullSim = new PvpSim(1, IDLE, IDLE, 10);
		assertFalse(fullSim.plain);
		// nobody fights with Knockback II: none by default
		assertEquals(0, fullSim.a.knockbackBonus);
		SimFighter full = fullSim.a;
		assertEquals(PvpSim.KIT_WEBS, full.webs);
		assertEquals(PvpSim.KIT_WIND, full.winds);
		assertTrue(full.hasShield);
		assertFalse(full.totem);
	}
	
	@Test
	public void jumpPeaksAt1_25()
	{
		PvpSim sim = duel(scripted((o, a) -> a.jump = o.myGround), IDLE, 5);
		double peak = 0;
		for(int i = 0; i < 12; i++)
		{
			sim.step();
			peak = Math.max(peak, sim.a.y);
		}
		// vanilla: 1.2522
		assertEquals(1.2522, peak, 0.001);
	}
	
	@Test
	public void sprintHitKnocksBackFurther()
	{
		double plain = knockbackDistance(false);
		double sprint = knockbackDistance(true);
		assertTrue(sprint > plain + 1, plain + " vs " + sprint);
	}
	
	private double knockbackDistance(boolean sprint)
	{
		boolean[] hit = {false};
		PvpSim sim = duel(scripted((o, a) -> {
			if(hit[0])
				return;
			a.forward = sprint || o.dist > 2.9 ? 1 : 0;
			a.sprint = sprint;
			a.attack =
				o.canHit && o.myChargeTicks == 0 && (o.mySprint || !sprint);
			if(a.attack)
				hit[0] = true;
		}), IDLE, 4);
		sim.a.onlyGoodHits = sprint;
		double start = 0;
		for(int i = 0; i < 60; i++)
		{
			boolean before = hit[0];
			sim.step();
			if(!before && hit[0])
				start = sim.b.x;
		}
		assertTrue(hit[0]);
		return sim.b.x - start;
	}
	
	@Test
	public void fallingHitIsACrit()
	{
		PvpSim sim = duel(scripted((o, a) -> {
			a.jump = o.myGround && o.sinceMyHit > 100;
			a.attack = o.canHit && o.myChargeTicks == 0 && o.critReady;
		}), IDLE, 2.5);
		sim.a.onlyGoodHits = true;
		for(int i = 0; i < 20 && sim.a.hits == 0; i++)
			sim.step();
		assertEquals(1, sim.a.crits);
		assertEquals(20 - 10.5, sim.b.health, 1e-4);
	}
	
	@Test
	public void hitImmunityBlocksTheSecondHit()
	{
		PvpSim sim = duel(
			scripted((o, a) -> a.attack = o.canHit && o.myChargeTicks <= 8),
			IDLE, 2.5);
		for(int i = 0; i < 9; i++)
			sim.step();
		// first hit full, the next ones within 10 ticks only for the
		// difference (weaker ones nothing)
		assertEquals(1, sim.a.hits);
		assertEquals(13, sim.b.health, 1e-4);
	}
	
	@Test
	public void raisedShieldBlocksAHitFromTheFront()
	{
		int[] t = {0};
		PvpSim sim = duel(scripted((o, a) -> a.attack = ++t[0] > 8 && o.canHit),
			scripted((o, a) -> a.shield = true), 2.5);
		sim.b.hasShield = true;
		for(int i = 0; i < 10; i++)
			sim.step();
		assertTrue(sim.b.blocked >= 1, "blocked " + sim.b.blocked);
		assertEquals(0, sim.a.hits);
		assertEquals(20, sim.b.health, 1e-4);
	}
	
	@Test
	public void saturationHealsFastAtFullFood()
	{
		PvpSim sim = duel(IDLE, IDLE, 6);
		sim.a.health = 10;
		sim.a.food = 20;
		sim.a.saturation = 20;
		for(int i = 0; i < 9; i++)
			sim.step();
		assertEquals(10, sim.a.health, 1e-4);
		sim.step();
		// FoodData.tick: min(saturation, 6) / 6 every 10 ticks
		assertEquals(11, sim.a.health, 1e-4);
		for(int i = 0; i < 10; i++)
			sim.step();
		assertEquals(12, sim.a.health, 1e-4);
		// 6 exhaustion a heal: a saturation point every 4
		assertTrue(sim.a.saturation < 20);
	}
	
	@Test
	public void slowRegenerationWithoutSaturation()
	{
		PvpSim sim = duel(IDLE, IDLE, 6);
		sim.a.health = 10;
		sim.a.food = 18;
		sim.a.saturation = 0;
		for(int i = 0; i < 79; i++)
			sim.step();
		assertEquals(10, sim.a.health, 1e-4);
		sim.step();
		assertEquals(11, sim.a.health, 1e-4);
		// 17 food: none at all
		sim.a.food = 17;
		for(int i = 0; i < 100; i++)
			sim.step();
		assertEquals(11, sim.a.health, 1e-4);
	}
	
	@Test
	public void noSprintAtSixFood()
	{
		Policy run = scripted((o, a) -> {
			a.forward = 1;
			a.sprint = true;
		});
		PvpSim sim = duel(run, IDLE, 20);
		sim.a.food = 6;
		for(int i = 0; i < 5; i++)
			sim.step();
		assertFalse(sim.a.sprinting);
		sim.a.food = 7;
		sim.step();
		assertTrue(sim.a.sprinting);
		// and sprinting costs: 0.1 a block on the ground
		float before = sim.a.exhaustion;
		for(int i = 0; i < 10; i++)
			sim.step();
		assertTrue(sim.a.exhaustion > before + 0.1);
	}
	
	@Test
	public void goldenAppleFeeds()
	{
		PvpSim sim = duel(scripted((o, a) -> a.eat = true), IDLE, 6);
		sim.a.gapples = 1;
		sim.a.food = 10;
		sim.a.saturation = 0;
		for(int i = 0; i < 32; i++)
			sim.step();
		assertEquals(1, sim.a.gapplesEaten);
		assertEquals(14, sim.a.food);
		assertEquals(9.6, sim.a.saturation, 1e-4);
	}
	
	@Test
	public void goldenAppleGivesAbsorptionAfter32Ticks()
	{
		PvpSim sim = duel(scripted((o, a) -> a.eat = true), IDLE, 6);
		sim.a.gapples = 1;
		for(int i = 0; i < 31; i++)
			sim.step();
		assertEquals(0, sim.a.gapplesEaten);
		sim.step();
		assertEquals(1, sim.a.gapplesEaten);
		assertEquals(4, sim.a.absorption, 1e-4);
		// absorption takes damage first
		assertEquals(1, PvpSim.absorb(sim.a, 5), 1e-4);
		assertEquals(0, sim.a.absorption, 1e-4);
	}
	
	@Test
	public void fireAspectBurnsUnlessFireResistance()
	{
		assertTrue(burnt(false) - burnt(true) >= 3);
	}
	
	/** How much one Fire Aspect II hit took in 120 ticks. */
	private static float burnt(boolean fireRes)
	{
		int[] hits = {0};
		PvpSim sim = duel(
			scripted((o, a) -> a.attack = hits[0] == 0 && o.canHit), IDLE, 2.5);
		sim.a.fireAspectTicks = PvpSim.FIRE_ASPECT_II;
		if(fireRes)
			sim.b.effectTicks[PvpSim.FIRE_RES] = 9600;
		for(int i = 0; i < 120; i++)
		{
			sim.step();
			hits[0] = sim.a.hits;
		}
		assertEquals(1, sim.a.hits);
		return 20 - sim.b.health;
	}
	
	@Test
	public void axeDisablesARaisedShield()
	{
		// from once the shield is up (a sword hit first would knock them out
		// of reach)
		int[] t = {0};
		PvpSim sim = duel(scripted((o, a) -> a.attack = ++t[0] > 8 && o.canHit),
			scripted((o, a) -> a.shield = true), 2.5);
		sim.a.hasAxe = true;
		int disabled = 0;
		for(int i = 0; i < 40; i++)
		{
			sim.step();
			disabled = Math.max(disabled, sim.b.shieldCooldown);
		}
		assertTrue(disabled > 60, "shield cooldown " + disabled);
		assertTrue(sim.a.hits >= 1 && sim.b.health < 20,
			sim.a.hits + " hits, health " + sim.b.health);
	}
	
	@Test
	public void pearlLandsWhereItWasThrown()
	{
		double drop = -1.52;
		for(double d : new double[]{4, 8, 12, 20})
			assertEquals(d, PvpSim.pearlRange(PvpSim.pearlPitch(d, drop), drop),
				0.6);
		
		int[] thrown = {0};
		PvpSim sim =
			duel(scripted((o, a) -> a.pearlOut = thrown[0]++ == 0), IDLE, 6);
		double x0 = sim.a.x, z0 = sim.a.z;
		// 17 food: no natural regeneration to heal the landing
		sim.a.food = 17;
		for(int i = 0; i < 40; i++)
			sim.step();
		assertEquals(PvpSim.KIT_PEARLS - 1, sim.a.pearls);
		// planned: far from them (the arena's wall stops it)
		double moved = Math.hypot(sim.a.x - x0, sim.a.z - z0);
		assertTrue(moved > 8, "moved " + moved);
		assertTrue(Math.hypot(sim.a.x - sim.b.x, sim.a.z - sim.b.z) > 12,
			"away from them");
		// 5 damage, past the armor (none here) and Protection (none)
		assertEquals(15, sim.a.health, 1e-4);
	}
	
	@Test
	public void bruteTakesLessAndHasTwoTotems()
	{
		PvpSim sim = duel(IDLE, IDLE, 6);
		sim.makeBrute(sim.b);
		assertEquals(0, sim.b.pearls);
		// Resistance III: x 0.4 (no armor here)
		sim.hurt(sim.b, sim.a, 10);
		assertEquals(16, sim.b.health, 1e-4);
		// a lethal hit: the totem, at 1 health; AutoTotem puts the second
		// one in and the brute's effects come back
		sim.b.invulnerableTime = 0;
		sim.hurt(sim.b, sim.a, 100);
		assertEquals(1, sim.b.health, 1e-4);
		assertTrue(sim.b.totem);
		assertEquals(8, sim.b.absorption, 1e-4);
		assertEquals(3, sim.b.resistance);
		assertTrue(sim.b.effectTicks[PvpSim.STRENGTH] > 0);
		// the second totem
		sim.b.invulnerableTime = 0;
		sim.hurt(sim.b, sim.a, 1000);
		assertEquals(1, sim.b.health, 1e-4);
		assertFalse(sim.b.totem);
		// the next one kills
		sim.b.invulnerableTime = 0;
		sim.b.absorption = 0;
		sim.hurt(sim.b, sim.a, 1000);
		assertTrue(sim.b.health <= 0);
	}
	
	@Test
	public void bruteWaitsForOneWhoPearledAway()
	{
		PvpSim sim = duel(IDLE, IDLE, 6);
		sim.makeBrute(sim.b);
		sim.b.waitFor = sim.a;
		sim.a.x = sim.b.x + 30;
		sim.b.action.forward = 1;
		sim.b.action.attack = true;
		sim.holdBack(sim.b);
		assertEquals(0, sim.b.action.forward);
		assertFalse(sim.b.action.attack);
		// within 20 blocks: it comes
		sim.a.x = sim.b.x + 15;
		sim.b.action.forward = 1;
		sim.holdBack(sim.b);
		assertEquals(1, sim.b.action.forward);
		assertNull(sim.b.waitFor);
	}
	
	@Test
	public void wallsStopYouAndPitsHurt()
	{
		// walking +x into a 2-high wall at x = 3
		Terrain t = Terrain.wallAt(3, 2);
		PvpSim sim = duel(scripted((o, a) -> {
			a.forward = 1;
			a.sprint = true;
		}), IDLE, 8);
		// they're behind the wall, so it walks into it
		sim.b.x = 12;
		sim.useTerrain(t);
		for(int i = 0; i < 40; i++)
			sim.step();
		assertTrue(sim.a.x < 3 - 0.29 && sim.a.x > 2,
			"at the wall: " + sim.a.x);
		
		// a 6-block fall: ceil(6 - 3) = 3 damage (no armor here)
		PvpSim fall = duel(IDLE, IDLE, 8);
		fall.useTerrain(Terrain.flat());
		fall.a.y = 6;
		fall.a.onGround = false;
		fall.a.food = 17;
		for(int i = 0; i < 40; i++)
			fall.step();
		assertEquals(17, fall.a.health, 1e-4);
	}
	
	@Test
	public void pearlStopsAtAPlayerInTheWay()
	{
		PvpSim sim = duel(IDLE, IDLE, 6);
		SimFighter a = sim.a;
		a.pearlFlying = true;
		a.pearlX = a.x;
		a.pearlY = 1;
		a.pearlZ = a.z;
		a.pearlVx = 1.5;
		for(int i = 0; i < 10 && a.pearlFlying; i++)
			sim.step();
		assertFalse(a.pearlFlying);
		// at their box, not past it
		assertTrue(a.x > sim.b.x - 1.2 && a.x < sim.b.x, "landed at " + a.x);
		assertEquals(15, a.health, 1e-4);
	}
	
	@Test
	public void windChargeAtYourFeetLaunches()
	{
		int[] thrown = {0};
		PvpSim sim =
			duel(scripted((o, a) -> a.windJump = thrown[0]++ == 0), IDLE, 6);
		double top = 0;
		for(int i = 0; i < 40; i++)
		{
			sim.step();
			top = Math.max(top, sim.a.y);
		}
		assertTrue(top > 5 && top < 8, "launched " + top);
		assertEquals(20, sim.a.health, 1e-4);
		assertEquals(0, sim.b.vy, 0.1);
	}
	
	@Test
	public void waterWashesAwayTheWebAndComesBack()
	{
		PvpSim sim = duel(scripted((o, a) -> a.water = true), IDLE, 6);
		sim.webX[0] = (int)Math.floor(sim.a.x);
		sim.webZ[0] = (int)Math.floor(sim.a.z);
		sim.webOwner[0] = sim.b;
		sim.webCount = 1;
		sim.webVersion++;
		int washedAt = -1;
		for(int i = 0; i < 30; i++)
		{
			sim.step();
			if(washedAt < 0 && sim.webCount == 0)
				washedAt = i;
		}
		assertTrue(washedAt > 0, "never washed");
		assertEquals(0, sim.a.breakTicks);
		assertEquals(PvpSim.KIT_WATER, sim.a.waters);
	}
	
	@Test
	public void potsThrowAllThreeBuffsTickByTick()
	{
		PvpSim sim = duel(scripted((o, a) -> {
			a.pot = true;
			a.forward = 1;
		}), IDLE, 6);
		// it stops and looks down first, keys or not
		sim.step();
		double x = sim.a.x, z = sim.a.z, moved = 0;
		for(int i = 0; i < PvpSim.POT_STOP_TICKS + 8; i++)
		{
			if(sim.a.potQueue != 0)
				moved = Math.hypot(sim.a.x - x, sim.a.z - z);
			sim.step();
		}
		assertEquals(3, sim.a.potsUsed);
		assertTrue(moved < 0.01, "moved " + moved);
		assertEquals(PvpSim.KIT_STRENGTH - 1, sim.a.pots);
		assertEquals(PvpSim.KIT_SPEED - 1, sim.a.speedPots);
		assertEquals(PvpSim.KIT_FIRE_RES - 1, sim.a.fireResPots);
		// thrown at the feet: most of each one's time
		assertTrue(sim.a.effectTicks[PvpSim.STRENGTH] > 1200);
		assertTrue(sim.a.effectTicks[PvpSim.SPEED] > 6400);
		assertTrue(sim.a.effectTicks[PvpSim.FIRE_RES] > 6400);
		// not again while they last
		for(int i = 0; i < 20; i++)
			sim.step();
		assertEquals(3, sim.a.potsUsed);
	}
	
	/**
	 * Potions don't stack: three thrown with more of each left = three
	 * hotbar slots to restock - the inventory open for 2 + 3 ticks, no keys,
	 * no hit (AimAssist's NeuralItems does the same).
	 */
	@Test
	public void emptiedSlotsAreRestocked()
	{
		PvpSim sim = duel(scripted((o, a) -> {
			a.pot = true;
			a.forward = 1;
		}), IDLE, 6);
		for(int i = 0; i < 40 && sim.a.restockUntil == PvpSim.NEVER; i++)
			sim.step();
		assertEquals(3, sim.a.potsUsed);
		// set on the tick it started: that one and 4 more
		assertEquals(5, sim.a.restockUntil - sim.tick);
		// (step() moves to the next tick, then plays it)
		double x = sim.a.x, z = sim.a.z;
		int frozen = 1;
		while(sim.tick + 1 < sim.a.restockUntil)
		{
			sim.step();
			assertTrue(sim.a.busy);
			frozen++;
		}
		assertEquals(5, frozen);
		// (sliding to a stop: the keys are what's off)
		assertTrue(Math.hypot(sim.a.x - x, sim.a.z - z) < 0.2);
		assertEquals(0, sim.a.restockPending);
	}
	
	@Test
	public void webSlowsYouDown()
	{
		double free = walked(false);
		double webbed = walked(true);
		assertTrue(webbed < free * 0.4, free + " vs " + webbed);
	}
	
	private double walked(boolean web)
	{
		PvpSim sim = duel(scripted((o, a) -> a.forward = 1), IDLE, 8);
		if(web)
		{
			sim.a.webReact = 1000;
			for(int i = 0; i < 4; i++)
			{
				sim.webX[i] = i;
				sim.webZ[i] = 0;
			}
			sim.webCount = 4;
			sim.webVersion++;
		}
		for(int i = 0; i < 20; i++)
			sim.step();
		return sim.a.x;
	}
	
	/** The whole-number web check is the hitbox overlap it replaced. */
	@Test
	public void webCheckIsTheOverlap()
	{
		PvpSim sim = new PvpSim(1, IDLE, IDLE, 600);
		SplittableRandom r = new SplittableRandom(5);
		for(int k = 0; k < 2000; k++)
		{
			sim.webCount = r.nextInt(PvpSim.MAX_WEBS + 1);
			for(int i = 0; i < sim.webCount; i++)
			{
				sim.webX[i] = r.nextInt(-4, 4);
				sim.webZ[i] = r.nextInt(-4, 4);
			}
			for(int j = 0; j < 200; j++)
			{
				// half the points on a 0.1 grid: edges exactly 0.3 away
				double x = j % 2 == 0 ? r.nextInt(-60, 60) / 10.0
					: r.nextDouble(-6, 6);
				double z = j % 2 == 0 ? r.nextInt(-60, 60) / 10.0
					: r.nextDouble(-6, 6);
				boolean expected = false;
				for(int i = 0; i < sim.webCount; i++)
					expected |= x + 0.3 > sim.webX[i]
						&& x - 0.3 < sim.webX[i] + 1 && z + 0.3 > sim.webZ[i]
						&& z - 0.3 < sim.webZ[i] + 1;
				assertEquals(expected, sim.inWeb(x, 0, z), x + " " + z);
			}
		}
	}
}
