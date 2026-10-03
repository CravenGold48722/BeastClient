/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.RotationFaker;
import net.wurstclient.WurstClient;

/**
 * Whether a hit is one an anticheat can verify - Matrix's and Grim's HITBOX
 * checks ("hit without any intersection") rebuild your look ray from what the
 * server knows and flag a hit that ray doesn't pass through.
 *
 * <p>
 * The catch: an attack packet goes out <i>before</i> this tick's movement
 * packet, so when it arrives the server still has the rotation from the last
 * movement packet. An aimbot that turns the camera every frame and hits the
 * moment the crosshair lands - after a 36 degree turn in one tick, say - hits
 * with a rotation the server hasn't seen. Which one a check uses (the last
 * one, the next one, something in between) isn't known for closed-source
 * anticheats, so a verifiable hit has to work with all of them:
 * <ul>
 * <li>the rotation already sent <i>and</i> the current one both hit,</li>
 * <li>from where you are now and from where this tick's move takes you,</li>
 * <li>within your real reach, with no block in between,</li>
 * <li>a little inside the hitbox's sides, since the server's copy of the
 * target is rarely exactly where you see it.</li>
 * </ul>
 * After a fast turn that holds the first hit back by a single tick.
 *
 * <p>
 * Call it inside the tick, right where the attack goes out.
 */
public enum HitCheck
{
	;
	
	/** How far inside the hitbox's sides the rays have to hit, blocks. */
	private static final double SIDE_MARGIN = 0.1;
	
	public static boolean isVerifiable(Entity target)
	{
		LocalPlayer player = WurstClient.MC.player;
		if(player == null || target == null)
			return false;
		
		AABB box = target.getBoundingBox().inflate(target.getPickRadius());
		double margin =
			Math.min(SIDE_MARGIN, Math.min(box.getXsize(), box.getZsize()) / 4);
		box = box.deflate(margin, 0, margin);
		
		double reach = player.entityInteractionRange();
		
		// in the tick, before moving: where the last movement packet put you
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 eyesAfterMove = eyes.add(player.getDeltaMovement());
		
		RotationFaker faker = WurstClient.INSTANCE.getRotationFaker();
		Rotation now =
			new Rotation(faker.getServerYaw(), faker.getServerPitch());
		Rotation sent = new Rotation(player.yRotLast, player.xRotLast);
		
		return rayHits(eyes, sent, box, reach) && rayHits(eyes, now, box, reach)
			&& rayHits(eyesAfterMove, now, box, reach);
	}
	
	private static boolean rayHits(Vec3 eyes, Rotation rotation, AABB box,
		double reach)
	{
		if(box.contains(eyes))
			return true;
		
		Vec3 end = eyes.add(rotation.toLookVec().scale(reach));
		Optional<Vec3> hit = box.clip(eyes, end);
		return hit.isPresent() && BlockUtils.hasLineOfSight(eyes, hit.get());
	}
}
