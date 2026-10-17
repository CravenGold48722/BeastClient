/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.zombie.Husk;
import com.mojang.blaze3d.vertex.PoseStack;

import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.MouseUpdateListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.gametest.UserSettings;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.GameClock;
import net.wurstclient.util.Rotation;

/**
 * AimAssist turning like the reference video (user, 2026-10-16), with the
 * user's own settings and the real mouse: it turns onto a still husk and
 * lands without swinging past, holds still there, a slow pull away with the
 * mouse is resisted, a hard flick breaks free and the aim brings the camera
 * back once the mouse stops, and a husk sliding sideways stays under the
 * crosshair.
 */
public final class AimAssistTurningTest extends SingleplayerTest
{
	/** Facing the husk (north). */
	private static final float HUSK_YAW = 180;
	
	public AimAssistTurningTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing AimAssist's turning with the mouse");
		runCommand("gamerule spawn_mobs false");
		runCommand("kill @e[type=!player]");
		// the user's own settings, except: only the turning here - no hits
		// knocking the husk around
		Map<Setting, JsonElement> before =
			context.computeOnClient(mc -> UserSettings.applyAimAssist());
		runWurstCommand("setcheckbox AimAssist Auto_attack off");
		context.runOnClient(mc -> {
			EventManager events = WurstClient.INSTANCE.getEventManager();
			// before AimAssist's own mouse handling, like a real mouse
			events.addFirst(MouseUpdateListener.class, hand);
			events.add(RenderListener.class, hand);
		});
		
