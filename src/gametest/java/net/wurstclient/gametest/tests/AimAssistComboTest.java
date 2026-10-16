/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * AimAssist's Auto combo the way the combo guides describe it, against real
 * husks:
 * <ul>
 * <li>sprint hits only, each as soon as the knockback is guaranteed (just
 * past 90% charge: 12 ticks with a sword, not the 13 of a full cooldown);
 * <li>hold W, hit, release W - W is let go right after every hit;
 * <li>no hit on a target that's falling (falling knockback);
 * <li>a jump reset the moment the target hits you.
 * </ul>
 */
public final class AimAssistComboTest extends SingleplayerTest
{
	/** What the client did, tick by tick (written on the client thread). */
	private final List<Boolean> forwardDown = new ArrayList<>();
	private final List<Boolean> onGround = new ArrayList<>();
	private final List<Double> yMotion = new ArrayList<>();
	private final List<Attack> attacks = new ArrayList<>();
	
	private record Attack(int tick, boolean sprintHit, boolean targetFalling)
	{}
	
	private final UpdateListener recorder = () -> {
		Minecraft mc = WurstClient.MC;
		forwardDown.add(mc.options.keyUp.isDown());
		onGround.add(mc.player.onGround());
		yMotion.add(mc.player.getDeltaMovement().y);
	};
	
	private final PlayerAttacksEntityListener attackRecorder = target -> {
		var player = WurstClient.MC.player;
		attacks.add(new Attack(forwardDown.size(),
			player.isSprinting() && player.wasSprinting, isFalling(target)));
	};
	
	public AimAssistComboTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing AimAssist's Auto combo");
		runCommand("gamerule spawn_mobs false");
		runCommand("kill @e[type=!player]");
		runCommand("item replace entity @s weapon.mainhand with diamond_sword");
		runCommand("tp @s ~ ~ ~ 0 0");
		context.waitTicks(7);
		
		runWurstCommand("setcheckbox AimAssist Auto_attack on");
		runWurstCommand("setcheckbox AimAssist Auto_combo on");
		// one less thing moving us around
		runWurstCommand("setcheckbox AimAssist Dodging off");
		
		context.runOnClient(mc -> {
			EventManager events = WurstClient.INSTANCE.getEventManager();
			events.add(UpdateListener.class, recorder);
			events.add(PlayerAttacksEntityListener.class, attackRecorder);
		});
		
