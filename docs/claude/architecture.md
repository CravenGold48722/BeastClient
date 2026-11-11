# Beast Client - code architecture reference

<!-- Moved out of CLAUDE.md (2026-10-30) so it isn't loaded every session; read it when the task needs it. -->

## Runtime layout

```
net.wurstclient
├── WurstInitializer          Fabric "main" entrypoint → WurstClient.INSTANCE.initialize()
├── WurstClient               enum singleton; owns every subsystem; MC / IMC statics
├── Feature                   base of Hack, Command, OtherFeature — settings + keybinds
├── Category                  BLOCKS MOVEMENT COMBAT RENDER CHAT FUN ITEMS OTHER
├── SearchTags / DontBlock    annotations (search aliases; exempt from TooManyHax)
├── RotationFaker             server-side (packet) vs client-side aiming
├── FriendsList, TooManyHaxFile, WurstTranslator, InputFaker
├── event/      Event, Listener, EventManager, CancellableEvent
├── events/     32 listener interfaces (the public event API)
├── hack/       Hack, HackList, EnabledHacksFile, @DontSaveState
├── hacks/      244 files — 180+ hacks plus 13 sub-packages
├── command/    Command, CmdList, CmdProcessor, CmdError/CmdSyntaxError
├── commands/   52 chat commands (.t, .goto, .bind, …)
├── other_feature/ + other_features/   19 "OtherFeatures" (non-toggleable options)
├── settings/   58 setting types + SettingsFile + filters/ filterlists/
├── clickgui/   ClickGui, Window, Component, components/, screens/
├── hud/        IngameHUD, HackListHUD, TabGui, WurstLogo
├── navigator/  the old searchable menu + PreferencesFile
├── keybinds/   Keybind, KeybindList, KeybindProcessor, KeybindsFile, PossibleKeybind
├── altmanager/ alt list, encryption, login screens
├── mixin/      72 mixins (+ freecam/, sodium/, xray/ sub-packages)
├── mixinterface/ IMinecraftClient, ILocalPlayer, IMultiPlayerGameMode, IKeyMapping, ISimpleOption, IChatComponent
├── ai/         PathFinder, PathProcessor, WalkPathProcessor, FlyPathProcessor, PathQueue
├── util/       49 helper classes (see below)
├── analytics/  PlausibleAnalytics (pageview pings)
├── update/     WurstUpdater, ProblematicResourcePackDetector
├── serverfinder/, nochatreports/, options/ (Wurst Options + keybind manager screens)
```

### Startup order (`WurstClient.initialize()`)

1. `MC` / `IMC` statics, create `.minecraft/wurst/` folder.
2. `PlausibleAnalytics` (`analytics.json`) → pageview `/`.
3. `EventManager`.
4. `HackList` (`enabled-hacks.json`) — **reflects over its own `public final …Hack` fields**, keying
   a `TreeMap` by `hack.getName()`. Adding a hack = adding one field; nothing else registers it.
5. `CmdList`, `OtfList`.
6. `SettingsFile` (`settings.json`, profiles in `wurst/settings/`) → `load()`, then TooManyHax's
   blocked-hacks file.
7. `KeybindList` (`keybinds.json`), `ClickGui` (`windows.json`), `Navigator` (`preferences.json`),
   `FriendsList` (`friends.json`).
8. `WurstTranslator`, `CmdProcessor` (hooked to `ChatOutputListener`), `KeybindProcessor`
   (`KeyPressListener` + `MouseButtonPressListener`), `IngameHUD` (`GUIRenderListener`),
   `RotationFaker` (`PreMotionListener` + `PostMotionListener`), `WurstUpdater` (`UpdateListener`),
   `ProblematicResourcePackDetector`, `AltManager` (`alts.encrypted_json`).

Enabled hacks are restored on the **first `onUpdate()`** (HackList registers itself as an
UpdateListener, loads the file, then removes itself), not during init.

## Event system

`EventManager` holds `Map<Class<? extends Listener>, ArrayList<Listener>>`. `EventManager.fire(e)`
is a static that no-ops if the manager is null or `wurst.isEnabled()` is false; it copies the
listener list, drops nulls (remove() nulls entries first, so concurrent fire/remove is safe), and
rethrows failures as a `ReportedException` with a crash-report category.

A hack subscribes in `onEnable()` and **must** unsubscribe in `onDisable()`:

```java
EVENTS.add(UpdateListener.class, this);
EVENTS.remove(UpdateListener.class, this);
```

