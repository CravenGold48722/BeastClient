# Beast Client — architecture reference

Written for future sessions so the folder doesn't have to be re-scraped. Everything below was
verified against the source in this repo, not recalled from upstream Wurst docs.

## What this repo is

**Beast Client** — a personal fork of [Wurst 7](https://github.com/Wurst-Imperium/Wurst7) by
CravenGold48722, living at `github.com/CravenGold48722/BeastClient` (remote name: `BeastClient`,
branch `main`). Java package names, class names and the mod id are all still `wurst` /
`net.wurstclient`; only the branding, the color palette and a handful of extra hacks are Beast's.

Git history is squashed into an "Initial commit" from the upstream tree, so **`git log` cannot tell
you which files are fork-custom**. Don't try; judge from content.

| | |
|---|---|
| Minecraft | `26.1.2` (`gradle.properties`, `WurstClient.MC_VERSION`) |
| Mappings | **Mojang official (mojmap)** — `Minecraft`, `LocalPlayer`, `ItemStack.is()`, `Mth`, `AABB`. Not Yarn. |
| Loader | Fabric, loader `0.19.3`, Fabric API `0.155.2+26.1.2`, Loom `1.16-SNAPSHOT` |
| Java | **25** (source, target, and mixin `compatibilityLevel`) |
| Version string | `WurstClient.VERSION = "7.56.8"`, jar `Beast-Client`, `mod_version=v7.5X.X-MC26.1.2` |
| Mod id / name | `wurst` / "Beast Client" (`src/main/resources/fabric.mod.json`) |

`fabric.mod.json` breaks on `wi_zoom` and `vulkanmod`, suggests `mo_glass`, and declares
`wurst.mixins.json` + `wurst.accesswidener`.

## Build, run, format

```bash
./gradlew compileJava          # fast correctness check — use this after every edit
./gradlew spotlessApply        # REQUIRED before finishing; see the trap below
./gradlew spotlessCheck        # verifies formatting
./gradlew build                # full build + tests
./gradlew runClient            # launch the game
./gradlew runClientWithMods    # launch with Sodium etc. from modrinth_deps.json
```

Eclipse launch configs (`Wurst7_client.launch`, …) exist for IDE use. `scripts/` holds
`build.cmd`, `genSources-eclipse.cmd`, `migrateMappings*.cmd`, `update_version_constants.py`.

**Spotless trap (hit this in a real session):** the formatter is
`eclipse().configFile(codestyle/formatter.xml)` and it **strips the trailing tabs that this
codebase's style puts on blank lines inside methods**. Hand-written code uses `\t\t` on blank lines;
after `spotlessApply` those lines are empty. So an `Edit` whose `old_string` spans a blank line will
fail with "String to replace not found" if you wrote the file pre-format and are matching
post-format text. Re-read the region (or match around single lines) after running spotless.

**Bash trap:** a heredoc big enough to hold a whole hack file blows up with
`ENAMETOOLONG: name too long, uv_spawn` on this Windows setup. Use the `Write` tool for new files.

Formatting style, for matching the surrounding code: tabs, Allman braces, `if(x)` with no space
before the paren, `}else` on one line, ~80 column wrap, GPL header on every file (Spotless enforces
the header via `LicenseHeaderStep`).

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
itself, so fork-added hacks (AttributeSwap, MaceAssist, …) simply have no translated description —
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
| `friends.json`, `alts.encrypted_json`, `analytics.json`, `toomanyhax.json` | as named |
| `autobuild/*.json`, `music/*.wav` | AutoBuild templates; AimAssist music files |

## GUIs

- **ClickGUI** (`clickgui/`): `ClickGui` owns `Window`s — one per `Category` plus a UI-settings
  window and Radar's own window — persisted to `windows.json`. Components live in
  `clickgui/components/` (`CheckboxComponent`, `SliderComponent`, `ComboBoxComponent`,
  `ColorComponent`, `FeatureButton`, list-edit buttons); pop-out editors in `clickgui/screens/`.
  Opened by `ClickGuiHack`, which switches itself off immediately after opening the screen.
- **TabGui** (`hud/TabGui.java`) and the **HackList HUD** (`hud/HackListHUD.java`) render through
  `IngameHUD` on `GUIRenderListener`; `WurstLogo` draws `assets/wurst/beast_128.png`.
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
  FightBot, Follow.

## Beast-specific parts of the fork

- **`util/BeastColors.java`** — the palette. Accent borders/letters are a dark-red→light-red
  gradient (`0xFF5A0000` → `0xFFFF3A3A`) keyed off **absolute screen X** so one wave sweeps the whole
  UI; `SELECTED_RED`, `SELECTED_RED_HOVER`, `SELECTED_FILL`, `ICON_BLACK`, `BRAND_RED`,
  `BRACKET_GRAY`. Used by `ClickGuiIcons`, `FeatureButton`, `TabGui`, `WurstLogo`, `RenderUtils`,
  `ChatUtils`, `ToggleAnnouncer`. (Upstream `WurstColors` still exists alongside it.)
- **`ToggleAnnouncer`** + **`ToggleMessagesOtf`** — "[Beast] Toggled X ON/OFF" in chat and/or the
  action bar, with separate checkboxes for each; only user-initiated toggles announce.
- Logo asset `assets/wurst/beast_128.png`; chat prefix `[Beast]`; jar name `Beast-Client`.
- **`AimAssistHack` is heavily extended** beyond upstream: server-side vs client-side aiming
  (`FaceTargetSetting`), auto-attack, auto-combo (sprint-reset hits), "Aura-Farming" 360 spin,
  dodging (random A/D strafes with min/max distance sliders), a switch-target key, and a **music
  player** (WAV files from `.minecraft/wurst/music/` via `FileSetting`, volume/loop/play-when).
- Extra combat hacks not in upstream: **`AttributeSwapHack`** (Simple/Smart slot swapping, breach
  swapping, shield breaker, lunge swapping, item saver), **`MaceDmgHack`** (fake-Y packet burst for
  mace damage), **`MaceAssistHack`** (below). Also present: Instacart, MassTpa, KillPotion,
  MileyCyrus, HeadRoll, ItemGenerator, BuildRandom, ForceOp, CrashChest, AutoLibrarian.
- `build.gradle` is modified vs. upstream: `withSourcesJar()` removed.

### MaceAssistHack (COMBAT)

A port of the standalone **BetterMaceSwap** mod (`C:\Users\rbnst\BetterMaceSwap`, package
`com.ritesh`, keybind-driven with an AutoConfig screen) into one Wurst hack — every mod keybind
became a checkbox, every config value a slider or dropdown. Implements `UpdateListener`,
`RenderListener`, `PlayerAttacksEntityListener`, `LeftClickListener`, `RightClickListener`.

Features: attribute swapping (best Density/Breach mace, smart switch, swap-back delay, swap scope),
stun slam (axe shield break + queued mace slam + follow-up), pearl catching (pitch lock + wind
charge, return slot Previous/Sword/Axe/Elytra/**Pearl**), wind-on-right-click, air pots, lunge
swapping (spear flick), mace aim assist (humanized rotation ported 1:1 from the mod — warm-up,
jitter, GCD rounding, 35% skip, all behind a "Humanize aim" checkbox), mace trigger bot, auto
chestplate.

Design points that were deliberate, don't "fix" them blindly:
- **Sub-tick trigger bot.** `triggerChecksPerTick` (default 10) — each check advances the player and
  the target along their velocities by `i/checks` of a tick and raycasts the target's hitbox, so an
  in-range moment inside a tick isn't missed. `onRender` adds one more check per frame.
- Item detection uses `ItemTags.SWORDS/AXES/SPEARS/CHEST_ARMOR` (the mod used
  `item.toString().contains("sword")`).
- **Mannequins**: `net.minecraft.world.entity.decoration.Mannequin extends Avatar`, and in 26.1.2
  `Player extends Avatar` too — so `instanceof Player` does **not** match a mannequin. Targeting is
  explicit checkboxes: players / mannequins / hostile (`instanceof Enemy`) / passive `Mob` /
  `ArmorStand`.
- `slamAttacking` is a re-entrancy guard for the stun slam's own `MC.gameMode.attack` calls only.
  Trigger-bot hits deliberately flow through `onPlayerAttacksEntity` so they get the same swaps a
  manual click gets.
- Trigger-bot clicks run `tryLungeSwap()` only when `Attribute swapping` is off (otherwise the spear
  switch would throw away the lined-up mace hit). Manual clicks always lunge-swap.
- `Swap scope` (All / Weapons only) gates **both** the mace swap and the stun slam's axe swap.
- Auto chestplate records the slot, uses the chestplate, and switches back inline in the same call
  (packet order: SetCarriedItem → UseItem → SetCarriedItem).
- `selectSlot()` sets `inventory.setSelectedSlot(slot)` **and** sends
  `ServerboundSetCarriedItemPacket` immediately rather than waiting for the client's end-of-tick sync.
- Aim-assist sliders step 0.5; `Min fall distance` defaults to 1.5 and gates on `player.fallDistance`
  (the mod only checked downward velocity).

## 26.1.2 API notes (things that changed and cost time)

- `Player extends Avatar extends LivingEntity`; `Mannequin` and `ClientMannequin` are new.
- `Inventory.getSelectedSlot()` / `setSelectedSlot(int)` are public — no accessor mixin needed
  (BetterMaceSwap needed one for `Inventory.selected`).
- `Entity.fallDistance` is a public **double** field.
- Enchantment checks: `stack.get(DataComponents.ENCHANTMENTS)` → `ItemEnchantments.keySet()` of
  `Holder<Enchantment>`, then `holder.is(Enchantments.DENSITY)`. `AttributeSwapHack` instead looks up
  `MC.level.registryAccess().lookup(Registries.ENCHANTMENT)` for levels.
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

## Tests

- `src/test/java/net/wurstclient/util/` — JUnit tests for `Rotation` / `RotationUtils` only.
- `src/gametest/` — in-game tests (`AltManagerTest`, `AutoMineHackTest`, `FreecamHackTest`,
  `NoFallHackTest`, `XRayHackTest`, …) run via `runClientGameTest` / `runClientGameTestWithMods`.
  There is no test coverage for combat hacks; verify those by launching the client.
