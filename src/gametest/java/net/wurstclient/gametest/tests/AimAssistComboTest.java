/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.gametest.UserSettings;
import net.wurstclient.hacks.AimAssistHack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.HitCheck;
import net.wurstclient.util.RotationUtils;

/**
 * AimAssist's Auto combo the way the combo guides describe it, against a
 * VexBot (a server-side PvP bot that w-taps, strafes, jumps and crits):
 * <ul>
 * <li>sprint hits only, each as soon as the knockback is guaranteed (just
 * past 90% charge: 12 ticks with a sword, not the 13 of a full cooldown);
 * <li>hold W, hit, release W - W is let go right after every hit;
 * <li>with Skip falling targets, no hit on a target that's falling (falling
 * knockback);
 * <li>every sure chance at a sprint hit or a crit taken;
 * <li>in a real fight: only sprint hits and crits, a jump reset when the bot
 * hits us on the ground, and a crit on the way down.
 * </ul>
 * VexBot comes from build.gradle's prepareVexbot (-PvexbotJar).
 */
public final class AimAssistComboTest extends SingleplayerTest
{
	private static final String BOT = "Vex";
	
	/** What the client did, tick by tick (written on the client thread). */
	private final List<Boolean> forwardDown = new ArrayList<>();
	private final List<Attack> attacks = new ArrayList<>();
	private final List<Boolean> jumped = new ArrayList<>();
	private final List<HitTaken> hitsTaken = new ArrayList<>();
	private final List<Boolean> backDown = new ArrayList<>();
	
	/** AimAssist's Adaptive style choice, tick by tick. */
	private final List<String> styles = new ArrayList<>();
	
	/** Ticks AimAssist held a ready hit for Hit select (one tick late). */
	private final List<Boolean> held = new ArrayList<>();
	
	/** Ticks where a hit could have gone out (see isChance). */
	private final List<Boolean> chances = new ArrayList<>();
	
	/**
	 * Ticks where a sprint hit or a crit could have landed (see
	 * describeSureChance): what it looked like, or null.
	 */
	private final List<String> sureChances = new ArrayList<>();
	
	private record Attack(int tick, boolean sprintHit, boolean crit,
		boolean targetFalling, boolean deflecting)
	{}
	
	/**
	 * A hit the bot landed: where it came from (its eyes to our hitbox, ours
	 * to its, its height over ours), how far our own hit was, our keys.
	 */
	private record HitTaken(int tick, boolean onGround, double theirReach,
		double ourReach, double botAbove, int ourChargeIn, boolean w, boolean s,
		boolean sprintReady, boolean verifiable, boolean crit)
	{}
	
	private final UpdateListener recorder = () -> {
		Minecraft mc = WurstClient.MC;
		var player = mc.player;
		// the hit that arrived since the last tick (see AimAssist's
		// reactToTargetHit)
		if(player.hurtTime > 0 && player.hurtTime == player.hurtDuration)
			hitsTaken.add(hitTaken(mc));
		forwardDown.add(mc.options.keyUp.isDown());
		backDown.add(mc.options.keyDown.isDown());
		chances.add(isChance(mc));
		styles
			.add(WurstClient.INSTANCE.getHax().aimAssistHack.getPlayingStyle());
		sureChances.add(describeSureChance(mc));
		// (AimAssist runs after this: the last tick's hold)
		held.add(WurstClient.INSTANCE.getHax().aimAssistHack.isHitSelecting());
		// the input the last tick was moved with
		jumped.add(player.input.keyPresses.jump());
	};
	
	private final PlayerAttacksEntityListener attackRecorder = target -> {
		var player = WurstClient.MC.player;
		attacks.add(new Attack(forwardDown.size(),
			player.isSprinting() && player.wasSprinting,
			player.fallDistance > 0 && !player.onGround()
				&& !player.isSprinting() && !player.wasSprinting,
			isFalling(target),
			player.hurtTime > 0 && player.hurtTime >= player.hurtDuration - 4
				|| target instanceof Player p && p.swinging
					&& p.swingTime <= 4));
	};
	
	/**
	 * Every rendered frame: the camera's yaw/pitch and the yaw/pitch to the
	 * bot's middle as drawn, for spotting a jittery aim (see jitter()).
	 */
	private final List<float[]> frames = new ArrayList<>();
	
	private final RenderListener frameRecorder = (matrices, partialTicks) -> {
		Minecraft mc = WurstClient.MC;
		Entity bot = findBot(mc);
		if(bot == null)
			return;
		var target = EntityUtils.getLerpedBox(bot, partialTicks).getCenter();
		var eyes = EntityUtils.getLerpedPos(mc.player, partialTicks).add(0,
			mc.player.getEyeHeight(mc.player.getPose()), 0);
		var needed = RotationUtils.getNeededRotations(eyes, target);
		frames.add(new float[]{mc.player.getYRot(), mc.player.getXRot(),
			needed.yaw(), needed.pitch()});
	};
	
	private String me;
	
	public AimAssistComboTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing AimAssist's Auto combo against a VexBot");
		if(!FabricLoader.getInstance().isModLoaded("vexbot"))
			throw new RuntimeException("VexBot isn't loaded - build.gradle's"
				+ " prepareVexbot copies it from -PvexbotJar");
			
		// The bot is a player too, so "execute as @p" (runCommand) could
		// pick it - every command here names us instead.
		me = context.computeOnClient(mc -> mc.player.getName().getString());
		
		runCommand("gamerule spawn_mobs false");
		runCommand("kill @e[type=!player]");
		runAsMe("item replace entity @s weapon.mainhand with diamond_sword");
		
		// the user's own settings (Auto combo, Aim at Center, ...)
		Map<Setting, JsonElement> before =
			context.computeOnClient(mc -> UserSettings.applyAimAssist());
		
		context.runOnClient(mc -> {
			EventManager events = WurstClient.INSTANCE.getEventManager();
			events.add(UpdateListener.class, recorder);
			events.add(PlayerAttacksEntityListener.class, attackRecorder);
			events.add(RenderListener.class, frameRecorder);
		});
		