`addPriority(...)` inserts before existing listeners. Events extending `CancellableEvent` stop
propagating once cancelled.

**Threads (each of these has caused a real crash or disconnect):**
- `PacketInputListener` runs on the **netty thread**, and any exception there is rethrown and
  disconnects you. Read `MC.player` etc. into a local once, compare entity IDs instead of
  `level.getEntity()`, and only set flags/volatiles — do the work in the next `onUpdate`.
- Never touch chat, screens, widgets, hack state (`setEnabled`) or the connection from a
  background thread: hand it over with `MC.execute(...)` (or `MC.submit(...).join()` if the thread
  needs the result). `ChatUtils.component/sendAsPlayer` already do this themselves.
- `IdentityHashMap` iterator entries die on `itr.remove()` — read the key first, or iterate a copy.

### Which mixin fires what

| Event | Fired from |
|---|---|
| `UpdateEvent`, `PreMotion`, `PostMotion`, `PlayerMove`, `AirStrafingSpeed`, `IsPlayerInWater`, `Knockback` | `LocalPlayerMixin` (`tick`, `sendPosition` HEAD/TAIL, `move`, `aiStep`) |
| `LeftClickEvent`, `RightClickEvent`, `HandleInputEvent`, `HandleBlockBreakingEvent` | `MinecraftMixin` (`startAttack`, `startUseItem`, `tick`, `continueAttack`) |
| `PlayerAttacksEntityEvent`, `BlockBreakingProgressEvent`, `StopUsingItemEvent` | `MultiPlayerGameModeMixin` |
| `RenderEvent` | `LevelRendererMixin` (world render, gives `PoseStack` + `partialTicks`) |
| `GUIRenderEvent` | `GuiMixin` |
| `KeyPressEvent` / `KeyEvent` | `KeyboardHandlerMixin` / `KeyMappingMixin` |
| `MouseButtonPress`, `MouseScroll`, `MouseUpdate` | `MouseHandlerMixin` |
| `ChatInputEvent` / `ChatOutputEvent` | `ChatComponentMixin` / `ChatScreenMixin` |
| `PacketInput` / `PacketOutput` / `ConnectionPacketOutput` | `ConnectionMixin`, `ClientCommonPacketListenerImplMixin` |
| `DeathEvent` | `DeathScreenMixin` |
| `CameraTransformViewBobbingEvent` | `GameRendererMixin` |
| `IsNormalCubeEvent`, `CactusCollisionShapeEvent`, `VisGraphEvent`, `VelocityFrom*` | `BlockStateBaseMixin`, `CactusBlockMixin`, `SectionOcclusionGraphMixin`, `EntityMixin` |

`MinecraftMixin` also fakes the session (`getUser`, `getGameProfile`, `getProfileKeyPairManager`)
for AltManager/NoChatReports and force-disables telemetry.

`mixinterface/` exposes mixin-added methods on vanilla classes:
`IMinecraftClient.getInteractionManager()/getPlayer()/getWurstSession()`,
`IMultiPlayerGameMode.windowClick_PICKUP/QUICK_MOVE/THROW/SWAP`, `rightClickItem/Block`,
`sendPlayerActionC2SPacket`; `IKeyMapping.isActuallyDown()/simulatePress()/setDown()`;
`ILocalPlayer.isTouchingWaterBypass()`.

`wurst.accesswidener` opens `Minecraft.startUseItem`, `Minecraft.rightClickDelay`,
`KeyboardHandler.keyPress`, `MouseHandler.onButton`, `GameRenderer.setPostEffect`,
`AbstractContainerScreen.slotClicked`, `Player.canGlide`, GUI scissor/render-state internals, etc.
If a vanilla member is private and you need it, add a line here rather than writing a new mixin.

## Anatomy of a hack

```java
@SearchTags({"alias one", "alias two"})          // optional; ClickGUI/Navigator search
public final class ExampleHack extends Hack implements UpdateListener
{
    private final CheckboxSetting foo = new CheckboxSetting("Foo", "tooltip", true);

    public ExampleHack()
    {
        super("Example");                        // name must not contain spaces
        setCategory(Category.COMBAT);
        addSetting(foo);                         // order here = order in the ClickGUI
    }

    @Override protected void onEnable()  { EVENTS.add(UpdateListener.class, this); }
    @Override protected void onDisable() { EVENTS.remove(UpdateListener.class, this); }
    @Override public void onUpdate()     { … }
}
```

