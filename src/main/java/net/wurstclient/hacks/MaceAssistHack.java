/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;

/**
 * Port of the "BetterMaceSwap" mod as a single Wurst hack. Everything that mod
 * toggled with a keybind is a checkbox here, and everything it kept in its
 * config screen is a slider or a dropdown.
 */
@SearchTags({"mace assist", "MaceAssist", "better mace swap", "mace swap",
	"attribute swap", "pearl catch", "lunge swap", "mace aim assist",
	"mace trigger bot", "auto chestplate", "stun slam", "wind charge"})
public final class MaceAssistHack extends Hack
	implements UpdateListener, RenderListener, PlayerAttacksEntityListener,
	LeftClickListener, RightClickListener
{
	// ── Attribute swapping ───────────────────────────────────────────────
	
	private final CheckboxSetting attributeSwap = new CheckboxSetting(
		"Attribute swapping",
		"Swaps to a mace when you land a hit, so the attack goes out with the"
			+ " mace's attributes, then swaps back.\n\n"
			+ "Trigger bot hits swap too, just like your own clicks.",
		true);
	
	private final EnumSetting<MaceMode> maceMode =
		new EnumSetting<>("Mace mode",
			"Which mace to prefer when swapping.\n\n"
				+ "§lDensity§r - more damage the further you fall.\n\n"
				+ "§lBreach§r - ignores most of the target's armor.",
			MaceMode.values(), MaceMode.DENSITY);
	
	private final CheckboxSetting smartSwitch = new CheckboxSetting(
		"Smart switch", "Ignores §lMace mode§r and picks Density while you are"
			+ " falling fast, Breach otherwise.",
		false);
	
	private final SliderSetting swapBackDelay =
		new SliderSetting("Swap-back delay",
			"Ticks to wait before swapping back to the item you were holding.",
			3, 1, 5, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final EnumSetting<SwapScope> swapScope =
		new EnumSetting<>("Swap scope",
			"Applies to both the mace swap and the stun slam's axe swap.\n\n"
				+ "§lAll§r - swaps no matter what you are holding.\n\n"
				+ "§lWeapons only§r - only swaps when you are already"
				+ " holding a sword, an axe or a mace.",
			SwapScope.values(), SwapScope.ALL);
	
	private final CheckboxSetting stunSlam = new CheckboxSetting("Stun slam",
		"When the target is blocking, hits them with an axe to break the"
			+ " shield and follows up with a mace slam.\n\n"
			+ "Works with any item - a sword, a wind charge, a carrot, an"
			+ " empty hand - and puts that item straight back in your hand"
			+ " afterwards, as long as §lSwap scope§r is set to §lAll§r.",
		true);
	
	private final CheckboxSetting swapOnPlayers =
		new CheckboxSetting("Swap on players",
			"Allows attribute swapping and stun slam on players.", true);
	
	private final CheckboxSetting swapOnMobs =
		new CheckboxSetting("Swap on mobs",
			"Allows attribute swapping and stun slam on mobs.", true);
	
	// ── Pearl catching ───────────────────────────────────────────────────
	
	private final CheckboxSetting pearlCatching =
		new CheckboxSetting("Pearl catching",
			"After throwing an ender pearl while looking up, locks the camera"
				+ " straight up and fires a wind charge so you catch your own"
				+ " pearl.",
			true);
	
	private final SliderSetting pearlCatchDelay =
		new SliderSetting("Pearl catch delay",
			"Ticks between the pearl leaving your hand and the wind charge.", 2,
			1, 5, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting pearlCatchAngle =
		new SliderSetting("Pearl catch angle",
			"How far up you have to be looking for a pearl throw to count as a"
				+ " catch attempt.",
			60, 60, 90, 1, ValueDisplay.DEGREES);
	
	private final EnumSetting<ReturnSlot> pearlReturnMode =
		new EnumSetting<>("Pearl return slot",
			"Which slot to return to after the pearl is thrown.\n\n"
				+ "§lPearl§r goes back to your pearls, so you can throw"
				+ " the next one straight away.",
			ReturnSlot.values(), ReturnSlot.PREVIOUS);
	
	// ── Wind charges ─────────────────────────────────────────────────────
	
	private final CheckboxSetting windOnRightClick =
		new CheckboxSetting("Wind on right click",
			"Right-clicking with a sword, an axe or a mace fires a wind charge"
				+ " instead of doing nothing.",
			true);
	
	private final CheckboxSetting airPots = new CheckboxSetting("Air pots",
		"Fires a wind charge right after you throw a splash potion in midair,"
			+ " so the potion catches you.",
		false);
	
	// ── Lunge swapping ───────────────────────────────────────────────────
	
	private final CheckboxSetting lungeSwapping = new CheckboxSetting(
		"Lunge swapping",
		"Clicking with a non-weapon briefly switches to a spear so the lunge"
			+ " goes out, then switches straight back.\n\n"
			+ "Trigger bot hits count as clicks too, except when"
			+ " §lAttribute swapping§r is also on - then the mace wins and no"
			+ " spear swap happens.",
		false);
	
	private final SliderSetting lungeSwapDelay =
		new SliderSetting("Lunge swap delay",
			"Ticks to stay on the spear before switching back.", 2, 0, 3, 1,
			ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(0, "instant"));
	
	// ── Mace aim assist ──────────────────────────────────────────────────
	
	private final CheckboxSetting maceAimAssist =
		new CheckboxSetting("Mace aim assist",
			"Drags your camera toward the nearest target while you fall, so the"
				+ " mace lands.",
			false);
	
	private final SliderSetting aimSpeed = new SliderSetting("Aim speed",
		"How fast the camera is allowed to turn, in degrees per second.", 15,
		0.5, 100, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting aimRange = new SliderSetting("Aim range",
		"How far away a target can be before aim assist ignores it.", 15, 0.5,
		25, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting minFallDistance = new SliderSetting(
		"Min fall distance",
		"How far you have to have fallen before aim assist and the trigger bot"
			+ " kick in.",
		1.5, 0, 20, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final EnumSetting<AimBone> aimBone = new EnumSetting<>("Aim bone",
		"Which part of the target to aim at.", AimBone.values(), AimBone.EYE);
	
	private final EnumSetting<AimMode> aimMode = new EnumSetting<>("Aim mode",
		"§lWhile falling§r - only aims during a mace drop.\n\n"
			+ "§lAlways§r - aims whenever a target is in range.",
		AimMode.values(), AimMode.WHILE_FALLING);
	
	private final CheckboxSetting humanizeAim = new CheckboxSetting(
		"Humanize aim",
		"Adds the warm-up delay, random speed jitter and mouse-like rounding"
			+ " that BetterMaceSwap used. Turn this off for a snappier, more"
			+ " obvious aim.",
		true);
	
	private final CheckboxSetting checkLOS = new CheckboxSetting(
		"Check line of sight", "Ignores targets you can't actually see.", true);
	
	// ── Targets ──────────────────────────────────────────────────────────
	
	private final CheckboxSetting targetPlayers = new CheckboxSetting(
		"Target players", "Aim assist and trigger bot attack players.", true);
	
	private final CheckboxSetting targetMannequins =
		new CheckboxSetting("Target mannequins",
			"Aim assist and trigger bot attack mannequins. Handy for practicing"
				+ " mace drops.",
			true);
	
	private final CheckboxSetting targetHostileMobs =
		new CheckboxSetting("Target hostile mobs",
			"Aim assist and trigger bot attack hostile mobs.", true);
	
	private final CheckboxSetting targetPassiveMobs =
		new CheckboxSetting("Target passive mobs",
			"Aim assist and trigger bot attack passive mobs.", true);
	
	private final CheckboxSetting targetArmorStands =
		new CheckboxSetting("Target armor stands",
			"Aim assist and trigger bot attack armor stands.", false);
	
	// ── Mace trigger bot ─────────────────────────────────────────────────
	
	private final CheckboxSetting maceTriggerBot = new CheckboxSetting(
		"Mace trigger bot",
		"Attacks the target by itself the moment it is close enough, without"
			+ " waiting for the next tick.",
		false);
	
	private final CheckboxSetting triggerFallingOnly =
		new CheckboxSetting("Trigger while falling only",
			"Only lets the trigger bot fire during a fall that is at least"
				+ " §lMin fall distance§r long.",
			false);
	
	private final SliderSetting triggerChecksPerTick = new SliderSetting(
		"Trigger checks per tick",
		"How many times per tick the trigger bot checks whether the target is"
			+ " close enough.\n\n"
			+ "Each check steps your position and the target's position a"
			+ " fraction of a tick forward, so a fast mace drop gets caught at"
			+ " the exact moment it enters range instead of a tick late.\n\n"
			+ "Every rendered frame adds another check on top of these.",
		10, 1, 40, 1, ValueDisplay.INTEGER.withSuffix("/tick"));
	
	private final SliderSetting triggerRange =
		new SliderSetting("Trigger range",
			"How close the target has to be for the trigger bot to attack.", 3,
			0.5, 6, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting triggerHitsPerFall = new SliderSetting(
		"Trigger hits per fall",
		"How many times the trigger bot may attack before you touch the ground"
			+ " again.",
		2, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting triggerCooldown =
		new SliderSetting("Trigger cooldown",
			"Ticks to wait between two trigger bot attacks.", 5, 0, 20, 1,
			ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(0, "none"));
	
	private final CheckboxSetting triggerOnlyWhenCharged = new CheckboxSetting(
		"Trigger only when charged",
		"Waits for the attack cooldown to fill up before firing. A mace does"
			+ " almost no damage when it isn't charged.",
		false);
	
	// ── Auto chestplate ──────────────────────────────────────────────────
	
	private final CheckboxSetting autoChestplate =
		new CheckboxSetting("Auto chestplate",
			"Equips a chestplate from your hotbar just before you land on the"
				+ " target, then switches straight back to the slot you were"
				+ " on.",
			false);
	
	private final SliderSetting chestplateDistance =
		new SliderSetting("Chestplate distance",
			"How close the target has to be before the chestplate goes on.",
			5.5, 0.5, 10, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting chestplateFallSpeed =
		new SliderSetting("Chestplate fall speed",
			"How fast you have to be falling before the chestplate goes on.",
			0.4, 0, 2, 0.05, ValueDisplay.DECIMAL);
	
	// ── State ────────────────────────────────────────────────────────────
	
	private int originalSlot = -1;
	private int swapBackTimer;
	private boolean maceSwapped;
	
	private Entity slamTarget;
	private int slamMaceSlot = -1;
	private int slamReturnSlot = -1;
	private boolean slamPending;
	
	private Entity slamFollowUpTarget;
	private int slamFollowUpSlot = -1;
	private int slamFollowUpDelay;
	private boolean slamFollowUpPending;
	
	private boolean pearlThrown;
	private int pearlCatchTimer;
	private int pearlReturnSlot = -1;
	private boolean cameraLocked;
	
	private int windFireDelay;
	private int windSlot = -1;
	private int windReturnSlot = -1;
	private int windActionCooldown;
	private boolean wasUsePressed;
	
	private boolean airPotThrown;
	private int airPotWindDelay;
	
	private boolean lungeSwapPending;
	private int lungeReturnSlot = -1;
	private int lungeDelay;
	
	private int lastSelectedSlot = -1;
	private boolean slamAttacking;
	
	private Entity lockedTarget;
	private int losGraceTicks;
	private long lastAimTimeMs;
	private int aimWarmupTicks;
	
	private int triggerHitsThisFall;
	private int triggerCooldownTimer;
	private boolean wasInAir;
	private boolean chestplateEquipped;
	
	public MaceAssistHack()
	{
		super("MaceAssist");
		setCategory(Category.COMBAT);
		
		addSetting(attributeSwap);
		addSetting(maceMode);
		addSetting(smartSwitch);
		addSetting(swapBackDelay);
		addSetting(swapScope);
		addSetting(stunSlam);
		addSetting(swapOnPlayers);
		addSetting(swapOnMobs);
		
		addSetting(pearlCatching);
		addSetting(pearlCatchDelay);
		addSetting(pearlCatchAngle);
		addSetting(pearlReturnMode);
		
		addSetting(windOnRightClick);
		addSetting(airPots);
		
		addSetting(lungeSwapping);
		addSetting(lungeSwapDelay);
		
		addSetting(maceAimAssist);
		addSetting(aimSpeed);
		addSetting(aimRange);
		addSetting(minFallDistance);
		addSetting(aimBone);
		addSetting(aimMode);
		addSetting(humanizeAim);
		addSetting(checkLOS);
		
		addSetting(targetPlayers);
		addSetting(targetMannequins);
		addSetting(targetHostileMobs);
		addSetting(targetPassiveMobs);
		addSetting(targetArmorStands);
		
		addSetting(maceTriggerBot);
		addSetting(triggerFallingOnly);
		addSetting(triggerChecksPerTick);
		addSetting(triggerRange);
		addSetting(triggerHitsPerFall);
		addSetting(triggerCooldown);
		addSetting(triggerOnlyWhenCharged);
		
		addSetting(autoChestplate);
		addSetting(chestplateDistance);
		addSetting(chestplateFallSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		fullReset();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		EVENTS.add(LeftClickListener.class, this);
		EVENTS.add(RightClickListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		EVENTS.remove(LeftClickListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		
		if(MC.player != null)
			restoreOriginalSlot();
		
		fullReset();
	}
	
	// ── Tick ─────────────────────────────────────────────────────────────
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		Inventory inventory = MC.player.getInventory();
		int currentSlot = inventory.getSelectedSlot();
		if(!inventory.getItem(currentSlot).is(Items.ENDER_PEARL))
			lastSelectedSlot = currentSlot;
		
		boolean inAir = !MC.player.onGround();
		if(wasInAir && !inAir)
		{
			triggerHitsThisFall = 0;
			chestplateEquipped = false;
		}
		wasInAir = inAir;
		
		if(triggerCooldownTimer > 0)
			triggerCooldownTimer--;
		
		tickLungeSwap();
		tickTargeting();
		
		if(cameraLocked)
			MC.player.setXRot(-90);
		
		if(windActionCooldown > 0)
			windActionCooldown--;
		
		if(windOnRightClick.isChecked())
			tickWindRightClick();
		
		tickStunSlam();
		
		if(maceSwapped && --swapBackTimer <= 0)
			restoreOriginalSlot();
		
		tickPearlCatch();
		
		if(windFireDelay > 0 && --windFireDelay == 0)
			fireWindCharge();
		
		if(airPotThrown && --airPotWindDelay <= 0)
		{
			airPotThrown = false;
			fireAirPotWindCharge();
		}
	}
	
	/**
	 * Runs the aim assist and the sub-tick trigger bot checks.
	 */
	private void tickTargeting()
	{
		boolean aimActive = isAimActive();
		boolean triggerActive = isTriggerActive();
		
		if(!aimActive && !triggerActive)
		{
			resetAim();
			return;
		}
		
		updateTarget();
		
		if(aimActive)
			applyRotation();
		
		if(lockedTarget == null)
			return;
		
		tickAutoChestplate();
		
		if(!triggerActive)
			return;
			
		// The whole point of this hack: instead of looking once per tick, walk
		// through the tick in slices and check each one, so a mace drop gets
		// hit at the exact moment it enters range.
		int checks = triggerChecksPerTick.getValueI();
		for(int i = 0; i <= checks; i++)
			if(tryTriggerAttack(i / (double)checks))
				break;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null || MC.level == null || lockedTarget == null)
			return;
		
		if(isAimActive())
			applyRotation();
			
		// Every rendered frame is another chance to notice that the target got
		// close enough, on top of the checks the tick already did.
		if(isTriggerActive())
			tryTriggerAttack(Mth.clamp(partialTicks, 0F, 1F));
	}
	
	/**
	 * Whether aim assist should be steering the camera right now.
	 */
	private boolean isAimActive()
	{
		if(!maceAimAssist.isChecked())
			return false;
		
		return aimMode.getSelected() == AimMode.ALWAYS || isFalling();
	}
	
	/**
	 * Whether the trigger bot should be looking for a hit right now. Unlike aim
	 * assist, it has its own falling requirement, so it can be used on standing
	 * targets such as mannequins.
	 */
	private boolean isTriggerActive()
	{
		if(!maceTriggerBot.isChecked())
			return false;
		
		return !triggerFallingOnly.isChecked() || isFalling();
	}
	
	// ── Target selection ─────────────────────────────────────────────────
	
	private void updateTarget()
	{
		double maxDistSq = aimRange.getValue() * aimRange.getValue();
		
		if(lockedTarget != null)
		{
			boolean valid = isValidTarget(lockedTarget)
				&& MC.player.distanceToSqr(lockedTarget) <= maxDistSq;
			
			if(!valid)
			{
				lockedTarget = null;
				losGraceTicks = 0;
				lastAimTimeMs = 0;
				
			}else if(!hasLineOfSight(lockedTarget))
			{
				if(losGraceTicks > 0)
					losGraceTicks--;
				else
				{
					lockedTarget = null;
					lastAimTimeMs = 0;
				}
				
			}else
				losGraceTicks = 3;
		}
		
		if(lockedTarget != null)
			return;
		
		double bestDist = maxDistSq;
		double yawRad = Math.toRadians(MC.player.getYRot());
		
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!isValidTarget(e))
				continue;
			
			// only consider targets in front of the player
			double dx = e.getX() - MC.player.getX();
			double dz = e.getZ() - MC.player.getZ();
			double dot = -Math.sin(yawRad) * dx + Math.cos(yawRad) * dz;
			if(dot <= 0)
				continue;
			
			if(!hasLineOfSight(e))
				continue;
			
			double distSq = MC.player.distanceToSqr(e);
			if(distSq >= bestDist)
				continue;
			
			bestDist = distSq;
			lockedTarget = e;
		}
		
		losGraceTicks = 3;
		lastAimTimeMs = 0;
		
		if(lockedTarget != null)
			aimWarmupTicks =
				humanizeAim.isChecked() ? 1 + (int)(Math.random() * 2) : 0;
	}
	
	private boolean isValidTarget(Entity e)
	{
		if(e == null || e == MC.player || !e.isAlive() || e.isRemoved())
			return false;
		
		if(WURST.getFriends().isFriend(e))
			return false;
		
		if(e instanceof Mannequin)
			return targetMannequins.isChecked();
		
		if(e instanceof Player)
			return targetPlayers.isChecked();
		
		if(e instanceof ArmorStand)
			return targetArmorStands.isChecked();
		
		if(e instanceof Mob)
			return e instanceof Enemy ? targetHostileMobs.isChecked()
				: targetPassiveMobs.isChecked();
		
		return false;
	}
	
	private boolean hasLineOfSight(Entity target)
	{
		if(!checkLOS.isChecked())
			return true;
		
		return BlockUtils.hasLineOfSight(MC.player.getEyePosition(),
			target.getEyePosition());
	}
	
	private boolean isFalling()
	{
		return !MC.player.onGround() && MC.player.getDeltaMovement().y < -0.1
			&& MC.player.fallDistance >= minFallDistance.getValue();
	}
	
	// ── Aim assist ───────────────────────────────────────────────────────
	
	private void applyRotation()
	{
		if(lockedTarget == null)
			return;
		
		boolean humanize = humanizeAim.isChecked();
		
		if(aimWarmupTicks > 0)
		{
			aimWarmupTicks--;
			return;
		}
		
		if(humanize && Math.random() < 0.35)
			return;
		
		long now = System.currentTimeMillis();
		float deltaSeconds =
			lastAimTimeMs == 0 ? 1F / 20F : (now - lastAimTimeMs) / 1000F;
		deltaSeconds = Math.min(deltaSeconds, 3F / 20F);
		lastAimTimeMs = now;
		
		double targetY = switch(aimBone.getSelected())
		{
			case CHEST -> lockedTarget.getY()
				+ lockedTarget.getBbHeight() * 0.65;
			case LEGS -> lockedTarget.getY() + lockedTarget.getBbHeight() * 0.2;
			default -> lockedTarget.getEyeY();
		};
		
		double dx = lockedTarget.getX() - MC.player.getX();
		double dy = targetY - MC.player.getEyeY();
		double dz = lockedTarget.getZ() - MC.player.getZ();
		double diffXZ = Math.sqrt(dx * dx + dz * dz);
		
		float targetYaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90F;
		float targetPitch = (float)-Math.toDegrees(Math.atan2(dy, diffXZ));
		
		float currentYaw = MC.player.getYRot();
		float currentPitch = MC.player.getXRot();
		
		float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
		float pitchDiff = Mth.wrapDegrees(targetPitch - currentPitch);
		
		float speed = (float)aimSpeed.getValue();
		float yawSpeed =
			humanize ? speed + (float)(Math.random() * 8 - 4) : speed;
		float pitchSpeed =
			humanize ? speed * 0.75F + (float)(Math.random() * 6 - 3) : speed;
		
		float yawMax = yawSpeed * deltaSeconds;
		float pitchMax = pitchSpeed * deltaSeconds;
		
		float smoothing =
			humanize ? 0.08F + (float)(Math.random() * 0.06F) : 0.12F;
		
		float yawStep = Mth.clamp(yawDiff * smoothing, -yawMax, yawMax);
		float pitchStep = Mth.clamp(pitchDiff * smoothing, -pitchMax, pitchMax);
		
		// don't fight the pitch while the yaw is still way off
		if(Math.abs(yawDiff) > 15F)
			pitchStep *= 0.3F;
		
		if(humanize)
		{
			// round the steps to a fake mouse sensitivity, the way a real mouse
			// can only move in whole counts
			float sens = speed / 100F;
			float gcd = (float)Math.pow(sens * 0.6, 2) * 0.15F;
			
			if(gcd > 0.001F)
			{
				yawStep = Math.round(yawStep / gcd) * gcd;
				pitchStep = Math.round(pitchStep / gcd) * gcd;
			}
			
			yawStep += (float)(Math.random() * 0.02 - 0.01);
			pitchStep += (float)(Math.random() * 0.015 - 0.0075);
		}
		
		MC.player.setYRot(currentYaw + yawStep);
		MC.player.setXRot(Mth.clamp(currentPitch + pitchStep, -90F, 90F));
	}
	
	private void resetAim()
	{
		lockedTarget = null;
		losGraceTicks = 0;
		lastAimTimeMs = 0;
		aimWarmupTicks = 0;
	}
	
	// ── Trigger bot ──────────────────────────────────────────────────────
	
	/**
	 * Checks whether the target is within trigger range at the given point
	 * inside the current tick and attacks if it is.
	 *
	 * @param progress
	 *            how far into the tick to look ahead, from 0 to 1.
	 * @return true if an attack was sent.
	 */
	private boolean tryTriggerAttack(double progress)
	{
		if(lockedTarget == null || !isValidTarget(lockedTarget))
			return false;
		
		if(triggerHitsThisFall >= triggerHitsPerFall.getValueI())
			return false;
		
		if(triggerCooldownTimer > 0)
			return false;
		
		if(triggerFallingOnly.isChecked() && !isFalling())
			return false;
		
		if(triggerOnlyWhenCharged.isChecked()
			&& MC.player.getAttackStrengthScale(0) < 0.9F)
			return false;
		
		if(!isInTriggerRange(lockedTarget, progress))
			return false;
		
		attackTarget(lockedTarget);
		return true;
	}
	
	/**
	 * Steps the player and the target forward by a fraction of a tick and
	 * raycasts the target's hitbox from there. This is what lets the trigger
	 * bot catch "close enough" moments that a once-per-tick check would sleep
	 * right through.
	 */
	private boolean isInTriggerRange(Entity target, double progress)
	{
		Vec3 eyes = MC.player.getEyePosition()
			.add(MC.player.getDeltaMovement().scale(progress));
		Vec3 look = MC.player.getLookAngle();
		Vec3 end = eyes.add(look.scale(triggerRange.getValue()));
		
		AABB box = target.getBoundingBox()
			.move(target.getDeltaMovement().scale(progress))
			.inflate(target.getPickRadius());
		
		return box.clip(eyes, end).isPresent();
	}
	
	private void attackTarget(Entity target)
	{
		if(slamAttacking)
			return;
			
		// A trigger bot hit is a click like any other, so it gets the same
		// swaps a manual click would. Attribute swapping and stun slam ride
		// along on the attack event; the lunge swap has to happen before the
		// attack goes out, so it is done here.
		//
		// With both swaps enabled the mace wins: switching to the spear would
		// throw away the mace hit the trigger bot just lined up.
		if(!attributeSwap.isChecked())
			tryLungeSwap();
		
		MC.gameMode.attack(MC.player, target);
		MC.player.swing(InteractionHand.MAIN_HAND);
		
		triggerHitsThisFall++;
		triggerCooldownTimer = triggerCooldown.getValueI();
	}
	
	private void tickAutoChestplate()
	{
		if(!autoChestplate.isChecked() || chestplateEquipped)
			return;
		
		if(MC.player.distanceTo(lockedTarget) > chestplateDistance.getValue())
			return;
		
		if(-MC.player.getDeltaMovement().y <= chestplateFallSpeed.getValue())
			return;
		
		int chestSlot = findItem(ItemTags.CHEST_ARMOR);
		if(chestSlot == -1)
			return;
		
		int returnSlot = MC.player.getInventory().getSelectedSlot();
		
		selectSlot(chestSlot);
		MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
		
		// straight back to the weapon, in the same call - the use packet is
		// already on its way, so there is nothing to wait for
		selectSlot(returnSlot);
		chestplateEquipped = true;
	}
	
	// ── Attribute swapping & stun slam ───────────────────────────────────
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(MC.player == null || target == null || slamAttacking)
			return;
		
		if(lungeSwapPending && lungeReturnSlot != -1)
			return;
		
		if(target instanceof Player && !swapOnPlayers.isChecked())
			return;
		
		if(target instanceof Mob && !swapOnMobs.isChecked())
			return;
		
		Inventory inventory = MC.player.getInventory();
		int startingSlot = inventory.getSelectedSlot();
		ItemStack held = MC.player.getMainHandItem();
		
		boolean weapon = isSword(held) || isAxe(held) || held.is(Items.MACE);
		boolean allowedByScope =
			weapon || swapScope.getSelected() == SwapScope.ALL;
		
		// the scope gates both swaps, the axe one included
		if(!allowedByScope)
			return;
		
		if(stunSlam.isChecked() && target instanceof LivingEntity living
			&& living.isBlocking() && doStunSlam(target, held, startingSlot))
			return;
		
		if(!attributeSwap.isChecked() || maceSwapped)
			return;
		
		boolean wantDensity = smartSwitch.isChecked()
			? !MC.player.onGround() && MC.player.getDeltaMovement().y < -0.3
			: maceMode.getSelected() == MaceMode.DENSITY;
		
		int maceSlot = findBestMace(wantDensity);
		if(maceSlot == -1 || maceSlot == startingSlot)
			return;
		
		originalSlot = startingSlot;
		selectSlot(maceSlot);
		maceSwapped = true;
		swapBackTimer = swapBackDelay.getValueI();
	}
	
	/**
	 * Breaks the target's shield with an axe and queues a mace slam behind it.
	 *
	 * @return true if a slam was queued.
	 */
	private boolean doStunSlam(Entity target, ItemStack held, int startingSlot)
	{
		boolean wantDensity = maceMode.getSelected() == MaceMode.DENSITY;
		int maceSlot = findBestMace(wantDensity);
		
		// already holding the axe, so the hit that just went out is the one
		// that breaks the shield - all that's left is the follow-up
		if(isAxe(held))
		{
			if(maceSlot != -1)
			{
				queueMaceSlam(target, maceSlot, startingSlot);
				queueSlamFollowUp(target, startingSlot, 3);
				return true;
			}
			
			int swordSlot = findItem(ItemTags.SWORDS);
			if(swordSlot != -1)
			{
				queueSlamFollowUp(target, swordSlot, 3);
				return true;
			}
			
			return false;
		}
		
		// anything else - a sword, a wind charge, a carrot, an empty hand -
		// gets the same treatment: axe out, shield broken, back to whatever
		// you were holding
		int axeSlot = findItem(ItemTags.AXES);
		if(axeSlot == -1)
			return false;
		
		selectSlot(axeSlot);
		slamAttacking = true;
		MC.gameMode.attack(MC.player, target);
		MC.player.swing(InteractionHand.MAIN_HAND);
		slamAttacking = false;
		
		if(maceSlot != -1)
		{
			queueMaceSlam(target, maceSlot, startingSlot);
			queueSlamFollowUp(target, startingSlot, 3);
		}else
			queueSlamFollowUp(target, startingSlot, 2);
		
		return true;
	}
	
	private void queueMaceSlam(Entity target, int maceSlot, int returnSlot)
	{
		slamTarget = target;
		slamMaceSlot = maceSlot;
		slamReturnSlot = returnSlot;
		slamPending = true;
	}
	
	private void queueSlamFollowUp(Entity target, int returnSlot,
		int delayTicks)
	{
		slamFollowUpTarget = target;
		slamFollowUpSlot = returnSlot;
		slamFollowUpDelay = delayTicks;
		slamFollowUpPending = true;
	}
	
	private void tickStunSlam()
	{
		if(slamPending && slamTarget != null)
		{
			slamPending = false;
			int prev = MC.player.getInventory().getSelectedSlot();
			selectSlot(slamMaceSlot);
			
			if(!slamAttacking)
			{
				slamAttacking = true;
				MC.gameMode.attack(MC.player, slamTarget);
				MC.player.swing(InteractionHand.MAIN_HAND);
				slamAttacking = false;
			}
			
			selectSlot(slamReturnSlot != -1 ? slamReturnSlot : prev);
			slamTarget = null;
		}
		
		if(slamFollowUpPending && slamFollowUpDelay > 0
			&& --slamFollowUpDelay == 0)
		{
			slamFollowUpPending = false;
			
			if(slamFollowUpTarget != null)
			{
				selectSlot(slamFollowUpSlot);
				
				if(!slamAttacking)
				{
					slamAttacking = true;
					MC.gameMode.attack(MC.player, slamFollowUpTarget);
					MC.player.swing(InteractionHand.MAIN_HAND);
					slamAttacking = false;
				}
				
				slamFollowUpTarget = null;
			}
		}
	}
	
	private void restoreOriginalSlot()
	{
		if(originalSlot >= 0 && originalSlot <= 8)
			selectSlot(originalSlot);
		
		maceSwapped = false;
		originalSlot = -1;
	}
	
	// ── Lunge swapping ───────────────────────────────────────────────────
	
	@Override
	public void onLeftClick(LeftClickEvent event)
	{
		tryLungeSwap();
	}
	
	/**
	 * Switches to a spear so the click goes out as a lunge, unless you are
	 * already holding something worth swinging.
	 */
	private void tryLungeSwap()
	{
		if(MC.player == null || !lungeSwapping.isChecked() || lungeSwapPending)
			return;
		
		ItemStack held = MC.player.getMainHandItem();
		if(isSword(held) || isAxe(held) || isSpear(held) || held.is(Items.MACE))
			return;
		
		int spearSlot = findItem(ItemTags.SPEARS);
		if(spearSlot == -1)
			return;
		
		lungeReturnSlot = MC.player.getInventory().getSelectedSlot();
		selectSlot(spearSlot);
		lungeSwapPending = true;
		lungeDelay = lungeSwapDelay.getValueI();
	}
	
	private void tickLungeSwap()
	{
		if(!lungeSwapPending)
			return;
		
		if(lungeDelay > 0)
		{
			lungeDelay--;
			return;
		}
		
		lungeSwapPending = false;
		
		if(lungeReturnSlot != -1)
		{
			selectSlot(lungeReturnSlot);
			lungeReturnSlot = -1;
		}
	}
	
	// ── Pearl catching, wind charges & air pots ──────────────────────────
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(MC.player == null)
			return;
		
		// the main hand wins, same as vanilla's own use order
		for(InteractionHand hand : new InteractionHand[]{
			InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND})
		{
			ItemStack stack = MC.player.getItemInHand(hand);
			
			if(stack.is(Items.ENDER_PEARL))
			{
				onPearlThrown();
				return;
			}
			
			if(stack.is(Items.SPLASH_POTION))
			{
				onSplashPotThrown();
				return;
			}
		}
	}
	
	private void onPearlThrown()
	{
		if(!pearlCatching.isChecked())
			return;
		
		pearlThrown = true;
		pearlCatchTimer = pearlCatchDelay.getValueI();
		
		pearlReturnSlot = switch(pearlReturnMode.getSelected())
		{
			case SWORD -> findItem(ItemTags.SWORDS);
			case AXE -> findItem(ItemTags.AXES);
			case ELYTRA -> findItem(Items.ELYTRA);
			case PEARL -> findItem(Items.ENDER_PEARL);
			default -> previousSlot();
		};
		
		if(pearlReturnSlot == -1)
			pearlReturnSlot = previousSlot();
		
		boolean hasWindCharge = findItem(Items.WIND_CHARGE) != -1
			|| MC.player.getOffhandItem().is(Items.WIND_CHARGE);
		
		if(hasWindCharge
			&& MC.player.getXRot() <= -(float)pearlCatchAngle.getValue())
		{
			MC.player.setXRot(-90);
			cameraLocked = true;
		}
	}
	
	private void onSplashPotThrown()
	{
		if(!airPots.isChecked() || MC.player.onGround())
			return;
		
		airPotThrown = true;
		airPotWindDelay = 2;
	}
	
	private void tickPearlCatch()
	{
		if(!pearlThrown || --pearlCatchTimer > 0)
			return;
		
		pearlThrown = false;
		int slot = findItem(Items.WIND_CHARGE);
		
		if(slot != -1 && cameraLocked)
		{
			windSlot = slot;
			windReturnSlot = pearlReturnSlot;
			selectSlot(windSlot);
			windFireDelay = 2;
			return;
		}
		
		cameraLocked = false;
		windSlot = -1;
		windReturnSlot = -1;
		windFireDelay = 0;
	}
	
	private void tickWindRightClick()
	{
		boolean usePressed = MC.options.keyUse.isDown();
		boolean justPressed = usePressed && !wasUsePressed;
		wasUsePressed = usePressed;
		
		if(!justPressed || windActionCooldown > 0)
			return;
		
		ItemStack held = MC.player.getMainHandItem();
		if(!isSword(held) && !isAxe(held) && !held.is(Items.MACE))
			return;
		
		if(MC.player.getOffhandItem().is(Items.WIND_CHARGE))
		{
			windReturnSlot = -1;
			windSlot = -1;
			windFireDelay = 2;
			windActionCooldown = 10;
			return;
		}
		
		int slot = findItem(Items.WIND_CHARGE);
		if(slot == -1)
			return;
		
		windReturnSlot = MC.player.getInventory().getSelectedSlot();
		windSlot = slot;
		selectSlot(windSlot);
		windFireDelay = 2;
		windActionCooldown = 15;
	}
	
	private void fireWindCharge()
	{
		InteractionHand hand = windSlot == -1 ? InteractionHand.OFF_HAND
			: InteractionHand.MAIN_HAND;
		
		MC.gameMode.useItem(MC.player, hand);
		MC.player.swing(hand);
		
		MC.execute(() -> {
			if(MC.player == null)
				return;
			
			if(windReturnSlot != -1)
				selectSlot(windReturnSlot);
			
			cameraLocked = false;
			windSlot = -1;
			windReturnSlot = -1;
		});
	}
	
	private void fireAirPotWindCharge()
	{
		if(MC.player.getOffhandItem().is(Items.WIND_CHARGE))
		{
			MC.gameMode.useItem(MC.player, InteractionHand.OFF_HAND);
			MC.player.swing(InteractionHand.OFF_HAND);
			return;
		}
		
		int slot = findItem(Items.WIND_CHARGE);
		if(slot == -1)
			return;
		
		int returnSlot = MC.player.getInventory().getSelectedSlot();
		selectSlot(slot);
		MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
		MC.player.swing(InteractionHand.MAIN_HAND);
		
		MC.execute(() -> {
			if(MC.player != null)
				selectSlot(returnSlot);
		});
	}
	
	// ── Helpers ──────────────────────────────────────────────────────────
	
	private int previousSlot()
	{
		if(lastSelectedSlot != -1)
			return lastSelectedSlot;
		
		return MC.player.getInventory().getSelectedSlot();
	}
	
	/**
	 * Selects a hotbar slot and tells the server about it right away, instead
	 * of waiting for the client's own sync at the end of the tick.
	 */
	private void selectSlot(int slot)
	{
		if(slot < 0 || slot > 8)
			return;
		
		Inventory inventory = MC.player.getInventory();
		if(inventory.getSelectedSlot() == slot)
			return;
		
		inventory.setSelectedSlot(slot);
		
		if(MC.player.connection != null)
			MC.player.connection
				.send(new ServerboundSetCarriedItemPacket(slot));
	}
	
	private int findItem(TagKey<Item> tag)
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(!stack.isEmpty() && stack.is(tag))
				return i;
		}
		
		return -1;
	}
	
	private int findItem(Item item)
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(!stack.isEmpty() && stack.is(item))
				return i;
		}
		
		return -1;
	}
	
	private int findBestMace(boolean wantDensity)
	{
		ResourceKey<Enchantment> goal =
			wantDensity ? Enchantments.DENSITY : Enchantments.BREACH;
		int fallback = -1;
		
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(!stack.is(Items.MACE))
				continue;
			
			if(fallback == -1)
				fallback = i;
			
			if(hasEnchantment(stack, goal))
				return i;
		}
		
		return fallback;
	}
	
	private boolean hasEnchantment(ItemStack stack,
		ResourceKey<Enchantment> key)
	{
		ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
		if(enchantments == null)
			return false;
		
		for(Holder<Enchantment> holder : enchantments.keySet())
			if(holder.is(key))
				return true;
			
		return false;
	}
	
	private boolean isSword(ItemStack stack)
	{
		return stack.is(ItemTags.SWORDS);
	}
	
	private boolean isAxe(ItemStack stack)
	{
		return stack.is(ItemTags.AXES);
	}
	
	private boolean isSpear(ItemStack stack)
	{
		return stack.is(ItemTags.SPEARS);
	}
	
	private void fullReset()
	{
		originalSlot = -1;
		swapBackTimer = 0;
		maceSwapped = false;
		
		slamPending = false;
		slamTarget = null;
		slamMaceSlot = -1;
		slamReturnSlot = -1;
		
		slamFollowUpPending = false;
		slamFollowUpTarget = null;
		slamFollowUpSlot = -1;
		slamFollowUpDelay = 0;
		
		pearlThrown = false;
		pearlCatchTimer = 0;
		pearlReturnSlot = -1;
		cameraLocked = false;
		
		windFireDelay = 0;
		windSlot = -1;
		windReturnSlot = -1;
		windActionCooldown = 0;
		wasUsePressed = false;
		
		airPotThrown = false;
		airPotWindDelay = 0;
		
		lungeSwapPending = false;
		lungeReturnSlot = -1;
		lungeDelay = 0;
		
		lastSelectedSlot = -1;
		slamAttacking = false;
		
		triggerHitsThisFall = 0;
		triggerCooldownTimer = 0;
		wasInAir = false;
		chestplateEquipped = false;
		
		resetAim();
	}
	
	private enum MaceMode
	{
		DENSITY("Density"),
		BREACH("Breach");
		
		private final String name;
		
		private MaceMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum SwapScope
	{
		ALL("All"),
		WEAPONS_ONLY("Weapons only");
		
		private final String name;
		
		private SwapScope(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum ReturnSlot
	{
		PREVIOUS("Previous"),
		SWORD("Sword"),
		AXE("Axe"),
		ELYTRA("Elytra"),
		PEARL("Pearl");
		
		private final String name;
		
		private ReturnSlot(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum AimBone
	{
		EYE("Eye"),
		CHEST("Chest"),
		LEGS("Legs");
		
		private final String name;
		
		private AimBone(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum AimMode
	{
		WHILE_FALLING("While falling"),
		ALWAYS("Always");
		
		private final String name;
		
		private AimMode(String name)
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
