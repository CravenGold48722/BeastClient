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
	
	// ── PID aim (pidStep)
	
	/**
	 * Proportional gain while the crosshair is off the target, per second:
	 * closes about 45% of the gap every tick, the same pull as the ease-out
	 * of {@link #humanStep}.
	 */
	public static final double KP_OFF = 12;
	
	/**
	 * Proportional gain while the crosshair is on the target: a gentle pull
	 * toward the aim point that keeps it on the hitbox without chasing every
	 * tiny change.
	 */
	public static final double KP_ON = 6;
	
	/** Derivative gain, seconds - damps the approach. */
	public static final double KD = 0.04;
	
	/**
	 * The integral only builds up within this many hitbox half-sizes of the
	 * aim point, so it corrects a steady lag behind a moving target but can't
	 * wind up during a big turn.
	 */
	public static final double I_DEPTH = 2;
	
	/** The most the integral can add, degrees per second. */
	public static final double I_LIMIT = 120;
	
	/**
	 * Deeper inside the hitbox than this (a share of its half-size around the
	 * aim point): no correction, only following the target's motion.
	 */
	public static final double REST_DEPTH = 0.3;
	
	/** ...and closer to the aim point than this, degrees. */
	public static final double REST_ANGLE = 0.5;
	
	/** How fast the gains blend between on and off target, seconds. */
	private static final double SCHEDULE_TAU = 0.08;
	
	/** Low-pass for the derivative, seconds. */
	private static final double DERIVATIVE_TAU = 0.03;
	
	/**
	 * How far behind the wanted rotation the humanized feed-forward follows,
	 * seconds - the eye takes a moment to pick up a change of direction.
	 */
	private static final double PURSUIT_LAG = 0.05;
	
	/** Gain multiplier without humanizing - snappier, there's no hand. */
	private static final double PLAIN_GAIN = 2;
	
	private double integYaw;
	private double integPitch;
	private double lastErrYaw;
	private double lastErrPitch;
	private boolean hasLastErr;
	private double derivYaw;
	private double derivPitch;
	/** 0 = gains for on target, 1 = gains for off target. */
	private double offTarget = 1;
	
	// setpoint feed-forward
	private float lastNeedYaw;
	private float lastNeedPitch;
	private boolean hasLastNeed;
	private double ffYaw;
	private double ffPitch;
	
	/** The previous step's dt, seconds; 0 = none yet. */
	private double lastDt;
	
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
		lastDt = 0;
		hasLastErr = false;
		hasLastNeed = false;
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
		resetPid();
	}
	
	/** Forgets the PID's memory (integral, derivative, gain schedule). */
	private void resetPid()
	{
		integYaw = 0;
		integPitch = 0;
		hasLastErr = false;
		derivYaw = 0;
		derivPitch = 0;
		offTarget = 1;
		hasLastNeed = false;
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
		double lead = leadDt(dt);
		float yawDiff = Mth.wrapDegrees(needYaw - yaw);
		float pitchDiff = Mth.wrapDegrees(needPitch - pitch);
		
		yaw = Mth.wrapDegrees(yaw + Mth.clamp(yawDiff, -maxChange, maxChange)
			+ (float)(trackYaw * lead));
		pitch = Mth.clamp(pitch + Mth.clamp(pitchDiff, -maxChange, maxChange)
			+ (float)(trackPitch * lead), -90F, 90F);
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
			double lead = leadDt(dt);
			moveBy(trackYaw * lead, trackPitch * lead);
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
		double lead = leadDt(dt);
		moveBy(moveYaw + trackYaw * lead, movePitch + trackPitch * lead);
	}
	
	/**
	 * One step of the PID aim: the speed adjusts itself to where the
	 * crosshair is relative to the target's hitbox, instead of being set by
	 * distance.
	 *
	 * <ul>
	 * <li><b>Gain schedule:</b> how hard it pulls depends on how deep inside
	 * the hitbox the aim is - the aim error divided by {@code tolerance}
	 * (the hitbox's angular half-size around the aim point). Off the target
	 * and near its edge: full pull ({@link #KP_OFF}), so it catches up fast
	 * and reacts before slipping off. Deep inside: a gentle pull
	 * ({@link #KP_ON}). Very deep inside ({@link #REST_DEPTH}): no
	 * correction at all, only following the target - nobody re-centers on a
	 * hitbox they're already on, and it saves rotation packets. The blend is
	 * smoothed over ~80ms.</li>
	 * <li><b>P</b> pulls toward the aim point.</li>
	 * <li><b>I</b> removes a steady lag behind a moving target that the
	 * tracking doesn't fully cover. It only builds up near the hitbox, never
	 * at full speed, and never while the gap is already closing fast - so an
	 * approach can't wind it up and carry the aim past the target.</li>
	 * <li><b>D</b> damps the approach and reacts within a frame when the
	 * target changes direction, low-pass filtered.</li>
	 * <li>On top: the target's own motion across the view (tracking), as
	 * feed-forward.</li>
	 * </ul>
	 * With {@code humanize}, the correction also gets the hand's traits from
	 * {@link #humanStep}: reaction delay, drifting speed, curved path,
	 * horizontal first, the hand easing into its speed, tremor while far off.
	 *
	 * @param tolerance
	 *            how far (degrees) the aim can be from the aim point and
	 *            still be on the hitbox
	 */
	public void pidStep(float needYaw, float needPitch, double dt, long now,
		double maxSpeed, double tolerance, boolean humanize)
	{
		if(dt <= 0)
			return;
		
		// still reacting - the hand hasn't started moving yet
		if(humanize && now < reactionEndNs)
			return;
		
		double errYaw = Mth.wrapDegrees(needYaw - yaw);
		double errPitch = Mth.wrapDegrees(needPitch - pitch);
		double distance = Math.hypot(errYaw, errPitch);
		double depth = distance / Math.max(tolerance, 0.3);
		
		// Feed-forward: how fast the wanted rotation itself is moving, measured
		// every step. It is worked out from where the target is drawn, so a
		// change of direction shows up within a frame - the per-tick tracking
		// estimate only catches it a tick later, by which time a strafing
		// target has slid off the crosshair. Humanized: a short pursuit lag,
		// like the eye following a moving target.
		if(hasLastNeed)
		{
			double e = ease(dt, humanize ? PURSUIT_LAG : 0.02);
			double rateYaw =
				Mth.clamp(Mth.wrapDegrees(needYaw - lastNeedYaw) / dt,
					-MAX_TRACKING, MAX_TRACKING);
			double ratePitch = Mth.clamp((needPitch - lastNeedPitch) / dt,
				-MAX_TRACKING, MAX_TRACKING);
			ffYaw += (rateYaw - ffYaw) * e;
			ffPitch += (ratePitch - ffPitch) * e;
		}else
		{
			// start from the tick-measured tracking
			ffYaw = trackYaw;
			ffPitch = trackPitch;
		}
		lastNeedYaw = needYaw;
		lastNeedPitch = needPitch;
		hasLastNeed = true;
		
		// gain schedule: full pull at the hitbox's edge and beyond, gentle
		// deep inside
		double scheduled = smoothstep(REST_DEPTH, 1, depth);
		offTarget += (scheduled - offTarget) * ease(dt, SCHEDULE_TAU);
		double kp = KP_ON + (KP_OFF - KP_ON) * offTarget;
		// no hand to slow it down: twice the pull
		kp *= humanize ? driftSpeedFactor(dt, now) : PLAIN_GAIN;
		// critically damped together with P for a pure turn (the camera
		// integrates speed into angle): s^2 (1 + KD) + kp s + ki
		double ki = kp * kp / (4 * (1 + KD));
		
		// D: error rate, low-pass filtered against the per-tick steps in the
		// target's measured position
		if(hasLastErr)
		{
			double e = ease(dt, DERIVATIVE_TAU);
			derivYaw +=
				(Mth.wrapDegrees(errYaw - lastErrYaw) / dt - derivYaw) * e;
			derivPitch += ((errPitch - lastErrPitch) / dt - derivPitch) * e;
		}
		lastErrYaw = errYaw;
		lastErrPitch = errPitch;
		hasLastErr = true;
		
		double wantYaw;
		double wantPitch;
		// Rest (no correction) only when deep inside the hitbox AND right on
		// the aim point - resting anywhere inside left the crosshair sitting
		// visibly off-center at close range, where the hitbox is many degrees
		// wide.
		boolean resting = depth < REST_DEPTH && distance < REST_ANGLE;
		if(resting)
		{
			// deep inside the hitbox: just follow the target (the integral
			// keeps whatever lag it has learned)
			wantYaw = Math.abs(integYaw) < 2 ? 0 : integYaw;
			wantPitch = Math.abs(integPitch) < 2 ? 0 : integPitch;
			
		}else
		{
			double dYaw = Mth.clamp(KD * derivYaw, -maxSpeed / 2, maxSpeed / 2);
			double dPitch =
				Mth.clamp(KD * derivPitch, -maxSpeed / 2, maxSpeed / 2);
			wantYaw = kp * errYaw + integYaw + dYaw;
			wantPitch = kp * errPitch + integPitch + dPitch;
			
			// I: near the hitbox, not at full speed, and not while the gap is
			// already closing at more than half the P pull (an approach)
			boolean saturated = Math.hypot(wantYaw, wantPitch) >= maxSpeed;
			double closing = distance < 1e-6 ? 0
				: -(errYaw * derivYaw + errPitch * derivPitch) / distance;
			if(depth < I_DEPTH && !saturated && closing < kp * distance / 2)
			{
				integYaw =
					Mth.clamp(integYaw + ki * errYaw * dt, -I_LIMIT, I_LIMIT);
				integPitch = Mth.clamp(integPitch + ki * errPitch * dt,
					-I_LIMIT, I_LIMIT);
			}
		}
		
		if(humanize && distance > 1e-3)
		{
			// curved path that straightens out near the target
			double bend = curve * Math.min(1, distance / 30);
			double bentYaw = wantYaw - wantPitch * bend;
			double bentPitch = wantPitch + wantYaw * bend;
			wantYaw = bentYaw;
			wantPitch = bentPitch;
			
			// people line up horizontally first
			if(Math.abs(errYaw) > 20)
				wantPitch *= 0.6;
		}
		
		// never faster than the top speed
		double want = Math.hypot(wantYaw, wantPitch);
		if(want > maxSpeed)
		{
			wantYaw *= maxSpeed / want;
			wantPitch *= maxSpeed / want;
		}
		
		if(humanize)
		{
			// the hand eases into the speed instead of jumping to it
			double response = ease(dt, HAND_RESPONSE);
			velYaw += (wantYaw - velYaw) * response;
			velPitch += (wantPitch - velPitch) * response;
			
			// a hand at rest is at rest, not creeping
			if(wantYaw == 0 && wantPitch == 0
				&& Math.hypot(velYaw, velPitch) < 0.5)
				velYaw = velPitch = 0;
			
			updateTremor(depth < 1 ? 0 : distance, Math.hypot(velYaw, velPitch),
				dt, now);
			if(resting && Math.hypot(tremorYaw, tremorPitch) < 0.5)
				tremorYaw = tremorPitch = 0;
			
		}else
		{
			velYaw = wantYaw;
			velPitch = wantPitch;
			tremorYaw = 0;
			tremorPitch = 0;
		}
		
		double moveYaw = (velYaw + tremorYaw) * dt;
		double movePitch = (velPitch + tremorPitch) * dt;
		
		// The correction never carries past the aim point in one step - also
		// not sideways, along a curved path (see humanStep).
		double move = Math.hypot(moveYaw, movePitch);
		if(move > distance && move > 0)
		{
			moveYaw *= distance / move;
			movePitch *= distance / move;
		}
		if((errYaw - moveYaw) * errYaw + (errPitch - movePitch) * errPitch < 0)
		{
			moveYaw = errYaw;
			movePitch = errPitch;
			velYaw = 0;
			velPitch = 0;
		}else if(moveYaw * errYaw + movePitch * errPitch < 0 && !resting
			&& velYaw * errYaw + velPitch * errPitch < 0)
		{
			// Already past the aim point and the hand still moving away from
			// it: it stops there. The check above only caught a step that
			// crosses the aim point - after a fast catch-up the hand's
			// leftover speed then coasted the aim ~4 degrees past a moving
			// target, a visible flick.
			moveYaw = 0;
			movePitch = 0;
			velYaw = 0;
			velPitch = 0;
		}
		
		// plus the target's own motion across the view (feed-forward)
		double lead = leadDt(dt);
		moveBy(moveYaw + ffYaw * lead, movePitch + ffPitch * lead);
	}
	
	/**
	 * How far ahead to follow the target's motion this step: until the next
	 * step, which is about one normal frame away - not this step's dt.
	 * After a lag spike, leading by the whole spike put the aim that far
	 * ahead of a fast target, a flick in its direction (23 degrees after
	 * 150ms at 150 degrees/s).
	 */
	private double leadDt(double dt)
	{
		double lead = Math.min(dt, lastDt > 0 ? lastDt : 1 / 60.0);
		lastDt = dt;
		return lead;
	}
	
	/** 0 below {@code edge0}, 1 above {@code edge1}, smooth in between. */
	private static double smoothstep(double edge0, double edge1, double x)
	{
		double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0, 1);
		return t * t * (3 - 2 * t);
	}
	
	/**
	 * Speed variation that drifts instead of jumping: every 120-280ms a new
	 * target speed (80-120%), eased toward over ~120ms.
	 */
	private double driftSpeedFactor(double dt, long now)
	{
		if(now >= nextSpeedChangeNs)
		{
			speedFactorTarget = 0.8 + random.nextDouble() * 0.4;
			nextSpeedChangeNs = now + (120 + random.nextInt(161)) * 1_000_000L;
		}
		speedFactor += (speedFactorTarget - speedFactor) * ease(dt, 0.12);
		return speedFactor;
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
