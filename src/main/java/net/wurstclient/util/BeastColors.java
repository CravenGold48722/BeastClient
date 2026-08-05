/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import org.joml.Matrix3x2f;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Beast Client color palette.
 *
 * <p>
 * Every accent border and every accent letter in the UI is painted with a
 * horizontal dark-red-to-light-red gradient that scrolls from left to right
 * over time. The gradient is keyed off the <i>absolute screen X</i> of whatever
 * is being drawn rather than off each element's own width, so a single
 * continuous wave sweeps across the entire interface instead of every button
 * animating on its own.
 */
public enum BeastColors
{
	;
	
	/** The dark end of the accent gradient. */
	public static final int DARK_RED = 0xFF5A0000;
	
	/** The light end of the accent gradient. */
	public static final int LIGHT_RED = 0xFFFF3A3A;
	
	/**
	 * The color of a selected / enabled element. Deliberately lighter than
	 * {@link #LIGHT_RED} so an active feature stands out against the gradient
	 * around it.
	 */
	public static final int SELECTED_RED = 0xFFFF9B9B;
	
	/**
	 * The hovered variant of {@link #SELECTED_RED}.
	 */
	public static final int SELECTED_RED_HOVER = 0xFFFFC4C4;
	
	/**
	 * The fill behind a selected / enabled element.
	 */
	public static final int SELECTED_FILL = 0xFFC22B2B;
	
	/**
	 * Glyph color for the pin knob, checkmarks and collapse arrows.
	 *
	 * <p>
	 * These sit on dark backgrounds, so black is deliberately low-contrast
	 * here. The pin stays legible because its needle is drawn in white on top
	 * of the black knob.
	 */
	public static final int ICON_BLACK = 0xFF000000;
	
	/** Accent red used for affordances like close and collapse. */
	public static final int ACCENT_RED = 0xFFD93A3A;
	
	/** The hovered variant of {@link #ACCENT_RED}. */
	public static final int ACCENT_RED_HOVER = 0xFFFF6B6B;
	
	/**
	 * The red used for the word "Beast" in every chat tag.
	 *
	 * <p>
	 * Shared by {@link ChatUtils} and {@link ToggleAnnouncer} so the two tags
	 * can't drift to different shades of red.
	 */
	public static final int BRAND_RED = 0xFF3A3A;
	
	/** The gray used for the brackets around the "Beast" tag. */
	public static final int BRACKET_GRAY = 0x8A8A8E;
	
	/** Neutral panel background for the flat, modern look. */
	public static final int PANEL_BG = 0xFF0E0E10;
	
	/** Slightly raised background, used for rows and input wells. */
	public static final int PANEL_BG_LIGHT = 0xFF17171A;
	
	/** Primary label color. */
	public static final int TEXT = 0xFFE6E6E6;
	
	/** Muted label color for secondary information. */
	public static final int TEXT_MUTED = 0xFF9A9A9E;
	
	/**
	 * How many pixels one full dark-light-dark cycle of the gradient spans.
	 */
	private static final float CYCLE_PIXELS = 320F;
	
	/** How fast the gradient scrolls to the right, in pixels per second. */
	private static final float SCROLL_PIXELS_PER_SECOND = 110F;
	
	private static final long START_TIME = System.nanoTime();
	
	/**
	 * Returns how far along the dark-to-light ramp the gradient is at the given
	 * absolute screen X, as a value from 0 (darkest) to 1 (lightest).
	 *
	 * <p>
	 * The wave is a triangle rather than a sawtooth so that it reads as one
	 * continuous moving sheen. A sawtooth would snap from light back to dark
	 * and leave a hard seam every cycle.
	 */
	public static float rampAt(float screenX)
	{
		float seconds = (System.nanoTime() - START_TIME) / 1_000_000_000F;
		float scrolled = screenX - seconds * SCROLL_PIXELS_PER_SECOND;
		
		float u = scrolled / CYCLE_PIXELS;
		u -= Math.floor(u);
		
		// triangle wave: 0 -> 1 over the first half, 1 -> 0 over the second
		return u < 0.5F ? u * 2F : (1F - u) * 2F;
	}
	
	/**
	 * Returns the accent gradient color at the given absolute screen X, at full
	 * opacity.
	 */
	public static int gradientAt(float screenX)
	{
		return gradientAt(screenX, 1F);
	}
	
	/**
	 * Returns the accent gradient color at the given absolute screen X, scaled
	 * to the given opacity.
	 */
	public static int gradientAt(float screenX, float opacity)
	{
		return withOpacity(lerpColor(DARK_RED, LIGHT_RED, rampAt(screenX)),
			opacity);
	}
	
	/**
	 * Linearly interpolates between two ARGB colors. {@code t} is clamped to
	 * the 0-1 range.
	 */
	public static int lerpColor(int from, int to, float t)
	{
		float f = Math.max(0F, Math.min(1F, t));
		
		int a = lerpChannel(from >>> 24, to >>> 24, f);
		int r = lerpChannel(from >> 16 & 0xFF, to >> 16 & 0xFF, f);
		int g = lerpChannel(from >> 8 & 0xFF, to >> 8 & 0xFF, f);
		int b = lerpChannel(from & 0xFF, to & 0xFF, f);
		
		return a << 24 | r << 16 | g << 8 | b;
	}
	
	private static int lerpChannel(int from, int to, float t)
	{
		return Math.round(from + (to - from) * t);
	}
	
	/**
	 * Multiplies the alpha channel of the given ARGB color by the given
	 * opacity.
	 */
	public static int withOpacity(int color, float opacity)
	{
		float clamped = Math.max(0F, Math.min(1F, opacity));
		int alpha = Math.round((color >>> 24) * clamped);
		return alpha << 24 | color & 0x00FFFFFF;
	}
	
	/**
	 * Converts an X coordinate in the current pose space into an absolute
	 * screen X, so that the gradient stays continuous across windows and
	 * scrolled content that render under a translated matrix.
	 */
	public static float toScreenX(GuiGraphicsExtractor context, float localX,
		float localY)
	{
		Matrix3x2f pose = context.pose();
		return localX * pose.m00() + localY * pose.m10() + pose.m20();
	}
}
