/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;
import net.wurstclient.util.InventoryUtils;

/**
 * Cart PvP insta-carting: press use while holding a Flame bow (or a loaded
 * crossbow with Crossbow Carting enabled). Requires a Rail and a TNT Minecart
 * anywhere in the inventory.
 * <p>
 * Ported from <a href="https://github.com/CravenGold48722/cartcore">CartCore
 * by thetruetrident</a>. Fires the weapon immediately at the targeted block,
 * jumps the player at the moment of release, then places the rail and TNT
 * Minecart there on the very next tick while the projectile is in the air.
 * A Totem of Undying is equipped alongside the placement.
 */
@SearchTags({"insta cart", "cart pvp", "tnt minecart pvp", "cart boost",
	"instacarting", "flame bow cart", "crossbow cart"})
public final class InstacartHack extends Hack implements UpdateListener
{
	private enum Phase
	{
		IDLE,
		/**
		 * Hack is forcing the bow to charge for {@link #chargeThreshold}
		 * ticks (~0.125s minimum). Even if the user releases right-click
		 * during this phase, {@code keyUse.setDown(true)} keeps the bow
		 * drawn until the threshold is reached.
		 */
		CHARGING,
		/**
		 * Weapon was fired this tick; on the next tick the rail + TNT Minecart
		 * are placed at {@link #pendingPos} while the projectile is in flight.
		 */
		PLACING
	}
	
	// ── Settings — mirrors cartcore's cartConfig
	// ──────────────────────────────
	
