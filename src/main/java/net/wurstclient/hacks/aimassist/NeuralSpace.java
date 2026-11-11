/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.aimassist;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.aimassist.neural.FocusMap;
import net.wurstclient.hacks.aimassist.neural.NeuralObservation;
import net.wurstclient.hacks.aimassist.neural.PvpSim;
import net.wurstclient.hacks.aimassist.neural.TerrainSense;
import net.wurstclient.util.BlockUtils;

/**
 * The neural network's space inputs in game
 * ({@link NeuralObservation#setSpace},
 * what PvpSim's observeSpace gives it), from one player's point of view: the
 * nearest cobweb, a wind charge in the air that isn't theirs, where the
 * other one's pearl will land, the nearest third player and the height
 * above the ground.
 */
public final class NeuralSpace
{
	private static final Minecraft MC = WurstClient.MC;
	/** How far webs are looked for on the focus map (a block scan). */
	
	// totem pops seen lately: x, z, tick (a ring)
	private static final double[] popX = new double[16], popZ = new double[16];
	private static final int[] popTick = new int[16];
	private static int pops;
	private static final FocusMap focusMap = new FocusMap();
	
	/**
	 * The real blocks as TerrainSense's ground: the highest collision box
	 * top at or below the feet + 2.25 in that column, else 9 below the feet.
	 */
	public static final TerrainSense.Heights WORLD = (x, z, feetY) -> {
		int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
		double limit = feetY + 2.25;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int y = (int)Math.floor(limit); y >= (int)Math.floor(feetY)
			- 8; y--)
		{
			pos.set(bx, y, bz);
			VoxelShape shape =
				BlockUtils.getState(pos).getCollisionShape(MC.level, pos);
			if(shape.isEmpty())
				continue;
			double top = y + shape.max(Direction.Axis.Y);
			if(top <= limit + 1e-6)
				return top;
		}
		return feetY - 9;
	};
	
	/** A totem popped there (entity event 35), on this tick. */
	public static void notePop(double x, double z, int tick)
	{
		int i = pops++ % popX.length;
		popX[i] = x;
		popZ[i] = z;
		popTick[i] = tick;
	}
	
	public static void fill(NeuralObservation o, Entity self, Entity other,
		int tick)
	{
		double ax = other.getX() - self.getX(), az = other.getZ() - self.getZ();
		double len = Math.sqrt(ax * ax + az * az);
		if(len < 1e-4)
		{
			ax = 1;
			az = 0;
		}else
		{
			ax /= len;
			az /= len;
		}
		
		// cobwebs: from a block below the feet to the head
		double webDx = Double.NaN, webDz = Double.NaN;
		double best =
			NeuralObservation.SPACE_RANGE * NeuralObservation.SPACE_RANGE;
		BlockPos feet = self.blockPosition();
		int r = (int)Math.ceil(NeuralObservation.SPACE_RANGE);
		for(int dx = -r; dx <= r; dx++)
			for(int dz = -r; dz <= r; dz++)
				for(int dy = -1; dy <= 1; dy++)
				{
					BlockPos pos = feet.offset(dx, dy, dz);
					if(!BlockUtils.getState(pos).is(Blocks.COBWEB))
						continue;
					double wx = pos.getX() + 0.5 - self.getX(),
						wz = pos.getZ() + 0.5 - self.getZ();
					double d2 = wx * wx + wz * wz;
					if(d2 < best)
					{
						best = d2;
						webDx = wx;
						webDz = wz;
					}
				}
			
		// wind charges that aren't theirs, the other one's pearl
		double windDx = Double.NaN, windDz = Double.NaN;
		double pearlDx = Double.NaN, pearlDz = Double.NaN;
		double bestWind = Double.MAX_VALUE;
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof AbstractWindCharge wind && wind.getOwner() != self)
			{
				double wx = e.getX() - self.getX(), wz = e.getZ() - self.getZ();
				double d2 = wx * wx + wz * wz;
				if(d2 < bestWind)
				{
					bestWind = d2;
					windDx = wx;
					windDz = wz;
				}
			}else if(e instanceof ThrownEnderpearl pearl
				&& pearl.getOwner() == other)
			{
				Vec3 v = e.getDeltaMovement();
				double[] land = PvpSim.pearlLanding(e.getX(), e.getY(),
					e.getZ(), v.x, v.y, v.z, other.getY());
				pearlDx = land[0] - self.getX();
				pearlDz = land[1] - self.getZ();
			}
		
		// the nearest third player
		double otherDx = Double.NaN, otherDz = Double.NaN;
		double bestOther =
			NeuralObservation.OTHER_RANGE * NeuralObservation.OTHER_RANGE;
		for(Player p : MC.level.players())
		{
			if(p == self || p == other || !p.isAlive() || p.isSpectator())
				continue;
			double px = p.getX() - self.getX(), pz = p.getZ() - self.getZ();
			double d2 = px * px + pz * pz;
			if(d2 < bestOther)
			{
				bestOther = d2;
				otherDx = px;
				otherDz = pz;
			}
		}
		
		o.setSpace(ax, az, webDx, webDz, windDx, windDz, pearlDx, pearlDz,
			otherDx, otherDz, heightAboveGround(self));
		// the terrain and the focus map: every 3rd-4th tick, like PvpSim
		if(!o.isSpaceDue(tick))
			return;
		fillFocus(o, self, ax, az, tick);
		TerrainSense.sense(WORLD, self.getX(), self.getY(), self.getZ(), ax, az,
			o.terrain, 0);
		if(o.isFarDue())
			TerrainSense.senseFar(WORLD, self.getX(), self.getY(), self.getZ(),
				ax, az, o.terrainFar, 0);
	}
	
	/**
	 * Everything within the render distance on PvpSim's focus map:
	 * players, projectiles, totem pops, webs (those near you).
	 */
	private static void fillFocus(NeuralObservation o, Entity self, double ax,
		double az, int tick)
	{
		FocusMap m = focusMap;
		double range =
			Math.min(MC.options.getEffectiveRenderDistance() * 16, 128);
		m.clear(self.getX(), self.getZ(), ax, az, range);
		for(Player p : MC.level.players())
			if(p != self && p.isAlive() && !p.isSpectator())
				m.add(p.getX(), p.getZ(), FocusMap.PLAYER,
					p.getHealth() / Math.max(1, p.getMaxHealth()));
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof ThrownEnderpearl || e instanceof AbstractWindCharge
				|| e instanceof AbstractThrownPotion
				|| e instanceof AbstractArrow arrow && !arrow.onGround()
					&& ((Projectile)e).getDeltaMovement().lengthSqr() > 0.01)
				m.add(e.getX(), e.getZ(), FocusMap.PROJECTILE, 0);
		for(int i = 0; i < Math.min(pops, popX.length); i++)
			if(tick - popTick[i] < FocusMap.TOTEM_TICKS)
				m.add(popX[i], popZ[i], FocusMap.TOTEM, 0);
		BlockPos feet = self.blockPosition();
		for(int dx = -FocusMap.WEB_SCAN; dx <= FocusMap.WEB_SCAN; dx++)
			for(int dz = -FocusMap.WEB_SCAN; dz <= FocusMap.WEB_SCAN; dz++)
				for(int dy = -1; dy <= 1; dy++)
				{
					BlockPos pos = feet.offset(dx, dy, dz);
					if(BlockUtils.getState(pos).is(Blocks.COBWEB))
						m.add(pos.getX() + 0.5, pos.getZ() + 0.5, FocusMap.WEB,
							0);
				}
		m.write(o.focus, 0);
	}
	
	/** Down to the first block with a collision box, at most 8 blocks. */
	private static double heightAboveGround(Entity self)
	{
		if(self.onGround())
			return 0;
		BlockPos pos = self.blockPosition();
		for(int i = 0; i <= 8; i++, pos = pos.below())
		{
			BlockState state = BlockUtils.getState(pos);
			VoxelShape shape = state.getCollisionShape(MC.level, pos);
			if(shape.isEmpty())
				continue;
			double top = pos.getY() + shape.max(Direction.Axis.Y);
			if(top <= self.getY() + 1e-6)
				return self.getY() - top;
		}
		return 8;
	}
}
