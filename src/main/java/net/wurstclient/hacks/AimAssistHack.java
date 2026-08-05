/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.aimassist.AiStrategy;
import net.wurstclient.hacks.aimassist.AiStrategy.Dodge;
import net.wurstclient.hacks.aimassist.GeminiCombatAdvisor;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.AimAtSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FaceTargetSetting;
import net.wurstclient.settings.FaceTargetSetting.FaceTarget;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.*;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

public final class AimAssistHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 4.5, 1, 20, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting fov =
		new SliderSetting("FOV", "description.wurst.setting.aimassist.fov", 120,
			30, 360, 10, ValueDisplay.DEGREES);
	
	private final AimAtSetting aimAt = new AimAtSetting(
		"What point in the target's hitbox AimAssist should aim at.");
	
	private final FaceTargetSetting faceTarget =
		FaceTargetSetting.withoutPacketSpam(
			WText.literal("How AimAssist rotates toward the target.\n\n"
				+ "§lServer-side§r (default) keeps your camera still and"
				+ " only aims inside the outgoing movement packet — silent"
				+ " aim, the way LiquidBounce works. Auto-attack still lands.\n\n"
				+ "§lClient-side§r turns your camera like the old"
				+ " behavior.\n\n"
				+ "Note: Auto-combo and Aura-Farming always steer the camera"
				+ " while they move you, since you can't sprint toward a target"
				+ " you aren't facing."),
			FaceTarget.CLIENT);
	
	private final TextFieldSetting switchTargetKey =
		new TextFieldSetting("Switch target key",
			"Key that cycles to a different target. Uses Minecraft key"
				+ " translation keys (e.g. key.keyboard.tab, key.keyboard.r).",
			"key.keyboard.tab", this::isValidKeybind);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"description.wurst.setting.aimassist.check_line_of_sight", true);
	
	private final CheckboxSetting aimWhileBlocking =
		new CheckboxSetting("Aim while blocking",
			"description.wurst.setting.aimassist.aim_while_blocking", false);
	
	private final CheckboxSetting autoAttack =
		new CheckboxSetting("Auto attack",
			"Automatically attacks the target as soon as the attack cooldown"
				+ " is full. Disable this if you want to click manually.",
			true);
	
	private final CheckboxSetting autoCombo = new CheckboxSetting("Auto combo",
		"Sprints forward and attacks with a sprint-knockback hit, resetting"
			+ " sprint between hits. Catches the target whether they are on"
			+ " the ground or in the air.\n\n"
			+ "When §lAura-Farming§r is enabled, jumps and attacks"
			+ " while falling for a critical hit instead.\n\n"
			+ "Requires §lAuto attack§r to be enabled.\n\n"
			+ "The combo is reset whenever your crosshair leaves the target"
			+ " or you click the attack button manually.",
		false);
	
	private final CheckboxSetting auraFarming =
		new CheckboxSetting("Aura-Farming",
			"Does a 360 spin while airborne on the way up, then snaps back to"
				+ " facing the target. Cosmetic only - attacks and targeting"
				+ " keep working through the spin.",
			false);
	
	private final EntityFilterList entityFilters =
		new EntityFilterList(FilterPlayersSetting.genericCombat(false),
			FilterSleepingSetting.genericCombat(false),
			FilterFlyingSetting.genericCombat(0),
			FilterHostileSetting.genericCombat(false),
			FilterNeutralSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterPassiveSetting.genericCombat(true),
			FilterPassiveWaterSetting.genericCombat(true),
			FilterBabiesSetting.genericCombat(true),
			FilterBatsSetting.genericCombat(true),
			FilterSlimesSetting.genericCombat(true),
			FilterPetsSetting.genericCombat(true),
			FilterVillagersSetting.genericCombat(true),
			FilterZombieVillagersSetting.genericCombat(true),
			FilterGolemsSetting.genericCombat(false),
			FilterPiglinsSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterZombiePiglinsSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterEndermenSetting
				.genericCombat(AttackDetectingEntityFilter.Mode.OFF),
			FilterShulkersSetting.genericCombat(false),
			FilterInvisibleSetting.genericCombat(true),
			FilterNamedSetting.genericCombat(false),
			FilterShulkerBulletSetting.genericCombat(false),
			FilterArmorStandsSetting.genericCombat(true),
			FilterCrystalsSetting.genericCombat(true));
	
	// Music settings
	private final CheckboxSetting playMusic = new CheckboxSetting("Play music",
		"Plays music while AimAssist is active.\n\n"
			+ "Place WAV files in .minecraft/wurst/music/ and select them"
			+ " with the Music file picker.",
		false);
	
	private final FileSetting musicFile = new FileSetting("Music file",
		"WAV file to play. Add your own files to .minecraft/wurst/music/"
			+ " and select them here.\n\n"
			+ "A sample tune is created automatically on first use.",
		"music", AimAssistHack::createDefaultMusicFiles);
	
	private final SliderSetting musicVolume =
		new SliderSetting("Volume", 50, 0, 100, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting loopMusic = new CheckboxSetting("Loop music",
		"Restarts the track from the beginning when it ends.", true);
	
	private final EnumSetting<PlayWhen> playWhen = new EnumSetting<>(
		"Play when",
		"Controls when music plays.\n\n"
			+ "§lWhile enabled§r - plays music whenever"
			+ " AimAssist is on.\n\n"
			+ "§lWhile targeting§r - only plays music while a"
			+ " target is locked.",
		PlayWhen.values(), PlayWhen.WHILE_ENABLED);
	
	// ── AI settings
	// ───────────────────────────────────────────────────────────
	
	private final CheckboxSetting aiEnabled = new CheckboxSetting("AI assist",
		"Lets a Gemini model analyze the fight and steer AimAssist.\n\n"
			+ "The model does §lnot§r press keys directly - a Gemini round"
			+ " trip takes far longer than a tick. Instead it continuously"
			+ " sets the plan (which way to juke, how aggressive to be, who"
			+ " to fight) and a reflex layer running every tick presses"
			+ " §lA§r and §lD§r at the right moment to act on it.\n\n"
			+ "Requires a Gemini API key below.",
		false);
	
	private final TextFieldSetting geminiApiKey =
		new TextFieldSetting("Gemini API key",
			"Your Google AI Studio API key.\n\n"
				+ "§cStored in plain text§r in your Wurst settings file. If"
				+ " you leave this blank, the §lBEAST_GEMINI_KEY§r"
				+ " environment variable is used instead.",
			"");
	
	private final TextFieldSetting geminiModel =
		new TextFieldSetting("Gemini model",
			"Which Gemini model to ask. Use a fast one - the flash models"
				+ " respond quickly enough to keep up with a fight.",
			"gemini-2.0-flash");
	
	private final SliderSetting aiInterval = new SliderSetting("AI interval",
		"How often to ask the model for an updated plan.\n\n"
			+ "Lower reacts faster but burns API quota much faster. Gemini's"
			+ " free tier allows roughly §l15 requests per minute§r, which is"
			+ " one every §l4000 ms§r - go below that on a free key and you"
			+ " will start getting rate-limited within seconds.\n\n"
			+ "Dodging does §lnot§r stop when a request is skipped: the reflex"
			+ " layer keeps reacting every tick using the last plan it got.",
		4000, 500, 15000, 250, ValueDisplay.INTEGER.withSuffix(" ms"));
	
	private final CheckboxSetting aiDodging = new CheckboxSetting("AI dodging",
		"Lets the AI-driven reflex layer strafe with §lA§r and §lD§r to"
			+ " dodge incoming attacks.",
		true);
	
	private final CheckboxSetting aiTargeting =
		new CheckboxSetting("AI targeting",
			"Lets the AI switch targets - for example away from someone"
				+ " running off and onto whoever is actually attacking you.",
			true);
	
	private Entity target;
	private boolean switchKeyDownLastTick;
	private long lastFrameTime;
	
	private ComboPhase comboPhase = ComboPhase.IDLE;
	private boolean attackKeyDownLastTick;
	private boolean forwardKeyForced;
	private boolean backwardKeyForced;
	private float stapTicksLeft;
	private float spinRemaining;
	private boolean spinTriggered;
	private int comboHitCount;
	private boolean airborneHitDone;
	
	/**
	 * Guarantees at most one attack per tick. Without it the airborne-hit path
	 * and the cooldown path could both fire on the same tick, which showed up
	 * as AimAssist "hitting twice".
	 */
	private boolean attackedThisTick;
	
	// ── AI state
	// ──────────────────────────────────────────────────────────────
	
	private final GeminiCombatAdvisor advisor = new GeminiCombatAdvisor();
	
	private int dodgeTicksLeft;
	private Dodge dodgeDirection = Dodge.NONE;
	private boolean leftKeyForced;
	private boolean rightKeyForced;
	private int dodgeCooldown;
	private Dodge lastDodgeDirection = Dodge.NONE;
	private String lastReportedAiError;
	private long lastAiErrorReportNanos;
	
	/** Minimum gap between two AI error messages in chat. */
	private static final int AI_ERROR_REPORT_INTERVAL_SECONDS = 10;
	
	// Music state — musicRunning and musicLine are volatile because the music
	// thread reads/writes them while the game thread writes/reads them.
	private Thread musicThread;
	private volatile boolean musicRunning;
	private volatile SourceDataLine musicLine;
	
	private static final double FAR_THRESHOLD_SQ = 3.05 * 3.05;
	private static final double CLOSE_THRESHOLD_SQ = 0.7 * 0.7;
	
	public AimAssistHack()
	{
		super("AimAssist");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(fov);
		addSetting(aimAt);
		addSetting(faceTarget);
		addSetting(switchTargetKey);
		addSetting(checkLOS);
		addSetting(aimWhileBlocking);
		addSetting(autoAttack);
		addSetting(autoCombo);
		addSetting(auraFarming);
		
		addSetting(aiEnabled);
		addSetting(geminiApiKey);
		addSetting(geminiModel);
		addSetting(aiInterval);
		addSetting(aiDodging);
		addSetting(aiTargeting);
		
		entityFilters.forEach(this::addSetting);
		
		addSetting(playMusic);
		addSetting(musicFile);
		addSetting(musicVolume);
		addSetting(loopMusic);
		addSetting(playWhen);
	}
	
	@Override
	protected void onEnable()
	{
		// disable incompatible hacks
		WURST.getHax().autoFishHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		target = null;
		switchKeyDownLastTick = false;
		lastFrameTime = System.nanoTime();
		comboPhase = ComboPhase.IDLE;
		attackKeyDownLastTick = false;
		forwardKeyForced = false;
		backwardKeyForced = false;
		stapTicksLeft = 0;
		comboHitCount = 0;
		spinRemaining = 0F;
		spinTriggered = false;
		airborneHitDone = false;
		attackedThisTick = false;
		resetDodge();
		lastDodgeDirection = Dodge.NONE;
		lastReportedAiError = null;
		lastAiErrorReportNanos = 0L;
		advisor.reset();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		
		updateMusicPlayback();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		target = null;
		resetCombo();
		resetDodge();
		advisor.reset();
		stopMusic();
	}
	
	@Override
	public void onUpdate()
	{
		// Exactly one attack is allowed per tick; see attackTarget().
		attackedThisTick = false;
		
		// Music is managed every tick before combat logic so it responds to
		// target changes from the previous tick without any extra early-return
		// handling.
		updateMusicPlayback();
		
		// Always track the attack-key edge so click detection stays in sync
		// across early returns, both modes, and screen transitions.
		boolean attackKeyDown =
			IKeyMapping.get(MC.options.keyAttack).isActuallyDown();
		boolean attackClicked = attackKeyDown && !attackKeyDownLastTick;
		attackKeyDownLastTick = attackKeyDown;
		
		// don't aim when a container/inventory screen is open
		if(MC.screen instanceof AbstractContainerScreen)
		{
			resetCombo();
			resetDodge();
			return;
		}
		
		if(!aimWhileBlocking.isChecked() && MC.player.isUsingItem())
		{
			target = null;
			resetCombo();
			resetDodge();
			return;
		}
		
		boolean switchKeyDown = isSwitchKeyDown();
		boolean switchRequested = switchKeyDown && !switchKeyDownLastTick;
		switchKeyDownLastTick = switchKeyDown;
		
		if(switchRequested || !isValidTarget(target))
			target = pickTarget(switchRequested ? target : null);
			
		// A manual switch always wins over the AI for that tick, so pressing
		// the switch key still feels responsive.
		if(!switchRequested)
			target = applyAiTargeting(target);
		
		if(target == null)
		{
			resetCombo();
			resetDodge();
			return;
		}
		WURST.getHax().autoSwordHack.setSlot(target);
		
		// Hand the fight to the AI and run the tick-rate reflex layer that
		// acts on whatever plan it last came back with.
		updateAi();
		updateDodge();
		
		// Apply the aim. Server-side keeps the camera still and only rewrites
		// the rotation in the outgoing movement packet (silent aim, the way
		// LiquidBounce works); the swap happens in onPreMotion/onPostMotion.
		// The cosmetic spin and the movement combo need a real camera angle to
		// sprint toward the target, so those steer client-side instead.
		Vec3 aimPoint = aimAt.getAimPoint(target);
		if(spinRemaining > 0F)
			WURST.getRotationFaker().faceVectorPacket(aimPoint);
		else if(autoAttack.isChecked() && autoCombo.isChecked())
			WURST.getRotationFaker().faceVectorClient(aimPoint);
		else
			faceTarget.face(aimPoint);
		
		if(!autoAttack.isChecked())
		{
			resetCombo();
			return;
		}
		
		// Reset the hit timer when the player manually clicks or the
		// crosshair leaves the target. During an aura-farming spin the
		// crosshair is intentionally off-target, so skip that half of the
		// check — attacks still land via the entity reference and the
		// spin snaps back to the target when it finishes.
		boolean spinning = spinRemaining > 0F;
		if(attackClicked || (!spinning && !isCrosshairOnTarget()))
		{
			MC.player.resetAttackStrengthTicker();
			resetCombo();
			return;
		}
		
		double distToTargetSq = EntityUtils.distanceToHitboxSq(target);
		
		boolean targetAirborne =
			!target.onGround() && (target.getDeltaMovement().y > 0
				|| target.fallDistance >= 0.333F);
		// The cooldown check matters here: without it this fired on a
		// part-charged weapon and the cooldown branch below then landed a
		// second hit in the same tick, which is what "AimAssist hits twice"
		// looked like in practice.
		if(targetAirborne && !airborneHitDone && distToTargetSq <= 8.999991
			&& MC.player.getAttackStrengthScale(0) >= 1F)
		{
			if(attackTarget())
				airborneHitDone = true;
		}
		if(target.onGround())
			airborneHitDone = false;
		
		if(autoCombo.isChecked())
			updateAutoCombo();
		else if(MC.player.getAttackStrengthScale(0) >= 1F
			&& distToTargetSq <= 8.999991)
			attackTarget();
	}
	
	/**
	 * The one and only place AimAssist swings at its target.
	 *
	 * <p>
	 * Several independent code paths can decide to attack on the same tick -
	 * the airborne opportunity hit, the combo state machine, and the plain
	 * cooldown hit. Funnelling them all through here caps it at one attack per
	 * tick, which is what fixes the double-hit.
	 *
	 * @return whether this call was the one that landed the attack
	 */
	private boolean attackTarget()
	{
		if(attackedThisTick || target == null)
			return false;
		
		attackedThisTick = true;
		MC.gameMode.attack(MC.player, target);
		MC.player.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	private void updateAutoCombo()
	{
		float cooldown = MC.player.getAttackStrengthScale(0);
		double distSq = target != null ? EntityUtils.distanceToHitboxSq(target)
			: Double.MAX_VALUE;
		boolean inComboRange =
			distSq > CLOSE_THRESHOLD_SQ && distSq < FAR_THRESHOLD_SQ;
		if(!auraFarming.isChecked())
		{
			if(MC.player.fallDistance >= 0.15F)
			{
				comboPhase = ComboPhase.STAPPING;
			}
			
		}
		switch(comboPhase)
		{
			case IDLE:
			WURST.getHax().autoSprintHack.setEnabled(true);
			adjustSpacing(distSq, inComboRange);
			if(!auraFarming.isChecked())
			{
				if(MC.player.fallDistance >= 0.15F)
				{
					comboPhase = ComboPhase.STAPPING;
				}
				
			}
			if(cooldown >= 1.0F && inComboRange)
			{
				MC.player.setSprinting(true);
				holdForward();
				MC.player.setSprinting(true);
				if(auraFarming.isChecked())
				{
					if(MC.player.onGround() && !MC.player.isInWater()
						&& !MC.player.isInLava() && !MC.player.isPassenger())
					{
						MC.player.jumpFromGround();
						comboPhase = ComboPhase.JUMPED;
					}
				}else if(attackTarget())
				{
					// Only advance the combo when the swing actually went
					// out - if something else already attacked this tick the
					// state machine must stay where it is.
					releaseForward();
					comboHitCount++;
					stapTicksLeft = 0.1F;
					comboPhase = ComboPhase.STAPPING;
				}
			}
			break;
			
			case JUMPED:
			// Only reached in aura-farming mode; bail to IDLE if it was
			// disabled mid-air.
			if(!auraFarming.isChecked())
			{
				comboPhase = ComboPhase.IDLE;
				break;
			}
			// Stay sprinting forward through the whole airborne phase.
			MC.player.setSprinting(true);
			holdForward();
			MC.player.setSprinting(true);
			if(cooldown >= 1.0F && !MC.player.onGround()
				&& !MC.player.isInWater() && !MC.player.isInLava())
			{
				boolean readyToHit = MC.player.fallDistance == 0F;
				
				if(readyToHit)
				{
					MC.player.setSprinting(true);
					if(attackTarget())
					{
						comboHitCount++;
						releaseForward();
						stapTicksLeft = 0.1F;
						comboPhase = ComboPhase.STAPPING;
					}
				}
			}
			// Safety net: if we landed without getting the hit (e.g. we were
			// blocked mid-air), fall back to IDLE and try again.
			else if(MC.player.onGround() && cooldown >= 1.0F)
			{
				comboPhase = ComboPhase.IDLE;
			}
			break;
			
			case STAPPING:
			// Hold backward briefly to reset the sprint state. The forward
			// key was already released in the JUMPED case at the moment of
			// the hit; this is the s-tap that follows that w-release.
			MC.player.setSprinting(false);
			WURST.getHax().autoSprintHack.setEnabled(false);
			holdBackward();
			MC.player.setSprinting(true);
			WURST.getHax().autoSprintHack.setEnabled(true);
			if(--stapTicksLeft <= 0)
			{
				releaseBackward();
				WURST.getHax().autoSprintHack.setEnabled(true);
				comboPhase = ComboPhase.IDLE;
				WURST.getHax().autoSprintHack.setEnabled(true);
				
			}
			WURST.getHax().autoSprintHack.setEnabled(true);
			break;
		}
	}
	
	private void holdForward()
	{
		MC.options.keyUp.setDown(true);
		forwardKeyForced = true;
	}
	
	private void releaseForward()
	{
		if(!forwardKeyForced)
			return;
		IKeyMapping.get(MC.options.keyUp).resetPressedState();
		forwardKeyForced = false;
	}
	
	private void holdBackward()
	{
		MC.options.keyDown.setDown(true);
		backwardKeyForced = true;
	}
	
	private void releaseBackward()
	{
		if(!backwardKeyForced)
			return;
		IKeyMapping.get(MC.options.keyDown).resetPressedState();
		backwardKeyForced = false;
	}
	
	private void resetCombo()
	{
		comboPhase = ComboPhase.IDLE;
		stapTicksLeft = 0;
		comboHitCount = 0;
		spinRemaining = 0F;
		releaseForward();
		releaseBackward();
	}
	
	// ── AI assist
	// ─────────────────────────────────────────────────────────────
	//
	// Split in two on purpose:
	//
	// updateAi() ships a snapshot of the fight off to Gemini on a background
	// thread and reads back whatever plan last arrived. That round trip takes
	// hundreds of milliseconds, so it can never be the thing that presses a
	// key.
	//
	// updateDodge() runs every tick and does the actual reacting. It decides
	// when a hit is coming and strafes out of the way immediately, using the
	// AI's plan to pick which way to go and how twitchy to be.
	
	/**
	 * Advice older than this describes a fight that has already moved on.
	 *
	 * <p>
	 * Comfortably longer than the slowest allowed AI interval, so a plan stays
	 * usable right up until the next one lands. If this were as short as the
	 * interval, the strategy would keep lapsing back to defaults between
	 * requests and the AI would feel like it was doing nothing.
	 */
	private static final long AI_STALE_NANOS = 20_000_000_000L;
	
	/** How long to wait after a dodge before another one may trigger. */
	private static final int DODGE_COOLDOWN_TICKS = 3;
	
	/** How many opponents to describe to the model. */
	private static final int MAX_OPPONENTS_REPORTED = 6;
	
	private boolean isAiActive()
	{
		return aiEnabled.isChecked() && !resolveApiKey().isEmpty();
	}
	
	/**
	 * The API key from the setting, falling back to an environment variable so
	 * the key doesn't have to sit in the settings file.
	 */
	private String resolveApiKey()
	{
		String key = geminiApiKey.getValue();
		if(key != null && !key.isBlank())
			return key.trim();
		
		String env = System.getenv("BEAST_GEMINI_KEY");
		return env == null ? "" : env.trim();
	}
	
	private void updateAi()
	{
		if(!isAiActive())
			return;
		
		reportAiErrors();
		
		long intervalMs = (long)aiInterval.getValue();
		
		// Building the snapshot walks the entity list, so skip it entirely on
		// the ticks where nothing would be sent anyway.
		if(!advisor.isDueForRequest(intervalMs))
			return;
		
		String model = geminiModel.getValue().trim();
		if(model.isEmpty())
			model = "gemini-3.5-flash-lite";
		
		advisor.request(resolveApiKey(), model, intervalMs, buildSnapshot());
	}
	
	/**
	 * Surfaces API problems without flooding chat: never the same message
	 * twice in a row, and at most one message every
	 * {@value #AI_ERROR_REPORT_INTERVAL_SECONDS} seconds. A flaky connection
	 * produces a steady trickle of timeouts, and nagging about each one is
	 * worse than useless mid-fight.
	 */
	private void reportAiErrors()
	{
		String error = advisor.getLastError();
		if(error == null)
		{
			lastReportedAiError = null;
			return;
		}
		
		long now = System.nanoTime();
		if(error.equals(lastReportedAiError)
			|| now - lastAiErrorReportNanos < AI_ERROR_REPORT_INTERVAL_SECONDS
				* 1_000_000_000L)
			return;
		
		lastReportedAiError = error;
		lastAiErrorReportNanos = now;
		ChatUtils.error("AimAssist AI: " + error);
	}
	
	/**
	 * Describes the current fight for the model. Built on the game thread so
	 * nothing off-thread ever touches the world.
	 */
	private JsonObject buildSnapshot()
	{
		JsonObject root = new JsonObject();
		
		JsonObject self = new JsonObject();
		self.addProperty("health", round(MC.player.getHealth()));
		self.addProperty("max_health", round(MC.player.getMaxHealth()));
		self.addProperty("absorption", round(MC.player.getAbsorptionAmount()));
		self.addProperty("on_ground", MC.player.onGround());
		self.addProperty("sprinting", MC.player.isSprinting());
		self.addProperty("attack_cooldown",
			round(MC.player.getAttackStrengthScale(0)));
		self.addProperty("held_item",
			MC.player.getMainHandItem().getItem().toString());
		self.addProperty("offhand_item",
			MC.player.getOffhandItem().getItem().toString());
		self.addProperty("current_target_id",
			target == null ? -1 : target.getId());
		self.addProperty("last_dodge", lastDodgeDirection.name().toLowerCase());
		root.add("self", self);
		
		JsonArray opponents = new JsonArray();
		Vec3 selfPos = MC.player.position();
		
		entityFilters.applyTo(EntityUtils.getAttackableEntities())
			.filter(e -> e.distanceToSqr(selfPos) <= 256)
			.sorted(Comparator.comparingDouble(e -> e.distanceToSqr(selfPos)))
			.limit(MAX_OPPONENTS_REPORTED)
			.forEach(e -> opponents.add(describeOpponent(e, selfPos)));
		
		root.add("opponents", opponents);
		return root;
	}
	
	private JsonObject describeOpponent(Entity e, Vec3 selfPos)
	{
		JsonObject o = new JsonObject();
		o.addProperty("id", e.getId());
		o.addProperty("name", e.getName().getString());
		o.addProperty("distance", round(
			(float)Math.sqrt(Math.max(0, EntityUtils.distanceToHitboxSq(e)))));
		o.addProperty("is_current_target", e == target);
		
		if(e instanceof LivingEntity living)
		{
			o.addProperty("health", round(living.getHealth()));
			o.addProperty("max_health", round(living.getMaxHealth()));
			o.addProperty("held_item",
				living.getMainHandItem().getItem().toString());
			o.addProperty("blocking", living.isBlocking());
		}
		
		o.addProperty("facing_us", round((float)facingUs(e, selfPos)));
		o.addProperty("closing_speed", round((float)closingSpeed(e, selfPos)));
		o.addProperty("on_our_left", isOnOurLeft(e));
		return o;
	}
	
	/** 1 when the entity is looking straight at us, 0 when looking away. */
	private double facingUs(Entity e, Vec3 selfPos)
	{
		Vec3 toUs = selfPos.subtract(e.position());
		if(toUs.lengthSqr() < 1.0E-6)
			return 0;
		
		return e.getLookAngle().normalize().dot(toUs.normalize());
	}
	
	/** Blocks per tick the entity is closing on us; negative means fleeing. */
	private double closingSpeed(Entity e, Vec3 selfPos)
	{
		Vec3 toUs = selfPos.subtract(e.position());
		if(toUs.lengthSqr() < 1.0E-6)
			return 0;
		
		return e.getDeltaMovement().dot(toUs.normalize());
	}
	
	/**
	 * How far to our left the entity is, from -1 (dead right) through 0
	 * (straight ahead) to 1 (dead left).
	 *
	 * <p>
	 * Minecraft's look vector is {@code (-sin(yaw), _, cos(yaw))}, which makes
	 * the player's left-hand direction {@code (look.z, -look.x)} on the
	 * horizontal plane. Sanity check: facing south (look = 0,0,1) gives a left
	 * of (1,0), i.e. east - which is correct.
	 */
	private double leftness(Entity e)
	{
		Vec3 look = MC.player.getLookAngle();
		Vec3 toThem = e.position().subtract(MC.player.position());
		
		double horizontal =
			Math.sqrt(toThem.x * toThem.x + toThem.z * toThem.z);
		if(horizontal < 1.0E-6)
			return 0;
		
		return (toThem.x * look.z - toThem.z * look.x) / horizontal;
	}
	
	/** Whether the entity sits to the left of where we are looking. */
	private boolean isOnOurLeft(Entity e)
	{
		return leftness(e) > 0;
	}
	
	private static float round(float value)
	{
		return Math.round(value * 100F) / 100F;
	}
	
	/**
	 * Lets the AI move us onto a different opponent - typically off someone
	 * who is running away and onto whoever has started attacking us.
	 */
	private Entity applyAiTargeting(Entity current)
	{
		if(!isAiActive() || !aiTargeting.isChecked() || MC.level == null)
			return current;
		
		AiStrategy strategy = advisor.getStrategy();
		if(!strategy.isFresh(AI_STALE_NANOS))
			return current;
		
		int wantedId = strategy.targetId();
		if(wantedId < 0)
			return current;
		
		Entity wanted = MC.level.getEntity(wantedId);
		if(wanted == null || wanted == current || wanted == MC.player)
			return current;
			
		// The model only gets to suggest - the usual range, filter and
		// line-of-sight rules still decide what is actually attackable.
		return isValidTarget(wanted) ? wanted : current;
	}
	
	/**
	 * The reflex layer. Runs every tick and presses A or D the moment an
	 * attack looks like it is about to land, following the plan the AI last
	 * handed back.
	 */
	private void updateDodge()
	{
		if(!isAiActive() || !aiDodging.isChecked())
		{
			resetDodge();
			return;
		}
		
		if(dodgeCooldown > 0)
			dodgeCooldown--;
		
		// See an in-progress dodge through to the end.
		if(dodgeTicksLeft > 0)
		{
			dodgeTicksLeft--;
			holdDodge(dodgeDirection);
			
			if(dodgeTicksLeft <= 0)
			{
				releaseDodgeKeys();
				dodgeDirection = Dodge.NONE;
				dodgeCooldown = DODGE_COOLDOWN_TICKS;
			}
			return;
		}
		
		releaseDodgeKeys();
		
		if(dodgeCooldown > 0)
			return;
		
		AiStrategy strategy = advisor.getStrategy();
		Entity threat = findMostImmediateThreat(strategy);
		if(threat == null)
			return;
		
		Dodge direction = chooseDodgeDirection(strategy, threat);
		if(direction == Dodge.NONE)
			return;
		
		dodgeDirection = direction;
		lastDodgeDirection = direction;
		dodgeTicksLeft =
			strategy.isFresh(AI_STALE_NANOS) ? strategy.dodgeTicks() : 4;
		holdDodge(direction);
	}
	
	/**
	 * Finds the opponent most likely to hit us in the next moment: close,
	 * looking at us, and either already in melee range or closing fast.
	 *
	 * <p>
	 * The AI's aggression setting decides how early this fires - low
	 * aggression starts dodging from further out, high aggression waits until
	 * a hit is genuinely imminent so we stay on the offensive longer.
	 */
	private Entity findMostImmediateThreat(AiStrategy strategy)
	{
		float aggression = effectiveAggression(strategy);
		
		// 4.6 blocks when playing safe, down to 3.1 when playing aggressive
		double threatRange = 4.6 - aggression * 1.5;
		double threatRangeSq = threatRange * threatRange;
		double facingThreshold = 0.55 + aggression * 0.3;
		
		Vec3 selfPos = MC.player.position();
		Entity best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		// Same filters the hack uses to pick targets, so we don't start juking
		// around passive mobs the user has filtered out.
		for(Entity e : entityFilters
			.applyTo(EntityUtils.getAttackableEntities()).toList())
		{
			double distSq = EntityUtils.distanceToHitboxSq(e);
			if(distSq > threatRangeSq || distSq >= bestDistSq)
				continue;
			
			if(facingUs(e, selfPos) < facingThreshold)
				continue;
			
			// Either already within swinging distance, or running us down.
			if(distSq > 9.0 && closingSpeed(e, selfPos) <= 0.05)
				continue;
			
			best = e;
			bestDistSq = distSq;
		}
		
		return best;
	}
	
	/**
	 * The aggression the reflex layer should actually use.
	 *
	 * <p>
	 * When the model calls for a disengage we don't take over the movement
	 * keys - the AI is only ever allowed to drive A and D - so a disengage is
	 * expressed as maximum caution instead: start dodging as early as the
	 * reflex layer allows.
	 */
	private float effectiveAggression(AiStrategy strategy)
	{
		if(!strategy.isFresh(AI_STALE_NANOS))
			return 0.5F;
		
		return strategy.disengage() ? 0F : strategy.aggression();
	}
	
	/**
	 * Picks which way to strafe. The AI's bias wins when it is fresh and
	 * decisive; otherwise we alternate so we don't juke the same way twice in
	 * a row and become trivially readable.
	 */
	private Dodge chooseDodgeDirection(AiStrategy strategy, Entity threat)
	{
		if(strategy.isFresh(AI_STALE_NANOS)
			&& strategy.dodgeBias() != Dodge.NONE)
			return strategy.dodgeBias();
			
		// No usable advice, so fall back to strafing away from whichever side
		// they are coming from.
		double leftness = leftness(threat);
		if(leftness > 0.25)
			return Dodge.RIGHT;
		if(leftness < -0.25)
			return Dodge.LEFT;
			
		// They are more or less straight ahead, which is the normal case
		// because AimAssist keeps us pointed at them. Alternate so we don't
		// juke the same way every time and become trivially readable.
		return lastDodgeDirection == Dodge.LEFT ? Dodge.RIGHT : Dodge.LEFT;
	}
	
	private void holdDodge(Dodge direction)
	{
		if(direction == Dodge.LEFT)
		{
			releaseRight();
			MC.options.keyLeft.setDown(true);
			leftKeyForced = true;
			
		}else if(direction == Dodge.RIGHT)
		{
			releaseLeft();
			MC.options.keyRight.setDown(true);
			rightKeyForced = true;
		}
	}
	
	private void releaseLeft()
	{
		if(!leftKeyForced)
			return;
		
		IKeyMapping.get(MC.options.keyLeft).resetPressedState();
		leftKeyForced = false;
	}
	
	private void releaseRight()
	{
		if(!rightKeyForced)
			return;
		
		IKeyMapping.get(MC.options.keyRight).resetPressedState();
		rightKeyForced = false;
	}
	
	private void releaseDodgeKeys()
	{
		releaseLeft();
		releaseRight();
	}
	
	private void resetDodge()
	{
		releaseDodgeKeys();
		dodgeTicksLeft = 0;
		dodgeCooldown = 0;
		dodgeDirection = Dodge.NONE;
	}
	
	private void adjustSpacing(double distSq, boolean inComboRange)
	{
		while(!inComboRange)
		{
			if(distSq > FAR_THRESHOLD_SQ)
			{
				releaseBackward();
				MC.player.setSprinting(true);
				holdForward();
				MC.player.setSprinting(true);
			}else if(distSq < CLOSE_THRESHOLD_SQ)
			{
				releaseForward();
				MC.player.setSprinting(false);
				holdBackward();
			}else
			{
				releaseForward();
				releaseBackward();
				MC.player.setSprinting(true);
				return;
			}
			return;
		}
	}
	
	private boolean isCrosshairOnTarget()
	{
		if(target == null)
			return false;
		if(MC.hitResult instanceof EntityHitResult eHit
			&& eHit.getEntity() == target)
			return true;
		// Fall back to a line-of-sight angle check so brief hitResult gaps
		// during the combo (e.g. while jumping) don't falsely reset.
		return RotationUtils.isFacingBox(target.getBoundingBox(),
			range.getValue());
	}
	
	private void updateAuraFarming()
	{
		if(MC.player.onGround() || !auraFarming.isChecked())
		{
			spinRemaining = 0F;
			spinTriggered = false;
			return;
		}
		
		// Only kick off a new spin while we're still on the way up;
		// don't start one once the player has already begun to fall.
		if(spinTriggered || MC.player.fallDistance > 0F)
			return;
		
		spinRemaining = 360F;
		spinTriggered = true;
	}
	
	private enum ComboPhase
	{
		IDLE,
		JUMPED,
		STAPPING
	}
	
	private enum PlayWhen
	{
		WHILE_ENABLED("While enabled"),
		WHILE_TARGETING("While targeting");
		
		private final String name;
		
		PlayWhen(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(target == null)
			return;
		
		long now = System.nanoTime();
		float dt = (now - lastFrameTime) / 1_000_000_000F;
		lastFrameTime = now;
		dt = Math.min(dt, 0.05F);
		
		updateAuraFarming();
		
		// Normal aiming now happens in onUpdate (silently by default). The only
		// thing that still moves the camera here is the cosmetic Aura-Farming
		// spin, which is meant to be seen.
		if(spinRemaining <= 0F)
			return;
		
		Vec3 hitVec = aimAt.getAimPoint(target);
		Rotation needed = RotationUtils.getNeededRotations(hitVec);
		
		float currentYaw = MC.player.getYRot();
		float currentPitch = MC.player.getXRot();
		float maxChange = 3600000000000000000000000000000F * dt;
		
		// Aura-farming spin: drive yaw through a full 360 while keeping pitch
		// locked on the target, so the aim is ready the moment the spin ends.
		// 1440 deg/s completes the spin in ~0.25s, matching the rising phase of
		// a normal jump.
		float spinSpeed = 1440F;
		float delta = Math.min(spinRemaining, spinSpeed * dt);
		spinRemaining -= delta;
		
		float nextPitch = RotationUtils.limitAngleChange(currentPitch,
			needed.pitch(), maxChange);
		
		MC.player.setYRot(currentYaw + delta);
		MC.player.setXRot(nextPitch);
	}
	
	private boolean isValidTarget(Entity e)
	{
		if(e == null || !e.isAlive())
			return false;
		if(EntityUtils.distanceToHitboxSq(e) > range.getValueSq())
			return false;
		if(checkLOS.isChecked()
			&& !BlockUtils.hasLineOfSight(aimAt.getAimPoint(e)))
			return false;
		return entityFilters.applyTo(Stream.of(e)).findAny().isPresent();
	}
	
	private Entity pickTarget(Entity exclude)
	{
		Stream<Entity> stream = EntityUtils.getAttackableEntities();
		
		double rangeSq = range.getValueSq();
		stream =
			stream.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq);
		
		if(exclude != null)
			stream = stream.filter(e -> e != exclude);
		
		if(fov.getValue() < 360.0)
			stream = stream.filter(e -> RotationUtils.getAngleToLookVec(
				aimAt.getAimPoint(e)) <= fov.getValue() / 2.0);
		
		stream = entityFilters.applyTo(stream);
		
		if(checkLOS.isChecked())
			stream = stream
				.filter(e -> BlockUtils.hasLineOfSight(aimAt.getAimPoint(e)));
		
		return stream
			.min(Comparator.comparingDouble(
				e -> RotationUtils.getAngleToLookVec(aimAt.getAimPoint(e))))
			.orElse(null);
	}
	
	private boolean isSwitchKeyDown()
	{
		try
		{
			return InputConstants.isKeyDown(MC.getWindow(),
				InputConstants.getKey(switchTargetKey.getValue()).getValue());
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	private boolean isValidKeybind(String keybind)
	{
		try
		{
			return InputConstants.getKey(keybind) != null;
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	// ── Music playback
	// ────────────────────────────────────────────────────────
	
	private void updateMusicPlayback()
	{
		if(!playMusic.isChecked())
		{
			if(isMusicPlaying())
				stopMusic();
			return;
		}
		boolean shouldPlay =
			playWhen.getSelected() == PlayWhen.WHILE_ENABLED || target != null;
		if(shouldPlay && !isMusicPlaying())
			startMusic();
		else if(!shouldPlay && isMusicPlaying())
			stopMusic();
	}
	
	private boolean isMusicPlaying()
	{
		return musicThread != null && musicThread.isAlive();
	}
	
	private void startMusic()
	{
		musicRunning = true;
		Path file = musicFile.getSelectedFile();
		float vol = (float)musicVolume.getValue() / 100F;
		
		musicThread = new Thread(() -> {
			do
			{
				if(!Files.exists(file))
					break;
				try(AudioInputStream raw =
					AudioSystem.getAudioInputStream(file.toFile()))
				{
					AudioFormat fmt = raw.getFormat();
					// Normalise to PCM_SIGNED 16-bit so any WAV variant plays.
					AudioFormat pcm =
						new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
							fmt.getSampleRate(), 16, fmt.getChannels(),
							fmt.getChannels() * 2, fmt.getSampleRate(), false);
					AudioInputStream pcmIn =
						AudioSystem.getAudioInputStream(pcm, raw);
					DataLine.Info info =
						new DataLine.Info(SourceDataLine.class, pcm);
					try(SourceDataLine line =
						(SourceDataLine)AudioSystem.getLine(info))
					{
						musicLine = line;
						line.open(pcm);
						applyVolume(line, vol);
						line.start();
						byte[] buf = new byte[4096];
						int n;
						while(musicRunning && (n = pcmIn.read(buf)) != -1)
							line.write(buf, 0, n);
						if(musicRunning)
							line.drain();
						line.stop();
						musicLine = null;
					}
				}catch(UnsupportedAudioFileException | LineUnavailableException
					| IOException e)
				{
					break;
				}
			}while(musicRunning && loopMusic.isChecked());
		}, "wurst-aimassist-music");
		musicThread.setDaemon(true);
		musicThread.start();
	}
	
	private void stopMusic()
	{
		musicRunning = false;
		// Stopping the line unblocks any in-progress write() on the music
		// thread so it sees !musicRunning and exits cleanly.
		SourceDataLine line = musicLine;
		if(line != null)
		{
			line.stop();
			musicLine = null;
		}
		if(musicThread != null)
		{
			musicThread.interrupt();
			musicThread = null;
		}
	}
	
	private static void applyVolume(SourceDataLine line, float volume)
	{
		if(!line.isControlSupported(FloatControl.Type.MASTER_GAIN))
			return;
		FloatControl gain =
			(FloatControl)line.getControl(FloatControl.Type.MASTER_GAIN);
		// Convert linear 0–1 to dB; clamp to the control's supported range.
		float db =
			volume <= 0F ? gain.getMinimum() : 20F * (float)Math.log10(volume);
		gain.setValue(
			Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
	}
	
	private static void createDefaultMusicFiles(Path folder)
	{
		Path sample = folder.resolve("sample.wav");
		if(Files.exists(sample))
			return;
		try
		{
			Files.write(sample, generateSampleWav());
		}catch(IOException e)
		{
			// ignore — folder may be read-only
		}
	}
	
	// Generates a short ascending A-minor combat jingle as a raw WAV byte
	// array.
	private static byte[] generateSampleWav()
	{
		int rate = 44100;
		// Each entry: [frequency Hz (0 = silence), duration seconds]
		double[][] melody = {{440.00, 0.12}, // A4
			{523.25, 0.12}, // C5
			{659.25, 0.12}, // E5
			{880.00, 0.28}, // A5
			{0.00, 0.06}, // rest
			{783.99, 0.10}, // G5
			{659.25, 0.10}, // E5
			{523.25, 0.10}, // C5
			{440.00, 0.30}, // A4
			{0.00, 0.20}, // tail silence
		};
		
		int totalSamples = 0;
		for(double[] note : melody)
			totalSamples += (int)(rate * note[1]);
		
		short[] pcm = new short[totalSamples];
		int pos = 0;
		for(double[] note : melody)
		{
			int len = (int)(rate * note[1]);
			double freq = note[0];
			int att = Math.max(1, (int)(rate * 0.010)); // 10 ms attack
			int rel = Math.max(1, (int)(rate * 0.040)); // 40 ms release
			for(int i = 0; i < len; i++, pos++)
			{
				double env = 1.0;
				if(i < att)
					env = (double)i / att;
				else if(i > len - rel)
					env = (double)(len - i) / rel;
				double wave =
					freq > 0 ? Math.sin(2 * Math.PI * freq * i / rate) : 0;
				pcm[pos] = (short)(wave * env * 16383); // half amplitude
			}
		}
		
		int dataBytes = totalSamples * 2; // 16-bit = 2 bytes per sample
		ByteBuffer buf =
			ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
		// RIFF header
		buf.put(new byte[]{'R', 'I', 'F', 'F'});
		buf.putInt(36 + dataBytes);
		buf.put(new byte[]{'W', 'A', 'V', 'E'});
		// fmt chunk
		buf.put(new byte[]{'f', 'm', 't', ' '});
		buf.putInt(16); // chunk size
		buf.putShort((short)1); // PCM
		buf.putShort((short)1); // mono
		buf.putInt(rate);
		buf.putInt(rate * 2); // byte rate
		buf.putShort((short)2); // block align
		buf.putShort((short)16); // bits per sample
		// data chunk
		buf.put(new byte[]{'d', 'a', 't', 'a'});
		buf.putInt(dataBytes);
		for(short s : pcm)
			buf.putShort(s);
		return buf.array();
	}
}