		try
		{
			// the combo's mechanics with its own style; Adaptive (the default)
			// only in the real fight
			runWurstCommand("setmode AimAssist Combat_style sprint_combo");
			if(part("still"))
				comboOnStillTarget();
			if(part("falling"))
				noHitWhileFalling();
			if(part("taps"))
				tapChoice();
			runWurstCommand("setmode AimAssist Combat_style adaptive");
			if(part("fight"))
				realFight();
			if(part("rematch"))
				rematchFight();
			if(part("crit"))
				critTraderFight();
			if(part("neural"))
				neuralFight();
			if(part("shield"))
				shieldAndCrouchDodge();
			
		}finally
		{
			context.runOnClient(mc -> {
				EventManager events = WurstClient.INSTANCE.getEventManager();
				events.remove(UpdateListener.class, recorder);
				events.remove(RenderListener.class, frameRecorder);
				events.remove(PlayerAttacksEntityListener.class,
					attackRecorder);
			});
			runWurstCommand("t AimAssist off");
			context.runOnClient(mc -> UserSettings.restore(before));
			removeBot();
			runAsMe("gamemode creative");
			runAsMe("effect clear @s");
			runCommand("gamerule spawn_mobs true");
			runCommand("kill @e[type=!player]"); // drops
			runAsMe("tp @s 0 -57 0 0 0");
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
	 * A bot that can't move (VexBot's movement freeze, no jumps, no
	 * knockback) and can't hurt us (creative): every hit a sprint hit, 12
	 * ticks apart, W let go after each.
	 */
	private void comboOnStillTarget()
	{
		startSpot();
		spawnBot("~ ~ ~-2.5");
		runAsMe("vexbot movement freeze");
		// Frozen, it still jumps out of the combo and takes knockback
		// (which would hold every hit back until it lands, see
		// noHitWhileFalling). The real fight is in realFight.
		runAsMe("attribute " + BOT + " minecraft:jump_strength base set 0");
		runAsMe(
			"attribute " + BOT + " minecraft:knockback_resistance base set 1");
		context.waitTicks(20);
		clearRecording();
		runWurstCommand("t AimAssist on");
		context.waitTicks(160);
		jitter("still bot");
		runWurstCommand("t AimAssist off");
		context.waitTicks(3);
		runAsMe("vexbot movement unfreeze");
		
		List<Attack> hits = snapshotAttacks();
		logger.info("Combo on a still bot: {} hits at ticks {}", hits.size(),
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
		
		removeBot();
	}
	
	/**
	 * With Skip falling targets (off by default, user 2026-10-16): a bot
	 * dropping from 6 blocks up right in front isn't hit while it falls (that
	 * would knock it into the ground), but once it has landed.
	 */
	private void noHitWhileFalling()
	{
		startSpot();
		spawnBot("~ ~6 ~-2");
		clearRecording();
		runWurstCommand("setcheckbox AimAssist Skip_falling_targets on");
		runWurstCommand("t AimAssist on");
		context.waitTicks(80);
		runWurstCommand("t AimAssist off");
		runWurstCommand("setcheckbox AimAssist Skip_falling_targets off");
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		logger.info("Falling bot: {} hits, falling at {}", hits.size(), hits
			.stream().filter(Attack::targetFalling).map(Attack::tick).toList());
		for(Attack hit : hits)
			if(hit.targetFalling())
				throw new RuntimeException(
					"Hit the bot while it was falling, at tick " + hit.tick());
		if(hits.isEmpty())
			throw new RuntimeException("Never hit the bot after it landed");
		
		removeBot();
	}
	
	/**
	 * A real fight (survival, both with diamond swords, both too tough to
	 * die in time): only sprint hits and crits, no hit on a falling bot
	 * unless it's a deflection, a jump reset whenever the bot hits us while
	 * we stand on the ground, and crits on the way down.
	 */
	private void realFight()
	{
		startSpot();
		spawnBot("~ ~ ~-4");
		context.waitTicks(20);
		runAsMe("gamemode survival");
		makeTough("@s");
		runAsMe("item replace entity " + BOT
			+ " weapon.mainhand with diamond_sword");
		clearRecording();
		// a stranger: nothing learned from the parts before
		context.runOnClient(mc -> WurstClient.INSTANCE.getHax().aimAssistHack
			.forgetOpponents());
		runAsMe("vexbot attack " + me + " " + BOT);
		runWurstCommand("t AimAssist on");
		recordFight("sprint_fight", 300);
		jitter("fight");
		// hits that land after this (the bot stops a few ticks late) found
		// AimAssist off
		int fightEnd = context.computeOnClient(mc -> forwardDown.size());
		logger.info("Fight: learned {}", learned());
		runWurstCommand("t AimAssist off");
		runAsMe("vexbot attack stop " + BOT);
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		List<HitTaken> taken =
			context.computeOnClient(mc -> List.copyOf(hitsTaken));
		List<Boolean> jumps = snapshot(jumped);
		logger.info("Fight: our hits {}", hits);
		logger.info("Fight: score {} hits on the bot, {} taken", hits.size(),
			taken.stream().filter(h -> h.tick() < fightEnd).count());
		firstFightOpening = logFightFlow("Fight", hits, taken, fightEnd);
		logDamage("Fight", hits, taken, fightEnd);
		logStyleSwitches("Fight", fightEnd);
		for(HitTaken h : taken)
			logger.info(String.format(Locale.ROOT,
				"Fight: hit taken at %d: its reach %.2f, ours %.2f, it %.2f"
					+ " above us, our hit ready in %d, W %s S %s, %s,"
					+ " sprint for the server %s, our look verifiable %s",
				h.tick(), h.theirReach(), h.ourReach(), h.botAbove(),
				h.ourChargeIn(), h.w(), h.s(),
				h.onGround() ? "on the ground" : "in the air", h.sprintReady(),
				h.verifiable()));
		
		boolean sweeps =
			UserSettings.aimAssist().get("Sprint sweeps").getAsBoolean();
		logger.info(
			"Fight: {} of {} hits neither sprint hits nor crits"
				+ " (Sprint sweeps {})",
			hits.stream().filter(h -> !h.sprintHit() && !h.crit()).count(),
			hits.size(), sweeps ? "on" : "off");
		if(hits.size() < 8)
			throw new RuntimeException(
				"Only " + hits.size() + " hits in a 300-tick fight");
		boolean skipFalling = context.computeOnClient(mc -> isSkipFalling());
		for(Attack hit : hits)
		{
			// (Sprint sweeps - on in the user's settings - chase a target
			// that backs out with hits that are neither)
			if(!hit.sprintHit() && !hit.crit() && !sweeps)
				throw new RuntimeException("Hit at tick " + hit.tick()
					+ " was neither a sprint hit nor a crit");
			if(skipFalling && hit.targetFalling() && !hit.deflecting())
				throw new RuntimeException("Hit the falling bot at tick "
					+ hit.tick() + " (and it wasn't a deflection)");
		}
		if(hits.stream().noneMatch(Attack::crit))
			throw new RuntimeException("Not a single crit in the fight");
			
		// The jump goes in with the input read in the tick the hit arrived,
		// which the next tick's record shows. (The knockback lifts us too -
		// only the jump key counts.) S instead is a fly-out (held into the
		// hit) or an S-tap deflection (too close to jump-reset).
		List<Boolean> back = snapshot(backDown);
		int onGround = 0;
		int jumpResets = 0;
		int sTaps = 0;
		for(HitTaken hit : taken)
		{
			if(!hit.onGround() || hit.tick() >= fightEnd)
				continue;
			onGround++;
			int t = hit.tick() + 1;
			if(t < jumps.size() && jumps.get(t))
				jumpResets++;
			else if(t < back.size() && back.get(t))
				sTaps++;
		}
		logger.info(
			"Fight: of {} hits taken on the ground, {} jump-reset, {} with S"
				+ " (fly-out / S-tap deflection)",
			onGround, jumpResets, sTaps);
		if(onGround == 0)
			throw new RuntimeException("The bot never hit us on the ground");
		if(jumpResets + sTaps < onGround)
			throw new RuntimeException("Only " + (jumpResets + sTaps) + " of "
				+ onGround + " hits taken on the ground got a reaction");
			
		// Chances to hit that went by: a hit goes out in the tick whose
		// record comes right before its index (the recorder runs first).
		List<Boolean> chance = snapshot(chances);
		Set<Integer> hitTicks = new HashSet<>();
		for(Attack hit : hits)
			hitTicks.add(hit.tick() - 1);
		int chanceTicks = 0;
		int missed = 0;
		int longestMiss = 0;
		int streak = 0;
		for(int t = 0; t < chance.size(); t++)
		{
			if(!chance.get(t))
			{
				streak = 0;
				continue;
			}
			chanceTicks++;
			if(hitTicks.contains(t))
			{
				streak = 0;
				continue;
			}
			missed++;
			longestMiss = Math.max(longestMiss, ++streak);
		}
		logger.info(
			"Fight: {} ticks with a chance to hit, {} went by, longest wait"
				+ " {} ticks",
			chanceTicks, missed, longestMiss);
		// (HitCheck's old 0.1-inside-on-both-rotations rule let 74% go by;
		// the rest now are mostly ticks the sent rotation was off the bot.)
		// Hops and Baits - on in the user's settings - let chances go on
		// purpose: a hop hits on the way down, a bait backs off. 2026-10-16:
		// 36 of 54 with them, 14 of 34 without.
		JsonObject user = UserSettings.aimAssist();
		boolean holdsBack =
			user.get("Hops").getAsBoolean() || user.get("Baits").getAsBoolean();
		int maxMissed = holdsBack ? 80 : 65;
		if(missed * 100 > chanceTicks * maxMissed)
			throw new RuntimeException("Let " + missed + " of " + chanceTicks
				+ " chances to hit go by (at most " + maxMissed + "%)");
			
		// A sure chance - a sprint hit or a crit that would have landed - is
		// never let go (user: "100% of the time, unless a setting says not
		// to").
		// (with nulls - List.copyOf can't)
		List<String> sure =
			context.computeOnClient(mc -> new ArrayList<>(sureChances));
		int sureTicks = 0;
		int heldBack = 0;
		List<Boolean> holds = snapshot(held);
		List<String> sureMissed = new ArrayList<>();
		for(int t = 0; t < sure.size() && t < fightEnd; t++)
		{
			if(sure.get(t) == null)
				continue;
			sureTicks++;
			if(t + 1 < holds.size() && holds.get(t + 1))
				heldBack++;
			else if(!hitTicks.contains(t))
				sureMissed.add("tick " + t + ": " + sure.get(t));
		}
		logger.info(
			"Fight: {} sure chances (sprint hit or crit), {} held by"
				+ " Hit select, {} missed {}",
			sureTicks, heldBack, sureMissed.size(), sureMissed);
		if(!sureMissed.isEmpty())
			throw new RuntimeException(
				"Let " + sureMissed.size() + " of " + sureTicks
					+ " sure sprint-hit/crit chances go by: " + sureMissed);
		
		removeBot();
	}
	
	/** What Adaptive has learned about the target (AimAssist on). */
	private String learned()
	{
		return context
			.computeOnClient(mc -> WurstClient.INSTANCE.getHax().aimAssistHack
				.describeOpponentModel());
	}
	
	/**
	 * Damage, not just hits (user, 2026-10-17: it looked like 75:25 for
	 * VexBot while AimAssist landed more hits): a crit counts 1.5 - a hit
	 * taken is a crit when the bot was falling.
	 */
	private void logDamage(String label, List<Attack> ours,
		List<HitTaken> theirs, int end)
	{
		double dealt = 0, taken = 0;
		int ourCrits = 0, theirCrits = 0;
		for(Attack a : ours)
		{
			dealt += a.crit() ? 1.5 : 1;
			ourCrits += a.crit() ? 1 : 0;
		}
		for(HitTaken h : theirs)
			if(h.tick() < end)
			{
				taken += h.crit() ? 1.5 : 1;
				theirCrits += h.crit() ? 1 : 0;
			}
		logger.info(String.format(Locale.ROOT,
			"%s: damage (crit = 1.5) ours %.1f (%d crits), theirs %.1f (%d"
				+ " crits)",
			label, dealt, ourCrits, taken, theirCrits));
	}
	
	/** realFight's first 100 ticks, ours:theirs. */
	private int[] firstFightOpening;
	
	private void logStyleSwitches(String label, int end)
	{
		List<String> styleTrace = snapshot(styles);
		StringBuilder switches = new StringBuilder();
		for(int i = 0; i < Math.min(end, styleTrace.size()); i++)
			if(i == 0 || !styleTrace.get(i).equals(styleTrace.get(i - 1)))
				switches.append(" ").append(i).append(": ")
					.append(styleTrace.get(i));
		logger.info("{}: Adaptive played{}", label, switches);
	}
	
	/**
	 * The same bot again (same name, so the same UUID): Adaptive remembers
	 * it per opponent for the session (user, 2026-10-17: "adapt as fast as
	 * possible"), so the rematch starts already adapted - its first 100 ticks
	 * against realFight's, where it knew nothing yet.
	 */
	private void rematchFight()
	{
		startSpot();
		spawnBot("~ ~ ~-4");
		context.waitTicks(20);
		runAsMe("gamemode survival");
		makeTough("@s");
		runAsMe("item replace entity " + BOT
			+ " weapon.mainhand with diamond_sword");
		clearRecording();
		runAsMe("vexbot attack " + me + " " + BOT);
		runWurstCommand("t AimAssist on");
		context.waitTick();
		logger.info("Rematch: starts with {}", learned());
		recordFight("rematch", 299);
		int fightEnd = context.computeOnClient(mc -> forwardDown.size());
		logger.info("Rematch: learned {}", learned());
		runWurstCommand("t AimAssist off");
		runAsMe("vexbot attack stop " + BOT);
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		List<HitTaken> taken =
			context.computeOnClient(mc -> List.copyOf(hitsTaken));
		logger.info("Rematch: score {} hits on the bot, {} taken", hits.size(),
			taken.stream().filter(h -> h.tick() < fightEnd).count());
		int[] opening = logFightFlow("Rematch", hits, taken, fightEnd);
		logDamage("Rematch", hits, taken, fightEnd);
		logStyleSwitches("Rematch", fightEnd);
		if(firstFightOpening != null)
			logger.info("Rematch: first 100 ticks {}:{}, first fight {}:{}",
				opening[0], opening[1], firstFightOpening[0],
				firstFightOpening[1]);
		removeBot();
		
		boolean sweeps =
			UserSettings.aimAssist().get("Sprint sweeps").getAsBoolean();
		if(hits.size() < 8)
			throw new RuntimeException(
				"Rematch: only " + hits.size() + " hits in a 300-tick fight");
		for(Attack hit : hits)
			if(!hit.sprintHit() && !hit.crit() && !sweeps)
				throw new RuntimeException("Rematch: hit at tick " + hit.tick()
					+ " was neither a sprint hit nor a crit");
	}
	
	/**
	 * The sprint reset after a hit by how the target moves: an S-tap when it
	 * comes at us (makes room), a W-tap when it backs off (keeps up). The
	 * test moves the bot itself, 0.12 blocks a tick (VexBot's goto and
	 * remote control didn't move it); we're in creative, so it can't hurt
	 * us.
	 */
	private void tapChoice()
	{
		// only the sprint reset (an uppercut or lunge would hit in the air)
		runWurstCommand("setcheckbox AimAssist Uppercuts off");
		runWurstCommand("setcheckbox AimAssist Lunges off");
		startSpot();
		spawnBot("~ ~ ~-4.5");
		Boolean towardS = sTapAfterFirstHit(0.12);
		removeBot();
		
		startSpot();
		spawnBot("~ ~ ~-2.5");
		Boolean awayS = sTapAfterFirstHit(-0.12);
		removeBot();
		// back to the user's values
		context.runOnClient(mc -> {
			var mine = UserSettings.aimAssist();
			for(String name : new String[]{"Uppercuts", "Lunges"})
				WurstClient.INSTANCE.getFeatureByName("AimAssist").getSettings()
					.get(name.toLowerCase(Locale.ROOT))
					.fromJson(mine.get(name));
		});
		
		logger.info("Tap choice: coming at us S-tap {}, backing off S-tap {}",
			towardS, awayS);
		if(towardS == null || awayS == null)
			throw new RuntimeException("Never hit the moving bot");
		if(!towardS)
			throw new RuntimeException(
				"W-tapped a bot coming at us (an S-tap makes room)");
		if(awayS)
			throw new RuntimeException(
				"S-tapped a bot backing off (a W-tap keeps up)");
	}
	
	/**
	 * Moves the bot by dz (+: south, toward us) every tick until our first
	 * hit; whether S went down right after it. null: no hit.
	 */
	private Boolean sTapAfterFirstHit(double dz)
	{
		String move = String.format(Locale.ROOT,
			"execute as %s at @s run tp @s ~ ~ ~%.2f", BOT, dz);
		// already moving when AimAssist first sees it
		for(int i = 0; i < 6; i++)
		{
			runCommand(move);
			context.waitTick();
		}
		clearRecording();
		runWurstCommand("t AimAssist on");
		for(int i = 0; i < 120
			&& context.computeOnClient(mc -> attacks.isEmpty()); i++)
		{
			runCommand(move);
			context.waitTick();
		}
		context.waitTicks(4);
		runWurstCommand("t AimAssist off");
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		if(hits.isEmpty())
			return null;
		
		List<Boolean> back = snapshot(backDown);
		// the reset's own ticks: from the third on, S can be the spacing
		// (staying out of the bot's reach while ours charges - VexBot keeps
		// walking at us even while the test pushes it away)
		int t = hits.get(0).tick();
		for(int i = t; i < Math.min(t + 2, back.size()); i++)
			if(back.get(i))
				return true;
		return false;
	}
	
	/**
	 * The fight, one screenshot a tick (user, 2026-10-17: "record them as
	 * mp4 and watch them" - the harness runs client and server in lockstep,
	 * so the screenshots don't slow the fight down): <name>.png in the
	 * screenshots folder, numbered - 20 a second of game time for a video.
	 */
	private void recordFight(String name, int ticks)
	{
		if(!RECORD)
		{
			context.waitTicks(ticks);
			return;
		}
		for(int i = 0; i < ticks; i++)
		{
			context.waitTick();
			context.takeScreenshot(name);
		}
	}
	
	/**
	 * Who held the fight (user: "VexBot keeps locking AimAssist in combos,
	 * while AimAssist can't lock Vex at all"): hits each side per 100 ticks,
	 * and the longest run of hits one side landed with no answer.
	 */
	private int[] logFightFlow(String label, List<Attack> ours,
		List<HitTaken> theirs, int end)
	{
		int[] opening = new int[2];
		List<int[]> events = new ArrayList<>();
		for(Attack a : ours)
			events.add(new int[]{a.tick(), 0});
		for(HitTaken h : theirs)
			if(h.tick() < end)
				events.add(new int[]{h.tick(), 1});
		events.sort((a, b) -> Integer.compare(a[0], b[0]));
		
		int[] longest = new int[2];
		int run = 0, side = -1;
		for(int[] e : events)
		{
			run = e[1] == side ? run + 1 : 1;
			side = e[1];
			longest[side] = Math.max(longest[side], run);
		}
		
		StringBuilder windows = new StringBuilder();
		for(int from = 0; from < end; from += 100)
		{
			int f = from;
			long o = events.stream()
				.filter(e -> e[1] == 0 && e[0] >= f && e[0] < f + 100).count();
			long t = events.stream()
				.filter(e -> e[1] == 1 && e[0] >= f && e[0] < f + 100).count();
			windows.append(String.format(Locale.ROOT, " %d-%d: %d:%d", from,
				from + 100, o, t));
			if(from == 0)
				opening = new int[]{(int)o, (int)t};
		}
		logger.info(
			"{}: per 100 ticks (ours:theirs){}; longest combo ours {}, theirs {}",
			label, windows, longest[0], longest[1]);
		return opening;
	}
	
	/**
	 * Combat style "Crit trader" (ECHO's way of fighting, see AimAssist) in
	 * the same 300-tick VexBot fight as realFight: only sprint hits and
	 * crits, crits among them, and the score next to the sprint combo's.
	 */
	private void critTraderFight()
	{
		startSpot();
		spawnBot("~ ~ ~-4");
		context.waitTicks(20);
		runAsMe("gamemode survival");
		makeTough("@s");
		runAsMe("item replace entity " + BOT
			+ " weapon.mainhand with diamond_sword");
		runWurstCommand("setmode AimAssist Combat_style crit_trader");
		clearRecording();
		runAsMe("vexbot attack " + me + " " + BOT);
		runWurstCommand("t AimAssist on");
		recordFight("crit_fight", 300);
		int fightEnd = context.computeOnClient(mc -> forwardDown.size());
		runWurstCommand("t AimAssist off");
		runAsMe("vexbot attack stop " + BOT);
		runWurstCommand("setmode AimAssist Combat_style adaptive");
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		long taken = context.computeOnClient(
			mc -> hitsTaken.stream().filter(h -> h.tick() < fightEnd).count());
		long crits = hits.stream().filter(Attack::crit).count();
		long neither =
			hits.stream().filter(h -> !h.sprintHit() && !h.crit()).count();
		logger.info(
			"Crit trader: score {} hits on the bot, {} taken; {} crits,"
				+ " {} neither sprint hits nor crits",
			hits.size(), taken, crits, neither);
		logFightFlow("Crit trader", hits,
			context.computeOnClient(mc -> List.copyOf(hitsTaken)), fightEnd);
		removeBot();
		
		if(hits.size() < 5)
			throw new RuntimeException("Crit trader: only " + hits.size()
				+ " hits in a 300-tick fight");
		if(crits == 0)
			throw new RuntimeException("Crit trader: not a single crit");
	}
	
	/**
	 * The "Neural network" setting in the same 300-tick VexBot fight: the
	 * network (trained in PvpSim, src/test) plays the keys and picks the hits
	 * - all of them sprint hits or crits - and the score is logged next to
	 * the combo's.
	 */
	private void neuralFight()
	{
		startSpot();
		spawnBot("~ ~ ~-4");
		context.waitTicks(20);
		runAsMe("gamemode survival");
		makeTough("@s");
		runAsMe("item replace entity " + BOT
			+ " weapon.mainhand with diamond_sword");
		runWurstCommand("setcheckbox AimAssist Neural_network on");
		clearRecording();
		runAsMe("vexbot attack " + me + " " + BOT);
		runWurstCommand("t AimAssist on");
		recordFight("neural_fight", 300);
		int fightEnd = context.computeOnClient(mc -> forwardDown.size());
		runWurstCommand("t AimAssist off");
		runAsMe("vexbot attack stop " + BOT);
		runWurstCommand("setcheckbox AimAssist Neural_network off");
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		long taken = context.computeOnClient(
			mc -> hitsTaken.stream().filter(h -> h.tick() < fightEnd).count());
		long crits = hits.stream().filter(Attack::crit).count();
		long neither =
			hits.stream().filter(h -> !h.sprintHit() && !h.crit()).count();
		logger.info(
			"Neural network: score {} hits on the bot, {} taken; {} crits,"
				+ " {} neither sprint hits nor crits",
			hits.size(), taken, crits, neither);
		logFightFlow("Neural network", hits,
			context.computeOnClient(mc -> List.copyOf(hitsTaken)), fightEnd);
		removeBot();
		
		if(hits.size() < 5)
			throw new RuntimeException("Neural network: only " + hits.size()
				+ " hits in a 300-tick fight");
		if(neither > 0)
			throw new RuntimeException("Neural network: " + neither
				+ " hits that were neither sprint hits nor crits");
	}
	
	/**
	 * Shield block and Crouch dodge (user, 2026-10-17: "doesn't work") against
	 * a VexBot, set up like the user's game: AutoTotem on (Health 0), a totem
	 * in the offhand and a shield in the hotbar. Shield source "Shield while
	 * healthy" puts the shield in after our first hit, the shield goes up
	 * while the bot's hit is due before ours, AutoTotem puts the totem back
	 * after the fight, and a crit dive onto us gets a crouch.
	 */
	private void shieldAndCrouchDodge()
	{
		startSpot();
		spawnBot("~ ~ ~-4");
		context.waitTicks(20);
		runAsMe("gamemode survival");
		makeTough("@s");
		runAsMe("item replace entity @s weapon.offhand with totem_of_undying");
		runAsMe("item replace entity @s hotbar.1 with shield");
		runAsMe("item replace entity " + BOT
			+ " weapon.mainhand with diamond_sword");
		runWurstCommand("setcheckbox AimAssist Shield_block on");
		runWurstCommand("setmode AimAssist Shield_source shield_while_healthy");
		runWurstCommand("setslider AutoTotem Health 0");
		runWurstCommand("t AutoTotem on");
		runWurstCommand("setcheckbox AimAssist Crouch_dodge on");
		// the drops below are teleports, up to ~6 blocks in a tick from where
		// knockback put it (120 blocks/s):
		// the user's Filter speed 35 let go of the target every time
		runWurstCommand("setslider AimAssist Filter_speed 200");
		
		int[] counts = new int[8];
		final int BLOCKING = 0, SWINGS = 1, HURT = 2, DIVES = 3,
			DIVE_CROUCHED = 4, CROUCHING = 5, TICKS = 6, OFFHAND_SHIELD = 7;
		boolean[] botWasSwinging = new boolean[1];
		UpdateListener watch = () -> {
			Minecraft mc = WurstClient.MC;
			var player = mc.player;
			counts[TICKS]++;
			if(player.getOffhandItem().is(Items.SHIELD))
				counts[OFFHAND_SHIELD]++;
			if(player.isBlocking())
				counts[BLOCKING]++;
			if(player.isShiftKeyDown())
				counts[CROUCHING]++;
			if(player.hurtTime > 0 && player.hurtTime == player.hurtDuration)
				counts[HURT]++;
			Player bot = findBot(mc);
			if(bot == null)
				return;
			if(bot.swinging && !botWasSwinging[0])
				counts[SWINGS]++;
			botWasSwinging[0] = bot.swinging;
			boolean dive = !bot.onGround() && bot.getY() - bot.yo < -0.05
				&& bot.getY() >= player.getY() - 0.1
				&& EntityUtils.distanceToHitboxSq(bot) <= 3.5 * 3.5;
			if(dive)
			{
				counts[DIVES]++;
				if(player.isShiftKeyDown())
					counts[DIVE_CROUCHED]++;
			}
		};
		context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
			.add(UpdateListener.class, watch));
		try
		{
			runAsMe("vexbot attack " + me + " " + BOT);
			runWurstCommand("t AimAssist on");
			// VexBot rarely drops onto us by itself (0 in 300 ticks once):
			// every 25 ticks it comes down from 3 blocks up, just in front
			context.waitTicks(40);
			for(int i = 0; i < 10; i++)
			{
				runAsMe("tp " + BOT + " ~ ~3 ~-1.5");
				context.waitTicks(25);
			}
			context.waitTicks(10);
			runWurstCommand("t AimAssist off");
			runAsMe("vexbot attack stop " + BOT);
			context.waitTicks(3);
			
		}finally
		{
			context.runOnClient(mc -> WurstClient.INSTANCE.getEventManager()
				.remove(UpdateListener.class, watch));
		}
		
		// the fight is over: AutoTotem puts the totem back
		boolean totemBack = false;
		for(int i = 0; i < 40 && !totemBack; i++)
		{
			context.waitTick();
			totemBack = context.computeOnClient(
				mc -> mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING));
		}
		runWurstCommand("t AutoTotem off");
		runAsMe("item replace entity @s weapon.offhand with air");
		runAsMe("item replace entity @s hotbar.1 with air");
		removeBot();
		
