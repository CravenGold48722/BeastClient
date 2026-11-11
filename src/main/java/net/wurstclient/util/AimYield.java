/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Random;

/**
 * How hard an aim assist may pull this frame (0-1), from two things a hand
 * does (user, 2026-11-03, MaceAssist: "smooth, EXTREMELY human, and easy to
 * pull away from"; the ideas are Kclient's AimAssist, see
 * {@code ../MaceAimAssistTurning Inspirations}):
 *
 * <ul>
 * <li><b>Giving way:</b> your mouse moving against the pull drops it to
 * {@link #FLOOR} within ~{@link #DROP_TAU} s - the camera goes where you
 * drag it instead of fighting you. Once the mouse has been still for
 * {@link #RELEASE_MS} ms the pull comes back over ~{@link #RISE_TAU} s, and
 * the aim brings the crosshair back like in the reference video.</li>
 * <li><b>Hesitating:</b> when the target changes direction across your view
 * (faster than {@link #DIR_RATE} degrees/s both ways) the pull eases off for
 * 300-400 ms - "hold on" - instead of reversing at once like a machine; not
 * again for {@link #DIR_COOLDOWN_MS} ms, so a fast strafer doesn't make it
 * stutter.</li>
 * </ul>
 * Time constants, not per-frame steps: the same at 30 and 240 fps.
 */
public final class AimYield
{
	/**
	 * The pull while you're dragging against it: at most ~10 degrees/s of
	 * the aim's 340. At 0.1 (Kclient's 0.2 is on a ~60 degrees/s aim) a
	 * 100 degrees/s drag still only got 29 degrees off in half a second.
	 */
	public static final double FLOOR = 0.03;
	/** Mouse movement against the pull that counts as dragging, degrees. */
	public static final double ENGAGE = 0.1;
	/** Mouse movement that still counts as dragging once it has started. */
	public static final double STILL_MOVING = 0.05;
	public static final long RELEASE_MS = 150;
	public static final double DROP_TAU = 0.05;
	public static final double RISE_TAU = 0.12;
	
	public static final double DIR_RATE = 6;
	public static final long DIR_HOLD_MIN_MS = 300;
	public static final long DIR_HOLD_MAX_MS = 400;
	public static final long DIR_COOLDOWN_MS = 450;
	private static final double RATE_TAU = 0.06;
	private static final double HESITATE_TAU = 0.06;
	
	private final Random random = new Random();
	
	private double pull = 1;
	private boolean dragging;
	private long quietSinceMs = -1;
	
	private double gate = 1;
	private boolean hasLastNeed;
	private double lastNeedYaw;
	private double needRate;
	private int lastDir;
	private long holdUntilMs;
	private long cooldownUntilMs;
	
	public void reset()
	{
		pull = 1;
		dragging = false;
		quietSinceMs = -1;
		gate = 1;
		hasLastNeed = false;
		needRate = 0;
		lastDir = 0;
		holdUntilMs = 0;
		cooldownUntilMs = 0;
	}
	
	/**
	 * One frame.
	 *
	 * @param mouseYaw
	 *            how far your mouse turned the camera since the last frame,
	 *            degrees
	 * @param aimYaw
	 *            how far the aim itself turned it last frame, degrees
	 * @param needYaw
	 *            the yaw the aim wants this frame
	 * @return the share of the aim's pull to use this frame, 0-1
	 */
	public double update(double mouseYaw, double aimYaw, double needYaw,
		double dt, long nowMs)
	{
		if(dt <= 0)
			return pull * gate;
		
		updateDrag(mouseYaw, aimYaw, dt, nowMs);
		boolean hesitate = updateDirection(needYaw, aimYaw, dt, nowMs);
		gate += ((hesitate ? 0 : 1) - gate) * ease(dt, HESITATE_TAU);
		return pull * gate;
	}
	
	/** Whether your mouse is dragging against the aim right now. */
	public boolean isDragging()
	{
		return dragging;
	}
	
	private void updateDrag(double mouseYaw, double aimYaw, double dt,
		long nowMs)
	{
		boolean against =
			Math.abs(aimYaw) > 0.05 && Math.abs(mouseYaw) >= ENGAGE
				&& Math.signum(aimYaw) != Math.signum(mouseYaw);
		if(against)
		{
			dragging = true;
			quietSinceMs = -1;
			
		}else if(dragging)
		{
			if(Math.abs(mouseYaw) >= STILL_MOVING)
				quietSinceMs = -1;
			else if(quietSinceMs < 0)
				quietSinceMs = nowMs;
			else if(nowMs - quietSinceMs >= RELEASE_MS)
				dragging = false;
		}
		
		double goal = dragging ? FLOOR : 1;
		pull += (goal - pull) * ease(dt, dragging ? DROP_TAU : RISE_TAU);
	}
	
	private boolean updateDirection(double needYaw, double aimYaw, double dt,
		long nowMs)
	{
		if(hasLastNeed)
		{
			double delta = wrap(needYaw - lastNeedYaw);
			needRate += (delta / dt - needRate) * ease(dt, RATE_TAU);
		}
		lastNeedYaw = needYaw;
		hasLastNeed = true;
		
		if(nowMs < holdUntilMs)
			return true;
		
		int dir = needRate > DIR_RATE ? 1 : needRate < -DIR_RATE ? -1 : 0;
		if(dir == 0)
			return false;
		boolean flipped = lastDir != 0 && dir != lastDir;
		lastDir = dir;
		
		// only while actually following it, and not again right away
		if(flipped && Math.abs(aimYaw) > 0.05 && nowMs >= cooldownUntilMs)
		{
			holdUntilMs = nowMs + DIR_HOLD_MIN_MS + (long)(random.nextDouble()
				* (DIR_HOLD_MAX_MS - DIR_HOLD_MIN_MS + 1));
			cooldownUntilMs = holdUntilMs + DIR_COOLDOWN_MS;
			return true;
		}
		return false;
	}
	
	private static double ease(double dt, double tau)
	{
		return 1 - Math.exp(-dt / tau);
	}
	
	private static double wrap(double degrees)
	{
		degrees %= 360;
		if(degrees >= 180)
			degrees -= 360;
		if(degrees < -180)
			degrees += 360;
		return degrees;
	}
}
