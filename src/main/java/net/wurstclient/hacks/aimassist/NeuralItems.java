/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist;

import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.aimassist.neural.NeuralAction;
import net.wurstclient.hacks.aimassist.neural.PvpSim;
import net.wurstclient.hacks.aimassist.neural.TerrainSense;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.PacketOrder;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

/**
 * The neural network's items in game, done the way a player does them (see
 * PvpSim for the rules it was trained with): a cobweb at the target's feet,
 * splash Strength, Speed and Fire Resistance at your own (one a tick, the
 * ones running low), a golden apple, an ender pearl toward the target or
 * away, a wind charge at your feet or theirs, and out of a web you're stuck
 * in - water (placed, then picked back up) or hitting it. The shield and
 * the axe for theirs are AimAssist's.
 *
 * <p>
 * Turn first, click on a later tick: the camera turns there through
 * AimAssist's own aim ({@link #getAimPoint()}), and the click goes out only
 * once both the crosshair and the rotation the server already has are on the
 * spot. Slot changes wait for {@link PacketOrder#canChangeSlotNow()}, and
 * the weapon comes back the tick after.
 */
public final class NeuralItems
{
	private static final Minecraft MC = WurstClient.MC;
	
	private static final double BLOCK_REACH = 4.5;
	private static final int WEB_AIM_TIMEOUT = 10;
	/**
	 * After a web that didn't get placed in time: no new try for this long.
	 * The net asks for a web on 45-64% of the ticks (in PvpSim one costs a
	 * tick), and each try turned the camera to the floor for 10 ticks, timed
	 * out and started over - 50+ ticks in a row with the hands "busy" and the
	 * crosshair off them: 2-6 hits a fight, dealt 0-6.6, taken 10-27 (user's
	 * fights, 2026-11-03).
	 */
	private static final int WEB_RETRY_TICKS = 40;
	private static final int POT_AIM_TIMEOUT = 12;
	private static final int THROW_AIM_TIMEOUT = 20;
	private static final int WASH_TIMEOUT = 12;
	/** Looking this far down counts as at your feet. */
	private static final double DOWN_PITCH = 80;
	/** The potions, in PvpSim's order (STRENGTH, SPEED, FIRE_RES). */
	@SuppressWarnings("unchecked")
	private static final Holder<MobEffect>[] POT_EFFECTS = new Holder[]{
		MobEffects.STRENGTH, MobEffects.SPEED, MobEffects.FIRE_RESISTANCE};
	private static final int BREAK_TIMEOUT = 40;
	/**
	 * A potion goes once the camera is this far down: straight down at your
	 * feet (user, 2026-10-26).
	 */
	private static final double POT_PITCH = 85;
	/** Slower than this (blocks a tick), you've stopped walking. */
	private static final double STOPPED_SPEED = 0.03;
	private static final int POT_STOP_TIMEOUT = 8;
	
	private enum Stage
	{
		IDLE,
		AIM_WEB,
		AIM_POT,
		AIM_PEARL,
		AIM_WIND,
		WATER_PLACE,
		WATER_WAIT,
		WATER_PICKUP,
		EATING,
		BREAK_WEB,
		RESTOCK,
		BACK
	}
	
	/**
	 * The kit items a hotbar slot is restocked with when it runs out (user,
	 * 2026-10-29): cobwebs, golden apples, pearls, wind charges, water
	 * buckets, the three splash potions.
	 */
	private static final List<java.util.function.Predicate<ItemStack>> KINDS =
		List.of(s -> s.is(Items.COBWEB), NeuralItems::isGoldenApple,
			s -> s.is(Items.ENDER_PEARL), s -> s.is(Items.WIND_CHARGE),
			s -> s.is(Items.WATER_BUCKET), isSplash(MobEffects.STRENGTH),
			isSplash(MobEffects.SPEED), isSplash(MobEffects.FIRE_RESISTANCE));
	private static final int WATER = 4;
	/** Picking the water back up: a click a tick until the bucket is full. */
	private static final int PICKUP_TIMEOUT = 8;
	
	private final Random random = new Random();
	private Stage stage = Stage.IDLE;
	private int stageTicks;
	private int prevSlot = -1;
	
