/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.WurstClient;
import net.wurstclient.util.BeastColors;
import net.wurstclient.util.RenderUtils;

public enum ClickGuiIcons
{
	;
	
	public static void drawMinimizeArrow(GuiGraphicsExtractor context, float x1,
		float y1, float x2, float y2, boolean hovering, boolean minimized)
	{
		float xa1 = x1 + 1;
		float xa2 = (x1 + x2) / 2;
		float xa3 = x2 - 1;
		float ya1;
		float ya2;
		
		// arrow
		int arrowColor;
		float[][] arrowVertices;
		if(minimized)
		{
			ya1 = y1 + 3;
			ya2 = y2 - 2.5F;
			arrowColor = hovering ? BeastColors.TEXT : BeastColors.TEXT_MUTED;
			arrowVertices = new float[][]{{xa1, ya1}, {xa2, ya2}, {xa3, ya1}};
			
		}else
		{
			ya1 = y2 - 3;
			ya2 = y1 + 2.5F;
			arrowColor = hovering ? BeastColors.ACCENT_RED_HOVER
				: BeastColors.ACCENT_RED;
			arrowVertices = new float[][]{{xa1, ya1}, {xa3, ya1}, {xa2, ya2}};
		}
		RenderUtils.fillTriangle2D(context, arrowVertices, arrowColor);
		
		// outline
		int outlineColor = 0x80101010;
		RenderUtils.drawLineStrip2D(context, arrowVertices, outlineColor);
	}
	
	public static final int SWITCH_WIDTH = 14;
	
	/**
	 * A square-cornered on/off switch, {@link #SWITCH_WIDTH} x 7 GUI pixels
	 * with its top left corner at (x, y): the accent wave with the knob on
	 * the right when on, a dark track with the knob on the left when off.
	 * {@code on} goes from 0 (off) to 1 (on), so the knob can slide and the
	 * wave fade in.
	 */
	public static void drawSwitch(GuiGraphicsExtractor context, int x, int y,
		float on, boolean hovering, boolean grayedOut)
	{
		int x2 = x + SWITCH_WIDTH;
		int y2 = y + 7;
		
		// track: a plain rectangle, the wave fading in over the dark one
		int track;
		if(grayedOut)
			track = BeastColors.lerpColor(0xFF1E1E22, 0xFF4A2226, on);
		else
			track = hovering ? 0xFF38383F : BeastColors.ROW_BORDER;
		context.fill(x, y, x2, y2, track);
		if(!grayedOut && on > 0)
			RenderUtils.fillGradient2D(context, x, y, x2, y2, on);
		
		// knob: a square, inset by a pixel, sliding across
		int knob;
		if(grayedOut)
			knob = 0xFF5A5A5E;
		else
			knob = BeastColors.lerpColor(
				hovering ? BeastColors.TEXT : BeastColors.TEXT_MUTED,
				hovering ? 0xFFFFFFFF : BeastColors.TEXT, on);
		float kx = x + 1 + (SWITCH_WIDTH - 7) * on;
		RenderUtils.fill2D(context, kx, y + 1, kx + 5, y2 - 1, knob);
	}
	
	/**
	 * The golden shield in front of an Important feature's name, 7 x 8 GUI
	 * pixels with its top left corner at (x, y).
	 */
	public static void drawShield(GuiGraphicsExtractor context, int x, int y)
	{
		// outline: flat top, straight sides, tapering to a point
		context.fill(x, y, x + 7, y + 4, BeastColors.GOLD_DARK);
		context.fill(x + 1, y + 4, x + 6, y + 6, BeastColors.GOLD_DARK);
		context.fill(x + 2, y + 6, x + 5, y + 7, BeastColors.GOLD_DARK);
		context.fill(x + 3, y + 7, x + 4, y + 8, BeastColors.GOLD_DARK);
		
		// gold face
		context.fill(x + 1, y + 1, x + 6, y + 4, BeastColors.GOLD);
		context.fill(x + 2, y + 4, x + 5, y + 6, BeastColors.GOLD);
		context.fill(x + 3, y + 6, x + 4, y + 7, BeastColors.GOLD);
		
		// light from the top left
		context.fill(x + 1, y + 1, x + 3, y + 2, BeastColors.GOLD_LIGHT);
		context.fill(x + 1, y + 2, x + 2, y + 4, BeastColors.GOLD_LIGHT);
	}
	
