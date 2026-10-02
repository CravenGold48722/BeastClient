/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

/**
 * Drives {@link HumanAim} for a hack: turns the camera toward something every
 * frame (or works out a silent rotation once per tick), starting a fresh,
 * human-like turn whenever the thing being aimed at changes.
 *
 * <p>
 * Call {@link #aimAtEntity} / {@link #turnCamera} from {@code onRender}, so
 * the camera moves every frame like it does with a mouse. The player's own
 * mouse movement in between is kept: it shifts the aim instead of being
 * overridden.
 */
public final class CameraAim
{
	private static final Minecraft MC = WurstClient.MC;
	
	private final HumanAim aim = new HumanAim();
	
	/** What the current turn is aimed at; a change starts a new turn. */
	private Object turnKey;
	
	/** Camera rotation right after this class last set it. */
	private float lastSetYaw;
	private float lastSetPitch;
	private boolean lastSetValid;
	
	/** Stops aiming. The next call starts fresh from the camera. */
	public void reset()
	{
		aim.invalidate();
		turnKey = null;
		lastSetValid = false;
	}
	
	public float getYaw()
	{
		return aim.getYaw();
	}
	
	public float getPitch()
	{
		return aim.getPitch();
	}
	
	/**
	 * Turns the camera toward a point on an entity for this frame. The point
	 * is given at the entity's tick position and moved to where the entity is
	 * drawn this frame.
	 */
	public void aimAtEntity(Entity target, Vec3 tickAimPoint,
		float partialTicks, boolean humanize, double maxSpeed)
	{
		Vec3 offset = EntityUtils.getLerpedPos(target, partialTicks)
			.subtract(target.position());
		Vec3 point = tickAimPoint.add(offset);
		Vec3 eyes = getFrameEyes(partialTicks);
		
		Rotation needed = RotationUtils.getNeededRotations(eyes, point);
		double[] track = HumanAim.trackingRate(eyes, point,
			HumanAim.relativeVelocity(target, MC.player));
		
		turnCamera(target, needed, track, humanize, maxSpeed);
	}
	
	/**
	 * Turns the camera toward an explicit rotation for this frame (e.g. a
	 * bow's firing solution), moving at {@code track} degrees per second on
	 * top - how fast that rotation itself is moving.
	 */
	public void turnCamera(Object key, Rotation needed, double[] track,
		boolean humanize, double maxSpeed)
	{
		long now = System.nanoTime();
		prepare(key, now, true);
		step(needed, track, now, humanize, maxSpeed);
		
		new Rotation(aim.getYaw(), aim.getPitch()).applyToClientPlayer();
		lastSetYaw = MC.player.getYRot();
		lastSetPitch = MC.player.getXRot();
		lastSetValid = true;
	}
	
	/**
	 * Works out the next silent (server-side) rotation toward {@code needed}
	 * without touching the camera. Call once per tick and fake the result
	 * with the RotationFaker.
	 */
	public Rotation turnSilently(Object key, Rotation needed, double[] track,
		boolean humanize, double maxSpeed)
	{
		long now = System.nanoTime();
		prepare(key, now, false);
		step(needed, track, now, humanize, maxSpeed);
		lastSetValid = false;
		return new Rotation(aim.getYaw(), aim.getPitch());
	}
	
	/**
	 * Whether the camera's crosshair (or the given rotation) is on the box,
	 * within {@code reach}.
	 */
	public static boolean isLookingAt(float yaw, float pitch, AABB box,
		double reach)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		if(box.contains(eyes))
			return true;
		
		Vec3 end = eyes.add(new Rotation(yaw, pitch).toLookVec().scale(reach));
		return box.clip(eyes, end).isPresent();
	}
	
	/** Where your eyes are drawn in this frame. */
	public static Vec3 getFrameEyes(float partialTicks)
	{
		return EntityUtils.getLerpedPos(MC.player, partialTicks).add(0,
			MC.player.getEyeHeight(MC.player.getPose()), 0);
	}
	
	private void prepare(Object key, long now, boolean camera)
	{
		if(!aim.isValid())
		{
			aim.seed(MC.player.getYRot(), MC.player.getXRot(), now);
			aim.startTurn(now);
			turnKey = key;
			return;
		}
		
		if(key != turnKey)
		{
			turnKey = key;
			aim.startTurn(now);
		}
		
		// The player moved the mouse since we last set the camera: let that
		// move the aim too, instead of snapping the camera back over it.
		if(camera && lastSetValid)
			aim.shift(Mth.wrapDegrees(MC.player.getYRot() - lastSetYaw),
				MC.player.getXRot() - lastSetPitch);
	}
	
	private void step(Rotation needed, double[] track, long now,
		boolean humanize, double maxSpeed)
	{
		float dt = aim.takeDt(now);
		aim.updateTracking(track[0], track[1], dt);
		
		if(humanize)
			aim.humanStep(needed.yaw(), needed.pitch(), dt, now, maxSpeed);
		else
			aim.linearStep(needed.yaw(), needed.pitch(), dt, maxSpeed);
	}
}
