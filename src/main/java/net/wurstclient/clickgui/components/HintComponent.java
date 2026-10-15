/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.clickgui.Component;
import net.wurstclient.util.BeastColors;
import net.wurstclient.util.BeastFont;

/** A line of muted text, e.g. what to do in an empty window. */
public final class HintComponent extends Component
{
	private static final Font TR = BeastFont.get();
	
	private final String text;
	
	public HintComponent(String text)
	{
		this.text = text;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY,
		float partialTicks)
	{
		// squeezed to fit a narrow (bar) window
		float squeeze =
			Math.min(1, (getWidth() - 5) / (float)Math.max(1, TR.width(text)));
		Matrix3x2fStack pose = context.pose();
		pose.pushMatrix();
		pose.translate(getX() + 3, getY() + 2 + (8 - 8 * squeeze) / 2);
		pose.scale(squeeze);
		context.text(TR, text, 0, 0, BeastColors.TEXT_MUTED, false);
		pose.popMatrix();
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(text) + 6;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