	private final SliderSetting tickVariance =
		new SliderSetting("Tick Variance",
			"Adds a random tick offset (0–N) to the fire threshold each shot to"
				+ " vary timing and reduce pattern-based detection.",
			0, 0, 10, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting cartOverloading =
		new CheckboxSetting("Cart Overloading",
			"Places every TNT Minecart found in the inventory onto the rail"
				+ " before firing, not just the first one.",
			false);
	
	private final CheckboxSetting crossbowCarting = new CheckboxSetting(
		"Crossbow Carting",
		"Also triggers when holding a fully loaded crossbow. The cart is"
			+ " placed and the crossbow fires immediately (no charge phase).",
		false);
	
	private final CheckboxSetting cartReplenishing = new CheckboxSetting(
		"Cart Replenishing",
		"Pre-stocks a spare rail and TNT Minecart from the main inventory"
			+ " into the hotbar right after firing so the next shot is faster.",
		false);
	
	private final CheckboxSetting soundEffects = new CheckboxSetting(
		"Sound Effects", "Plays a sound when the sequence fires.", true);
	
	private final CheckboxSetting chatLogs = new CheckboxSetting("Chat Logs",
		"Prints a chat notice when the sequence fires.", false);
	
	// ── CartcoreCore fields
	// ───────────────────────────────────────────────────
	
	/**
	 * Target pitch (degrees) to smooth toward each tick.
	 * Null when no sequence is active.
	 * Mirrors {@code CartcoreCore.targetPitch}.
	 */
	private Integer targetPitch = null;
	
	/**
	 * Interpolation speed toward {@link #targetPitch} each tick.
	 * Mirrors {@code CartcoreCore.pitchSpeed}.
	 */
	private final float pitchSpeed = 0.75f;
	
	/**
	 * Set to {@code true} the tick the bow/crossbow releases; cleared the
	 * following tick after post-fire actions run.
	 * Mirrors {@code CartcoreClient.shotBow}, which cartcore's
	 * {@code BowItemMixin.onStoppedUsing} sets to {@code true}.
	 */
	private boolean shotBow = false;
	
	// ── Internal state
	// ────────────────────────────────────────────────────────
	
	private Phase phase = Phase.IDLE;
	private boolean useKeyWasDown;
	private BlockPos cartPos;
	private boolean usingCrossbow;
	private int savedSlot;
	
	/** Ticks elapsed in the current CHARGING phase. */
	private int chargingTicks;
	/**
	 * Target charge duration in ticks for the current shot. 3 ticks = 0.15s
	 * is the vanilla minimum for a bow to actually fire an arrow (power >
	 * 0.1). Tick Variance adds a random 0–N on top.
	 */
	private int chargeThreshold;
	/** Minimum ticks the bow must charge before it can fire. */
	private static final int MIN_CHARGE_TICKS = 2;
	
	/**
	 * Hotbar slot the rail was moved to; used by the PLACING phase.
	 * Set in {@link #startSequence} and consumed in {@link #doPlacement}.
	 */
	private int pendingRailHotbar = -1;
	
	/**
	 * Hotbar slot the first TNT Minecart was moved to; used by the PLACING
	 * phase.
	 */
	private int pendingCartHotbar = -1;
	
	/**
	 * Block position where the rail + cart will be placed on the next tick,
	 * i.e., while the projectile is in the air.
	 */
	private BlockPos pendingPos = null;
	
	private final Random random = new Random();
	
	public InstacartHack()
	{
		super("Instacart");
		setCategory(Category.COMBAT);
		addSetting(tickVariance);
		addSetting(cartOverloading);
		addSetting(crossbowCarting);
		addSetting(cartReplenishing);
		addSetting(soundEffects);
		addSetting(chatLogs);
	}
	
	@Override
	protected void onEnable()
	{
		phase = Phase.IDLE;
		useKeyWasDown = false;
		targetPitch = null;
		shotBow = false;
		cartPos = null;
		usingCrossbow = false;
		savedSlot = -1;
		chargingTicks = 0;
		chargeThreshold = MIN_CHARGE_TICKS;
		pendingRailHotbar = -1;
		pendingCartHotbar = -1;
		pendingPos = null;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		targetPitch = null;
		cartPos = null;
		pendingPos = null;
		phase = Phase.IDLE;
	}
	
	/**
	 * Main tick handler.
	 *
	 * <p>
	 * Structure mirrors {@code CartcoreClient}'s {@code START_CLIENT_TICK}
	 * callback: pitch smoothing runs first, then the instaCarting / post-fire
	 * logic, then the trigger check.
	 *
	 * <p>
	 * <b>cartAssister mixin approximation:</b> The mixin overrides
	 * {@code getPitch(tickDelta)} to return a stored {@code lastPitch} value.
	 * Here {@code setXRot()} is called every tick during pitch smoothing;
	 * Minecraft copies {@code xRot} to {@code xRotO} at tick start, so after
	 * one tick both values are at the smoothed pitch — equivalent result.
	 */
	@Override
	public void onUpdate()
	{
		boolean useKeyDown = MC.options.keyUse.isDown();
		boolean useKeyClicked = useKeyDown && !useKeyWasDown;
		useKeyWasDown = useKeyDown;
		
		// ── CartcoreClient: pitch smoothing ──────────────────────────────────
		// Mirrors:
		// if (CartcoreCore.targetPitch != null) {
		// float smoothed = currentPitch + (target - currentPitch) * pitchSpeed;
		// player.setPitch(smoothed);
		// ((cartAssistMixinInterface) player).setLastPitch(smoothed);
		// }
		if(targetPitch != null)
		{
			float current = MC.player.getXRot();
			float target = (float)(int)targetPitch;
			float smoothed = current + (target - current) * pitchSpeed;
			MC.player.setXRot(smoothed);
		}
		
		// ── BowItemMixin equivalent: post-fire
		// ────────────────────────────────
		// Mirrors BowItemMixin.onStoppedUsing:
		// CartcoreClient.shotBow = true;
		// CartcoreCore.targetPitch = null;
		if(shotBow)
		{
			shotBow = false;
			targetPitch = null;
		}
		
		// ── PLACING: place rail + cart while projectile is in the air
		// ─────────
		// Weapon was fired last tick; this tick the cart appears at pendingPos.
		if(phase == Phase.PLACING)
		{
			doPlacement();
			return;
		}
		
		// ── CHARGING: hack holds the bow for chargeThreshold ticks
		// ──────────
		// Force keyUse down so vanilla keeps the bow drawn even if the
		// user only tapped right-click once.
		if(phase == Phase.CHARGING)
		{
			MC.options.keyUse.setDown(true);
			useKeyWasDown = true;
			chargingTicks++;
			
			if(chargingTicks >= chargeThreshold)
				fireBowAndTransition();
			return;
		}
		
		// ── Sequence trigger (IDLE only)
		// ──────────────────────────────────────
		if(phase != Phase.IDLE)
			return;
		
		// Crossbow: fire on first click (already loaded, no charge needed).
		boolean xbowTrigger = crossbowCarting.isChecked()
			&& isHoldingLoadedCrossbow() && useKeyClicked;
		
		// Bow: trigger whenever the user holds right-click with a flame bow.
		// The CHARGING phase takes care of drawing the bow for 0.125s before
		// releasing — no need for the user to pre-charge.
		boolean bowTrigger = !xbowTrigger && isHoldingFlameBow() && useKeyDown;
		
		if(!bowTrigger && !xbowTrigger)
			return;
		
		int railSlot = InventoryUtils.indexOf(Items.RAIL, 36);
		int cartSlot = InventoryUtils.indexOf(Items.TNT_MINECART, 36);
		if(railSlot == -1 || cartSlot == -1)
			return;
		
		startSequence(railSlot, cartSlot, xbowTrigger);
	}
	
	// ── Sequence
	// ──────────────────────────────────────────────────────────────
	
	/**
	 * Fires the weapon, then stores the target position and the hotbar
	 * slots of the rail and cart for {@link #doPlacement()} to consume on
	 * the next tick.
	 *
	 * <p>
	 * <b>Bow:</b> the player is already charging (vanilla handled the
	 * start). We send {@code RELEASE_USE_ITEM} to the server and call
	 * {@code releaseUsingItem()} client-side so the arrow fires with the
	 * current charge level. {@code setDown(false)} prevents
	 * {@code aiStep()} from immediately restarting the charge this tick.
	 *
	 * <p>
	 * <b>Crossbow:</b> already loaded — {@code rightClickItem()} fires
	 * it in one call.
	 */
	private void startSequence(int railInvSlot, int cartInvSlot,
		boolean crossbow)
	{
		usingCrossbow = crossbow;
		
		savedSlot = MC.player.getInventory().getSelectedSlot();
		
		// Move rail and cart to the hotbar now so the PLACING tick can act
		// immediately. Slot swaps don't change the selected slot, so the bow
		// stays selected for the CHARGING phase below.
		int railHotbar = ensureInHotbar(railInvSlot, savedSlot, -1);
		if(railHotbar == -1)
			return;
		
		int cartHotbar = ensureInHotbar(cartInvSlot, savedSlot, railHotbar);
		if(cartHotbar == -1)
			return;
		
		pendingRailHotbar = railHotbar;
		pendingCartHotbar = cartHotbar;
		
		// Ensure the weapon slot is selected (swaps above shouldn't have
		// changed it, but be defensive).
		MC.player.getInventory().setSelectedSlot(savedSlot);
		
		if(crossbow)
		{
			// Crossbow: predict arrow landing, fire immediately (no charge).
			BlockPos pos = predictArrowLandingPos();
			if(pos == null)
				pos = MC.player.blockPosition();
			pendingPos = pos;
			
			IMC.getInteractionManager().rightClickItem();
			shotBow = true;
			phase = Phase.PLACING;
			MC.player.jumpFromGround();
		}else
		{
			// Bow: force a 0.125s (3-tick) charge, then release. The
			// CHARGING phase forces keyUse.setDown(true) each tick so the
			// bow draws regardless of whether the user keeps holding
			// right-click.
			int variance = (int)tickVariance.getValue();
			chargeThreshold = MIN_CHARGE_TICKS
				+ (variance > 0 ? random.nextInt(variance + 1) : 0);
			chargingTicks = 0;
			MC.options.keyUse.setDown(true);
			useKeyWasDown = true;
			phase = Phase.CHARGING;
		}
	}
	
	/**
	 * Called from {@link #onUpdate()} once the CHARGING phase reaches its
	 * threshold. Predicts arrow landing based on current aim, fires the bow
	 * (server packet + client-side release), and transitions to PLACING so
	 * the rail/cart go down next tick.
	 */
	private void fireBowAndTransition()
	{
		// Predict landing now — player aim may have shifted during charging.
		BlockPos pos = predictArrowLandingPos();
		if(pos == null)
			pos = MC.player.blockPosition();
		pendingPos = pos;
		
		// Server packet — makes the server actually fire the arrow with the
		// current charge level.
		IMC.getInteractionManager().sendPlayerActionC2SPacket(
			ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
			MC.player.blockPosition(), MC.player.getDirection());
		// Client-side — end using state / bow animation.
		MC.player.releaseUsingItem();
		// Prevent aiStep() from restarting the charge this tick.
		MC.options.keyUse.setDown(false);
		useKeyWasDown = false;
		
		shotBow = true;
		phase = Phase.PLACING;
		MC.player.jumpFromGround();
	}
	
	// ── Placement (next tick, while projectile is in the air)
	// ─────────────────
	
	/**
	 * Runs the tick after firing. Places the rail and TNT Minecart at
	 * {@link #pendingPos} while the projectile is still in flight, then
	 * equips a totem and completes post-fire actions.
	 */
	private void doPlacement()
	{
		if(pendingPos == null)
		{
			phase = Phase.IDLE;
			return;
		}
		
		BlockPlacingParams params =
			BlockPlacer.getBlockPlacingParams(pendingPos);
		
		if(params != null)
		{
			// Place rail.
			MC.player.getInventory().setSelectedSlot(pendingRailHotbar);
			IMC.getInteractionManager().rightClickBlock(params.neighbor(),
				params.side(), params.hitVec());
			
			// Place first TNT Minecart on the rail.
			MC.player.getInventory().setSelectedSlot(pendingCartHotbar);
			IMC.getInteractionManager().rightClickBlock(pendingPos,
				Direction.UP, Vec3.atCenterOf(pendingPos).add(0, 0.5, 0));
			
			// cartOverloading: stack every remaining TNT Minecart onto the
			// rail.
			if(cartOverloading.isChecked())
			{
				int extra;
				while((extra =
					InventoryUtils.indexOf(Items.TNT_MINECART, 36)) != -1)
				{
					int extraHotbar =
						ensureInHotbar(extra, savedSlot, pendingRailHotbar);
					if(extraHotbar == -1)
						break;
					MC.player.getInventory().setSelectedSlot(extraHotbar);
					IMC.getInteractionManager().rightClickBlock(pendingPos,
						Direction.UP,
						Vec3.atCenterOf(pendingPos).add(0, 0.5, 0));
				}
			}
			
			cartPos = pendingPos;
		}
		
		// Restore weapon slot.
		MC.player.getInventory().setSelectedSlot(savedSlot);
		
		equipTotemInOffhand();
		
		// Clean up pending state.
		pendingPos = null;
		pendingRailHotbar = -1;
		pendingCartHotbar = -1;
		
		onFired();
		phase = Phase.IDLE;
	}
	
	// ── Arrow trajectory simulation
	// ──────────────────────────────────────────
	
	/**
	 * Simulates the arrow's parabolic flight and returns the position where
	 * the rail should be placed (adjacent air block on the face the arrow
	 * hits), or {@code null} if nothing is hit within range.
	 *
	 * <p>
	 * Per-tick physics mirrors {@code AbstractArrow.tick()} exactly:
	 * <ol>
	 * <li>Raycast {@code pos → pos+vel} for block collision (COLLIDER
	 * shape)</li>
	 * <li>pos += vel</li>
	 * <li>vel *= 0.99 (inertia/drag), then vel.y -= 0.05 (gravity)</li>
	 * </ol>
	 *
	 * <p>
	 * Speed is derived from the actual charge: for a bow fired after
	 * {@link #chargeThreshold} ticks, power = (f²+2f)/3 where f=ticks/20;
	 * for a loaded crossbow the speed is 3.15 blocks/tick.
	 */
	private BlockPos predictArrowLandingPos()
	{
		if(MC.player == null || MC.level == null)
			return null;
		
		// AbstractArrow constructor: y = owner.getEyeY() - 0.1
		Vec3 pos = MC.player.getEyePosition().subtract(0, 0.1, 0);
		
		// Compute actual arrow speed from charge level (mirrors BowItem logic).
		double speed;
		if(usingCrossbow)
		{
			speed = 3.15; // CrossbowItem arrow projectile speed
		}else
		{
			float f = (float)chargeThreshold / 20.0F;
			f = (f * f + f * 2.0F) / 3.0F;
			if(f > 1.0F)
				f = 1.0F;
			speed = f * 3.0;
		}
		
		Vec3 vel = MC.player.getLookAngle().scale(speed);
		
		for(int tick = 0; tick < 80; tick++)
		{
			// Sub-divide this tick into ≤0.25-block steps so the simulated
			// path stays within 1/4 block of the real arc between ticks.
			int subSteps = Math.max(1, (int)Math.ceil(vel.length() / 0.01));
			Vec3 subVel = vel.scale(1.0 / subSteps);
			
			for(int s = 0; s < subSteps; s++)
			{
				Vec3 nextPos = pos.add(subVel);
				
				// Vanilla uses COLLIDER (not OUTLINE) for arrow block
				// collision.
				BlockHitResult hit = MC.level.clip(
					new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER,
						ClipContext.Fluid.NONE, MC.player));
				
				if(hit.getType() == HitResult.Type.BLOCK)
					// Return the air block adjacent to the struck face; the
					// rail goes there (you place ON the face, not inside the
					// solid block).
					return hit.getBlockPos().relative(hit.getDirection());
				
				pos = nextPos;
			}
			
			// Vanilla AbstractArrow.tick() order: inertia first, gravity
			// second.
			// Physics is applied once per tick (matching vanilla), not per
			// sub-step.
			vel = vel.scale(0.99).add(0, -0.05, 0);
			
			if(pos.y < MC.level.getMinY())
				return null;
		}
		
		return null;
	}
	
