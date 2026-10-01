/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.wurstclient.hack.Hack;
import net.wurstclient.hack.HackList;

/**
 * One-time move of existing installs onto the "actually do it" defaults.
 *
 * <p>
 * settings.json stores every setting, defaults included, so changing a
 * default in code never reaches anyone who has run the client before. This
 * moves each listed setting to its new default, but only if it still holds
 * the old default - a value the user picked on purpose is left alone.
 */
public enum LegitDefaultsMigration
{
	;
	
	private static final String MARKER_FILE = "legit-defaults-v1";
	
	/** Hack name, setting name, old default that gets replaced. */
	private static final String[][] OLD_DEFAULTS = {
		{"AnchorAura", "Face target", "Off"},
		{"CrystalAura", "Face target", "Off"},
		{"AutoBuild", "Face target", "Server-side"},
		{"AutoBuild", "Swing hand", "Server-side"},
		{"AutoLibrarian", "Face target", "Server-side"},
		{"AutoLibrarian", "Swing hand", "Server-side"},
		{"BuildRandom", "Face target", "Server-side"},
		{"BuildRandom", "Swing hand", "Server-side"},
		{"BonemealAura", "Face target", "Server-side"},
		{"AutoFarm", "Face target", "Server-side"},
		{"AutoFarm", "Swing hand", "Server-side"},
		{"TreeBot", "Face target", "Server-side"},
		{"TreeBot", "Swing hand", "Server-side"},
		{"VeinMiner", "Swing hand", "Server-side"},
		{"Nuker", "Swing hand", "Server-side"},
		{"SpeedNuker", "Swing hand", "Off"}, {"Criticals", "Mode", "Packet"},
		{"FastBreak", "Legit mode", "false"},
		{"BowAimbot", "Silent aim", "true"},
		{"ExtraElytra", "Stop flying in water", "true"},
		{"Step", "Mode", "Legit"}, {"Excavator", "Mode", "Fast"}};
	
	public static void run(Path wurstFolder, HackList hax)
	{
		Path marker = wurstFolder.resolve(MARKER_FILE);
		if(Files.exists(marker))
			return;
		
		for(String[] entry : OLD_DEFAULTS)
		{
			Hack hack = hax.getHackByName(entry[0]);
			if(hack == null)
				continue;
			
			Setting setting = hack.getSettings().get(entry[1].toLowerCase());
			if(setting == null)
				continue;
			
			if(!setting.toJson().getAsString().equalsIgnoreCase(entry[2]))
				continue;
			
			resetToDefault(setting);
		}
		
		try
		{
			Files.createFile(marker);
			
		}catch(IOException e)
		{
			System.out.println("Couldn't create " + marker);
			e.printStackTrace();
		}
	}
	
	private static void resetToDefault(Setting setting)
	{
		if(setting instanceof EnumSetting<?> enumSetting)
			enumSetting
				.setSelected(enumSetting.getDefaultSelected().toString());
		else if(setting instanceof CheckboxSetting checkbox)
			checkbox.setChecked(checkbox.isCheckedByDefault());
	}
}
