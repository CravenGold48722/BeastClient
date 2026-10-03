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
import java.util.Random;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.MouseUpdateListener.MouseUpdateEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.KeyPresser;
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
import net.wurstclient.util.HitCheck;
import net.wurstclient.util.HumanAim;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

public final class AimAssistHack extends Hack
	implements UpdateListener, RenderListener, MouseUpdateListener
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
				+ "§lClient-side§r (default) actually turns your camera, so"
				+ " the server sees exactly what you see.\n\n"
				+ "§lServer-side§r keeps your camera still and only aims"
				+ " inside the outgoing movement packet — silent aim, the way"
				+ " LiquidBounce works. Also lets Auto-attack keep hitting"
				+ " during an Aura-Farming spin.\n\n"
				+ "Note: Auto-combo and Aura-Farming always steer the camera"
				+ " while they move you, since you can't sprint toward a target"
				+ " you aren't facing."),
			FaceTarget.CLIENT);
	
	private final CheckboxSetting smoothAim = new CheckboxSetting("Smooth aim",
		"Turns toward the target in continuous steps instead of snapping"
			+ " straight to the needed angle.\n\n"
			+ "Used when the target is further away than"
			+ " §lSmooth aim distance§r, and every time the aim picks up a"
			+ " target - starting, switching, after a kill, after an"
			+ " Aura-Farming spin - until your crosshair is on it. Once it is,"
			+ " the aim snaps within that distance, so close-range tracking"
			+ " stays exact.\n\n"
			+ "How fast it turns is set by §lSmooth aim speed§r. On auto, the"
			+ " speed adjusts itself (a PID controller) to where your crosshair"
			+ " is: a fast pull while it's off the target or about to slip off"
			+ " its edge, a firm one once it's on that keeps re-centering it,"
			+ " up to 720°/s.\n\n"
			+ "It also moves as fast as the target is moving across your"
			+ " view, so the crosshair stays on a moving target instead of"
			+ " trailing behind it.\n\n"
			+ "With §lFace target§r on Client-side the camera turns every"
			+ " frame, like a mouse, instead of 20 times a second - and while"
			+ " AimAssist has a target, your mouse doesn't turn the camera"
			+ " (use §lSwitch target key§r to change targets).",
		true);
	
	private final CheckboxSetting humanizeSmoothAim = new CheckboxSetting(
		"Humanize smooth aim",
		"Makes §lSmooth aim§r move like a hand on a mouse: a short reaction"
			+ " delay before turning to a new target, speeding up at the start"
			+ " and slowing down as it closes in, a speed that drifts a little"
			+ " over time, a slightly curved path, horizontal before vertical,"
			+ " a slight hand tremor while still far off, and a moment's lag"
			+ " picking up the target's changes of direction, like an eye"
			+ " following it.\n\n"
			+ "Off: a straight, snappier pull with none of those.",
		true);
	
	/** The top of the speed slider, shown as "auto". */
	private static final double AUTO_SPEED = 2010;
	
	private final SliderSetting smoothAimSpeed = new SliderSetting(
		"Smooth aim speed",
		"How fast §lSmooth aim§r turns toward the target, in degrees per"
			+ " second. A set speed takes over from the automatic one: the turn"
			+ " then eases up to that speed (or holds it, with"
			+ " §lHumanize smooth aim§r off).\n\n"
			+ "§lauto§r (the end of the slider): the PID controller picks the"
			+ " speed itself from where your crosshair is - fast while off the"
			+ " target, gentle once on it - up to 720°/s.",
		AUTO_SPEED, 360, AUTO_SPEED, 10,
		ValueDisplay.INTEGER.withSuffix("°/s").withLabel(AUTO_SPEED, "auto"))
	{
		@Override
		public float[] getKnobColor()
		{
			// blue while on auto, like the other "auto" sliders
			if(getValue() >= AUTO_SPEED)
				return new float[]{0, 0.5F, 1};
			
			return super.getKnobColor();
		}
	};
	
	private final SliderSetting smoothAimDistance = new SliderSetting(
		"Smooth aim distance",
		"Hitbox distance below which the aim snaps instead of smoothing,"
			+ " once your crosshair has reached the target. Measured the same"
			+ " way as §lRange§r.",
		4, 0, 20, 0.25, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
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
			"Does a 360 spin while airborne on the way up, then turns back to"
				+ " face the target (smoothly, with §lSmooth aim§r on).\n\n"
				+ "With §lFace target§r on Client-side the server sees the"
				+ " real spin, so Auto attack only hits when your crosshair"
				+ " sweeps over the target. On Server-side the attacks keep"
				+ " going through the spin.",
			false);
	
	/**
	 * Only decides which target to pick - see {@link #isValidTarget}.
	 */
	private final FilterFlyingSetting filterFlying =
		FilterFlyingSetting.genericCombat(0);
	
	private final EntityFilterList entityFilters =
		new EntityFilterList(FilterPlayersSetting.genericCombat(false),
			FilterSleepingSetting.genericCombat(false), filterFlying,
			FilterSpeedSetting.genericCombat(100),
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
	
	// ── Dodging
	// ─────────────────────────────────────────────────────────────────
	
	private final CheckboxSetting dodging = new CheckboxSetting("Dodging",
		"Strafes with §lA§r or §lD§r when an opponent looks about to hit"
			+ " you.\n\n"
			+ "The side is picked at random each time so the movement can't be"
			+ " read, and the distance is a random amount between the two"
			+ " sliders below.\n\n"
			+ "If there isn't room to strafe that far on the chosen side, the"
			+ " dodge goes the other way instead.",
		true);
	
	private final SliderSetting minDodgeDistance = new SliderSetting(
		"Min dodge distance", "The shortest a random dodge can be.", 1, 0.25, 8,
		0.25, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting maxDodgeDistance =
		new SliderSetting("Max dodge distance",
			"The longest a random dodge can be.\n\n"
				+ "If this ends up below the minimum, the minimum wins.",
			5, 0.25, 8, 0.25, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private Entity target;
	private boolean switchKeyDownLastTick;
	private long lastFrameTime;
	
	// ── Aim state
	// ─────────────────────────────────────────────────────────────
	
	/**
	 * The smooth, human-like aim (shared with the other aiming hacks). Holds
	 * the rotation AimAssist last aimed with, snapped or smoothed - separately
	 * from the camera, since server-side aiming never moves the player's own
	 * rotation - so smoothing always continues from the last angle that
	 * actually went out.
	 */
	private final HumanAim aim = new HumanAim();
	
	/**
	 * Forces the smooth aim regardless of distance, so a target switch or a
	 * fresh target after a kill is caught up to by turning rather than by
	 * teleporting the angle. Cleared once the crosshair is on the target.
	 */
	private boolean smoothCatchUp;
	
	/**
	 * Set by onUpdate when the camera should be aimed this tick. The actual
	 * turning then happens every frame in onRender, the way a mouse moves the
	 * camera, instead of in 20 jumps per second.
	 */
	private boolean frameAimActive;
	private boolean frameAimForceClient;
	
	/**
	 * Mouse movement by the player since the last tick, summed up frame by
	 * frame. Needed because AimAssist now writes the camera every frame, so
	 * a once-per-tick comparison would only see the last frame's movement.
	 */
	private float steerAccumYaw;
	private float steerAccumPitch;
	private boolean steeringNow;
	
	/** Previous target, for spotting switches and kills. */
	private Entity lastAimedTarget;
	
	/**
	 * Camera rotation right after AimAssist last turned it (or, if it didn't,
	 * as of the last tick). Comparing against it next tick tells how far the
	 * player moved the mouse in between.
	 */
	private float lastCameraYaw;
	private float lastCameraPitch;
	private boolean lastCameraValid;
	
	/**
	 * Mouse movement per tick, in degrees, above which the player counts as
	 * actively turning. A real flick toward another target is far above this;
	 * hand tremor and the aim's own rounding are far below it.
	 */
	private static final float STEER_THRESHOLD = 1.5F;
	
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
	
	// ── Dodge state
	// ────────────────────────────────────────────────────────────
	
	private enum Dodge
	{
		NONE,
		LEFT,
		RIGHT;
		
		private Dodge opposite()
		{
			return this == LEFT ? RIGHT : this == RIGHT ? LEFT : NONE;
		}
	}
	
	private final Random random = new Random();
	
	private Dodge dodgeDirection = Dodge.NONE;
	private boolean leftKeyForced;
	private boolean rightKeyForced;
	private int dodgeCooldown;
	
	/** How far the dodge in progress is trying to travel, in blocks. */
	private double dodgeDistance;
	
	/** Where the dodge in progress started, for measuring how far it got. */
	private Vec3 dodgeStartPos;
	
	/**
	 * Ticks before the dodge in progress gives up.
	 *
	 * <p>
	 * Distance is measured rather than timed, so this only exists to stop a
	 * dodge that can't make progress - shoved against a wall that appeared
	 * mid-strafe, held in place by knockback - from holding the key forever.
	 */
	private int dodgeTicksLeft;
	
	// Music state - volatile because the music thread reads/writes these while
	// the game thread writes/reads them.
	private volatile Thread musicThread;
	private volatile SourceDataLine musicLine;
	
	/** The track that last ended on its own, so it isn't restarted. */
	private volatile Path musicEndedFile;
	
	/**
	 * How fast the smooth aim turns, in degrees per second.
	 *
	 * <p>
	 * 720: a 180 in a quarter of a second (36 degrees per tick) - a fast but
	 * ordinary flick, well inside what a hand does, and quick enough to feel
	 * instant in a fight. The humanized turn uses this as its top speed and
	 * eases in and out below it.
	 *
	 * <p>
	 * Chosen by the user; keep it at 720.
	 */
	private static final float SMOOTH_AIM_SPEED = 720F;
	
	private static final double FAR_THRESHOLD_SQ = 3.01 * 3.01;
	private static final double CLOSE_THRESHOLD_SQ = 0.75 * 0.75;
	
	public AimAssistHack()
	{
		super("AimAssist");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(fov);
		addSetting(aimAt);
		addSetting(faceTarget);
		addSetting(smoothAim);
		addSetting(humanizeSmoothAim);
		addSetting(smoothAimSpeed);
		addSetting(smoothAimDistance);
		addSetting(switchTargetKey);
		addSetting(checkLOS);
		addSetting(aimWhileBlocking);
		addSetting(autoAttack);
		addSetting(autoCombo);
		addSetting(auraFarming);
		
		addSetting(dodging);
		addSetting(minDodgeDistance);
		addSetting(maxDodgeDistance);
		
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
		resetAimState();
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
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(MouseUpdateListener.class, this);
		
		// a track that finished last time may play again
		musicEndedFile = null;
		updateMusicPlayback();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(MouseUpdateListener.class, this);
		target = null;
		resetAimState();
		resetCombo();
		resetDodge();
		stopMusic();
	}
	
	@Override
	public void onUpdate()
	{
		// Exactly one attack is allowed per tick; see attackTarget().
		attackedThisTick = false;
		
		// re-armed below if the camera should be aimed this tick
		frameAimActive = false;
		
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
		
		// Tracked every tick, early returns included, so the baseline is
		// never stale.
		boolean steering = isPlayerSteering();
		
		// don't aim when a container/inventory screen is open
		if(MC.screen instanceof AbstractContainerScreen)
		{
			// picks up again with a smooth turn from wherever the camera is
			aim.invalidate();
			resetCombo();
			resetDodge();
			return;
		}
		
		if(!aimWhileBlocking.isChecked() && MC.player.isUsingItem())
		{
			target = null;
			aim.invalidate();
			resetCombo();
			resetDodge();
			return;
		}
		
		boolean switchKeyDown = isSwitchKeyDown();
		boolean switchRequested = switchKeyDown && !switchKeyDownLastTick;
		switchKeyDownLastTick = switchKeyDown;
		
		if(switchRequested || !isValidTarget(target))
			target = pickTarget(switchRequested ? target : null);
		else if(steering)
		{
			// The player is turning toward something else. Follow their
			// crosshair instead of clinging to the old target: whatever is
			// closest to it now becomes the target. Without this, AimAssist
			// pulled the camera back every tick and the player could never
			// get it onto a target 20-40 degrees away.
			Entity closest = pickTarget(null);
			if(closest != null)
				target = closest;
		}
		
		// A different entity here means the target was switched or the old one
		// died, both of which are caught up to by turning instead of snapping.
		if(target != lastAimedTarget)
		{
			lastAimedTarget = target;
			smoothCatchUp = target != null;
			if(smoothCatchUp)
				aim.startTurn(System.nanoTime());
		}
		
		if(target == null)
		{
			// Nothing to continue from next time we acquire a target; the
			// player is free to look wherever until then.
			aim.invalidate();
			resetCombo();
			resetDodge();
			return;
		}
		WURST.getHax().autoSwordHack.setSlot(target);
		
		updateDodge();
		
		// Apply the aim. Server-side keeps the camera still and only rewrites
		// the rotation in the outgoing movement packet (silent aim, the way
		// LiquidBounce works); the swap happens in onPreMotion/onPostMotion.
		// The cosmetic spin and the movement combo need a real camera angle to
		// sprint toward the target, so those steer client-side instead.
		Vec3 aimPoint = aimAt.getAimPoint(target);
		if(spinRemaining > 0F)
		{
			// Only fake the aim behind the spin when Face target asks for
			// silent aim. Otherwise the server sees the real spin.
			if(faceTarget.getSelected() == FaceTarget.SERVER)
				WURST.getRotationFaker().faceVectorPacket(aimPoint);
			aim.invalidate();
		}else if(steering)
		{
			// Don't fight the mouse while the player is turning. The aim picks
			// up again from wherever they stop - with a smooth turn, see
			// applyAim().
			aim.invalidate();
		}else
		{
			boolean forceClient =
				autoAttack.isChecked() && autoCombo.isChecked();
			
			// Turning the camera happens every frame in onRender, like a
			// mouse. Only the silent and packet modes, which have nothing to
			// show on screen, stay on the tick.
			if(forceClient || faceTarget.getSelected() == FaceTarget.CLIENT)
			{
				frameAimActive = true;
				frameAimForceClient = forceClient;
			}else
				applyAim(aimPoint, getTargetBox(target.getBoundingBox()),
					RotationUtils.getEyesPos(), forceClient);
		}
		
		if(!autoAttack.isChecked())
		{
			resetCombo();
			return;
		}
		
		// Reset the hit timer when the player manually clicks or the
		// crosshair leaves the target. During an aura-farming spin with
		// server-side aim the crosshair is intentionally off-target while the
		// server still sees you facing it, so skip that half of the check
		// there. Without silent aim the server sees the spin too, so only
		// hit when the crosshair actually sweeps over the target.
		boolean silentSpin =
			spinRemaining > 0F && faceTarget.getSelected() == FaceTarget.SERVER;
		if(attackClicked || (!silentSpin && !isCrosshairOnTarget()))
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
	 * Aims at the given point.
	 *
	 * <p>
	 * Within §lSmooth aim distance§r, once the crosshair has reached the
	 * target, it snaps - the old snappy aim, which tracks best in melee
	 * range. Otherwise (further out, and every time the aim picks a target up
	 * until the crosshair is on it) it turns smoothly: at the set
	 * §lSmooth aim speed§r, or on auto with the PID aim (see
	 * {@link HumanAim#pidStep}), whose speed adjusts itself to where the
	 * crosshair is.
	 *
	 * @param forceClient
	 *            ignore §lFace target§r and steer the camera. The
	 *            auto-combo needs a real camera angle to sprint toward the
	 *            target with.
	 */
	private void applyAim(Vec3 aimPoint, AABB targetBox, Vec3 eyes,
		boolean forceClient)
	{
		Rotation needed = RotationUtils.getNeededRotations(eyes, aimPoint);
		long now = System.nanoTime();
		
		if(!aim.isValid())
		{
			aim.seed(MC.player.getYRot(), MC.player.getXRot(), now);
			
			// Picking the aim up again - after an Aura-Farming spin, after a
			// screen - always turns smoothly until the crosshair is on the
			// target, instead of snapping there just because it's close.
			if(!smoothCatchUp)
			{
				smoothCatchUp = true;
				aim.startTurn(now);
			}
		}
		
		// Real elapsed time rather than a flat tick, so the turn keeps the same
		// degrees-per-second under a laggy or sped-up tick loop.
		float dt = aim.takeDt(now);
		
		// kept up to date while snapping too, so it's ready when smoothing
		// takes over
		double[] track = HumanAim.trackingRate(eyes, aimPoint,
			HumanAim.relativeVelocity(target, MC.player));
		aim.updateTracking(track[0], track[1], dt);
		
		boolean smooth = smoothAim.isChecked() && (smoothCatchUp || EntityUtils
			.distanceToHitboxSq(target) > smoothAimDistance.getValueSq());
		
		if(smooth)
		{
			boolean humanize = humanizeSmoothAim.isChecked();
			double speed = smoothAimSpeed.getValue();
			if(speed >= AUTO_SPEED)
				aim.pidStep(needed.yaw(), needed.pitch(), dt, now,
					SMOOTH_AIM_SPEED,
					getAimTolerance(aimPoint, targetBox, eyes), humanize);
			else if(humanize)
				aim.humanStep(needed.yaw(), needed.pitch(), dt, now, speed);
			else
				aim.linearStep(needed.yaw(), needed.pitch(), dt, speed);
				
			// Caught up: the crosshair is on the target and close to the aim
			// point, so switching to snapping (within Smooth aim distance)
			// doesn't jump. Until then the turn stays smooth.
			double left = aim.angleTo(needed.yaw(), needed.pitch());
			if(smoothCatchUp && (left <= 0.5
				|| left <= CATCH_UP_SETTLED && isAimOnTarget(targetBox, eyes)))
				smoothCatchUp = false;
			
		}else
		{
			aim.snap(needed.yaw(), needed.pitch());
			smoothCatchUp = false;
		}
		
		applyAimRotation(forceClient);
	}
	
	/**
	 * How far from the aim point, in degrees, the catch-up turn counts as
	 * settled once the crosshair is on the target.
	 */
	private static final double CATCH_UP_SETTLED = 2;
	
	/**
	 * Whether a ray along the current aim rotation hits the target's hitbox,
	 * i.e. where the crosshair would be on it.
	 */
	private boolean isAimOnTarget(AABB targetBox, Vec3 eyes)
	{
		if(targetBox.contains(eyes))
			return true;
		
		Vec3 look = new Rotation(aim.getYaw(), aim.getPitch()).toLookVec();
		Vec3 end = eyes.add(look.scale(range.getValue() + 2));
		return targetBox.clip(eyes, end).isPresent();
	}
	
	/**
	 * How far, in degrees, the aim can be from the aim point and still be on
	 * the hitbox: the distance from the aim point to the hitbox's nearest
	 * edge, as seen from the eyes.
	 */
	private static double getAimTolerance(Vec3 aimPoint, AABB box, Vec3 eyes)
	{
		Vec3 center = box.getCenter();
		double side = Math.min(box.getXsize(), box.getZsize()) / 2
			- Math.hypot(aimPoint.x - center.x, aimPoint.z - center.z);
		double vertical =
			Math.min(aimPoint.y - box.minY, box.maxY - aimPoint.y);
		double margin = Math.max(Math.min(side, vertical), 0.05);
		double distance = Math.max(eyes.distanceTo(aimPoint), 0.1);
		return Math.toDegrees(Math.atan(margin / distance));
	}
	
	/** Sends the aim rotation the way Face target asks for. */
	private void applyAimRotation(boolean forceClient)
	{
		if(forceClient)
		{
			setClientRotation();
			return;
		}
		
		switch(faceTarget.getSelected())
		{
			case OFF ->
				{
				}
			case SERVER -> WURST.getRotationFaker()
				.faceRotationPacket(aim.getYaw(), aim.getPitch());
			case CLIENT -> setClientRotation();
			case SPAM -> new Rotation(aim.getYaw(), aim.getPitch())
				.sendPlayerLookPacket();
		}
	}
	
	private void setClientRotation()
	{
		// mouse-sized steps, see Rotation.applyToClientPlayer()
		new Rotation(aim.getYaw(), aim.getPitch()).applyToClientPlayer();
		rememberCamera();
	}
	
	/**
	 * Whether the player moved the mouse noticeably during the last tick.
	 * Called once per tick; starts a new tick's worth of counting.
	 */
	private boolean isPlayerSteering()
	{
		absorbMouseMovement();
		// While the mouse is locked (see onMouseUpdate), a camera change can't
		// be yours - it's another hack turning it (MaceAssist, BowAimbot...),
		// which used to make AimAssist let go of its target.
		boolean steering = !isLockingMouse() && isSteerAccumHigh();
		
		steerAccumYaw = 0;
		steerAccumPitch = 0;
		steeringNow = steering;
		return steering;
	}
	
	/**
	 * Adds how far the camera moved since AimAssist last touched it - which
	 * can only be the player's mouse - to this tick's total, and takes the
	 * new baseline.
	 */
	private void absorbMouseMovement()
	{
		if(lastCameraValid)
		{
			steerAccumYaw +=
				Math.abs(Mth.wrapDegrees(MC.player.getYRot() - lastCameraYaw));
			steerAccumPitch += Math.abs(MC.player.getXRot() - lastCameraPitch);
		}
		
		rememberCamera();
	}
	
	private boolean isSteerAccumHigh()
	{
		return steerAccumYaw > STEER_THRESHOLD
			|| steerAccumPitch > STEER_THRESHOLD;
	}
	
	/** The hitbox the crosshair has to be on, as the game's own pick uses. */
	private AABB getTargetBox(AABB box)
	{
		return box.inflate(target.getPickRadius());
	}
	
	private void rememberCamera()
	{
		lastCameraYaw = MC.player.getYRot();
		lastCameraPitch = MC.player.getXRot();
		lastCameraValid = true;
	}
	
	private void resetAimState()
	{
		aim.invalidate();
		smoothCatchUp = false;
		lastAimedTarget = null;
		lastCameraValid = false;
		steerAccumYaw = 0;
		steerAccumPitch = 0;
		steeringNow = false;
		frameAimActive = false;
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
			
		// Only a hit the server can verify - with the rotation it already has
		// as well as the current one, within reach, nothing in the way.
		// Hitting the moment a fast turn landed was a hit through thin air
		// to Matrix's and Grim's HITBOX checks. See HitCheck.
		if(!HitCheck.isVerifiable(target))
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
			// (This used to switch AutoSprint on every tick, which kept it on
			// - saved - after AimAssist was turned off and made it impossible
			// to turn off meanwhile. The combo presses sprint itself.)
			adjustSpacing(distSq, inComboRange);
			if(cooldown >= 1.0F && inComboRange)
			{
				holdForward();
				KeyPresser.press(MC.options.keySprint);
				if(auraFarming.isChecked())
				{
					if(MC.player.onGround() && !MC.player.isInWater()
						&& !MC.player.isInLava() && !MC.player.isPassenger())
					{
						KeyPresser.press(MC.options.keyJump);
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
			// A critical hit needs you falling AND not sprinting (vanilla
			// Player.canCriticalAttack). This used to hit while still rising
			// (fallDistance == 0) with sprint held the whole time, so it never
			// crit. Now: sprint forward on the way up for momentum; once
			// falling, let go of forward, which makes vanilla stop the sprint
			// by itself, and hit as soon as it has.
			boolean falling = isFallingForCrit();
			if(!falling)
			{
				holdForward();
				KeyPresser.press(MC.options.keySprint);
			}else
				releaseForward();
			
			if(cooldown >= 1.0F && !MC.player.onGround()
				&& !MC.player.isInWater() && !MC.player.isInLava())
			{
				boolean readyToHit = falling && !MC.player.isSprinting();
				
				if(readyToHit)
				{
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
			// (AutoSprint used to be switched off and straight back on here
			// in the same tick, which did nothing but rewrite
			// enabled-hacks.json on every hit.)
			holdBackward();
			// No sprint here: a real s-tap is just S. Holding sprint while
			// walking backward is impossible in vanilla and only "works" with
			// Omnidirectional Sprint, which makes it a giveaway.
			KeyPresser.release(MC.options.keySprint);
			if(--stapTicksLeft <= 0)
			{
				releaseBackward();
				comboPhase = ComboPhase.IDLE;
			}
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
	
	// ── Dodging
	// ─────────────────────────────────────────────────────────────────
	//
	// When an opponent looks about to land a hit, pick a side at random and
	// strafe that far out of the way. Randomising the side is the point: a
	// dodge that always breaks the same way is one an opponent learns to read
	// in a couple of exchanges.
	//
	// The distance is measured as it is travelled rather than converted into a
	// tick count up front, because how fast you actually strafe depends on
	// sprinting, sneaking, ice, soul sand, potion effects and knockback.
	
	/** How long to wait after a dodge before another one may trigger. */
	private static final int DODGE_COOLDOWN_TICKS = 3;
	
	/**
	 * How close to the requested distance counts as having arrived. Strafing
	 * covers roughly a tenth of a block per tick, so without a little slack the
	 * dodge would routinely overshoot by most of a tick's worth.
	 */
	private static final double DODGE_ARRIVAL_SLACK = 0.05;
	
	/** Sample spacing when measuring how much room there is to strafe into. */
	private static final double CLEARANCE_STEP = 0.25;
	
	/** A dodge shorter than this isn't worth doing. */
	private static final double MIN_USEFUL_CLEARANCE = 1.0;
	
	/** How far ahead a dodge in progress checks for a wall each tick. */
	private static final double WALL_CHECK_AHEAD = 0.5;
	
	/**
	 * The most ticks a dodge may last. Generous - a slow 8 block strafe runs to
	 * around 80 ticks - because it only exists to catch a dodge that has
	 * stopped making progress entirely.
	 */
	private static final int MAX_DODGE_TICKS = 120;
	
	/** How close an opponent has to be before a dodge is considered. */
	private static final double THREAT_RANGE_SQ = 3.85 * 3.85;
	
	/** How squarely an opponent must be looking at us to count as a threat. */
	private static final double THREAT_FACING_THRESHOLD = 0.7;
	
	/**
	 * Runs every tick. Sees an in-progress dodge through to its distance, and
	 * otherwise decides whether a new one should start.
	 */
	private void updateDodge()
	{
		if(!dodging.isChecked())
		{
			resetDodge();
			return;
		}
		
		if(dodgeCooldown > 0)
			dodgeCooldown--;
		
		if(dodgeDirection != Dodge.NONE)
		{
			continueDodge();
			return;
		}
		
		releaseDodgeKeys();
		
		if(dodgeCooldown > 0)
			return;
		
		if(findMostImmediateThreat() != null)
			startDodge();
	}
	
	/**
	 * Keeps the strafe key held until the dodge has covered its distance or run
	 * out of ticks.
	 */
	private void continueDodge()
	{
		if(dodgeStartPos == null)
		{
			endDodge();
			return;
		}
		
		double travelled =
			horizontalDistance(MC.player.position(), dodgeStartPos);
		
		if(--dodgeTicksLeft <= 0
			|| travelled >= dodgeDistance - DODGE_ARRIVAL_SLACK)
		{
			endDodge();
			return;
		}
		
		// A and D are relative to where the player is facing, and AimAssist
		// keeps turning us to track the target, so the direction the dodge is
		// actually travelling drifts away from the one that was measured for
		// room at the start. Re-check the ground just ahead each tick rather
		// than trusting that first measurement for the whole strafe.
		if(clearance(dodgeDirection, WALL_CHECK_AHEAD) < WALL_CHECK_AHEAD)
		{
			endDodge();
			return;
		}
		
		holdDodge(dodgeDirection);
	}
	
	/**
	 * Picks a side and a distance at random, then checks there is actually room
	 * for it. If the chosen side is walled off short of that distance the dodge
	 * goes the other way instead, and if neither side has room it takes
	 * whichever has more and settles for that.
	 */
	private void startDodge()
	{
		double distance = randomDodgeDistance();
		Dodge chosen = random.nextBoolean() ? Dodge.LEFT : Dodge.RIGHT;
		
		double chosenRoom = clearance(chosen, distance);
		if(chosenRoom >= distance)
		{
			beginDodge(chosen, distance);
			return;
		}
		
		Dodge other = chosen.opposite();
		double otherRoom = clearance(other, distance);
		if(otherRoom >= distance)
		{
			beginDodge(other, distance);
			return;
		}
		
		// Boxed in on both sides. Take the roomier one and go as far as it
		// allows, unless that is so short it wouldn't move us out of the way.
		Dodge roomier = otherRoom > chosenRoom ? other : chosen;
		double room = Math.max(otherRoom, chosenRoom);
		
		if(room >= MIN_USEFUL_CLEARANCE)
			beginDodge(roomier, room);
	}
	
	private void beginDodge(Dodge direction, double distance)
	{
		dodgeDirection = direction;
		dodgeDistance = distance;
		dodgeStartPos = MC.player.position();
		dodgeTicksLeft = MAX_DODGE_TICKS;
		holdDodge(direction);
	}
	
	private void endDodge()
	{
		releaseDodgeKeys();
		dodgeDirection = Dodge.NONE;
		dodgeStartPos = null;
		dodgeDistance = 0;
		dodgeTicksLeft = 0;
		dodgeCooldown = DODGE_COOLDOWN_TICKS;
	}
	
	/** A random distance between the two sliders, in blocks. */
	private double randomDodgeDistance()
	{
		double min = minDodgeDistance.getValue();
		double max = Math.max(min, maxDodgeDistance.getValue());
		
		return min + random.nextDouble() * (max - min);
	}
	
	/**
	 * How far the player could strafe in the given direction before hitting
	 * something, capped at the distance being asked for.
	 *
	 * <p>
	 * The player's whole hitbox is swept along in steps rather than a single
	 * ray being cast, so a knee-high ledge or a low overhang counts as a wall
	 * the same way it would when you walked into it.
	 */
	private double clearance(Dodge direction, double distance)
	{
		if(MC.level == null)
			return distance;
		
		Vec3 step = strafeVector(direction);
		AABB box = MC.player.getBoundingBox();
		double clear = 0;
		
		// The endpoint is tested exactly rather than being left to fall off the
		// end of the sampling grid, so a wall sitting in the last part-step
		// still counts.
		for(double d = CLEARANCE_STEP;; d += CLEARANCE_STEP)
		{
			double at = Math.min(d, distance);
			
			AABB moved = box.move(step.x * at, 0, step.z * at);
			if(!MC.level.noCollision(MC.player, moved))
				return clear;
			
			clear = at;
			if(at >= distance)
				return distance;
		}
	}
	
	/**
	 * A unit vector pointing left or right of where the player is facing.
	 *
	 * <p>
	 * Minecraft's look vector is {@code (-sin(yaw), _, cos(yaw))}, which makes
	 * the player's left {@code (cos(yaw), _, sin(yaw))}. Sanity check: at yaw 0
	 * the player faces south and this gives east, which is correct.
	 */
	private Vec3 strafeVector(Dodge direction)
	{
		double yaw = Math.toRadians(MC.player.getYRot());
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		
		return direction == Dodge.LEFT ? left : left.scale(-1);
	}
	
	private static double horizontalDistance(Vec3 a, Vec3 b)
	{
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		
		return Math.sqrt(dx * dx + dz * dz);
	}
	
	/**
	 * Finds the opponent most likely to hit us in the next moment: close,
	 * looking at us, and either already in melee range or closing fast.
	 */
	private Entity findMostImmediateThreat()
	{
		Vec3 selfPos = MC.player.position();
		Entity best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		// Same filters the hack uses to pick targets, so we don't start juking
		// around passive mobs the user has filtered out.
		for(Entity e : entityFilters
			.applyTo(EntityUtils.getAttackableEntities()).toList())
		{
			double distSq = EntityUtils.distanceToHitboxSq(e);
			if(distSq > THREAT_RANGE_SQ || distSq >= bestDistSq)
				continue;
			
			if(facingUs(e, selfPos) < THREAT_FACING_THRESHOLD)
				continue;
			
			// Either already within swinging distance, or running us down.
			if(distSq > 9.0 && closingSpeed(e, selfPos) <= 0.05)
				continue;
			
			best = e;
			bestDistSq = distSq;
		}
		
		return best;
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
		dodgeDirection = Dodge.NONE;
		dodgeStartPos = null;
		dodgeDistance = 0;
		dodgeTicksLeft = 0;
		dodgeCooldown = 0;
	}
	
	/** Walks into combo range: forward if too far, back if too close. */
	private void adjustSpacing(double distSq, boolean inComboRange)
	{
		if(inComboRange)
			return;
		
		if(distSq > FAR_THRESHOLD_SQ)
		{
			releaseBackward();
			holdForward();
			KeyPresser.press(MC.options.keySprint);
			
		}else if(distSq < CLOSE_THRESHOLD_SQ)
		{
			releaseForward();
			holdBackward();
		}
	}
	
	/** Falling, the way a critical hit needs (Player.canCriticalAttack). */
	private boolean isFallingForCrit()
	{
		return MC.player.fallDistance > 0 && !MC.player.onGround()
			&& MC.player.getDeltaMovement().y < 0;
	}
	
	/**
	 * Whether AimAssist needs the sprint key let go right now - during the
	 * s-tap and while falling into a critical hit. AutoSprint checks this, so
	 * it doesn't press sprint again in the same tick and undo the reset.
	 */
	public boolean isHoldingSprintOff()
	{
		if(!isEnabled() || !autoAttack.isChecked() || !autoCombo.isChecked())
			return false;
		
		return comboPhase == ComboPhase.STAPPING
			|| comboPhase == ComboPhase.JUMPED && isFallingForCrit();
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
		
		// Camera aiming happens here, every frame. (Silent and packet aiming
		// only matter once per tick and stay in onUpdate.)
		if(spinRemaining <= 0F)
		{
			aimFrame(partialTicks);
			return;
		}
		
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
		
		new Rotation(currentYaw + delta, nextPitch).applyToClientPlayer();
		
		// the spin is AimAssist turning the camera, not the player steering
		rememberCamera();
	}
	
	/**
	 * Turns the camera toward the target for this frame. A mouse moves the
	 * camera every frame, so doing it once per tick looked like 20 small jumps
	 * a second no matter how smooth the turn itself was.
	 */
	private void aimFrame(float partialTicks)
	{
		if(!frameAimActive)
			return;
			
		// The player grabbing the mouse in the middle of a tick stops the aim
		// right away, not only at the next tick.
		absorbMouseMovement();
		if(!isLockingMouse() && (steeringNow || isSteerAccumHigh()))
		{
			steeringNow = true;
			aim.invalidate();
			return;
		}
		
		// aim at where the target is drawn in this frame, not where it was at
		// the last tick
		Vec3 offset = EntityUtils.getLerpedPos(target, partialTicks)
			.subtract(target.position());
		Vec3 aimPoint = aimAt.getAimPoint(target).add(offset);
		AABB box = getTargetBox(target.getBoundingBox().move(offset));
		
		// ...and from where your eyes are drawn in this frame. Mixing in the
		// tick position here made the aim wobble every tick while moving.
		Vec3 eyes = EntityUtils.getLerpedPos(MC.player, partialTicks).add(0,
			MC.player.getEyeHeight(MC.player.getPose()), 0);
		
		applyAim(aimPoint, box, eyes, frameAimForceClient);
	}
	
	/**
	 * While AimAssist has a target and is turning your camera, the aim is its
	 * job: your mouse doesn't turn the camera until the target is gone (dead,
	 * out of range or sight, filtered out) or AimAssist is turned off. Before,
	 * nudging the mouse to follow a target that had drifted a little off the
	 * crosshair counted as "steering" and made AimAssist let go of it.
	 * Use the switch target key to change targets.
	 */
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		if(!isLockingMouse())
			return;
		
		event.setDeltaX(0);
		event.setDeltaY(0);
	}
	
	private boolean isLockingMouse()
	{
		if(target == null || MC.player == null)
			return false;
			
		// Silent aim never turns the camera - locking it would just freeze
		// your view.
		return faceTarget.getSelected() == FaceTarget.CLIENT
			|| autoAttack.isChecked() && autoCombo.isChecked()
			|| spinRemaining > 0F;
	}
	
	private boolean isValidTarget(Entity e)
	{
		// also drops a target left over from the previous world or dimension
		if(e == null || !e.isAlive() || e.level() != MC.level)
			return false;
		if(EntityUtils.distanceToHitboxSq(e) > range.getValueSq())
			return false;
		if(checkLOS.isChecked()
			&& !BlockUtils.hasLineOfSight(aimAt.getAimPoint(e)))
			return false;
			
		// The flying filter only decides which target to pick. A target that
		// was locked on the ground stays locked while it jumps, gets knocked
		// up (by your own hits, too) or steps off a ledge - with "Filter
		// flying" set, AimAssist used to let go of it the moment it left
		// the ground.
		return entityFilters.testOneIgnoring(e, filterFlying);
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
			InputConstants.Key key =
				InputConstants.getKey(switchTargetKey.getValue());
			int code = key.getValue();
			
			// GLFW logs a "GL ERROR" for every invalid key code it's asked
			// about, so unbound keys and mouse buttons need their own path.
			if(code == InputConstants.UNKNOWN.getValue())
				return false;
			if(key.getType() == InputConstants.Type.MOUSE)
				return GLFW.glfwGetMouseButton(MC.getWindow().handle(),
					code) == GLFW.GLFW_PRESS;
			
			return InputConstants.isKeyDown(MC.getWindow(), code);
			
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
			musicEndedFile = null;
			return;
		}
		boolean shouldPlay =
			playWhen.getSelected() == PlayWhen.WHILE_ENABLED || target != null;
		if(!shouldPlay)
		{
			if(isMusicPlaying())
				stopMusic();
			musicEndedFile = null;
			return;
		}
		
		// Once a track has ended on its own (finished with Loop music off, or
		// the file is missing or unreadable), don't start it again until
		// something changes - otherwise "Loop music" off still looped, and a
		// bad file was retried on a fresh thread every tick.
		Path file = musicFile.getSelectedFile();
		if(!isMusicPlaying() && !file.equals(musicEndedFile))
			startMusic(file);
	}
	
	private boolean isMusicPlaying()
	{
		return musicThread != null && musicThread.isAlive();
	}
	
	private void startMusic(Path file)
	{
		musicEndedFile = null;
		float vol = (float)musicVolume.getValue() / 100F;
		
		Thread thread = new Thread(() -> {
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
						while(isCurrentMusicThread()
							&& (n = pcmIn.read(buf)) != -1)
							line.write(buf, 0, n);
						if(isCurrentMusicThread())
							line.drain();
						line.stop();
						
						// only clear our own line, never a newer thread's
						if(musicLine == line)
							musicLine = null;
					}
				}catch(UnsupportedAudioFileException | LineUnavailableException
					| IOException | IllegalArgumentException e)
				{
					break;
				}
			}while(isCurrentMusicThread() && loopMusic.isChecked());
			
			// ended by itself rather than by stopMusic()
			if(isCurrentMusicThread())
				musicEndedFile = file;
		}, "wurst-aimassist-music");
		thread.setDaemon(true);
		musicThread = thread;
		thread.start();
	}
	
	/**
	 * Each music thread checks whether it is still the one that should be
	 * playing. A single shared "running" flag let an old thread that hadn't
	 * finished stopping yet see the flag set again by its replacement and
	 * keep playing alongside it.
	 */
	private boolean isCurrentMusicThread()
	{
		return musicThread == Thread.currentThread();
	}
	
	private void stopMusic()
	{
		// From here on the old thread is no longer current, so it stops at its
		// next check.
		Thread thread = musicThread;
		musicThread = null;
		
		// Stopping and flushing the line makes an in-progress write() return,
		// so the thread gets to that check right away.
		SourceDataLine line = musicLine;
		if(line != null)
		{
			line.stop();
			line.flush();
			musicLine = null;
		}
		if(thread != null)
			thread.interrupt();
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
