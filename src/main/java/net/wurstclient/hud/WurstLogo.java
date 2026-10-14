/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.WurstLogoOtf;
import net.wurstclient.util.InvertedText;

public final class WurstLogo
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Identifier LOGO_TEXTURE =
		Identifier.fromNamespaceAndPath("wurst", "beast_128.png");
	
	public void render(GuiGraphicsExtractor context)
	{
		WurstLogoOtf otf = WURST.getOtfs().wurstLogoOtf;
		if(!otf.isVisible())
			return;
		
		String version = getVersionString();
		// version string: no background, the opposite gray of whatever is
		// behind it, so it reads on any background
		InvertedText.draw(context, version, 94, 16);
		
		// Wurst logo
		context.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, 0, 2, 0, 0, 92,
			36, 92, 36);
	}
	
	private String getVersionString()
	{
		String version = "v" + WurstClient.VERSION;
		version += " MC" + WurstClient.MC_VERSION;
		
		if(WURST.getUpdater().isOutdated())
			version += " (outdated)";
		
		return version;
	}
}
