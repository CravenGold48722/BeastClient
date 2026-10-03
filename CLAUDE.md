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
| Version string | `WurstClient.VERSION` is read from the mod metadata (`mod_version` in `gradle.properties`, minus the `-MC…` suffix); jar `Beast-Client` |
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
  FightBot, Follow. `PathProcessor.setCreativeFlying(bool)` toggles creative flight *with* the
  abilities packet (never assign `abilities.flying` alone).

## Do it for real, don't fake it (user requirement, 2026-09-30)

The user wants every action that doesn't inherently need a fake packet done the way a player
does it. When writing or touching a hack:

- **Jump / sprint / swim up / sneak:** `KeyPresser.press(MC.options.keyJump)` (or `keySprint`,
  `keyShift`), never `jumpFromGround()`, `setSprinting(true)`, `push(0, 0.04, 0)` or velocity
  edits. Since 1.21.2 the client sends `ServerboundPlayerInputPacket` with the held keys, so a jump
  without the key pressed is visible to anti-cheats. `KeyPresser` holds the key for N reads of
  `KeyboardInput.tick()` (hooked by `KeyboardInputMixin`) and then restores the physical state;
  it uses `IKeyMapping.setDownIgnoringToggle()` so Toggle Sprint/Sneak keys don't flip.
  Presses wait while any screen is open (vanilla takes no movement input then) unless InvWalk
  is on, so `.jump` from chat fires right after the chat closes.
  Only movement cheats that can't be done with keys (Flight, Jetpack, Speed, NoClip…) keep velocity
  edits.
