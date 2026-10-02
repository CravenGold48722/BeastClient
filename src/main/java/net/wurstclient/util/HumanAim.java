/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Random;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The smooth, human-like aim every aiming hack shares: AimAssist, MaceAssist,
 * BowAimbot, the Killauras, FightBot and Protect.
 *
 * <p>
 * Holds the rotation being aimed with (separately from the camera, so it also
 * works for silent aim) and moves it toward a wanted rotation one step at a
 * time:
 * <ul>
 * <li>a short reaction delay before a new turn starts,</li>
 * <li>speeds up, then slows down as it closes in (ease-in, ease-out), never
 * faster than the given top speed,</li>
 * <li>a speed that drifts a little over time instead of jumping around,</li>
 * <li>a slightly curved path that straightens out near the target,</li>
 * <li>horizontal first - the vertical part lags while the horizontal gap is
 * still big,</li>
 * <li>a slow hand tremor while still far off,</li>
 * <li>and, on top of all that, moving as fast as the target moves across the
 * view (tracking), so the aim stays on a moving target instead of trailing
 * behind it.</li>
 * </ul>
 * Every step is scaled by the real elapsed time, so stepping every frame or
 * every tick gives the same turn - every frame just looks smoother.
 */
public final class HumanAim
{
	/**
	 * How quickly the ease-out closes the remaining angle, per second. Covers
	 * about 42% of what's left every tick: -ln(1 - 0.425) / 0.05s.
	 */
	public static final double EASE_RATE = 11;
	
	/**
	 * How quickly the hand gets up to speed and slows down again, in seconds.
	 * Makes the turn speed up at the start instead of jumping straight to full
	 * speed.
	 *
	 * <p>
	 * 1 / (4 * EASE_RATE): exactly critically damped together with the
	 * ease-out, i.e. the quickest it can be without carrying past the target.
	 * (It was 0.04, which overshot big turns by a few degrees and swung back.)
	 */
	public static final double HAND_RESPONSE = 1 / (4 * EASE_RATE);
	
	/**
	 * The most the tracking can add, in degrees per second. Only reached by a
	 * target zipping right past your face; keeps a measurement glitch from
	 * flinging the aim.
	 */
	public static final double MAX_TRACKING = 1440;
	
	/** Longest step, so a freeze doesn't turn into one huge jump. */
	private static final double MAX_DT = 0.15;
	
	private final Random random;
	
	private float yaw;
	private float pitch;
	private boolean valid;
	private long lastStepNs;
	
	private long reactionEndNs;
	private float curve;
	
	private double velYaw;
	private double velPitch;
	
	private double speedFactor = 1;
	private double speedFactorTarget = 1;
	private long nextSpeedChangeNs;
	
	private double tremorYaw;
	private double tremorPitch;
	private double tremorTargetYaw;
	private double tremorTargetPitch;
	private long nextTremorChangeNs;
	
	private double trackYaw;
	private double trackPitch;
	
	public HumanAim()
	{
		this(new Random());
	}
	
	public HumanAim(Random random)
	{
		this.random = random;
	}
	
	/** Whether there is an aim rotation to continue from. */
	public boolean isValid()
	{
		return valid;
	}
	
	/** Forgets the aim rotation; the next use has to {@link #seed} again. */
	public void invalidate()
	{
		valid = false;
	}
	
	public float getYaw()
	{
		return yaw;
	}
	
	public float getPitch()
	{
		return pitch;
	}
	
	/**
	 * Starts aiming from the given rotation (usually wherever the camera is
	 * pointing right now).
	 */
	public void seed(float yaw, float pitch, long now)
	{
		this.yaw = yaw;
		this.pitch = pitch;
		valid = true;
		// one frame back, so the first step actually turns
		lastStepNs = now - 16_000_000L;
	}
	
	/**
	 * Begins a new turn, the way a hand starts moving toward something new: a
	 * fresh 50-150ms reaction delay, a new path bend, the hand at rest and
	 * the tracking measured from scratch.
	 */
	public void startTurn(long now)
	{
		reactionEndNs = now + (50 + random.nextInt(101)) * 1_000_000L;
		curve = (float)(random.nextGaussian() * 0.08);
		velYaw = 0;
		velPitch = 0;
		tremorYaw = 0;
		tremorPitch = 0;
		trackYaw = 0;
		trackPitch = 0;
	}
	
	/**
	 * Moves the aim rotation by the given amount without starting a new turn.
	 * Used to follow the player's own mouse movement.
	 */
	public void shift(float dYaw, float dPitch)
	{
		yaw = Mth.wrapDegrees(yaw + dYaw);
		pitch = Mth.clamp(pitch + dPitch, -90F, 90F);
	}
	
