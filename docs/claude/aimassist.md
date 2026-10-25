# Aiming at entities: HumanAim, AimAssist, BowAimbot

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

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
  instead of 13); hold W, hit, release W - between hits it stays out of **their** reach
  (user, 2026-10-17: knocked up and back, "the hypotenuse is always longer than the leg" -
  `theirReachToUs()` = their eyes to our hitbox, so a target in the air has to come closer
  than we do) with S while that's under 3.0 + `THEIR_REACH_MARGIN` 1.0 and they aren't backing
  off, coasts otherwise, and sets off with W + sprint only `SPRINT_LEAD_TICKS` (3) +
  `approachTicks(dist)` before the charge, so it arrives as the hit charges (it chased from
  3.0 and backed off under 2.0: a VexBot landed 11 of its 16 hits during our cooldown from
  0.3-2.4 blocks; score 18:16 -> 19:10; the fight logs every hit taken with both reaches);
  Hops only replace backing off when too close (they hopped at any distance from 1.5);
  **Combat style** (user, 2026-10-17, "HT1 bot" = Minecraft Combat AI's ECHO v29 in Janus's
  video `Wkz9kOb3mt0`, beat HT1s 45:18; its on-screen overlay shows distance, phase
  closing/opening/holding, its 3x3 movement keys and JUMP/SPRT/HIT/SNEAK per frame - read at
  10 fps): Sprint combo (default) or **Crit trader** = ECHO's rules - at the reach edge
  strafing, no W inside it, jump whenever the charge is <= 6 ticks away (falls = crit as it
  charges), hit from ~3 blocks, S + sneak 3 ticks after the hit. Against VexBot (which sticks
  at 0.5-1.7 blocks) Sprint combo 20:13, Crit trader 10 (7 crits):13 - roughly even in damage;
  `AimAssistComboTest.critTraderFight` logs both; **Adaptive** (default, user 2026-10-17
  "adapt to who it's fighting"; then "as fast as possible and as smart as possible"):
  `hacks/aimassist/OpponentModel` (no MC classes; `OpponentModelTest`), one per opponent
  **UUID for the session** (`opponentModels`; a rematch starts adapted; `forgetOpponents()`
  for tests). Learns on every hit either way: a discounted UCB1 bandit (discount 0.88,
  2-exchange dwell) picks the style from hits landed (crit 1.5) minus taken - credited to the
  style actually played, so fixed-style play counts too - nudged by what the opponent showed;
  EMA rates (alpha 0.35, the first sample replaces the 0.5 prior) of countering our hit within
  6 ticks, shrugging our knockback (< 0.8 blocks after 3 ticks = jump reset), and winning
  races; Adaptive then backs off with S + sneak after hits against a counterer/shrugger and
  waits 0.6-1.4 blocks outside their reach (by races lost) instead of the fixed 1.0. (The
  first version, 120-tick windows, only chose after 12 s; an adaptive VexBot learns too.)
  `AimAssistComboTest.rematchFight` fights the same bot again and logs both openings.
  **VexBot's playbook, from its jar** (user, 2026-10-17: Vex was combo-locking AimAssist): its
  crits land from up to attackRange + 1 (a server-side bot, never reach-checked - measured
  3.05-3.65 eye-to-hitbox), it jump-resets **before** your hit (your charge >= 0.85, facing
  it), holds W through your knockback and crits on the way down, jumps when being comboed,
  hit-selects 45% of cycles, feints. Copying its moves as checkboxes - Landing punish, Hit
  select (both off), Early crits (on), Dive bait (`DIVE_BAIT` false) - made AimAssist WORSE
  against it (23:31 with all on, 26:24 with the measured set); spacing on its learned longer
  reach made it passive (the model still learns `maxReach`/`diveRate`). The test's VexBot had
  kept the still-bot part's knockback resistance 1 and jump strength 0 through respawns (same
  UUID, saved attributes) - every fight before 2026-10-17 was against a bot taking no
  knockback; `spawnBot` resets both.
  **Races** (both charged, both in reach): `isChargedToStrike` strikes on
  vanilla's 0.9 threshold a tick sooner against a target that swung in the last 40 ticks, and
  falling with the hit charged and the target in reach keeps sprint (letting go for a crit
  left a tick of neither - the server still had us sprinting). VexBot fight (hardest
  settings, 600 ticks): 30:27 -> 36:25, longest combo ours 7 / theirs 4; the remaining lost
  races all had our look not verifiable (the sent rotation off a strafing bot at 2.6-3.0).
  **VexBot as hard as it gets** (user: "absolute highest difficulty ... excluding gear"):
  from its jar - difficulty PERFECT (no misses, 0-tick reactions, perfect crit timing, full
  reach; `vexbot_settings.json` sets it, `vexbot difficulty <preset>` doesn't exist - that
  command takes knobs), `vexbot settings jumpresetchance 100` / `counterstunchance 100`
  (defaults 50/30), `strafeincombo true`, `vexbot playstyle adaptive Vex` (style first;
  hardest of aggressive/adaptive/default); reach stays vanilla 3.0 (up to 6 = a reach hack).
  The user saw the first seconds of every fight go to AimAssist ("Vex isn't doing anything")
  and then VexBot lock it in combos - so the fight logs hits per 100 ticks and the longest
  combo each side, and records one screenshot every 2 ticks (`recordFight`; the harness keeps
  client and server in lockstep) for an mp4; sprint reset = W-tap, or S-tap when still under 2.3 after the hit; **every chance at a
  sprint hit or crit is taken** (user, 2026-10-16: "100% of the time, unless a setting says
  not to" - `isHitChance` runs before the ground combo's tricks and in every combo phase; only
  Baits, Aura-Farming on the ground, Shield breaker and Skip falling targets hold one back;
  `AimAssistComboTest` records every tick a sprint hit/crit would land and HitCheck passes
  and fails on any missed: 17 of 17 taken); **Skip falling targets** (checkbox, off by
  default - user; all "ticks since X" timers start at `NEVER`
  = -1,000,000: with `Integer.MIN_VALUE`, `tickCount - lastX` overflowed and Crouch dodge,
  Fly-outs and Weak hits never fired - found 2026-10-17; Crouch dodge now runs before the
  look-away gate in every phase, skipped only for a hit that can go out now): no hit on a
  falling target (falling knockback sends them into the ground and
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
  no S into a wall (MeleeWallGuard), opt-in Combo strafe and Shield block (**Shield source**,
  user 2026-10-17 - AutoTotem holds the offhand: Shield while healthy (default; a hotbar shield
  into the offhand right after our hit via select + swap-hands key + back, while in a fight -
  target or a hit in the last 3 s - and health >= "Shield swap health" (+1 heart to swap in);
  AutoTotem yields while `isKeepingShieldInOffhand()`), Hotbar shield (main-hand shield to
  block, weapon back after - every switch restarts the cooldown), Always totem, Always
  shield; tested in `AimAssistComboTest` with the user's setup: shield in the offhand 292 of
  307 ticks, totem back after). Its mace/crystal/
  elytra/pearl/potion/loot AIs are outside AimAssist. Also fixed: the combo reset (and let go
  of W) whenever the crosshair slipped off the target for a tick, so the next hit came 2+ ticks
  late - now only looking 45°+ away resets it; and no attack while using an item. Gametest `AimAssistComboTest` fights a **VexBot** (user,
  2026-10-15: a server-side Fabric PvP bot mod, `../vexbot-3.0.14mc26.1.2.jar`, MIT; it
  w-taps, strafes, jumps, crits and jump-resets): a frozen bot with jump strength 0 and
  knockback resistance 1 (12-tick gaps, sprint hits, W let go - a frozen VexBot still jumps out
  of a combo and takes knockback, so it isn't still otherwise; **the bot respawns with the
  same UUID and its saved attributes** - every later fight had knockback resistance 1 and
  jump strength 0 until 2026-10-17, user: "it looks like vex is taking no KB"; `spawnBot`
  logs and resets both to vanilla, and the jitter limit is the bot's own zig-zag + 1 point
  (min 2%) since a bot that really bounces zig-zags itself), a bot dropped from 6 blocks (no
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
