/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.joml.Quaternionf;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

public record Rotation(float yaw, float pitch)
{
	private static final Minecraft MC = WurstClient.MC;
	
	/**
	 * Turns the camera to this rotation the way the mouse would: the change is
	 * rounded to whole steps of {@link #getMouseStep()}.
	 *
	 * <p>
	 * A real mouse can only turn the camera in multiples of that step, which
	 * anti-cheats check for. It also means a change smaller than half a step
	 * leaves the rotation untouched, so tracking a still target doesn't send
	 * a new rotation packet every tick over floating-point noise.
	 */
	public void applyToClientPlayer()
	{
		Rotation snapped =
			snapToMouseSteps(MC.player.getYRot(), MC.player.getXRot());
		
		if(snapped.yaw != MC.player.getYRot())
			MC.player.setYRot(snapped.yaw);
		if(snapped.pitch != MC.player.getXRot())
			MC.player.setXRot(snapped.pitch);
	}
	
	/**
	 * Returns the rotation a mouse could actually reach when turning from
	 * {@code fromYaw}/{@code fromPitch} toward this one: the change is rounded
	 * to whole {@link #getMouseStep()} steps, and the yaw stays continuous
	 * (no jump by 360 degrees).
	 */
	public Rotation snapToMouseSteps(float fromYaw, float fromPitch)
	{
		double step = getMouseStep();
		double yawChange = Mth.wrapDegrees(yaw - fromYaw);
		double pitchChange = pitch - fromPitch;
		yawChange = Math.round(yawChange / step) * step;
		pitchChange = Math.round(pitchChange / step) * step;
		
		return new Rotation((float)(fromYaw + yawChange),
			Mth.clamp((float)(fromPitch + pitchChange), -90F, 90F));
	}
	
	/**
	 * The smallest turn, in degrees, that one unit of mouse movement makes at
	 * the current sensitivity. Same math as vanilla's
	 * {@code MouseHandler.turnPlayer()} and {@code Entity.turn()}.
	 */
	public static double getMouseStep()
	{
		double s = MC.options.sensitivity().get() * 0.6 + 0.2;
		return s * s * s * 8.0 * 0.15;
	}
	
	public void sendPlayerLookPacket()
	{
		sendPlayerLookPacket(MC.player.onGround(),
			MC.player.horizontalCollision);
	}
	
	public void sendPlayerLookPacket(boolean onGround,
		boolean horizontalCollision)
	{
		MC.player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw,
			pitch, onGround, horizontalCollision));
	}
	
	public double getAngleTo(Rotation other)
	{
		float yaw1 = Mth.wrapDegrees(yaw);
		float yaw2 = Mth.wrapDegrees(other.yaw);
		float diffYaw = Mth.wrapDegrees(yaw1 - yaw2);
		
		float pitch1 = Mth.wrapDegrees(pitch);
		float pitch2 = Mth.wrapDegrees(other.pitch);
		float diffPitch = Mth.wrapDegrees(pitch1 - pitch2);
		
		return Math.sqrt(diffYaw * diffYaw + diffPitch * diffPitch);
	}
	
	public Rotation withYaw(float yaw)
	{
		return new Rotation(yaw, pitch);
	}
	
	public Rotation withPitch(float pitch)
	{
		return new Rotation(yaw, pitch);
	}
	
	public Vec3 toLookVec()
	{
		float radPerDeg = Mth.DEG_TO_RAD;
		float pi = Mth.PI;
		
		float adjustedYaw = -Mth.wrapDegrees(yaw) * radPerDeg - pi;
		float cosYaw = Mth.cos(adjustedYaw);
		float sinYaw = Mth.sin(adjustedYaw);
		
		float adjustedPitch = -Mth.wrapDegrees(pitch) * radPerDeg;
		float nCosPitch = -Mth.cos(adjustedPitch);
		float sinPitch = Mth.sin(adjustedPitch);
		
		return new Vec3(sinYaw * nCosPitch, sinPitch, cosYaw * nCosPitch);
	}
	
	public Quaternionf toQuaternion()
	{
		float radPerDeg = Mth.DEG_TO_RAD;
		float yawRad = -Mth.wrapDegrees(yaw) * radPerDeg;
		float pitchRad = Mth.wrapDegrees(pitch) * radPerDeg;
		
		float sinYaw = Mth.sin(yawRad / 2);
		float cosYaw = Mth.cos(yawRad / 2);
		float sinPitch = Mth.sin(pitchRad / 2);
		float cosPitch = Mth.cos(pitchRad / 2);
		
		float x = sinPitch * cosYaw;
		float y = cosPitch * sinYaw;
		float z = -sinPitch * sinYaw;
		float w = cosPitch * cosYaw;
		
		return new Quaternionf(x, y, z, w);
	}
	
	public static Rotation wrapped(float yaw, float pitch)
	{
		return new Rotation(Mth.wrapDegrees(yaw), Mth.wrapDegrees(pitch));
	}
}
