/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.WurstTest;
import net.wurstclient.other_features.VanillaSpoofOtf;
import net.wurstclient.util.ModTranslationKeys;
import net.wurstclient.util.VanillaLanguage;

public enum VanillaSpoofTest
{
	;
	
	/**
	 * Checks the translation-key spoof: inside
	 * {@link VanillaSpoofOtf#withVanillaTranslations}, a key only a mod
	 * provides must come back raw, like on a vanilla client, while vanilla
	 * keys still translate.
	 */
	public static void testTranslationSpoof(ClientGameTestContext context)
	{
		WurstTest.LOGGER.info("Testing VanillaSpoof translations");
		
		context.runOnClient(mc -> {
			VanillaSpoofOtf spoof =
				WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf;
			if(!spoof.isEnabled())
				throw new RuntimeException("Spoof Vanilla should be on");
				
			// The exact, pack-based language must build - otherwise it
			// silently falls back to the cruder mod-key list.
			Language vanilla = VanillaLanguage.get();
			if(vanilla == null)
				throw new RuntimeException("Vanilla language didn't build");
			
			String jump = spoof.withVanillaTranslations(
				() -> Component.translatable("key.jump").getString());
			if(jump.equals("key.jump"))
				throw new RuntimeException("Vanilla key didn't translate");
			
			String modKey = ModTranslationKeys
				.findModOnlyKey(key -> Language.getInstance().has(key)
					&& !Language.getInstance().getOrDefault(key).equals(key))
				.orElseThrow(() -> new RuntimeException(
					"No mod-only translation key to test with"));
			
			String normal = Component.translatable(modKey).getString();
			if(normal.equals(modKey))
				throw new RuntimeException(
					"Mod key " + modKey + " doesn't translate normally");
			
			String spoofed = spoof.withVanillaTranslations(
				() -> Component.translatable(modKey).getString());
			if(!spoofed.equals(modKey))
				throw new RuntimeException("Mod key " + modKey
					+ " translated to \"" + spoofed + "\" while spoofing");
			
			// and back to normal afterwards
			if(!Component.translatable(modKey).getString().equals(normal))
				throw new RuntimeException("Language wasn't restored");
			
			WurstTest.LOGGER.info("VanillaSpoof hid mod key {}", modKey);
		});
	}
}
