/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.wurstclient.WurstClient;

/**
 * The language a vanilla client would have loaded with the same resource
 * packs: vanilla's own, the player's and the server's, minus every pack a
 * mod brought in.
 *
 * <p>
 * Stricter than just hiding mod-only keys: it also catches mods that change
 * the text of vanilla keys, and translations coming from mods' built-in
 * resource packs. Built lazily and thrown away whenever resources reload.
 */
public enum VanillaLanguage
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	private static Language cached;
	private static boolean failed;
	
	/**
	 * Returns the vanilla-equivalent language, or {@code null} if it couldn't
	 * be built (callers should fall back to {@link ModTranslationKeys}).
	 */
	public static synchronized Language get()
	{
		if(cached == null && !failed)
		{
			cached = build();
			failed = cached == null;
		}
		
		return cached;
	}
	
	/** Called when resource packs or the language reload. */
	public static synchronized void invalidate()
	{
		cached = null;
		failed = false;
	}
	
	private static Language build()
	{
		try
		{
			List<PackResources> packs = MC.getResourceManager().listPacks()
				.filter(VanillaLanguage::isNonModPack).toList();
			
			// Not closed on purpose: the packs belong to the game's own
			// resource manager and stay in use there.
			MultiPackResourceManager manager =
				new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
			
			// Same language list LanguageManager builds: English as the base,
			// plus the selected language on top.
			LanguageManager languages = MC.getLanguageManager();
			String selected = languages.getSelected();
			List<String> codes = new ArrayList<>(List.of("en_us"));
			boolean rightToLeft = false;
			if(!selected.equals("en_us"))
			{
				LanguageInfo info = languages.getLanguage(selected);
				if(info != null)
				{
					codes.add(selected);
					rightToLeft = info.bidirectional();
				}
			}
			
			ClientLanguage language =
				ClientLanguage.loadFrom(manager, codes, rightToLeft);
			
			// If the vanilla pack somehow got filtered out, this would be an
			// empty language that hides everything. Better to fall back.
			if(!language.has("key.jump"))
			{
				System.err.println(
					"[VanillaSpoof] Vanilla language came out empty, falling"
						+ " back to hiding mod keys only.");
				return null;
			}
			
			return language;
			
		}catch(RuntimeException e)
		{
			System.err
				.println("[VanillaSpoof] Couldn't build vanilla language");
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * Vanilla, user, server, world and feature packs all use Minecraft's own
	 * pack sources. Fabric gives mod packs sources of its own.
	 */
	private static boolean isNonModPack(PackResources pack)
	{
		PackSource source = pack.location().source();
		return source == PackSource.DEFAULT || source == PackSource.BUILT_IN
			|| source == PackSource.FEATURE || source == PackSource.WORLD
			|| source == PackSource.SERVER;
	}
}