	private BlockPos webFloor;
	private Vec3 aimPoint;
	private int stuckTicks;
	private int stuckReact = 3;
	private int eatCount;
	private boolean holdingUse;
	private boolean holdingAttack;
	/** Potions still to throw this round (bits), and when each last went. */
	private int potQueue;
	/** The stage tick potting stood still at, -1 while still stopping. */
	private int potStopTick = -1;
	private final int[] potThrown = {-100, -100, -100};
	private boolean pearlIn, windJump;
	private Rotation pearlAim;
	private final double[] playersX = new double[64], playersZ = new double[64];
	/** The kit item (KINDS) each hotbar slot last held, -1 none. */
	private final int[] slotKind = {-1, -1, -1, -1, -1, -1, -1, -1, -1};
	/** Fight ticks until the next web try (WEB_RETRY_TICKS). */
	private int webRetryLeft;
	/**
	 * Ticks until the next restock try after the inventory didn't open - a
	 * restock that can't start must not keep the hands busy every tick.
	 */
	private static final int RESTOCK_RETRY_TICKS = 40;
	private int restockRetryLeft;
	
	/** Where the camera should look this frame, or null for the target. */
	public Vec3 getAimPoint()
	{
		return aimPoint;
	}
	
	/** Whether the items have the hands (no hit from the network). */
	public boolean isBusy()
	{
		return stage != Stage.IDLE;
	}
	
	/** Whether it has the inventory open to restock the hotbar. */
	public boolean isRestocking()
	{
		return stage == Stage.RESTOCK;
	}
	
	/**
	 * A tick of restocking while the inventory is open (AimAssist's onUpdate
	 * stops at open screens - this one is ours).
	 */
	public void tickRestock()
	{
		stageTicks++;
		updateRestock();
	}
	
	/** Whether it's turning to or throwing potions. */
	public boolean isPotting()
	{
		return stage == Stage.AIM_POT;
	}
	
	/** Whether the use key is held for eating (not the shield). */
	public boolean isEating()
	{
		return stage == Stage.EATING;
	}
	
	/**
	 * One tick. Returns true while an item has the hands this tick (the
	 * network's hit waits).
	 */
	public boolean update(NeuralAction act, Entity target)
	{
		LocalPlayer player = MC.player;
		stageTicks++;
		if(webRetryLeft > 0)
			webRetryLeft--;
		if(restockRetryLeft > 0)
			restockRetryLeft--;
		trackSlots();
		
		switch(stage)
		{
			case BACK:
			if(!PacketOrder.canChangeSlotNow())
				return true;
			if(prevSlot >= 0)
				selectSlot(prevSlot);
			prevSlot = -1;
			setStage(Stage.IDLE);
			return true;
			
			case BREAK_WEB:
			updateBreakWeb();
			return true;
			
			case EATING:
			updateEating(act.eat);
			return true;
			
			case AIM_WEB:
			updateWeb(target);
			return true;
			
			case AIM_POT:
			updatePot();
			return true;
			
			case AIM_PEARL:
			updatePearl(target);
			return true;
			
			case AIM_WIND:
			updateWind(target);
			return true;
			
			case WATER_PLACE:
			case WATER_WAIT:
			case WATER_PICKUP:
			updateWater();
			return true;
			
			case RESTOCK:
			updateRestock();
			return true;
			
			default:
			break;
		}
		
		// stuck in a web: out of it after a moment, like a player - water
		// if the network wants, else hitting it
		if(webCells(player.getBoundingBox()) != null)
		{
			if(++stuckTicks > stuckReact)
			{
				stuckTicks = 0;
				stuckReact = 2 + random.nextInt(4);
				if(act.water && findSlot(s -> s.is(Items.WATER_BUCKET)) >= 0)
				{
					setStage(Stage.WATER_PLACE);
					updateWater();
					return true;
				}
				setStage(Stage.BREAK_WEB);
				updateBreakWeb();
				return true;
			}
		}else
			stuckTicks = 0;
		
		// anything that ran out, from the inventory first
		if(restockRetryLeft == 0 && restockSlot() >= 0)
		{
			setStage(Stage.RESTOCK);
			updateRestock();
			return true;
		}
		
		if(act.eat && findSlot(NeuralItems::isGoldenApple) >= 0)
		{
			setStage(Stage.EATING);
			updateEating(true);
			return true;
		}
		
		if(act.web && webRetryLeft == 0
			&& findSlot(s -> s.is(Items.COBWEB)) >= 0
			&& (webFloor = findWebFloor(target)) != null)
		{
			setStage(Stage.AIM_WEB);
			updateWeb(target);
			return true;
		}
		
		if(act.pot && (potQueue = potsNeeded()) != 0)
		{
			potStopTick = -1;
			setStage(Stage.AIM_POT);
			updatePot();
			return true;
		}
		
		// pearling in only from further than it lands short
		double dist = Math.sqrt(player.distanceToSqr(target));
		if((act.pearlIn && dist > PvpSim.PEARL_SHORT + 2 || act.pearlOut)
			&& isReady(s -> s.is(Items.ENDER_PEARL)) && !isPearlFlying())
		{
			pearlIn = act.pearlIn;
			setStage(Stage.AIM_PEARL);
			updatePearl(target);
			return true;
		}
		
		if((act.windJump || act.windHit)
			&& isReady(s -> s.is(Items.WIND_CHARGE)))
		{
			windJump = act.windJump;
			setStage(Stage.AIM_WIND);
			updateWind(target);
			return true;
		}
		
		return false;
	}
	
