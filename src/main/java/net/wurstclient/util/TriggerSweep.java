/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Sees a hit coming: samples the coming tick 150 times - your eyes moving
 * along your velocity, the target's hitbox along its own - and finds the
 * moment the target comes within reach. That finds windows a check at the
 * tick boundaries alone would only notice once they're already there (or
 * miss entirely, when a fast fall passes through reach between two ticks).
 *
 * <p>
 * Only for timing and aiming. The hit itself still has to pass the real
 * check, in the tick, on real positions: a hit on a predicted position is
 * one the server never saw (Grim "HITBOX: hit without any intersection").
 */
public enum TriggerSweep
{
	;
	
	public static final int SAMPLES = 150;
	
	/**
	 * The earliest fraction (0-1] of the coming tick at which the target is
	 * within reach: 0 when it already is, -1 when it won't be during this
	 * tick.
	 *
	 * @param selfStep
	 *            how far your eyes move during the tick
	 * @param targetStep
	 *            how far the target moves during the tick
	 */
	public static double firstInReach(Vec3 eyes, Vec3 selfStep, AABB box,
		Vec3 targetStep, double reach)
	{
		double reachSq = reach * reach;
		if(distanceSq(eyes, box) <= reachSq)
			return 0;
		
		for(int i = 1; i <= SAMPLES; i++)
		{
			double f = i / (double)SAMPLES;
			Vec3 e = eyes.add(selfStep.scale(f));
			AABB b = box.move(targetStep.scale(f));
			if(distanceSq(e, b) <= reachSq)
				return f;
		}
		return -1;
	}
	
	/** Squared distance from a point to the nearest point of a box. */
	public static double distanceSq(Vec3 p, AABB box)
	{
		double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
		return dx * dx + dy * dy + dz * dz;
	}
}
