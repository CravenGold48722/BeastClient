/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

/**
 * Dropped items lie on the ground instead of floating and spinning. Idea
 * from Meteor Client's ItemPhysics, written for Beast; hooked in
 * {@code ItemPhysicsMixin}, which swaps vanilla's hover and spin for this.
 */
@SearchTags({"item physics", "dropped items", "flat items"})
public final class ItemPhysicsHack extends Hack
{
	/** Vanilla's limit for "flat" item models (one pixel deep). */
	private static final double FLAT_DEPTH = 1 / 16.0 + 1e-4;
	
	private final CheckboxSetting randomRotation = new CheckboxSetting(
		"Random rotation", "Turns each item a different way.", true);
	
	public ItemPhysicsHack()
	{
		super("ItemPhysics", "Makes dropped items lie on the ground.", false);
		setCategory(Category.RENDER);
		addSetting(randomRotation);
	}
	
	/** Instead of vanilla's hover: rest on the ground. */
	public void translate(PoseStack pose, ItemEntityRenderState state)
	{
		AABB box = state.item.getModelBoundingBox();
		double y = isFlat(box) ? -box.minZ : -box.minY;
		pose.translate(0, (float)y + 0.01F, 0);
	}
	
	/** Instead of vanilla's spin: a fixed turn, flat items laid down. */
	public void rotate(PoseStack pose, ItemEntityRenderState state)
	{
		if(randomRotation.isChecked())
			pose.mulPose(
				Axis.YP.rotationDegrees(Math.floorMod(state.seed, 360)));
		
		if(isFlat(state.item.getModelBoundingBox()))
			pose.mulPose(Axis.XP.rotationDegrees(-90));
	}
	
	private static boolean isFlat(AABB box)
	{
		return box.getZsize() <= FLAT_DEPTH;
	}
}
