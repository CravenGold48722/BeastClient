/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.util.Rotation;

/**
 * AimAssist's Sticky turning with the real mouse (the "sensitivity based aim
 * assist" video): a sweep that overshoots a still husk without AimAssist
 * lands on it with it, a still hand turns nothing, a hard flick breaks free,
 * and a husk sliding sideways stays under the crosshair.
 */
public final class AimAssistStickyTest extends SingleplayerTest
{
	/** Facing the husk (north). */
	private static final float HUSK_YAW = 180;
	
	public AimAssistStickyTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing AimAssist's Sticky turning with the mouse");
		runCommand("gamerule spawn_mobs false");
		runCommand("kill @e[type=!player]");
		runWurstCommand("setmode AimAssist Turning Sticky");
		// only the turning: no hits, no combo moving us around
		runWurstCommand("setcheckbox AimAssist Auto_attack off");
		runWurstCommand("setcheckbox AimAssist Dodging off");
		
		try
		{
			startSpot();
			runCommand("summon husk 0.5 -60 -24 {NoAI:1b,Silent:1b,"
				+ "Invulnerable:1b,PersistenceRequired:1b}");
			context.waitTicks(10);
			
			// 40 degrees of hand from 30 beside the husk: 10 past it
			float plain = sweep(40, 16);
			boolean plainOn = isCrosshairOnHusk();
			
			startSpot();
			runWurstCommand("t AimAssist on");
			context.waitTicks(3);
			float sticky = sweep(40, 16);
			boolean stickyOn = isCrosshairOnHusk();
			logger.info(
				"Sweep 30 -> 10 past: without AimAssist {} deg off (on it:"
					+ " {}), with Sticky {} deg off (on it: {})",
				plain, plainOn, sticky, stickyOn);
			if(plainOn)
				throw new RuntimeException(
					"The sweep without AimAssist should miss the husk");
			if(!stickyOn)
				throw new RuntimeException("Sticky didn't stop the crosshair"
					+ " on the husk (" + sticky + " deg off)");
			
			// still hand, still husk: nothing turns
			float yawBefore = yaw();
			float pitchBefore = pitch();
			context.waitTicks(40);
			if(yaw() != yawBefore || pitch() != pitchBefore)
				throw new RuntimeException("Sticky turned the camera with the"
					+ " mouse and the husk both still");
			
			// a husk sliding sideways, 0.08 blocks a tick: stays on it
			int off = 0;
			StringBuilder trace = new StringBuilder();
			for(int i = 0; i < 30; i++)
			{
				runCommand(
					"execute as @e[type=husk] at @s run tp @s ~0.08 ~ ~");
				context.waitTick();
				boolean on = isCrosshairOnHusk();
				if(!on)
					off++;
				trace.append(String.format(java.util.Locale.ROOT, " %s%.1f",
					on ? "" : "!", offsetToHusk()));
			}
			logger.info("Sliding husk, deg off per tick:{}", trace);
			logger.info("Sliding husk: crosshair off it {} of 30 ticks", off);
			if(off > 0)
				throw new RuntimeException("Sticky lost the sliding husk ("
					+ off + " of 30 ticks off it)");
			
			// a hard flick away breaks free
			float flick = sweep(60, 4);
			logger.info("Hard flick: {} deg off", flick);
			if(isCrosshairOnHusk() || Math.abs(flick) < 20)
				throw new RuntimeException("A hard flick should leave the"
					+ " husk, ended " + flick + " deg off");
			
		}finally
		{
			runWurstCommand("t AimAssist off");
			runWurstCommand("setcheckbox AimAssist Auto_attack on");
			runWurstCommand("setcheckbox AimAssist Dodging on");
			runCommand("kill @e[type=husk]");
			context.waitTicks(25); // death animation
			runCommand("kill @e[type=!player]"); // drops
			runCommand("gamerule spawn_mobs true");
			runCommand("tp @s 0 -57 0 0 0");
			clearChat();
			clearParticles();
			context.runOnClient(
				mc -> mc.gui.setOverlayMessage(Component.empty(), false));
			context.waitTicks(7);
		}
	}
	
	/** 30 degrees beside the husk, at its height. */
	private void startSpot()
	{
		runCommand("tp @s 0.5 -60 -20.5 " + (HUSK_YAW - 30) + " 10");
		context.waitTicks(7);
	}
	
	/**
	 * Moves the mouse to the right (toward and past the husk) by the given
	 * degrees of hand over the given ticks, evenly, then waits for it to
	 * settle. Returns how far the crosshair ends up from facing the husk.
	 */
	private float sweep(double degrees, int ticks)
	{
		double counts =
			degrees / context.computeOnClient(mc -> Rotation.getMouseStep());
		long done = 0;
		for(int i = 1; i <= ticks; i++)
		{
			long next = Math.round(counts * i / ticks);
			input.moveCursor(next - done, 0);
			done = next;
			context.waitTick();
		}
		context.waitTicks(3);
		return Mth.wrapDegrees(yaw() - HUSK_YAW);
	}
	
	private boolean isCrosshairOnHusk()
	{
		return context
			.computeOnClient(mc -> mc.crosshairPickEntity instanceof Husk);
	}
	
	/** Yaw from the crosshair to the husk's tick position, degrees. */
	private float offsetToHusk()
	{
		return context.computeOnClient(mc -> {
			Husk husk = mc.level
				.getEntitiesOfClass(Husk.class,
					mc.player.getBoundingBox().inflate(10))
				.stream().findFirst().orElse(null);
			if(husk == null)
				return Float.NaN;
			double dx = husk.getX() - mc.player.getX();
			double dz = husk.getZ() - mc.player.getZ();
			float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
			return Mth.wrapDegrees(mc.player.getYRot() - yaw);
		});
	}
	
	private float yaw()
	{
		return context.computeOnClient(mc -> mc.player.getYRot());
	}
	
	private float pitch()
	{
		return context.computeOnClient(mc -> mc.player.getXRot());
	}
}