	private void updateWeb(Entity target)
	{
		LocalPlayer player = MC.player;
		int slot = findSlot(s -> s.is(Items.COBWEB));
		if(slot < 0 || !BlockUtils.getState(webFloor.above()).canBeReplaced())
		{
			finish();
			return;
		}
		
		Vec3 eyes = player.getEyePosition();
		aimPoint = stageTicks > WEB_AIM_TIMEOUT ? null : pickWebPoint(webFloor,
			eyes, target.getBoundingBox(), player.getYRot(), player.getXRot());
		if(aimPoint == null)
		{
			// missed: fight on for a while before the next try
			webRetryLeft = WEB_RETRY_TICKS;
			finish();
			return;
		}
		
		// the crosshair and the rotation the server has, both on the face
		if(!(MC.hitResult instanceof BlockHitResult hit)
			|| hit.getType() != HitResult.Type.BLOCK
			|| !hit.getBlockPos().equals(webFloor)
			|| hit.getDirection() != Direction.UP
			|| !sentLookHits(webFloor, Direction.UP, target))
			return;
		
		if(!player.getMainHandItem().is(Items.COBWEB))
		{
			if(!PacketOrder.canChangeSlotNow())
				return;
			rememberSlot();
			selectSlot(slot);
		}
		InteractionSimulator.rightClickBlock(hit, InteractionHand.MAIN_HAND);
		aimPoint = null;
		setStage(prevSlot >= 0 ? Stage.BACK : Stage.IDLE);
	}
	
	private void updatePot()
	{
		LocalPlayer player = MC.player;
		if(potQueue == 0 || stageTicks > POT_STOP_TIMEOUT + POT_AIM_TIMEOUT + 3)
		{
			finish();
			return;
		}
		
		// first stand still (AimAssist lets go of every movement key while
		// potting), still looking at them - not turning mid-sprint
		Vec3 v = player.getDeltaMovement();
		if(potStopTick < 0)
		{
			if((!player.onGround()
				|| v.x * v.x + v.z * v.z > STOPPED_SPEED * STOPPED_SPEED)
				&& stageTicks <= POT_STOP_TIMEOUT)
				return;
			potStopTick = stageTicks;
		}
		
		// then straight down at your feet (a hair ahead, so the yaw stays)
		double yaw = Math.toRadians(player.getYRot());
		aimPoint = player.position().add(-Math.sin(yaw) * 0.03, 0,
			Math.cos(yaw) * 0.03);
		
		if(player.getXRot() < POT_PITCH || player.xRotLast < POT_PITCH)
		{
			if(stageTicks - potStopTick > POT_AIM_TIMEOUT)
				finish();
			return;
		}
		
		// one a tick: the next slot change waits for the next tick
		int type = Integer.numberOfTrailingZeros(potQueue);
		int slot = findSlot(isSplash(POT_EFFECTS[type]));
		if(slot < 0)
		{
			potQueue &= potQueue - 1;
			return;
		}
		if(slot != player.getInventory().getSelectedSlot())
		{
			if(!PacketOrder.canChangeSlotNow())
				return;
			rememberSlot();
			selectSlot(slot);
		}
		InteractionSimulator.rightClickItem(InteractionHand.MAIN_HAND);
		potThrown[type] = player.tickCount;
		potQueue &= potQueue - 1;
		if(potQueue == 0)
		{
			aimPoint = null;
			setStage(prevSlot >= 0 ? Stage.BACK : Stage.IDLE);
		}
	}
	
