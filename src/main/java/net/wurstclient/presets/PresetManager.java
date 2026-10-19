/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.presets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.wurstclient.WurstClient;
import net.wurstclient.util.json.JsonException;

/**
 * Full presets (ported from the CevAPI fork): one folder per preset in
 * {@code .minecraft/wurst/presets/}, holding a settings, enabled-hacks,
 * keybinds and TooManyHax profile. Saving and loading go through Beast's own
 * profile code for each of them, so a preset is the same as saving/loading
 * those four profiles by hand.
 */
public final class PresetManager
{
	private static final String SETTINGS = "settings.json";
	private static final String ENABLED_HACKS = "enabled-hacks.json";
	private static final String KEYBINDS = "keybinds.json";
	private static final String TOO_MANY_HAX = "toomanyhax.json";
	
	private final WurstClient wurst;
	private final Path presetsFolder;
	
	public PresetManager(WurstClient wurst, Path wurstFolder)
	{
		this.wurst = wurst;
		presetsFolder = wurstFolder.resolve("presets");
	}
	
	public Path getPresetsFolder()
	{
		return presetsFolder;
	}
	
	public ArrayList<Path> listPresets()
	{
		if(!Files.isDirectory(presetsFolder))
			return new ArrayList<>();
		
		try(Stream<Path> files = Files.list(presetsFolder))
		{
			return files.filter(Files::isDirectory).sorted()
				.collect(Collectors.toCollection(ArrayList::new));
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	public void savePreset(String name) throws IOException
	{
		Path folder = getPresetFolder(name);
		Files.createDirectories(folder);
		
		try
		{
			wurst.saveSettingsProfile(abs(folder, SETTINGS));
			wurst.getHax().saveProfile(abs(folder, ENABLED_HACKS));
			wurst.getKeybinds().saveProfile(abs(folder, KEYBINDS));
			wurst.getHax().tooManyHaxHack
				.saveProfile(abs(folder, TOO_MANY_HAX));
			
		}catch(JsonException e)
		{
			throw new IOException(e);
		}
	}
	
	public void loadPreset(String name) throws IOException
	{
		Path folder = getPresetFolder(name);
		if(!Files.isDirectory(folder))
			throw new NoSuchFileException(name);
		
		try
		{
			if(Files.exists(folder.resolve(SETTINGS)))
				wurst.loadSettingsProfile(abs(folder, SETTINGS));
			if(Files.exists(folder.resolve(ENABLED_HACKS)))
				wurst.getHax().loadProfile(abs(folder, ENABLED_HACKS));
			if(Files.exists(folder.resolve(KEYBINDS)))
				wurst.getKeybinds().loadProfile(abs(folder, KEYBINDS));
			if(Files.exists(folder.resolve(TOO_MANY_HAX)))
				wurst.getHax().tooManyHaxHack
					.loadProfile(abs(folder, TOO_MANY_HAX));
			
		}catch(JsonException e)
		{
			throw new IOException(e);
		}
	}
	
	public void deletePreset(String name) throws IOException
	{
		Path folder = getPresetFolder(name);
		if(!Files.isDirectory(folder))
			throw new NoSuchFileException(name);
		
		try(Stream<Path> walk = Files.walk(folder))
		{
			List<Path> paths = walk
				.sorted(Comparator.comparingInt(Path::getNameCount).reversed())
				.collect(Collectors.toList());
			for(Path path : paths)
				Files.deleteIfExists(path);
		}
	}
	
	private Path getPresetFolder(String name) throws IOException
	{
		Path folder = presetsFolder.resolve(name).normalize();
		if(name.isBlank() || !folder.getParent().equals(presetsFolder))
			throw new IOException("Invalid preset name: " + name);
		
		return folder;
	}
	
	/**
	 * The profile methods resolve a file name against their own profiles
	 * folder; an absolute path resolves to itself.
	 */
	private static String abs(Path folder, String file)
	{
		return folder.resolve(file).toAbsolutePath().toString();
	}
}
