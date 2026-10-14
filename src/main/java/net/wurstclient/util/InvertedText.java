/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;
import net.wurstclient.WurstShaderPipelines;

/**
 * Text in the opposite gray of whatever is behind each of its pixels: white
 * on black, black on white, mid gray on mid gray, never a color. Blending
 * can't do that (it can't mix the color channels into a brightness), so a
 * shader reads the pixel behind each glyph pixel straight from the main
 * framebuffer it is drawing into. Each pixel is read and written once, by
 * the same fragment; a copy of the framebuffer (copyTextureToTexture right
 * before the GUI) came out all black on 26.1.2, with no GL error.
 *
 * <p>
 * Draws the ASCII glyphs of the BeastFont sheet (the same ones
 * {@link BeastFont} uses), spaced like {@link BeastFont} spaces them.
 */
public enum InvertedText
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	private static final Identifier GLYPHS = Identifier
		.fromNamespaceAndPath("wurst", "textures/font/beast_ascii.png");
	
	/** One glyph cell of the 256x256 sheet: 16 px, drawn 8 GUI px tall. */
	private static final int CELL = 16;
	private static final float CELL_SIZE = 8;
	
	public static void draw(GuiGraphicsExtractor context, String text, float x,
		float y)
	{
		GpuSampler nearest =
			RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		TextureSetup textures = TextureSetup.doubleTexture(
			MC.getTextureManager().getTexture(GLYPHS).getTextureView(), nearest,
			MC.getMainRenderTarget().getColorTextureView(), nearest);
		
		Matrix3x2f pose = new Matrix3x2f(context.pose());
		ScreenRectangle scissor = context.scissorStack.peek();
		float cx = x;
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if(c > ' ' && c < 127)
			{
				float u = (c % 16) * CELL / 256F;
				float v = (c / 16) * CELL / 256F;
				context.guiRenderState.addGuiElement(new GlyphRenderState(
					WurstShaderPipelines.INVERTED_GRAY_TEXT, textures, pose, cx,
					y, cx + CELL_SIZE, y + CELL_SIZE, u, u + CELL / 256F, v,
					v + CELL / 256F, scissor, bounds(pose, cx, y, scissor)));
			}
			
			cx += BeastFont.get().getSplitter().stringWidth(String.valueOf(c));
		}
	}
	
	@Nullable
	private static ScreenRectangle bounds(Matrix3x2f pose, float x, float y,
		@Nullable ScreenRectangle scissor)
	{
		ScreenRectangle rect = new ScreenRectangle((int)Math.floor(x),
			(int)Math.floor(y), (int)CELL_SIZE + 1, (int)CELL_SIZE + 1)
				.transformMaxBounds(pose);
		return scissor != null ? scissor.intersection(rect) : rect;
	}
	
	private record GlyphRenderState(RenderPipeline pipeline,
		TextureSetup textureSetup, Matrix3x2f pose, float x0, float y0,
		float x1, float y1, float u0, float u1, float v0, float v1,
		@Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
		implements GuiElementRenderState
	{
		@Override
		public void buildVertices(VertexConsumer vertices)
		{
			// vanilla's corner order, the GUI pipeline culls back faces
			vertices.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0)
				.setColor(-1);
			vertices.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1)
				.setColor(-1);
			vertices.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1)
				.setColor(-1);
			vertices.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0)
				.setColor(-1);
		}
	}
}