	/**
	 * The potions worth throwing now (bits, PvpSim's order): in the hotbar,
	 * running low and none of that kind thrown in the last second.
	 */
	private int potsNeeded()
	{
		LocalPlayer player = MC.player;
		int need = 0;
		for(int i = 0; i < POT_EFFECTS.length; i++)
		{
			MobEffectInstance effect = player.getEffect(POT_EFFECTS[i]);
			int left = effect == null ? 0 : effect.getDuration();
			if(left < PvpSim.REPOT_TICKS[i]
				&& player.tickCount - potThrown[i] > 20
				&& findSlot(isSplash(POT_EFFECTS[i])) >= 0)
				need |= 1 << i;
		}
		return need;
	}
	
	/**
	 * A pearl at the point PvpSim's pearls land: just short of the target,
	 * or far behind you. The camera turns to the throw first.
	 */
	private void updatePearl(Entity target)
	{
		LocalPlayer player = MC.player;
		int slot = findSlot(s -> s.is(Items.ENDER_PEARL));
		if(slot < 0 || target == null || stageTicks > THROW_AIM_TIMEOUT)
		{
			finish();
			return;
		}
		
		// planned once over the real blocks (TerrainSense, like PvpSim):
		// in, just short of the target; out, as far from every player as
		// it gets, never into a drop
		if(pearlAim == null)
		{
			int n = 0;
			for(Player p : MC.level.players())
				if(p != player && p.isAlive() && !p.isSpectator() && n < 64)
				{
					playersX[n] = p.getX();
					playersZ[n++] = p.getZ();
				}
			Vec3 eyes = player.getEyePosition(), v = player.getDeltaMovement();
			double[] aim = TerrainSense.planPearl(NeuralSpace.WORLD, eyes.x,
				eyes.y, eyes.z, player.getY(), v.x, player.onGround() ? 0 : v.y,
				v.z, pearlIn, target.getX(), target.getZ(), playersX, playersZ,
				n);
			pearlAim = new Rotation((float)aim[0], (float)aim[1]);
		}
		Rotation want = pearlAim;
		aimPoint = player.getEyePosition().add(want.toLookVec().scale(8));
		if(!onRotation(want, 1))
			return;
		
		if(!clickWith(slot))
			return;
		aimPoint = null;
		setStage(prevSlot >= 0 ? Stage.BACK : Stage.IDLE);
	}
	
	/** A wind charge straight down (a launch) or at the target's feet. */
	private void updateWind(Entity target)
	{
		LocalPlayer player = MC.player;
		int slot = findSlot(s -> s.is(Items.WIND_CHARGE));
		if(slot < 0 || target == null || stageTicks > THROW_AIM_TIMEOUT)
		{
			finish();
			return;
		}
		
		if(windJump)
		{
			aimPoint = player.position();
			if(player.getXRot() < DOWN_PITCH || player.xRotLast < DOWN_PITCH)
				return;
		}else
		{
			aimPoint = target.position().add(0, 0.1, 0);
			if(!onRotation(RotationUtils.getNeededRotations(aimPoint), 2))
				return;
		}
		
		if(!clickWith(slot))
			return;
		aimPoint = null;
		setStage(prevSlot >= 0 ? Stage.BACK : Stage.IDLE);
	}
	
