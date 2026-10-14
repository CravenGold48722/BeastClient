/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;

/**
 * The font of Beast's own UI (ClickGUI, Navigator, TabGUI, HackList, logo).
 *
 * <p>
 * The glyphs are defined in {@code assets/wurst/font/beast.json} (ASCII from
 * the BeastFont pack, everything else falls through to vanilla's glyphs).
 * This is a second {@link Font} on top of vanilla's glyph cache that draws
 * unstyled text in that font, so the rest of the game - chat, signs, vanilla
 * screens - keeps the normal one, and resource reloads carry over by
 * themselves.
 */
public enum BeastFont
{
	;
	
	public static final FontDescription DESCRIPTION =
		new FontDescription.Resource(
			Identifier.fromNamespaceAndPath("wurst", "beast"));
	
	private static Font font;
	
	public static Font get()
	{
		if(font == null)
			font = new Font(new BeastProvider(WurstClient.MC.font.provider));
		
		return font;
	}
	
	private record BeastProvider(Font.Provider vanilla) implements Font.Provider
	{
		@Override
		public GlyphSource glyphs(FontDescription description)
		{
			// text that asks for a specific font (e.g. the enchanting table's)
			// keeps it, only the default one is swapped
			if(FontDescription.DEFAULT.equals(description))
				return vanilla.glyphs(DESCRIPTION);
			
			return vanilla.glyphs(description);
		}
		
		@Override
		public EffectGlyph effect()
		{
			return vanilla.effect();
		}
	}
}