	// ── Post-fire (BowItemMixin equivalent)
	// ───────────────────────────────────
	
	private void onFired()
	{
		if(soundEffects.isChecked() && MC.level != null)
			MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
				MC.player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS,
				1.0F, 1.0F, false);
		
		if(chatLogs.isChecked())
			MC.player.sendSystemMessage(
				Component.literal("\u00a7a[Instacart] Fired!"));
		
		if(cartReplenishing.isChecked())
			preStockForNextShot();
	}
	
	private void equipTotemInOffhand()
	{
		if(MC.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING))
			return;
		// Searches full inventory (slots 0–35) for any Totem of Undying.
		int slot = InventoryUtils.indexOf(Items.TOTEM_OF_UNDYING, 36);
		if(slot == -1)
			return;
		int networkSlot = InventoryUtils.toNetworkSlot(slot);
		boolean offhandOccupied = !MC.player.getOffhandItem().isEmpty();
		IMC.getInteractionManager().windowClick_PICKUP(networkSlot);
		IMC.getInteractionManager().windowClick_PICKUP(45);
		if(offhandOccupied)
			IMC.getInteractionManager().windowClick_PICKUP(networkSlot);
	}
	
	/**
	 * cartReplenishing: pre-stocks a spare rail and TNT Minecart from the main
	 * inventory into the hotbar so the next shot needs no swap delay.
	 */
	private void preStockForNextShot()
	{
		int rail = InventoryUtils.indexOf(Items.RAIL, 36);
		int cart = InventoryUtils.indexOf(Items.TNT_MINECART, 36);
		if(rail != -1 && rail >= 9)
			ensureInHotbar(rail, savedSlot, -1);
		if(cart != -1 && cart >= 9)
			ensureInHotbar(cart, savedSlot, -1);
	}
	
	// ── Helpers
	// ───────────────────────────────────────────────────────────────
	
	/**
	 * CartcoreCore.checkIfInHotbar() equivalent — replaced by
	 * {@link InventoryUtils#indexOf} with a limit of 9 wherever a hotbar-only
	 * check is needed.
	 * <p>
	 * If {@code itemSlot} is already in the hotbar (0–8) returns it unchanged.
	 * Otherwise swaps it to a free (or least-important) hotbar slot that is
	 * neither {@code avoid1} nor {@code avoid2}. Returns -1 on failure.
	 */
	private int ensureInHotbar(int itemSlot, int avoid1, int avoid2)
	{
		if(itemSlot < 0)
			return -1;
		if(itemSlot < 9)
			return itemSlot;
		for(int i = 0; i < 9; i++)
		{
			if(i == avoid1 || i == avoid2)
				continue;
			if(MC.player.getInventory().getItem(i).isEmpty())
			{
				IMC.getInteractionManager().windowClick_SWAP(
					InventoryUtils.toNetworkSlot(itemSlot), i);
				return i;
			}
		}
		for(int i = 8; i >= 0; i--)
		{
			if(i == avoid1 || i == avoid2)
				continue;
			IMC.getInteractionManager()
				.windowClick_SWAP(InventoryUtils.toNetworkSlot(itemSlot), i);
			return i;
		}
		return -1;
	}
	
	private boolean isHoldingFlameBow()
	{
		ItemStack held = MC.player.getMainHandItem();
		if(!(held.getItem() instanceof BowItem) || MC.level == null)
			return false;
		RegistryAccess ra = MC.level.registryAccess();
		Registry<Enchantment> reg = ra.lookupOrThrow(Registries.ENCHANTMENT);
		return reg.get(Enchantments.FLAME)
			.map(e -> EnchantmentHelper.getItemEnchantmentLevel(e, held) > 0)
			.orElse(false);
	}
	
	private boolean isHoldingLoadedCrossbow()
	{
		ItemStack held = MC.player.getMainHandItem();
		return held.getItem() instanceof CrossbowItem
			&& CrossbowItem.isCharged(held);
	}
	
	private void reset()
	{
		targetPitch = null;
		cartPos = null;
		pendingPos = null;
		pendingRailHotbar = -1;
		pendingCartHotbar = -1;
		usingCrossbow = false;
		shotBow = false;
		chargingTicks = 0;
		chargeThreshold = MIN_CHARGE_TICKS;
		phase = Phase.IDLE;
	}
}
