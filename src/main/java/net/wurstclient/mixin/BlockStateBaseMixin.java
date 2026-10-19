/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.IsNormalCubeListener.IsNormalCubeEvent;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.HandNoClipHack;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.hacks.AutoFlyHack;

@Mixin(BlockStateBase.class)
public abstract class BlockStateBaseMixin extends StateHolder<Block, BlockState>
{
	private BlockStateBaseMixin(WurstClient wurst, Block owner,
		Property<?>[] properties, Comparable<?>[] values)
	{
		super(owner, properties, values);
	}
	
	@Inject(
		method = "isCollisionShapeFullBlock(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("TAIL"),
		cancellable = true)
	private void onIsFullCube(BlockGetter world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir)
	{
		IsNormalCubeEvent event = new IsNormalCubeEvent();
		EventManager.fire(event);
		
		cir.setReturnValue(cir.getReturnValue() && !event.isCancelled());
	}
	
	@Inject(
		method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetOutlineShape(BlockGetter view, BlockPos pos,
		CollisionContext context, CallbackInfoReturnable<VoxelShape> cir)
	{
		if(context == CollisionContext.empty())
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return;
		
		// BedBreakAura (ported from the CevAPI fork) clicks through blocks
		if(hax.bedBreakAuraHack.shouldClearBlock(pos))
		{
			cir.setReturnValue(Shapes.empty());
			return;
		}
		
		HandNoClipHack handNoClipHack = hax.handNoClipHack;
		if(!handNoClipHack.isEnabled() || handNoClipHack.isBlockInList(pos))
			return;
		
		cir.setReturnValue(Shapes.empty());
	}
	
	@Inject(
		method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetCollisionShape(BlockGetter world, BlockPos pos,
		CollisionContext context, CallbackInfoReturnable<VoxelShape> cir)
	{
		// AutoFly (ported from the CevAPI fork) can make lava and fire solid,
		// and the air above them, so a path never dips into them. Lava you're
		// under or inside stays passable so you can get out upward.
		HackList autoFlyHax = WurstClient.INSTANCE.getHax();
		if(autoFlyHax != null && autoFlyHax.autoFlyHack.isEnabled())
		{
			AutoFlyHack autoFly = autoFlyHax.autoFlyHack;
			BlockState self = (BlockState)(Object)this;
			boolean solidLava = autoFly.shouldMakeLavaSolid();
			boolean solidFire = autoFly.shouldMakeFireSolid();
			boolean lava = solidLava && getFluidState().is(FluidTags.LAVA);
			boolean fire = solidFire
				&& (self.is(Blocks.FIRE) || self.is(Blocks.SOUL_FIRE));
			boolean clearance = false;
			if(!lava && !fire && (solidLava || solidFire))
			{
				BlockState below = world.getBlockState(pos.below());
				clearance =
					solidLava && below.getFluidState().is(FluidTags.LAVA)
						|| solidFire && (below.is(Blocks.FIRE)
							|| below.is(Blocks.SOUL_FIRE));
			}
			
			boolean lavaCollision = lava || clearance && world
				.getBlockState(pos.below()).getFluidState().is(FluidTags.LAVA);
			if(lavaCollision && WurstClient.MC.player != null
				&& WurstClient.MC.player.getBoundingBox().maxY <= pos.getY()
					+ 1.0E-3)
			{
				cir.setReturnValue(Shapes.empty());
				return;
			}
			
			if(lava || fire || clearance)
			{
				cir.setReturnValue(Shapes.block());
				return;
			}
		}
		
		// AntiVoid's client-side floor (ported from the CevAPI fork)
		HackList antiVoidHax = WurstClient.INSTANCE.getHax();
		BlockState state = (BlockState)(Object)this;
		if(antiVoidHax != null && (antiVoidHax.antiVoidHack
			.shouldMakeFalseFloor(pos, state)
			|| antiVoidHax.antiVoidHack.shouldMakeLavaFloor(world, pos, state)))
		{
			cir.setReturnValue(Shapes.block());
			return;
		}
		
		if(getFluidState().isEmpty())
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.jesusHack.shouldBeSolid())
			return;
		
		cir.setReturnValue(Shapes.block());
		cir.cancel();
	}
	
	@Shadow
	public abstract FluidState getFluidState();
}
