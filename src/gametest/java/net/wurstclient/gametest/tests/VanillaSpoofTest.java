/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ConnectionPacketOutputListener.ConnectionPacketOutputEvent;
import net.wurstclient.gametest.WurstTest;
import net.wurstclient.other_features.VanillaSpoofOtf;
import net.wurstclient.util.ModTranslationKeys;
import net.wurstclient.util.VanillaLanguage;

public enum VanillaSpoofTest
{
	;
	
	/** Stand-in for a mod's network message on any channel. */
	private record TestPayload(Type<TestPayload> type)
		implements CustomPacketPayload
	{
		private TestPayload(String channel)
		{
			this(new Type<>(Identifier.parse(channel)));
		}
	}
	
	/**
	 * Checks which outgoing custom payloads VanillaSpoof lets through. Runs
	 * on the title screen, where there's no singleplayer server, so the
	 * filter is active.
	 */
	public static void testPacketFilter(ClientGameTestContext context)
	{
		WurstTest.LOGGER.info("Testing VanillaSpoof packet filter");
		
		context.runOnClient(mc -> {
			VanillaSpoofOtf spoof =
				WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf;
			
			// brand -> vanilla
			ConnectionPacketOutputEvent brand = send(spoof,
				new ServerboundCustomPayloadPacket(new BrandPayload("fabric")));
			if(brand.isCancelled() || !(brand
				.getPacket() instanceof ServerboundCustomPayloadPacket p
				&& p.payload() instanceof BrandPayload b
				&& b.brand().equals("vanilla")))
				throw new RuntimeException("Brand wasn't spoofed");
			
			// another mod's message -> dropped
			if(!send(spoof, new ServerboundCustomPayloadPacket(
				new TestPayload("fabric:test"))).isCancelled())
				throw new RuntimeException("Mod payload wasn't dropped");
			
			// Simple Voice Chat's message -> through, untouched
			ServerboundCustomPayloadPacket voice =
				new ServerboundCustomPayloadPacket(
					new TestPayload("voicechat:request_secret"));
			ConnectionPacketOutputEvent voiceEvent = send(spoof, voice);
			if(voiceEvent.isCancelled() || voiceEvent.getPacket() != voice)
				throw new RuntimeException("Voice chat payload was blocked");
			
			// channel list -> only voice chat's channels left
			ConnectionPacketOutputEvent list = send(spoof,
				new ServerboundCustomPayloadPacket(
					new RegistrationPayload(RegistrationPayload.REGISTER,
						List.of(Identifier.parse("voicechat:secret"),
							Identifier.parse("fabric:registry/sync"),
							Identifier.parse("c:version")))));
			if(list.isCancelled() || !(list
				.getPacket() instanceof ServerboundCustomPayloadPacket p2
				&& p2.payload() instanceof RegistrationPayload r && r.channels()
					.equals(List.of(Identifier.parse("voicechat:secret")))))
				throw new RuntimeException(
					"Channel list wasn't cut down to voice chat");
			
			// channel list without voice chat -> dropped
			if(!send(spoof,
				new ServerboundCustomPayloadPacket(
					new RegistrationPayload(RegistrationPayload.REGISTER,
						List.of(Identifier.parse("fabric:registry/sync")))))
							.isCancelled())
				throw new RuntimeException("Channel list wasn't dropped");
		});
	}
	
	private static ConnectionPacketOutputEvent send(VanillaSpoofOtf spoof,
		ServerboundCustomPayloadPacket packet)
	{
		ConnectionPacketOutputEvent event =
			new ConnectionPacketOutputEvent(packet);
		spoof.onSentConnectionPacket(event);
		return event;
	}
	
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
