/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.SharedConstants;
import net.minecraft.locale.Language;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
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
import net.wurstclient.util.VanillaLanguage;

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
			+ "§lKnown data packs§r - when joining, only claims to have the"
			+ " data packs vanilla ships with. Fabric otherwise claims every"
			+ " mod's pack too, which a server can ask about.\n\n"
			+ "Packets are only touched on real servers, not in singleplayer."
			+ " Servers that require Fabric mods will treat you as vanilla.",
		true)
	{
		@Override
		public void update()
		{
			refreshChatSigning();
		}
	};
	
	private final CheckboxSetting signChat = new CheckboxSetting(
		"Sign chat like vanilla",
		"While Spoof Vanilla is on, pauses NoChatReports' \"Disable"
			+ " signatures\" so your chat is signed and your chat key is sent"
			+ " when joining, exactly like vanilla.\n\n"
			+ "A vanilla client with a Microsoft account always does that, so"
			+ " unsigned chat is a giveaway for a modded client - but signed"
			+ " messages can be reported to Mojang. Turn this off to keep"
			+ " NoChatReports working.\n\n" + "Takes effect on the next join.",
		true)
	{
		@Override
		public void update()
		{
			refreshChatSigning();
		}
	};
	
	/** Depth of {@link #withVanillaTranslations(Supplier)} calls. */
	private int vanillaScopeDepth;
	
	/** Data packs a vanilla client knows, as vanilla itself lists them. */
	private Set<KnownPack> vanillaKnownPacks;
	
	public VanillaSpoofOtf()
	{
		super("VanillaSpoof",
			"Bypasses anti-Fabric plugins and mod detection by pretending to be"
				+ " a vanilla client.");
		addSetting(spoof);
		addSetting(signChat);
		
		EVENTS.add(ConnectionPacketOutputListener.class, this);
	}
	
	/**
	 * Whether NoChatReports should stand down so chat is signed the way a
	 * vanilla client signs it.
	 */
	public boolean shouldSignChat()
	{
		return spoof.isChecked() && signChat.isChecked();
	}
	
	private void refreshChatSigning()
	{
		// NoChatReports is created after this OTF, so it may not exist yet
		// while the settings are being loaded.
		if(WURST.getOtfs() != null && WURST.getOtfs().noChatReportsOtf != null)
			WURST.getOtfs().noChatReportsOtf.refresh();
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
		
		// Fabric swaps the client's list of trusted data packs for one that
		// includes every mod's pack (KnownPacksManagerMixin), so a server
		// that offers e.g. a Fabric API pack gets told we have it. Claim
		// only what vanilla's own trusted list contains.
		if(event.getPacket() instanceof ServerboundSelectKnownPacks known)
		{
			List<KnownPack> vanillaOnly = known.knownPacks().stream()
				.filter(this::isVanillaKnownPack).toList();
			if(vanillaOnly.size() != known.knownPacks().size())
				event.setPacket(new ServerboundSelectKnownPacks(vanillaOnly));
			
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
	
	private boolean isVanillaKnownPack(KnownPack pack)
	{
		if(vanillaKnownPacks == null)
			vanillaKnownPacks = loadVanillaKnownPacks();
		
		if(vanillaKnownPacks != null)
			return vanillaKnownPacks.contains(pack);
			
		// Fallback if vanilla's list couldn't be built: Fabric files mod
		// packs under the "minecraft" namespace too, but with the mod's
		// version instead of the game's.
		return pack.isVanilla()
			&& pack.version().equals(SharedConstants.getCurrentVersion().id());
	}
	
	/**
	 * The same list vanilla's {@code KnownPacksManager} starts from, before
	 * Fabric redirects it to the modded one.
	 */
	private static Set<KnownPack> loadVanillaKnownPacks()
	{
		try
		{
			PackRepository repo =
				ServerPacksSource.createVanillaTrustedRepository();
			repo.reload();
			
			Set<KnownPack> packs = new HashSet<>();
			for(Pack pack : repo.getAvailablePacks())
				pack.location().knownPackInfo().ifPresent(packs::add);
			
			return packs;
			
		}catch(RuntimeException e)
		{
			System.err.println("[VanillaSpoof] Couldn't list vanilla packs");
			e.printStackTrace();
			return null;
		}
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
	 * The current language as vanilla would have it ({@link VanillaLanguage}:
	 * same resource packs minus the mods'), so mod keys fall back to the raw
	 * key (or the component's fallback text) and vanilla keys a mod reworded
	 * show vanilla's text. Server and user resource pack translations stay,
	 * since vanilla clients have those too. If that can't be built, falls
	 * back to just hiding mod-only keys.
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
			// Exact: what vanilla would show with the same resource packs.
			Language vanilla = VanillaLanguage.get();
			if(vanilla != null)
				return vanilla.getOrDefault(key, fallback);
			
			// Fallback: just hide the keys only mods provide.
			if(ModTranslationKeys.isModOnly(key))
				return fallback;
			
			return real.getOrDefault(key, fallback);
		}
		
		@Override
		public boolean has(String key)
		{
			Language vanilla = VanillaLanguage.get();
			if(vanilla != null)
				return vanilla.has(key);
			
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
