# Features ported from CevAPI and Meteor

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

### Features ported from other clients (user, 2026-10-18)

User: "ANY features `../UI+Feature_Inpirations` [the CevAPI Wurst fork, MC 26.3] OR
`../Feature_Inpirations+Additions` [Meteor Client] have that beast doesn't should be added to
beast, if it has some that we also have, DO NOT REPLACE OURS WITH THEIRS". Done in batches,
each built, smoke-tested and shipped. Rules that came out of it:
- **Never bulk-copy unread code** (the auto-mode classifier blocks it): scan each batch for
  network/token/exec/file-delete use, read what's flagged, copy only the dependency closure.
- **Duplicates of Beast features are skipped, not merged**: AutoMace and AttributeSwap
  (= MaceAssist), SurfaceXray (user: "we already have xray"), HideWurst (= RecordingMode),
  PacketDelay (= Blink), GlobalToggle (only drives CevAPI's own ESP pipeline). SpearAssist
  went **into MaceAssist** as its "Spear" setting group (`hacks/maceassist/SpearAssist`;
  user: "integrate spearassist into maceassist, remember we already have lunge-swapping") -
  without its "pass" mode, which needed CevAPI's AimAssist/Flight hooks.
- CevAPI's categories Intel/Tools/Creative-Op map to Render/Other/Other (the ClickGUI bar
  has exactly ten slots).
- 26.3 -> 26.1.2: `MC.gui.setScreen/screen()` -> `MC.setScreen/screen`, SDL input ->
  GLFW (`util/SdlUtils` reimplemented), `FirstPersonHandsAndItemsRenderer` ->
  `ItemInHandRenderer` (`ViewmodelItemInHandRendererMixin`), `swing(hand, anim, false)` ->
  `swing(hand)`, `ServerboundPunchPacket` doesn't exist, `ServerboundMoveVehiclePacket` is
  a (Vec3, yRot, xRot, onGround) record, sign text uses booleans not `SignTextSlot`.
- Their shared-class additions became **additive shims**, never replacements:
  `RenderUtils` (flash colors, tracer/curve widths, octahedrons, circles, labels - drawn
  through Beast's buffers and RecordingMode), chunk-search readiness/version
  (`AbstractChunkCoordinator`), `Setting.isVisibleInGui`/`resetToDefault`, `SettingGroup`
  (Beast-styled popup row), `Hack(name, description, false)` for plain-English
  descriptions, `Hack.getStatusText()` (HackList suffix), `TimerManager` (a hack's timer
  request beats the Timer hack while it lasts), `NiceWurstModule` is a pass-through stub.
- Their hooks inside mixins Beast also has go into **new mixin classes** (RenderAdjust*,
  FunCreepers*, AntiBlast*, ContainerOverlayHooksMixin for every container-screen overlay
  and click, BlockAndItemHooksGameModeMixin, ...) or as small additions to ours.
  Netty-thread callbacks only set flags (ServerObserver -> AntiCheatDetect via
  `MC.execute`); ChestRecorder's per-snapshot `new Timer()` threads became one scheduler
  that runs on the client thread.
- Gametest `PortedFeaturesTest` (`gametest ported`) switches every ported hack on and off
  (looking at the sky), opens a chest and the inventory with ChestSearch/EnchantmentHandler
  on, and must end clean. Add each new batch to its list.
- **Nothing built to disrupt or grief servers** (user, 2026-10-18: "excluding
  server-disrupting stuff is fine"): BundleDupe (packet flood), VillagerDupe, ChatSpam,
  CommandSpam, and CevAPI's Creative/Op kit except NBTEditor (AutoCommand's default macro deops everyone and ops you) (Airstrike,
  Boom, ExplosionAura, Voider, HandOfGod, ServerKill, MultiverseAnnihilator, UUIDBan,
  ForceTP, ForceOP book/sign, the advert text/title/display/name spawners).
- Also left out: ForceAllowChats (overrides the account's Mojang/Microsoft chat, Realms and
  multiplayer flags - on a child account those are the family settings), AltSwitch/AltBot
  (park accounts as protocol bots), CevAPI's NBT preset pack (wide-range TNT/Wither
  spawners, silverfish infestation, wands, OP kits - NBTEditor's built-in list is empty,
  your own presets in `wurst/nbt-presets` still load), XPGUI's wallpaper downloads from
  wallpaperaccess.com (its theme buttons use the bundled `background.png`; a URL you type
  still loads).
- Batch F (OTFs, commands, GUIs): AltGUI and XPGUI are extra screens next to Beast's
  ClickGUI (CevAPI's "Favorites" = Beast's Important), ClientChatOverlay
  (`hud/ClientMessageOverlay`, `ClientChatOverlayMixin` on `addClientSystemMessage`;
  `ChatUtils.component` marks Beast's own lines), PerformanceOverlay (Update/Render/
  GUIRender events time each hack only while it's on), CommandPrefix
  (`CmdProcessor.getPrefix()`), PacketFirewall (in both send hooks; with vanilla-only off it
  changes nothing; can pause hacks on an anticheat detection - `Hack.setEnabled`,
  `EnabledHacksFile`, AntiCheatDetect), PacketTools (events only), Anti-Fingerprint
  (`net.cevapi.*`, `AntiFingerprintPackMixin` after vanilla's thread check), ConnectionLog
  overlay (log4j appender, drawn on the connect/disconnected screens), DiscordRPC (off by
  default; still CevAPI's Discord application ID, so Discord shows their app name),
  Presets (`presets/PresetManager`: one folder per preset holding Beast's own settings /
  enabled-hacks / keybinds / TooManyHax profiles), `.panic restore` (what the last panic
  turned off, this session), `.search <block>` (Beast's Search has no query mode),
  `.autobuild "text" <height>` (a generated template that isn't a file), `.aboveground`.
  Also wired: NbtFilter's packet hooks (`PacketDecoderMixin`, `ConnectionMixin.onSend` -
  they were missing since it was ported), CustomTotem's `customtotem/` assets, AutoClicker's
  StaffMonitor/SimulationSonar pauses.
- Still open: HideModMenu (needs ModMenu mixins against modmenu 18.0.0-alpha.8 plus a
  mixin plugin to skip them without ModMenu), the ClientChatOverlay extras that need more
  ChatScreen/tab-list hooks (command colouring, tab-list colours), AutoFly's path
  anti-hunger (an onGround rewrite at the connection level - not wired).