		try
		{
			// 45 degrees beside the husk, at its height
			runCommand("tp @s 0.5 -60 -20.5 " + (HUSK_YAW - 45) + " 10");
			context.waitTicks(7);
			runCommand("summon husk 0.5 -60 -24 {NoAI:1b,Silent:1b,"
				+ "Invulnerable:1b,PersistenceRequired:1b}");
			context.waitTicks(10);
			
			// the turn onto it, tick by tick
			runWurstCommand("t AimAssist on");
			StringBuilder trace = new StringBuilder();
			int landed = -1;
			float past = 0;
			for(int i = 0; i < 30; i++)
			{
				context.waitTick();
				float off = offsetToHusk();
				trace.append(String.format(Locale.ROOT, " %.1f", off));
				// it turns from -45 up to 0: past it is positive
				past = Math.max(past, off);
				if(landed < 0 && isCrosshairOnHusk())
					landed = i + 1;
			}
			logger.info("Turn from 45 deg, deg off per tick:{}", trace);
			if(landed < 0 || landed > 12)
				throw new RuntimeException("The aim took " + landed
					+ " ticks to get onto the husk from 45 degrees");
			if(past > 1)
				throw new RuntimeException(
					"The aim swung " + past + " deg past the husk");
			
			// still hand, still husk: nothing moves
			float yawBefore = yaw();
			float pitchBefore = pitch();
			context.waitTicks(40);
			if(yaw() != yawBefore || pitch() != pitchBefore)
				throw new RuntimeException("The camera moved with the mouse"
					+ " and the husk both still");
				
			// a slow pull away (100 deg/s of hand for 0.2 s): resisted - it
			// creeps a little, stays on the husk
			hand.move(100, 0.2);
			float pulled = hand.maxOff;
			int offFrames = hand.offFrames;
			logger.info("Slow pull (20 deg of hand): at most {} deg off,"
				+ " off the husk on {} frames", pulled, offFrames);
			if(offFrames > 0)
				throw new RuntimeException("A slow pull took the crosshair"
					+ " off the husk (" + offFrames + " frames)");
			context.waitTicks(20);
			
			// a hard flick (1200 deg/s for 0.1 s, 120 degrees of hand) breaks
			// free...
			hand.move(1200, 0.1);
			float flicked = hand.maxOff;
			boolean freed = hand.offFrames > 0;
			// ...and the aim brings it back once the mouse stops
			int back = -1;
			for(int i = 0; i < 30 && back < 0; i++)
			{
				context.waitTick();
				if(isCrosshairOnHusk())
					back = i + 1;
			}
			logger.info(
				"Hard flick: up to {} deg off (off the husk on {}"
					+ " frames), back on after {} ticks",
				flicked, hand.offFrames, back);
			if(!freed || flicked < 30)
				throw new RuntimeException("A hard flick didn't break free ("
					+ flicked + " deg off at most)");
			if(back < 0 || back > 15)
				throw new RuntimeException("The aim didn't bring the camera"
					+ " back after the flick (" + back + " ticks)");
			context.waitTicks(20);
			
			// a husk sliding sideways, 0.08 blocks a tick: stays on it
			int off = 0;
			StringBuilder slide = new StringBuilder();
			for(int i = 0; i < 30; i++)
			{
				runCommand(
					"execute as @e[type=husk] at @s run tp @s ~0.08 ~ ~");
				context.waitTick();
				boolean on = isCrosshairOnHusk();
				if(!on)
					off++;
				slide.append(String.format(Locale.ROOT, " %s%.1f",
					on ? "" : "!", offsetToHusk()));
			}
			logger.info("Sliding husk, deg off per tick:{}", slide);
			if(off > 0)
				throw new RuntimeException("The aim lost the sliding husk ("
					+ off + " of 30 ticks off it)");
			
		}finally
		{
			context.runOnClient(mc -> {
				EventManager events = WurstClient.INSTANCE.getEventManager();
				events.remove(MouseUpdateListener.class, hand);
				events.remove(RenderListener.class, hand);
			});
			runWurstCommand("t AimAssist off");
			context.runOnClient(mc -> UserSettings.restore(before));
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
	
	private final Hand hand = new Hand();
	
	/**
	 * A hand on the mouse: moves it to the right at a set speed every frame,
	 * like a real mouse (the test's moveCursor arrives once a tick, in
	 * bursts the aim pulls back from between ticks), and watches the
	 * crosshair every frame while it does.
	 */
	private final class Hand implements MouseUpdateListener, RenderListener
	{
		private volatile double degPerSec;
		private long lastFrame;
		private double carry;
		volatile float maxOff;
		volatile int offFrames;
		
		void move(double speed, double seconds)
		{
			context.runOnClient(mc -> {
				maxOff = 0;
				offFrames = 0;
				carry = 0;
				lastFrame = GameClock.nanoTime();
				degPerSec = speed;
			});
			context.waitTicks((int)Math.round(seconds * 20));
			context.runOnClient(mc -> degPerSec = 0);
		}
		
		@Override
		public void onMouseUpdate(MouseUpdateEvent event)
		{
			long now = GameClock.nanoTime();
			double dt = (now - lastFrame) / 1e9;
			lastFrame = now;
			if(degPerSec == 0)
				return;
			
			double counts = degPerSec * dt / Rotation.getMouseStep() + carry;
			long whole = Math.round(counts);
			carry = counts - whole;
			event.setDeltaX(event.getDeltaX() + whole);
		}
		
		@Override
		public void onRender(PoseStack matrices, float partialTicks)
		{
			if(degPerSec == 0)
				return;
			maxOff = Math.max(maxOff, Math.abs(offsetToHuskNow()));
			if(!(WurstClient.MC.crosshairPickEntity instanceof Husk))
				offFrames++;
		}
	}
	
	private boolean isCrosshairOnHusk()
	{
		return context
			.computeOnClient(mc -> mc.crosshairPickEntity instanceof Husk);
	}
	
	/** Yaw from the crosshair to the husk, degrees. */
	private float offsetToHusk()
	{
		return context.computeOnClient(mc -> offsetToHuskNow());
	}
	
	/** offsetToHusk() on the client thread. */
	private static float offsetToHuskNow()
	{
		{
			var mc = WurstClient.MC;
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
		}
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
