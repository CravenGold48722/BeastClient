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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.MouseUpdateListener.MouseUpdateEvent;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.GameClock;
import net.wurstclient.util.KeyPresser;
import net.wurstclient.util.PacketOrder;
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
import net.wurstclient.util.AimFriction;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.HitCheck;
import net.wurstclient.util.HumanAim;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

public final class AimAssistHack extends Hack implements UpdateListener,
	RenderListener, MouseUpdateListener, PacketInputListener
{
	/**
	 * Targets this much closer than the closest one count as equally close,
	 * so the one with the least health is picked among them. Blocks.
	 */
	private static final double DISTANCE_TIE = 0.4;
	
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
			+ " Aura-Farming spin, after you pulled the camera away - until"
			+ " your crosshair is on it. Once it is, the aim snaps within that"
			+ " distance, so close-range tracking stays exact.\n\n"
			+ "How fast it turns is set by §lSmooth aim speed§r. On auto it"
			+ " moves like a hand flicking back onto the target: up to"
			+ " 340°/s (more for big turns), then it closes half of what's"
			+ " left every 1/30 s and lands on the aim point without"
			+ " overshooting.\n\n"
			+ "It also moves as fast as the target is moving across your"
			+ " view, so the crosshair stays on a moving target instead of"
			+ " trailing behind it.\n\n"
			+ "With §lFace target§r on Client-side the camera turns every"
			+ " frame, like a mouse, instead of 20 times a second. Your mouse"
			+ " still turns it too, slowed down near the target (see"
			+ " §lStickiness§r) - pull away and the aim brings it back once"
			+ " you stop.",
		true);
	
	private final SliderSetting stickiness = new SliderSetting("Stickiness",
		"While AimAssist turns your camera, how much your own mouse is slowed"
			+ " down near the target: pulling away, the crosshair first only"
			+ " creeps off it, a real flick breaks free, and once you stop the"
			+ " aim brings it back. Closing in it's slowed less.\n\n"
			+ "0%: your mouse turns the camera at full speed, the aim still"
			+ " pulls it back.",
		0.75, 0, 0.95, 0.05, ValueDisplay.PERCENTAGE);
	
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
			+ "§lauto§r (the end of the slider): moves like a hand flicking"
			+ " onto the target - up to 340°/s (more for big turns), then"
			+ " closing half of what's left every 1/30 s.",
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
	
	private final SliderSetting attackerMemory = new SliderSetting(
		"Attacker memory",
		"How long an entity counts as attacking you after it last hurt you.\n\n"
			+ "Targets are picked in this order: whoever is attacking you,"
			+ " then whoever is closest, then whoever has the least health"
			+ " (when the distances are within half a block).\n\n"
			+ "Once locked, AimAssist only switches when the target dies,"
			+ " leaves the range or line of sight, when a different entity"
			+ " attacks you while the target isn't, or when another entity"
			+ " that's attacking you gets more than half a block closer than"
			+ " the target. A closer entity that isn't attacking you never"
			+ " steals the lock.",
		4, 1, 15, 0.5, ValueDisplay.DECIMAL.withSuffix("s"));
	
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
		"Holds the target in a combo with sprint-knockback hits on the ground"
			+ " and crits in the air (every hit waits until the server sees"
			+ " you sprinting - or, for a crit, falling and not sprinting):\n"
			+ "- hits as soon as the knockback is guaranteed (just past 90%"
			+ " charge), not only at a full cooldown\n"
			+ "- hold W, hit, release W: lets go of W right after each hit,"
			+ " coasts, and only holds W again just before the next one, so"
			+ " you don't run into their reach\n"
			+ "- resets sprint with an S-tap when the target comes at you"
			+ " (makes room) and a W-tap when it backs off (keeps up); if"
			+ " it's neither, an S-tap only when it's too close\n"
			+ "- keeps W and sprint going when the crosshair slips off the"
			+ " target for a moment, so the next hit isn't late\n"
			+ "- never hits a raised shield (see §lShield breaker§r) and"
			+ " doesn't walk into a target stuck in a cobweb - crits it"
			+ " instead\n"
			+ "- takes every chance at a sprint hit or a crit, unless a"
			+ " setting holds it back (§lSkip falling targets§r, §lBaits§r,"
			+ " §lAura-Farming§r, §lShield breaker§r)\n"
			+ "- in the air (a jump reset, a drop): holds W while rising,"
			+ " lets go once falling and crits\n"
			+ "- deflects: sprint-hits right back when the target hits you\n"
			+ "- against a very laggy player (200+ ms), just keeps hitting at"
			+ " full speed\n\n"
			+ "When §lAura-Farming§r is enabled, jumps and crits on the way"
			+ " down.\n\n" + "Requires §lAuto attack§r to be enabled.\n\n"
			+ "The combo is reset whenever you look clearly away from the"
			+ " target (45°+) or click the attack button manually.",
		false);
	
	private final CheckboxSetting skipFalling = new CheckboxSetting(
		"Skip falling targets",
		"With §lAuto attack§r: doesn't hit a target that's falling (that"
			+ " knocks them into the ground and lets them rebuild momentum"
			+ " first) - waits until they land - but hits them at once while"
			+ " they're still going up, so a jump reset that came too early"
			+ " gets them launched. A very laggy player (200+ ms) is hit"
			+ " anyway.\n\n"
			+ "Off: every chance at a sprint hit or crit is taken, falling"
			+ " target or not.",
		false);
	
	private final CheckboxSetting jumpReset = new CheckboxSetting("Jump reset",
		"Jumps the moment the target hits you while you're on the ground."
			+ " The forward boost of a sprint jump cancels most of the"
			+ " knockback, so you stay close enough to hit back.\n\n"
			+ "Reacts to the actual hit, so delaying a hit to catch a jump"
			+ " reset doesn't work against it.\n\n"
			+ "With §lAuto combo§r it also holds W and sprint for it; without,"
			+ " it only jumps when you're already sprinting forward.\n\n"
			+ "When the target is already too close for that (under 2"
			+ " blocks), the combo S-taps instead and deflects from there.",
		true);
	
	// The techniques from the combo guides (../howtocombotranscript*.txt),
	// each its own switch. They all need Auto combo.
	
	private final CheckboxSetting flyOuts = new CheckboxSetting("Fly-outs",
		"With §lAuto combo§r: when the target's next hit is due before yours"
			+ " (from its last swing and its weapon's attack speed), holds S"
			+ " and jumps right before it lands, so its knockback carries you"
			+ " out of the trade - then lets go of S again, so you can build"
			+ " up speed before the next trade. Only after you've hit it"
			+ " yourself.",
		true);
	
	private final CheckboxSetting sprintCrits =
		new CheckboxSetting("Sprint crits",
			"With §lAuto combo§r: when you're falling and no longer sprinting"
				+ " (your last sprint hit ended it), keeps W held for the crit"
				+ " instead of letting go - more distance covered, and punish"
				+ " crits can be chained.",
			true);
	
	private final CheckboxSetting hopCrits = new CheckboxSetting("Hop crits",
		"With §lAuto combo§r: hops right after landing a deflection on the"
			+ " ground and crits on the way down. If the target jump-resets"
			+ " against your deflection, it jumps right into the crit.",
		true);
	
	private final CheckboxSetting uppercuts = new CheckboxSetting("Uppercuts",
		"With §lAuto combo§r: when the target is just out of reach and your"
			+ " hit is ready, sprint-jumps and sprint-hits on the way up -"
			+ " the jump's speed boost reaches a bit further.",
		true);
	
	private final CheckboxSetting lunges = new CheckboxSetting("Lunges",
		"With §lAuto combo§r: when the target backs out of the trade (3.5 to"
			+ " 6 blocks away and moving off), sprint-jumps after it once"
			+ " you've been sprinting for half a second (full speed), and"
			+ " hits on the way down.",
		true);
	
	private final CheckboxSetting doubleCrits = new CheckboxSetting(
		"Double crits",
		"With §lAuto combo§r: after a hit from up close (where the target"
			+ " would rather punish-crit than deflect), jumps straight up at"
			+ " once, holds S and crits on the way down.\n\n"
			+ "A prediction: if the target deflects instead, you get"
			+ " stunned.",
		false);
	
	private final CheckboxSetting hops = new CheckboxSetting("Hops",
		"With §lAuto combo§r: instead of backing off when you'd get too close"
			+ " between hits, hops (jump and W, never both at once) and"
			+ " sprint-hits on the way down - targets expecting a crit tend"
			+ " to stay in the combo.",
		false);
	
	private final CheckboxSetting baits = new CheckboxSetting("Baits",
		"With §lAuto combo§r: when the target jumps at you from just out of"
			+ " reach, sometimes (at random, to stay unpredictable) holds S"
			+ " so its crit misses.",
		false);
	
	private final CheckboxSetting sprintSweeps =
		new CheckboxSetting("Sprint sweeps",
			"With §lAuto combo§r: when the target backs out right after your"
				+ " sprint hit, keeps W held without a sprint reset and hits"
				+ " again unsprinted - less knockback, so it can't fly out of"
				+ " the trade.\n\n" + "These hits aren't sprint hits or crits.",
			false);
	
	private final CheckboxSetting weakHits = new CheckboxSetting("Weak hits",
		"With §lAuto combo§r: squeezes in a weak (uncharged) hit when the"
			+ " target is about to leave your reach and your next real hit"
			+ " is still a while off.\n\n"
			+ "These hits aren't sprint hits or crits.",
		false);
	
	// From VexBot's sword AI (a server-side PvP bot; ideas, not its code).
	
	private final CheckboxSetting punishOpenings =
		new CheckboxSetting("Punish openings",
			"With §lAuto combo§r: sprint-hits at once, whatever the spacing,"
				+ " when the target has just swung (hit or miss - it can't hit"
				+ " again for a while) or just lowered its shield.",
			true);
	
	private final CheckboxSetting shieldBreaker =
		new CheckboxSetting("Shield breaker",
			"When the target holds up a shield toward you: switches to an axe"
				+ " from your hotbar, hits once (that disables the shield for 5"
				+ " seconds) and switches back. Without an axe, waits for the"
				+ " shield to come down instead of hitting it - a hit on a"
				+ " shield does nothing and knocks you back.",
			true);
	
	private final CheckboxSetting crouchDodge =
		new CheckboxSetting("Crouch dodge",
			"With §lAuto combo§r: crouches for a moment when the target drops"
				+ " onto you for a crit and your own hit isn't ready - the"
				+ " smaller hitbox makes it miss more often.",
			true);
	
	private final CheckboxSetting comboStrafe = new CheckboxSetting(
		"Combo strafe",
		"With §lAuto combo§r: strafes (A or D) while in reach between hits,"
			+ " switching sides after every hit - harder to hit back, and"
			+ " strafing moves you toward the target a bit slower, which fine-"
			+ "tunes the spacing. Not while §lDodging§r dodges.",
		false);
	
	private final CheckboxSetting shieldBlock = new CheckboxSetting(
		"Shield block",
		"With §lAuto combo§r and a shield in your offhand: raises it when the"
			+ " target's next hit is due before yours (shields take a"
			+ " quarter second to come up) and lowers it in time for your"
			+ " own hit. Not against axes - they disable shields.",
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
	
	/**
	 * Entity ID -> System.nanoTime() of when it last hurt you, from the
	 * server's damage events. Written on the network thread.
	 */
	private final ConcurrentHashMap<Integer, Long> lastHurtBy =
		new ConcurrentHashMap<>();
	private ClientLevel attackersLevel;
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
	
	/** Slows your mouse down near the target (Stickiness). */
	private final AimFriction friction = new AimFriction();
	
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
	private int resetTicksLeft;
	private boolean resetWithS;
	private float spinRemaining;
	private boolean spinTriggered;
	private int comboHitCount;
	
	/** Ticks AimAssist has run, for the short timers below. */
	private int tickCount;
	
	/**
	 * The tick of something that hasn't happened yet: far in the past, but
	 * not Integer.MIN_VALUE - `tickCount - lastX` overflowed with that, so
	 * every `> N ticks since` check failed until the first time (Crouch
	 * dodge, Fly-outs and Weak hits could never happen; found 2026-10-17).
	 */
	private static final int NEVER = -1_000_000;
	
	/**
	 * Until this tick a hit goes out as a deflection: right after the target
	 * hit you, whatever the spacing or the target's fall.
	 */
	private int deflectUntilTick = NEVER;
	
	/** Ticks of S left before an S-tap deflection (too close to deflect). */
	private int deflectSTapTicks;
	
	/** When the combo last hit the target (tickCount), for fly-outs. */
	private int lastHitTick = NEVER;
	
	/** What the jump in progress is for; decides the keys in the air. */
	private AirMode airMode = AirMode.NONE;
	
	/** A fly-out in progress: ticks so far, and whether the hit came. */
	private int lastFlyOutTick = NEVER;
	private int flyOutTicks;
	private boolean flyOutHit;
	
	/** Ticks of S left for a bait, and the target jump it was decided on. */
	private int baitTicks;
	private boolean baitDecided;
	
	/** Chasing a target that backs out with unsprinted hits. */
	private boolean sweepChase;
	
	/** When the last weak hit went out (tickCount). */
	private int lastWeakHitTick = NEVER;
	
	/** Ticks in a row sprinting forward on the ground (full speed: ~10). */
	private int sprintTicks;
	
	/** Whether the combo let go of sprint this tick (see AutoSprint). */
	private boolean sprintHeldOff;
	
	/** The target's last swing (tickCount) and what its swing looked like. */
	private int targetSwingTick = NEVER;
	private boolean targetWasSwinging;
	private int targetLastSwingTime;
	private Entity swingTracked;
	
	/** The target's movement per tick (see trackTargetMotion). */
	private Vec3 targetMotion = Vec3.ZERO;
	private Vec3 lastTargetPos;
	private Entity motionTracked;
	
	/** Until this tick a hit goes out like a deflection (Punish openings). */
	private int punishUntilTick = NEVER;
	private boolean targetWasBlocking;
	
	/**
	 * Shield breaker: 0 idle, 1 switched to the axe (hit next), 2 hit done
	 * (switch back next). The slot to go back to.
	 */
	private int shieldBreakStage;
	private int shieldBreakPrevSlot = -1;
	private int shieldBreakTicks;
	
	/** When the last crouch dodge started (tickCount). */
	private int lastCrouchDodgeTick = NEVER;
	
	/** Combo strafe: which side, flipped after every hit. */
	private boolean strafeLeft;
	private boolean comboStrafing;
	
	/** Shield block: holding the use key, and for how long. */
	private boolean shieldBlocking;
	private int shieldBlockTicks;
	
	/** Further than this from the target, the player looked away. */
	private static final double LOOKED_AWAY_DEGREES = 45;
	
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
	 * 340 (user, 2026-10-16: "make the aim like the video"): the reference
	 * video's 45-degree returns onto the target cruise at 11 degrees per
	 * 1/30 s at FOV 70. Bigger turns get more (see applyAim) - a hand flicks
	 * a long way faster. (It was 720, chosen by the user before the video.)
	 */
	private static final float SMOOTH_AIM_SPEED = 340F;
	
	public AimAssistHack()
	{
		super("AimAssist");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(fov);
		addSetting(aimAt);
		addSetting(faceTarget);
		addSetting(smoothAim);
		addSetting(stickiness);
		addSetting(humanizeSmoothAim);
		addSetting(smoothAimSpeed);
		addSetting(smoothAimDistance);
		addSetting(switchTargetKey);
		addSetting(attackerMemory);
		addSetting(checkLOS);
		addSetting(aimWhileBlocking);
		addSetting(autoAttack);
		addSetting(autoCombo);
		addSetting(skipFalling);
		addSetting(jumpReset);
		addSetting(flyOuts);
		addSetting(sprintCrits);
		addSetting(hopCrits);
		addSetting(uppercuts);
		addSetting(lunges);
		addSetting(doubleCrits);
		addSetting(hops);
		addSetting(baits);
		addSetting(sprintSweeps);
		addSetting(weakHits);
		addSetting(punishOpenings);
		addSetting(shieldBreaker);
		addSetting(crouchDodge);
		addSetting(comboStrafe);
		addSetting(shieldBlock);
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
		lastHurtBy.clear();
		lastFrameTime = GameClock.nanoTime();
		resetAimState();
		comboPhase = ComboPhase.IDLE;
		attackKeyDownLastTick = false;
		forwardKeyForced = false;
		backwardKeyForced = false;
		resetTicksLeft = 0;
		comboHitCount = 0;
		deflectUntilTick = NEVER;
		spinRemaining = 0F;
		spinTriggered = false;
		attackedThisTick = false;
		resetCombo();
		lastHitTick = NEVER;
		lastWeakHitTick = NEVER;
		sprintTicks = 0;
		swingTracked = null;
		motionTracked = null;
		lastTargetPos = null;
		targetMotion = Vec3.ZERO;
		punishUntilTick = NEVER;
		lastFlyOutTick = NEVER;
		targetWasBlocking = false;
		shieldBreakStage = 0;
		shieldBreakPrevSlot = -1;
		lastCrouchDodgeTick = NEVER;
		comboStrafing = false;
		shieldBlocking = false;
		shieldBlockTicks = 0;
		resetDodge();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(MouseUpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		
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
		EVENTS.remove(PacketInputListener.class, this);
		lastHurtBy.clear();
		target = null;
		resetAimState();
		resetCombo();
		resetShieldBreak();
		resetDodge();
		stopMusic();
	}
	
	@Override
	public void onUpdate()
	{
		// Exactly one attack is allowed per tick; see attackTarget().
		attackedThisTick = false;
		tickCount++;
		
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
		
		// (the combo's own Shield block keeps going)
		if(!aimWhileBlocking.isChecked() && MC.player.isUsingItem()
			&& !shieldBlocking)
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
		
		long now = System.nanoTime();
		forgetOldAttackers(now);
		
		// The lock only changes when the target is gone (dead, out of range,
		// out of sight), on the switch key, when something else attacks you
		// while the target doesn't, or when another attacker gets clearly
		// closer than an attacking target. A closer non-attacker never takes
		// the lock - that kept restarting the aim between two targets.
		if(switchRequested || !isValidTarget(target))
			target = pickTarget(switchRequested ? target : null, false, now);
		else if(!lastHurtBy.isEmpty())
		{
			Entity attacker = pickTarget(target, true, now);
			// DISTANCE_TIE: two attackers side by side don't trade the lock
			// back and forth every few ticks
			if(attacker != null && (!isAttackingYou(target, now)
				|| distanceTo(attacker) < distanceTo(target) - DISTANCE_TIE))
				target = attacker;
		}
		
		// A different entity here means the target was switched or the old one
		// died, both of which are caught up to by turning instead of snapping.
		if(target != lastAimedTarget)
		{
			lastAimedTarget = target;
			smoothCatchUp = target != null;
			if(smoothCatchUp)
				aim.startTurn(GameClock.nanoTime());
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
		// (not while the shield breaker holds the axe)
		if(shieldBreakStage == 0)
			WURST.getHax().autoSwordHack.setSlot(target);
		
		updateDodge();
		trackTargetSwing();
		trackTargetMotion();
		trackSprint();
		reactToTargetHit();
		updateCrouchDodge();
		
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
		
		// No hit while the player clicks themselves or the crosshair is off
		// the target. During an aura-farming spin with server-side aim the
		// crosshair is intentionally off-target while the server still sees
		// you facing it, so skip that half of the check there. Without silent
		// aim the server sees the spin too, so only hit when the crosshair
		// actually sweeps over the target.
		// (This used to also reset the attack cooldown - every tick the
		// crosshair slipped off a strafing target cost a full cooldown, so
		// clear chances to hit went by and you had to click yourself.)
		// (The combo also used to reset whenever the crosshair slipped off the
		// target for a moment - a strafing or jumping one, a lag spike. That
		// let go of W, so once the crosshair was back on, sprint had to be
		// rebuilt first and the charged hit waited 2 ticks or never came.
		// Now only looking clearly away resets it; whether a hit can go out
		// right now is HitCheck's call in attackTarget().)
		boolean silentSpin =
			spinRemaining > 0F && faceTarget.getSelected() == FaceTarget.SERVER;
		if(attackClicked || !silentSpin && !isCrosshairOnTarget()
			&& RotationUtils.getAngleToLookVec(
				target.getBoundingBox().getCenter()) > LOOKED_AWAY_DEGREES)
		{
			resetCombo();
			return;
		}
		
		if(updateShieldBreak())
			return;
			
		// (A separate path used to hit any airborne target right away. A
		// falling one gets falling knockback - into the ground - so it lands
		// and rebuilds its momentum first; now a falling target is waited
		// for, see isGoodMomentToHit().)
		if(autoCombo.isChecked())
			updateAutoCombo();
		else if(isHitCharged() && EntityUtils.distanceToHitboxSq(target) <= 9
			&& (isDeflecting() || isGoodMomentToHit()))
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
		long now = GameClock.nanoTime();
		
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
					Math.max(SMOOTH_AIM_SPEED,
						8 * aim.angleTo(needed.yaw(), needed.pitch())),
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
			float dYaw = Mth.wrapDegrees(MC.player.getYRot() - lastCameraYaw);
			float dPitch = MC.player.getXRot() - lastCameraPitch;
			steerAccumYaw += Math.abs(dYaw);
			steerAccumPitch += Math.abs(dPitch);
			
			// While AimAssist turns the camera your mouse does too (slowed
			// near the target, see onMouseUpdate): the aim carries on from
			// where the mouse put it, at once and smoothly - no reaction
			// delay, no snapping back - like the video's return after a
			// flick. No integral built up while you held it off, either.
			if(isLockingMouse() && aim.isValid() && (dYaw != 0 || dPitch != 0))
			{
				aim.shift(dYaw, dPitch);
				aim.clearIntegral();
				smoothCatchUp = true;
			}
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
		friction.reset();
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
	/**
	 * Whether the server sees you sprinting (the last sprint state the
	 * client sent, {@code wasSprinting}) - or sprinting isn't possible right
	 * now at all, so waiting for it would mean never hitting: low hunger,
	 * blindness, using an item, sneaking, riding, gliding, in water without
	 * swimming. Without Auto combo you steer, so a hit while you aren't
	 * walking forward goes out unsprinted too.
	 */
	private boolean isSprintHitReady()
	{
		LocalPlayer player = MC.player;
		if(player.isSprinting() && player.wasSprinting)
			return true;
		
		boolean canSprint = (player.getFoodData().getFoodLevel() > 6
			|| player.getAbilities().mayfly) && !player.isUsingItem()
			&& !player.hasEffect(MobEffects.BLINDNESS)
			&& !player.isMovingSlowly() && !player.isPassenger()
			&& !player.isFallFlying()
			&& (!player.isInWater() || player.isUnderWater());
		if(!autoCombo.isChecked())
			canSprint &= player.input.hasForwardImpulse();
		
		return !canSprint;
	}
	
	private boolean attackTarget()
	{
		return attackTarget(false);
	}
	
	/**
	 * Whether a sprint hit or a crit can go out right now: charged, in
	 * reach, a moment worth hitting, and attackTarget() would land it. Only a
	 * setting holds one back - Skip falling targets (isGoodMomentToHit),
	 * Baits while backing off, Aura-Farming on the ground (it jumps for the
	 * crit), Shield breaker while it switches to the axe (before the combo).
	 */
	private boolean isHitChance(double dist, boolean deflecting)
	{
		if(baitTicks > 0 || auraFarming.isChecked() && !isAirborne())
			return false;
		
		return isHitCharged() && dist <= REACH
			&& (deflecting || isGoodMomentToHit()) && canLandGoodHit();
	}
	
	/** attackTarget() without the side effects: would it hit right now? */
	private boolean canLandGoodHit()
	{
		LocalPlayer player = MC.player;
		return !attackedThisTick && target != null && !player.isUsingItem()
			&& HitCheck.isVerifiable(target)
			&& (!isShieldUp(target)
				|| isShieldBreaker(player.getMainHandItem()))
			&& (isCritReady() || isSprintHitReady());
	}
	
	/**
	 * @param anyHit
	 *            also an unsprinted or uncharged hit (Sprint sweeps, Weak
	 *            hits) - the caller decided it's worth it
	 */
	private boolean attackTarget(boolean anyHit)
	{
		// (nobody can attack while using an item - a shield, food)
		if(attackedThisTick || target == null || MC.player.isUsingItem())
			return false;
			
		// Only a hit the server can verify - with the rotation it already has
		// as well as the current one, within reach, nothing in the way.
		// Hitting the moment a fast turn landed was a hit through thin air
		// to Matrix's and Grim's HITBOX checks. See HitCheck.
		if(!HitCheck.isVerifiable(target))
			return false;
			
		// A hit on a raised shield does nothing and knocks us back - only
		// one that disables it (an axe) is worth it.
		if(isShieldUp(target) && !isShieldBreaker(MC.player.getMainHandItem()))
			return false;
			
		// A sprint hit (knockback, keeps combos going) or, falling, a crit
		// (+50% damage) - never a plain sweep. The server decides from the
		// state it already has - the attack packet goes out before this
		// tick's movement and sprint updates - so wait until it has one.
		if(!anyHit && !isCritReady() && !isSprintHitReady())
		{
			// (in the air the combo lets go of sprint for the crit)
			if(!isAirborne())
				KeyPresser.press(MC.options.keySprint);
			return false;
		}
		
		attackedThisTick = true;
		MC.gameMode.attack(MC.player, target);
		MC.player.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	// ── Combo
	// ─────────────────────────────────────────────────────────────────
	//
	// The way good players hold a sword combo (Theobald, Intel - see
	// ../howtocombotranscript*.txt): sprint hits and crits; hold W, hit,
	// release W - and don't hold W again until just before the next hit, or
	// you walk into their reach; reset sprint with an S-tap when they come at
	// you and a W-tap when they back off; don't hit someone who's falling
	// (falling knockback sends them into the ground and they rebuild momentum
	// first); sprint-hit right back when they hit you (a deflection - with an
	// S-tap first when too close); jump the moment you're hit (a jump reset);
	// fly out of a trade when their hit comes before yours can; and the
	// jumps: punish/sprint crits, hop crits, uppercuts, lunges, double crits
	// and hops (see the settings).
	
	/** Hitbox distance from which the target is in reach. */
	private static final double REACH = 3.0;
	
	/** Closer than this, the target can easily hit back: S to back off. */
	private static final double TOO_CLOSE = 2.0;
	
	/**
	 * Closer than this after a hit, the sprint reset is an S-tap - when the
	 * target isn't clearly moving toward or away from us.
	 */
	private static final double S_TAP_DISTANCE = 2.3;
	
	/**
	 * Blocks per tick the target has to move toward (or away from) us for
	 * the sprint reset to be an S-tap (or a W-tap) whatever the distance.
	 */
	private static final double TAP_SPEED = 0.05;
	
	/**
	 * How many ticks before the hit W goes down: one to start sprinting, one
	 * for the server to have it (see isSprintHitReady), one to spare - with
	 * two, a VexBot charged at the same time got its hit in first 4 times in
	 * a 300-tick fight while ours waited for the sprint.
	 */
	private static final int SPRINT_LEAD_TICKS = 3;
	
	/** How long after being hit a hit still counts as a deflection. */
	private static final int DEFLECT_TICKS = 4;
	
	/**
	 * From this ping - the target's alone, or both added up - trades stack
	 * hits: just keep hitting, falling target or not.
	 */
	private static final int LAGGY_PING_MS = 200;
	
	/** Ticks sprinting forward until you're at full speed (~half a second). */
	private static final int FULL_SPEED_TICKS = 10;
	
	/** The longest a fly-out holds S, in ticks. */
	private static final int MAX_FLYOUT_TICKS = 12;
	
	/** What the jump in progress is for - it decides the keys in the air. */
	private enum AirMode
	{
		/** A jump reset, a drop, Aura-Farming: punish or sprint crit. */
		NONE,
		/** Sprint-hit on the way up, then like NONE. */
		UPPERCUT,
		/** W and sprint all the way: the most distance. */
		LUNGE,
		/** No W: a short hop for a crit after a deflection. */
		HOP_CRIT,
		/** Straight up, then S: crit on the way down, out of the trade. */
		DOUBLE_CRIT,
		/** W only in the air: sprint-hit on the way down. */
		HOP
	}
	
	private void updateAutoCombo()
	{
		double dist = Math.sqrt(EntityUtils.distanceToHitboxSq(target));
		boolean deflecting = isDeflecting();
		double closing = closingSpeed(target);
		sprintHeldOff = false;
		updateShieldBlock(dist);
		
		// Every chance at a sprint hit or a crit is taken (user, 2026-10-16:
		// "100% of the time, unless a setting says not to"). The ground
		// combo's tricks below used to have some ticks to themselves - an
		// S-tap deflection, a cobweb, a fly-out falling past the target with
		// a crit ready - and the hit waited.
		boolean chance = isHitChance(dist, deflecting);
		if(chance && comboPhase != ComboPhase.IDLE && attackTarget())
		{
			// (the reset or fly-out carries on)
			comboHitCount++;
			lastHitTick = tickCount;
			deflectSTapTicks = 0;
		}
		
		switch(comboPhase)
		{
			case IDLE:
			if(isAirborne())
				holdAirKeys();
			else
			{
				airMode = AirMode.NONE;
				if(!chance && updateGroundCombo(dist, deflecting, closing))
					break;
			}
			
			// A target in the air is hit at once while it's still going up -
			// a jump reset that came too early gets it launched - and waited
			// for while it's falling (see isGoodMomentToHit). No guessing
			// when they'll jump.
			boolean inReach = dist <= REACH;
			boolean goodMoment = deflecting || isGoodMomentToHit();
			if(isHitCharged() && inReach && goodMoment
				&& attackTarget(sweepChase))
			{
				deflectSTapTicks = 0;
				afterHit(dist, deflecting, closing);
				break;
			}
			
			// Weak hits: one more (uncharged) hit before they leave reach.
			if(weakHits.isChecked() && !isHitCharged() && inReach
				&& dist > REACH - 0.4 && closing < -0.1 && goodMoment
				&& ticksUntilCharged() > 3 && tickCount - lastWeakHitTick > 6
				&& MC.player.getAttackStrengthScale(0.5F) >= 0.3F
				&& attackTarget(true))
				lastWeakHitTick = tickCount;
			break;
			
			case RESETTING:
			// The sprint reset after a hit: W is already let go. A W-tap is
			// just that; an S-tap also holds S. No sprint here: a real s-tap
			// is just S. Holding sprint while walking backward is impossible
			// in vanilla and only "works" with Omnidirectional Sprint, which
			// makes it a giveaway. (AutoSprint used to be switched off and
			// straight back on here, rewriting enabled-hacks.json every hit.)
			releaseForward();
			if(resetWithS)
				holdBackward();
			releaseSprint();
			if(--resetTicksLeft <= 0)
			{
				releaseBackward();
				comboPhase = ComboPhase.IDLE;
			}
			break;
			
			case FLYOUT:
			// Holding S into their hit, so its knockback carries us out of
			// the trade (the jump at the start carries us further). Let go
			// once out of their reach - or once their hit clearly didn't
			// come - so we can build speed again before the next trade
			// instead of backing off for too long.
			releaseForward();
			holdBackward();
			releaseSprint();
			flyOutTicks++;
			if(flyOutHit && dist > REACH + 0.5 || !flyOutHit && flyOutTicks > 6
				|| flyOutTicks >= MAX_FLYOUT_TICKS)
			{
				releaseBackward();
				comboPhase = ComboPhase.IDLE;
			}
			break;
		}
	}
	
	/**
	 * The combo on the ground: what to do between hits. Returns true if this
	 * tick is spoken for (a jump, a fly-out) and no hit should go out.
	 */
	private boolean updateGroundCombo(double dist, boolean deflecting,
		double closing)
	{
		LocalPlayer player = MC.player;
		boolean canJump = !player.isInWater() && !player.isInLava()
			&& !player.isPassenger() && !player.onClimbable();
		
		if(auraFarming.isChecked())
		{
			// jumps for a crit (and the spin) once charged and in reach
			releaseBackward();
			holdForward();
			pressSprint();
			if(isHitCharged() && dist < REACH && canJump)
				KeyPresser.press(MC.options.keyJump);
			return true;
		}
		
		// S-tap deflection: too close to stun them, so a tick of S first.
		if(deflectSTapTicks > 0)
		{
			deflectSTapTicks--;
			releaseForward();
			holdBackward();
			releaseSprint();
			return true;
		}
		
		// Stuck in a cobweb, knockback can't move them and walking in
		// would trap us too: stay out and crit (a hop, a crit on the way
		// down).
		if(isInCobweb(target) && !isInCobweb(player))
		{
			releaseForward();
			releaseBackward();
			releaseSprint();
			releaseComboStrafe();
			if(canJump && dist <= REACH && ticksUntilCharged() <= 3)
			{
				KeyPresser.press(MC.options.keyJump);
				airMode = AirMode.HOP_CRIT;
			}
			return true;
		}
		
		// Fly-out: their next hit comes before ours can, too early even to
		// deflect - take it holding S, jumping backward, out of the trade.
		// Only once we've hit them (flying out mid-combo lets them chase and
		// get extra hits). Flying out of every trade a deflection could win
		// let a VexBot chase and combo us for 3 seconds straight.
		if(flyOuts.isChecked() && !deflecting && canJump && hasRoomBehind()
			&& tickCount - lastHitTick <= 15 && tickCount - lastFlyOutTick > 20
			&& isTargetHitComing()
			&& ticksUntilCharged() > targetTicksUntilCharged() + DEFLECT_TICKS)
		{
			comboPhase = ComboPhase.FLYOUT;
			lastFlyOutTick = tickCount;
			flyOutTicks = 0;
			flyOutHit = false;
			releaseForward();
			holdBackward();
			releaseSprint();
			KeyPresser.press(MC.options.keyJump);
			return true;
		}
		
		// Bait: they jump at us from just out of reach - S, and their crit
		// misses. Not every time.
		if(baitTicks > 0)
		{
			baitTicks--;
			releaseForward();
			holdBackward();
			releaseSprint();
			return false;
		}
		boolean targetRising = target.getY() - target.yo > 0.05;
		if(!targetRising)
			baitDecided = false;
		else if(baits.isChecked() && !baitDecided && !deflecting
			&& closing > 0.1 && dist > REACH && dist < REACH + 2.5)
		{
			baitDecided = true;
			if(random.nextBoolean())
			{
				baitTicks = 3;
				releaseForward();
				holdBackward();
				releaseSprint();
				return false;
			}
		}
		
		boolean fullSpeed = sprintTicks >= FULL_SPEED_TICKS;
		boolean sprinting = player.isSprinting() && player.wasSprinting;
		
		// Lunge: they back out of the trade - sprint-jump after them, once
		// at full speed (a lunge from a standstill covers less ground).
		if(lunges.isChecked() && canJump && fullSpeed && closing < -0.05
			&& dist > REACH + 0.5 && dist <= 6 && ticksUntilCharged() <= 6
			&& !isFalling(target))
		{
			releaseBackward();
			holdForward();
			pressSprint();
			KeyPresser.press(MC.options.keyJump);
			airMode = AirMode.LUNGE;
			return true;
		}
		
		// Uppercut: just out of reach with the hit ready - a sprint jump's
		// speed boost reaches a bit further, and the hit goes out rising.
		if(uppercuts.isChecked() && canJump && sprinting && dist > REACH
			&& dist <= REACH + 0.7 && ticksUntilCharged() <= 1
			&& closing <= TAP_SPEED && !isFalling(target))
		{
			releaseBackward();
			holdForward();
			pressSprint();
			KeyPresser.press(MC.options.keyJump);
			airMode = AirMode.UPPERCUT;
			return true;
		}
		
		if(sweepChase)
		{
			// Sprint sweeps: no sprint reset, W stays down while they back
			// out - the next hit has less knockback, so they can't fly out.
			if(closing >= -0.05 || dist > REACH + 1)
				sweepChase = false;
			else
			{
				releaseBackward();
				holdForward();
				releaseSprint();
				return false;
			}
		}
		
		// Spacing (user, 2026-10-17: "they can't hit you but you can hit
		// them"): go in only so as to arrive as the hit charges, and until
		// then stay out of THEIR reach - from their eyes to our hitbox, so a
		// target knocked up into the air has to come closer than we do. It
		// used to chase as soon as they were past 3 blocks (arriving in their
		// reach with 10 ticks of cooldown left) and only back off under 2.0:
		// of 16 hits a VexBot landed in a 300-tick fight, 11 came while our
		// own hit was 6-11 ticks away, from 0.3-2.4 blocks.
		if(deflecting
			|| ticksUntilCharged() <= SPRINT_LEAD_TICKS + approachTicks(dist))
		{
			releaseBackward();
			holdForward();
			pressSprint();
			
		}else if(hops.isChecked() && canJump && dist < TOO_CLOSE
			&& dist >= TOO_CLOSE - 0.5)
		{
			// Hops: jump without W, W in the air, a sprint hit on the way
			// down - instead of backing off when too close. (It hopped at any
			// distance from 1.5 up, staying in their reach every cooldown.)
			releaseForward();
			releaseBackward();
			KeyPresser.press(MC.options.keyJump);
			airMode = AirMode.HOP;
			
		}else if(theirReachToUs() <= REACH + THEIR_REACH_MARGIN
			&& closing > -TAP_SPEED && hasRoomBehind())
		// (not from one that's backing off itself: it's no threat, and
		// it's the one to keep up with)
		{
			releaseForward();
			holdBackward();
			
		}else
		{
			releaseForward();
			releaseBackward();
		}
		
		// Combo strafe: A or D while in reach, the side flipped every hit.
		if(comboStrafe.isChecked() && dist <= REACH + 0.5
			&& dodgeDirection == Dodge.NONE)
		{
			holdDodge(strafeLeft ? Dodge.LEFT : Dodge.RIGHT);
			comboStrafing = true;
		}else
			releaseComboStrafe();
		return false;
	}
	
	/**
	 * The keys in the air, by what the jump was for. A plain one (a jump
	 * reset, a drop) ends in a punish crit: W while rising - the momentum -
	 * and let go once falling, so the server has us not sprinting by the
	 * hit. If sprinting already ended (our last sprint hit ended it), W can
	 * stay down for a sprint crit.
	 */
	private void holdAirKeys()
	{
		LocalPlayer player = MC.player;
		boolean rising = player.getDeltaMovement().y > 0;
		switch(airMode)
		{
			case LUNGE, HOP ->
			{
				releaseBackward();
				holdForward();
				pressSprint();
			}
			
			case HOP_CRIT ->
			{
				releaseForward();
				releaseBackward();
				releaseSprint();
			}
			
			case DOUBLE_CRIT ->
			{
				releaseForward();
				holdBackward();
				releaseSprint();
			}
			
			default ->
			{
				releaseBackward();
				if(rising)
				{
					holdForward();
					pressSprint();
				}else
				{
					releaseSprint();
					if(sprintCrits.isChecked() && !player.isSprinting())
						holdForward();
					else
						releaseForward();
				}
			}
		}
	}
	
	/**
	 * After a hit: release W and reset sprint - an S-tap when they come at
	 * us (it makes room), a W-tap when they back off (it keeps up), else by
	 * distance - or, with the settings for it, jump at once (hop crit after
	 * a deflection, double crit from up close) or keep W down (sprint
	 * sweeps).
	 */
	private void afterHit(double dist, boolean deflecting, double closing)
	{
		comboHitCount++;
		lastHitTick = tickCount;
		strafeLeft = !strafeLeft;
		boolean onGround = !isAirborne();
		
		if(sweepChase)
		{
			// one more unsprinted hit; the chase goes on while they back out
			releaseBackward();
			holdForward();
			releaseSprint();
			return;
		}
		
		if(sprintSweeps.isChecked() && onGround && closing < -0.15)
		{
			// they back out right away: chase with sprint sweeps
			sweepChase = true;
			releaseBackward();
			holdForward();
			releaseSprint();
			return;
		}
		
		releaseForward();
		
		boolean canJump = onGround && !MC.player.isInWater()
			&& !MC.player.isInLava() && !MC.player.isPassenger();
		if(canJump && deflecting && hopCrits.isChecked())
		{
			// hop crit: a hop right after the deflection
			KeyPresser.press(MC.options.keyJump);
			airMode = AirMode.HOP_CRIT;
			resetWithS = false;
			resetTicksLeft = 1;
			comboPhase = ComboPhase.RESETTING;
			return;
		}
		if(canJump && doubleCrits.isChecked() && dist < TOO_CLOSE)
		{
			// double crit: straight up at once, S after the jump
			KeyPresser.press(MC.options.keyJump);
			airMode = AirMode.DOUBLE_CRIT;
			resetWithS = false;
			resetTicksLeft = 1;
			comboPhase = ComboPhase.RESETTING;
			return;
		}
		
		if(closing > TAP_SPEED)
			resetWithS = true;
		else if(closing < -TAP_SPEED)
			resetWithS = false;
		else
			resetWithS = dist < S_TAP_DISTANCE;
		resetWithS &= onGround;
		resetTicksLeft = resetWithS ? closing > 0.2 ? 3 : 2 : 1;
		comboPhase = ComboPhase.RESETTING;
	}
	
	/**
	 * Blocks per tick the entity moves toward us (negative: away), from its
	 * positions - other players' motion isn't sent, only where they are.
	 */
	private double closingSpeed(Entity e)
	{
		double toX = MC.player.getX() - e.getX();
		double toZ = MC.player.getZ() - e.getZ();
		double len = Math.sqrt(toX * toX + toZ * toZ);
		if(len < 1.0E-4)
			return 0;
		
		return (targetMotion.x * toX + targetMotion.z * toZ) / len;
	}
	
	/**
	 * The target's movement per tick, from where it was each tick and
	 * smoothed a little (like VexBot): its last-tick step alone reads 0
	 * after a position snap (a teleport, a correction).
	 */
	private void trackTargetMotion()
	{
		Vec3 pos = target.position();
		if(target != motionTracked || lastTargetPos == null)
			targetMotion = Vec3.ZERO;
		else
		{
			Vec3 step = pos.subtract(lastTargetPos);
			// a teleport isn't movement
			if(step.horizontalDistanceSqr() > 4)
				step = Vec3.ZERO;
			targetMotion = targetMotion.scale(0.5).add(step.scale(0.5));
		}
		motionTracked = target;
		lastTargetPos = pos;
	}
	
	/** Notes when the target swings: its attack cooldown starts over then. */
	private void trackTargetSwing()
	{
		if(target != swingTracked)
		{
			swingTracked = target;
			targetSwingTick = NEVER;
			targetWasSwinging = false;
			targetLastSwingTime = 0;
		}
		if(!(target instanceof LivingEntity le))
			return;
		
		boolean near =
			EntityUtils.distanceToHitboxSq(le) <= (REACH + 0.5) * (REACH + 0.5);
		if(le.swinging
			&& (!targetWasSwinging || le.swingTime < targetLastSwingTime))
		{
			targetSwingTick = tickCount;
			// it can't hit again for a while: answer the swing
			if(punishOpenings.isChecked() && near)
				punishUntilTick = tickCount + DEFLECT_TICKS;
		}
		targetWasSwinging = le.swinging;
		targetLastSwingTime = le.swingTime;
		
		// it lowered its shield: hit before it comes back up
		boolean blocking = le.isBlocking();
		if(punishOpenings.isChecked() && targetWasBlocking && !blocking && near)
			punishUntilTick = tickCount + DEFLECT_TICKS + 1;
		targetWasBlocking = blocking;
	}
	
	/** Ticks in a row sprinting forward on the ground (for lunges). */
	private void trackSprint()
	{
		LocalPlayer player = MC.player;
		if(!player.isSprinting() || !player.input.hasForwardImpulse())
			sprintTicks = 0;
		else if(player.onGround())
			sprintTicks++;
	}
	
	/**
	 * Ticks until the target can deal a full hit again: its weapon's attack
	 * speed (players; mobs a second) since its last swing, at the same 90%
	 * as ours. 0 if it hasn't swung yet.
	 */
	private int targetTicksUntilCharged()
	{
		if(targetSwingTick == NEVER)
			return 0;
		
		double cooldown = 20;
		if(target instanceof Player p)
		{
			ItemStack weapon = p.getMainHandItem();
			double speed =
				weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
					ItemAttributeModifiers.EMPTY).compute(
						Attributes.ATTACK_SPEED, 4, EquipmentSlot.MAINHAND);
			cooldown = 20 / Math.max(speed, 0.1);
		}
		
		double since = tickCount - targetSwingTick;
		return Math.max(0, (int)Math.ceil(0.9 * cooldown - 0.5 - since));
	}
	
	/**
	 * Whether the target's next hit is about to come: it's facing us, within
	 * its reach of us (a bit more for momentum), and charged within a tick.
	 */
	private boolean isTargetHitComing()
	{
		// only a target that's fighting: one that never swings (a dummy, a
		// passive mob) isn't about to hit anyone
		if(!(target instanceof LivingEntity)
			|| tickCount - targetSwingTick > 40)
			return false;
		
		Vec3 eyes = target.getEyePosition();
		Vec3 nearest = closestPoint(MC.player.getBoundingBox(), eyes);
		if(eyes.distanceToSqr(nearest) > 3.3 * 3.3)
			return false;
		
		if(facingUs(target, MC.player.position()) < 0.8)
			return false;
		
		return targetTicksUntilCharged() <= 1;
	}
	
	/**
	 * How far beyond their reach we stay while our hit charges: they close
	 * in at up to ~0.28 blocks a tick sprinting, we back off at ~0.22.
	 */
	private static final double THEIR_REACH_MARGIN = 1.0;
	
	/** Blocks a tick a sprint covers once going (for approachTicks). */
	private static final double SPRINT_STEP = 0.28;
	
	/**
	 * Ticks of sprinting it takes from this hitbox distance until the
	 * target is in reach - how much earlier than the charge to set off.
	 */
	private static int approachTicks(double dist)
	{
		if(dist <= REACH)
			return 0;
		return (int)Math.ceil((dist - REACH + 0.1) / SPRINT_STEP);
	}
	
	/**
	 * The target's reach to us: its eyes to the nearest point of our
	 * hitbox. Up in the air after our hit, its eyes are above our head and
	 * this is the slanted line - longer than ours to it, which only spans the
	 * gap. That's what holds a combo: we can hit them, they can't hit us.
	 */
	private double theirReachToUs()
	{
		Vec3 eyes = target.getEyePosition();
		return eyes.distanceTo(closestPoint(MC.player.getBoundingBox(), eyes));
	}
	
	private static Vec3 closestPoint(AABB box, Vec3 p)
	{
		return new Vec3(Mth.clamp(p.x, box.minX, box.maxX),
			Mth.clamp(p.y, box.minY, box.maxY),
			Mth.clamp(p.z, box.minZ, box.maxZ));
	}
	
	/**
	 * Whether S would actually move us: no wall right behind (VexBot's
	 * MeleeWallGuard - an S-tap or fly-out into a wall does nothing).
	 */
	private boolean hasRoomBehind()
	{
		double yaw = Math.toRadians(MC.player.getYRot());
		AABB moved = MC.player.getBoundingBox().move(Math.sin(yaw) * 0.55, 0,
			-Math.cos(yaw) * 0.55);
		return MC.level.noCollision(MC.player, moved);
	}
	
	/**
	 * Crouch dodge: the target drops onto us for a crit - a smaller hitbox
	 * for a moment, unless our own hit can go out right now. (User,
	 * 2026-10-17: "crouch dodge doesn't work" - it only ran standing between
	 * hits with our hit 3+ ticks away and the crosshair within 45 degrees of
	 * the target; a diver that just appeared above is further off than that,
	 * and with nobody being hit our hit is ready most of the time. The
	 * gametest dropped a VexBot onto us 10 times: 38 ticks of dives, no
	 * crouch.)
	 */
	private void updateCrouchDodge()
	{
		if(!crouchDodge.isChecked() || !autoAttack.isChecked()
			|| !autoCombo.isChecked())
			return;
		if(tickCount - lastCrouchDodgeTick <= 15 || !isCritDiveIncoming())
			return;
		if(isHitCharged() && canLandGoodHit())
			return;
		
		KeyPresser.press(MC.options.keyShift, 3);
		lastCrouchDodgeTick = tickCount;
	}
	
	/**
	 * The target drops onto us from our height or above, within reach. (It
	 * also wanted their hit fully charged by our count from their last
	 * swing - a spam-clicker swings so often that never was the case.)
	 */
	private boolean isCritDiveIncoming()
	{
		if(!(target instanceof LivingEntity) || target.onGround())
			return false;
		if(target.getY() - target.yo > -0.05
			|| target.getY() < MC.player.getY() - 0.1)
			return false;
		
		return EntityUtils.distanceToHitboxSq(target) <= (REACH + 0.5)
			* (REACH + 0.5);
	}
	
	private static boolean isInCobweb(Entity e)
	{
		return MC.level.getBlockState(e.blockPosition()).is(Blocks.COBWEB)
			|| MC.level.getBlockState(BlockPos.containing(e.getEyePosition()))
				.is(Blocks.COBWEB);
	}
	
	private void releaseComboStrafe()
	{
		if(!comboStrafing)
			return;
		comboStrafing = false;
		if(dodgeDirection == Dodge.NONE)
			releaseDodgeKeys();
	}
	
	/**
	 * Whether the entity holds up a shield toward us: vanilla blocks hits
	 * coming from in front of it.
	 */
	private static boolean isShieldUp(Entity e)
	{
		if(!(e instanceof LivingEntity le) || !le.isBlocking())
			return false;
		
		Vec3 toUs = MC.player.position().subtract(le.position());
		Vec3 look = le.getLookAngle();
		return look.x * toUs.x + look.z * toUs.z > 0;
	}
	
	/** A weapon whose hits disable shields (axes). */
	private static boolean isShieldBreaker(ItemStack stack)
	{
		Weapon weapon = stack.get(DataComponents.WEAPON);
		return weapon != null && weapon.disableBlockingForSeconds() > 0;
	}
	
	private static int findShieldBreakerSlot()
	{
		for(int i = 0; i < 9; i++)
			if(isShieldBreaker(MC.player.getInventory().getItem(i)))
				return i;
		return -1;
	}
	
	/**
	 * Shield breaker: switch to the axe, hit (next tick - the slot change
	 * has to reach the server first, and never after an attack in the same
	 * tick, see PacketOrder), switch back. Returns true while it runs.
	 */
	private boolean updateShieldBreak()
	{
		if(shieldBreakStage == 1)
		{
			shieldBreakTicks++;
			if(!isShieldUp(target) || shieldBreakTicks > 10
				|| attackTarget(true))
				shieldBreakStage = 2;
			return true;
		}
		
		if(shieldBreakStage == 2)
		{
			if(!PacketOrder.canChangeSlotNow())
				return true;
			if(shieldBreakPrevSlot >= 0)
				selectSlot(shieldBreakPrevSlot);
			shieldBreakStage = 0;
			shieldBreakPrevSlot = -1;
			return true;
		}
		
		if(!shieldBreaker.isChecked() || !isShieldUp(target)
			|| EntityUtils.distanceToHitboxSq(target) > REACH * REACH)
			return false;
		
		// already holding one: just hit
		if(isShieldBreaker(MC.player.getMainHandItem()))
			return attackTarget(true);
		
		int axe = findShieldBreakerSlot();
		if(axe < 0)
			return false;
		if(!PacketOrder.canChangeSlotNow())
			return true;
		
		shieldBreakPrevSlot = MC.player.getInventory().getSelectedSlot();
		selectSlot(axe);
		shieldBreakStage = 1;
		shieldBreakTicks = 0;
		return true;
	}
	
	private static void selectSlot(int slot)
	{
		MC.player.getInventory().setSelectedSlot(slot);
		IMC.getInteractionManager().syncSelectedSlot();
	}
	
	/** Puts the weapon back if the shield breaker was holding the axe. */
	private void resetShieldBreak()
	{
		if(shieldBreakStage != 0 && shieldBreakPrevSlot >= 0
			&& MC.player != null && PacketOrder.canChangeSlotNow())
			selectSlot(shieldBreakPrevSlot);
		shieldBreakStage = 0;
		shieldBreakPrevSlot = -1;
	}
	
	/**
	 * Shield block: up while the target's hit is due before ours (a shield
	 * takes 5 ticks to block), down in time for our own hit.
	 */
	private void updateShieldBlock(double dist)
	{
		ItemStack offhand = MC.player.getOffhandItem();
		boolean want = shieldBlock.isChecked()
			&& offhand.has(DataComponents.BLOCKS_ATTACKS)
			&& !MC.player.getCooldowns().isOnCooldown(offhand)
			&& target instanceof LivingEntity le
			&& !isShieldBreaker(le.getMainHandItem()) && dist <= 4.5
			&& facingUs(target, MC.player.position()) >= 0.8
			&& targetTicksUntilCharged() <= 6 && ticksUntilCharged() > 2
			&& shieldBlockTicks < 40;
		
		if(want)
		{
			MC.options.keyUse.setDown(true);
			shieldBlocking = true;
			shieldBlockTicks++;
		}else
			stopShieldBlock();
	}
	
	private void stopShieldBlock()
	{
		if(!shieldBlocking)
		{
			shieldBlockTicks = 0;
			return;
		}
		IKeyMapping.get(MC.options.keyUse).resetPressedState();
		shieldBlocking = false;
		shieldBlockTicks = 0;
	}
	
	/**
	 * Whether the weapon is charged enough for a sprint hit: vanilla's
	 * {@code Player.attack} gives the extra knockback above 90% strength
	 * (measured half a tick ahead), so a full cooldown isn't needed. Fired a
	 * tick past that, so the server's own count - a tick off now and then -
	 * is past it too.
	 */
	private boolean isHitCharged()
	{
		return ticksUntilCharged() == 0;
	}
	
	private int ticksUntilCharged()
	{
		for(int ticks = 0; ticks < 40; ticks++)
			if(MC.player.getAttackStrengthScale(ticks - 0.5F) > 0.9F)
				return ticks;
		return 40;
	}
	
	/**
	 * Not while the target is falling: a hit then is falling knockback, down
	 * into the ground, so they land and rebuild their momentum before you
	 * can - wait until they land, or hit them while they're still going up.
	 * A very laggy player is hit regardless; the knockback reaches them late
	 * anyway.
	 */
	private boolean isGoodMomentToHit()
	{
		return !skipFalling.isChecked() || isLaggy(target)
			|| !isFalling(target);
	}
	
	private static boolean isFalling(Entity e)
	{
		// other players' motion isn't sent, only their positions
		return !e.onGround() && !e.isInWater() && !e.isInLava()
			&& !(e instanceof LivingEntity le && le.onClimbable())
			&& e.getY() - e.yo < -0.01;
	}
	
	/**
	 * Whether the target is very laggy, or both pings together are high: the
	 * knockback lands late either way and the trade stacks hits, so hitting
	 * fast pays off more than waiting.
	 */
	private static boolean isLaggy(Entity e)
	{
		if(!(e instanceof Player) || MC.getConnection() == null)
			return false;
		
		PlayerInfo info = MC.getConnection().getPlayerInfo(e.getUUID());
		if(info == null)
			return false;
		
		PlayerInfo own = MC.getConnection().getPlayerInfo(MC.player.getUUID());
		int ownPing = own == null ? 0 : own.getLatency();
		return info.getLatency() + ownPing >= LAGGY_PING_MS;
	}
	
	/** A deflection, or punishing an opening (see Punish openings). */
	private boolean isDeflecting()
	{
		return tickCount <= deflectUntilTick || tickCount <= punishUntilTick;
	}
	
	/**
	 * When the target has just hit you: jump right away (a jump reset - the
	 * sprint jump's forward boost cancels most of the knockback) and hit back
	 * within the next few ticks whatever the spacing (a deflection).
	 *
	 * <p>
	 * Called in the tick the hit arrived: the damage event is handled before
	 * the player's tick, which is when this runs and the hurt animation
	 * starts; the jump and sprint keys are then read in the same tick, after
	 * the knockback was applied.
	 */
	private void reactToTargetHit()
	{
		LocalPlayer player = MC.player;
		boolean justHurt =
			player.hurtTime > 0 && player.hurtTime == player.hurtDuration;
		Long hurtBy = lastHurtBy.get(target.getId());
		if(!justHurt || hurtBy == null
			|| System.nanoTime() - hurtBy > 500_000_000L)
			return;
			
		// it just attacked, so its cooldown starts over (its swing packet
		// may come a tick later)
		targetSwingTick = tickCount;
		
		boolean combo = autoAttack.isChecked() && autoCombo.isChecked();
		
		// Flying out: the hit was the point (its knockback carries us off),
		// no deflection and no jump reset back into the trade.
		if(combo && comboPhase == ComboPhase.FLYOUT)
		{
			flyOutHit = true;
			return;
		}
		
		if(combo)
			deflectUntilTick = tickCount + DEFLECT_TICKS;
			
		// Too close for a deflection to stun them: S-tap first to make a bit
		// of room, then deflect (an S-tap deflection) - a jump reset would
		// only keep us this close.
		if(combo && player.onGround()
			&& Math.sqrt(EntityUtils.distanceToHitboxSq(target)) < TOO_CLOSE)
		{
			resetCombo();
			deflectSTapTicks = 1;
			deflectUntilTick = tickCount + DEFLECT_TICKS + 2;
			return;
		}
		
		if(!jumpReset.isChecked() || !player.onGround() || player.isInWater()
			|| player.isInLava() || player.isPassenger())
			return;
		
		if(combo)
		{
			// a jump reset needs a sprint jump forward
			resetCombo();
			releaseBackward();
			holdForward();
			KeyPresser.press(MC.options.keySprint);
			
		}else if(!player.isSprinting() || !player.input.hasForwardImpulse())
			return;
		
		KeyPresser.press(MC.options.keyJump);
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
		// nothing to gain from walking into a wall
		if(!hasRoomBehind())
		{
			releaseBackward();
			return;
		}
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
	
	private void pressSprint()
	{
		KeyPresser.press(MC.options.keySprint);
		sprintHeldOff = false;
	}
	
	private void releaseSprint()
	{
		KeyPresser.release(MC.options.keySprint);
		sprintHeldOff = true;
	}
	
	private void resetCombo()
	{
		comboPhase = ComboPhase.IDLE;
		resetTicksLeft = 0;
		comboHitCount = 0;
		spinRemaining = 0F;
		deflectSTapTicks = 0;
		airMode = AirMode.NONE;
		flyOutTicks = 0;
		flyOutHit = false;
		baitTicks = 0;
		sweepChase = false;
		sprintHeldOff = false;
		releaseComboStrafe();
		stopShieldBlock();
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
	
	/** In the air - not on the ground, a ladder, in water or riding. */
	private boolean isAirborne()
	{
		LocalPlayer player = MC.player;
		return !player.onGround() && !player.onClimbable()
			&& !player.isInWater() && !player.isInLava()
			&& !player.isPassenger() && !player.isFallFlying();
	}
	
	/**
	 * Whether a hit now would be a crit to the server: vanilla's
	 * {@code Player.canCriticalAttack} - falling, not on a ladder or in
	 * water, not blind, not riding, not sprinting - with the state the server
	 * has (the attack goes out before this tick's movement packet, and
	 * {@code wasSprinting} is the sprint state it was last told).
	 */
	private boolean isCritReady()
	{
		LocalPlayer player = MC.player;
		return player.fallDistance > 0 && !player.onGround()
			&& !player.onClimbable() && !player.isInWater()
			&& !player.hasEffect(MobEffects.BLINDNESS) && !player.isPassenger()
			&& !player.isSprinting() && !player.wasSprinting
			&& target instanceof LivingEntity;
	}
	
	/**
	 * Whether AimAssist needs the sprint key let go right now - during the
	 * sprint reset after a hit. AutoSprint checks this, so it doesn't press
	 * sprint again in the
	 * same tick and undo the reset.
	 */
	public boolean isHoldingSprintOff()
	{
		if(!isEnabled() || !autoAttack.isChecked() || !autoCombo.isChecked())
			return false;
		
		return comboPhase != ComboPhase.IDLE || sprintHeldOff;
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
		RESETTING,
		FLYOUT
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
		
		long now = GameClock.nanoTime();
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
		// right away, not only at the next tick. (While AimAssist turns the
		// camera, the mouse is followed instead - see absorbMouseMovement.)
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
		AABB drawnBox = target.getBoundingBox().move(offset);
		AABB box = getTargetBox(drawnBox);
		
		// ...and from where your eyes are drawn in this frame. Mixing in the
		// tick position here made the aim wobble every tick while moving.
		Vec3 eyes = EntityUtils.getLerpedPos(MC.player, partialTicks).add(0,
			MC.player.getEyeHeight(MC.player.getPose()), 0);
		
		applyAim(frameAimPoint(offset, drawnBox, eyes), box, eyes,
			frameAimForceClient);
	}
	
	/**
	 * The aim point as drawn in this frame. Aim at: Auto is the hitbox point
	 * closest to your eyes - from this frame's eyes too. AimAtSetting works
	 * it out from the tick's eye position, which jumped once a tick while you
	 * moved (a few degrees up close), and the aim chased every jump: the
	 * camera zig-zagged on 9-11% of the frames of a fight against a VexBot,
	 * the bot itself on 1%.
	 */
	private Vec3 frameAimPoint(Vec3 offset, AABB drawnBox, Vec3 eyes)
	{
		return aimAt.getSelected() == AimAtSetting.AimAt.AUTO
			? closestPoint(drawnBox, eyes)
			: aimAt.getAimPoint(target).add(offset);
	}
	
	/**
	 * While AimAssist turns your camera, your mouse still turns it too, like
	 * the reference video (2026-10-16): slowed down near the target
	 * (Stickiness, see {@link AimFriction}) - pulling away, the crosshair
	 * first only creeps off, a real flick breaks free - and the aim carries
	 * on from wherever the mouse put the camera, bringing it back once you
	 * stop. (It used to lock the mouse completely while it had a target.
	 * Before that, nudging the mouse counted as "steering" and made AimAssist
	 * let go of the target - it never lets go for the mouse now either; use
	 * the switch target key to change targets.)
	 */
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		LocalPlayer player = MC.player;
		if(!isLockingMouse())
		{
			friction.reset();
			return;
		}
		
		// the cosmetic spin is AimAssist's alone
		if(spinRemaining > 0F)
		{
			event.setDeltaX(0);
			event.setDeltaY(0);
			return;
		}
		
		// (spyglass and cinematic camera turn by their own rules)
		if(MC.screen != null || !MC.mouseHandler.isMouseGrabbed()
			|| MC.options.smoothCamera || player.isScoping())
			return;
		
		float partialTicks =
			MC.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 offset = EntityUtils.getLerpedPos(target, partialTicks)
			.subtract(target.position());
		AABB drawnBox = target.getBoundingBox().move(offset);
		Vec3 eyes = EntityUtils.getLerpedPos(player, partialTicks).add(0,
			player.getEyeHeight(player.getPose()), 0);
		AimFriction.Target t = getFrictionTarget(
			frameAimPoint(offset, drawnBox, eyes), getTargetBox(drawnBox), eyes,
			player.getYRot(), player.getXRot());
		
		double step = Rotation.getMouseStep();
		int signX = MC.options.invertMouseX().get() ? -1 : 1;
		int signY = MC.options.invertMouseY().get() ? -1 : 1;
		double[] slowed = AimFriction.slow(event.getDeltaX() * step * signX,
			event.getDeltaY() * step * signY, t, stickiness.getValue());
		double[] counts =
			friction.toCounts(slowed[0], slowed[1], step, signX, signY);
		event.setDeltaX(counts[0]);
		event.setDeltaY(counts[1]);
	}
	
	/** Where the target is relative to the camera, for AimFriction. */
	private static AimFriction.Target getFrictionTarget(Vec3 aimPoint, AABB box,
		Vec3 eyes, float yaw, float pitch)
	{
		Rotation needed = RotationUtils.getNeededRotations(eyes, aimPoint);
		double errorYaw = Mth.wrapDegrees(yaw - needed.yaw());
		double errorPitch = pitch - needed.pitch();
		
		// inside the hitbox: on it whichever way you look
		if(box.contains(eyes))
			return new AimFriction.Target(errorYaw, errorPitch, 180, -180, 180);
		
		double horizontal =
			Math.max(Math.hypot(aimPoint.x - eyes.x, aimPoint.z - eyes.z), 0.1);
		double halfWidth = Math.toDegrees(Math
			.atan(Math.min(box.getXsize(), box.getZsize()) / 2 / horizontal));
		double top = pitchTo(eyes, box.maxY, horizontal);
		double bottom = pitchTo(eyes, box.minY, horizontal);
		
		return new AimFriction.Target(errorYaw, errorPitch, halfWidth,
			top - needed.pitch(), bottom - needed.pitch());
	}
	
	private static double pitchTo(Vec3 eyes, double y, double horizontal)
	{
		return -Math.toDegrees(Math.atan2(y - eyes.y, horizontal));
	}
	
	/**
	 * Whether AimAssist is turning the camera toward a target right now.
	 * Other aiming hacks (MaceAssist) leave the camera alone meanwhile - two
	 * aims pulling toward different points jerked the view around.
	 */
	public boolean isTurningCamera()
	{
		return isEnabled() && isLockingMouse();
	}
	
	/**
	 * Whether AimAssist turns the camera (your mouse still does too, slowed
	 * near the target - see onMouseUpdate).
	 */
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
	
	/**
	 * Picks the best target: anyone who's attacking you before anyone who
	 * isn't, then the closest, then - among those within
	 * {@link #DISTANCE_TIE} of the closest - the one with the least health.
	 *
	 * @param attackersOnly
	 *            only consider entities that are attacking you
	 */
	private Entity pickTarget(Entity exclude, boolean attackersOnly, long now)
	{
		List<Entity> candidates = findCandidates(exclude);
		
		List<Entity> attackers = new ArrayList<>();
		for(Entity e : candidates)
			if(isAttackingYou(e, now))
				attackers.add(e);
			
		if(!attackers.isEmpty())
			candidates = attackers;
		else if(attackersOnly)
			return null;
		
		double closest = Double.MAX_VALUE;
		for(Entity e : candidates)
			closest = Math.min(closest, distanceTo(e));
		
		Entity best = null;
		float bestHealth = Float.MAX_VALUE;
		for(Entity e : candidates)
		{
			if(distanceTo(e) > closest + DISTANCE_TIE)
				continue;
			
			float health = getHealth(e);
			if(best == null || health < bestHealth)
			{
				best = e;
				bestHealth = health;
			}
		}
		
		return best;
	}
	
	private static double distanceTo(Entity e)
	{
		return Math.sqrt(EntityUtils.distanceToHitboxSq(e));
	}
	
	private static float getHealth(Entity e)
	{
		if(e instanceof LivingEntity living)
			return living.getHealth() + living.getAbsorptionAmount();
		
		// end crystals, shulker bullets: one hit anyway
		return 0;
	}
	
	private boolean isAttackingYou(Entity e, long now)
	{
		Long hurtAt = lastHurtBy.get(e.getId());
		return hurtAt != null && now - hurtAt <= getAttackerMemoryNanos();
	}
	
	private long getAttackerMemoryNanos()
	{
		return (long)(attackerMemory.getValue() * 1e9);
	}
	
	/**
	 * Drops attackers that haven't hurt you for longer than
	 * §lAttacker memory§r, and everyone after a world or dimension change
	 * (entity IDs start over).
	 */
	private void forgetOldAttackers(long now)
	{
		if(MC.level != attackersLevel)
		{
			attackersLevel = MC.level;
			lastHurtBy.clear();
			return;
		}
		
		long memory = getAttackerMemoryNanos();
		lastHurtBy.values().removeIf(hurtAt -> now - hurtAt > memory);
	}
	
	/**
	 * Remembers who hurt you. Damage events name the entity responsible (the
	 * shooter for arrows, the thrower for potions), and the server sends
	 * them for every hit, also the ones a shield or armor fully absorbs.
	 */
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!(event.getPacket() instanceof ClientboundDamageEventPacket packet))
			return;
		
		// Network thread: compare IDs only, don't look entities up.
		LocalPlayer player = MC.player;
		if(player == null || packet.entityId() != player.getId())
			return;
		
		int attacker = packet.sourceCauseId();
		if(attacker < 0)
			attacker = packet.sourceDirectId();
		if(attacker < 0 || attacker == player.getId())
			return;
		
		lastHurtBy.put(attacker, System.nanoTime());
	}
	
	private List<Entity> findCandidates(Entity exclude)
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
		
		return stream.toList();
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
