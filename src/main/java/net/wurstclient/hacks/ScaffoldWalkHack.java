/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Arrays;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.MouseUpdateListener.MouseUpdateEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.FaceTargetSetting;
import net.wurstclient.settings.FaceTargetSetting.FaceTarget;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.CameraAim;
import net.wurstclient.util.Godbridge;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.PacketOrder;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"scaffold walk", "BridgeWalk", "bridge walk", "AutoBridge",
	"auto bridge", "tower", "godbridge", "god bridge"})
public final class ScaffoldWalkHack extends Hack
	implements UpdateListener, RenderListener, MouseUpdateListener
{
	private final FaceTargetSetting faceTarget =
		FaceTargetSetting.withPacketSpam(WText
			.literal("How ScaffoldWalk faces the block it places against.\n\n"
				+ "§lClient-side§r godbridges, the way players do it: walk"
				+ " toward where you want the bridge and ScaffoldWalk turns"
				+ " around to face back along it at 45 degrees, looks down"
				+ " at about 75 degrees and walks backward with §lS§r plus"
				+ " §lA§r or §lD§r - your keys keep meaning the direction you"
				+ " were facing. It clicks the side of the last block the"
				+ " moment the crosshair is on it, no sneaking, and turns"
				+ " back to where you were looking when you stop.\n\n"
				+ "The other modes place instantly with a faked or no"
				+ " rotation."),
			FaceTarget.CLIENT);
	
	private final CheckboxSetting humanizeAim = new CheckboxSetting(
		"Humanize aim",
		"Turns like a hand on a mouse: a short reaction delay, easing in and"
			+ " out, a slightly curved path. Off: a straight, even turn.",
		true);
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"How fast §lClient-side§r turns around to bridge, and back"
			+ " afterwards.",
		900, 180, 1800, 30, ValueDisplay.DEGREES.withSuffix("/s"));
	
	/**
	 * Your center this far off the lane's middle keeps the clicks clear of
	 * the block's side corner.
	 */
	private static final double LANE_OFFSET = 0.15;
	
	private final CameraAim cameraAim = new CameraAim();
	private final Godbridge.Planner planner = new Godbridge.Planner();
	private final Object bridgeTurn = new Object();
	private final Object returnTurn = new Object();
	
	// godbridge state
	private boolean bridging;
	private Direction bridgeDir;
	private int laneY;
	/** +1: facing back to the right of the bridge (S+D), -1: left (S+A). */
	private int side;
	/** 1 = along the bridge, -1 = back toward its start, 0 = standing. */
	private int moving;
	private int idleTicks;
	private float targetYaw;
	private float targetPitch;
	private double nominalPitch;
	/** Where you were at the start of the last tick, to measure your step. */
	private Vec3 lastTickPos;
	
	// where you were looking before, to turn back to
	private float origYaw;
	private float origPitch;
	private boolean returning;
	
	/** The slot to go back to once done, -1 = none. */
	private int prevSlot = -1;
	private boolean slotRestorePending;
	private int legacyIdleTicks;
	
	public ScaffoldWalkHack()
	{
		super("ScaffoldWalk");
		setCategory(Category.BLOCKS);
		addSetting(faceTarget);
		addSetting(humanizeAim);
		addSetting(turnSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		bridging = false;
		returning = false;
		prevSlot = -1;
		slotRestorePending = false;
		cameraAim.reset();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(MouseUpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(MouseUpdateListener.class, this);
		bridging = false;
		returning = false;
		cameraAim.reset();
		if(prevSlot != -1)
			restoreSlot();
		prevSlot = -1;
		slotRestorePending = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(slotRestorePending && restoreSlot())
			slotRestorePending = false;
		
		if(faceTarget.getSelected() == FaceTarget.CLIENT)
			tickGodbridge();
		else
		{
			if(bridging)
				endBridge();
			tickLegacy();
		}
	}
	
	// ── Godbridge ───────────────────────────────────────────────────────
	
	private void tickGodbridge()
	{
		LocalPlayer player = MC.player;
		Vec2 intent = getPhysicalIntent();
		
		if(!bridging)
		{
			if(MC.screen != null || intent.lengthSquared() < 1e-4
				|| !player.onGround() || MC.options.keyShift.isDown())
				return;
				
			// the direction you're walking in, rounded to the nearest of the
			// four bridge directions (while still turning back from the last
			// bridge, your keys mean the direction you faced before it)
			float keysYaw = returning ? origYaw : player.getYRot();
			Direction dir = Direction.fromYRot(
				keysYaw - Math.toDegrees(Math.atan2(intent.x, intent.y)));
			// diagonal keys would end it again right away
			Vec3 walk = toWorld(intent, keysYaw);
			if(Math.abs(walk.dot(lateralVec(dir))) > 0.5)
				return;
			int lane = BlockPos.containing(player.position()).below().getY();
			if(findGap(dir, lane, 2.5) == null || findBlockSlot() == -1)
				return;
			
			startBridge(dir, lane);
		}
		
		// you sneaked, opened something, fell off, or walk sideways off the
		// lane: hand the controls back
		Vec3 world = toWorld(intent, origYaw);
		Vec3 d = dirVec(bridgeDir);
		double along = world.dot(d);
		double across = Math.abs(world.dot(lateralVec(bridgeDir)));
		if(MC.screen != null || MC.options.keyShift.isDown()
			|| player.getY() < laneY + 1 - 0.6 || across > 0.5)
		{
			endBridge();
			return;
		}
		
		int wanted = along > 0.3 ? 1 : along < -0.3 ? -1 : 0;
		if(wanted == 0 && ++idleTicks > 10)
		{
			endBridge();
			return;
		}
		if(wanted != 0)
			idleTicks = 0;
			
		// Turn around first, then walk - like a player. Walking during the
		// turn could take you over the edge before the aim is there.
		boolean turned =
			Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw)) < 8
				&& Math.abs(player.getXRot() - targetPitch) < 8;
		moving = turned ? wanted : 0;
		
		// click first (with the aim from before), then aim for the next one
		BlockPos gap = findGap(bridgeDir, laneY, 1.5);
		if(gap != null && ensureBlockSlot())
			tryPlace(gap);
		
		updateAim(findGap(bridgeDir, laneY, 1.5));
		lastTickPos = player.position();
	}
	
	private void startBridge(Direction dir, int lane)
	{
		LocalPlayer player = MC.player;
		bridging = true;
		bridgeDir = dir;
		laneY = lane;
		idleTicks = 0;
		lastTickPos = null;
		planner.reset();
		moving = 0;
		if(!returning)
		{
			origYaw = player.getYRot();
			origPitch = player.getXRot();
		}
		returning = false;
		
		// face back along the bridge at 45 degrees, to whichever side is the
		// shorter turn
		float back = dir.toYRot() + 180;
		float right = back + 45;
		float left = back - 45;
		side = Math.abs(Mth.wrapDegrees(right - player.getYRot())) <= Math
			.abs(Mth.wrapDegrees(left - player.getYRot())) ? 1 : -1;
		
		// everyone holds a slightly different angle
		nominalPitch = Godbridge.NOMINAL_PITCH + (Math.random() - 0.5) * 0.6;
		targetYaw = back + 45 * side;
		targetPitch = (float)nominalPitch;
	}
	
	private void endBridge()
	{
		if(!bridging)
			return;
		
		bridging = false;
		moving = 0;
		returning = true;
		slotRestorePending = prevSlot != -1;
	}
	
	/**
	 * Picks this tick's aim: back along the bridge at 45 degrees (nudged a
	 * little to keep you in the lane), and the pitch that lands the next
	 * click inside the window - see {@link Godbridge}.
	 */
	private void updateAim(BlockPos gap)
	{
		LocalPlayer player = MC.player;
		Vec3 d = dirVec(bridgeDir);
		Vec3 e = lateralVec(bridgeDir);
		float baseYaw = bridgeDir.toYRot() + 180 + 45 * side;
		Vec3 look = Vec3.directionFromRotation(0, baseYaw);
		
		// stay a little to the side the crosshair moves away from, so the
		// clicks land on the side face, not its corner
		BlockPos column = gap != null ? gap.relative(bridgeDir.getOpposite())
			: BlockPos.containing(player.position()).below();
		Vec3 center = Vec3.atCenterOf(column);
		double offset = player.position().subtract(center).dot(e);
		double wanted = -LANE_OFFSET * Math.signum(look.dot(e));
		float correction =
			(float)Mth.clamp((wanted - offset) * 20, -3, 3) * moving;
		targetYaw = baseYaw + correction;
		
		if(gap == null || moving <= 0)
		{
			targetPitch = (float)nominalPitch;
			return;
		}
		
		// past: how far your center is past the last block's side face
		Vec3 last = Vec3.atCenterOf(gap.relative(bridgeDir.getOpposite()));
		double past = player.position().subtract(last).dot(d) - 0.5;
		// the last tick's actual step (the stored velocity is after friction,
		// about half of it; xo is already reset to the current position here)
		double speed = lastTickPos == null ? 0
			: Math.max(player.position().subtract(lastTickPos).dot(d), 0);
		double eyeAboveTop = player.getEyeY() - (laneY + 1);
		double horizontal = -Vec3.directionFromRotation(0, targetYaw).dot(d);
		
		targetPitch = (float)planner.pitch(past, speed, eyeAboveTop, horizontal,
			nominalPitch);
	}
	
	/**
	 * Clicks what the crosshair is really on, like a player spam-clicking:
	 * only when it's on the last block's side face (with the rotation the
	 * server already has, too - the click goes out before this tick's
	 * movement packet), the spot is free and your feet are still level with
	 * the bridge.
	 */
	private void tryPlace(BlockPos gap)
	{
		LocalPlayer player = MC.player;
		if(!BlockUtils.getState(gap).canBeReplaced())
			return;
			
		// your feet already below the bridge's top: the block would be
		// inside you
		if(player.getBoundingBox().minY < laneY + 1 - 1e-4
			&& player.getBoundingBox().intersects(new AABB(gap)))
			return;
		
		BlockPos last = gap.relative(bridgeDir.getOpposite());
		BlockHitResult now =
			raycast(new Rotation(player.getYRot(), player.getXRot()));
		BlockHitResult sent =
			raycast(new Rotation(player.yRotLast, player.xRotLast));
		if(!isOnFace(now, last) || !isOnFace(sent, last))
			return;
		
		InteractionSimulator.rightClickBlock(now, InteractionHand.MAIN_HAND);
	}
	
	private boolean isOnFace(BlockHitResult hit, BlockPos last)
	{
		return hit != null && hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().equals(last)
			&& hit.getDirection() == bridgeDir;
	}
	
	private BlockHitResult raycast(Rotation rotation)
	{
		LocalPlayer player = MC.player;
		Vec3 eyes = player.getEyePosition();
		Vec3 end = eyes
			.add(rotation.toLookVec().scale(player.blockInteractionRange()));
		return MC.level.clip(new ClipContext(eyes, end,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
	}
	
	/**
	 * The first free spot on the lane in front of you (within
	 * {@code reach} blocks of your center), with a block behind it to click
	 * on. Null when there's nothing to bridge.
	 */
	private BlockPos findGap(Direction dir, int lane, double reach)
	{
		Vec3 pos = MC.player.position();
		BlockPos start = BlockPos.containing(pos.x, lane, pos.z);
		for(int i = 0; i <= Math.ceil(reach); i++)
		{
			BlockPos spot = start.relative(dir, i);
			if(!BlockUtils.getState(spot).canBeReplaced())
				continue;
			
			// too far ahead to matter yet
			Vec3 spotCenter = Vec3.atCenterOf(spot);
			if(spotCenter.subtract(pos).dot(dirVec(dir)) - 0.5 > reach)
				return null;
			
			BlockPos behind = spot.relative(dir.getOpposite());
			return BlockUtils.canBeClicked(behind) ? spot : null;
		}
		return null;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null)
			return;
		
		double speed = turnSpeed.getValue();
		boolean humanize = humanizeAim.isChecked();
		if(bridging)
		{
			cameraAim.turnCamera(bridgeTurn,
				new Rotation(targetYaw, targetPitch), new double[2], humanize,
				speed);
			return;
		}
		
		if(!returning)
			return;
		
		// turn back to where you were looking before
		Rotation orig = new Rotation(origYaw, origPitch);
		cameraAim.turnCamera(returnTurn, orig, new double[2], humanize, speed);
		if(Math.abs(Mth.wrapDegrees(MC.player.getYRot() - origYaw)) < 1
			&& Math.abs(MC.player.getXRot() - origPitch) < 1)
		{
			returning = false;
			cameraAim.reset();
		}
	}
	
	/**
	 * While bridging, the camera is ScaffoldWalk's - your mouse would only
	 * fight it. While turning back, moving the mouse takes over.
	 */
	@Override
	public void onMouseUpdate(MouseUpdateEvent event)
	{
		if(bridging)
		{
			event.setDeltaX(0);
			event.setDeltaY(0);
			return;
		}
		
		if(returning && (event.getDeltaX() != 0 || event.getDeltaY() != 0))
		{
			returning = false;
			cameraAim.reset();
		}
	}
	
	/**
	 * Called from KeyboardInputMixin after vanilla has read the movement
	 * keys: while bridging (and turning back), your keys keep meaning the
	 * direction you were facing before. They become the keys that walk that
	 * way with the camera where it is now - S plus A or D while bridging,
	 * like a player godbridging - and those are also the keys the server
	 * sees.
	 *
	 * @return the input to use, or null to leave vanilla's alone
	 */
	public Input modifyInput(Input keys)
	{
		if(!isEnabled() || MC.player == null || !bridging && !returning)
			return null;
		
		Vec3 world;
		if(bridging)
			world = dirVec(bridgeDir).scale(moving);
		else
			world = toWorld(getPhysicalIntent(), origYaw);
		
		if(world.lengthSqr() < 1e-4)
			return new Input(false, false, false, false, keys.jump(),
				keys.shift(), keys.sprint());
		
		// the eight key combinations, relative to where the camera faces
		double worldYaw = Math.toDegrees(Math.atan2(-world.x, world.z));
		double rel = Mth.wrapDegrees(worldYaw - MC.player.getYRot());
		int octant = Math.floorMod((int)Math.round(rel / 45), 8);
		boolean forward = octant == 0 || octant == 1 || octant == 7;
		boolean backward = octant == 3 || octant == 4 || octant == 5;
		boolean right = octant == 1 || octant == 2 || octant == 3;
		boolean left = octant == 5 || octant == 6 || octant == 7;
		
		// no sneaking and no sprinting backward while godbridging
		boolean shift = !bridging && keys.shift();
		boolean sprint = !bridging && keys.sprint();
		return new Input(forward, backward, left, right, keys.jump(), shift,
			sprint);
	}
	
	/** Vanilla's move vector for a set of keys. */
	public static Vec2 moveVectorOf(Input keys)
	{
		float strafe = impulse(keys.left(), keys.right());
		float forward = impulse(keys.forward(), keys.backward());
		return new Vec2(strafe, forward).normalized();
	}
	
	private static float impulse(boolean positive, boolean negative)
	{
		return positive == negative ? 0 : positive ? 1 : -1;
	}
	
	/** The movement keys you're physically holding, x = left, y = forward. */
	private Vec2 getPhysicalIntent()
	{
		float strafe =
			impulse(MC.options.keyLeft.isDown(), MC.options.keyRight.isDown());
		float forward =
			impulse(MC.options.keyUp.isDown(), MC.options.keyDown.isDown());
		return new Vec2(strafe, forward);
	}
	
	/**
	 * Turns a key direction (x = left, y = forward) at a yaw into the world.
	 */
	private static Vec3 toWorld(Vec2 intent, float yaw)
	{
		Vec3 forward = Vec3.directionFromRotation(0, yaw);
		Vec3 left = Vec3.directionFromRotation(0, yaw - 90);
		return forward.scale(intent.y).add(left.scale(intent.x));
	}
	
	private static Vec3 dirVec(Direction dir)
	{
		return new Vec3(dir.getStepX(), 0, dir.getStepZ());
	}
	
	/** 90 degrees clockwise (seen from above) from the bridge direction. */
	private static Vec3 lateralVec(Direction dir)
	{
		return new Vec3(-dir.getStepZ(), 0, dir.getStepX());
	}
	
	// ── Hotbar ─────────────────────────────────────────────────────────
	
	private boolean isUsableBlock(ItemStack stack, BlockPos below)
	{
		if(stack.isEmpty() || !(stack.getItem() instanceof BlockItem))
			return false;
		
		// only full, solid blocks
		Block block = Block.byItem(stack.getItem());
		BlockState state = block.defaultBlockState();
		if(!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,
			BlockPos.ZERO))
			return false;
		
		// not ones that would fall
		return !(block instanceof FallingBlock
			&& FallingBlock.isFree(BlockUtils.getState(below.below())));
	}
	
	private int findBlockSlot()
	{
		BlockPos below = BlockPos.containing(MC.player.position()).below();
		int selected = MC.player.getInventory().getSelectedSlot();
		if(isUsableBlock(MC.player.getInventory().getItem(selected), below))
			return selected;
		
		for(int i = 0; i < 9; i++)
			if(isUsableBlock(MC.player.getInventory().getItem(i), below))
				return i;
		return -1;
	}
	
	/**
	 * Holds the blocks: switches to them once and stays there while
	 * scaffolding, like a player would, instead of switching to them and
	 * back for every block (two slot packets each time). The old slot comes
	 * back once done.
	 */
	private boolean ensureBlockSlot()
	{
		int slot = findBlockSlot();
		if(slot == -1)
			return false;
		
		int selected = MC.player.getInventory().getSelectedSlot();
		if(slot == selected)
			return true;
		
		// not after a click in the same tick (Grim PacketOrderE)
		if(!PacketOrder.canChangeSlotNow())
			return false;
		
		if(prevSlot == -1)
			prevSlot = selected;
		slotRestorePending = false;
		MC.player.getInventory().setSelectedSlot(slot);
		IMC.getInteractionManager().syncSelectedSlot();
		return true;
	}
	
	private boolean restoreSlot()
	{
		if(prevSlot == -1)
			return true;
		if(MC.player == null || !PacketOrder.canChangeSlotNow())
			return false;
		
		MC.player.getInventory().setSelectedSlot(prevSlot);
		IMC.getInteractionManager().syncSelectedSlot();
		prevSlot = -1;
		return true;
	}
	
	// ── Instant placement (Server-side, packet spam, off) ──────────────
	
	private void tickLegacy()
	{
		BlockPos belowPlayer =
			BlockPos.containing(MC.player.position()).below();
		
		// check if block is already placed
		if(!BlockUtils.getState(belowPlayer).canBeReplaced())
		{
			if(prevSlot != -1 && ++legacyIdleTicks > 10)
				slotRestorePending = true;
			return;
		}
		legacyIdleTicks = 0;
		
		if(!ensureBlockSlot())
			return;
		
		scaffoldTo(belowPlayer);
	}
	
	private void scaffoldTo(BlockPos belowPlayer)
	{
		// tries to place a block directly under the player
		if(placeBlock(belowPlayer))
			return;
			
		// if that doesn't work, tries to place a block next to the block that's
		// under the player
		Direction[] sides = Direction.values();
		for(Direction side : sides)
		{
			BlockPos neighbor = belowPlayer.relative(side);
			if(placeBlock(neighbor))
				return;
		}
		
		// if that doesn't work, tries to place a block next to a block that's
		// next to the block that's under the player
		for(Direction side : sides)
			for(Direction side2 : Arrays.copyOfRange(sides, side.ordinal(), 6))
			{
				if(side.getOpposite().equals(side2))
					continue;
				
				BlockPos neighbor = belowPlayer.relative(side).relative(side2);
				if(placeBlock(neighbor))
					return;
			}
	}
	
	private boolean placeBlock(BlockPos pos)
	{
		Vec3 eyesPos = RotationUtils.getEyesPos();
		
		for(Direction side : Direction.values())
		{
			BlockPos neighbor = pos.relative(side);
			Direction side2 = side.getOpposite();
			
			// check if side is visible (facing away from player)
			if(eyesPos.distanceToSqr(Vec3.atCenterOf(pos)) >= eyesPos
				.distanceToSqr(Vec3.atCenterOf(neighbor)))
				continue;
			
			// check if neighbor can be right clicked
			if(!BlockUtils.canBeClicked(neighbor))
				continue;
			
			Vec3 hitVec = Vec3.atCenterOf(neighbor)
				.add(Vec3.atLowerCornerOf(side2.getUnitVec3i()).scale(0.5));
			
			// check if hitVec is within range (4.25 blocks)
			if(eyesPos.distanceToSqr(hitVec) > 18.0625)
				continue;
			
			// face and place block, swinging only if a real click would
			faceTarget.face(hitVec);
			InteractionSimulator.rightClickBlock(
				new BlockHitResult(hitVec, side2, neighbor, false),
				InteractionHand.MAIN_HAND);
			MC.rightClickDelay = 4;
			
			return true;
		}
		
		return false;
	}
}
