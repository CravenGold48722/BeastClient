/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.util.function.Supplier;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ModTranslationKeys;

@DontBlock
@SearchTags({"vanilla spoof", "AntiFabric", "anti fabric", "LibHatesMods",
	"HackedServer", "translation key exploit", "mod detection"})
public final class VanillaSpoofOtf extends OtherFeature
	implements ConnectionPacketOutputListener
{
	private final CheckboxSetting spoof = new CheckboxSetting("Spoof Vanilla",
		"Makes servers see a plain vanilla client:\n\n"
			+ "§lBrand§r - reports \"vanilla\" instead of \"fabric\".\n\n"
			+ "§lChannels§r - drops every other custom payload, like"
			+ " Fabric's list of mod network channels. A vanilla client never"
			+ " sends any.\n\n"
			+ "§lLogin questions§r - answers every login query with"
			+ " \"not understood\", the way vanilla does.\n\n"
			+ "§lTranslation keys§r - signs and anvil names show mod"
			+ " translation keys and mod keybinds raw, like vanilla, so a"
			+ " server can't spot mods by what text you send back.\n\n"
			+ "Packets are only touched on real servers, not in singleplayer."
			+ " Servers that require Fabric mods will treat you as vanilla.",
		false);
	
	/** Depth of {@link #withVanillaTranslations(Supplier)} calls. */
	private int vanillaScopeDepth;
	
	public VanillaSpoofOtf()
	{
		super("VanillaSpoof",
			"Bypasses anti-Fabric plugins and mod detection by pretending to be"
				+ " a vanilla client.");
		addSetting(spoof);
		
		EVENTS.add(ConnectionPacketOutputListener.class, this);
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		if(!spoof.isChecked())
			return;
		
		// The integrated server is Fabric too and expects Fabric's handshake.
		if(MC.hasSingleplayerServer())
			return;
			
		// A vanilla client sends exactly one custom payload: its brand. So
		// rewrite that one and drop all the others (Fabric's "register"
		// channel list, "c:version", mod channels, ...).
		//
		// Dropping the channel list is also what keeps this from hanging the
		// connection: Fabric servers only start their own handshake for
		// clients that announced Fabric's channels. Without the list they
		// treat us as vanilla and never wait for an answer.
		if(event.getPacket() instanceof ServerboundCustomPayloadPacket packet)
		{
			if(packet.payload() instanceof BrandPayload)
				event.setPacket(new ServerboundCustomPayloadPacket(
					new BrandPayload("vanilla")));
			else
				event.cancel();
			
			return;
		}
		
		// Vanilla answers every login query with "not understood" (no
		// payload). Rewritten rather than cancelled, so whatever Fabric
		// waits on after sending the answer still runs.
		if(event
			.getPacket() instanceof ServerboundCustomQueryAnswerPacket answer
			&& answer.payload() != null)
			event.setPacket(new ServerboundCustomQueryAnswerPacket(
				answer.transactionId(), null));
	}
	
	/**
	 * Runs the given code as if only vanilla's translations existed. Text
	 * that is about to be sent back to the server (sign lines, anvil names)
	 * should be turned into a string inside this.
	 */
	public <T> T withVanillaTranslations(Supplier<T> action)
	{
		if(!spoof.isChecked())
			return action.get();
		
		Language real = Language.getInstance();
		Language.inject(new VanillaOnlyLanguage(real));
		vanillaScopeDepth++;
		
		try
		{
			return action.get();
			
		}finally
		{
			vanillaScopeDepth--;
			Language.inject(real);
		}
	}
	
	/**
	 * Whether a keybind component with this name should resolve to its raw
	 * name right now, like a keybind vanilla doesn't have.
	 */
	public boolean shouldHideKeybind(String name)
	{
		return vanillaScopeDepth > 0 && !ModTranslationKeys.isVanilla(name);
	}
	
	@Override
	public boolean isEnabled()
	{
		return spoof.isChecked();
	}
	
	@Override
	public String getPrimaryAction()
	{
		return isEnabled() ? "Disable" : "Enable";
	}
	
	@Override
	public void doPrimaryAction()
	{
		spoof.setChecked(!spoof.isChecked());
	}
	
	/**
	 * The current language with every mod-only key removed, so those keys
	 * fall back to the raw key (or the component's fallback text) exactly
	 * like they would on a vanilla client. Server resource pack translations
	 * stay, since vanilla clients have those too.
	 */
	private static final class VanillaOnlyLanguage extends Language
	{
		private final Language real;
		
		private VanillaOnlyLanguage(Language real)
		{
			this.real = real;
		}
		
		@Override
		public String getOrDefault(String key, String fallback)
		{
			if(ModTranslationKeys.isModOnly(key))
				return fallback;
			
			return real.getOrDefault(key, fallback);
		}
		
		@Override
		public boolean has(String key)
		{
			return !ModTranslationKeys.isModOnly(key) && real.has(key);
		}
		
		@Override
		public boolean isDefaultRightToLeft()
		{
			return real.isDefaultRightToLeft();
		}
		
		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text)
		{
			return real.getVisualOrder(text);
		}
	}
}
