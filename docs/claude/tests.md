# Test client, quick rounds and the anticheat tests

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

- **Test client = performance mods + lowest graphics + tuned JVM (user, 2026-10-16).**
  `preparePerfMods` (build.gradle, after `deleteGameTestRunDir`) copies - never moves - the
  performance mods from `-PperfModsDir` (default the user's `.minecraft/mods`: Sodium, Sodium
  Extra, ImmediatelyFast, EntityCulling, MoreCulling, CullLeaves, BetterBlockEntities, Particle
  Core, FerriteCore, ModernFix, SmoothJoin, Lithium, BadOptimizations, Debugify,
  Krypton, Ixeris, Fast IP Ping + cloth-config, fzzy_config, fabric-language-kotlin) and writes
  an `options.txt` with the lowest graphics, except what tested features change or need to show
  (vignette, bobbing, hurt tilt, screen/FOV effects, gamma, FOV, GUI scale). Left out: Dynamic
  FPS (throttles a background window; the client runs at most one tick per frame) and Moonrise
  (closing the test world deadlocked the harness on the first run). `-PnoPerfMods` /
  `gametest -noperfmods` runs without them; the screenshot templates are recorded with them.
  Sodium 0.9.2 threw "Global terrain uniforms have not been updated" on every Fabric test
  screenshot (the screenshot's extra `GameRenderer.render` skips the extract step where Sodium
  re-arms its uniforms) - gametest `SodiumUniformsMixin` re-arms them at the end of each frame.
  JVM (user, 2026-10-17: "set a memory limit, it was using 5GB"): client `-Xms512M -Xmx2G`,
  G1 (pause 50 ms), `UseCompactObjectHeaders` (JDK 25), string dedup, unused heap given back
  (`MinHeapFreeRatio=10`, `MaxHeapFreeRatio=25`, `G1PeriodicGCInterval=10000`), code cache
  192M; the Gradle daemon gives heap back too - peaks 1.4 GB client + 1.0 GB Gradle.
  Not lite-cpu either: it throttles chunk rebuilds whatever its config says ("profile:
  aggressive"). Screenshots still raced Sodium's own chunk rebuilds after a block change
  ("all drawn" before the rebuild was scheduled): `assertScreenshotEquals` retries a
  mismatch twice 10 ticks apart and records templates only after 20 ticks; `in_game` also
  waits for the wall's block to reach the client.
- **Quick rounds:** `gametest aimassistcombo -parts fight,rematch` (parts: still, falling,
  taps, fight, rematch, crit, shield; `gametest mace -parts catch` = the pearl catch test
  alone) and `-record` for fight screenshots (off by default - ~900 a run). A round is ~2.5
  min, ~1.5 of it game startup. Wait loops must not match their own command line (a
  `Get-CimInstance ... -match 'gametest.ps1'` loop found itself and never ended), and a `cut`
  in a Monitor pipe buffers (no events).
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
    `BlockBreaker.isTurnPending(pos)` in every mining hack and CrystalAura's placing: the
    first tick on a new block only turns, then it breaks once the last sent rotation is on it
    too (an "old look within 3 degrees" rule instead kept `.excavate` waiting forever while it
    walked up close; just "the old look reaches it" broke the wheat behind a just-broken one,
    which Intave still had in front); line of sight is now the crosshair's (OUTLINE shapes,
    first block hit = the target) instead of collision shapes. AutoFarm 17 -> 0 flags.
  - CrystalAura placed and hit in the tick it turned (Grim RotationPlace "post-flying"; on
    one run it never hurt the husk): placing waits for the turn, hits for `HitCheck`.
  - AntiWobble: 26.1's wobble is max(portal lerp, `getEffectBlendFactor(NAUSEA)`) - the mixin
    only zeroed the portal part, so nausea still wobbled; now both.
  - Test scenes that couldn't show the effect: NameTags (CustomNameVisible = vanilla shows
    it anyway; husk off the crosshair at 7 blocks, Scale 3), Overlay (obsidian by hand barely
    started - a pumpkin), NewChunks (only marks chunks where liquid starts flowing: a water
    source, and its own counter decides - the water's animation is 20% screen noise),
    NoFireOverlay (burn damage tilted the view in every shot - damage tilt off), `.dig`
    (the area starts at your own head's block: `dig 3 2 2` reaches 8 stone).
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
