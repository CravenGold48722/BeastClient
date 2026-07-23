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

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
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
		stopMusic();
	}
	
	@Override
	public void onUpdate()
	{
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
			return;
		}
		
		if(!aimWhileBlocking.isChecked() && MC.player.isUsingItem())
		{
			target = null;
			resetCombo();
			return;
		}
		
		boolean switchKeyDown = isSwitchKeyDown();
		boolean switchRequested = switchKeyDown && !switchKeyDownLastTick;
		switchKeyDownLastTick = switchKeyDown;
		
		if(switchRequested || !isValidTarget(target))
			target = pickTarget(switchRequested ? target : null);
		
		if(target == null)
		{
			resetCombo();
			return;
		}
		WURST.getHax().autoSwordHack.setSlot(target);
		
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
		if(targetAirborne && !airborneHitDone && distToTargetSq <= 8.999991)
		{
			MC.gameMode.attack(MC.player, target);
			MC.player.swing(InteractionHand.MAIN_HAND);
			airborneHitDone = true;
		}
		if(target.onGround())
			airborneHitDone = false;
		
		if(autoCombo.isChecked())
			updateAutoCombo();
		else if(MC.player.getAttackStrengthScale(0) >= 1F
			&& distToTargetSq <= 8.999991)
		{
			MC.gameMode.attack(MC.player, target);
			MC.player.swing(InteractionHand.MAIN_HAND);
		}
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
				}else
				{
					MC.gameMode.attack(MC.player, target);
					MC.player.swing(InteractionHand.MAIN_HAND);
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
					MC.gameMode.attack(MC.player, target);
					MC.player.swing(InteractionHand.MAIN_HAND);
					comboHitCount++;
					releaseForward();
					stapTicksLeft = 0.1F;
					comboPhase = ComboPhase.STAPPING;
					
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
