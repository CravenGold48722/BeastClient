# Beast Client — architecture reference

Written for future sessions so the folder doesn't have to be re-scraped. Everything below was
verified against the source in this repo, not recalled from upstream Wurst docs.

## Effort budget (all agents and subagents)

A high or max effort setting is a **ceiling, not a target**. Spend thinking where it changes the
result: being thorough means covering everything the task actually needs, checked properly - not
using up the budget. Match the depth to what the task has in it:

- **Simple / mechanical** (find a file, grep for usages, read and summarise one class, rename,
  move a jar, run a build, a one-line fix whose cause is already known): the work is in doing it
  and checking the result, so put the effort there rather than into deliberating first.
- **Medium** (a bug with a known symptom in a few files, a small feature that follows an existing
  pattern): plan up front, then carry the plan out; revisit it when a result contradicts it.
- **Hard** (anticheat behaviour, aim/prediction math, threading, packet order, a symptom with no
  obvious cause): this is what the budget is for. Work it through fully and verify the answer.

Thinking that adds nothing is what to skip: re-deriving what this file or the prompt already
says, weighing options you won't pick, re-reading files you just read or edited, and restating
the same reasoning twice. Reports cover what changed, what was verified and what wasn't, without
padding. A subagent asked for a specific answer returns that answer in full, not a survey of the
codebase.

## When a task is finished: build, commit, push, install (user, 2026-10-14)

Standing permission - don't ask first. Whenever you finish a piece of work (a code change that
is done and verified, not mid-task and not for questions/read-only work):

1. `./gradlew spotlessApply build -PnoAnticheat` (the anticheat servers aren't needed for the
   jar). If it fails, fix it or stop and report - never commit or install a broken build.
