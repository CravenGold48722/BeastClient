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
import java.util.function.Function;

import net.wurstclient.Feature;

/**
 * One-time moves of existing installs onto new defaults.
 *
 * <p>
 * settings.json stores every setting, defaults included, so changing a
 * default in code never reaches anyone who has run the client before. Each
 * batch moves its listed settings to their new defaults once, but only if
 * they still hold the old default - a value the user picked on purpose is
 * left alone. A batch is marked as done with an empty file named after it.
 */
public enum LegitDefaultsMigration
{
	;
	
	/** Feature name, setting name, old default that gets replaced. */
	private static final Batch[] BATCHES = {
		// do things for real instead of faking packets
		new Batch("legit-defaults-v1",
			new String[][]{{"AnchorAura", "Face target", "Off"},
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
				{"SpeedNuker", "Swing hand", "Off"},
				{"Criticals", "Mode", "Packet"},
				{"FastBreak", "Legit mode", "false"},
				{"BowAimbot", "Silent aim", "true"},
				{"ExtraElytra", "Stop flying in water", "true"},
				{"Step", "Mode", "Legit"}, {"Excavator", "Mode", "Fast"}}),
		
		// Spoof Vanilla on by default
		new Batch("legit-defaults-v2",
			new String[][]{{"VanillaSpoof", "Spoof Vanilla", "false"}})};
	
	public static void run(Path wurstFolder,
		Function<String, Feature> featureByName)
	{
		for(Batch batch : BATCHES)
			batch.run(wurstFolder, featureByName);
	}
	
	private static void resetToDefault(Setting setting)
	{
		if(setting instanceof EnumSetting<?> enumSetting)
			enumSetting
				.setSelected(enumSetting.getDefaultSelected().toString());
		else if(setting instanceof CheckboxSetting checkbox)
			checkbox.setChecked(checkbox.isCheckedByDefault());
	}
	
	private record Batch(String markerFile, String[][] oldDefaults)
	{
		private void run(Path wurstFolder,
			Function<String, Feature> featureByName)
		{
			Path marker = wurstFolder.resolve(markerFile);
			if(Files.exists(marker))
				return;
			
			for(String[] entry : oldDefaults)
			{
				Feature feature = featureByName.apply(entry[0]);
				if(feature == null)
					continue;
				
				Setting setting =
					feature.getSettings().get(entry[1].toLowerCase());
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
	}
}