		try
		{
			comboOnStillTarget();
			noHitWhileFalling();
			jumpResetWhenHit();
			
		}finally
		{
			context.runOnClient(mc -> {
				EventManager events = WurstClient.INSTANCE.getEventManager();
				events.remove(UpdateListener.class, recorder);
				events.remove(PlayerAttacksEntityListener.class,
					attackRecorder);
			});
			runWurstCommand("t AimAssist off");
			runWurstCommand("setcheckbox AimAssist Auto_combo off");
			runWurstCommand("setcheckbox AimAssist Dodging on");
			runCommand("gamemode creative");
			runCommand("effect clear @s");
			runCommand("gamerule spawn_mobs true");
			runCommand("kill @e[type=husk]");
			context.waitTicks(25); // death animation
			runCommand("kill @e[type=!player]"); // drops
			runCommand("tp @s 0 -57 0 0 0");
			clearInventory();
			clearChat();
			clearParticles();
			clearToasts();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7);
			waitForHandSwing();
		}
	}
	
	/**
	 * A husk that can't move or die: every hit a sprint hit, 12 ticks apart
	 * (one combo hit may be held back on purpose), W let go after each.
	 */
	private void comboOnStillTarget()
	{
		runCommand(
			"summon husk ~ ~ ~2.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,"
				+ "Health:1024f,attributes:[{id:\"minecraft:max_health\","
				+ "base:1024}]}");
		context.waitTicks(5);
		clearRecording();
		runWurstCommand("t AimAssist on");
		context.waitTicks(160);
		runWurstCommand("t AimAssist off");
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		logger.info("Combo on a still husk: {} hits at ticks {}", hits.size(),
			hits.stream().map(Attack::tick).toList());
		if(hits.size() < 8)
			throw new RuntimeException(
				"Only " + hits.size() + " hits in 160 ticks");
		
		List<Integer> gaps = new ArrayList<>();
		for(int i = 1; i < hits.size(); i++)
			gaps.add(hits.get(i).tick() - hits.get(i - 1).tick());
		long fast = gaps.stream().filter(g -> g <= 12).count();
		if(gaps.stream().anyMatch(g -> g < 11))
			throw new RuntimeException("A hit before the knockback was"
				+ " guaranteed (under 11 ticks): " + gaps);
		if(fast < gaps.size() - 2)
			throw new RuntimeException(
				"Hits still wait for a full cooldown: gaps " + gaps);
		
		for(Attack hit : hits)
			if(!hit.sprintHit())
				throw new RuntimeException(
					"Hit at tick " + hit.tick() + " wasn't a sprint hit");
			
		List<Boolean> forward = snapshot(forwardDown);
		for(Attack hit : hits)
		{
			int t = hit.tick();
			boolean released = t < forward.size() && !forward.get(t)
				|| t + 1 < forward.size() && !forward.get(t + 1);
			if(!released)
				throw new RuntimeException("W still held after the hit at tick "
					+ t + " (hold W, hit, release W)");
		}
		
		runCommand("kill @e[type=husk]");
		context.waitTicks(25);
	}
	
	/**
	 * A husk dropping from 6 blocks up right in front: no hit while it falls
	 * (that would knock it into the ground), but one once it has landed.
	 */
	private void noHitWhileFalling()
	{
		runCommand(
			"summon husk ~ ~6 ~2 {NoAI:0b,Silent:1b,PersistenceRequired:1b,"
				+ "Health:1024f,attributes:[{id:\"minecraft:max_health\","
				+ "base:1024},{id:\"minecraft:movement_speed\",base:0}]}");
		clearRecording();
		runWurstCommand("t AimAssist on");
		context.waitTicks(80);
		runWurstCommand("t AimAssist off");
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		logger.info("Falling husk: {} hits, falling at {}", hits.size(), hits
			.stream().filter(Attack::targetFalling).map(Attack::tick).toList());
		for(Attack hit : hits)
			if(hit.targetFalling())
				throw new RuntimeException(
					"Hit the husk while it was falling, at tick " + hit.tick());
		if(hits.isEmpty())
			throw new RuntimeException("Never hit the husk after it landed");
		
		runCommand("kill @e[type=husk]");
		context.waitTicks(25);
	}
	
	/**
	 * The husk hits us (survival, a damage command with the husk as the
	 * attacker): AimAssist jumps in that same tick - a jump reset.
	 */
	private void jumpResetWhenHit()
	{
		runCommand(
			"summon husk ~ ~ ~2.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,"
				+ "Health:1024f,attributes:[{id:\"minecraft:max_health\","
				+ "base:1024}]}");
		runCommand("gamemode survival");
		runCommand("effect give @s regeneration infinite 4 true");
		context.waitTicks(5);
		runWurstCommand("t AimAssist on");
		context.waitTicks(20);
		
		for(int attempt = 0; attempt < 3; attempt++)
		{
			// standing on the ground first
			context.waitFor(mc -> mc.player.onGround(), 40);
			context.waitTicks(4);
			clearRecording();
			runCommand("damage @s 1 minecraft:mob_attack by @e[type=husk,"
				+ "limit=1]");
			context.waitTicks(6);
			
			List<Boolean> ground = snapshot(onGround);
			List<Double> motion = snapshot(yMotion);
			logger.info("After being hit: on ground {}, y motion {}", ground,
				motion);
			for(int t = 0; t < ground.size(); t++)
				if(motion.get(t) > 0.3)
				{
					logger.info("Jump reset {} ticks after the hit", t);
					runWurstCommand("t AimAssist off");
					return;
				}
		}
		
		throw new RuntimeException("Never jumped when the husk hit us");
	}
	
	private void clearRecording()
	{
		context.runOnClient(mc -> {
			forwardDown.clear();
			onGround.clear();
			yMotion.clear();
			attacks.clear();
		});
	}
	
	private List<Attack> snapshotAttacks()
	{
		return context.computeOnClient(mc -> List.copyOf(attacks));
	}
	
	private <T> List<T> snapshot(List<T> list)
	{
		return context.computeOnClient(mc -> List.copyOf(list));
	}
	
	private static boolean isFalling(Entity e)
	{
		return e instanceof Husk && !e.onGround() && e.getY() - e.yo < -0.01;
	}
}
