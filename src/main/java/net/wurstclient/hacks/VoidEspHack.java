/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

/**
 * Gaps in the bedrock floor (and the Nether roof) that lead into the void.
 * Idea from Meteor Client's VoidESP, written for Beast.
 */
@SearchTags({"void esp", "bedrock hole", "nether roof hole"})
public final class VoidEspHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final int NETHER_ROOF_Y = 127;
	
	private final SliderSetting radius =
		new SliderSetting("Radius", "How far around you to look for holes.", 64,
			8, 256, 8, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting holeHeight = new SliderSetting("Hole height",
		"How many layers of the floor have to be open.", 1, 1, 5, 1,
		ValueDisplay.INTEGER);
	private final CheckboxSetting airOnly = new CheckboxSetting("Air only",
		"Only counts a gap of air, not any non-bedrock block.", false);
	private final CheckboxSetting netherRoof = new CheckboxSetting(
		"Nether roof", "Also looks for holes in the Nether roof.", true);
	private final ColorSetting color = new ColorSetting("Color",
		"The color of the holes.", new Color(225, 25, 255));
	
	private final ArrayList<AABB> boxes = new ArrayList<>();
	private int timer;
	
	public VoidEspHack()
	{
		super("VoidESP", "Highlights holes in the bedrock floor and the Nether"
			+ " roof that lead into the void.", false);
		setCategory(Category.RENDER);
		addSetting(radius);
		addSetting(holeHeight);
		addSetting(airOnly);
		addSetting(netherRoof);
		addSetting(color);
	}
	
	@Override
	protected void onEnable()
	{
		timer = 0;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		boxes.clear();
	}
	
	@Override
	public void onUpdate()
	{
		// up to 260k columns at the largest radius - once a second is enough
		if(timer-- > 0)
			return;
		timer = 19;
		
		boxes.clear();
		if(MC.level.dimension() == Level.END)
			return;
		
		boolean nether = MC.level.dimension() == Level.NETHER;
		int px = MC.player.getBlockX();
		int pz = MC.player.getBlockZ();
		int r = radius.getValueI();
		int minY = MC.level.getMinY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		
		for(int x = px - r; x <= px + r; x++)
			for(int z = pz - r; z <= pz + r; z++)
			{
				LevelChunk chunk =
					MC.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
				if(chunk == null)
					continue;
				
				if(isHole(chunk, pos, x, z, minY, 1))
					boxes.add(new AABB(x, minY, z, x + 1, minY + 1, z + 1));
				
				if(nether && netherRoof.isChecked()
					&& isHole(chunk, pos, x, z, NETHER_ROOF_Y, -1))
					boxes.add(new AABB(x, NETHER_ROOF_Y, z, x + 1,
						NETHER_ROOF_Y + 1, z + 1));
			}
	}
	
	private boolean isHole(LevelChunk chunk, BlockPos.MutableBlockPos pos,
		int x, int z, int startY, int step)
	{
		for(int i = 0; i < holeHeight.getValueI(); i++)
		{
			BlockState state =
				chunk.getBlockState(pos.set(x, startY + i * step, z));
			boolean wrong =
				airOnly.isChecked() ? !state.isAir() : state.is(Blocks.BEDROCK);
			if(wrong)
				return false;
		}
		
		return true;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(boxes.isEmpty())
			return;
		
		RenderUtils.drawSolidBoxes(matrixStack, boxes, color.getColorI(0x30),
			false);
		RenderUtils.drawOutlinedBoxes(matrixStack, boxes, color.getColorI(0xC0),
			false);
	}
}
