/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.locale.Language;

/**
 * Knows which translation keys exist only because a mod is installed.
 *
 * <p>
 * Servers can put such a key on a sign or an item name and see what comes
 * back: a vanilla client has no translation for it and sends the raw key,
 * a modded client sends the translated text. Comparing against the keys in
 * the vanilla language file tells the two apart.
 */
public enum ModTranslationKeys
{
	;
	
	private static Set<String> vanillaKeys;
	private static Set<String> modOnlyKeys;
	
	/** Whether vanilla Minecraft has a translation for this key. */
	public static synchronized boolean isVanilla(String key)
	{
		load();
		return vanillaKeys.contains(key);
	}
	
	/** Whether only a mod (not vanilla) provides a translation for this key. */
	public static synchronized boolean isModOnly(String key)
	{
		load();
		return modOnlyKeys.contains(key);
	}
	
	/** A key only a mod provides that matches the filter. Used by the tests. */
	public static synchronized Optional<String> findModOnlyKey(
		Predicate<String> filter)
	{
		load();
		return modOnlyKeys.stream().filter(filter).findAny();
	}
	
	private static void load()
	{
		if(modOnlyKeys != null)
			return;
		
		vanillaKeys = new HashSet<>();
		Set<String> modKeys = new HashSet<>();
		
		for(ModContainer mod : FabricLoader.getInstance().getAllMods())
		{
			String id = mod.getMetadata().getId();
			if(id.equals("java"))
				continue;
			
			Set<String> target = id.equals("minecraft") ? vanillaKeys : modKeys;
			for(Path root : mod.getRootPaths())
				collectLangKeys(root.resolve("assets"), target);
		}
		
		modKeys.removeAll(vanillaKeys);
		modOnlyKeys = modKeys;
	}
	
	/** Reads every assets/&lt;namespace&gt;/lang/*.json under the folder. */
	private static void collectLangKeys(Path assets, Set<String> keys)
	{
		if(!Files.isDirectory(assets))
			return;
		
		try(Stream<Path> namespaces = Files.list(assets))
		{
			for(Path namespace : namespaces.toList())
			{
				Path lang = namespace.resolve("lang");
				if(!Files.isDirectory(lang))
					continue;
				
				try(Stream<Path> files = Files.list(lang))
				{
					for(Path file : files.toList())
						// deprecated.json is vanilla's list of renamed keys,
						// not
						// a translation file
						if(file.toString().endsWith(".json") && !file
							.getFileName().toString().equals("deprecated.json"))
							readKeys(file, keys);
				}
			}
			
		}catch(IOException e)
		{
			System.err.println("[VanillaSpoof] Couldn't list " + assets);
			e.printStackTrace();
		}
	}
	
	private static void readKeys(Path file, Set<String> keys)
	{
		try(InputStream in = Files.newInputStream(file))
		{
			Language.loadFromJson(in, (key, value) -> keys.add(key));
			
		}catch(Exception e)
		{
			System.err.println("[VanillaSpoof] Couldn't read " + file);
			e.printStackTrace();
		}
	}
}