2. Commit everything that belongs to the task on `main` (message ends with the
   `Co-Authored-By` line from the session's attribution reminder) and `git push BeastClient main`.
3. Move the new jar from `build/libs/` (the highest `Beast-Client-v*.jar`; the build bumps
   `mod_version` every time) to `%APPDATA%\.minecraft\mods`, deleting the previous
   `Beast-Client-*.jar` there first - only one Beast jar may be in mods.
4. Say in the report that it was committed, pushed and installed (with the commit hash and the
   jar name).

Don't commit unrelated leftovers you didn't make without asking; never force-push.

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
| Loader | Fabric, loader `0.19.5`, Fabric API `0.155.3+26.1.2`, Loom `1.18-SNAPSHOT`, Gradle `9.8.1` (Loom 1.18 needs 9.7+; it made run configs' `vmArgs`/`programArgs` read-only - use `vmArg(...)`/`programArg(...)`) |
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
.\gametest [mace scaffold]     # in-game tests, summary only (gametest.cmd -> scripts/gametest.ps1;
                               #   = runClientGameTest -Ponly=mace,scaffold; -mods, -update, -help)
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
| `important-hacks.json` | hacks marked Important (middle-click in the ClickGUI) |
| `friends.json`, `alts.encrypted_json`, `analytics.json`, `toomanyhax.json` | as named |
| `autobuild/*.json`, `music/*.wav` | AutoBuild templates; AimAssist music files |

## GUIs

- **ClickGUI** (`clickgui/`): `ClickGui` owns `Window`s — one per `Category` plus a UI-settings
  window and Radar's own window — persisted to `windows.json`. Components live in
  `clickgui/components/` (`CheckboxComponent`, `SliderComponent`, `ComboBoxComponent`,
  `ColorComponent`, `FeatureButton`, list-edit buttons); pop-out editors in `clickgui/screens/`.
  Opened by `ClickGuiHack`, which switches itself off immediately after opening the screen.
  **Look (user, 2026-10-14, "modernize, red, not see-through, don't copy"):** solid panels (the
  Opacity sliders are gone, `getOpacity()` is 1; hover = `ClickGui.getFillColor(hovering)`, never
  opacity x 1.5), drop shadow, **no red outlines anywhere** (user: windows, buttons, tooltips,
  boxes, enabled rows - `ClickGui.drawOutline` is a gray `ROW_BORDER` hairline; the wave stays
  in text, the enabled bar, slider fills and the tab underline); no pin button (only Radar's
  window is pinned, always); title bars `BAR_BG` (near black); feature rows left-aligned with a
  hairline, enabled = dark red row (`SELECTED_TINT`) + gradient bar on the left; checkboxes are
  square-cornered switches with a square knob (`ClickGuiIcons.drawSwitch`), sliders a slim track
  filled with the wave up to a white knob (`getKnobColor`'s green/blue is no longer drawn).
  Inspiration was the CevAPI fork's "modern" ClickGUI (`../UI+Feature_Inpirations`) - ideas only,
  no code copied.
  **Important** (that fork's "Favorites"): middle-click a hack -> golden shield
  (`ClickGuiIcons.drawShield`) and listed in the "Important" dropdown; saved in
  `important-hacks.json` (`hack/ImportantHacksFile`, `Hack.isImportant/setImportant`).
  Right-click a row = its settings. Gametest `ClickGuiTest` does all of it with the real mouse
  (tabs included: dragging Combat's tab onto Render's slot and back, dragging the Combat
  window and reopening it) and leaves `wurstlogo_inverted.png` / `clickgui_bar.png` /
  `clickgui_tab_dragged.png` / `clickgui_settings.png` / `clickgui_rows.png` in the
  screenshots folder; it also fails if a section is shown before its title is clicked, on
  tabs outside the bar/overlapping, or a dropdown not right below its tab and the bar, not
  normal size, not one column, or off screen.
  **Gradient quads:** `RenderUtils.fillHorizontalGradient2D` must send the corners in vanilla's
  order (TL, BL, BR, TR) - the GUI pipeline culls back faces, and the old order meant every
  gradient border/bar/scrollbar was silently never drawn (found 2026-10-14).
  The wave moves with real time, so the gametests call `BeastColors.freezeWave()` first - the
  HackList in screenshots was a random shade of red and templates failed now and then.
  **Bar (user, 2026-10-14):** a near-black bar along the top, one row of equal slots (screen
  width / 10), one tab per bar window (`Window.isInBar`: the categories, Important, UI
  Settings). Default order from the middle out by `ClickGui.BY_IMPORTANCE` (Important in the
  middle; Chat/Fun/UI Settings at the ends); titles too wide for a slot are drawn smaller. Tabs
  stay put except when you drag one: it follows the mouse and on release swaps with the tab in
  the slot it's over, both sliding into place; the order is saved (`windows.json` `"_layout":
  {"version": 3, "order": [...]}`; older files open all closed, docked, default order). A tab
  click (on release, no drag) opens/closes its window and brings it to the front. Bar windows
  have their own title bar again (no buttons) and are **one column with a scrollbar**, at most
  "Max height" (slider back, on-screen pixels) and never past the bottom of the screen.
  **Like the inspiration's top bar (user, latest):** every section is hidden until its title
  is clicked, and an open one is a normal window at its normal size and text - free to
  overlap others. (Before: all ten fitted side by side, shrunk or squeezed into equal slots -
  the user dropped that. `Window.getScale` / `getDisplayWidth`, the scaled drawing and the
  mouse conversion (`toWindowX/Y`) are still in place but the scale is always 1;
  `ClickGui.pushFittedText` still fits tab/window titles and too-long rows.) `windows.json`
  layout version 4: older files start with every section hidden (version 3 keeps its tab
  order).
  Docked (default) they open right below their tab (touching the bar); dragging one by its
  title bar undocks it (`Window.isDocked`, saved) and it reopens where it was left - its tab doesn't move.
  **Animations** (`util/Anim`: exponential glide by real time, first update snaps;
  `Anim.ease` = smoothstep): bar windows slide open/closed (scissor reveal, the mouse doesn't
  reach a closing one), tabs slide between slots, the little gradient line under a tab grows
  from the middle and shows **only under the front-most open bar window**, enabled hack rows
  fade their tint/text and grow the left bar, switches slide their knob and fade the wave in.
  Gametests call `Anim.setInstant(true)` (no half-finished animation in a screenshot), so the
  animations themselves are only checked by eye.
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
  aim (strafer 40°/s: 96% vs 81% on target). Within "Smooth aim distance" (once caught up, and
  not right after your mouse moved it) it snaps; "Smooth aim speed" 360-2000 overrides
  the PID with a fixed top speed (old humanStep/linearStep), its top end "auto" (the value reads "auto") = PID.
  **Turning like the reference video (user, 2026-10-16, `../whataimassistturningshouldbelike.mp4`
  "Sensitivity Based Aim-Assist"; ideas from Kclient/Raven's AimAssist in
  `../AimAssistTurning Inspirations`, no code). Watched at 20-30 fps and measured from the
  zombie's position per frame (`motion.py` in a scratchpad, FOV 70 assumed):** the aim pulls
  the camera onto the target by itself AND your mouse still works - pulling away the crosshair
  first only creeps (0.4-2°/frame at 30 fps), a flick breaks free, and once you stop it comes
  straight back: the clean 45° return goes 44.9 42.5 31.4 19.9 9.5 5.2 2.4 1.2 0.6 -0.1 (every
  1/30 s): ~11°/frame (~340°/s), then half of what's left per frame (k ~21/s), no overshoot.
  A first try ("Turning: Sticky", a mouse-only friction/magnetism/tracking mode) was wrong -
  the user: "i didn't mean a new setting that doesn't even turn towards it automatically" -
  removed. Now: `HumanAim` KP_OFF 21 / KP_ON 16 (were 12/6), the humanized hand response is
  critically damped with the current gain (`1/(4 kp)`; the fixed HAND_RESPONSE tuned for the
  old gain overshot), the integral only builds for a moving target (it swung small turns past
  a still one), auto speed `max(340, 8 x angle left)` (was a flat 720; 180° in < 0.5 s).
  `VideoAimTest` replays the 45° return (20 seeds, within 3° per frame) and checks no overshoot.
  **Mouse:** no longer zeroed - `onMouseUpdate` slows it near the target (`util/AimFriction`,
  "Stickiness" 75%: full while leaving the aim point, half at the hitbox edge growing to full at
  the aim point while closing, fading out 5° outside the hitbox; applied in 0.25° slices along
  the movement - all of a big per-frame burst at the start position's factor kept a 60° flick
  at 5°; whole mouse counts with the fraction carried), and `absorbMouseMovement` shifts the
  aim by what the mouse did (`HumanAim.shift`, `clearIntegral`, `smoothCatchUp` so it doesn't
  snap back) - the aim continues from there. Gametest `AimAssistTurningTest` (the user's
  settings; a per-frame "virtual hand" `MouseUpdateListener` added first - the test's
  `moveCursor` arrives once a tick, bursts the pull undoes between ticks): 45° -> on in 4 ticks,
  no swing past; still = no movement; 100°/s pull creeps 1.5°, stays on; 1200°/s flick 72°
  off, back 4 ticks after; sliding husk within 0.1°. Sweep `AimAssist (pulled away)` flicks
  the same way (`FeatureTestKit.flickMouse`, 1200°/s for 2 ticks; a 60° `moveCursor` sweep in
  3 bursts never got off a husk 2.5 blocks away - ±13° hitbox at 75% stickiness): clean on
  both, off and back.
  **Jitter (user: "aimassist is jittery in the tests"):** Aim at: Auto used `AimAtSetting`'s
  closest point from the **tick** eye position inside the per-frame aim - it jumped once a
  tick while moving and the aim chased it (camera zig-zag on 9-11% of a VexBot fight's frames,
  the bot's own 1%); now `frameAimPoint` from this frame's eyes: 0.1%. `AimAssistComboTest`
  records every frame and fails over 2% zig-zag. The AimAssist tests run with the user's own
  settings (`gametest/UserSettings`, a copy of their settings.json from 2026-10-16 - Aim at
  Center, Auto combo, Sprint sweeps...; tests override only what they must, e.g. Auto attack
  off for the turning test, Auto combo off for PacketBudget - it walks in and out, every step
  a new angle, a rotation every tick; the fight allows sprint-sweep hits when that's on, up to
  80% chances let by with Hops/Baits on (they hold hits back on purpose: 36/54 with them,
  14/34 without; 65% otherwise), and only counts hits taken while AimAssist was still on).
  While AimAssist has a target and turns the camera your mouse is slowed, never zeroed
  (the old "you moved the mouse >1.5° = steering" release made it let go of targets). Gate attacks on the crosshair actually
  being on the target while turning. All three steps lead the target's motion by one frame
  (`leadDt`: the previous step's dt), never by the current dt - after a 150 ms lag spike that
  put the aim 23° ahead of a fast target (`noFlickAfterFrameHitch`); and the hand stops once
  past the aim point while still moving away. Only one hack turns the camera at a time:
  MaceAssist's mace aim yields while `AimAssist.isTurningCamera()` (both at once flicked the view
  up and sideways while falling). AimAssist hits are sprint hits or crits, never sweeps (user,
  2026-10-03; crits allowed 2026-10-15): `attackTarget` waits for `isSprinting() && wasSprinting`
  (what the server has - the attack goes out before this tick's sprint update), or `isCritReady`
  (vanilla `canCriticalAttack` with the server's state: fallDistance > 0, not on ground/ladder/
  water/riding, not blind, `!isSprinting && !wasSprinting`), unless sprinting is impossible
  right now (hunger, blindness, item use, sneaking, riding, gliding, water). **Auto combo** (user, 2026-10-15, from two
  combo guides - `../howtocombotranscript*.txt`): a hit as soon as vanilla gives sprint knockback
  (`Player.attack`: strength at +0.5 tick > 0.9 - fired a tick past that, 12 ticks with a sword
  instead of 13); hold W, hit, release W - between hits it coasts (no keys), backs off with S
  under 2.0 blocks, chases over 3.0, and holds W only `SPRINT_LEAD_TICKS` (2) before the next
  hit; sprint reset = W-tap, or S-tap when still under 2.3 after the hit; **every chance at a
  sprint hit or crit is taken** (user, 2026-10-16: "100% of the time, unless a setting says
  not to" - `isHitChance` runs before the ground combo's tricks and in every combo phase; only
  Baits, Aura-Farming on the ground, Shield breaker and Skip falling targets hold one back;
  `AimAssistComboTest` records every tick a sprint hit/crit would land and HitCheck passes
  and fails on any missed: 17 of 17 taken); **Skip falling targets** (checkbox, off by
  default - user): no hit on a falling target (falling knockback sends them into the ground and
  they rebuild momentum first - `isFalling(Entity)` from `getY() - yo`, other players' motion
  isn't sent) unless they're 200+ ms laggy (`PlayerInfo.getLatency`); a **deflection** (hit back whatever the spacing within
  4 ticks after the target hits you); **in the air** (a jump reset, Aura-Farming's jump, a drop)
  W + sprint while rising, both let go once falling, then a **crit** (punish crit); against
  **their** jump resets no guessing (user): a target on the ground or still rising is hit at
  once - an early jump gets them launched - and a falling one waited for (the random "Delayed
  hits" hold-back of hit 2-4 was removed, 2026-10-15);
  **Jump reset** (jump in the tick the target's hit arrives: `hurtTime == hurtDuration` at the
  UpdateEvent, which runs before `baseTick` and before the input is read; with the combo it
  holds W + sprint for it). **Everything else from both guides** (user, 2026-10-15: "implement
  every non-unrelated feature"), each its own checkbox: the sprint reset by the target's
  movement (S-tap when it comes at you, W-tap when it backs off, distance otherwise - from
  `trackTargetMotion`, positions over ticks smoothed like VexBot's; `x - xo` read 0 after a
  position snap); S-tap deflection when hit from under 2 blocks (instead of a jump reset);
  Fly-outs (S + jump into their hit when it's due before ours plus the deflection window -
  from their last swing (`swinging`/`swingTime`) and their weapon's attack speed
  (`ItemAttributeModifiers.compute(ATTACK_SPEED, 4, MAINHAND)`); only against a target that
  swung in the last 40 ticks, 20 ticks apart; flying out whenever theirs came first let a
  VexBot combo us for 67 ticks); Sprint crits (W stays down falling when sprint already ended);
  Hop crits (hop after a ground deflection); Uppercuts (just out of reach, not approaching);
  Lunges (target backing off 3.5-6 blocks, after 10 ticks of sprinting); combined ping 200+ =
  hit stacking; opt-in: Double crits, Hops, Baits (random), Sprint sweeps and Weak hits (the
  last two are the only non-sprint/crit hits - off by default because of the user's "never
  sweeps"). Not added: delayed sprint hits (the user replaced them with "no guessing").
  **From VexBot's AI** (user: "decompile vexbot, add what it does better, except aiming";
  Vineflower on its combat classes - ideas only, no code): Punish openings (hit at once when the
  target just swung or lowered its shield), Shield breaker (axe from the hotbar: switch, hit
  next tick, switch back with `PacketOrder`; never hit a raised shield otherwise), Crouch dodge
  (sneak 3 ticks against an incoming crit dive), no walking into a cobwebbed target (crits it),
  no S into a wall (MeleeWallGuard), opt-in Combo strafe and Shield block. Its mace/crystal/
  elytra/pearl/potion/loot AIs are outside AimAssist. Also fixed: the combo reset (and let go
  of W) whenever the crosshair slipped off the target for a tick, so the next hit came 2+ ticks
  late - now only looking 45°+ away resets it; and no attack while using an item. Gametest `AimAssistComboTest` fights a **VexBot** (user,
  2026-10-15: a server-side Fabric PvP bot mod, `../vexbot-3.0.14mc26.1.2.jar`, MIT; it
  w-taps, strafes, jumps, crits and jump-resets): a frozen bot with jump strength 0 and
  knockback resistance 1 (12-tick gaps, sprint hits, W let go - a frozen VexBot still jumps out
  of a combo and takes knockback, so it isn't still otherwise), a bot dropped from 6 blocks (no
  hit while it falls), and a 300-tick real fight in survival, both with diamond swords and
  resistance 3 + regeneration 4 (only sprint hits/crits, no non-deflection/punish hit on a
  falling bot, every hit taken on the ground answered with a jump reset or S (fly-out / S-tap
  deflection), at least one crit, at most 65% of the "chances" - charged, bot under the
  crosshair within 2.8, not falling - let go by; with the guide + VexBot features: 22 hits,
  19 of 41 chances let go); plus a tap-choice part (the test teleports the bot 0.12 a tick
  toward/away - VexBot's `goto` and `control ... move` didn't move it - S-tap toward, W-tap
  away; Uppercuts/Lunges off there). Jumps are read from `input.keyPresses.jump()` one tick
  after the hit (knockback lifts you too). VexBot traps: it's a player, so `runCommand`'s `execute as @p`
  can pick **the bot** (it got our sword) - the test names us (`runAsMe`); spawning is async
  (~1 s, i.e. ~60 ticks at the test tick rate); a bot that dies leaves the server (give it
  regeneration). `build.gradle`'s `prepareVexbot` copies the jar (`-PvexbotJar`) into the
  test client's mods after `deleteGameTestRunDir` and writes `vexbot_settings.json`
  `{"global":{"statsOn":false}}` - VexBot posts usage stats to its author's server every few
  seconds by default; its `/vexbot extensions` commands download add-ons (never used). The
  anticheat sweep's `AimAssist (Auto combo)` fights a VexBot **on Grim** (`anticheat.gradle`
  puts the jar and the same settings file on the Grim server; `comboAgainstVexBot`, 200
  ticks, counted in an UpdateListener - sampled between ticks a hit's full hurt time is
  already gone) and still a husk on Intave (VexBot is Fabric-only, Intave is Paper). First Grim
  run (2026-10-15): clean, 9 hits, 9/9 jump resets. Grim checks the bot too: only flag lines
  naming `Wurst-Bot` count (`AnticheatServer.CLIENT_NAME`), others are logged as "another
  player". `set("AimAssist", "Auto combo", true)` must come before that branch (it fought
  without the combo once: never sprinted, so never hit). BowAimbot's lead comes from `util/BallisticSolver`
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
- **Attacks (Grim HITBOX / Reach):** only attack inside the tick (UpdateEvent, HandleInput, the
  attack event) - never from `onRender`, the movement packet after it must carry the rotation the
  hit was aimed with - and only when a ray from the current eye position along the current look
  hits the target's current hitbox (+ pick radius) within `player.entityInteractionRange()`, with no
  block in between. No predicted positions, no extra range. Gate the attack on `util/HitCheck.isVerifiable`:
  the last-sent rotation (`yRotLast`/`xRotLast`) must hit 0.03 inside the hitbox sides and the
  current one the hitbox, from the current and the post-move eye position - the attack packet
  precedes this tick's movement packet, so hitting the moment a per-frame turn lands flagged
  Matrix HITBOX ("hit without any intersection", AimAssist, 2026-10-02). AimAssist and
  MaceAssist use it. (2026-10-15: it was 0.1 inside for both rotations - against a strafing,
  jumping VexBot that turned down 74% of the ticks with a hit to take, mostly by hundredths of a
  block; Grim's `Reach` accepts a hit if any one of the next, the last or a mixed look ray
  passes any box the target could have had on the client's screen. Now 46%, the rest mostly
  ticks where the sent rotation was off the target.)
- **Packet order (Grim PacketOrderE):** a slot change must never go out after an attack, interact,
  use, use-on-block, release, PlayerCommand or sneak/sprint input change in the same tick (tick =
  up to `ServerboundClientTickEndPacket`). `util/PacketOrder` tracks that from the sent packets and
  `MultiPlayerGameModeMixin` holds `ensureHasSentCarriedItem` back; vanilla's `tick()` sends it at
  the start of the next tick. Code that switches and then attacks/uses must check
  `PacketOrder.canChangeSlotNow()` and wait a tick if false (MaceAssist does), or the action goes
  out with the old item. `PacketBudgetTest` counts violations independently (MaceAssist swap-back
  scenario: 9/9 violations without the guard, 0 with it).
- **ScaffoldWalk Client-side = a real godbridge, any direction** (user, 2026-10-03; Hypixel
  bridging guide, LiquidBounce's GodBridge technique). Keys only move in 45° steps relative to
  the camera, so the camera faces bridge + 180 + 45k: straight bridges at 45° with S + A/D (pitch
  ~75.4, players 75.0-75.8), diagonals straight back with S only, other angles a staircase. A face
  is only clickable from in front, so the look must point back along both axes the staircase
  steps on. `KeyboardInputMixin` → `ScaffoldWalkHack.modifyInput` remaps the keys (your W keeps
  meaning the direction you faced; the server's input packet shows S+D/S like a player's) and
  sets sneak; the camera turns per frame with `CameraAim` and back when you stop. Clicks only on
  the real crosshair hit, and only when the sent rotation (`yRotLast/xRotLast`) hits the same face.
  `util/Godbridge` does the planning; things that each cost a round of falls to learn:
  - the window is ~1 tick per block (crosshair clears the top edge ~0.3 past it, where you fall) -
    a fixed pitch falls within 30 blocks (why humans jump every 8-10); the planner predicts tick
    positions with vanilla's movement (step = last * 0.546 + 0.098) and raycasts candidate pitches;
  - place where support runs out (any cell under the predicted hitbox, margin 0.05), not a fixed
    path; moving off a block's corner needs a stepping stone first, placed only from the edge;
  - you start falling in a tick only if nothing is under you **after** that tick's click
    (vertical movement is resolved first, at the tick-start position);
  - commit a click early, re-check every tick that it still hits **and** lands no later than the
    predicted support loss; one tick ahead only the rotation already aimed for works (yaw and
    pitch kept); 2+ ticks ahead the steered yaw has time to be sent; a yaw that changes between
    the sent and the current rotation misses near corners (yaw nudges per click: 500+ sim falls);
  - sneak edge protection shortens the step, not the velocity - predict from
    `getDeltaMovement() / 0.546`, never from the distance moved (slid off when sneak ended);
  - no window at all: sneak to the edge and plan there; still none near the edge: look straight
    at a face (rescue); shift the line sideways so it doesn't run along block corners
    (`chooseAnchor`); pick the stance by simulating each a few blocks ahead (`chooseStance`, ~2 ms
    once per bridge; planning ~0.02 ms/tick); keep planning while sliding after you let go.
  - smooth aim (user, 2026-10-03): corrections < 12° follow a critically damped spring
    (`Godbridge.spring`, omega 50/s; exact closed form), big turns stay humanized. The planner
    **predicts the camera** with that spring (`Cam.at`): a click at tick k needs the camera at k
    and at k-1 (= the rotation sent) on the face. Assuming an instant camera with omega 35 gave
    488 sim falls; the humanized ease-out took 4+ ticks for a few degrees. The "turn around before
    walking" gate is only for the start (during a rescue turn it dropped sneak mid-slide);
  - jumps like a godbridger (Hypixel: every 8-10 blocks, placing in the air): jump key via the
    input, right after a placement, only in rhythm (no sneak/rescue for 6 ticks), only if
    `jumpSafe` (a 3-block simulation from here) and the plan made with the jump has a click that
    catches the landing (`hasPlan`); `Body` models vanilla air movement (jump 0.42, gravity 0.08,
    drag 0.98, air accel 0.0196, friction 0.91, landing decided at the tick-start position, crouch
    eyes 1.27); the horizon is 14 ticks while jumping/in the air (8 on the ground - 14 everywhere
    made walking worse); stepping stones may be placed mid-air (on the ground only from the edge);
  - the gametest also fails on any packet type a bridging player wouldn't send, swings != block
    clicks, too many clicks, or no jump on the straight bridge;
  - found with an in-game flight recorder (2026-10-06): the planner must predict the camera
    **rounded to whole mouse steps** (`Cam.shownYaw/shownPitch`, the sim rounds to 0.15 too) -
    0.03° off the exact spring angle missed a block's edge; a kept plan must still be allowed
    (a stepping stone planned mid-jump was kept after landing and walked off); the sneak
    slowdown follows the crouching flag vanilla sets from the **previous** tick's sneak key
    (`Body.step` uses `crouching`), edge protection the current one; planned clicks must still
    hit with ±0.015 blocks of position (`SLACK`) - at 81° pitch 0.01 blocks moves the hit 0.07
    down the face. Still open: the 200° gametest bridge falls in ~1 of 2 runs near its end,
    apparently after W is released (the recorder didn't catch it while bridging);
  `GodbridgeTest` simulates every degree 0-359 from random offsets (0 falls, ~14% sneak ticks at
  odd angles, 0 for straight/diagonal); `ScaffoldWalkHackTest` (gametest) bridges north,
  north-east and 200° from a ledge in the air. Gametest tp: `"%d.5"` is the wrong block for
  negative coordinates - format `Vec3.atBottomCenterOf(pos)`.
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
   if the new one is >2 blocks closer). AimAssist (user spec, 2026-10-03): attackers first
   (hurt you within "Attacker memory", from `ClientboundDamageEventPacket` cause/direct IDs),
   then closest, then least health within 0.5 blocks of the closest; switches only on death,
   out of range/sight, the switch key, another entity attacking while the target isn't, or
   another attacker >0.5 blocks closer than an attacking target. A closer non-attacker or the
   mouse never takes the lock.
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
- **Trigger bot = real geometry, once per tick, in the tick.** It used to check sub-tick slices with
  both positions moved ahead along their velocities, plus once per rendered frame - hits on positions
  the server never saw (Grim "HITBOX: hit without any intersection", user report 2026-10-02). Now
  `isCrosshairOnTarget`: current eyes, current look, min(Trigger range, `entityInteractionRange()`),
  hitbox + pick radius, block line of sight. Don't bring the prediction back.
- **Mace aim = yaw only, smooth (user, 2026-10-12, second pass).** It never touches pitch (the
  player aims up/down). A PID onto the 3D hitbox with yaw-first, flick-on-urgent and aiming from
  predicted eyes was tried first and made macing *worse than off* ("snapping, off target"):
  urgent flicks up to 3600°/s, aiming from where the eyes would be (looks off on screen), tremor.
  Now `HumanAim.springYawStep` (via `CameraAim.turnYawSmoothly`): accel-limited (full speed in
  1/12 s - a plain critically damped spring starts at full speed on a big turn, its pull grows
  with the gap: 0 -> 126°/s in one frame at 180°/s), brakes in time (sqrt profile), last bit like
  a spring (omega = speed/15, 6..60/s), lands without overshoot, feed-forward of the target's
  sideways motion; Humanize = reaction delay on a new target only. `YawSpringTest` checks no
  overshoot, pitch untouched, no speed jump > speed/3 per frame, 90° in < 0.2 s at 1800,
  following a 40°/s target within 2°. On target = your horizontal direction crosses the
  footprint 0.1 inside its sides (directly above: always); held still there; a turn starts
  once off it, aims for the footprint's middle third, and ends once settled and the target isn't
  sliding > 5°/s across the view (hysteresis - stopping at the edge made it stop-start). Aim speed
  0.5-1800. **Approach target**: more than 3 blocks away sideways and reachable before you fall to
  its height (vanilla air physics) -> W held until within 3.
- **Fast dives (riptide, long falls):** the hit goes out from the eyes the server has at the next
  tick, and the rotation sent the tick before must hit from there too - with yaw-only aim the
  player's pitch decides that. Falling onto a target, aim range counts sideways and the "in front"
  filter is skipped (a 10k-block drop is never within 15 blocks in the air). Too fast to ever be
  in reach in the air (gravity 1: ~49 blocks/tick) the hit lands the tick after landing - a
  normal hit, no smash (the server resets fall distance on the landing packet), and only with
  "Trigger while falling only" off. The gravity attribute is capped at 1 by vanilla (`base set
  50` = 1). `HitCheck`'s post-move check ignores the downward pull while on the ground (with
  gravity 1 it put the eyes a block low). Gametest `MaceTriggerTest` (the test sets the pitch, Aim
  speed 1800): 40-block 3.5 blocks/tick dive from 1.5 blocks to the side facing 90° away (one
  tick in reach, hits in the air), a 10,000-block drop at max gravity 2 blocks to the side facing
  away (hits after landing), and the cobweb. Test mobs need `PersistenceRequired` - hostile mobs
  despawn instantly >128 blocks (3D) from players.
- **Cobweb on right click** (default on): a fresh right-click on a block with a sword puts a
  cobweb there from the hotbar (offhand: vanilla does it, the sword has no use), before Wind on
  right click. Placed the tick after the click and switched back the tick after that (vanilla's
  own sword click goes out in the click's tick; `PacketOrder`). Fresh clicks are `keyUse` held now
  but not last tick **or** a `RightClickEvent` this tick - polling `isDown` alone missed taps
  shorter than a tick (wind charges had the same blind spot).
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
  (PID aim vs the old ease-out: landing, overshoot, resting, strafers), `YawSpringTest`
  (MaceAssist's smooth yaw-only turn: no overshoot or speed jumps, tracking, resuming after
  `hold`).
- `src/gametest/` — in-game tests (`AltManagerTest`, `VanillaSpoofTest`, `KeyPresserTest`,
  `PacketBudgetTest`, `AutoMineHackTest`, `FreecamHackTest`, `NoFallHackTest`, `XRayHackTest`, …)
  run via `runClientGameTest` / `runClientGameTestWithMods`; the whole suite passes (exit 0,
  "Test complete" in `build/run/clientGameTest/logs/latest.log`). Every `SingleplayerTest` ends
  with a screenshot compared to one clean-world template, so a test must leave nothing behind:
  clear chat, the action bar (toggle announcements), particles, dropped items / XP, wait ~25 ticks
  for death animations and ~7 ticks after a `tp` rotation. Failed runs keep their PNGs in
  `build/run/clientGameTest/screenshots/` — look at them before guessing.
  The in-world tests run at `/tick rate` `WurstTest.TICK_RATE` (60), MaceAssist and ScaffoldWalk
  first. That only works because per-frame aim (`CameraAim`, AimAssist's frame aim, ScaffoldWalk's
  spring) runs on `util/GameClock` - ticks that actually ran + the frame's partial tick, 50 ms each -
  not `System.nanoTime`; the test client runs at most one tick per frame, so real time x tick rate
  would be wrong too. New per-frame aim code must use `GameClock.nanoTime()`.
- **Test client = performance mods + lowest graphics + tuned JVM (user, 2026-10-16).**
  `preparePerfMods` (build.gradle, after `deleteGameTestRunDir`) copies - never moves - the
  performance mods from `-PperfModsDir` (default the user's `.minecraft/mods`: Sodium, Sodium
  Extra, ImmediatelyFast, EntityCulling, MoreCulling, CullLeaves, BetterBlockEntities, Particle
  Core, lite-cpu, FerriteCore, ModernFix, SmoothJoin, Lithium, BadOptimizations, Debugify,
  Krypton, Ixeris, Fast IP Ping + cloth-config, fzzy_config, fabric-language-kotlin) and writes
  an `options.txt` with the lowest graphics, except what tested features change or need to show
  (vignette, bobbing, hurt tilt, screen/FOV effects, gamma, FOV, GUI scale). Left out: Dynamic
  FPS (throttles a background window; the client runs at most one tick per frame) and Moonrise
  (closing the test world deadlocked the harness on the first run). `-PnoPerfMods` /
  `gametest -noperfmods` runs without them; the screenshot templates are recorded with them.
  Sodium 0.9.2 threw "Global terrain uniforms have not been updated" on every Fabric test
  screenshot (the screenshot's extra `GameRenderer.render` skips the extract step where Sodium
  re-arms its uniforms) - gametest `SodiumUniformsMixin` re-arms them at the end of each frame.
  JVM: client `-Xms1G -Xmx3G`, servers `-Xms256M` + their old max, all G1 (pause 50 ms),
  `UseCompactObjectHeaders` (JDK 25), string dedup, `G1PeriodicGCInterval=15000`.
- `PacketBudgetTest` counts packets, swings per attack and aim settling in singleplayer; feel and
  accuracy still need a launch of the client.
- **Real anticheats (`AnticheatTest`, user, 2026-10-13).** The gametests also join two real
  servers, each its own process, and collect every flag: **GrimAC** on a Fabric 26.1.2 server
  (source `../Testing`) and **Intave** on Paper 26.1.2 (source `../Testing2`). `gradle/anticheat.gradle`
  builds both from source (only when it changed; Intave's build needs git - a stub repo in
  `build/anticheat/intave-git` via `GIT_DIR`), downloads the Fabric server launcher / Paper 26.1.2
  (pinned, sha256), and prepares `build/anticheat/{grim,intave}` (fresh world each run, offline mode,
  flat, ports 25571/25572, nobody op - Intave gives ops its bypass trust factor; Grim verbose to
  console; Intave kick/ban commands emptied; bStats and update checks off). `-PnoAnticheat` /
  `-PgrimDir` / `-PintaveDir`; `gametest grim|intave|anticheat` runs just those. `AnticheatServer`
  starts the process, sends console commands (`query` waits for an answer line, e.g. a husk's
  health), reads Grim flags from the console ("Grim » X failed Check") and Intave's from
  `plugins/Intave/logs` ("(DET)" lines - Intave never prints them). Scenarios: baseline (no hacks,
  must stay clean), control (Flight, must flag - proves flags arrive), Killaura, MaceAssist,
  ScaffoldWalk; each reports what it did (husk damage, blocks placed) and the tick pacing.
  Things that cost a round each:
  - Grim **can't run in the integrated server**: packetevents attaches to every connection in the
    JVM, including the client's end of the singleplayer link, and its client side misreads the
    configuration packets (and its 26.x build has no client chain entrypoint - it crashed on an
    immutable list). Separate processes are the only accurate setup anyway.
  - Grim's bundled cloud-fabric asks for MC >=26.2; the test copy rewrites that to 26.1.2
    (`GrimJarPatch`; Groovy reads backslash-u escapes even inside regexes - normalise the JSON's
    `>` first). Grim then runs without commands (cloud fails at runtime) - fine, console
    commands are vanilla.
  - **Timer/TimerLimit flags at the join are server lag, not the client**: a fresh server goes
    silent for ~1 s after the first join; the client keeps ticking evenly (measured by
    `PacketTrace`: tick-end packets out, any packet in), and the timer check, whose clock is its
    own pings, sees the client ahead. A run with Wurst switched off also got them. So the join is
    its own report line (after a 5 s settle) and the baseline ignores timer flags only in a window
    where the server was silent > 500 ms while the client stayed <= 22 ticks/s.
  - Fabric's gametest harness can **deadlock closing the singleplayer world** while another heavy
    process starts (render thread in the deferred disconnect -> `IntegratedServer.halt`, server
    thread parked in the harness phaser): the servers are awaited before that world is created.
    It also hit 3 of 5 `gametest grim` runs (2026-10-15), where the world closed moments after
    it opened (setting the tick rate back to 20 first didn't help): when every `only` term just
    picks anticheat servers, no singleplayer world is made (`WurstTest.isAnticheatOnly`).
    `gametest.ps1` has a watchdog (no game log output for 4 min -> jstack to
    `build/gametest-hang.txt`, kill, FAILED), and the gametest `ConnectionMixin` logs every
    disconnect reason and connection exception (a silent disconnect used to hang the run).
  First results (2026-10-13): Killaura, MaceAssist (mace hit) and ScaffoldWalk clean on both;
  Intave once noted "halting game ticks" (+0 VL) after the Flight setbacks.
- **Every feature on both anticheats (`AnticheatFeatureSweep`, user, 2026-10-13):** each hack
  and command has its own test built for what it does (a pool for Jesus, a ladder for FastLadder,
  ores for VeinMiner, a still 1024-health husk for the auras...) **and a check that it did it**
  (husk lost health, bot stayed on the water, vein gone, packets of the right kind sent, server's
  copy of an item changed, the screen changed). A failed check is "DIDN'T WORK" in the report
  next to the flags - a clean feature that never acted means nothing. Only the four core
  scenarios fail the run. `FeatureTestKit` holds the helpers; rules that each cost a round:
  - Speed: a test runs until its effect shows (timeout), builds only its own scene, and the next
    test's `begin()` undoes only what this one dirtied (arena, inventory, effects, game mode,
    time, weather, camera, settings via `set()` restored from JSON). 8 ticks after each for late
    flags. ~6.5 min for all 204 per server. The first server is started in Fabric's
    **preLaunch** (`AnticheatServers`), so it boots with the client; settled = 8 quick answers.
  - Flags before the feature starts (its first `on()`/typed command) are setup (teleports, the
    last test's leftovers): set aside and listed as "set aside as setup", not counted.
  - Code inside `waitUntil`/`maxOver` lambdas runs on the client thread: use `rel(mc)`, never
    `pos()`/`client()` (those call `computeOnClient` and throw there).
  - `at()` waits for the client's AcceptTeleportation, not the position (in the air it falls at
    once). Mobs killed by cleanup linger client-side while dying: only count `isAlive()` ones.
  - Render hacks: `screenChange` = two screenshots off, one on (all with HUD list and toggle
    messages hidden, toasts and particles cleared); on must differ by > 1.3 x the off/off noise.
    Both "off" shots come before enabling - Fullbright fades out after it's off. Rain fades for
    seconds, so NoWeather runs last and cleanup waits for it.
  - Commands that change items in creative are checked on the **server's** copy too.
  - **Walking probe** before every test (4 ticks of W): some features leave the anticheat
    rejecting every move afterwards (Grim after ArrowDMG's packet burst: client normal, every
    step a Simulation setback) and every later test inherited it. A failed probe notes "couldn't
    walk after <test>" with the client's state, and the bot reconnects (`AnticheatTest.reconnect`).
  - Setup must not trip the anticheat itself: rebuild the arena with the bot **outside** it
    (floor changes reach Grim's world copy and the client a tick apart = 0.0784 Simulation
    setback), let the bot stop before teleporting (momentum = 0.6 setback), and wait for 3
    on-ground ticks in a row (a late setback lifted it: "Can't jump/build in mid-air").
  - Cleanup always re-selects slot 0 **with the sync** (after AutoSwitch the server held another
    slot: Throw/AirPlace clicked with an empty hand). Keys for jumps are held >= 2 ticks
    (`pressKey` is press+release inside one tick - never seen as held). Measure an effect while
    it happens (`maxOver` from the action on): a setback a tick later erased jumps measured
    afterwards. Many targeting hacks skip hostile mobs by default (AnchorAura, CrystalAura,
    Follow, RemoteView: "Filter hostile mobs") - set it for the husk.
  - Failed non-render tests save `ac_<server>_fail_<test>.png` - look before guessing.
    A failed check with flags reads "DIDN'T WORK (flagged - the anticheat likely undid it)".
  - **Clean record per feature:** anticheats hold a grudge - Intave reports a movement only once
    the player's physics VL is past 50, and after the Flight control it sat around 400, so every
    0.0784 mis-prediction of later features became a flag and a setback. After a test that
    flagged, the next one reconnects first (`AnticheatTest.lastFlagged`). Results no longer
    depend on test order.
  - `lookAt()` turns with real mouse movement (`moveCursor`, whole mouse steps); the server's
    `/rotate` made the client send an extra rotation packet mid-tick (Grim AimDuplicateLook /
    TickTimer, Intave "2 packets without client tick end", blamed on the feature). The first
    mouse move of a run can get lost: it falls back to `Rotation.applyToClientPlayer`.
  - `-Pfeatures=a,b` / `gametest grim -features a,b`: only those features' tests (plus baseline
    and control) - ~2-3 min per server instead of ~11. Never compile while a run is loading
    classes (NoClassDefFoundError mid-run).
  Found and fixed (2026-10-13/14, verified on both servers):
  - SkinDerp never told the server (26.1's `Options.setModelPart` only sets the option): now
    `broadcastOptions()`, and it restores your own skin parts instead of turning all on.
  - NameProtect didn't hide names already in chat (lines are laid out when they arrive): it
    re-lays the chat out on toggle (`IChatComponent.refreshTrimmedMessages`).
  - NoShieldOverlay never moved the raised shield: the blocking hook targeted
    `applyItemArmTransform` ordinal 3, which in 26.1.2 is the riptide spin - now ordinal 1 (the
    transform before the `getUseAnimation()` switch).
  - `.potion/.rename/.author/.enchant` changed the item on the client only: now
    `InventoryUtils.setCreativeStack` like `.repair`.
  - `.sv` crashed when the server wasn't joined from the server list: `ConnectScreenMixin`
    remembers every join (Quick Play, Reconnect, other mods), `.sv` errors instead of throwing.
    The test joins via `ConnectScreen.startConnecting` and exercises that.
  - Criticals Mini/Full Jump faked movement (Mini: velocity push) or only jumped after the hit:
    now the clicked hit is held back (`LeftClickEvent` cancelled), the jump key pressed, and the
    hit sent on the way down - from the **2nd** falling tick (the hit precedes the tick's
    movement packet, so on the 1st the server still has you at the peak: no critical on
    Paper/Intave). Full Jump also waits for a full recharge. Clean criticals on both servers.
  - Blink held only movement: now everything at the connection level
    (`ConnectionPacketOutputListener`), switching right after a tick end, released all at once
    on the next tick - indistinguishable from a lag spike. Clean on both (hack and `.blink`).
  - Instacart did everything in one tick (two turns the server never saw; the cart went out with
    the rail selected because `PacketOrder` holds a slot change after a click): now one step per
    tick - turn + rail slot, rail, cart slot + cart, back. Clean on both. (The test's
    `tnt_minecart 4` was refused: minecarts stack to 1.)
  Found and fixed (2026-10-16 sweep):
  - Grim WrongBreak (`CANCELLED_DIGGING, last=null`) once per mining hack: Nuker, AutoFarm,
    AutoLibrarian, TreeBot, Tunneller and VeinMiner forced `isDestroying = true` before
    `stopDestroyBlock()` on disable - an abort for a dig the server never had. Gone.
  - Intave interactionraytrace ("broke X, but looking at a different block"): the break went
    out in the tick the camera turned to the block, before the movement packet with that turn.
    `BlockBreaker.isTurnPending(pos)` (the rotation about to be sent is on it, the last sent
    one isn't -> wait a tick) in every mining hack; line of sight is now the crosshair's
    (OUTLINE shapes, first block hit = the target) instead of collision shapes.
  - NukerLegit called `startDestroyBlock` itself, skipping vanilla's 5-tick `destroyDelay`
    (Grim FastBreak, Intave "started breaking too quickly") - only `continueDestroyBlock` now.
  - FightBot (Range 4.25 -> Grim Reach at 4.19) and Protect (any look within 7 -> Intave
    "attacked out of sight") gate hits on `HitCheck.isVerifiable`.
  - RainbowUI only changed the old `acColor`, which the Beast UI doesn't draw with - it now
    turns `BeastColors.gradientAt`'s wave into a moving rainbow.
  - Test fixes: BunnyHop's default "Jump if" is Sprinting (the test only held W); NoVignette
    can't show on screenshots (the test client's `vignette.png` is blank on purpose).
  Grim's every-step setbacks of the path-AI walk (FightBot, Protect, AntiAFK) were its grudge
  from earlier tests: with a clean record they walk and fight on Grim too. Still open: Kaboom,
  SpeedNuker and `.damage` get the bot kicked (packet spam by design).
