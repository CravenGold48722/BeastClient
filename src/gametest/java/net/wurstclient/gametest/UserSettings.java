/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.Setting;

/**
 * The user's own settings, so the tests run AimAssist the way it's really
 * played (user, 2026-10-16: "change all the testings to match my actual
 * ones"). A copy of {@code .minecraft/wurst/settings.json} from that day -
 * copied, not read at test time, so a test run never depends on (or
 * touches) the real config. Tests override only what they must on top, and
 * say why.
 */
public enum UserSettings
{
	;
	
	private static final String AIM_ASSIST = """
		{
			"Range": 15.0,
			"FOV": 190.0,
			"Aim at": "Center",
			"Stickiness": 0.75,
			"Face target": "Client-side",
			"Smooth aim": true,
			"Humanize smooth aim": true,
			"Smooth aim speed": 2010.0,
			"Smooth aim distance": 4.0,
			"Switch target key": "key.keyboard.tab",
			"Attacker memory": 3.0,
			"Check line of sight": true,
			"Aim while blocking": true,
			"Auto attack": true,
			"Auto combo": true,
			"Jump reset": true,
			"Fly-outs": true,
			"Sprint crits": true,
			"Hop crits": true,
			"Uppercuts": true,
			"Lunges": true,
			"Double crits": false,
			"Hops": true,
			"Baits": true,
			"Sprint sweeps": true,
			"Weak hits": false,
			"Punish openings": true,
			"Shield breaker": true,
			"Crouch dodge": true,
			"Combo strafe": true,
			"Shield block": true,
			"Aura-Farming": false,
			"Dodging": false,
			"Min dodge distance": 3.25,
			"Max dodge distance": 6.25,
			"Filter players": false,
			"Filter sleeping": true,
			"Filter flying": 1.0,
			"Filter speed": 35.0,
			"Filter hostile mobs": false,
			"Filter neutral mobs": "On",
			"Filter passive mobs": false,
			"Filter passive water mobs": true,
			"Filter babies": true,
			"Filter bats": true,
			"Filter slimes": true,
			"Filter pets": true,
			"Filter villagers": true,
			"Filter zombie villagers": true,
			"Filter golems": true,
			"Filter piglins": "Off",
			"Filter zombie piglins": "Off",
			"Filter endermen": "Off",
			"Filter shulkers": true,
			"Filter invisible": true,
			"Filter named": false,
			"Filter shulker bullets": true,
			"Filter armor stands": true,
			"Filter end crystals": true,
			"Play music": false,
			"Music file": "sample.wav",
			"Volume": 50.0,
			"Loop music": true,
			"Play when": "While enabled"
		}
		""";
	
	/**
	 * Puts the user's AimAssist settings in place and returns what was there
	 * before, for {@link #restore}. Client thread only.
	 */
	public static Map<Setting, JsonElement> applyAimAssist()
	{
		return apply("AimAssist", AIM_ASSIST);
	}
	
	/** All of the user's AimAssist settings, by name. */
	public static JsonObject aimAssist()
	{
		return JsonParser.parseString(AIM_ASSIST).getAsJsonObject();
	}
	
	private static Map<Setting, JsonElement> apply(String feature, String json)
	{
		Feature f = WurstClient.INSTANCE.getFeatureByName(feature);
		Map<Setting, JsonElement> before = new LinkedHashMap<>();
		JsonObject values = JsonParser.parseString(json).getAsJsonObject();
		for(Map.Entry<String, JsonElement> e : values.entrySet())
		{
			Setting s =
				f.getSettings().get(e.getKey().toLowerCase(Locale.ROOT));
			if(s == null)
				throw new IllegalStateException(
					"No setting " + feature + " " + e.getKey());
			before.put(s, s.toJson());
			s.fromJson(e.getValue());
		}
		return before;
	}
	
	/** Puts back what {@link #applyAimAssist} replaced. Client thread only. */
	public static void restore(Map<Setting, JsonElement> before)
	{
		before.forEach(Setting::fromJson);
	}
}
