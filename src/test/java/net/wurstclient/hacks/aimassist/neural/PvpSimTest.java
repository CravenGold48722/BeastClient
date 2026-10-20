/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist.neural;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		sim.armor = sim.toughness = 0;
		sim.b.x = dist + 0.6;
		sim.b.z = 0;
		for(SimFighter f : new SimFighter[]{sim.a, sim.b})
		{
			f.viewDelay = 0;
			f.aimNoise = 0;
			f.aimRate = 60;
			f.attackTicker = 20;
			f.x = f == sim.a ? 0 : dist + 0.6;
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
}
