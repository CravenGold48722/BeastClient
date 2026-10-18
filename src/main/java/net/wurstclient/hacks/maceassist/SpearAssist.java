/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.maceassist;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.RightClickListener.RightClickEvent;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EntityTypeListSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.HitCheck;
import net.wurstclient.util.KeyPresser;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

/**
 * MaceAssist's spear features, from the CevAPI fork's SpearAssist (user,
 * 2026-10-18: "integrate spearassist into maceassist, remember we already
 * have lunge-swapping"). Lunge swapping stays MaceAssist's own; this adds
 * what it didn't do: boosts while a spear is charged, target highlights,
 * keeping the charge up, attacking when ready and SpearKill.
 *
 * <p>
 * Left out: SpearAssist's "pass" mode, which steered CevAPI's AimAssist and
 * Flight (forced pass targets, suppressed turning, speed limits) - Beast's
 * AimAssist and Flight have no such hooks.
 */
public final class SpearAssist
{
	private static final net.minecraft.client.Minecraft MC = WurstClient.MC;
	
	private final EnumSetting<BoostMode> boostMode = new EnumSetting<>(
		"Spear boost",
		"Speeds you up while a spear is charged.\n\n"
			+ "§lHold§r - flies you along your look while you hold attack.\n\n"
			+ "§lDash§r - each new attack press dashes you forward.",
		BoostMode.values(), BoostMode.OFF);
	
	private final SliderSetting boostSpeed = new SliderSetting(
		"Spear boost speed", "Speed of the spear boost and dash.", 1, 0.05, 10,
		0.05, ValueDisplay.DECIMAL.withSuffix(" blocks/tick"));
	