- **Aiming at entities:** use the shared engine, never a hand-rolled turn. `util/HumanAim` is
  the math (reaction delay, critically damped ease-in/out, drifting speed, curve, tremor,
  tracking a moving target; unit-tested in `HumanAimTest`), `util/CameraAim` drives it per
  frame from `onRender` (or per tick for silent aim). Used by AimAssist, MaceAssist, BowAimbot,
  Killaura (Client-side), KillauraLegit, FightBot, Protect. AimAssist's smooth aim uses `HumanAim.pidStep`
  instead of `humanStep`: a PID with gain scheduling on how deep inside the hitbox the aim is
  (error / angular tolerance from `getAimTolerance`; full pull at the edge and beyond, gentle
  inside, no correction deep inside), an integral that only builds near the hitbox and never
  while closing fast (no windup overshoot), filtered D, and setpoint feed-forward (the wanted
  angle's own rate, measured per frame) - that last part is what keeps it on A/D strafers;
  the per-tick tracking estimate alone lags each reversal. `PidAimTest` compares it with the old
  aim (strafer 40°/s: 96% vs 81% on target). Within "Smooth aim distance" (once caught up) it snaps; "Smooth aim speed" 360-2000 overrides
  the PID with a fixed top speed (old humanStep/linearStep), its top end "auto" (blue knob) = PID.
  While AimAssist has a target and turns the camera, `onMouseUpdate` zeroes your mouse input
  (the old "you moved the mouse >1.5° = steering" release made it let go of targets). Gate attacks on the crosshair actually
  being on the target while turning. BowAimbot's lead comes from `util/BallisticSolver`
  (vanilla projectile physics, tested in `BallisticSolverTest`) plus `util/TargetPredictor`:
  Robocode-style pattern matching (replays the most similar past moments of the target's
  movement, relative to the shooter), aims at the densest spot of those replays within the hit
  width rather than their average, and runs virtual guns (pattern / averaged / linear / still)
  scored on practice shots. It starts from the server position (`EntitySpeedTracker
  .getLatestServerPos`), fills in the ticks between the server's every-2nd-tick updates, and looks
  ahead by flight + 0.5 tick (the hit is checked on whole ticks) + ping. `TargetPredictorTest`
  shoots simulated vanilla arrows at simulated A/D strafers: steady rhythms 93-100% hits (linear
  lead: 0-50%); random 5-15-tick rhythms about 50% at 30 blocks, where perfect knowledge of the
  random rule would get 52%. Never go back to plain velocity lead for strafers.
- **MaceAssist aim** also rounds to the real mouse step (both Humanize and snappy); humanize jitter is
  whole mouse counts. KillauraLegit moves via MouseUpdateEvent deltas, so vanilla applies sensitivity.
- **Mouse-step rotations:** every Wurst-made rotation (client via `Rotation.applyToClientPlayer`,
  silent via `RotationFaker.faceRotationPacket`) is snapped with `Rotation.snapToMouseSteps` to
  multiples of `Rotation.getMouseStep()` (vanilla's sensitivity math). Never call
  `setYRot/setXRot` with a computed angle directly — un-snapped deltas are what GCD/"aim modulo"
  checks look for, and float noise otherwise sends a rotation packet every tick.
- **VanillaSpoof** (on by default): brand -> "vanilla"; drops every other `ServerboundCustomPayloadPacket`
  (Fabric channel lists); login-query answers -> null; known data packs filtered to vanilla's
  trusted list; sign/anvil text resolved via `withVanillaTranslations` (language rebuilt
  from non-mod packs by `VanillaLanguage`, fallback `ModTranslationKeys`; mod keybinds raw;
  covered by gametest `VanillaSpoofTest`); "Sign chat like vanilla" suspends NoChatReports.
  Packet rewrites skip singleplayer. New fingerprint fixes go here.
- **Never go silent:** a hack that blocks the player's movement packets (RemoteView) must still
  send vanilla's once-per-20-ticks position reminder with the real position — a long silence
  then a burst is what blink checks flag.
- **Background traffic:** Plausible analytics is off by default (config v3) and the upstream
  `WurstUpdater` is not registered — both only phoned upstream Wurst's servers.
- **Rotations:** `FaceTargetSetting` defaults to `CLIENT` everywhere; hard-coded
  `faceVectorPacket`/`sendPlayerLookPacket` is only allowed behind a user-selected option. With
  CLIENT/SERVER, act on one target per tick (`canFaceMultipleTargetsPerTick()`).
- **Swings / clicks:** `SwingHand.CLIENT`; use `InteractionSimulator.rightClickBlock/rightClickItem`
  (swings only when vanilla would). `IMultiPlayerGameMode.rightClickBlock` no longer sends a stray
  UseItem after a successful block click. Hold `keyUse` for eating instead of re-sending UseItem.
- **Inventory:** `windowClick_*` automatically opens the real `InventoryScreen` first via
  `InventoryOpener` (only when no screen is open, not in creative) and closes it with `onClose()`
  3 idle ticks later, which sends the container-close packet. While that auto-opened
  screen is up, `KeyboardInputMixin` zeroes movement input (unless InvWalk). Skipped while riding
  something with its own inventory. Attacking, using, placing or mining closes it first
  (`MultiPlayerGameModeMixin` → `InventoryOpener.beforeWorldInteraction()`), since nobody can do
  those with the inventory open.
- **Hotbar slot:** after `setSelectedSlot`, sync with `IMC.getInteractionManager().syncSelectedSlot()`
  (vanilla's `ensureHasSentCarriedItem`), never a hand-built `ServerboundSetCarriedItemPacket`.
- **Packet order (Grim PacketOrderE):** a slot change must never go out after an attack, interact,
  use, use-on-block, release, PlayerCommand or sneak/sprint input change in the same tick (tick =
  up to `ServerboundClientTickEndPacket`). `util/PacketOrder` tracks that from the sent packets and
  `MultiPlayerGameModeMixin` holds `ensureHasSentCarriedItem` back; vanilla's `tick()` sends it at
  the start of the next tick. Code that switches and then attacks/uses must check
  `PacketOrder.canChangeSlotNow()` and wait a tick if false (MaceAssist does), or the action goes
  out with the old item. `PacketBudgetTest` counts violations independently (MaceAssist swap-back
  scenario: 9/9 violations without the guard, 0 with it).
- **Chat / server commands:** `ChatUtils.sendAsPlayer("/cmd …")` — same normalisation and chat
  history as typing it; doesn't fire `ChatOutputEvent`, so it can't loop.
- **Changing a default:** `settings.json` stores defaults too, so add an entry to
  `settings/LegitDefaultsMigration` (add a new `Batch` with its own marker name). It only moves settings
  still on the old default.

## Auditing: finding bugs, inefficiencies and extra packets without being told

The user periodically asks for "check everything" with planted or unknown issues and expects them
found unprompted. Two earlier passes reported "everything is fine" and were wrong (a join crash in
`KeyPresser` was sitting in plain sight). What went wrong was **reading for plausibility instead of
checking against rules**. Treat every pass as: assume there are bugs, sweep the **whole tree**
(not just files touched this session — `git status` / `git diff` only shows recent work, and
history is squashed), and for each rule below grep for every site rather than sampling.

**Order of work:** run the mechanical sweeps (1–8) across the whole tree first, then the semantic
checks (9–11) on the fork-heavy hacks (AimAssist, MaceAssist, Killaura*, FightBot, Protect,
BowAimbot, AutoSprint, Tunneller, AutoLibrarian, Instacart, everything in `util/` that's
fork-only), then **measure** (12–13). Don't stop at the first finding in a file.

1. **Listener balance.** Every `EVENTS.add(X.class, this)` needs the matching `remove` in
   `onDisable`. Only always-on OTFs may keep listeners. Also check `onDisable` resets every bit of
   state `onEnable` doesn't (targets, phases, `CameraAim.reset()`), or the next enable starts stale.
2. **Stuck keys.** grep `setDown(true)`, `KeyPresser.press`, `keyUse`/`keyShift`/`keyUp`: any key a
   hack holds must be released in `onDisable` (`IKeyMapping.get(key).resetPressedState()`) for
   **every** phase it can be disabled in. *Found:* Tunneller (walk/sneak), AutoLibrarian (sneak
   while placing), Instacart (use while charging) all left keys held.
3. **Direct packet sends.** grep `getConnection().send(`, `sendPacket`, `new Serverbound`. Each must
   be inherently fake (Blink, MaceDmg, RemoteView reminder…) or behind a user option; everything
   else goes through vanilla (`syncSelectedSlot`, `InteractionSimulator`, `KeyPresser`, real
   inventory clicks). Then the **indirect** sources, which grep for sends won't show: rotations
   written every tick (float noise → a Rot packet every tick), slot changes and back in one tick,
   `swing` without an attack, `useItem` re-sent while already using, inventory clicks that open and
   close the screen repeatedly, `setSprinting`/`setShiftKeyDown` (→ PlayerCommand), key flapping
   (→ PlayerInput).
4. **Hacks fighting each other / the user.** A hack must not `setEnabled(true)` another hack as a
   side effect (it's saved to `enabled-hacks.json` and changes the user's setup). *Found:*
   AimAssist's auto-combo force-enabled AutoSprint, and AutoSprint then re-pressed sprint during
   AimAssist's s-tap / crit fall, cancelling both. Fix pattern: the owner exposes a query
   (`isHoldingSprintOff()`) and the other hack yields.
5. **Vanilla rules, verified not remembered.** When a hack claims a vanilla effect (crit, sweep,
   shield disable, keypair, projectile physics), `javap` the deobf jar and read the real check.
   *Found:* the "crit" combo hit while sprinting — `Player.canCriticalAttack` requires
   `!isSprinting()`, so it never crit. Duplicate `prepareKeyPair` looked like a bug but
   `ClientPacketListener.setKeyPair` returns early on the same keys — verify both ways.
6. **Threads.** Everything off the render thread (netty `PacketInputListener`s, `new Thread`,
   executors, `CompletableFuture`) must not touch `MC.player`/`level`/`gameMode`/screens/chat
   except via `MC.execute`/`MC.submit(...).join()`. Collections shared with another thread need
   copies or concurrent types; never call `entry.getKey()` after removing it from an
   `IdentityHashMap` (the `KeyPresser` crash). Also count threads: *found* NewChunks started one
   thread per loaded chunk (thousands on join) → one shared `MinPriorityThreadFactory` executor.
7. **Parallel streams.** `EntityUtils.getAttackableEntities()` and friends are parallel, so every
   filter/comparator they call must be read-only. *Found:* `EntitySpeedTracker.getTopSpeed`
   pruned its map from inside a filter (data race) → reads filter, only `record()` prunes.
8. **Hot paths.** Per-tick/per-frame code: no disk writes (setting setters and `setEnabled` save
   files — guard with "only if changed"), no `System.out` (*found:* `MessageCompleter` logged every
   AutoComplete request and response — spam and a privacy leak), no new threads, no streams over
   all entities more than once per tick, no allocation-heavy work in `onRender` that could be
   per tick.
9. **Target churn.** Picking the "best" target afresh every tick makes the smooth aim restart
   (reaction delay and all) whenever two candidates swap rank, so the crosshair never lands and it
   keeps sending rotations. Any aiming hack needs stickiness: keep the previous target while it's
   still valid (KillauraLegit, Killaura client mode), or hysteresis (FightBot/Protect switch only
   if the new one is >2 blocks closer).
10. **Stale references.** Any field holding an `Entity` across ticks must re-check `isRemoved()`,
    `level() == MC.level` and the filters before use (dimension change, death, respawn).
11. **Math, by simulation not by eye.** Aim/physics code gets a JUnit test against the real
    classes (fabric-loader-junit makes MC classes available): `HumanAimTest`,
    `BallisticSolverTest`. A temporary trace test that prints the trajectory found the 4°
    overshoot (underdamped `HAND_RESPONSE`) that reading the code missed. Delete trace tests
    afterwards; keep the assertions.
12. **Packet budget gametest** (`gametest/tests/PacketBudgetTest`). Counts every outgoing packet
    by class via `ConnectionPacketOutputListener`. Idle: with the always-running hacks on and
    nothing to do, the counts must equal plain vanilla's (60 TickEnd + 3 Pos per 60 ticks).
    Engaged: with a still husk off to the side, a hack may send rotations while turning onto it
    but ≤3 once on target, exactly one swing per attack, and no slot/container/use packets. **When
    you add or change a hack that runs every tick or aims, add it to this test.** Read the
    per-type breakdown in `build/run/clientGameTest/logs/latest.log` even when it passes — a
    surprising zero (e.g. a hack that never acquired the target) is a finding too.
13. **Diff against upstream** for anything that looks off: fork code is where most bugs are, and
    upstream may already have fixed a shared bug since the fork point (NoFog, Fullbright,
    stale-entity checks were all ported that way).

14. **Read the user's real config** (`.minecraft/wurst/settings.json`, `enabled-hacks.json`)
    when a reported symptom doesn't follow from the defaults. *Found:* "AimAssist stops aiming when
    the target moves a bit" was the user's saved AimAssist "Filter flying" 0.4 (default 0) being
    re-checked on the locked target every tick - any jump or knockback dropped it. Also check
    which other always-on hacks touch the same thing (MaceAssist and BowAimbot also turn the
    camera, which AimAssist's steering detector took for the mouse). Read-only: never edit them.

Report findings honestly: say what was found, what was fixed, and what was checked and found fine
(with the reason, e.g. "duplicate keypair is harmless because…"). Never claim "no issues" for a
category you didn't sweep.

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
- Extra combat hacks not in upstream: **`MaceDmgHack`** (fake-Y packet burst for
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
swapping (spear flick), mace aim assist (now the shared per-frame `CameraAim` / `HumanAim` engine, the
same one AimAssist uses; "Humanize aim" switches the human-like curve on), mace trigger bot, auto
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
- `selectSlot()` sets `inventory.setSelectedSlot(slot)` and then calls
  `IMC.getInteractionManager().syncSelectedSlot()` (vanilla's own sync), so the slot is sent once,
  immediately, and vanilla doesn't send it again on the next attack or tick.
- Aim-assist sliders step 0.5; `Min fall distance` defaults to 1.5 and gates on `player.fallDistance`
  (the mod only checked downward velocity).

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

## Tests

- `src/test/java/net/wurstclient/util/` — JUnit tests for `Rotation`, `RotationUtils`, `HumanAim`
  (settling, no overshoot, tracking lag), `BallisticSolver` (hits at range, moving targets) and
  `TargetPredictor` (hit rates against simulated strafers, < 1 ms per tick) and `PidAimTest`
  (PID aim vs the old ease-out: landing, overshoot, resting, strafers).
- `src/gametest/` — in-game tests (`AltManagerTest`, `VanillaSpoofTest`, `KeyPresserTest`,
  `PacketBudgetTest`, `AutoMineHackTest`, `FreecamHackTest`, `NoFallHackTest`, `XRayHackTest`, …)
  run via `runClientGameTest` / `runClientGameTestWithMods`; the whole suite passes (exit 0,
  "Test complete" in `build/run/clientGameTest/logs/latest.log`). Every `SingleplayerTest` ends
  with a screenshot compared to one clean-world template, so a test must leave nothing behind:
  clear chat, the action bar (toggle announcements), particles, dropped items / XP, wait ~25 ticks
  for death animations and ~7 ticks after a `tp` rotation. Failed runs keep their PNGs in
  `build/run/clientGameTest/screenshots/` — look at them before guessing.
- `PacketBudgetTest` is the only automated check of combat-hack behaviour (packets, swings per
  attack, aim settling); feel and accuracy still need a launch of the client.
