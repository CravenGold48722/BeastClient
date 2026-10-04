/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
				+ "§lClient-side§r godbridges in whatever direction you walk,"
				+ " the way players do it: it turns around to face back along"
				+ " the bridge and walks backward - straight bridges at 45"
				+ " degrees with §lS§r plus §lA§r or §lD§r, diagonals"
				+ " straight back with §lS§r, other angles as a staircase -"
				+ " looking down at about 75 degrees and clicking the side of"
				+ " a block the moment the crosshair is on it. Your keys keep"
				+ " meaning the direction you were facing, and it turns back"
				+ " when you stop. Where no click works in time it sneaks to"
				+ " the edge (or looks straight at the block) instead of"
				+ " letting you fall.\n\n"
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
	
	/** Bridge directions this close to straight or diagonal snap to it. */
	private static final double SNAP_DEGREES = 4;
	
	/**
	 * Camera corrections smaller than this (degrees) go straight to the target
	 * instead of easing in and out.
	 */
	private static final double SMALL_TURN = 12;
	
	/** How far around you the stance choice looks at the blocks. */
	private static final int SNAPSHOT_RADIUS = 8;
	
	private final CameraAim cameraAim = new CameraAim();
	private final Object bridgeTurn = new Object();
	private final Object returnTurn = new Object();
	
	// godbridge state
	private boolean bridging;
	private Godbridge planner;
	private double bridgeYaw;
	private double stanceYaw;
	private int laneY;
	/** 1 = along the bridge, -1 = back toward its start, 0 = standing. */
	private int moving;
	private boolean sneaking;
	/** Whether the turn-around at the start is done. */
	private boolean walking;
	private int idleTicks;
	private long tick;
	private float targetYaw;
	private float targetPitch;
	
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
		sneaking = false;
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
		sneaking = false;
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
		tick++;
		
		if(!bridging && !tryStartBridge(intent))
		{
			return;
		}
		
		// you sneaked, opened something, fell off, or walk sideways off the
		// line: hand the controls back
		Vec3 world = toWorld(intent, origYaw);
		Vec3 dir = Vec3.directionFromRotation(0, (float)bridgeYaw);
		double along = world.dot(dir);
		double across = Math.abs(
			world.dot(Vec3.directionFromRotation(0, (float)bridgeYaw + 90)));
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
		// turn could take you over the edge before the aim is there. (Only at
		// the start: later turns, like looking straight at a block, happen
		// while the planner keeps you sneaking on the edge.)
		if(!walking)
			walking =
				Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw)) < 8
					&& Math.abs(player.getXRot() - targetPitch) < 8;
		moving = walking ? wanted : 0;
		
		// click first (with the aim from before), then aim for the next one
		if(ensureBlockSlot())
			tryPlace();
		updateAim();
	}
	
	private boolean tryStartBridge(Vec2 intent)
	{
		LocalPlayer player = MC.player;
		if(MC.screen != null || intent.lengthSquared() < 1e-4
			|| !player.onGround() || MC.options.keyShift.isDown())
			return false;
			
		// the direction you're walking in (while still turning back from
		// the last bridge, your keys mean the direction you faced before)
		float keysYaw = returning ? origYaw : player.getYRot();
		double yaw = keysYaw - Math.toDegrees(Math.atan2(intent.x, intent.y));
		// nearly straight or diagonal: exactly that, a cleaner bridge
		double snapped = Math.round(yaw / 45) * 45;
		if(Math.abs(Mth.wrapDegrees(yaw - snapped)) <= SNAP_DEGREES)
			yaw = snapped;
		
		int lane = BlockPos.containing(player.position()).below().getY();
		Vec3 pos = player.position();
		double[] anchor = Godbridge.chooseAnchor(yaw, pos.x, pos.z);
		Godbridge bridge =
			new Godbridge(laneOf(lane), yaw, anchor[0], anchor[1]);
		
		// only with an edge coming up, and blocks to bridge with
		if(!edgeAhead(bridge, pos, yaw, 2.5) || findBlockSlot() == -1)
			return false;
		
		Vec3 velocity = player.getDeltaMovement().scale(1 / Godbridge.FRICTION);
		double eyeAboveTop = player.getEyeY() - (lane + 1);
		stanceYaw = Godbridge.chooseStance(snapshot(lane, pos), yaw, anchor[0],
			anchor[1], pos.x, pos.z, velocity.x, velocity.z, eyeAboveTop,
			player.blockInteractionRange(), player.getYRot());
		if(Double.isNaN(stanceYaw))
			return false;
		
		bridging = true;
		planner = bridge;
		bridgeYaw = yaw;
		laneY = lane;
		idleTicks = 0;
		moving = 0;
		sneaking = false;
		walking = false;
		if(!returning)
		{
			origYaw = player.getYRot();
			origPitch = player.getXRot();
		}
		returning = false;
		targetYaw = (float)stanceYaw;
		targetPitch = (float)Godbridge.NOMINAL_PITCH;
		return true;
	}
	
	private boolean edgeAhead(Godbridge bridge, Vec3 pos, double yaw,
		double distance)
	{
		Vec3 dir = Vec3.directionFromRotation(0, (float)yaw);
		for(double d = 0; d <= distance; d += 0.1)
			if(!bridge.supported(pos.x + dir.x * d, pos.z + dir.z * d))
				return true;
		return false;
	}
	
	private Godbridge.Lane laneOf(int lane)
	{
		return (x, z) -> !BlockUtils.getState(new BlockPos(x, lane, z))
			.canBeReplaced();
	}
	
	/** The lane's blocks around you, for simulating the stances. */
	private Set<Long> snapshot(int lane, Vec3 pos)
	{
		Set<Long> solid = new HashSet<>();
		int px = Mth.floor(pos.x);
		int pz = Mth.floor(pos.z);
		Godbridge.Lane real = laneOf(lane);
		for(int x = px - SNAPSHOT_RADIUS; x <= px + SNAPSHOT_RADIUS; x++)
			for(int z = pz - SNAPSHOT_RADIUS; z <= pz + SNAPSHOT_RADIUS; z++)
				if(real.isSolid(x, z))
					solid.add(Godbridge.key(x, z));
		return solid;
	}
	
	private void endBridge()
	{
		if(!bridging)
			return;
		
		bridging = false;
		planner = null;
		moving = 0;
		sneaking = false;
		returning = true;
		slotRestorePending = prevSlot != -1;
	}
	
	/**
	 * Picks this tick's aim with the planner: the stance, nudged a little to
	 * stay on the line, and the pitch that lands the next click inside its
	 * window - see {@link Godbridge}.
	 */
	private void updateAim()
	{
		LocalPlayer player = MC.player;
		Vec3 pos = player.position();
		
		// steering back onto the line (the other way round walking back)
		double lateral = planner.lateral(pos.x, pos.z);
		double correction =
			Godbridge.yawCorrection(moving >= 0 ? lateral : -lateral);
		// (kept when you let go: a yaw change spoils a click that's due)
		double planYaw = stanceYaw + correction;
		
		// the real velocity, not the step: sneaking at an edge cuts the step
		// short but keeps the momentum
		Vec3 velocity = player.getDeltaMovement().scale(1 / Godbridge.FRICTION);
		
		// Stopped and not sliding any more (or walking back along the
		// bridge): nothing to plan. Still sliding after letting go, though,
		// the slide can take you over the edge - keep planning, with no keys.
		boolean sliding = moving == 0 && velocity.horizontalDistance() > 0.005;
		if(moving < 0 || moving == 0 && !sliding)
		{
			planner.reset();
			sneaking = false;
			targetYaw = (float)planYaw;
			targetPitch = (float)Godbridge.NOMINAL_PITCH;
			return;
		}
		
		// where the keys will take you: the one of the eight key directions
		// relative to the camera that's closest to the bridge direction
		double cameraYaw = player.getYRot();
		double moveYaw = cameraYaw
			+ Math.round(Mth.wrapDegrees(bridgeYaw - cameraYaw) / 45) * 45;
		Vec3 move =
			sliding ? Vec3.ZERO : Vec3.directionFromRotation(0, (float)moveYaw);
		double eyeAboveTop = player.getEyeY() - (laneY + 1);
		
		double pitch =
			planner.plan(tick, pos.x, pos.z, velocity.x, velocity.z, move.x,
				move.z, eyeAboveTop, planYaw, player.blockInteractionRange());
		sneaking = planner.shouldSneak();
		double override = planner.yawOverride();
		targetYaw = (float)(Double.isNaN(override) ? planYaw : override);
		targetPitch = (float)pitch;
	}
	
	/**
	 * Clicks what the crosshair is really on, like a player spam-clicking:
	 * only when it's on the side of a block of the lane, the spot behind it
	 * is free and part of the bridge, your feet are still level with the
	 * bridge, and the rotation the server already has hits the same face
	 * (the click goes out before this tick's movement packet).
	 */
	private void tryPlace()
	{
		LocalPlayer player = MC.player;
		BlockHitResult now =
			raycast(new Rotation(player.getYRot(), player.getXRot()));
		BlockHitResult sent =
			raycast(new Rotation(player.yRotLast, player.xRotLast));
		if(now == null || now.getType() != HitResult.Type.BLOCK || sent == null
			|| sent.getType() != HitResult.Type.BLOCK
			|| !now.getBlockPos().equals(sent.getBlockPos())
			|| now.getDirection() != sent.getDirection())
			return;
		
		Direction face = now.getDirection();
		BlockPos clicked = now.getBlockPos();
		if(face.getAxis() == Direction.Axis.Y || clicked.getY() != laneY)
			return;
		
		BlockPos target = clicked.relative(face);
		Vec3 pos = player.position();
		if(!BlockUtils.getState(target).canBeReplaced() || !planner
			.isBridgeCell(target.getX(), target.getZ(), pos.x, pos.z))
			return;
			
		// your feet already below the bridge's top: the block would be
		// inside you
		if(player.getBoundingBox().minY < laneY + 1 - 1e-4
			&& player.getBoundingBox().intersects(new AABB(target)))
			return;
		
		InteractionSimulator.rightClickBlock(now, InteractionHand.MAIN_HAND);
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
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC.player == null)
			return;
		
		double speed = turnSpeed.getValue();
		boolean humanize = humanizeAim.isChecked();
		if(bridging)
		{
			// Turning around (or to look at a block) is a hand on a mouse,
			// easing in and out. The pitch nudges between blocks are small,
			// quick corrections that must land within a tick - the click
			// needs the rotation already sent to be on the face too, and the
			// humanized ease-out took 4+ ticks for a few degrees.
			boolean small = Math.abs(
				Mth.wrapDegrees(MC.player.getYRot() - targetYaw)) < SMALL_TURN
				&& Math.abs(MC.player.getXRot() - targetPitch) < SMALL_TURN;
			cameraAim.turnCamera(bridgeTurn,
				new Rotation(targetYaw, targetPitch), new double[2],
				humanize && !small, speed);
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
	 * way with the camera where it is now - S plus A or D on a straight
	 * bridge, S on a diagonal, like a player godbridging - and those are
	 * also the keys the server sees. Sneaking when the planner says so.
	 *
	 * @return the input to use, or null to leave vanilla's alone
	 */
	public Input modifyInput(Input keys)
	{
		if(!isEnabled() || MC.player == null || !bridging && !returning)
			return null;
		
		Vec3 world;
		if(bridging)
			world =
				Vec3.directionFromRotation(0, (float)bridgeYaw).scale(moving);
		else
			world = toWorld(getPhysicalIntent(), origYaw);
		
		if(world.lengthSqr() < 1e-4)
			return new Input(false, false, false, false, keys.jump(),
				bridging ? sneaking : keys.shift(), keys.sprint());
		
		// the eight key combinations, relative to where the camera faces
		double worldYaw = Math.toDegrees(Math.atan2(-world.x, world.z));
		double rel = Mth.wrapDegrees(worldYaw - MC.player.getYRot());
		int octant = Math.floorMod((int)Math.round(rel / 45), 8);
		boolean forward = octant == 0 || octant == 1 || octant == 7;
		boolean backward = octant == 3 || octant == 4 || octant == 5;
		boolean right = octant == 1 || octant == 2 || octant == 3;
		boolean left = octant == 5 || octant == 6 || octant == 7;
		
		// no sprinting backward while godbridging; sneaking only to stay on
		// an edge until a click works
		boolean shift = bridging ? sneaking : keys.shift();
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