Then add one line to `HackList`, alphabetically:
`public final ExampleHack exampleHack = new ExampleHack();`

Inherited from `Feature`: `WURST`, `EVENTS`, `MC` (`Minecraft`), `IMC` (`IMinecraftClient`).
Other hacks are reachable as `WURST.getHax().killauraHack`, settings as `WURST.getHax()`,
friends as `WURST.getFriends().isFriend(entity)`.

`Hack.setEnabled()` details worth knowing:
- refuses to enable when `TooManyHax` blocks the hack (`@DontBlock` exempts a hack),
- announces the toggle **only** when `markUserInitiatedToggle()` was called first (ClickGUI, TabGUI,
  Navigator, keybinds, commands) — so hacks driving other hacks stay silent,
- `NavigatorHack` / `ClickGuiHack` are excluded from the HUD hack list,
- saves state to `enabled-hacks.json` unless the class is annotated `@DontSaveState`.

Descriptions resolve through `WURST.translate("description.wurst.hack." + name.toLowerCase())`
against `src/main/resources/assets/wurst/translations/*.json`. A missing key falls back to the key
itself, so fork-added hacks (MaceAssist, MaceDmg, …) simply have no translated description —
that's fine, not a bug. Setting descriptions can be either a translation key or literal English
text; both constructors take a `String` and most fork code passes literal text.

## Settings

`Setting` subclasses implement `getComponent()` (ClickGUI widget), `toJson`/`fromJson`
(persistence), `exportWikiData()`, and `getPossibleKeybinds()`.

Common types: `CheckboxSetting(name, desc, default)`, `SliderSetting(name, desc, value, min, max,
step, ValueDisplay)`, `EnumSetting<>(name, desc, Values.values(), default)`, `ColorSetting`,
`TextFieldSetting`, `FileSetting`, `BlockSetting` / `BlockListSetting`, `ItemListSetting`,
`ChunkAreaSetting`, `EspStyleSetting`, `EspBoxSizeSetting`, `SwingHandSetting`, `AimAtSetting`,
`FaceTargetSetting`, `AttackSpeedSliderSetting`, `PauseAttackOnContainersSetting`,
`RoundingPrecisionSetting`, `TakeItemsFromSetting`, `PlantTypeSetting`, `BookOffersSetting`,
plus `CheckboxLock` / `SliderLock` for settings that force another setting's value.

`ValueDisplay`: `INTEGER`, `DECIMAL`, `PERCENTAGE`, `DEGREES`, `LOGARITHMIC`, `NONE`,
`ROUNDING_PRECISION`, `AREA_FROM_RADIUS`; chainable `.withSuffix(" blocks")`,
`.withLabel(0, "instant")`. Read values with `getValue()` (double) / `getValueI()` (int);
checkboxes with `isChecked()`; enums with `getSelected()`.

Entity targeting uses `EntityFilterList` + the ~24 `settings/filters/Filter*Setting` classes
(`FilterPlayersSetting.genericCombat(false)`, `FilterInvisibleSetting`, `FilterArmorStandsSetting`,
`AttackDetectingEntityFilter.Mode.OFF`, …). Killaura/AimAssist/FightBot all build one of these.
MaceAssist deliberately does **not** — it uses its own plain checkboxes.
`FilterSpeedSetting` ("Filter speed", blocks/s, default 100 in combat lists + MaceAssist) reads
`WURST.getEntitySpeedTracker()`, which samples every entity's latest server position each tick
(interpolation target, keyed by UUID) to skip teleporting anti-cheat bots.

Setting names are keyed lowercase inside a feature; duplicates throw at construction
(`"Duplicate setting: <hack> <name>"`).

## Files written to `.minecraft/wurst/`

| File | Contents |
|---|---|
| `settings.json` (+ `settings/` profiles) | every feature's settings, `{feature: {setting: value}}` |
| `enabled-hacks.json` (+ `enabled hacks/` profiles) | which hacks are on |
| `keybinds.json` | key → command string |
| `windows.json` | ClickGUI window positions / pinned / minimized |
| `preferences.json` | Navigator usage counts |
| `important-hacks.json` | hacks marked Important (middle-click in the ClickGUI) |
| `friends.json`, `alts.encrypted_json`, `analytics.json`, `toomanyhax.json` | as named |
| `autobuild/*.json`, `music/*.wav` | AutoBuild templates; AimAssist music files |

## GUIs

