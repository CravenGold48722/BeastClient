/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.google.gson.JsonArray;

import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonArray;

/**
 * {@code important-hacks.json}: the names of the hacks marked as Important.
 */
public final class ImportantHacksFile
{
	private final Path path;
	
	public ImportantHacksFile(Path path)
	{
		this.path = path;
	}
	
	public void load(HackList hax)
	{
		try
		{
			WsonArray wson = JsonUtils.parseFileToArray(path);
			for(String name : wson.getAllStrings())
			{
				Hack hack = hax.getHackByName(name);
				if(hack != null)
					hack.loadImportant(true);
			}
			
		}catch(NoSuchFileException e)
		{
			// nothing marked yet
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load " + path.getFileName());
			e.printStackTrace();
		}
	}
	
	public void save(HackList hax)
	{
		JsonArray json = new JsonArray();
		for(Hack hack : hax.getAllHax())
			if(hack.isImportant())
				json.add(hack.getName());
			
		try
		{
			JsonUtils.toJson(json, path);
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't save " + path.getFileName());
			e.printStackTrace();
		}
	}
}
