/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.CityUtils;
import net.wurstclient.util.RenderUtils;

/**
 * The surround block to mine to city the nearest player. Idea from Meteor
 * Client's CityESP, written for Beast.
 */
@SearchTags({"city esp", "surround", "crystal pvp"})
public final class CityEspHack extends Hack
	implements UpdateListener, RenderListener
{
	private final ColorSetting color = new ColorSetting("Color",
		"The highlighted block's color.", new Color(225, 0, 0));
	
	private BlockPos target;
	
	public CityEspHack()
	{
		super("CityESP", "Shows which block to mine to city the nearest"
			+ " player (crystal PvP).", false);
		setCategory(Category.RENDER);
		addSetting(color);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		target = null;
	}
	
	@Override
	public void onUpdate()
	{
		target = CityUtils.getCityBlock(
			CityUtils.findTarget(MC.player.blockInteractionRange() + 2));
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(target == null)
			return;
		
		AABB box = new AABB(target);
		RenderUtils.drawSolidBox(matrixStack, box, color.getColorI(0x4B),
			false);
		RenderUtils.drawOutlinedBox(matrixStack, box, color.getColorI(0xFF),
			false);
	}
}