	/** Seconds since the last step (capped), and marks this as the last. */
	public float takeDt(long now)
	{
		double dt = (now - lastStepNs) / 1_000_000_000.0;
		lastStepNs = now;
		return (float)Mth.clamp(dt, 0, MAX_DT);
	}
	
	/** Jumps straight to the given rotation. */
	public void snap(float yaw, float pitch)
	{
		this.yaw = Mth.wrapDegrees(yaw);
		this.pitch = Mth.clamp(pitch, -90F, 90F);
		velYaw = 0;
		velPitch = 0;
	}
	
	/** Angle between the aim rotation and the given one, in degrees. */
	public double angleTo(float needYaw, float needPitch)
	{
		return Math.hypot(Mth.wrapDegrees(needYaw - yaw), needPitch - pitch);
	}
	
	public boolean isReacting(long now)
	{
		return now < reactionEndNs;
	}
	
	/**
	 * Feeds in how fast the wanted rotation is moving across the view right
	 * now (degrees per second), e.g. from {@link #trackingRate}. Smoothed a
	 * little, since movement is only measured once per tick.
	 */
	public void updateTracking(double rawYaw, double rawPitch, double dt)
	{
		rawYaw = Mth.clamp(rawYaw, -MAX_TRACKING, MAX_TRACKING);
		rawPitch = Mth.clamp(rawPitch, -MAX_TRACKING, MAX_TRACKING);
		
		double e = ease(dt, 0.05);
		trackYaw += (rawYaw - trackYaw) * e;
		trackPitch += (rawPitch - trackPitch) * e;
	}
	
	/**
	 * A constant-speed turn in a straight line (the non-humanized smooth aim),
	 * plus tracking.
	 */
	public void linearStep(float needYaw, float needPitch, double dt,
		double maxSpeed)
	{
		float maxChange = (float)(maxSpeed * dt);
		float yawDiff = Mth.wrapDegrees(needYaw - yaw);
		float pitchDiff = Mth.wrapDegrees(needPitch - pitch);
		
		yaw = Mth.wrapDegrees(yaw + Mth.clamp(yawDiff, -maxChange, maxChange)
			+ (float)(trackYaw * dt));
		pitch = Mth.clamp(pitch + Mth.clamp(pitchDiff, -maxChange, maxChange)
			+ (float)(trackPitch * dt), -90F, 90F);
	}
	
	/**
	 * One step of the humanized turn toward the wanted rotation, never faster
	 * than {@code maxSpeed} degrees per second (tracking comes on top).
	 */
	public void humanStep(float needYaw, float needPitch, double dt, long now,
		double maxSpeed)
	{
		double yawDiff = Mth.wrapDegrees(needYaw - yaw);
		double pitchDiff = Mth.wrapDegrees(needPitch - pitch);
		double distance = Math.hypot(yawDiff, pitchDiff);
		if(dt <= 0)
			return;
		
		// still reacting - the hand hasn't started moving yet
		if(now < reactionEndNs)
			return;
			
		// Already right on it: nothing left to correct, just keep following
		// the target's motion across the view.
		if(distance < 1e-3)
		{
			velYaw = 0;
			velPitch = 0;
			moveBy(trackYaw * dt, trackPitch * dt);
			return;
		}
		
		// Speed variation that drifts instead of jumping: every 120-280ms a
		// new target speed (80-120%), eased toward over ~120ms.
		if(now >= nextSpeedChangeNs)
		{
			speedFactorTarget = 0.8 + random.nextDouble() * 0.4;
			nextSpeedChangeNs = now + (120 + random.nextInt(161)) * 1_000_000L;
		}
		speedFactor += (speedFactorTarget - speedFactor) * ease(dt, 0.12);
		
		// Ease-out: the further off, the faster, up to the speed limit. The
		// minimum keeps the last bit from crawling.
		double desiredSpeed = distance * EASE_RATE * speedFactor;
		desiredSpeed = Mth.clamp(desiredSpeed,
			Math.min(Math.min(30, maxSpeed), distance / dt), maxSpeed);
		
		// Direction toward the target, bent sideways a little. The bend fades
		// out as the target gets close, so it still lands on it.
		double dirYaw = yawDiff / distance;
		double dirPitch = pitchDiff / distance;
		double bend = curve * Math.min(1, distance / 30);
		double bentYaw = dirYaw - dirPitch * bend;
		double bentPitch = dirPitch + dirYaw * bend;
		double bentLength = Math.hypot(bentYaw, bentPitch);
		
		double wantYaw = bentYaw / bentLength * desiredSpeed;
		double wantPitch = bentPitch / bentLength * desiredSpeed;
		
		// People line up horizontally first.
		if(Math.abs(yawDiff) > 20)
			wantPitch *= 0.6;
			
		// The hand eases into the wanted speed instead of jumping to it:
		// speeds up at the start, slows down smoothly at the end.
		double response = ease(dt, HAND_RESPONSE);
		velYaw += (wantYaw - velYaw) * response;
		velPitch += (wantPitch - velPitch) * response;
		
		// never faster than the top speed
		double speed = Math.hypot(velYaw, velPitch);
		if(speed > maxSpeed)
		{
			velYaw *= maxSpeed / speed;
			velPitch *= maxSpeed / speed;
			speed = maxSpeed;
		}
		
		updateTremor(distance, speed, dt, now);
		
		double moveYaw = (velYaw + tremorYaw) * dt;
		double movePitch = (velPitch + tremorPitch) * dt;
		
		// The correction never goes past the wanted rotation. Checking the
		// length alone wasn't enough: the hand's speed lags a little behind
		// its direction on a curved path, so a step could cross the target
		// sideways. If this step would put the target behind the aim, land
		// right on it and stop the hand instead.
		double move = Math.hypot(moveYaw, movePitch);
		if(move > distance)
		{
			moveYaw *= distance / move;
			movePitch *= distance / move;
		}
		double leftYaw = yawDiff - moveYaw;
		double leftPitch = pitchDiff - movePitch;
		if(leftYaw * yawDiff + leftPitch * pitchDiff < 0)
		{
			moveYaw = yawDiff;
			movePitch = pitchDiff;
			velYaw = 0;
			velPitch = 0;
		}
		
		// On top of the correction, follow the target's own motion across
		// the view, so the aim keeps up instead of trailing behind.
		moveBy(moveYaw + trackYaw * dt, movePitch + trackPitch * dt);
	}
	