	public static void drawRadarArrow(GuiGraphicsExtractor context, float x1,
		float y1, float x2, float y2)
	{
		float x3 = x1 + (x2 - x1) / 2;
		float y3 = y1 + (y2 - y1) * 0.75F;
		
		// arrow
		ClickGui gui = WurstClient.INSTANCE.getGui();
		int arrowColor =
			RenderUtils.toIntColor(gui.getAcColor(), gui.getOpacity());
		float[][] arrowVertices = {{x3, y1}, {x1, y2}, {x3, y3}, {x2, y2}};
		RenderUtils.fillQuads2D(context, arrowVertices, arrowColor);
		
		// outline
		int outlineColor = 0x80101010;
		RenderUtils.drawLineStrip2D(context, arrowVertices, outlineColor);
	}
	
	public static void drawCheck(GuiGraphicsExtractor context, float x1,
		float y1, float x2, float y2, boolean hovering, boolean grayedOut)
	{
		float xc1 = x1 + 2.5F;
		float xc2 = x1 + 3.5F;
		float xc3 = (x1 + x2) / 2 - 1;
		float xc4 = x2 - 3.5F;
		float xc5 = x2 - 2.5F;
		float yc1 = y1 + 2.5F;
		float yc2 = y1 + 3.5F;
		float yc3 = (y1 + y2) / 2;
		float yc4 = yc3 + 1;
		float yc5 = y2 - 4.5F;
		float yc6 = y2 - 2.5F;
		
		// check
		int checkColor = grayedOut ? 0xC0808080 : BeastColors.ICON_BLACK;
		float[][] checkVertices = {{xc2, yc3}, {xc1, yc4}, {xc3, yc6},
			{xc3, yc5}, {xc3, yc5}, {xc3, yc6}, {xc5, yc2}, {xc4, yc1}};
		RenderUtils.fillQuads2D(context, checkVertices, checkColor);
		
		// outline
		int outlineColor = 0x80101010;
		float[][] outlineVertices = {{xc2, yc3}, {xc3, yc5}, {xc4, yc1},
			{xc5, yc2}, {xc3, yc6}, {xc1, yc4}, {xc2, yc3}};
		RenderUtils.drawLineStrip2D(context, outlineVertices, outlineColor);
	}
	
	public static void drawIndeterminateCheck(GuiGraphicsExtractor context,
		float x1, float y1, float x2, float y2, boolean hovering,
		boolean grayedOut)
	{
		float xc1 = x1 + 2.5F;
		float xc2 = x2 - 2.5F;
		float yc1 = y1 + 2.5F;
		float yc2 = y2 - 2.5F;
		
		// fill
		int checkColor = grayedOut ? 0xC0808080 : BeastColors.ICON_BLACK;
		RenderUtils.fill2D(context, xc1, yc1, xc2, yc2, checkColor);
		
		// outline
		int outlineColor = 0x80101010;
		RenderUtils.drawBorder2D(context, xc1, yc1, xc2, yc2, outlineColor);
	}
	
	public static void drawCross(GuiGraphicsExtractor context, float x1,
		float y1, float x2, float y2, boolean hovering)
	{
		float xc1 = x1 + 2;
		float xc2 = x1 + 3;
		float xc3 = x2 - 2;
		float xc4 = x2 - 3;
		float xc5 = x1 + 3.5F;
		float xc6 = (x1 + x2) / 2;
		float xc7 = x2 - 3.5F;
		float yc1 = y1 + 3;
		float yc2 = y1 + 2;
		float yc3 = y2 - 3;
		float yc4 = y2 - 2;
		float yc5 = y1 + 3.5F;
		float yc6 = (y1 + y2) / 2;
		float yc7 = y2 - 3.5F;
		
		// cross
		int crossColor =
			hovering ? BeastColors.ACCENT_RED_HOVER : BeastColors.ACCENT_RED;
		float[][] crossVertices = {{xc2, yc2}, {xc1, yc1}, {xc4, yc4},
			{xc3, yc3}, {xc3, yc1}, {xc4, yc2}, {xc6, yc5}, {xc7, yc6},
			{xc6, yc7}, {xc5, yc6}, {xc1, yc3}, {xc2, yc4}};
		RenderUtils.fillQuads2D(context, crossVertices, crossColor);
		
		// outline
		int outlineColor = 0x80101010;
		float[][] outlineVertices = {{xc1, yc1}, {xc2, yc2}, {xc6, yc5},
			{xc4, yc2}, {xc3, yc1}, {xc7, yc6}, {xc3, yc3}, {xc4, yc4},
			{xc6, yc7}, {xc2, yc4}, {xc1, yc3}, {xc5, yc6}};
		RenderUtils.drawLineStrip2D(context, outlineVertices, outlineColor);
	}
}