	/**
	 * Water out of a web: looking down from inside it, the bucket puts the
	 * source above the web, which flows down and washes it away; then the
	 * empty bucket picks the source back up.
	 */
	private void updateWater()
	{
		LocalPlayer player = MC.player;
		aimPoint = player.position();
		boolean down =
			player.getXRot() >= DOWN_PITCH && player.xRotLast >= DOWN_PITCH;
		switch(stage)
		{
			case WATER_PLACE ->
			{
				int slot = findSlot(s -> s.is(Items.WATER_BUCKET));
				if(slot < 0 || stageTicks > THROW_AIM_TIMEOUT)
				{
					finish();
					return;
				}
				if(down && clickWith(slot))
					setStage(Stage.WATER_WAIT);
			}
			case WATER_WAIT ->
			{
				if(webCells(player.getBoundingBox()) == null
					|| stageTicks > WASH_TIMEOUT)
					setStage(Stage.WATER_PICKUP);
			}
			default ->
			{
				// full again (or something else in the hand): done; else a
				// click a tick until it is - one click used to be all, and a
				// missed pickup left the water on the ground (user,
				// 2026-10-29). Still empty after that: restocked from the
				// inventory (restockSlot).
				if(!player.getMainHandItem().is(Items.BUCKET)
					|| stageTicks > PICKUP_TIMEOUT)
				{
					finish();
					return;
				}
				if(!down)
					return;
				InteractionSimulator.rightClickItem(InteractionHand.MAIN_HAND);
			}
		}
	}
	