	private void moveBy(double dYaw, double dPitch)
	{
		yaw = Mth.wrapDegrees(yaw + (float)dYaw);
		pitch = Mth.clamp(pitch + (float)dPitch, -90F, 90F);
	}
	
	/**
	 * A slow, small wobble on top of the turn, like the tremor of a real hand.
	 * Grows a little with speed and fades out near the target, so it can't
	 * make the aim shake around it.
	 */
	private void updateTremor(double distance, double speed, double dt,
		long now)
	{
		if(distance > 3)
		{
			if(now >= nextTremorChangeNs)
			{
				double size = 2 + speed * 0.03;
				tremorTargetYaw = random.nextGaussian() * size;
				tremorTargetPitch = random.nextGaussian() * size * 0.7;
				nextTremorChangeNs =
					now + (60 + random.nextInt(81)) * 1_000_000L;
			}
		}else
		{
			tremorTargetYaw = 0;
			tremorTargetPitch = 0;
		}
		
		double e = ease(dt, 0.08);
		tremorYaw += (tremorTargetYaw - tremorYaw) * e;
		tremorPitch += (tremorTargetPitch - tremorPitch) * e;
	}
	
	/** How much of the way to close in {@code dt} with time constant tau. */
	public static double ease(double dt, double tau)
	{
		return 1 - Math.exp(-dt / tau);
	}
	
	/**
	 * How fast an aim point moves across the view, in degrees per second, if
	 * it moves at {@code relativeVelocity} (blocks per second) relative to
	 * the eyes. Returns {yaw rate, pitch rate}.
	 */
	public static double[] trackingRate(Vec3 eyes, Vec3 aimPoint,
		Vec3 relativeVelocity)
	{
		double ahead = 0.05;
		Rotation now = RotationUtils.getNeededRotations(eyes, aimPoint);
		Rotation later = RotationUtils.getNeededRotations(eyes,
			aimPoint.add(relativeVelocity.scale(ahead)));
		
		return new double[]{Mth.wrapDegrees(later.yaw() - now.yaw()) / ahead,
			(later.pitch() - now.pitch()) / ahead};
	}
	
	/**
	 * How fast {@code target} moves relative to {@code self}, in blocks per
	 * second, measured over the last tick.
	 */
	public static Vec3 relativeVelocity(Entity target, Entity self)
	{
		Vec3 targetMove =
			target.position().subtract(target.xo, target.yo, target.zo);
		Vec3 ownMove = self.position().subtract(self.xo, self.yo, self.zo);
		return targetMove.subtract(ownMove).scale(20);
	}
}
