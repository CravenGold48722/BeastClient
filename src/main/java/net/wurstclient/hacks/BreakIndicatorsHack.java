/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.SortedSet;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.RenderUtils;

/**
 * A growing box on every block being mined - yours and everyone else's the
 * server tells you about. Idea from Meteor Client's BreakIndicators,
 * written for Beast.
 */
@SearchTags({"break indicators", "mining progress", "break progress"})
public final class BreakIndicatorsHack extends Hack implements RenderListener
{
	private final CheckboxSetting own =
		new CheckboxSetting("Own", "Shows the block you're mining.", true);
	private final CheckboxSetting others = new CheckboxSetting("Others",
		"Shows blocks other players are mining.", true);
	private final ColorSetting startColor = new ColorSetting("Start color",
		"The color of a block that was just started.", new Color(25, 252, 25));
	private final ColorSetting endColor = new ColorSetting("End color",
		"The color of a block that's about to break.", new Color(255, 25, 25));
	
	public BreakIndicatorsHack()
	{
		super("BreakIndicators",
			"Shows how far along every block being mined is.", false);
		setCategory(Category.RENDER);
		addSetting(own);
		addSetting(others);
		addSetting(startColor);
		addSetting(endColor);
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
		BlockPos ownPos = MC.gameMode.destroyBlockPos;
		boolean mining = MC.gameMode.isDestroying() && ownPos != null;
		
		if(own.isChecked() && mining && MC.gameMode.destroyProgress > 0)
			drawProgress(matrixStack, ownPos,
				Math.min(1, MC.gameMode.destroyProgress));
		
		if(!others.isChecked())
			return;
		
		for(SortedSet<BlockDestructionProgress> progresses : MC.levelRenderer.destructionProgress
			.values())
		{
			if(progresses.isEmpty())
				continue;
			
			BlockDestructionProgress info = progresses.last();
			if(mining && info.getPos().equals(ownPos))
				continue;
			
			// stages 0-9
			drawProgress(matrixStack, info.getPos(),
				(info.getProgress() + 1) / 10.0);
		}
	}
	
	private void drawProgress(PoseStack matrixStack, BlockPos pos,
		double progress)
	{
		BlockState state = MC.level.getBlockState(pos);
		VoxelShape shape = state.getShape(MC.level, pos);
		if(shape.isEmpty())
			return;
		
		AABB full = shape.bounds().move(pos);
		AABB box = full.deflate(full.getXsize() * (1 - progress) / 2,
			full.getYsize() * (1 - progress) / 2,
			full.getZsize() * (1 - progress) / 2);
		
		int color =
			blend(startColor.getColorI(), endColor.getColorI(), progress);
		int rgb = color & 0xFFFFFF;
		RenderUtils.drawSolidBox(matrixStack, box, 0x50000000 | rgb, false);
		RenderUtils.drawOutlinedBox(matrixStack, box, 0xC0000000 | rgb, false);
	}
	
	private static int blend(int from, int to, double t)
	{
		int r = (int)Math
			.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
		int g = (int)Math
			.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
		int b = (int)Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