	/** Notes which kit item each hotbar slot holds (for restockSlot). */
	private void trackSlots()
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			int kind = kindOf(stack);
			if(kind >= 0)
				slotKind[i] = kind;
			else if(!stack.isEmpty()
				&& !(slotKind[i] == WATER && stack.is(Items.BUCKET)))
				slotKind[i] = -1;
		}
	}
	
	private static int kindOf(ItemStack stack)
	{
		for(int k = 0; k < KINDS.size(); k++)
			if(KINDS.get(k).test(stack))
				return k;
		return -1;
	}
	
	/**
	 * A hotbar slot whose kit item ran out (empty, or a water bucket left
	 * empty) while the inventory has more, or -1. Only where a player could
	 * open the inventory now.
	 */
	private int restockSlot()
	{
		LocalPlayer player = MC.player;
		if(MC.screen != null || player.hasInfiniteMaterials()
			|| MC.gameMode.isServerControlledInventory()
			|| player.isUsingItem())
			return -1;
		for(int i = 0; i < 9; i++)
		{
			int kind = slotKind[i];
			if(kind < 0)
				continue;
			ItemStack stack = player.getInventory().getItem(i);
			boolean ranOut =
				stack.isEmpty() || kind == WATER && stack.is(Items.BUCKET);
			if(ranOut && inventorySlot(kind) >= 0)
				return i;
		}
		return -1;
	}
	
	/** Where the main inventory (not the hotbar) has this kind, or -1. */
	private static int inventorySlot(int kind)
	{
		for(int i = 9; i < 36; i++)
			if(KINDS.get(kind).test(MC.player.getInventory().getItem(i)))
				return i;
		return -1;
	}
	
	/**
	 * Restocking like a player (user, 2026-10-29): open the inventory (this
	 * tick), one item a tick from it into the hotbar slot that ran out (in
	 * place of an empty bucket - swapped), then close it: 3 ticks for one
	 * item. No keys meanwhile (the open inventory takes them, like vanilla's)
	 * and no hit. PvpSim does the same.
	 */
	private void updateRestock()
	{
		var opener = WurstClient.INSTANCE.getInventoryOpener();
		// the first call comes right from setStage (tick 0): this checked
		// tick 1, so it never opened, went idle and started over every tick -
		// "busy" for a whole fight, not one hit (McSimply, 2026-11-10)
		if(stageTicks == 0)
		{
			opener.beforeInventoryClick();
			if(!opener.isOpenedByUs())
			{
				restockRetryLeft = RESTOCK_RETRY_TICKS;
				setStage(Stage.IDLE);
			}
			return;
		}
		
		int slot =
			opener.isOpenedByUs() && stageTicks < 12 ? restockSlotOpen() : -1;
		if(slot < 0)
		{
			// done (or closed by something else): close it like Esc
			opener.beforeWorldInteraction();
			setStage(Stage.IDLE);
			return;
		}
		WurstClient.IMC.getInteractionManager().windowClick_SWAP(
			InventoryUtils.toNetworkSlot(inventorySlot(slotKind[slot])), slot);
	}
	
	/** restockSlot() with the inventory already open. */
	private int restockSlotOpen()
	{
		for(int i = 0; i < 9; i++)
		{
			int kind = slotKind[i];
			if(kind < 0)
				continue;
			ItemStack stack = MC.player.getInventory().getItem(i);
			if((stack.isEmpty() || kind == WATER && stack.is(Items.BUCKET))
				&& inventorySlot(kind) >= 0)
				return i;
		}
		return -1;
	}
	
	/**
	 * Whether the camera and the rotation the server has are both within
	 * this many degrees of the wanted one.
	 */
	private static boolean onRotation(Rotation want, double degrees)
	{
		LocalPlayer player = MC.player;
		Rotation now = new Rotation(player.getYRot(), player.getXRot());
		Rotation sent = new Rotation(player.yRotLast, player.xRotLast);
		return now.getAngleTo(want) <= degrees
			&& sent.getAngleTo(want) <= degrees;
	}
	
	/**
	 * To this hotbar slot (when a slot change may go out now) and a right
	 * click with it. False if it has to wait a tick.
	 */
	private boolean clickWith(int slot)
	{
		if(slot != MC.player.getInventory().getSelectedSlot())
		{
			if(!PacketOrder.canChangeSlotNow())
				return false;
			rememberSlot();
			selectSlot(slot);
		}
		InteractionSimulator.rightClickItem(InteractionHand.MAIN_HAND);
		return true;
	}
	
	/** In the hotbar and not on cooldown. */
	private static boolean isReady(java.util.function.Predicate<ItemStack> item)
	{
		int slot = findSlot(item);
		return slot >= 0 && !MC.player.getCooldowns()
			.isOnCooldown(MC.player.getInventory().getItem(slot));
	}
	
	/** One of your ender pearls is still in the air. */
	public static boolean isPearlFlying()
	{
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof ThrownEnderpearl pearl
				&& pearl.getOwner() == MC.player)
				return true;
		return false;
	}
	
	/** Ticks until this item can be used again (cooldown x full ticks). */
	public static int cooldownTicks(
		java.util.function.Predicate<ItemStack> item, int full)
	{
		int slot = findSlot(item);
		if(slot < 0)
			return 0;
		return (int)Math.ceil(MC.player.getCooldowns().getCooldownPercent(
			MC.player.getInventory().getItem(slot), 0) * full);
	}
	
	private void updateEating(boolean eat)
	{
		LocalPlayer player = MC.player;
		ItemStack hand = player.getMainHandItem();
		boolean holding = isGoldenApple(hand);
		
		// eaten (the count went down, or the last one is gone) - before the
		// held key starts the next one. Once started it's finished, whatever
		// the network says (user, 2026-10-26: it let go mid-bite).
		if(!eat && eatCount == 0
			|| eatCount > 0 && (!holding || hand.getCount() < eatCount))
		{
			finish();
			return;
		}
		
		if(!holding)
		{
			int slot = findSlot(NeuralItems::isGoldenApple);
			if(slot < 0 || !PacketOrder.canChangeSlotNow())
			{
				if(slot < 0)
					finish();
				return;
			}
			rememberSlot();
			selectSlot(slot);
			eatCount = player.getMainHandItem().getCount();
		}else if(eatCount == 0)
			eatCount = player.getMainHandItem().getCount();
		
		MC.options.keyUse.setDown(true);
		holdingUse = true;
	}
	
	private void updateBreakWeb()
	{
		LocalPlayer player = MC.player;
		BlockPos[] cells = webCells(player.getBoundingBox());
		if(cells == null || stageTicks > BREAK_TIMEOUT)
		{
			finish();
			return;
		}
		
		// the web closest to where you look
		BlockPos best = null;
		double bestAngle = Double.MAX_VALUE;
		for(BlockPos pos : cells)
		{
			if(pos == null)
				continue;
			double angle =
				RotationUtils.getAngleToLookVec(Vec3.atCenterOf(pos));
			if(angle < bestAngle)
			{
				bestAngle = angle;
				best = pos;
			}
		}
		aimPoint = Vec3.atCenterOf(best);
		
		boolean onWeb = MC.hitResult instanceof BlockHitResult hit
			&& hit.getType() == HitResult.Type.BLOCK
			&& BlockUtils.getState(hit.getBlockPos()).is(Blocks.COBWEB)
			&& sentLookHits(hit.getBlockPos(), null, null);
		if(onWeb)
		{
			MC.options.keyAttack.setDown(true);
			holdingAttack = true;
		}else
			releaseAttack();
	}
	
	/** Ends the current item: keys up, the weapon back next tick. */
	private void finish()
	{
		releaseUse();
		releaseAttack();
		aimPoint = null;
		setStage(prevSlot >= 0 ? Stage.BACK : Stage.IDLE);
	}
	
	/**
	 * Lets go of everything and puts the weapon back - now if it can, else
	 * with {@link #tickBack()} on a later tick.
	 */
	public void reset()
	{
		releaseUse();
		releaseAttack();
		aimPoint = null;
		stuckTicks = 0;
		if(MC.player == null)
			prevSlot = -1;
		setStage(prevSlot >= 0 ? Stage.BACK : Stage.IDLE);
		tickBack();
	}
	
	/** Puts the weapon back after an item, with or without a target. */
	public void tickBack()
	{
		if(stage != Stage.BACK || MC.player == null
			|| !PacketOrder.canChangeSlotNow())
			return;
		if(prevSlot >= 0)
			selectSlot(prevSlot);
		prevSlot = -1;
		setStage(Stage.IDLE);
	}
	
	private void setStage(Stage stage)
	{
		this.stage = stage;
		pearlAim = null;
		stageTicks = 0;
		if(stage != Stage.EATING)
			eatCount = 0;
	}
	
	private void releaseUse()
	{
		if(!holdingUse)
			return;
		IKeyMapping.get(MC.options.keyUse).resetPressedState();
		holdingUse = false;
	}
	
	private void releaseAttack()
	{
		if(!holdingAttack)
			return;
		IKeyMapping.get(MC.options.keyAttack).resetPressedState();
		holdingAttack = false;
	}
	
	private void rememberSlot()
	{
		if(prevSlot < 0)
			prevSlot = MC.player.getInventory().getSelectedSlot();
	}
	
	private static void selectSlot(int slot)
	{
		MC.player.getInventory().setSelectedSlot(slot);
		WurstClient.IMC.getInteractionManager().syncSelectedSlot();
	}
	
	// ── what's where
	
	/**
	 * The block whose top a cobweb goes on: the one under the target's feet,
	 * if the target stands on it and the cell above is free.
	 */
	private static BlockPos findWebFloor(Entity target)
	{
		if(target == null || !target.onGround())
			return null;
		BlockPos feet = target.blockPosition();
		BlockPos floor = feet.below();
		if(!BlockUtils.getState(feet).canBeReplaced()
			|| BlockUtils.getState(feet).is(Blocks.COBWEB) || !BlockUtils
				.getState(floor).isFaceSturdy(MC.level, floor, Direction.UP))
			return null;
		
		Vec3 eyes = MC.player.getEyePosition();
		if(eyes.distanceToSqr(Vec3.atCenterOf(floor)) > (BLOCK_REACH + 0.5)
			* (BLOCK_REACH + 0.5))
			return null;
		return floor;
	}
	
	/**
	 * A point on the floor block's top face to click: in reach, the ray to it
	 * not through the target (the crosshair would pick the target), the face
	 * the first thing it hits. The one closest to where you look.
	 */
	private static Vec3 pickWebPoint(BlockPos floor, Vec3 eyes, AABB targetBox,
		float yaw, float pitch)
	{
		AABB avoid = targetBox.inflate(0.1);
		Vec3 look = new Rotation(yaw, pitch).toLookVec();
		Vec3 best = null;
		double bestDot = -2;
		for(int i = 0; i < 5; i++)
			for(int j = 0; j < 5; j++)
			{
				Vec3 p = new Vec3(floor.getX() + 0.1 + 0.2 * i,
					floor.getY() + 1, floor.getZ() + 0.1 + 0.2 * j);
				if(eyes.distanceToSqr(p) > BLOCK_REACH * BLOCK_REACH
					|| avoid.contains(eyes) || avoid.clip(eyes, p).isPresent())
					continue;
				BlockHitResult hit =
					clip(eyes, p.add(p.subtract(eyes).normalize().scale(0.05)));
				if(hit.getType() != HitResult.Type.BLOCK
					|| !hit.getBlockPos().equals(floor)
					|| hit.getDirection() != Direction.UP)
					continue;
				double dot = p.subtract(eyes).normalize().dot(look);
				if(dot > bestDot)
				{
					bestDot = dot;
					best = p;
				}
			}
		return best;
	}
	
	/**
	 * Whether the last rotation sent (what the server has when the click
	 * arrives) hits this block - on that face, if one is given - before the
	 * target.
	 */
	private static boolean sentLookHits(BlockPos pos, Direction face,
		Entity target)
	{
		LocalPlayer player = MC.player;
		Vec3 eyes = player.getEyePosition();
		Vec3 end = eyes.add(new Rotation(player.yRotLast, player.xRotLast)
			.toLookVec().scale(BLOCK_REACH));
		BlockHitResult hit = clip(eyes, end);
		if(hit.getType() != HitResult.Type.BLOCK
			|| !hit.getBlockPos().equals(pos)
			|| face != null && hit.getDirection() != face)
			return false;
		if(target == null)
			return true;
		AABB box = target.getBoundingBox();
		return !box.contains(eyes) && box.clip(eyes, hit.getLocation()).map(
			p -> p.distanceToSqr(eyes) >= hit.getLocation().distanceToSqr(eyes))
			.orElse(true);
	}
	
	private static BlockHitResult clip(Vec3 from, Vec3 to)
	{
		return MC.level.clip(new ClipContext(from, to,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
	}
	
	/** The cobweb cells this box is in, or null if none. */
	public static BlockPos[] webCells(AABB box)
	{
		BlockPos[] cells = null;
		int n = 0;
		for(BlockPos pos : BlockPos.betweenClosed(
			BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos
				.containing(box.maxX - 1e-7, box.maxY - 1e-7, box.maxZ - 1e-7)))
		{
			if(!BlockUtils.getState(pos).is(Blocks.COBWEB))
				continue;
			if(cells == null)
				cells = new BlockPos[12];
			if(n < cells.length)
				cells[n++] = pos.immutable();
		}
		return cells;
	}
	
	static int findSlot(java.util.function.Predicate<ItemStack> item)
	{
		for(int i = 0; i < 9; i++)
			if(item.test(MC.player.getInventory().getItem(i)))
				return i;
		return -1;
	}
	
	/** Hotbar count of an item kind, for the network's observation. */
	public static int count(java.util.function.Predicate<ItemStack> item)
	{
		int n = 0;
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(item.test(stack))
				n += stack.getCount();
		}
		return n;
	}
	
	public static boolean isCobweb(ItemStack stack)
	{
		return stack.is(Items.COBWEB);
	}
	
	public static boolean isGoldenApple(ItemStack stack)
	{
		return stack.is(Items.GOLDEN_APPLE)
			|| stack.is(Items.ENCHANTED_GOLDEN_APPLE);
	}
	
	/** A splash potion with this effect (Strength II, Speed, ...). */
	public static java.util.function.Predicate<ItemStack> isSplash(
		Holder<MobEffect> which)
	{
		return stack -> {
			if(!stack.is(Items.SPLASH_POTION))
				return false;
			PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
			if(contents == null)
				return false;
			for(MobEffectInstance effect : contents.getAllEffects())
				if(effect.is(which))
					return true;
			return false;
		};
	}
}
