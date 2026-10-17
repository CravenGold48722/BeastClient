/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Comparator;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.RotationFaker;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.SwingHandSetting.SwingHand;

public enum BlockBreaker
{
	;
	
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Minecraft MC = WurstClient.MC;
	
	public static boolean breakOneBlock(BlockPos pos)
	{
		BlockBreakingParams params = getBlockBreakingParams(pos);
		if(params == null)
			return false;
		
		return breakOneBlock(params);
	}
	
	public static boolean breakOneBlock(BlockBreakingParams params)
	{
		// face block (turns the camera, no silent rotation)
		WURST.getRotationFaker().faceVectorClient(params.hitVec);
		if(isTurnPending(params.pos))
			return true;
		
		// damage block
		if(!MC.gameMode.continueDestroyBlock(params.pos, params.side))
			return false;
		
		// swing arm
		SwingHand.CLIENT.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	/**
	 * Whether the look just turned onto this block and the server doesn't
	 * have it yet: true if the rotation about to be sent (client or faked)
	 * puts the crosshair on it but the last one sent didn't. A break packet
	 * goes out before this tick's movement packet, so breaking right away is
	 * a break "looking at a different block" to the server (Intave's
	 * interactionraytrace) - wait a tick instead, like a player whose
	 * crosshair has to get there first. False when the look isn't on the
	 * block at all (no rotation, out of sight): nothing to wait for.
	 */
	public static boolean isTurnPending(BlockPos pos)
	{
		LocalPlayer player = MC.player;
		RotationFaker faker = WURST.getRotationFaker();
		float yaw = faker.getServerYaw();
		float pitch = faker.getServerPitch();
		if(!isLookingAt(pos, yaw, pitch))
			return false;
			
		// The first tick on a new block only turns, even if the old look
		// reaches it too: right after breaking the block in front, the old
		// look runs through where that block was - which the server can still
		// have there (Intave: "wheat block, but looking at a different wheat
		// block"). (A "turned more than 3 degrees" rule did that too, but kept
		// .excavate waiting forever while walking up close to a block.)
		int now = player.tickCount;
		boolean newBlock = !pos.equals(turnTarget) || now - turnTargetTick > 1;
		turnTarget = pos.immutable();
		turnTargetTick = now;
		if(newBlock)
			return true;
		
		return !isLookingAt(pos, player.yRotLast, player.xRotLast);
	}
	
	/** The block isTurnPending() was last asked about, and when. */
	private static BlockPos turnTarget;
	private static int turnTargetTick;
	
	/** Whether a crosshair with this rotation would pick this block. */
	public static boolean isLookingAt(BlockPos pos, float yaw, float pitch)
	{
		LocalPlayer player = MC.player;
		Vec3 eyes = player.getEyePosition();
		Vec3 end = eyes.add(Vec3.directionFromRotation(pitch, yaw)
			.scale(player.blockInteractionRange()));
		BlockHitResult hit = MC.level.clip(new ClipContext(eyes, end,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().equals(pos);
	}
	
	/**
	 * Whether a crosshair aimed from the eyes at this point on the block's
	 * face would pick the block - the first outline the ray meets, the way
	 * the crosshair sees it. (Collision shapes alone said a crop's bottom
	 * face, flush on the farmland, was in sight.)
	 */
	private static boolean canSee(Vec3 eyes, BlockPos pos, Vec3 hitVec)
	{
		// a little past the face, into the block
		Vec3 to = eyes.add(hitVec.subtract(eyes).scale(1.02));
		BlockHitResult hit = MC.level.clip(new ClipContext(eyes, to,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		return hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().equals(pos);
	}
	
	/**
	 * Returns everything you need to break a block at the given position, such
	 * as which side to face, the exact hit vector to face that side, the
	 * squared distance to that hit vector, and whether or not there is line of
	 * sight to that hit vector.
	 */
	public static BlockBreakingParams getBlockBreakingParams(BlockPos pos)
	{
		return getBlockBreakingParams(RotationUtils.getEyesPos(), pos);
	}
	
	/**
	 * Returns everything you need to break a block at the given position, such
	 * as which side to face, the exact hit vector to face that side, the
	 * squared distance to that hit vector, and whether or not there is line of
	 * sight to that hit vector.
	 */
	public static BlockBreakingParams getBlockBreakingParams(Vec3 eyes,
		BlockPos pos)
	{
		Direction[] sides = Direction.values();
		
		BlockState state = BlockUtils.getState(pos);
		VoxelShape shape = state.getShape(MC.level, pos);
		if(shape.isEmpty())
			return null;
		
		AABB box = shape.bounds();
		Vec3 halfSize = new Vec3(box.maxX - box.minX, box.maxY - box.minY,
			box.maxZ - box.minZ).scale(0.5);
		Vec3 center = Vec3.atLowerCornerOf(pos).add(box.getCenter());
		
		Vec3[] hitVecs = new Vec3[sides.length];
		for(int i = 0; i < sides.length; i++)
		{
			Vec3i dirVec = sides[i].getUnitVec3i();
			Vec3 relHitVec = new Vec3(halfSize.x * dirVec.getX(),
				halfSize.y * dirVec.getY(), halfSize.z * dirVec.getZ());
			hitVecs[i] = center.add(relHitVec);
		}
		
		double distanceSqToCenter = eyes.distanceToSqr(center);
		double[] distancesSq = new double[sides.length];
		boolean[] linesOfSight = new boolean[sides.length];
		
		for(int i = 0; i < sides.length; i++)
		{
			distancesSq[i] = eyes.distanceToSqr(hitVecs[i]);
			
			// no need to raytrace the rear sides,
			// they can't possibly have line of sight
			if(distancesSq[i] >= distanceSqToCenter)
				continue;
			
			linesOfSight[i] = canSee(eyes, pos, hitVecs[i]);
		}
		
		Direction side = sides[0];
		for(int i = 1; i < sides.length; i++)
		{
			int bestSide = side.ordinal();
			
			// prefer sides with LOS
			if(!linesOfSight[bestSide] && linesOfSight[i])
			{
				side = sides[i];
				continue;
			}
			
			if(linesOfSight[bestSide] && !linesOfSight[i])
				continue;
			
			// then pick the closest side
			if(distancesSq[i] < distancesSq[bestSide])
				side = sides[i];
		}
		
		return new BlockBreakingParams(pos, side, hitVecs[side.ordinal()],
			distancesSq[side.ordinal()], linesOfSight[side.ordinal()]);
	}
	
	public static record BlockBreakingParams(BlockPos pos, Direction side,
		Vec3 hitVec, double distanceSq, boolean lineOfSight)
	{
		public BlockHitResult toHitResult()
		{
			return new BlockHitResult(hitVec, side, pos, false);
		}
	}
	
	/**
	 * Returns a comparator that compares BlockBreakingParams by line of sight
	 * first, then by distance.
	 */
	public static Comparator<BlockBreakingParams> comparingParams()
	{
		return Comparator.comparing(BlockBreakingParams::lineOfSight).reversed()
			.thenComparing(params -> params.distanceSq);
	}
	
	/**
	 * Returns a comparator that compares BlockBreakingParams by line of sight
	 * first, then by distance.
	 */
	public static <T> Comparator<T> comparingParams(
		Function<T, BlockBreakingParams> keyExtractor)
	{
		return Comparator.<T, BlockBreakingParams> comparing(keyExtractor,
			comparingParams());
	}
	
	public static void breakBlocksWithPacketSpam(Iterable<BlockPos> blocks)
	{
		Vec3 eyesPos = RotationUtils.getEyesPos();
		ClientPacketListener netHandler = MC.player.connection;
		
		for(BlockPos pos : blocks)
		{
			Vec3 posVec = Vec3.atCenterOf(pos);
			double distanceSqPosVec = eyesPos.distanceToSqr(posVec);
			
			for(Direction side : Direction.values())
			{
				Vec3 hitVec = posVec
					.add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
				
				// check if side is facing towards player
				if(eyesPos.distanceToSqr(hitVec) >= distanceSqPosVec)
					continue;
				
				// break block
				netHandler.send(new ServerboundPlayerActionPacket(
					Action.START_DESTROY_BLOCK, pos, side));
				netHandler.send(new ServerboundPlayerActionPacket(
					Action.STOP_DESTROY_BLOCK, pos, side));
				
				break;
			}
		}
	}
}
