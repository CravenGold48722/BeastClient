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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
	
	private record HitTaken(int tick, boolean onGround)
	{}
	
	private final UpdateListener recorder = () -> {
		Minecraft mc = WurstClient.MC;
		var player = mc.player;
		// the hit that arrived since the last tick (see AimAssist's
		// reactToTargetHit)
		if(player.hurtTime > 0 && player.hurtTime == player.hurtDuration)
			hitsTaken.add(new HitTaken(forwardDown.size(), player.onGround()));
		forwardDown.add(mc.options.keyUp.isDown());
		backDown.add(mc.options.keyDown.isDown());
		chances.add(isChance(mc));
		sureChances.add(describeSureChance(mc));
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
			comboOnStillTarget();
			noHitWhileFalling();
			tapChoice();
			realFight();
			
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
		runAsMe("vexbot attack " + me + " " + BOT);
		runWurstCommand("t AimAssist on");
		context.waitTicks(300);
		jitter("fight");
		// hits that land after this (the bot stops a few ticks late) found
		// AimAssist off
		int fightEnd = context.computeOnClient(mc -> forwardDown.size());
		runWurstCommand("t AimAssist off");
		runAsMe("vexbot attack stop " + BOT);
		context.waitTicks(3);
		
		List<Attack> hits = snapshotAttacks();
		List<HitTaken> taken =
			context.computeOnClient(mc -> List.copyOf(hitsTaken));
		List<Boolean> jumps = snapshot(jumped);
		logger.info("Fight: our hits {}", hits);
		logger.info("Fight: hits taken {}", taken);
		
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
		List<String> sureMissed = new ArrayList<>();
		for(int t = 0; t < sure.size() && t < fightEnd; t++)
		{
			if(sure.get(t) == null)
				continue;
			sureTicks++;
			if(!hitTicks.contains(t))
				sureMissed.add("tick " + t + ": " + sure.get(t));
		}
		logger.info("Fight: {} sure chances (sprint hit or crit), {} missed {}",
			sureTicks, sureMissed.size(), sureMissed);
		if(!sureMissed.isEmpty())
			throw new RuntimeException(
				"Let " + sureMissed.size() + " of " + sureTicks
					+ " sure sprint-hit/crit chances go by: " + sureMissed);
		
		removeBot();
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
		int t = hits.get(0).tick();
		for(int i = t; i < Math.min(t + 3, back.size()); i++)
			if(back.get(i))
				return true;
		return false;
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
	}
	
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
		// it 9-11% of the fight's frames (the bot's own: under 1%).
		if(camYaw > MAX_ZIGZAG || camPitch > MAX_ZIGZAG)
			throw new RuntimeException("Jittery aim - " + line);
		return line;
	}
	
	/** Frames the camera may zig-zag on, percent (see jitter()). */
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