	private final SliderSetting dashDistance =
		new SliderSetting("Spear dash distance",
			"How far one dash takes you each time you press attack.", 4, 0, 10,
			0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final CheckboxSetting allowReverse = new CheckboxSetting(
		"Spear reverse boost",
		"Hold the back key while attacking to boost backwards instead.", false);
	
	private final CheckboxSetting stayGrounded =
		new CheckboxSetting("Spear boost stays grounded",
			"Boosts only horizontally, so they don't lift you off the ground.",
			false);
	
	private final CheckboxSetting highlight = new CheckboxSetting(
		"Spear target highlight",
		"While you hold a spear, makes targets in your line of sight glow:"
			+ " the near color within the near range (in reach for a charged"
			+ " hit), the far color beyond the far range.",
		false);
	
	private final SliderSetting nearRange = new SliderSetting(
		"Spear near range", "Targets this close glow in the near color.", 7, 4,
		10, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting farRange = new SliderSetting("Spear far range",
		"Targets at least this far away glow in the far color.", 8.5, 5, 20,
		0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final ColorSetting nearColor = new ColorSetting("Spear near color",
		"Glow of nearby targets.", Color.YELLOW);
	
	private final ColorSetting farColor = new ColorSetting("Spear far color",
		"Glow of far targets.", new Color(0, 255, 0));
	
	private final CheckboxSetting keepCharged =
		new CheckboxSetting("Keep spear charged",
			"While you hold use with a spear, lets go and raises it again right"
				+ " before the charge runs out, so it stays charged.",
			false);
	
	private final SliderSetting chargeMargin =
		new SliderSetting("Spear recharge margin",
			"How many ticks before the charge would run out to raise it again.",
			8, 5, 20, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final CheckboxSetting autoAttack =
		new CheckboxSetting("Spear auto attack",
			"While you hold attack with a spear, attacks again the moment the"
				+ " attack indicator is full.",
			false);
	
	private final EnumSetting<SpearKillMode> spearKill = new EnumSetting<>(
		"SpearKill",
		"While you charge a spear:\n\n"
			+ "§lLunge§r - turns to the target you're looking at and launches"
			+ " you at it once the charge is ready.\n\n"
			+ "§lBlink§r - holds your movement packets back and sends them as"
			+ " one jump when you're close to the target.",
		SpearKillMode.values(), SpearKillMode.OFF);
	
	private final SliderSetting skMaxRange =
		new SliderSetting("SK max range", "How far away targets can be.", 256,
			0, 512, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final EnumSetting<TargetListMode> skListMode =
		new EnumSetting<>("SK target list mode", TargetListMode.values(),
			TargetListMode.BLACKLIST);
	
	private final EntityTypeListSetting skEntities =
		new EntityTypeListSetting("SK target entities",
			WText.literal("Entities to whitelist or blacklist for SpearKill."));
	
	private final CheckboxSetting skBlinkLunge =
		new CheckboxSetting("SK blink + lunge",
			"Combine Blink mode with velocity based lunging.", false);
	
	private final SliderSetting skBlinkLungeStrength = new SliderSetting(
		"SK blink lunge strength", "Velocity applied towards the target.", 1,
		0.1, 2, 0.1, ValueDisplay.DECIMAL);
	
	private final SliderSetting skBlinkLungeTicks = new SliderSetting(
		"SK blink lunge delay", "Ticks to charge before lunging.", 15, 1, 30, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting skFlushRange = new SliderSetting(
		"SK flush range", "Distance to the target when the packets go out.", 3,
		1, 10, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting skMaxFlushRange =
		new SliderSetting("SK force flush distance",
			"Distance travelled after which the packets go out anyway.", 9.5, 1,
			20, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final CheckboxSetting skBlinkAim =
		new CheckboxSetting("SK blink aimbot",
			"Keeps you turned to the target in Blink mode.", true);
	
	private final SliderSetting skDistanceBoost =
		new SliderSetting("SK blink distance boost",
			"Extra blocks added to the start position when the packets go out.",
			0, 0, 10, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting skLungeStrength = new SliderSetting(
		"SK lunge strength", "Velocity applied towards the target.", 5, 0, 10,
		0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting skStopOnTarget =
		new CheckboxSetting("SK stop on target",
			"Stops the lunge once you reach the target.", true);
	
	private final SliderSetting skStopDistance = new SliderSetting(
		"SK stop distance", "How close counts as reaching the target.", 1, 0, 5,
		0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting skLungeDelay =
		new SliderSetting("SK lunge delay modifier",
			"Scales how long the spear charges before a lunge.", 100, 50, 200,
			5, ValueDisplay.INTEGER.withSuffix("%"));
	
	private final CheckboxSetting skChatFeedback =
		new CheckboxSetting("SK chat feedback",
			"Says in chat how far each blink jump went.", false);
	
	private final SettingGroup group = new SettingGroup("Spear",
		WText.literal("Spear features (from SpearAssist): boosts, target"
			+ " highlights, keeping the charge up, auto attack and SpearKill."
			+ " Lunge swapping is separate, above."));
	
	// state
	private final ArrayList<LivingEntity> nearTargets = new ArrayList<>();
	private final ArrayList<LivingEntity> farTargets = new ArrayList<>();
	private double dashRemaining;
	private boolean reverseDash;
	private boolean attackKeyDown;
	private boolean autoAttackPrimed;
	private boolean chargeRestartPending;
	
	private final ArrayDeque<ServerboundMovePlayerPacket> skPackets =
		new ArrayDeque<>();
	private boolean skBlinking;
	private boolean skFlushing;
	private Vec3 skStartPos;
	private boolean skWasCharging;
	private double skLastDistance = Double.MAX_VALUE;
	private boolean skWasApproaching;
	private Entity skTarget;
	private int skChargeTicks;
	private int skFlushCooldown;
	private volatile boolean skSetbackReceived;
	
	public SpearAssist()
	{
		group.addChildren(boostMode, boostSpeed, dashDistance, allowReverse,
			stayGrounded, highlight, nearRange, farRange, nearColor, farColor,
			keepCharged, chargeMargin, autoAttack, spearKill, skMaxRange,
			skListMode, skEntities, skBlinkLunge, skBlinkLungeStrength,
			skBlinkLungeTicks, skFlushRange, skMaxFlushRange, skBlinkAim,
			skDistanceBoost, skLungeStrength, skStopOnTarget, skStopDistance,
			skLungeDelay, skChatFeedback);
	}
	
	/** The group first, then its (hidden) settings, for saving. */
	public Stream<Setting> getSettings()
	{
		return Stream.concat(Stream.of(group), group.getChildren().stream());
	}
	
	public void reset()
	{
		stopBlink(true);
		nearTargets.clear();
		farTargets.clear();
		dashRemaining = 0;
		reverseDash = false;
		attackKeyDown = false;
		autoAttackPrimed = false;
		chargeRestartPending = false;
		skPackets.clear();
		skBlinking = false;
		skFlushing = false;
		skStartPos = null;
		skWasCharging = false;
		skLastDistance = Double.MAX_VALUE;
		skWasApproaching = false;
		skTarget = null;
		skChargeTicks = 0;
		skFlushCooldown = 0;
		skSetbackReceived = false;
	}
	
	public static boolean isSpear(ItemStack stack)
	{
		return stack != null && stack.is(ItemTags.SPEARS);
	}
	
	// ── Tick ─────────────────────────────────────────────────────────────
	
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.gameMode == null
			|| !MC.player.isAlive())
			return;
		
		if(skSetbackReceived)
		{
			skSetbackReceived = false;
			if(skBlinking)
			{
				skPackets.clear();
				skStartPos = MC.player.position();
				skLastDistance = skTarget != null
					? MC.player.distanceTo(skTarget) : Double.MAX_VALUE;
				skWasApproaching = false;
			}
		}
		
		if(spearKill.getSelected() != SpearKillMode.OFF)
		{
			updateSpearKill();
			nearTargets.clear();
			farTargets.clear();
			return;
		}
		if(skBlinking)
			stopBlink(true);
		
		boolean holdingSpear = isSpear(MC.player.getMainHandItem());
		keepChargeUp(holdingSpear);
		
		boolean charging = holdingSpear && MC.player.isUsingItem()
			&& isSpear(MC.player.getUseItem());
		boolean attackHeld = MC.options.keyAttack.isDown();
		boolean attackPressed = attackHeld && !attackKeyDown;
		
		if(charging && boostMode.getSelected() != BoostMode.OFF)
		{
			Vec3 look = MC.player.getLookAngle();
			if(attackPressed)
			{
				reverseDash =
					allowReverse.isChecked() && MC.options.keyDown.isDown();
				if(boostMode.getSelected().dash)
					dashRemaining = Math.max(0, dashDistance.getValue());
			}
			
			boolean reverseHold = allowReverse.isChecked() && attackHeld
				&& MC.options.keyDown.isDown();
			if(attackHeld && boostMode.getSelected().hold)
				applyHoldVelocity(reverseHold ? look.scale(-1) : look);
			continueDash(reverseDash ? look.scale(-1) : look);
			
		}else
		{
			dashRemaining = 0;
			reverseDash = false;
		}
		attackKeyDown = attackHeld;
		
		updateHighlights(holdingSpear);
		autoAttack(holdingSpear, attackHeld);
	}
	
	// ── Keeping the charge up ────────────────────────────────────────────
	
	/**
	 * Lets the spear down just before its charge runs out and raises it
	 * again on the next tick (with the sound muted, like a quick re-click).
	 */
	private void keepChargeUp(boolean holdingSpear)
	{
		boolean maintain = holdingSpear && keepCharged.isChecked()
			&& MC.options.keyUse.isDown();
		if(!maintain)
		{
			chargeRestartPending = false;
			return;
		}
		
		if(chargeRestartPending)
		{
			chargeRestartPending = false;
			MC.rightClickDelay = 0;
			boolean wasSilent = MC.player.isSilent();
			MC.player.setSilent(true);
			MC.startUseItem();
			MC.player.setSilent(wasSilent);
			return;
		}
		
		if(!MC.player.isUsingItem() || !isSpear(MC.player.getUseItem()))
			return;
		
		if(MC.player
			.getTicksUsingItem() < getRefreshTicks(MC.player.getUseItem()))
			return;
		
		MC.gameMode.releaseUsingItem(MC.player);
		chargeRestartPending = true;
	}
	
	private int getRefreshTicks(ItemStack stack)
	{
		int margin = chargeMargin.getValueI();
		KineticWeapon kinetic = stack.get(DataComponents.KINETIC_WEAPON);
		if(kinetic == null)
			return Math.max(1, 200 - margin - 1);
		
		int duration = kinetic.computeDamageUseDuration();
		int usefulFloor = Math.min(duration / 2, duration - 1);
		return Math.max(usefulFloor, Math.max(1, duration - margin - 1));
	}
	
	/**
	 * While keeping the charge up, a right click raises the spear again
	 * instead of whatever else it would do. Returns true if it took the click.
	 */
	public boolean onRightClick(RightClickEvent event)
	{
		if(!keepCharged.isChecked()
			|| spearKill.getSelected() != SpearKillMode.OFF
			|| MC.player == null)
			return false;
		
		if(chargeRestartPending)
		{
			event.cancel();
			return true;
		}
		
		return false;
	}
	
	// ── Boosts ───────────────────────────────────────────────────────────
	
	private void continueDash(Vec3 direction)
	{
		if(dashRemaining <= 0)
			return;
		
		double speed = Math.max(0, boostSpeed.getValue());
		if(speed <= 0)
		{
			dashRemaining = 0;
			return;
		}
		
		double step = Math.min(speed, dashRemaining);
		boolean flying = isFlying();
		Vec3 boost = stayGrounded.isChecked() || flying
			? horizontal(direction).scale(step)
			: direction.normalize().scale(step);
		MC.player.setDeltaMovement(MC.player.getDeltaMovement().add(boost));
		MC.player.fallDistance = 0;
		dashRemaining -= step;
	}
	
	private void applyHoldVelocity(Vec3 direction)
	{
		double speed = boostSpeed.getValue();
		if(speed <= 0)
			return;
		
		boolean flying = isFlying();
		Vec3 target = stayGrounded.isChecked() || flying
			? horizontal(direction).scale(speed)
			: direction.normalize().scale(speed);
		double y = stayGrounded.isChecked() ? 0
			: flying ? MC.player.getDeltaMovement().y : target.y;
		MC.player.setDeltaMovement(target.x, y, target.z);
		MC.player.fallDistance = 0;
	}
	
	private boolean isFlying()
	{
		return MC.player.getAbilities().flying
			|| WurstClient.INSTANCE.getHax().flightHack.isEnabled();
	}
	
	private static Vec3 horizontal(Vec3 direction)
	{
		Vec3 h = new Vec3(direction.x, 0, direction.z);
		return h.lengthSqr() <= 1.0E-6 ? Vec3.ZERO : h.normalize();
	}
	
	// ── Highlights ───────────────────────────────────────────────────────
	
	private void updateHighlights(boolean holdingSpear)
	{
		nearTargets.clear();
		farTargets.clear();
		if(!highlight.isChecked() || !holdingSpear)
			return;
		
		double near = nearRange.getValue();
		double far = Math.max(farRange.getValue(), near + 0.5);
		EntityUtils.getAttackableEntities().sequential()
			.filter(LivingEntity.class::isInstance)
			.map(LivingEntity.class::cast).forEach(entity -> {
				double dy = entity.getY() - MC.player.getY();
				if(dy < 0 || dy > 8 || !hasLineOfSight(entity))
					return;
				
				double distSq = MC.player.distanceToSqr(entity);
				if(distSq <= near * near)
					nearTargets.add(entity);
				else if(distSq >= far * far)
					farTargets.add(entity);
			});
	}
	
	/** For the glow outline (EntityMixin). */
	public Integer getGlowColor(LivingEntity entity)
	{
		if(nearTargets.contains(entity))
			return nearColor.getColorI(0xFF);
		if(farTargets.contains(entity))
			return farColor.getColorI(0xFF);
		return null;
	}
	
	private boolean hasLineOfSight(Entity entity)
	{
		Vec3 eyes = MC.player.getEyePosition(1F);
		Vec3 center = entity.getBoundingBox().getCenter();
		HitResult result = MC.level.clip(new ClipContext(eyes, center,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
		return result.getType() == HitResult.Type.MISS;
	}
	
	// ── Auto attack ──────────────────────────────────────────────────────
	
	private void autoAttack(boolean holdingSpear, boolean attackHeld)
	{
		if(!autoAttack.isChecked() || !holdingSpear || !attackHeld)
		{
			autoAttackPrimed = false;
			return;
		}
		
		float strength = MC.player.getAttackStrengthScale(0);
		if(strength <= 0.1F)
		{
			autoAttackPrimed = false;
			return;
		}
		
		if(strength < 0.999F || autoAttackPrimed)
			return;
		
		// only a hit the server can verify (Beast's attack rules)
		if(!(MC.hitResult instanceof EntityHitResult hit)
			|| !HitCheck.isVerifiable(hit.getEntity()))
			return;
		
		MC.gameMode.attack(MC.player, hit.getEntity());
		MC.player.swing(InteractionHand.MAIN_HAND);
		autoAttackPrimed = true;
	}
	
	// ── SpearKill ────────────────────────────────────────────────────────
	
	private void updateSpearKill()
	{
		boolean charging =
			isSpear(MC.player.getMainHandItem()) && MC.player.isUsingItem();
		
		if(spearKill.getSelected() == SpearKillMode.LUNGE)
		{
			if(skBlinking)
				stopBlink(true);
			skChargeTicks = 0;
			skFlushCooldown = 0;
			skWasCharging = charging;
			
			if(!charging)
			{
				skTarget = null;
				return;
			}
			
			if(skTarget == null || !skTarget.isAlive())
				skTarget = findTarget();
			if(skTarget instanceof LivingEntity && isValidTarget(skTarget))
				runLunge();
			return;
		}
		
		// Blink
		if(charging)
		{
			skChargeTicks++;
			if(skTarget == null || !skTarget.isAlive() || !canSee(skTarget))
			{
				skTarget = findTarget();
				skLastDistance = skTarget != null
					? MC.player.distanceTo(skTarget) : Double.MAX_VALUE;
				skWasApproaching = false;
			}
		}else
		{
			skChargeTicks = 0;
			skTarget = null;
			skLastDistance = Double.MAX_VALUE;
			skWasApproaching = false;
		}
		
		if(charging && skBlinkAim.isChecked() && skTarget != null)
			turnTo(skTarget);
		
		if(charging && skBlinkLunge.isChecked() && skTarget != null
			&& skFlushCooldown == 0
			&& skChargeTicks >= skBlinkLungeTicks.getValueI())
		{
			turnTo(skTarget);
			launchAlongLook(skBlinkLungeStrength.getValue());
		}
		
		if(skFlushCooldown > 0)
			skFlushCooldown--;
		
		if(charging && !skWasCharging)
			startBlink();
		
		if(!charging && skWasCharging && skBlinking)
		{
			if(skTarget != null)
				turnTo(skTarget);
			flushPackets();
			skBlinking = false;
			skStartPos = null;
		}
		
		skWasCharging = charging;
		
		if(!skBlinking || skTarget == null || !charging)
			return;
		
		double distance = MC.player.distanceTo(skTarget);
		boolean approaching = distance < skLastDistance;
		boolean flush = distance <= skFlushRange.getValue()
			|| skWasApproaching && !approaching && distance < 8;
		
		if(!flush && !skBlinkLunge.isChecked() && skStartPos != null
			&& MC.player.position().distanceTo(skStartPos) >= skMaxFlushRange
				.getValue())
		{
			flushPackets();
			startBlink();
		}
		
		if(flush)
		{
			turnTo(skTarget);
			flushPackets();
			if(skBlinkLunge.isChecked())
				skFlushCooldown = skBlinkLungeTicks.getValueI();
			startBlink();
			return;
		}
		
		skLastDistance = distance;
		skWasApproaching = approaching;
	}
	
	private void runLunge()
	{
		turnTo(skTarget);
		
		int useTicks = MC.player.getTicksUsingItem();
		if(useTicks <= getReadyTicks(MC.player.getMainHandItem()))
			return;
		
		if(skStopOnTarget.isChecked()
			&& MC.player.getBoundingBox().inflate(skStopDistance.getValue())
				.intersects(skTarget.getBoundingBox()))
		{
			skTarget = null;
			MC.player.setDeltaMovement(Vec3.ZERO);
			return;
		}
		
		launchAlongLook(skLungeStrength.getValue());
	}
	
	private void launchAlongLook(double speed)
	{
		Vec3 look = Vec3.directionFromRotation(MC.player.getXRot(),
			MC.player.getYRot());
		KeyPresser.press(MC.options.keySprint);
		MC.player.setDeltaMovement(look.scale(speed));
	}
	
	/**
	 * Turns to the target, a little above or below its middle when it's
	 * higher or lower than you. Snapped to mouse steps like every Beast
	 * rotation.
	 */
	private void turnTo(Entity target)
	{
		Vec3 eyes = MC.player.getEyePosition(1F);
		AABB box = target.getBoundingBox();
		double centerY = box.getCenter().y;
		double heightDiff = centerY - eyes.y;
		double height = box.maxY - box.minY;
		double y = centerY;
		if(heightDiff >= 1)
			y -= height * Math.min(heightDiff / 5, 0.4);
		else if(heightDiff <= -1)
			y += height * Math.min(-heightDiff / 5, 0.4);
		
		Rotation rotation = RotationUtils.getNeededRotations(
			new Vec3(box.getCenter().x, y, box.getCenter().z));
		rotation.applyToClientPlayer();
	}
	
	private void startBlink()
	{
		skBlinking = true;
		skStartPos = MC.player.position();
		skPackets.clear();
		skLastDistance = skTarget != null ? MC.player.distanceTo(skTarget)
			: Double.MAX_VALUE;
		skWasApproaching = false;
	}
	
	private void stopBlink(boolean force)
	{
		if(!skBlinking && !force)
			return;
		
		if(MC.player != null)
			flushPackets();
		skBlinking = false;
		skStartPos = null;
	}
	
	/**
	 * Sends the held-back movement as one jump: the start position (moved
	 * back by the distance boost) and then where you are now.
	 */
	private void flushPackets()
	{
		if(MC.player == null || MC.level == null || skPackets.isEmpty())
		{
			skPackets.clear();
			return;
		}
		
		Vec3 now = MC.player.position();
		double distance = skStartPos != null ? skStartPos.distanceTo(now) : 0;
		if(skStartPos == null || distance < skFlushRange.getValue())
		{
			skPackets.clear();
			return;
		}
		
		Vec3 start = skStartPos;
		double boost = skDistanceBoost.getValue();
		Vec3 dir = horizontal(now.subtract(skStartPos));
		if(boost > 0 && dir.lengthSqr() > 0)
		{
			Vec3 boosted = skStartPos.subtract(dir.scale(boost));
			HitResult hit = MC.level.clip(new ClipContext(skStartPos, boosted,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
			start = hit.getType() == HitResult.Type.MISS ? boosted
				: hit.getLocation().add(dir.scale(0.5));
		}
		
		skFlushing = true;
		try
		{
			MC.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
				start.x, start.y, start.z, MC.player.getYRot(),
				MC.player.getXRot(), false, false));
			MC.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
				now.x, now.y, now.z, MC.player.getYRot(), MC.player.getXRot(),
				MC.player.onGround(), MC.player.horizontalCollision));
			
		}finally
		{
			skFlushing = false;
		}
		
		if(skChatFeedback.isChecked())
			ChatUtils.message(String.format(Locale.ROOT,
				"SpearKill jump: %.1f blocks (moved %.1f, boost %.1f)",
				start.distanceTo(now), distance, boost));
		
		skPackets.clear();
	}
	
	/** Holds movement packets back while blinking. */
	public void onSentPacket(PacketOutputEvent event)
	{
		if(spearKill.getSelected() != SpearKillMode.BLINK || skFlushing
			|| !skBlinking || !(event
				.getPacket() instanceof ServerboundMovePlayerPacket packet))
			return;
		
		event.cancel();
		skPackets.addLast(packet);
	}
	
	/** Network thread: only notes the server's position correction. */
	public void onSetback()
	{
		skSetbackReceived = true;
	}
	
	private Entity findTarget()
	{
		if(MC.hitResult instanceof EntityHitResult hit
			&& isValidTarget(hit.getEntity()))
			return hit.getEntity();
		
		double maxRange = skMaxRange.getValue();
		Vec3 eyes = MC.player.getEyePosition(1F);
		Vec3 look = MC.player.getLookAngle();
		HitResult blockHit =
			MC.level.clip(new ClipContext(eyes, eyes.add(look.scale(maxRange)),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
		double rayLength = blockHit.getType() == HitResult.Type.MISS ? maxRange
			: eyes.distanceTo(blockHit.getLocation());
		
		AABB searchBox =
			MC.player.getBoundingBox().expandTowards(look.scale(rayLength));
		List<LivingEntity> candidates = MC.level.getEntitiesOfClass(
			LivingEntity.class, searchBox, e -> e.isAlive() && e != MC.player);
		candidates.sort(Comparator.comparingDouble(
			e -> eyes.distanceToSqr(e.getBoundingBox().getCenter())));
		
		for(LivingEntity e : candidates)
		{
			Vec3 center = e.getBoundingBox().getCenter();
			if(eyes.distanceTo(center) > maxRange)
				break;
			if(!isValidTarget(e) || !canSee(e))
				continue;
			if(look.dot(center.subtract(eyes).normalize()) > 0.999)
				return e;
		}
		
		return null;
	}
	
	private boolean canSee(Entity target)
	{
		Vec3 eyes = MC.player.getEyePosition(1F);
		Vec3 center = target.getBoundingBox().getCenter();
		HitResult result = MC.level.clip(new ClipContext(eyes, center,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
		return result.getType() == HitResult.Type.MISS || eyes
			.distanceTo(result.getLocation()) >= eyes.distanceTo(center) - 0.5;
	}
	
	private boolean isValidTarget(Entity entity)
	{
		if(entity == null || entity == MC.player)
			return false;
		
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		List<String> names = skEntities.getTypeNames();
		boolean inList = names.contains(id.toString());
		if(skListMode.getSelected() == TargetListMode.WHITELIST)
			return inList || names.isEmpty();
		return !inList;
	}
	
	/** Roughly how long each spear needs to charge, by its material. */
	private int getReadyTicks(ItemStack stack)
	{
		String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		int ticks = 14;
		if(name.contains("stone") || name.contains("golden"))
			ticks = 13;
		else if(name.contains("copper"))
			ticks = 12;
		else if(name.contains("iron"))
			ticks = 11;
		else if(name.contains("diamond"))
			ticks = 9;
		else if(name.contains("netherite"))
			ticks = 7;
		
		return Math.round(ticks * skLungeDelay.getValueI() / 100F);
	}
	
	private enum BoostMode
	{
		OFF("Off", false, false),
		HOLD("Hold", true, false),
		DASH("Dash", false, true),
		HOLD_AND_DASH("Hold + dash", true, true);
		
		private final String name;
		private final boolean hold;
		private final boolean dash;
		
		private BoostMode(String name, boolean hold, boolean dash)
		{
			this.name = name;
			this.hold = hold;
			this.dash = dash;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum SpearKillMode
	{
		OFF("Off"),
		LUNGE("Lunge"),
		BLINK("Blink");
		
		private final String name;
		
		private SpearKillMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum TargetListMode
	{
		WHITELIST("Whitelist"),
		BLACKLIST("Blacklist");
		
		private final String name;
		
		private TargetListMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
