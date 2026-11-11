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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
 * Safe holes for crystal PvP: an air gap whose floor and four sides are all
 * bedrock or blast-proof blocks (obsidian, crying obsidian, ender chests,
 * anvils...). Idea from Meteor Client's HoleESP, written for Beast.
 */
@SearchTags({"hole esp", "safe hole", "crystal hole"})
public final class HoleEspHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final Direction[] SIDES =
		{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	
	private final SliderSetting horizontalRadius = new SliderSetting(
		"Horizontal radius", "How far to the sides to look for holes.", 10, 1,
		32, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting verticalRadius = new SliderSetting(
		"Vertical radius", "How far up and down to look for holes.", 5, 1, 32,
		1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting minHeight = new SliderSetting("Min height",
		"How much free space a hole needs above its floor.", 2, 1, 5, 1,
		ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final CheckboxSetting doubles = new CheckboxSetting("Doubles",
		"Also shows two-block holes that are safe as a pair.", true);
	private final CheckboxSetting ignoreOwn = new CheckboxSetting("Ignore own",
		"Doesn't show the hole you're standing in.", false);
	private final CheckboxSetting webs = new CheckboxSetting("Webs",
		"Also shows holes with a cobweb in them.", false);
	private final SliderSetting height =
		new SliderSetting("Box height", "How tall the drawn boxes are.", 0.2,
			0.05, 1, 0.05, ValueDisplay.DECIMAL);
	private final ColorSetting bedrockColor = new ColorSetting("Bedrock color",
		"Holes made only of bedrock (can't be mined).", new Color(100, 255, 0));
	private final ColorSetting obsidianColor = new ColorSetting(
		"Obsidian color", "Holes made only of blast-proof, mineable blocks.",
		new Color(255, 0, 0));
	private final ColorSetting mixedColor = new ColorSetting("Mixed color",
		"Holes made of both.", new Color(255, 127, 0));
	
	private final ArrayList<AABB> bedrockBoxes = new ArrayList<>();
	private final ArrayList<AABB> obsidianBoxes = new ArrayList<>();
	private final ArrayList<AABB> mixedBoxes = new ArrayList<>();
	private int timer;
	
	public HoleEspHack()
	{
		super("HoleESP", "Highlights holes that are safe from crystal and"
			+ " anchor explosions.", false);
		setCategory(Category.RENDER);
		addSetting(horizontalRadius);
		addSetting(verticalRadius);
		addSetting(minHeight);
		addSetting(doubles);
		addSetting(ignoreOwn);
		addSetting(webs);
		addSetting(height);
		addSetting(bedrockColor);
		addSetting(obsidianColor);
		addSetting(mixedColor);
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
		bedrockBoxes.clear();
		obsidianBoxes.clear();
		mixedBoxes.clear();
	}
	
	@Override
	public void onUpdate()
	{
		// a few thousand block reads - every 5 ticks is plenty for holes
		if(timer-- > 0)
			return;
		timer = 4;
		
		bedrockBoxes.clear();
		obsidianBoxes.clear();
		mixedBoxes.clear();
		
		BlockPos center = MC.player.blockPosition();
		int h = horizontalRadius.getValueI();
		int v = verticalRadius.getValueI();
		double boxHeight = height.getValue();
		
		for(BlockPos pos : BlockPos.betweenClosed(center.offset(-h, -v, -h),
			center.offset(h, v, h)))
		{
			if(ignoreOwn.isChecked() && pos.equals(center))
				continue;
			
			if(!isOpen(pos))
				continue;
			
			int[] count = new int[2]; // bedrock, blast-proof
			if(!isWall(pos.below(), count))
				continue;
			
			Direction openSide = null;
			boolean valid = true;
			for(Direction side : SIDES)
			{
				BlockPos next = pos.relative(side);
				if(isWall(next, count))
					continue;
				
				// a double: one open side leading into a second hole cell
				if(!doubles.isChecked() || openSide != null || !isOpen(next)
					|| !isPartner(next, side.getOpposite(), count))
				{
					valid = false;
					break;
				}
				
				openSide = side;
			}
			if(!valid)
				continue;
			
			// each double is found from both cells; keep one
			if(openSide == Direction.NORTH || openSide == Direction.WEST)
				continue;
			
			AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1, pos.getY() + boxHeight, pos.getZ() + 1);
			if(openSide != null)
				box = box.expandTowards(openSide.getStepX(), 0,
					openSide.getStepZ());
			
			if(count[1] == 0)
				bedrockBoxes.add(box);
			else if(count[0] == 0)
				obsidianBoxes.add(box);
			else
				mixedBoxes.add(box);
		}
	}
	
	/** The second cell of a double: closed on every side but the shared one. */
	private boolean isPartner(BlockPos pos, Direction shared, int[] count)
	{
		if(!isWall(pos.below(), count))
			return false;
		
		for(Direction side : SIDES)
			if(side != shared && !isWall(pos.relative(side), count))
				return false;
			
		return true;
	}
	
	private boolean isOpen(BlockPos pos)
	{
		for(int i = 0; i < minHeight.getValueI(); i++)
		{
			BlockPos p = pos.above(i);
			BlockState state = MC.level.getBlockState(p);
			if(i == 0 && !webs.isChecked() && state.is(Blocks.COBWEB))
				return false;
			
			if(!state.getCollisionShape(MC.level, p).isEmpty()
				&& !state.is(Blocks.COBWEB))
				return false;
		}
		
		return true;
	}
	
	/** Bedrock (count[0]) or a blast-proof block (count[1]). */
	private boolean isWall(BlockPos pos, int[] count)
	{
		BlockState state = MC.level.getBlockState(pos);
		if(state.getCollisionShape(MC.level, pos).isEmpty())
			return false;
		
		boolean unbreakable = state.getDestroySpeed(MC.level, pos) < 0;
		if(unbreakable)
		{
			count[0]++;
			return true;
		}
		
		if(state.getBlock().getExplosionResistance() >= 600)
		{
			count[1]++;
			return true;
		}
		
		return false;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		draw(matrixStack, bedrockBoxes, bedrockColor);
		draw(matrixStack, obsidianBoxes, obsidianColor);
		draw(matrixStack, mixedBoxes, mixedColor);
	}
	
	private void draw(PoseStack matrixStack, ArrayList<AABB> boxes,
		ColorSetting color)
	{
		if(boxes.isEmpty())
			return;
		
		RenderUtils.drawSolidBoxes(matrixStack, boxes, color.getColorI(0x40),
			false);
		RenderUtils.drawOutlinedBoxes(matrixStack, boxes, color.getColorI(0xC0),
			false);
	}
}
