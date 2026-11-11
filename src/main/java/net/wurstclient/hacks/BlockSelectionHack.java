/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.RenderUtils;

/**
 * Replaces the thin black outline around the block you look at. Idea from
 * Meteor Client's BlockSelection, written for Beast; vanilla's outline is
 * hidden by {@code BlockSelectionMixin} while this is on.
 */
@SearchTags({"block selection", "block outline", "block highlight"})
public final class BlockSelectionHack extends Hack implements RenderListener
{
	private static final double FACE = 0.002;
	
	private final ColorSetting lineColor = new ColorSetting("Line color",
		"The outline's color.", new Color(255, 255, 255));
	private final CheckboxSetting fill =
		new CheckboxSetting("Fill", "Also fills the block.", true);
	private final ColorSetting fillColor = new ColorSetting("Fill color",
		"The fill's color (drawn see-through).", new Color(255, 255, 255));
	private final CheckboxSetting exactShape = new CheckboxSetting(
		"Exact shape",
		"Draws every part of blocks made of several boxes (stairs, fences...)"
			+ " instead of one box around them.",
		true);
	private final CheckboxSetting oneSide = new CheckboxSetting("One side",
		"Only highlights the face you're looking at.", false);
	private final CheckboxSetting hideInside =
		new CheckboxSetting("Hide inside",
			"Draws nothing while your head is inside the block.", true);
	
	public BlockSelectionHack()
	{
		super("BlockSelection",
			"Changes how the block you look at is outlined.", false);
		setCategory(Category.RENDER);
		addSetting(lineColor);
		addSetting(fill);
		addSetting(fillColor);
		addSetting(exactShape);
		addSetting(oneSide);
		addSetting(hideInside);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(!(MC.hitResult instanceof BlockHitResult hit)
			|| hit.getType() == HitResult.Type.MISS)
			return;
		
		if(hideInside.isChecked() && hit.isInside())
			return;
		
		BlockPos pos = hit.getBlockPos();
		VoxelShape shape = MC.level.getBlockState(pos).getShape(MC.level, pos);
		if(shape.isEmpty())
			return;
		
		List<AABB> boxes;
		if(oneSide.isChecked())
			boxes = List.of(faceOf(shape.bounds(), hit.getDirection()));
		else if(exactShape.isChecked())
			boxes = shape.toAabbs();
		else
			boxes = List.of(shape.bounds());
		
		boxes = boxes.stream().map(box -> box.move(pos)).toList();
		if(fill.isChecked())
			RenderUtils.drawSolidBoxes(matrixStack, boxes,
				fillColor.getColorI(0x30), false);
		RenderUtils.drawOutlinedBoxes(matrixStack, boxes,
			lineColor.getColorI(0xFF), false);
	}
	
	/** A thin slab on one face of the box. */
	private static AABB faceOf(AABB box, Direction side)
	{
		return switch(side)
		{
			case DOWN -> new AABB(box.minX, box.minY - FACE, box.minZ, box.maxX,
				box.minY + FACE, box.maxZ);
			case UP -> new AABB(box.minX, box.maxY - FACE, box.minZ, box.maxX,
				box.maxY + FACE, box.maxZ);
			case NORTH -> new AABB(box.minX, box.minY, box.minZ - FACE,
				box.maxX, box.maxY, box.minZ + FACE);
			case SOUTH -> new AABB(box.minX, box.minY, box.maxZ - FACE,
				box.maxX, box.maxY, box.maxZ + FACE);
			case WEST -> new AABB(box.minX - FACE, box.minY, box.minZ,
				box.minX + FACE, box.maxY, box.maxZ);
			case EAST -> new AABB(box.maxX - FACE, box.minY, box.minZ,
				box.maxX + FACE, box.maxY, box.maxZ);
		};
	}
}