		logger.info(
			"Shield & crouch dodge: {} ticks, shield in the offhand {}, up {},"
				+ " bot swung {} times, hurt {} times, totem back after: {};"
				+ " crit dives onto us {} ticks, crouched in {} of them,"
				+ " crouching {} ticks in all",
			counts[TICKS], counts[OFFHAND_SHIELD], counts[BLOCKING],
			counts[SWINGS], counts[HURT], totemBack, counts[DIVES],
			counts[DIVE_CROUCHED], counts[CROUCHING]);
		if(counts[OFFHAND_SHIELD] == 0)
			throw new RuntimeException("Shield source \"Shield while healthy\""
				+ " never put the hotbar shield into the offhand");
		if(!totemBack)
			throw new RuntimeException(
				"AutoTotem didn't put the totem back after the fight");
		if(counts[SWINGS] > 3 && counts[BLOCKING] == 0)
			throw new RuntimeException("Shield block never raised the shield ("
				+ counts[SWINGS] + " bot swings)");
		if(counts[DIVES] > 0 && counts[CROUCHING] == 0)
			throw new RuntimeException("Crouch dodge never crouched ("
				+ counts[DIVES] + " ticks of crit dives onto us)");
	}
	
	/**
	 * The same open spot on the ground for every part, facing north - away
	 * from what the other tests built around the origin.
	 */
	private void startSpot()
	{
		runAsMe("tp @s 0.5 -60 -20.5 180 0");
		context.waitTicks(7);
	}
	
	/** A VexBot at a position relative to us, too tough to die in a test. */
	private void spawnBot(String relPos)
	{
		runAsMe("vexbot spawn " + BOT + " " + relPos);
		context.waitFor(mc -> mc.level.players().stream()
			.anyMatch(p -> p.getName().getString().equals(BOT)), 300);
		makeTough(BOT);
		hardestVexBot();
		// The still-bot part sets knockback resistance 1 and jump strength 0,
		// and the bot comes back with the same UUID - its saved player data
		// with them (user, 2026-10-17: "it looks like vex is taking no KB").
		// Logged as spawned, then vanilla's values.
		runAsMe("attribute " + BOT + " minecraft:knockback_resistance get");
		runAsMe("attribute " + BOT + " minecraft:jump_strength get");
		runAsMe(
			"attribute " + BOT + " minecraft:knockback_resistance base set 0");
		runAsMe("attribute " + BOT + " minecraft:jump_strength base set "
			+ VANILLA_JUMP_STRENGTH);
	}
	
	private static final String VANILLA_JUMP_STRENGTH = "0.41999998688697815";
	
	/**
	 * VexBot as hard as it gets (user, 2026-10-17: "the absolute highest
	 * difficulty, everything configured so it's as hard to beat as
	 * possible, excluding gear"). Read from its jar: difficulty PERFECT (no
	 * misses, 0-tick reactions, perfect crit timing, always full reach - its
	 * default, set in vexbot_settings.json by build.gradle too), jump resets
	 * and counter-stuns (flying out of a hard hit) 100% (default 50 / 30),
	 * combo strafing on, playstyle adaptive (the hardest of aggressive,
	 * adaptive, default in 4 runs, 2026-10-17). Its reach stays vanilla 3.0:
	 * more would be a reach hack, not a better player.
	 */
	private void hardestVexBot()
	{
		runCommand("vexbot settings jumpresetchance 100");
		runCommand("vexbot settings counterstunchance " + VEX_COUNTER_STUN);
		runCommand("vexbot settings strafeincombo true");
		runCommand("vexbot playstyle " + VEX_PLAYSTYLE + " " + BOT);
	}
	
	private static final String VEX_PLAYSTYLE = "adaptive";
	
	/**
	 * Fight screenshots for videos only on request (gametest -record): ~900
	 * per run made every round slower.
	 */
	private static final boolean RECORD =
		Boolean.getBoolean("wurst.test.record");
	
	/**
	 * Only some parts (gametest -parts fight,rematch): still, falling, taps,
	 * fight, rematch, crit, neural, shield. All without it.
	 */
	private static boolean part(String name)
	{
		String parts = System.getProperty("wurst.test.parts");
		return parts == null || parts.isBlank()
			|| java.util.Arrays.asList(parts.split(",")).contains(name);
	}
	
	private static final String VEX_COUNTER_STUN = "100";
	
	private void makeTough(String target)
	{
		runAsMe("effect give " + target + " resistance infinite 3 true");
		runAsMe("effect give " + target + " regeneration infinite 4 true");
		runAsMe("effect give " + target + " saturation infinite 0 true");
	}
	
	private void removeBot()
	{
		runCommand("vexbot kill " + BOT);
		context.waitTicks(25); // death animation
		runCommand("kill @e[type=item]");
	}
	
	private void runAsMe(String command)
	{
		runCommand("execute as " + me + " at @s run " + command);
	}
	
	/**
	 * A tick where a hit could go out: charged, the bot under the crosshair
	 * within 2.8 blocks, not falling, no shield up, not using an item.
	 */
	private static Player findBot(Minecraft mc)
	{
		if(mc.level == null)
			return null;
		for(Player p : mc.level.players())
			if(p != mc.player && p.getName().getString().equals(BOT))
				return p;
		return null;
	}
	
	private static boolean isChance(Minecraft mc)
	{
		var player = mc.player;
		if(player.getAttackStrengthScale(-0.5F) <= 0.9F || player.isUsingItem())
			return false;
		
		for(Player p : mc.level.players())
			if(p != player && p.getName().getString().equals(BOT))
				return mc.crosshairPickEntity == p
					&& !(isSkipFalling() && isFalling(p)) && !p.isBlocking()
					&& EntityUtils.distanceToHitboxSq(p) <= 2.8 * 2.8;
		return false;
	}
	
	private void clearRecording()
	{
		context.runOnClient(mc -> {
			backDown.clear();
			chances.clear();
			sureChances.clear();
			held.clear();
			styles.clear();
			forwardDown.clear();
			jumped.clear();
			attacks.clear();
			hitsTaken.clear();
			frames.clear();
		});
	}
	
	/**
	 * How jittery the camera was since the last clearRecording(): the share
	 * of frames where it turned back the way it came (a zig-zag, both steps
	 * at least 0.3 degrees), next to the same for the direction to the bot -
	 * a camera zig-zagging more than its target is the aim's own jitter.
	 */
	private String jitter(String phase)
	{
		List<float[]> f = context.computeOnClient(mc -> List.copyOf(frames));
		int n = f.size();
		double camYaw = zigzags(f, 0), camPitch = zigzags(f, 1);
		double botYaw = zigzags(f, 2), botPitch = zigzags(f, 3);
		double off = 0;
		for(float[] a : f)
			off += Math.hypot(net.minecraft.util.Mth.wrapDegrees(a[0] - a[2]),
				a[1] - a[3]);
		String line = String.format(Locale.ROOT,
			"%s: %d frames, camera zig-zags yaw %.1f%% pitch %.1f%%,"
				+ " bot's own yaw %.1f%% pitch %.1f%%, %.1f deg off on average",
			phase, n, camYaw, camPitch, botYaw, botPitch, n == 0 ? 0 : off / n);
		logger.info("Jitter - {}", line);
		// A camera that zig-zags more than its target is the aim's own
		// jitter: "Aim at: Auto" worked from the tick's eye position made
		// it 9-11% of the fight's frames (the bot's own: under 1%). A bot
		// that takes knockback and jumps zig-zags itself (pitch 1.7% once its
		// leftover "no knockback, no jumps" attributes were gone) and the
		// camera following it does too - so up to the bot's own + 1 point.
		if(camYaw > Math.max(MAX_ZIGZAG, botYaw + 1)
			|| camPitch > Math.max(MAX_ZIGZAG, botPitch + 1))
			throw new RuntimeException("Jittery aim - " + line);
		return line;
	}
	
	/** Frames the camera may zig-zag on at least, percent (see jitter()). */
	private static final double MAX_ZIGZAG = 2;
	
	private static double zigzags(List<float[]> f, int i)
	{
		int count = 0;
		for(int k = 2; k < f.size(); k++)
		{
			double d1 = net.minecraft.util.Mth
				.wrapDegrees(f.get(k - 1)[i] - f.get(k - 2)[i]);
			double d2 = net.minecraft.util.Mth
				.wrapDegrees(f.get(k)[i] - f.get(k - 1)[i]);
			if(d1 * d2 < 0 && Math.abs(d1) >= 0.3 && Math.abs(d2) >= 0.3)
				count++;
		}
		return f.size() < 3 ? 0 : 100.0 * count / (f.size() - 2);
	}
	
	private List<Attack> snapshotAttacks()
	{
		return context.computeOnClient(mc -> List.copyOf(attacks));
	}
	
	private <T> List<T> snapshot(List<T> list)
	{
		return context.computeOnClient(mc -> List.copyOf(list));
	}
	
	private HitTaken hitTaken(Minecraft mc)
	{
		var player = mc.player;
		Player bot = findBot(mc);
		int chargeIn = 0;
		while(chargeIn < 40
			&& player.getAttackStrengthScale(chargeIn - 0.5F) <= 0.9F)
			chargeIn++;
		double theirReach = Double.NaN, ourReach = Double.NaN, above = 0;
		if(bot != null)
		{
			Vec3 eyes = bot.getEyePosition();
			AABB box = player.getBoundingBox();
			Vec3 nearest = new Vec3(Mth.clamp(eyes.x, box.minX, box.maxX),
				Mth.clamp(eyes.y, box.minY, box.maxY),
				Mth.clamp(eyes.z, box.minZ, box.maxZ));
			theirReach = eyes.distanceTo(nearest);
			ourReach = Math.sqrt(EntityUtils.distanceToHitboxSq(bot));
			above = bot.getY() - player.getY();
		}
		return new HitTaken(forwardDown.size(), player.onGround(), theirReach,
			ourReach, above, chargeIn, mc.options.keyUp.isDown(),
			mc.options.keyDown.isDown(),
			player.isSprinting() && player.wasSprinting,
			bot != null && HitCheck.isVerifiable(bot),
			bot != null && isFalling(bot));
	}
	
	private static Field baitTicks;
	
	/**
	 * A sure chance (user, 2026-10-16: a sprint hit or crit "100% of the
	 * time, unless a setting says not to"): a hit now would be a sprint hit
	 * (the sprint state the server has) or a crit (vanilla's rule, the same),
	 * charged, in reach, verifiable from the sent and the current rotation
	 * (HitCheck - anything else is a hit the anticheat throws out), not on a
	 * raised shield, and no setting holding it back (Skip falling targets,
	 * Baits). Null if not, else what it looked like.
	 */
	private static String describeSureChance(Minecraft mc)
	{
		var player = mc.player;
		AimAssistHack hack = WurstClient.INSTANCE.getHax().aimAssistHack;
		if(!hack.isEnabled() || player.getAttackStrengthScale(-0.5F) <= 0.9F
			|| player.isUsingItem())
			return null;
		
		if(!(findBot(mc) instanceof Player bot) || bot.isBlocking()
			|| EntityUtils.distanceToHitboxSq(bot) > 3 * 3
			|| !HitCheck.isVerifiable(bot))
			return null;
		
		if(isSkipFalling() && isFalling(bot) || isBaiting(hack))
			return null;
		
		boolean sprintHit = player.isSprinting() && player.wasSprinting;
		boolean crit = player.fallDistance > 0 && !player.onGround()
			&& !player.isSprinting() && !player.wasSprinting;
		if(!sprintHit && !crit)
			return null;
		
		return String.format(Locale.ROOT,
			"%s, %.2f blocks, %s, bot %s, W %s S %s",
			sprintHit ? "sprint hit" : "crit",
			Math.sqrt(EntityUtils.distanceToHitboxSq(bot)),
			player.onGround() ? "on the ground" : "in the air",
			bot.onGround() ? "on the ground" : "in the air",
			mc.options.keyUp.isDown(), mc.options.keyDown.isDown());
	}
	
	/** AimAssist's Skip falling targets. Client thread. */
	private static boolean isSkipFalling()
	{
		return ((CheckboxSetting)WurstClient.INSTANCE.getHax().aimAssistHack
			.getSettings().get("skip falling targets")).isChecked();
	}
	
	/** AimAssist's Baits backing off right now (its private baitTicks). */
	private static boolean isBaiting(AimAssistHack hack)
	{
		try
		{
			if(baitTicks == null)
			{
				baitTicks = AimAssistHack.class.getDeclaredField("baitTicks");
				baitTicks.setAccessible(true);
			}
			return baitTicks.getInt(hack) > 0;
			
		}catch(ReflectiveOperationException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private static boolean isFalling(Entity e)
	{
		return e instanceof Player && !e.onGround() && e.getY() - e.yo < -0.01;
	}
}
