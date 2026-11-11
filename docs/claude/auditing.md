# Auditing: finding bugs, inefficiencies and extra packets

<!-- Moved out of CLAUDE.md (2026-10-30) so it isn't loaded every session; read it when the task needs it. -->


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