- **ClickGUI** (`clickgui/`): `ClickGui` owns one `Window` per `Category` plus UI-settings,
  Important and Radar windows (`windows.json`, layout version 4). Solid red/black theme, a top bar
  of tabs, no red outlines, `util/Anim` animations, gametest `ClickGuiTest`. Rules that bit:
  `RenderUtils.fillHorizontalGradient2D` sends corners in vanilla's order (TL, BL, BR, TR - back
  faces are culled); gametests call `BeastColors.freezeWave()` and `Anim.setInstant(true)`.
  **Read `docs/claude/gui.md` before touching the ClickGUI's look, bar, tabs or animations.**
- **Font:** Beast's own UI (ClickGUI, Navigator, TabGUI, HackList, logo text) draws with
  `util/BeastFont.get()`, never `MC.font`: a second `Font` over vanilla's glyph cache that maps the
  default font to `wurst:beast` (`assets/wurst/font/beast.json`: ASCII from the BeastFont
  pack's `ascii.png`, 16 px cells, MIT - `textures/font/beast_ascii_LICENSE.txt`; everything
  else falls through to vanilla). Chat, signs and vanilla screens keep the normal font. Needs
  the access-widened `Font.provider`.
- **TabGui** (`hud/TabGui.java`) and the **HackList HUD** (`hud/HackListHUD.java`) render through
  `IngameHUD` on `GUIRenderListener`; `WurstLogo` draws `assets/wurst/beast_128.png` and the
  version with no background, in the opposite **gray** of each pixel behind it
  (`util/InvertedText`: BeastFont ASCII glyphs through `WurstShaderPipelines.INVERTED_GRAY_TEXT`,
  whose shader reads the main framebuffer it draws into - each pixel read and written once by
  the same fragment; a `copyTextureToTexture` copy came out all black on 26.1.2 with no GL
  error, cause not found). The logo's Background/Text color settings are gone.
- **Navigator** (`navigator/`): older searchable list of every feature, ranked by
  `preferences.json` usage counts.
- **Wurst Options / keybind manager / zoom manager**: `options/`.
- **AltManager**: `altmanager/`, with `Encryption` writing `alts.encrypted_json`.

## Commands

`Command extends Feature`; `.name args`, parsed by `CmdProcessor` off `ChatOutputListener`.
`call(String[] args)` throws `CmdError` / `CmdSyntaxError` (both `CmdException`), `printHelp()`
prints the `syntax` varargs. 52 of them, incl. `.t/.tp/.goto/.path`, `.bind/.binds/.unbind`,
`.setcheckbox/.setslider/.setmode/.setcolor` (scriptable settings), `.blocklist/.itemlist`,
`.invsee/.viewnbt/.copyitem/.give/.enchant/.repair`, `.friends/.protect/.annoy/.say`,
`.toomanyhax`, `.features/.enabledhax/.help`.

Keybinds map a key name to a command string (`KeybindProcessor` → `CmdProcessor`), so
"toggle hack X" is really the command `.toggle`-style string stored in `keybinds.json`.

## Utilities worth reaching for before writing new code

- `BlockUtils` — `getState`, `getHardness`, `raycast(from, to)`, **`hasLineOfSight(Vec3, Vec3)`**.
- `RotationUtils` / `Rotation` — `getEyesPos`, `getNeededRotations`, `getAngleToLookVec`,
  `slowlyTurnTowards`, `limitAngleChange`, `isFacingBox`.
- `RotationFaker` (via `WURST.getRotationFaker()`) — `faceVectorPacket` (silent/server-side) vs
  `faceVectorClient` (moves the camera); backed by PreMotion/PostMotion.
- `EntityUtils` — `getAttackableEntities()`, `IS_ATTACKABLE`, `getLerpedPos/Box(e, partialTicks)`,
  `distanceToHitboxSq`.
- `InventoryUtils` — `indexOf(Item|Predicate[, maxSlot])`, `count`, `selectItem(slot)` (handles
  hotbar vs. shift-click vs. swap), `toNetworkSlot`, creative-mode item helpers.
- `RenderUtils` — `drawSolidBox`/`drawOutlinedBox`/`drawCrossBox`/`drawNode`(+`…Boxes` batch
  variants), `drawLine`, `drawTracer(s)`, `drawCurvedLine`, `applyRegionalRenderOffset`,
  `getCameraPos/Region`, `getRainbowColor`, `toIntColor`.
