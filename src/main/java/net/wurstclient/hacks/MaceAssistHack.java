/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.util.PearlCatchSolver;
import net.wurstclient.util.KeyPresser;
import net.wurstclient.events.PacketInputListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.wurstclient.SearchTags;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.filters.FilterSpeedSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.CameraAim;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.HitCheck;
import net.wurstclient.util.HumanAim;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.PacketOrder;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

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
	LeftClickListener, RightClickListener, PacketInputListener
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
	
	private final CheckboxSetting pearlCatching = new CheckboxSetting(
		"Pearl catching",
		"After you throw an ender pearl, shoots it down with a wind charge"
			+ " so you land where it was - as high up as can be done for"
			+ " sure: the pearl's path and the shot are worked out with"
			+ " vanilla's own physics. On the ground, a throw steeper than 45"
			+ " degrees waits for a jump and one flatter for a sprint-jump,"
			+ " so the pearl gets your speed too.",
		true);
	
	private final SliderSetting pearlCatchAngle =
		new SliderSetting("Pearl catch angle",
			"How far up you have to be throwing for the pearl to be caught (0 ="
				+ " any throw from straight ahead up). Throws below this travel"
				+ " normally.",
			0, 0, 90, 1, ValueDisplay.DEGREES);
	
	private final CheckboxSetting pearlLaunchFirst = new CheckboxSetting(
		"Launch first",
		"On the ground, a pearl catch starts with a wind charge at your feet:"
			+ " the pearl goes up from the top of that jump, so the catch takes"
			+ " you higher (VexBot's \"higher\" pearl catch). Your right-click"
			+ " with the pearl starts it; the pearl is thrown at the top.",
		true);
	
	private final CheckboxSetting pearlChaining = new CheckboxSetting(
		"Chaining",
		"After a pearl catch, throws the next pearl straight up and catches it"
			+ " too, again and again, until you right-click yourself (or the"
			+ " pearls or wind charges run out). Starts like a normal pearl"
			+ " catch: right-click with a pearl while looking up past the"
			+ " catch angle.",
		false);
	
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
	
	private final CheckboxSetting cobwebOnRightClick = new CheckboxSetting(
		"Cobweb on right click",
		"Right-clicking a block with a sword puts a cobweb there, from your"
			+ " hotbar or offhand, then switches back to the sword.\n\n"
			+ "Comes before §lWind on right click§r: with cobwebs and wind"
			+ " charges both on you, you get the cobweb (the wind charge only"
			+ " when you aren't looking at a block).",
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
	
	private final CheckboxSetting maceAimAssist = new CheckboxSetting(
		"Mace aim assist",
		"Turns your camera sideways toward the nearest target while you"
			+ " fall, so the mace lands. Only sideways - looking up and down"
			+ " stays yours.",
		false);
	
	private final SliderSetting aimSpeed = new SliderSetting("Aim speed",
		"How fast the camera turns toward the target, in degrees per second -"
			+ " the top speed, and how quickly it settles (faster = firmer).\n\n"
			+ "On top of this it moves as fast as the target moves across your"
			+ " view, so once it's on the target it stays there.\n\n"
			+ "AimAssist turns at 720.",
		15, 0.5, 1800, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting aimRange = new SliderSetting("Aim range",
		"How far away a target can be before aim assist ignores it.", 15, 0.5,
		25, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting minFallDistance = new SliderSetting(
		"Min fall distance",
		"How far you have to have fallen before aim assist and the trigger bot"
			+ " kick in.",
		1.5, 0, 20, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final EnumSetting<AimMode> aimMode = new EnumSetting<>("Aim mode",
		"§lWhile falling§r - only aims during a mace drop.\n\n"
			+ "§lAlways§r - aims whenever a target is in range.",
		AimMode.values(), AimMode.WHILE_FALLING);
	
	private final CheckboxSetting humanizeAim = new CheckboxSetting(
		"Humanize aim",
		"The aim turns smoothly - speeding up and slowing down, never past"
			+ " the target - until your direction is anywhere on the target,"
			+ " and doesn't move the camera at all while it already is (it"
			+ " does keep following a target that slides across your view).\n\n"
			+ "On: a short reaction delay before turning to a new target, like"
			+ " a hand.\n\n"
			+ "Either way the camera turns every frame and moves in whole mouse"
			+ " counts of your real sensitivity, like an actual mouse.",
		true);
	
	private final CheckboxSetting approachTarget = new CheckboxSetting(
		"Approach target",
		"When the target is further than 3 blocks away sideways and you can"
			+ " still get there - before you fall to its height, worked out"
			+ " with vanilla's air movement - turns you toward it sideways"
			+ " only and holds W until you're within 3 blocks of it.\n\n"
			+ "On the ground (Aim mode §lAlways§r) it walks you there.",
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
	
	private final FilterSpeedSetting filterSpeed =
		FilterSpeedSetting.genericCombat(100);
	
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
	
	private final SliderSetting triggerRange = new SliderSetting(
		"Trigger range",
		"How close the target has to be for the trigger bot to attack.\n\n"
			+ "Never more than your real reach (3 blocks in survival) - a hit"
			+ " from further away is one the server rejects and flags.",
		3, 0.5, 6, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
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
	
	private int pearlReturnSlot = -1;
	
	/** A catch under way (see tickCatch); null = none. */
	private PearlCatch pearlCatch;
	/** Ticks counted in onUpdate - the clock catches are timed on. */
	private int clock;
	/** Our position at the start of the last tick (see knownMovement). */
	private Vec3 lastTickPos;
	/** The server's spawn packet for our newest pearl (netty thread). */
	private volatile PearlSpawn pearlSpawn;
	
	/** A jump or sprint-jump before the throw; null = none. */
	private ThrowPrep throwPrep;
	private int prepTicks;
	private InteractionHand prepHand;
	
	private enum ThrowPrep
	{
		JUMP,
		SPRINT_JUMP;
	}
	
	private record PearlSpawn(Vec3 pos, Vec3 vel, long nanos)
	{}
	
	/** A catch under way: the pearl thrown, its wind charge to come. */
	private static final class PearlCatch
	{
		/** The clock when the pearl's use packet went out. */
		final int throwTick;
		/** Where vanilla starts it and how fast, without the spread. */
		final Vec3 nominalSpawn;
		final Vec3 nominalVel;
		final long thrownNanos;
		/** From the server's spawn packet: the exact start, at this age. */
		Vec3 spawnPos;
		Vec3 spawnVel;
		int spawnAge = -1;
		boolean pearlSeen;
		/** The pearl's path from now ([0]) and its age now. */
		Vec3[] path;
		int age;
		/** The plan the camera turns to: fresh, or the locked one. */
		PearlCatchSolver.Plan plan;
		/**
		 * The plan kept while it still works (re-checked every tick): a new
		 * best plan every tick moved the target and the camera chased it
		 * until the window for a sure shot was gone.
		 */
		PearlCatchSolver.Plan locked;
		/** The clock tick the locked plan fires in. */
		int lockedFire;
		/** Ticks a fire was held back by the exact check. */
		int held;
		
		PearlCatch(int throwTick, Vec3 nominalSpawn, Vec3 nominalVel)
		{
			this.throwTick = throwTick;
			this.nominalSpawn = nominalSpawn;
			this.nominalVel = nominalVel;
			thrownNanos = System.nanoTime();
		}
	}
	
	/** The human-like turn up for pearl catches (the mace aim has its own). */
	private final CameraAim pearlAim = new CameraAim();
	private static final Object PEARL_AIM = new Object();
	private static final double PEARL_AIM_SPEED = 1500;
	/**
	 * What the solver counts on per tick (the humanized turn eases in and
	 * out, so less than the top speed's 75).
	 */
	private static final double PEARL_TURN_PER_TICK = 50;
	
	/** Chaining: where the chain is; null = no chain. */
	/**
	 * Launch first: DOWN = turning down for the wind charge at our feet, UP =
	 * rising, turning up, the pearl at the top; null = not launching.
	 */
	private LaunchStep launchStep;
	private int launchTicks;
	
	private enum LaunchStep
	{
		DOWN,
		UP;
	}
	
	private ChainStep chainStep;
	private Entity chainPearl;
	private int chainTicks;
	private int chainDelay;
	/** The slot to go back to when the chain ends. */
	private int chainHomeSlot = -1;
	/** The click that started the chain was let go of. */
	private boolean chainArmed;
	
	private enum ChainStep
	{
		/** The pearl is up: wind charge, then wait for it to land. */
		CATCHING,
		/** Turning up and throwing the next pearl. */
		THROWING;
	}
	
	private int windFireDelay;
	private int windSlot = -1;
	private int windReturnSlot = -1;
	private int windActionCooldown;
	private boolean wasUsePressed;
	/** Vanilla handled a right click this tick (see onUpdate). */
	private boolean rightClickedThisTick;
	
	/** The hotbar slot a cobweb goes down from next; -1 = none. */
	private int cobwebSlot = -1;
	/** The slot to switch back to after a cobweb; -1 = none. */
	private int cobwebReturnSlot = -1;
	
	private boolean airPotThrown;
	private int airPotWindDelay;
	
	private boolean lungeSwapPending;
	private int lungeReturnSlot = -1;
	private int lungeDelay;
	
	private int lastSelectedSlot = -1;
	private boolean slamAttacking;
	
	private Entity lockedTarget;
	private int losGraceTicks;
	/** The smooth, human-like aim, shared with AimAssist. */
	private final CameraAim cameraAim = new CameraAim();
	
	/**
	 * Where on the target the current turn goes, relative to the middle of
	 * its footprint: the part of the middle nearest the crosshair when the
	 * turn started. null = pick a new one.
	 */
	private Vec3 aimOffset;
	/** The target {@link #aimOffset} was picked on. */
	private Entity aimOffsetTarget;
	/** A turn is under way (until it has settled); otherwise the aim holds. */
	private boolean turning;
	
	/** Turning sideways toward the target and holding W, see Approach. */
	private boolean approaching;
	/** Whether this hack is holding W right now. */
	private boolean holdingForward;
	
	/** Within this many blocks sideways of the target, the approach stops. */
	private static final double APPROACH_DISTANCE = 3;
	
	/**
	 * How far inside the footprint's sides your direction has to cross it to
	 * count as on the target - the margin {@link HitCheck} wants, so a hit
	 * can go out.
	 */
	private static final double ON_TARGET_MARGIN = 0.1;
	/** ...at most this share of its half-size (as HitCheck: a quarter). */
	private static final double ON_TARGET_SHARE = 0.5;
	
	/**
	 * A turn aims for the middle third of the footprint (at least 0.2 blocks
	 * inside the sides), so it ends well inside the band where the aim holds.
	 */
	private static final double AIM_ZONE_MARGIN = 0.2;
	private static final double AIM_ZONE_SHARE = 0.65;
	
	/** A turn has settled within this many degrees of its point... */
	private static final double SETTLED_YAW = 0.5;
	/** ...while the target slides across your view slower than this, deg/s. */
	private static final double STILL_RATE = 5;
	
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
		addSetting(pearlCatchAngle);
		addSetting(pearlLaunchFirst);
		addSetting(pearlChaining);
		addSetting(pearlReturnMode);
		
		addSetting(windOnRightClick);
		addSetting(cobwebOnRightClick);
		addSetting(airPots);
		
		addSetting(lungeSwapping);
		addSetting(lungeSwapDelay);
		
		addSetting(maceAimAssist);
		addSetting(aimSpeed);
		addSetting(aimRange);
		addSetting(minFallDistance);
		addSetting(aimMode);
		addSetting(humanizeAim);
		addSetting(approachTarget);
		addSetting(checkLOS);
		
		addSetting(targetPlayers);
		addSetting(targetMannequins);
		addSetting(targetHostileMobs);
		addSetting(targetPassiveMobs);
		addSetting(targetArmorStands);
		addSetting(filterSpeed);
		
		addSetting(maceTriggerBot);
		addSetting(triggerFallingOnly);
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
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		EVENTS.remove(LeftClickListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		
		if(MC.player != null)
		{
			restoreOriginalSlot();
			// back to the sword if a cobweb was just put down
			if(cobwebReturnSlot != -1 && PacketOrder.canChangeSlotNow())
				selectSlot(cobwebReturnSlot);
		}
		
		fullReset();
	}
	
	// ── Tick ─────────────────────────────────────────────────────────────
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		clock++;
		
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
		
		// (a pearl catch turns the camera up in onRender)
		
		if(windActionCooldown > 0)
			windActionCooldown--;
		
		boolean usePressed = MC.options.keyUse.isDown();
		// a fresh click: held now, or a tap vanilla handled that was already
		// let go of by the time the tick polls the key
		boolean usePressedNow =
			!wasUsePressed && (usePressed || rightClickedThisTick);
		rightClickedThisTick = false;
		wasUsePressed = usePressed;
		
		tickCobweb();
		// a cobweb, when one can go down, comes before the wind charge
		if(usePressedNow && !startCobweb() && windOnRightClick.isChecked())
			tickWindRightClick();
		
		tickStunSlam();
		
		if(maceSwapped && --swapBackTimer <= 0)
			restoreOriginalSlot();
		
		tickThrowPrep();
		tickLaunch();
		tickChain();
		tickCatch();
		
		if(windFireDelay > 0 && --windFireDelay == 0)
			fireWindCharge();
		
		if(airPotThrown && --airPotWindDelay <= 0)
		{
			airPotThrown = false;
			fireAirPotWindCharge();
		}
		
		// (before this tick's movement: next tick, the movement from here is
		// the one the server knows)
		lastTickPos = MC.player.position();
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
		
		// (the camera itself is turned every frame, in onRender)
		if(!aimActive)
		{
			cameraAim.reset();
			aimOffset = null;
		}
		
		updateApproach(aimActive);
		
		if(lockedTarget == null)
			return;
		
		tickAutoChestplate();
		
		if(!triggerActive)
			return;
			
		// Once per tick, here in the tick - right before the movement packet
		// that carries the rotation this hit was aimed with, like a vanilla
		// click. (It used to also check slices of the tick with your and the
		// target's positions moved ahead, and every rendered frame - hits on
		// positions the server never saw, which Grim flags as "HITBOX: hit
		// without any intersection".)
		tryTriggerAttack();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null || MC.level == null)
			return;
			
		// A pearl catch looks straight up - turned like a hand flicking up
		// (user, 2026-10-17: "humanize the aim aiming up"; it used to snap
		// there in one frame).
		if(launchStep != null)
		{
			pearlAim.turnCamera(PEARL_AIM,
				new Rotation(MC.player.getYRot(),
					launchStep == LaunchStep.DOWN ? 80 : -90),
				new double[2], true, PEARL_AIM_SPEED);
			return;
		}
		
		PearlCatch c = pearlCatch;
		if(c != null || chainStep == ChainStep.THROWING)
		{
			// the catch's shot (see tickCatch); before there's a plan, at the
			// pearl a few ticks ahead; before it's seen, straight up
			Rotation needed;
			if(c != null && c.plan != null)
				needed = c.plan.rotation();
			else if(c != null && c.path != null)
				needed = RotationUtils.getNeededRotations(
					CameraAim.getFrameEyes(partialTicks),
					c.path[Math.min(6, c.path.length - 1)]);
			else
				needed = new Rotation(MC.player.getYRot(), -90);
			pearlAim.turnCamera(PEARL_AIM, needed, new double[2], true,
				PEARL_AIM_SPEED);
			return;
		}
		pearlAim.reset();
		
		if(lockedTarget == null)
			return;
			
		// While AimAssist turns the camera, it owns it. Both aiming at once -
		// MaceAssist at its target's chest, AimAssist at its own target's
		// center - flicked the view up and sideways whenever you fell far
		// enough for the mace aim to start.
		if(WURST.getHax().aimAssistHack.isTurningCamera())
		{
			cameraAim.reset();
			return;
		}
		
		// Turned every frame, like a mouse - sideways only: the pitch is all
		// yours.
		if(!isAimActive())
			return;
		
		Vec3 eyes = CameraAim.getFrameEyes(partialTicks);
		Vec3 drawnOffset = EntityUtils.getLerpedPos(lockedTarget, partialTicks)
			.subtract(lockedTarget.position());
		AABB footprint = footprint(lockedTarget.getBoundingBox()
			.inflate(lockedTarget.getPickRadius()).move(drawnOffset), eyes);
		Vec3 look = new Rotation(MC.player.getYRot(), 0).toLookVec();
		
		// On target: your direction crosses its footprint, a little inside
		// the sides (directly above it, any direction does).
		AABB band = inset(footprint, ON_TARGET_MARGIN, ON_TARGET_SHARE);
		boolean onTarget = isRayOn(eyes, look, band);
		
		// Held still while on it. A turn starts once the crosshair has slid
		// off, and runs until it has settled near the middle - so it neither
		// stops dead at the edge nor starts and stops along it.
		if(!turning && onTarget || band.contains(eyes))
		{
			turning = false;
			cameraAim.hold(lockedTarget);
			return;
		}
		
		AABB aimZone = inset(footprint, AIM_ZONE_MARGIN, AIM_ZONE_SHARE);
		if(!turning || aimOffset == null || aimOffsetTarget != lockedTarget)
		{
			// the part of the middle nearest the crosshair, kept relative to
			// the target for the whole turn (picked afresh every frame, it
			// would move with the camera and the aim would chase itself)
			turning = true;
			aimOffsetTarget = lockedTarget;
			aimOffset = nearestPointToRay(eyes, look, aimZone)
				.subtract(aimZone.getCenter());
		}
		Vec3 point = aimZone.getCenter().add(aimOffset);
		
		cameraAim.turnYawSmoothly(lockedTarget, point.subtract(drawnOffset),
			partialTicks, humanizeAim.isChecked(), aimSpeed.getValue());
		
		// Settled once there and the target isn't sliding across your view;
		// while it is, the aim keeps following it smoothly instead of
		// stopping and catching up again.
		double left = Math.abs(
			Mth.wrapDegrees(RotationUtils.getNeededRotations(eyes, point).yaw()
				- MC.player.getYRot()));
		double sliding = Math.abs(HumanAim.trackingRate(eyes, point,
			HumanAim.relativeVelocity(lockedTarget, MC.player))[0]);
		if(left < SETTLED_YAW && sliding < STILL_RATE)
			turning = false;
	}
	
	/**
	 * The target's footprint, stretched up and down to your eye height, for
	 * checking a sideways-only aim against.
	 */
	private static AABB footprint(AABB box, Vec3 eyes)
	{
		return new AABB(box.minX, eyes.y - 1, box.minZ, box.maxX, eyes.y + 1,
			box.maxZ);
	}
	
	/**
	 * Shrinks the box by {@code side} on each side - never by more than
	 * {@code maxShare} of its half-size.
	 */
	private static AABB inset(AABB box, double side, double maxShare)
	{
		double s = Math.min(side,
			Math.min(box.getXsize(), box.getZsize()) / 2 * maxShare);
		return box.deflate(s, 0, s);
	}
	
	/** Whether a ray from the eyes along the look passes through the box. */
	private static boolean isRayOn(Vec3 eyes, Vec3 look, AABB box)
	{
		if(box.contains(eyes))
			return true;
		
		double far = eyes.distanceTo(box.getCenter()) + box.getSize() * 2 + 1;
		return box.clip(eyes, eyes.add(look.scale(far))).isPresent();
	}
	
	/**
	 * The point in the box nearest the ray from the eyes along the look: the
	 * ray's point at the box's depth, pulled into the box, refined once.
	 */
	private static Vec3 nearestPointToRay(Vec3 eyes, Vec3 look, AABB box)
	{
		Vec3 point = box.getCenter();
		for(int i = 0; i < 2; i++)
		{
			double t = Math.max(0.1, point.subtract(eyes).dot(look));
			Vec3 onRay = eyes.add(look.scale(t));
			point = new Vec3(Mth.clamp(onRay.x, box.minX, box.maxX),
				Mth.clamp(onRay.y, box.minY, box.maxY),
				Mth.clamp(onRay.z, box.minZ, box.maxZ));
		}
		return point;
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
				&& getAimDistanceSq(lockedTarget) <= maxDistSq;
			
			if(!valid)
			{
				lockedTarget = null;
				losGraceTicks = 0;
				cameraAim.reset();
				
			}else if(!hasLineOfSight(lockedTarget))
			{
				if(losGraceTicks > 0)
					losGraceTicks--;
				else
				{
					lockedTarget = null;
					cameraAim.reset();
				}
				
			}else
				losGraceTicks = 3;
		}
		
		if(lockedTarget != null)
			return;
		
		double bestDist = maxDistSq;
		Vec3 eyes = MC.player.getEyePosition();
		Vec3 look = MC.player.getLookAngle();
		
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!isValidTarget(e))
				continue;
			
			double distSq = getAimDistanceSq(e);
			if(distSq >= bestDist)
				continue;
				
			// only targets in front of you - along your real (3D) look, so a
			// target below you counts while diving down at it (by yaw alone,
			// one just behind your yaw direction never got picked) - or one
			// you're falling onto, wherever you look
			Vec3 toTarget = e.getBoundingBox().getCenter().subtract(eyes);
			if(look.dot(toTarget) <= 0 && !isFallingOnto(e))
				continue;
			
			// last: from high up, a long raycast
			if(!hasLineOfSight(e))
				continue;
			
			bestDist = distSq;
			lockedTarget = e;
		}
		
		losGraceTicks = 3;
		// (a new target starts a fresh turn with a reaction delay - see
		// CameraAim)
	}
	
	/**
	 * How far away a target is for §lAim range§r: straight-line, except one
	 * you're falling onto, which counts by its distance sideways - dropping
	 * from 10,000 blocks, it has to be locked long before it's 15 blocks
	 * away (at 50 gravity there's no tick in between).
	 */
	private double getAimDistanceSq(Entity e)
	{
		if(!isFallingOnto(e))
			return MC.player.distanceToSqr(e);
		
		double dx = e.getX() - MC.player.getX();
		double dz = e.getZ() - MC.player.getZ();
		return dx * dx + dz * dz;
	}
	
	/** Whether you're falling and the target is below you. */
	private boolean isFallingOnto(Entity e)
	{
		return isFalling() && e.getBoundingBox().maxY <= MC.player.getY();
	}
	
	private boolean isValidTarget(Entity e)
	{
		// also drops a target left over from the previous world or dimension
		if(e == null || e == MC.player || !e.isAlive() || e.isRemoved()
			|| e.level() != MC.level)
			return false;
		
		if(WURST.getFriends().isFriend(e))
			return false;
		
		// teleporting anti-cheat bots
		if(filterSpeed.isFilterEnabled() && !filterSpeed.test(e))
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
	
	/**
	 * Approach: while the target is more than {@link #APPROACH_DISTANCE}
	 * blocks away sideways and you can still get there, the aim turns
	 * sideways only (see onRender) and W is held - the real key, like a
	 * player pressing it.
	 */
	private void updateApproach(boolean aimActive)
	{
		approaching = aimActive && approachTarget.isChecked()
			&& lockedTarget != null && MC.screen == null
			&& !WURST.getHax().aimAssistHack.isTurningCamera()
			&& getSidewaysDistance(lockedTarget) > APPROACH_DISTANCE
			&& canReachSideways(lockedTarget);
		
		if(approaching)
		{
			MC.options.keyUp.setDown(true);
			holdingForward = true;
			
		}else
			releaseForward();
	}
	
	private void releaseForward()
	{
		if(!holdingForward)
			return;
		
		// back to whatever the physical key says
		IKeyMapping.get(MC.options.keyUp).resetPressedState();
		holdingForward = false;
	}
	
	private double getSidewaysDistance(Entity target)
	{
		return Math.hypot(target.getX() - MC.player.getX(),
			target.getZ() - MC.player.getZ());
	}
	
	/**
	 * Whether holding W toward the target gets you within
	 * {@link #APPROACH_DISTANCE} blocks of it sideways before you've fallen
	 * to its height - vanilla's air movement, tick by tick: W adds 0.02 (0.026
	 * sprinting) times 0.98 per tick, sideways speed keeps 91% per tick, the
	 * fall speed loses gravity and then 2% per tick. Starts from how fast
	 * you're already moving toward it and counts in how fast it moves away.
	 * On the ground you can walk there, so yes. Blocks in the way aren't
	 * looked at.
	 */
	private boolean canReachSideways(Entity target)
	{
		LocalPlayer player = MC.player;
		if(player.onGround())
			return true;
		
		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		double distance = Math.hypot(dx, dz);
		double needed = distance - APPROACH_DISTANCE;
		if(needed <= 0)
			return true;
		double dirX = dx / distance;
		double dirZ = dz / distance;
		
		Vec3 velocity = player.getDeltaMovement();
		double speed = velocity.x * dirX + velocity.z * dirZ;
		double accel = (player.isSprinting() ? 0.026 : 0.02) * 0.98;
		double targetAway = (target.getX() - target.xo) * dirX
			+ (target.getZ() - target.zo) * dirZ;
		
		double fallSpeed = velocity.y;
		double gravity = player.getGravity();
		double height = player.getY() - target.getY();
		double covered = 0;
		
		// at most 10 seconds of falling
		for(int tick = 1; tick <= 200; tick++)
		{
			speed += accel;
			covered += speed;
			speed *= 0.91;
			
			height += fallSpeed - (target.getY() - target.yo);
			fallSpeed = (fallSpeed - gravity) * 0.98;
			
			if(covered >= needed + targetAway * tick)
				return true;
			
			// fallen to its height: too late
			if(height <= 0 && fallSpeed < 0)
				return false;
		}
		
		return false;
	}
	
	private void resetAim()
	{
		lockedTarget = null;
		aimOffset = null;
		aimOffsetTarget = null;
		approaching = false;
		releaseForward();
		turning = false;
		losGraceTicks = 0;
		cameraAim.reset();
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
	private boolean tryTriggerAttack()
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
		
		if(!isCrosshairOnTarget(lockedTarget)
			|| !HitCheck.isVerifiable(lockedTarget))
			return false;
		
		attackTarget(lockedTarget);
		return true;
	}
	
	/**
	 * Whether the crosshair is on the target right now, checked the way the
	 * server checks a hit: from where your eyes are, along the rotation going
	 * out with this tick's movement packet, no further than your real reach
	 * (and Trigger range), against the target's hitbox as you see it, with
	 * no block in between.
	 */
	private boolean isCrosshairOnTarget(Entity target)
	{
		Vec3 eyes = MC.player.getEyePosition();
		double reach = Math.min(triggerRange.getValue(),
			MC.player.entityInteractionRange());
		Vec3 end = eyes.add(MC.player.getLookAngle().scale(reach));
		
		AABB box = target.getBoundingBox().inflate(target.getPickRadius());
		if(box.contains(eyes))
			return true;
		
		Optional<Vec3> hit = box.clip(eyes, end);
		return hit.isPresent() && BlockUtils.hasLineOfSight(eyes, hit.get());
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
		
		// the chestplate has to be in hand on the server before the use
		if(!PacketOrder.canChangeSlotNow())
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
		useItemAndSwing(InteractionHand.MAIN_HAND);
		
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
			
		// Something already went out this tick (an earlier hit, a use...), so a
		// swap now would only reach the server next tick - after this hit,
		// which then wouldn't get the mace anyway. Grim flags slot changes
		// after a hit in the same tick (PacketOrderE). See PacketOrder.
		if(!PacketOrder.canChangeSlotNow())
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
		// A queued hit whose target died, despawned or got left behind in
		// another dimension is dropped. Entity IDs get reused, so attacking
		// it anyway could hit something else entirely.
		if(slamTarget != null && !isStillAttackable(slamTarget))
		{
			slamPending = false;
			slamTarget = null;
		}
		if(slamFollowUpTarget != null && !isStillAttackable(slamFollowUpTarget))
			slamFollowUpTarget = null;
		
		// (waits for a tick where the mace can go out before the hit)
		if(slamPending && slamTarget != null && PacketOrder.canChangeSlotNow())
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
			if(!PacketOrder.canChangeSlotNow())
			{
				// the slam just went out - follow up next tick
				slamFollowUpDelay = 1;
				return;
			}
			
			slamFollowUpPending = false;
			
			// Back to the item you started with even if the target is gone -
			// otherwise you'd be left holding the axe or mace.
			selectSlot(slamFollowUpSlot);
			
			if(slamFollowUpTarget != null)
			{
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
	
	private boolean isStillAttackable(Entity e)
	{
		return e.isAlive() && !e.isRemoved() && e.level() == MC.level;
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
		
		// the spear would only reach the server after this click
		if(!PacketOrder.canChangeSlotNow())
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
		
		// While launching first, the launch throws the pearl.
		if(launchStep != null)
		{
			event.cancel();
			return;
		}
		
		// While chaining, the chain throws; a click of yours (after letting
		// go of the one that started it) ends it - and does nothing else.
		if(chainStep != null)
		{
			event.cancel();
			if(chainArmed)
			{
				stopChain();
				wasUsePressed = true;
			}
			return;
		}
		
		rightClickedThisTick = true;
		
		// the main hand wins, same as vanilla's own use order
		for(InteractionHand hand : new InteractionHand[]{
			InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND})
		{
			ItemStack stack = MC.player.getItemInHand(hand);
			
			if(stack.is(Items.ENDER_PEARL))
			{
				// Launch first: this pearl goes up from the top of a jump
				if(startLaunch())
				{
					event.cancel();
					return;
				}
				// a jump or sprint-jump first: thrown in the air
				if(startThrowPrep(hand))
				{
					event.cancel();
					return;
				}
				// vanilla throws it now, before this tick's onUpdate
				onPearlThrown(clock + 1);
				return;
			}
			
			if(stack.is(Items.SPLASH_POTION))
			{
				onSplashPotThrown();
				return;
			}
		}
	}
	
	/** A pearl of ours was thrown (its use packet out at that clock tick). */
	private void onPearlThrown(int throwTick)
	{
		if(!pearlCatching.isChecked())
			return;
		
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
		
		if(isCatchThrow())
		{
			startCatch(throwTick);
			if(pearlChaining.isChecked())
				startChain();
		}
	}
	
	/** A throw from here would be caught: wind charges, steep enough. */
	private boolean isCatchThrow()
	{
		return hasWindCharge()
			&& MC.player.getXRot() <= -(float)pearlCatchAngle.getValue();
	}
	
	private boolean hasWindCharge()
	{
		return findItem(Items.WIND_CHARGE) != -1
			|| MC.player.getOffhandItem().is(Items.WIND_CHARGE);
	}
	
	/** Looking up enough for a catch's pearl or wind charge. */
	private boolean isLookingUp()
	{
		return MC.player.getXRot() <= -87;
	}
	
	// ── The catch ──
	// User, 2026-10-17: "calculates the EXACT trajectory of the pearl ...
	// and calculates the angle you need to throw the wind-charge", from
	// "virtually any angle", a jump timed before a throw straight up and a
	// sprint-jump before one between straight ahead and 45 degrees up. The
	// physics and the solver: util/PearlCatchSolver. Here: what the server
	// will use - the rotation in the use packet, the movement it knows (the
	// last tick's), the pearl's own spawn packet - and when.
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		// netty thread: only note it (tickCatch uses it). An entity's spawn
		// comes inside a bundle with its data - the bundle is what arrives.
		if(event.getPacket() instanceof ClientboundBundlePacket bundle)
		{
			for(Packet<?> packet : bundle.subPackets())
				notePearlSpawn(packet);
			return;
		}
		notePearlSpawn(event.getPacket());
	}
	
	private void notePearlSpawn(Packet<?> p)
	{
		if(!(p instanceof ClientboundAddEntityPacket packet)
			|| packet.getType() != EntityType.ENDER_PEARL)
			return;
		LocalPlayer player = MC.player;
		if(player == null || packet.getData() != player.getId())
			return;
		pearlSpawn = new PearlSpawn(
			new Vec3(packet.getX(), packet.getY(), packet.getZ()),
			packet.getMovement(), System.nanoTime());
	}
	
	/**
	 * The movement the server knows for us right now: the last tick's (our
	 * last movement packet's position change).
	 */
	private Vec3 knownMovement()
	{
		return lastTickPos == null ? Vec3.ZERO
			: MC.player.position().subtract(lastTickPos);
	}
	
	/** Our pearl was just thrown: the catch starts. */
	private void startCatch(int throwTick)
	{
		LocalPlayer player = MC.player;
		Vec3 dir = PearlCatchSolver.direction(Mth.wrapDegrees(player.getYRot()),
			Mth.wrapDegrees(player.getXRot()));
		Vec3 spawn = new Vec3(player.getX(),
			player.getEyeY() - PearlCatchSolver.PEARL_SPAWN_DROP,
			player.getZ());
		pearlCatch = new PearlCatch(throwTick, spawn, PearlCatchSolver
			.launch(dir, Vec3.ZERO, knownMovement(), player.onGround()));
	}
	
	/**
	 * A catch throw on the ground: jump (steeper than 45 degrees) or
	 * sprint-jump (flatter) first, and throw in the first tick in the air -
	 * vanilla gives the pearl the thrower's movement, the jump's included.
	 */
	private boolean startThrowPrep(InteractionHand hand)
	{
		if(!pearlCatching.isChecked() || !MC.player.onGround()
			|| throwPrep != null || pearlCatch != null || chainStep != null
			|| launchStep != null || !isCatchThrow())
			return false;
		
		throwPrep =
			MC.player.getXRot() <= -45 ? ThrowPrep.JUMP : ThrowPrep.SPRINT_JUMP;
		prepTicks = 0;
		prepHand = hand;
		return true;
	}
	
	private void tickThrowPrep()
	{
		if(throwPrep == null)
			return;
		
		LocalPlayer player = MC.player;
		if(++prepTicks > 30
			|| !player.getItemInHand(prepHand).is(Items.ENDER_PEARL))
		{
			throwPrep = null;
			return;
		}
		
		if(player.onGround())
		{
			if(throwPrep == ThrowPrep.SPRINT_JUMP)
			{
				// W and sprint until sprinting, then the jump with them
				KeyPresser.press(MC.options.keyUp, 2);
				KeyPresser.press(MC.options.keySprint, 2);
				if(prepTicks < 3 || !player.isSprinting() && prepTicks < 10)
					return;
			}
			KeyPresser.press(MC.options.keyJump);
			return;
		}
		
		// the first tick in the air: the jump is the movement the server
		// knows now
		if(isOnCooldown(Items.ENDER_PEARL))
			return;
		throwPrep = null;
		if(InteractionSimulator.rightClickItem(prepHand))
			onPearlThrown(clock);
	}
	
	private void tickCatch()
	{
		PearlCatch c = pearlCatch;
		if(c == null)
			return;
			
		// its moves on the server so far (the wind charge fired now comes
		// that many ticks after it)
		int now = clock - c.throwTick;
		if(now > 100)
		{
			pearlCatch = null;
			return;
		}
		
		// the server's spawn packet: where it really started (the random
		// spread is in it), at which of its moves
		PearlSpawn spawn = pearlSpawn;
		if(c.spawnAge < 0 && spawn != null && spawn.nanos() >= c.thrownNanos)
		{
			c.spawnPos = spawn.pos();
			c.spawnVel = spawn.vel();
			c.spawnAge = matchAge(c, spawn.pos());
		}
		
		// gone: it landed, hit something, or was caught
		if(findOwnPearl() != null)
			c.pearlSeen = true;
		else if(c.pearlSeen || now > 20)
		{
			pearlCatch = null;
			return;
		}
		
		boolean exact = c.spawnAge >= 0;
		Vec3 pos = exact ? c.spawnPos : c.nominalSpawn;
		Vec3 vel = exact ? c.spawnVel : c.nominalVel;
		int from = exact ? c.spawnAge : 0;
		if(now < from)
			return;
		for(int a = from; a < now; a++)
		{
			vel = vel.add(0, -PearlCatchSolver.PEARL_GRAVITY, 0)
				.scale(PearlCatchSolver.PEARL_INERTIA);
			pos = pos.add(vel);
		}
		c.path = PearlCatchSolver.pearlPath(pos, vel, PearlCatchSolver.HORIZON);
		c.age = now;
		
		// the wind charge in hand from the tick after the throw: the
		// steepest throws' sure shots go 2-4 ticks after it
		InteractionHand windHand = windHand();
		
		PearlCatchSolver.Shooter[] who = predictShooters(50);
		int earliest = windCooldownTicks();
		if(windHand == null)
			earliest = Math.max(earliest, 1);
			
		// (the camera turns toward a plan on the nominal path; only one on
		// the exact path is locked and fired)
		if(!exact)
		{
			c.plan = solveCatch(c, who, earliest);
			return;
		}
		
		// keep the locked plan while it still works on the exact path
		if(c.locked != null)
		{
			int fire = c.lockedFire - clock;
			if(fire < 0 || fire >= who.length
				|| !stillCatches(c, who[fire], fire, c.locked))
				c.locked = null;
		}
		// a new one when there's none - or a sure one over an unsure one
		if(c.locked == null || !c.locked.sure())
		{
			PearlCatchSolver.Plan fresh = solveCatch(c, who, earliest);
			// Only a sure plan is locked; an unsure one only once the
			// pearl is near the top of its flight and nothing better can
			// come. (Locking the best of a poor first tick fired 8-60% shots
			// the moment the exact path was known - a tick or two of turning
			// later, sure ones were in reach.)
			if(fresh != null
				&& (fresh.sure() || fresh.rate() >= 0.5 || now >= 40)
				&& (c.locked == null || fresh.sure() && !c.locked.sure()))
			{
				c.locked = fresh;
				c.lockedFire = clock + fresh.fire();
			}
			// the camera turns toward the best plan meanwhile
			c.plan = c.locked != null ? c.locked : fresh;
		}else
			c.plan = c.locked;
		if(c.locked == null)
			return;
		
		InteractionHand hand = windHand;
		if(hand == null || c.lockedFire > clock
			|| isOnCooldown(Items.WIND_CHARGE))
			return;
			
		// the exact check, with the rotation the use packet will carry: sure
		// now; 50%+ once a tick of waiting didn't get it sure (the window
		// only gets worse); anything at all once the pearl is near its top
		double rate = currentRate(c, who[0]);
		boolean go = rate >= PearlCatchSolver.MIN_RATE
			|| rate >= 0.5 && c.held >= 1 || rate > 0 && now >= 40;
		if(!go)
		{
			// the camera didn't make it: a new plan next tick
			c.held++;
			c.locked = null;
			return;
		}
		
		if(!useItemAndSwingResult(hand))
			return;
		pearlCatch = null;
		int back = pearlReturnSlot;
		if(hand == InteractionHand.MAIN_HAND && back != -1)
			MC.execute(() -> {
				if(MC.player != null)
					selectSlot(back);
			});
	}
	
	/**
	 * Which of the pearl's moves its spawn packet was sent at: the one whose
	 * nominal position (no spread) is nearest.
	 */
	private static int matchAge(PearlCatch c, Vec3 pos)
	{
		Vec3[] nominal =
			PearlCatchSolver.pearlPath(c.nominalSpawn, c.nominalVel, 6);
		int best = 0;
		for(int a = 1; a < nominal.length; a++)
			if(nominal[a].distanceToSqr(pos) < nominal[best].distanceToSqr(pos))
				best = a;
		return best;
	}
	
	/**
	 * Where we'll be for each tick a wind charge could be fired in: eyes,
	 * the movement the server will know, on the ground - vanilla's air
	 * physics until we hit something.
	 */
	private PearlCatchSolver.Shooter[] predictShooters(int ticks)
	{
		LocalPlayer player = MC.player;
		PearlCatchSolver.Shooter[] who = new PearlCatchSolver.Shooter[ticks];
		double eye = player.getEyeY() - player.getY();
		Vec3 pos = player.position();
		Vec3 vel = player.getDeltaMovement();
		Vec3 known = knownMovement();
		boolean ground = player.onGround();
		AABB box = player.getBoundingBox();
		for(int f = 0; f < ticks; f++)
		{
			who[f] =
				new PearlCatchSolver.Shooter(pos.add(0, eye, 0), known, ground);
			if(ground)
			{
				known = Vec3.ZERO;
				continue;
			}
			Vec3 next = pos.add(vel);
			AABB moved = box.move(next.subtract(player.position()));
			if(!MC.level.noCollision(player, moved))
			{
				ground = true;
				known = Vec3.ZERO;
				continue;
			}
			known = next.subtract(pos);
			pos = next;
			vel = new Vec3(vel.x * 0.91, (vel.y - 0.08) * 0.98, vel.z * 0.91);
		}
		return who;
	}
	
	/** Whether a planned shot, fired f ticks from now, still catches. */
	private boolean stillCatches(PearlCatch c, PearlCatchSolver.Shooter s,
		int fire, PearlCatchSolver.Plan plan)
	{
		Vec3 vel = PearlCatchSolver.launch(plan.dir(), Vec3.ZERO, s.known(),
			s.onGround());
		if(PearlCatchSolver.hitMove(c.path, c.age, fire, s.eyes(), vel) < 0)
			return false;
		return !plan.sure() || PearlCatchSolver.isSure(c.path, c.age, fire,
			s.eyes(), plan.dir(), s.known(), s.onGround());
	}
	
	/** Ticks until a wind charge can be used again (10 after the last). */
	private int windCooldownTicks()
	{
		float left = MC.player.getCooldowns()
			.getCooldownPercent(new ItemStack(Items.WIND_CHARGE), 0);
		return (int)Math.ceil(left * 10);
	}
	
	/**
	 * The highest sure catch the camera can turn to in time (the turn counted
	 * in yaw and pitch: near straight up a small change of direction can be
	 * a big yaw swing - counted as one, every shot came too late for the
	 * sure window 2-4 ticks after a straight-up throw).
	 */
	private PearlCatchSolver.Plan solveCatch(PearlCatch c,
		PearlCatchSolver.Shooter[] who, int earliest)
	{
		return PearlCatchSolver.solve(c.path, c.age, who, earliest, 40,
			new Rotation(MC.player.getYRot(), MC.player.getXRot()),
			PEARL_TURN_PER_TICK);
	}
	
	/**
	 * How often a wind charge used now, with the camera's rotation as it is
	 * (the one the use packet carries; the server wraps it), catches the
	 * pearl.
	 */
	private double currentRate(PearlCatch c, PearlCatchSolver.Shooter now)
	{
		Vec3 dir =
			PearlCatchSolver.direction(Mth.wrapDegrees(MC.player.getYRot()),
				Mth.wrapDegrees(MC.player.getXRot()));
		return PearlCatchSolver.hitRate(c.path, c.age, 0, now.eyes(), dir,
			now.known(), now.onGround());
	}
	// ── Chaining ──
	// User, 2026-10-17: in an elytra mace kit (pearls + wind charges) others
	// "always get WAY higher" with pearl catches - one catch after the next.
	// The chain: the catch's wind charge, the pearl lands (it's gone), a
	// short pause, turn up and throw the next pearl once vanilla's pearl
	// cooldown (20 ticks) is over, catch it, and so on.
	
	/** The pearl that started it was just thrown; this catch is the first. */
	private void startChain()
	{
		chainStep = ChainStep.CATCHING;
		chainTicks = 0;
		chainPearl = null;
		chainHomeSlot = pearlReturnSlot;
		chainArmed = !MC.options.keyUse.isDown();
		// between catches, the wind charge goes back to the pearls
		int pearls = findItem(Items.ENDER_PEARL);
		pearlReturnSlot =
			pearls != -1 ? pearls : MC.player.getInventory().getSelectedSlot();
	}
	
	private void tickChain()
	{
		if(chainStep == null)
			return;
		
		if(!pearlChaining.isChecked())
		{
			stopChain();
			return;
		}
		
		if(!MC.options.keyUse.isDown())
			chainArmed = true;
		chainTicks++;
		
		if(chainStep == ChainStep.CATCHING)
		{
			// the catch's wind charge first
			if(pearlCatch != null || windFireDelay > 0)
			{
				if(chainTicks > 60)
					stopChain();
				return;
			}
			
			if(chainPearl == null)
				chainPearl = findOwnPearl();
			// not seen at all: it landed before we looked
			boolean landed = chainPearl == null ? chainTicks > 10
				: chainPearl.isRemoved() || chainPearl.level() != MC.level;
			if(!landed)
			{
				if(chainTicks > 200)
					stopChain();
				return;
			}
			
			chainStep = ChainStep.THROWING;
			chainTicks = 0;
			chainPearl = null;
			// a moment to react to the landing, like a player
			chainDelay = 2 + (int)(Math.random() * 3);
			return;
		}
		
		// THROWING: turning up (onRender), then the next pearl
		if(!hasWindCharge())
		{
			stopChain();
			return;
		}
		
		InteractionHand hand = MC.player.getMainHandItem().is(Items.ENDER_PEARL)
			? InteractionHand.MAIN_HAND
			: MC.player.getOffhandItem().is(Items.ENDER_PEARL)
				? InteractionHand.OFF_HAND : null;
		if(hand == null)
		{
			int slot = findItem(Items.ENDER_PEARL);
			if(slot == -1)
				stopChain();
			else if(PacketOrder.canChangeSlotNow())
				selectSlot(slot);
			return;
		}
		
		if(chainTicks < chainDelay || !isLookingUp() || MC.player.getCooldowns()
			.isOnCooldown(MC.player.getItemInHand(hand)))
		{
			if(chainTicks > 100)
				stopChain();
			return;
		}
		
		if(!InteractionSimulator.rightClickItem(hand))
			return;
		
		// the same catch as after a pearl of yours
		pearlReturnSlot = MC.player.getInventory().getSelectedSlot();
		startCatch(clock);
		chainStep = ChainStep.CATCHING;
		chainTicks = 0;
	}
	
	// ── Launch first ──
	// VexBot's "higher" pearl catch (its PearlCatchController): a wind charge
	// at its feet first (looking 80 degrees down), the pearl thrown from the
	// top of that jump, then the catch - all of it starts ~6 blocks higher.
	
	/** Starts a launch-first catch instead of this throw, if one can go. */
	private boolean startLaunch()
	{
		// (a throw straight up - flatter ones get a sprint-jump instead)
		if(!pearlLaunchFirst.isChecked() || !pearlCatching.isChecked()
			|| launchStep != null || chainStep != null || pearlCatch != null
			|| throwPrep != null || !MC.player.onGround() || !isCatchThrow()
			|| isOnCooldown(Items.WIND_CHARGE) || MC.player.getXRot() > -75)
			return false;
		
		launchStep = LaunchStep.DOWN;
		launchTicks = 0;
		// the slot the catch returns to, as after a pearl of yours
		pearlReturnSlot = switch(pearlReturnMode.getSelected())
		{
			case SWORD -> findItem(ItemTags.SWORDS);
			case AXE -> findItem(ItemTags.AXES);
			case ELYTRA -> findItem(Items.ELYTRA);
			case PEARL -> findItem(Items.ENDER_PEARL);
			default -> previousSlot();
		};
		if(pearlReturnSlot == -1)
			pearlReturnSlot = MC.player.getInventory().getSelectedSlot();
		return true;
	}
	
	private void tickLaunch()
	{
		if(launchStep == null)
			return;
		if(++launchTicks > 60)
		{
			launchStep = null;
			return;
		}
		
		if(launchStep == LaunchStep.DOWN)
		{
			// the wind charge at our feet once looking down
			InteractionHand hand = windHand();
			if(hand == null || MC.player.getXRot() < 75)
				return;
			if(useItemAndSwingResult(hand))
			{
				launchStep = LaunchStep.UP;
				launchTicks = 0;
			}
			return;
		}
		
		// UP: the pearl near the top of the jump, looking up
		if(MC.player.onGround() && launchTicks > 10)
		{
			launchStep = null;
			return;
		}
		InteractionHand hand = pearlHand();
		if(hand == null)
		{
			int slot = findItem(Items.ENDER_PEARL);
			if(slot == -1)
				launchStep = null;
			else if(PacketOrder.canChangeSlotNow())
				selectSlot(slot);
			return;
		}
		// as soon as looking up: still rising, our speed goes into the pearl
		// (vanilla adds it) - waiting for the top of the jump, after a
		// humanized 170-degree turn, threw it on the way down: 4.7 blocks
		// and only once the launch's wind charge cooldown (10 ticks) is
		// nearly over: a sure catch fires 2-4 ticks after the pearl, and
		// thrown at once the catch's wind charge came too late (0 of 3)
		if(!isLookingUp() || isOnCooldown(Items.ENDER_PEARL)
			|| windCooldownTicks() > 2)
			return;
		if(!InteractionSimulator.rightClickItem(hand))
			return;
		
		launchStep = null;
		// the same catch as after a pearl of yours
		startCatch(clock);
		if(pearlChaining.isChecked())
			startChain();
	}
	
	/** The hand holding a wind charge, switching to one first; or null. */
	private InteractionHand windHand()
	{
		if(MC.player.getMainHandItem().is(Items.WIND_CHARGE))
			return InteractionHand.MAIN_HAND;
		if(MC.player.getOffhandItem().is(Items.WIND_CHARGE))
			return InteractionHand.OFF_HAND;
		int slot = findItem(Items.WIND_CHARGE);
		if(slot != -1 && PacketOrder.canChangeSlotNow())
			selectSlot(slot);
		return null;
	}
	
	private InteractionHand pearlHand()
	{
		if(MC.player.getMainHandItem().is(Items.ENDER_PEARL))
			return InteractionHand.MAIN_HAND;
		if(MC.player.getOffhandItem().is(Items.ENDER_PEARL))
			return InteractionHand.OFF_HAND;
		return null;
	}
	
	private boolean isOnCooldown(Item item)
	{
		return MC.player.getCooldowns().isOnCooldown(new ItemStack(item));
	}
	
	private boolean useItemAndSwingResult(InteractionHand hand)
	{
		if(!(MC.gameMode.useItem(MC.player,
			hand) instanceof InteractionResult.Success success))
			return false;
		if(success.swingSource() == InteractionResult.SwingSource.CLIENT)
			MC.player.swing(hand);
		return true;
	}
	
	/** The newest ender pearl of ours in the world, or null. */
	private Entity findOwnPearl()
	{
		Entity best = null;
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof ThrownEnderpearl pearl
				&& pearl.getOwner() == MC.player
				&& (best == null || pearl.tickCount < best.tickCount))
				best = pearl;
		return best;
	}
	
	private void stopChain()
	{
		chainStep = null;
		chainPearl = null;
		
		// back to where you were - after a catch still under way
		if(chainHomeSlot != -1)
		{
			if(pearlCatch != null)
				pearlReturnSlot = chainHomeSlot;
			else if(windFireDelay > 0 && windSlot != -1)
				windReturnSlot = chainHomeSlot;
			else
				selectSlot(chainHomeSlot);
		}
		chainHomeSlot = -1;
	}
	
	private void onSplashPotThrown()
	{
		if(!airPots.isChecked() || MC.player.onGround())
			return;
		
		airPotThrown = true;
		airPotWindDelay = 2;
	}
	
	/** Right-click just pressed, see onUpdate. */
	private void tickWindRightClick()
	{
		if(windActionCooldown > 0)
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
	
	/**
	 * Right-click just pressed with a sword, looking at a block: puts a
	 * cobweb down there, from the hotbar (see {@link #tickCobweb}) or, if
	 * there's one in the offhand, lets vanilla do it - the sword has no use
	 * of its own, so vanilla goes on to the offhand.
	 *
	 * @return true if the click is the cobweb's (no wind charge then)
	 */
	private boolean startCobweb()
	{
		// one at a time; a click while one is going down is still its
		if(cobwebSlot != -1 || cobwebReturnSlot != -1)
			return true;
		
		if(!cobwebOnRightClick.isChecked()
			|| !isSword(MC.player.getMainHandItem()))
			return false;
		
		if(!(MC.hitResult instanceof BlockHitResult hit)
			|| hit.getType() != HitResult.Type.BLOCK)
			return false;
		
		if(MC.player.getOffhandItem().is(Items.COBWEB))
			return true;
		
		int slot = findItem(Items.COBWEB);
		if(slot == -1)
			return false;
		
		cobwebSlot = slot;
		cobwebReturnSlot = MC.player.getInventory().getSelectedSlot();
		return true;
	}
	
	/**
	 * Places the cobweb a tick after the click and switches back the tick
	 * after that. Vanilla's own right click with the sword goes out in the
	 * click's tick, and a slot change after it in the same tick is out of
	 * order (see {@link PacketOrder}); so is switching back right after the
	 * placement.
	 */
	private void tickCobweb()
	{
		if(cobwebSlot != -1)
		{
			if(!PacketOrder.canChangeSlotNow())
				return;
			
			int slot = cobwebSlot;
			cobwebSlot = -1;
			
			// still looking at a block, no screen open, cobweb still there
			if(MC.screen != null
				|| !MC.player.getInventory().getItem(slot).is(Items.COBWEB)
				|| !(MC.hitResult instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK)
			{
				cobwebReturnSlot = -1;
				return;
			}
			
			selectSlot(slot);
			InteractionSimulator.rightClickBlock(hit,
				InteractionHand.MAIN_HAND);
			return;
		}
		
		if(cobwebReturnSlot != -1 && PacketOrder.canChangeSlotNow())
		{
			selectSlot(cobwebReturnSlot);
			cobwebReturnSlot = -1;
		}
	}
	
	private void fireWindCharge()
	{
		InteractionHand hand = windSlot == -1 ? InteractionHand.OFF_HAND
			: InteractionHand.MAIN_HAND;
		
		useItemAndSwing(hand);
		
		MC.execute(() -> {
			if(MC.player == null)
				return;
			
			if(windReturnSlot != -1)
				selectSlot(windReturnSlot);
			
			windSlot = -1;
			windReturnSlot = -1;
		});
	}
	
	private void fireAirPotWindCharge()
	{
		if(MC.player.getOffhandItem().is(Items.WIND_CHARGE))
		{
			useItemAndSwing(InteractionHand.OFF_HAND);
			return;
		}
		
		int slot = findItem(Items.WIND_CHARGE);
		if(slot == -1)
			return;
		
		// the wind charge has to be in hand on the server before the use
		if(!PacketOrder.canChangeSlotNow())
		{
			airPotThrown = true;
			airPotWindDelay = 1;
			return;
		}
		
		int returnSlot = MC.player.getInventory().getSelectedSlot();
		selectSlot(slot);
		useItemAndSwing(InteractionHand.MAIN_HAND);
		
		MC.execute(() -> {
			if(MC.player != null)
				selectSlot(returnSlot);
		});
	}
	
	// ── Helpers ──────────────────────────────────────────────────────────
	
	/**
	 * Uses the item and swings only if vanilla would, so a use that fails
	 * (e.g. on cooldown) doesn't send a swing packet on its own.
	 */
	private void useItemAndSwing(InteractionHand hand)
	{
		if(MC.gameMode.useItem(MC.player,
			hand) instanceof InteractionResult.Success success
			&& success.swingSource() == InteractionResult.SwingSource.CLIENT)
			MC.player.swing(hand);
	}
	
	private int previousSlot()
	{
		if(lastSelectedSlot != -1)
			return lastSelectedSlot;
		
		return MC.player.getInventory().getSelectedSlot();
	}
	
	/**
	 * Selects a hotbar slot and tells the server about it right away, instead
	 * of waiting for the client's own sync at the end of the tick. Uses the
	 * game's own sync, which never sends the same slot twice.
	 */
	private void selectSlot(int slot)
	{
		if(slot < 0 || slot > 8)
			return;
		
		Inventory inventory = MC.player.getInventory();
		if(inventory.getSelectedSlot() == slot)
			return;
		
		inventory.setSelectedSlot(slot);
		
		// Vanilla's own sync, so the game doesn't send the same slot a second
		// time on the next attack or tick.
		IMC.getInteractionManager().syncSelectedSlot();
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
		
		pearlReturnSlot = -1;
		pearlCatch = null;
		pearlSpawn = null;
		throwPrep = null;
		lastTickPos = null;
		pearlAim.reset();
		chainStep = null;
		chainPearl = null;
		chainHomeSlot = -1;
		launchStep = null;
		
		windFireDelay = 0;
		windSlot = -1;
		windReturnSlot = -1;
		windActionCooldown = 0;
		cobwebSlot = -1;
		cobwebReturnSlot = -1;
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
