/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * The anticheat servers this run tests against, in order - and the first one
 * started as soon as the game process launches (Fabric's preLaunch, before
 * Minecraft even loads), so it boots while the client does instead of
 * after it: that wait used to be 10-30 seconds of an idle title screen.
 *
 * <p>
 * Loads no game classes: this runs before the game is set up.
 */
public final class AnticheatServers implements PreLaunchEntrypoint
{
	private static List<AnticheatServer> servers;
	
	@Override
	public void onPreLaunch()
	{
		List<AnticheatServer> wanted = get();
		if(wanted.isEmpty())
			return;
		
		LoggerFactory.getLogger("Wurst Test")
			.info("Starting the {} server early", wanted.get(0).getName());
		wanted.get(0).start();
	}
	
	/** The servers this run wants, in the order they're tested. */
	public static synchronized List<AnticheatServer> get()
	{
		if(servers != null)
			return servers;
		
		List<String> only = Arrays
			.stream(System.getProperty("wurst.test.only", "")
				.toLowerCase(Locale.ROOT).split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).toList();
		
		servers = new ArrayList<>();
		for(AnticheatServer server : new AnticheatServer[]{
			AnticheatServer.grim(), AnticheatServer.intave()})
		{
			if(server == null)
				continue;
			String test =
				("AnticheatTest" + server.getName()).toLowerCase(Locale.ROOT);
			if(only.isEmpty() || only.stream().anyMatch(test::contains))
				servers.add(server);
		}
		return servers;
	}
}