- `ChatUtils` — `message/warning/error`, prefix `§7[§cBeast§7]§r `.
- `ItemUtils`, `BlockBreaker`, `BlockPlacer`, `InteractionSimulator`, `PacketUtils`, `MathUtils`,
  `ColorUtils`, `RegionPos`, `FakePlayerEntity`, `OverlayRenderer`, `util/json` (`JsonUtils`,
  `WsonObject`, `JsonException`), `util/text` (`WText`), `util/chunk`.
- `ai/` — `PathFinder` + `WalkPathProcessor`/`FlyPathProcessor` power `.goto`, Tunneller, TreeBot,
  FightBot, Follow. `PathProcessor.setCreativeFlying(bool)` toggles creative flight *with* the
  abilities packet (never assign `abilities.flying` alone).

## Beast-specific parts of the fork

- **`util/BeastColors.java`** — the palette. Accent borders/letters are a dark-red→light-red
  gradient (`0xFF5A0000` → `0xFFFF3A3A`) keyed off **absolute screen X** so one wave sweeps the whole
  UI; `SELECTED_RED`, `SELECTED_RED_HOVER`, `SELECTED_FILL`, `ICON_BLACK`, `BRAND_RED`,
  `BRACKET_GRAY`, solid panel shades (`PANEL_BG`, `PANEL_BG_LIGHT`, `PANEL_HOVER`, `ROW_BORDER`,
  `SELECTED_TINT(_HOVER)`), `SHADOW`, the shield's `GOLD(_DARK/_LIGHT)`. No blue anywhere in
  the theme (user). Used by `ClickGuiIcons`, `FeatureButton`, `TabGui`, `WurstLogo`, `RenderUtils`,
  `ChatUtils`, `ToggleAnnouncer`. (Upstream `WurstColors` still exists alongside it.)
- **`ToggleAnnouncer`** + **`ToggleMessagesOtf`** — "[Beast] Toggled X ON/OFF" in chat and/or the
  action bar, with separate checkboxes for each; only user-initiated toggles announce.
- Logo asset `assets/wurst/beast_128.png`; chat prefix `[Beast]`; jar name `Beast-Client`.
- **`AimAssistHack` is heavily extended** beyond upstream: server-side vs client-side aiming
  (`FaceTargetSetting`), auto-attack, auto-combo (sprint-reset hits), "Aura-Farming" 360 spin,
  dodging (random A/D strafes with min/max distance sliders), a switch-target key, and a **music
  player** (WAV files from `.minecraft/wurst/music/` via `FileSetting`, volume/loop/play-when).
- Extra combat hacks not in upstream: **`MaceDmgHack`** (fake-Y packet burst for
  mace damage), **`MaceAssistHack`** (below). Also present: Instacart, MassTpa, KillPotion,
  MileyCyrus, HeadRoll, ItemGenerator, BuildRandom, ForceOp, CrashChest, AutoLibrarian.
- `build.gradle` is modified vs. upstream: `withSourcesJar()` removed.

## 26.1.2 API notes (things that changed and cost time)

- `Player extends Avatar extends LivingEntity`; `Mannequin` and `ClientMannequin` are new.
- `Inventory.getSelectedSlot()` / `setSelectedSlot(int)` are public — no accessor mixin needed
  (BetterMaceSwap needed one for `Inventory.selected`).
- `Entity.fallDistance` is a public **double** field.
- Enchantment checks: `stack.get(DataComponents.ENCHANTMENTS)` → `ItemEnchantments.keySet()` of
  `Holder<Enchantment>`, then `holder.is(Enchantments.DENSITY)`. `AutoArmor`, `AutoTool`, `EnchantCmd`
  etc. instead look up `registryAccess().lookup(Registries.ENCHANTMENT)` for levels.
- `ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)` then
  `MC.level.clip(ctx)` — or just use `BlockUtils.hasLineOfSight`.
- Spears exist as items (`Items.WOODEN_SPEAR` … `NETHERITE_SPEAR`, tag `ItemTags.SPEARS`), but there
  is **no** `SpearItem` class. `MaceItem` and `AxeItem` do exist.
- `MultiPlayerGameMode.attack(Player, Entity)`, `.useItem(Player, InteractionHand)`,
  `.piercingAttack(PiercingWeapon)`.
- Rendering is `PoseStack` + `GuiGraphicsExtractor`; `RenderPipelines` / `WurstRenderLayers` /
  `WurstShaderPipelines` wrap the new pipeline API.

To confirm any vanilla signature without guessing, the deobfuscated jar is at
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.1.2/`;
`unzip -l` it to find a class and `javap -classpath <dir> <fqcn>` to read the members.
