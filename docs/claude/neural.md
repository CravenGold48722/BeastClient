# AimAssist neural network, PvpSim and the trainer

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

### AimAssist "Neural network" (user, 2026-10-19)

User: "a smart neural network trained by us ... testing it against itself ... as little system
resources as possible ... STEER IT USING ACTUAL SWORD PVP TACTICS ... speed up the time ...
reaction speed way faster than a human's ... aim humanized and smooth (but not TOO smooth)".
Checkbox under Combat style; with Auto attack it replaces Auto combo's rules (`updateNeural`):
every tick the net picks W/S, A/D, jump, sprint, sneak and attack - through the same keys
(`holdForward`/`holdDodge`/`KeyPresser`) and the same `canLandGoodHit` + `attackTarget` gate
(sprint hits or crits, HitCheck) as the combo; no reaction delay (a human needs 4-5 ticks).
The camera stays HumanAim's (the user's aim settings). Combo-only reactions (jump reset in
`reactToTargetHit`, crouch dodge, Dodging, shield) stand down while it's on.
- `hacks/aimassist/neural/` (shipped, no MC classes): `NeuralNet` (MLP, one flat float array,
  Pade tanh, no allocation per pass), `NeuralObservation` (26 inputs relative to the line to
  the opponent, scaled ~-1..1, `VERSION` - bump it when inputs change, old weights then refuse
  to load), `NeuralAction` (3-way W/S, 3-way A/D, 4 keys), `NeuralPolicy` (loads
  `assets/wurst/neural/aimassist_pvp.bin`). AimAssist's `fillNeuralObservation` must give
  exactly what `PvpSim.observe` gives (velocities of the target = `targetMotion`'s EMA, hurt
  time 10 on the tick the hit arrives, `wasSprinting`, ...).
- `PvpSim` (shipped since in-game learning uses it; `Backprop` too) - vanilla 26.1.2 rules read with `javap -c`
  (damage 7 x (0.2 + 0.8 s^2), crit x1.5, knockback 0.4 + 0.5 sprint along the look, attacker
  x0.6 and sprint off, 10-tick immunity, armor formula, input x0.98 / sneak x0.3 / square
  rescale, ground 0.1/0.13 friction 0.546, air 0.02/0.026 drag 0.91, gravity, jump 0.42 +
  0.2 sprint) plus a lagging, noisy camera and 0-2 ticks of view delay; knockback reaches the
  victim at the start of its next tick (a velocity packet) so jump resets can happen - with it
  applied in the hit's tick a jump reset was impossible. `PvpSimTest` pins jump peak 1.2522,
  crit damage, sprint knockback, immunity. ~1.1M ticks/s per core (~54,000x real time),
  ~16,000 whole fights/s on 7 threads (22-25k/s on 8 after the speedups).
- `NeuralTrainer` (src/test, not shipped) (`gradlew trainNeuralAim`, `-PnnArgs="sweep 300"` / `"probe"`): DAgger
  imitation of `ScriptedPolicies.SprintCombo` (Beast's combo rules), then OpenAI-ES
  (antithetic pairs sharing opponents and seeds, centered ranks, Adam, cosine lr) in a league:
  5 scripted fighters + up to 30 past snapshots + itself, sampled by PFSP (AlphaStar: opponents
  it loses to more often); fitness = damage traded + win + sprint hits/crits/combos, early on
  + agreement with the teacher (fades by 25%). Best checkpoint by benchmark win rate.
  Strafe bonus (`STRAFE_SHARE` 0.6 of the in-range ticks) counts only the first `STRAFE_RUN`
  (25) ticks of each direction - it circled one way the whole fight (2026-10-31); `ItemRules`
  drops A/D beyond 5 blocks.
- Findings: every shape from 8 to 32+16 hidden units plays equally well (sweep, 2026-10-19),
  so the shipped net is the smallest, 26-8-10 (306 weights, 1.2 KB, ~350 ns a decision).
  Before the knockback-timing fix ES sat at ~55% vs Sprint combo whatever the population or
  sigma; after it, 2000 gens from imitation + 2000 resumed (`resume` mode, ~24.6M fights,
  ~30 min) reached Sprint combo 87%, CritTrader 96%, Aggressor 95%, Spammer 86%, Counter 68%
  (fresh seeds) and 68% against the 2000-gen version. Still climbing at the end - more
  `resume` rounds may help.
- `NeuralPolicyTest` (in `gradlew build`): the shipped net loads, beats every scripted
  fighter > 55% over 400 fresh fights, mirror match even, < 5 us a decision. Gametest
  `aimassistcombo -parts neural`: 300-tick VexBot fight (hardest settings), only sprint
  hits/crits. First run: 16 hits (7 crits) to 14; Adaptive Auto combo in the same run 14:9 -
  one fight each, not enough to rank them. Sim-to-real gap to watch: VexBot crits from
  3.05-3.65 (the sim's opponents keep vanilla 3.0).

**No preset styles, learning all the time** (user, 2026-10-19: "remove those [Sprint combo,
Crit trader...] and make it just adapt to every opponent it faces in real time ... even IN-GAME
it's actively TRAINING"; then "it should adapt WHILE FIGHTING, in real-time, even when it's
only training (against itself), and fighting against real opponents should also be TRAINING
it remembers"). With Neural network on, Combat style isn't used. Three layers of learning:
- **Inside a fight, every exchange - `FightAdapter`** (shipped, no MC classes): an offset on
  the 10 output biases (the key tendencies). Each test plays 4 reward events with +sigma x eps
  and 4 with -sigma x eps (Rademacher eps, sigma 0.1), reward +1 our hit / +1.5 crit / -1 hit
  taken, then moves the offset by `LR 0.1 x diff x eps` (decay 0.995, cap 0.6). Hidden biases
  too was worse (noise of ~15 hits a fight). No allocation; ~600 ns a decision with it.
  **The same adapter plays in every simulated fight of the training, both sides**
  (`OnlineLearner.NetPolicy`, `-Dnn.adapt=true` default), so the weights evolve to work with
  it; copies of real players (below) don't adapt. In game: `OnlineLearner.decide(profile,
  input)` on the client thread, `Profile.noteResult` (from AimAssist's `noteHitLanded`/
  `noteHitTaken`) feeds the reward; the offset is kept per opponent and saved.
  Honest numbers: `FightAdapterTest` (series of 8 fights per opponent, offset carried over)
  adapting vs fixed 77.7% vs 77.8% with v6, 79.2 vs 79.0 before - neutral in the simulator
  (~15 hit events a fight is little signal); a big config sweep found nothing better
  (76.9-79.3% everything). It's there because the user asked; it must never cost more than
  2 points (the test's assert).
- **During the fight, per opponent - `OnlineLearner`** (with "Learn in game", default on;
  "Learning CPU" 35% of one core, one min-priority thread): `OpponentRecorder` infers the
  opponent's keys from their movement/jumps/sprint/sneak/swings and the observation they had;
  after 150 samples `Backprop` trains a clone of them, then ES tunes a copy of the net against
  that clone (reach as seen); the tuned net plays only once it beats the previous one on the
  same seeds by 2+ points **and wins >= 50% against the general net** (soundness gate: with
  champion v1 vs Counter every tuned net beat the copy 88-93% but lost 38-50% to the general
  net and did 48-59% vs the real one, general 63% - it exploited where the copy plays wrong;
  2026-10-29), **and only while the general net beats the copy < 85%** (`SATURATED`, measured
  once per copy: v2 vs Counter was 90.5% real, its tuned net 83% though it passed the gate -
  2026-10-31), **and only against a faithful copy** (`copyFaithful`: after 60 real full hits
  while the general net played - `Profile.noteHit`, from AimAssist - its hit share vs the
  copy must be within 0.06 of the real one; else no ES at all. v4: every scripted fighter's
  copy was beaten 64-72% where the real one loses 88-100%, share 0.08-0.19 lower; its tuned
  net vs Counter beat the copy 68% and fell 88 -> 78% vs the real one - 2026-11-02), and
  only while it does better in the real fight. The general net trains against
  every copied opponent **every 3rd slice during fights and after them** (`generalSlice`,
  150 gens a round, kept only if it beats its base >= 50% and doesn't do worse vs the copies;
  `adoptNewerGeneral` picks up the nightly trainer's newer champion).
  Files in `.minecraft/wurst/neural/`: `opponents/<uuid>.bin` (format BOP2: magic, shipped
  hash, reach, samples seen, clone net, tuned net, the FightAdapter offset; BOP1 still loads),
  **Copy fixes (2026-11-02)**, found by comparing scripted fighters with their copies (v4 beat
  them 88-100%, their copies 64-72%): `OpponentRecorder` skips W/S/A/D while they're in a web
  or using an item (`slowed`; 75% of Counter's "no key" labels were W/S held in a web, 10%
  eating), records webs/water/potions/pearls/wind charges (`items` bits; AimAssist: block
  updates on netty -> `placedWebs`/`placedWater`, projectiles with owner = them on the next
  tick; a copy used the general net's raw item outputs: 24 webs a fight to Counter's 18) and
  writes one sample per tick (all labels of a view; 3 a tick filled the 4000 in a minute).
  `NetPolicy.copy` draws its one-off actions (jump, attack, items) with the training's
  `POS_*` weight taken out of the logit, and holds W/S and A/D like a player (`drawKey`, a
  fresh draw every ~6 ticks or when the held key drops under 20%): a copy of Aggressor swung
  on 92% of the ticks it could hit, Aggressor on 17%. Still not faithful: a copy holds S where
  it ends up (Counter's copy S 55% of the ticks, Counter 28%) - behaviour cloning drifts off
  the states it learned from; the fidelity gate catches it. **Style changes**
  (`styleZ`, user: "compare recent play (last 30 sec) to stuff before, and only use the recent
  if it has changed significantly"): every move is scored by the copy before it learns it
  (`Profile.scoreNew`, `Backprop.surprise` over keys/jump/sprint/sneak/hits, not items); once
  per 30+ s of new moves all moves since the last check are compared with the 4 windows of
  already-checked moves before (`styleBase`, >= 3 needed): 3.5x their spread (at least 0.16
  nats) up = stands out; the next check must stay 2x up against that same base (kept, the
  ring may have dropped it) = changed -> `keepNewest` of both windows (the copy forgets the
  rest) and retrains on them. Lone spikes were false alarms against v5/v6 (SprintCombo +0.875,
  Spammer +0.729; the next window -0.2 to -0.7 spreads); a real switch +1.4..+2.7 then
  +0.42..+0.93 (the copy already learned from the first). Noticed within 2 min of their
  moves; seed sets 0, 3500, 7000 pass (2026-11-03). Counting key frequencies was useless (Aggressor
  sprinted 19-64% across its own windows). Subtle switches (Aggressor <-> Counter, W 86%
  both) only sometimes - the copy relearns every 10 s anyway. Real-hit share for the gate is
  the last 120 hits.
  `aimassist_learned.bin` (shipped net's hash, general net, version - a different shipped net
  = ignored). Saved 30 s after the last fight (never in the tick), at most 32 opponents.
  `OnlineLearnerTest`: copy fidelity, tuned beats general vs the copy by 2+ points on the same
  seeds (80 -> 84%; vs the real opponent 74 -> 76%) and no worse vs the real one - or none kept
  (v1 vs Counter: none passes the gate, general plays on); the in-fight offset survives save/load.
  `NeuralPolicyTest`: shipped net >= 50% vs every scripted fighter (was > 55%; v1 is 50.0% vs
  Counter on its fixed seed, 55-57% in the trainer's yardstick); `aDecisionTakesMicroseconds`
  (< 5 us) fails while the trainer is running (5.6-6.8 us) - run it with the trainer stopped.
- **Offline, self-play only - `ContinuousTrainer`** (src/test, `NeuralTrainer forever <dir>
  [HH:mm]`): rounds of 300 ES gens in a league (`trainer/hall.bin`, up to 30 past champions +
  itself, PFSP), 128 pairs x 24 games; the candidate duels the champion over 4000 games:
  >= 53% (>= 50% at a stop) = a second duel on 16,000 fresh fights (`CONFIRM_GAMES`), which must
  reach 50.8% (2 standard errors, `CONFIRM_Z`; each kind >= 48%) - no promotions by luck
  (user, 2026-11-01); not when lite stops (the next run checks it). Then new champion (written
  to `aimassist_learned.bin`, AimAssist takes it at the next start or after its next fight),
  >= 47% = keep training from it, else back to the champion.
  **No more teacher** unless it's doing something terribly wrong: yardstick vs the scripted
  fighters (400 games, fixed seed) mean < 60% or any < 40% -> one guided round; a candidate
  that drops 5+ points on the yardstick isn't promoted (forgetting gate). Stops on
  `trainer/stop`, at the given time, or Ctrl+C; one trainer at a time (`trainer/lock`, `pid`).
  Log: `trainer/train.log`.
  **Commands** (repo root; `scripts/train.ps1`, the jar is `gradlew neuralTrainerJar` ->
  `build/neural/beast-trainer.jar`, copied to `trainer/beast-trainer.jar`, rebuilt at each start
  and the old one kept if the build fails): `train` (all cores, idle priority, 1 GB),
  `train -Until 07:00`, **`train lite`** (user: "super lightweight ... practically no system
  resources ... in the background while I'm doing other stuff": 1 thread, `-Dnn.duty=0.1` =
  works 10% of the time and sleeps 9x as long as it worked after every generation and around
  the duel/yardstick (`NeuralTrainer.dutyPause`), 16 pairs x 12 games, idle priority,
  `-Xmx128m` SerialGC - measured 1.3% of the PC's CPU, 54 MB, ~670 fights/s), `stoptrain`
  (= `train stop`: stop file, waits for the round check, kills after 60 s), `train status`,
  `train install` / `uninstall`.
  **24/7** (user, 2026-10-31): a scheduled run leaves `trainer/then-lite`; when it ends (its
  -Until, Minecraft starting, a crash), `ship-net.ps1` ships, then runs `train.ps1 continue` =
  lite until 5 min before the next scheduled start (07:55 / 22:55; none within 10 min of one) -
  before waiting for the game to close to swap the jar. A skipped nightly (busy, Minecraft,
  battery) starts that lite instead. `train stop` removes the marker (nothing restarts). Lite
  builds the trainer `--no-daemon` (no idle Gradle daemon) and runs with 2 JIT threads and a
  64 MB code cache.
  **Watchdog** (2026-11-01): task "Beast Client training watchdog" runs `train.ps1 watchdog`
  every 90 min - starts/upgrades to the right run (full in the windows, lite otherwise; logs
  `watchdog:` lines in nightly.log); `train stop` leaves `trainer\paused`, which holds it off
  until the next start. Task times are local wall-clock (`StartBoundary` without offset).
  **Nightly** (user, 2026-10-27: 23:00-07:00, then "Beast Client morning training" 08:00-14:30 via `train.ps1 start`, no checks): scheduled task "Beast Client nightly training", daily 23:00,
  start-when-available, 9 h limit, runs `train.ps1 nightly`: skips (noted in
  `trainer/nightly.log`) if already training, < 2 GB RAM free or on
  battery (no CPU check since 2026-11-03: a ship build made it skip to lite); trains at **idle** priority like every run (`$ScheduledPriority`;
  the morning task passes `-Priority Idle`; `start -Priority BelowNormal|Normal|AboveNormal` by
  hand if wanted - user, 2026-10-30: below-normal was tried and kept the test suite from
  finishing in 50 min) until its -Until time - Minecraft open or not (user, 2026-11-01: no
  more skipping or stopping for the game; idle priority lets it have the CPU first).
- **Shipped net v6 (2026-10-19)**: the self-play champion after ~7 rounds with the adapter in
  the loop. Head to head (4000 fights) 61% vs the first shipped net and 59% vs the one before
  it, but **lower against the scripted fighters** (Sprint combo 76% vs 88%, Counter 75% vs
  67%; `NeuralPolicyTest` numbers) - self-play optimises beating itself, not the scripts.
  Shipping a new `aimassist_pvp.bin` changes the shipped hash: learned files from the old one
  are ignored and the trainer's champion restarts at v0 from it (the hall stays).
- **Reaction speed** (user asked for "100 times a tick"): a decision is ~350 ns (~600 ns with
  the adapter, ~1.2 us while the trainer loads every core) = about 1/40,000 of a tick, made in
  the UpdateEvent with the state of that tick - a 0-tick reaction. The game reads keys and
  sends attacks once per tick and the server sees nothing in between, so deciding more often
  wouldn't change a single packet; the camera aim already runs every frame.

### Observation version 12: ECHO's inputs, history, recorded fights, far view (user, 2026-10-27)

ECHO = minecraftcombatai.com's V29 sword model (Janus's video "they made an AI that can crack ht1
players"; developer "skork"). Its public checkpoint `sdffewe/minecraft-combat-transformer`
(newgen6.pt, GPL-3.0) is a 96-tick transformer (d_model 192, 5 layers, 6 heads, ffn 640,
attention pool + aim pathway, 2,288,234 weights, 78 features x 2 = 156 inputs, 28 auxiliary
outcome heads), trained by behavior cloning on recorded server packets ("pvpnewset"), human
rows only, advantage-weighted (newgen6's config: hit_h 10 ticks, adv_taken_penalty 1.75,
awr_temperature 2). Read it without torch with a restricted unpickler (find_class allows only
the tensor-rebuild globals) - never `torch.load` an untrusted pickle. Its weights are NOT in
ours (user: compare only, for now).
- **Testing against ECHO** (user, 2026-11-01, "with the newest ECHO version"): the newest
  public one is `sdffewe/minecraft-combat-MLP` (2026-08-13, AGPL-3.0, plain JSON: 56 inputs x
  offsets 0,1,2,3,4,6,8,12,16 = 504 -> 256 -> 128 -> 64 ReLU, W/S + A/D softmax, sprint/jump
  thresholds, 45 more predictions). `EchoPolicy` (test side) plays it in PvpSim from
  `neural/trainer/echo/combat_move_model.json` (outside the repo); `java ... NeuralTrainer echo
  <neural folder> [games]` calibrates. Inputs fit (risk AUC 0.94), but it doesn't fight on its
  own: its moves are velocity labels (knockback = "back", standstill = "none"), so it won
  0.2-1.5% against v3 - not in the round report, since the number would be fake. The real
  ECHO (v32) is only on the paid minecraftcombatai.com API.
- **Inputs** (`NeuralObservation`, `SIZE` 343 = 171 + `ECHO` 33 + `CONTEXT` 48 + `FAR` 43 +
  `LONG` 48, all appended so `widen` kept v4 playing as before): `EchoFeatures` (no MC classes, the sim and
  AimAssist both call it) - pitches, signed aim errors both ways (mirror flips the yaw ones),
  their aim error and whether their look ray is on you, fall distances, weapon in hand, their
  sneak / swing / ticks since swings (yours too), eye distance, both pings (sim: 25 + 50 x view
  delay) and the server tick (AimAssist: `ClientboundSetTimePacket` rate), closing speed,
  speeds and directions, and ECHO's opponent model (aggression, spacing, swing interval, hit
  range; EMAs, kept per player for the session in AimAssist). ECHO's `schema_*` features are
  unnamed and left out.
- **History** (`NeuralHistory`, observation version 13: learned attention, user 2026-10-27 "add a
  transformer 96 tick thing to our current and keep going"; a full ECHO-size transformer -
  "step 2" - is never to be built, user). The first hidden layer (48) is averaged over
  `STEP` = 2 ticks (user 2026-10-28, for speed: 15-30% more fights/s) and projected to a
  query, key and value for each of 3 heads (16 each); the keys and values of the last 96
  steps (9.6 s) are kept, and each head's softmax over q.k/4 + a learned weight per age mixes
  the values into the context inputs (48), which the tick in between reuses. Its 7,560 weights are the end of the net's
  parameters (`NeuralNet.paramCount` = `layerCount` + `NeuralHistory.PARAMS`): ES trains them,
  Backprop leaves them (grads 0), `widen` copies them, version-12 files get
  `initialParams()`. Trainer: `VectorKernel.HistoryOps` (transposed projections, bit-identical,
  `simdHistoryIsThePlainOne`; one fused q/k/v projection, SIMD softmax with `exp`'s exact
  operations, the mix with each output chunk in a register; rings written backwards so ages
  run alongside slots). Cost: training ~3.5x slower per fight than before version 12 (side by
  side on 2 threads); `STEP` 2 and the SIMD rework (2026-10-28) won back ~1.3-1.5x, then
  +10% (flat value ring, 6-chunk projection, softmax with only intrinsified vector ops -
  masked int ops and `FloatVector.max` ran as slow Java fallbacks, 8% of the time -
  `NeuralHistory.sum` in 8 running sums for both paths, branch-free non-zero list, SIMD
  tanh, table-driven terrain mirroring in `encode`). Tried and found useless (2026-10-28):
  JVM flags (`UseCompactObjectHeaders`, bigger inlining limits: slower, no counted-loop
  safepoints; GC is ~0.1%), fewer threads (8 on the 4-core i7-8650U is fastest), batching
  the history across fighters (only its projection could share loads: ~1-2%). The net stays a plain MLP, so ES, FightAdapter, copies and
  Backprop work unchanged. `VectorKernel` copies layer 1 into the net's activations for it.
  AimAssist keeps the mirror image for the whole fight now (flipping mid-fight would mix two
  views in one history). A second, slow ring (one average a second, 120 = 2 minutes; ECHO's
  makers speak of a two-minute context) is pooled once a second into `memoryLong`. In game both
  rings are kept per player for the session (`resetFight` keeps the minutes), and the opponent
  models are saved per player in `neural/styles.bin` (`EchoFeatures.StyleBook`), so it
  remembers a player across sessions. Speed: the pool's two loops are element by element
  (transposed ring for the dots), the trainer runs them with SIMD (`NeuralHistory.Ops`,
  `VectorKernel.HistoryOps`, bit-identical); still the inputs and history roughly halve the
  trainer's fights/s (side by side on 2 threads: 24 vs ~52).
- **Adapting has to pay in training**: `ScriptedPolicies.Styled` gives the scripted fighters in
  training (`SCRIPTED_SHARE`) a random style each fight (spacing +-0.6, a strafe side habit,
  jumpiness, a hit delay 0-3 ticks, sneaking while charging, back-off 0-4 ticks); the yardstick
  keeps the plain ones. **`ScriptedPolicies.Switching`** (user, 2026-11-02: "a real person can
  change strategies mid-match"): in 40% of those fights another scripted fighter with its own
  style takes over 5-25 s in, half the time switching back 5-20 s later - so reading a change
  in the history inputs pays (`ScriptedPoliciesTest`).
- **Far view**: `TerrainSense.senseFar` (5x5 patch 6 blocks apart, rays to 48 in steps of 2),
  log distance (to 64), dy/16; 15% of duels start 14-28 blocks apart (around the middle).
- **Recorded fights** (`FightLog`, AimAssist "Record fights", default on): every fight against a
  player within 8 blocks, tick by tick, gzip, 16-bit floats, in `neural/fights` - your input +
  keys + human/network flag + damage so far, their samples (OpponentRecorder), and the raw
  packet events. Grounded in packets like ECHO's labels (AimAssist's packet tap, IDs only on
  netty): their movement from `getPositionCodec().getBase()` (the server's position, not the
  client's 3-tick smoothing), their swings from `ClientboundAnimatePacket`, knockback from
  `ClientboundSetEntityMotionPacket` (keys unknown for 8 ticks), your keys from the
  `ServerboundPlayerInputPacket` your client sent, your hits from `ServerboundAttackPacket`. The trainer
  (`ContinuousTrainer.learnFromFights`, every round): copies of players with 500+ samples join
  the league at `REAL_SHARE` 50% - as often as self-play, user 2026-10-29 (copy mode, any hit), and one epoch of advantage-weighted
  imitation (lr 0.0005, up to 20,000 samples) nudges the net toward the moves that paid off -
  yours where you played, theirs always. ES stays the teacher; promotion is unchanged.
  Ending a fight (`AimAssistHack.endFightLog`): a death, another target or 5 s out of range -
  also from the no-target and screen-open paths (before 2026-10-29 a fight ended by a kill
  stayed open, everything in the gzip buffer, and was lost when the game closed: both recorded
  fights were 10-byte files, so the trainer had learned nothing from real players). The file
  is sync-flushed every 100 ticks (a cut-off file reads up to there). Turning AimAssist off
  mid-fight keeps recording (`recordWhileOff`: the tick + packet tap stay registered, nothing
  aimed or pressed, rows marked human) until that fight ends.
- **Webs that never land** (user, 2026-11-03: "STILL walking backwards and not hitting"; 5 fights
  vs Eaglecpvp/SomaliCaptain67): the net asks for a web on 45-64% of the ticks (PvpSim: a web
  costs 1 tick); in game `AIM_WEB` turned the camera to their feet, timed out after 10 ticks and
  started over - 50+ ticks in a row "items busy" with the crosshair on the floor: every one of
  464 held-back hits had the items bit, 364 also unverifiable; 2-6 attacks a fight, dealt 0-6.6,
  taken 10-27. Now a missed web waits `WEB_RETRY_TICKS` 40 fight ticks before the next try. Still
  open: the sim's web is far cheaper than the game's (turning there takes up to 10 ticks) - make
  PvpSim's cost match and retrain. Walking backwards is the net's own spacing (S 51% in
  self-play, 66% on the real rows); resetting the inputs the sim never varies (gear difference
  x77/x78 - your toughness ~3.5 higher than theirs -, bystanders x69-71, their shield up x29,
  aim error/facing) changed nothing (`TmpFightCheckTest.whatDrivesS`).
- **`MoveRules`** (user, 2026-11-10: "fix the walking backwards and the one sided strafing";
  Blingcapy FT2 lost 0-2 by a few HP: S 38-51%, seconds of backing off 4-8 blocks out while
  they stood, in reach 43%; A/D 100% one side per fight - the net circles one way in its own
  frame, AimAssist's per-fight mirror makes that all-A or all-D): laid over the keys after
  `KeySmoother` and `ItemRules` in both PvpSim (`NetPolicy.finish`, not copies) and AimAssist.
  S beyond 3.5 blocks only while they close in (`opAlong` > 0.1) or when low with gapples, else
  W; A/D runs end after 15-40 ticks (the other side). A/B in PvpSim with the shipped net (400
  fights per scripted fighter, 1000 head to head): no rules 88.3% mean; this 88.3% (Counter 71
  vs 76), S beyond 3.5 blocks 31% -> 12% of the ticks (6% with them not closing in = eating);
  strafe alone 90.6%. Limiting S *inside* their reach (3-8 tick steps + rests) cost 7-9 points
  (79-83%): the step back after a hit while recharging is real spacing - left alone. AimAssist
  now records the last action after the rules, like PvpSim (it was before `ItemRules`).
- **Restocking** (user, 2026-10-29): `NeuralItems` remembers the kit item of each hotbar slot
  (`KINDS`: webs, gapples, pearls, wind charges, water buckets, the 3 splash pots); when one
  runs out (or a water bucket is left empty) and the main inventory has more: open the
  inventory (tick 1, `InventoryOpener`), one SWAP a tick into that slot, close (Esc) - 3 ticks
  for one item, no keys or hit meanwhile (AimAssist keeps ticking `tickRestock` while its own
  inventory screen is open). Water pickup clicks every tick until the bucket is full (was one
  click). `PvpSim` does the same pause (`restockPending`/`restockUntil`: a pot each - they
  don't stack - and every 64th golden apple; 2 + n ticks, no keys, busy).
  **It never ran until 2026-11-10:** the first `updateRestock` call comes straight from
  `setStage` (stageTicks 0) but the open branch checked tick 1 - so it went idle and started
  over every tick: "items busy" all fight, 0 attacks (McSimply, full kit: 273/273 ticks held
  back, dealt 0, taken 27.8). Now it opens at tick 0, and a failed open waits
  `RESTOCK_RETRY_TICKS` 40 instead of looping.
- **Gear is per fighter** (`PvpSim.Gear`: armor, toughness, Protection levels, sword damage,
  Sharpness bonus, Knockback strength, Fire Aspect ticks; `setGear`; user, 2026-10-30).
  **No Knockback II anywhere** ("nobody fights with it"): the default is no Knockback; the
  trainer gives Knockback I to 10% of its fights (`KNOCKBACK_SHARE`). In game, AimAssist reads
  both players' real gear every 20 ticks (`gearOf`: armor value, toughness, Protection levels,
  the sword's damage + Sharpness/Knockback/Fire Aspect levels as the server sent the items) into
  the opponent's `Profile.setGear`, and every learner practice fight (tuning, its soundness
  gate, the general net's rounds) runs in that gear (`fight`/`winRate` overloads; self-play in
  your gear, `noteMyGear`). Without Knockback II, v1 vs scripted rose to Counter 68% (was 50%).
- **The net sees the enchantments** (observation version 14, `NeuralObservation.GEAR` = 8
  inputs at the very end: Knockback x2, Sharpness/3, Protection/16, Fire Aspect/160, yours then
  theirs; `setGear` from `PvpSim.SimFighter.gear()` / AimAssist's `gearOf`; user, 2026-10-30:
  "Knockback I and none should be separate - it should tell the enchants apart"). Before, gear
  showed only as differences (`opWeapon`, `opToughness`). Added by `widen` at width 48 (343 ->
  351): new inputs zero weights, so v1 played as before. `widen` now also carries the champion
  over (`aimassist_learned.bin`, same number - it was read as v0 after a resize). Fight logs
  from version 12-13 still load: shorter rows padded with 0 (`FightLog.readX`). Version-13 nets
  were backed up in the session scratchpad before the widen.
- **Hits need the charge** (2026-10-31): AimAssist's neural gate let a hit out whenever it was
  a "sprint hit or crit" by state but never checked the charge - in vanilla both need > 90%. In
  real plain fights (glued together, in reach tick after tick) it hit every 1-4 ticks: 98 of 98
  hits uncharged, 55 into their hurt time, ~0.4 HP a hit; lost 4 rounds 12-16 vs 19 HP. Now
  `isChargedToStrike()` in the gate, and PvpSim's `onlyGoodHits` needs `strength(0.5) > 0.9`
  too (the sim had the same gap; there knockback usually separated them long enough). Also new:
  `FightLog.NO_HIT` (why a wanted hit didn't go out: AimAssist's NO_HIT_* bits, the charge, the
  distance, the slot) and `SKIPPED` (AimAssist skipped the net: screen open / shield break) -
  a fight with 230 hittable ticks and 0 hits (pcritterr, full kit, shield in the offhand) is
  still unexplained; the next recorded fight says why.
- **Plain vs enchanted, kept apart** (user, 2026-10-31): v2 tied v1 overall but won only 40.8% in
  enchanted gear (v1's home: it trained there all its life) - the promotion duel averaged a
  trade-off away. Now: training is 50% plain / 50% enchanted (`PvpSim.plainShare`); the
  promotion duel is 2,000 plain + 2,000 enchanted fights and a candidate needs the overall win
  **and >= 48% in each** (`ContinuousTrainer.duel`, `GROUP_FLOOR`; the round line shows both).
  With the enchantment inputs a trick that only helps plain fights can stay - used in plain
  fights only. v1 (the best enchanted net so far) is pinned in the league for good:
  `trainer/pinned.bin` goes into hall slot 0 at every start (never replaced; `widen` widens it).
- **Plain gear** (`PvpSim.PLAIN_SHARE` 0.35 of the trainer's fights, `plain`, `bareKit`; user,
  2026-10-30): plain diamond armor + diamond sword, no enchantments, no items/shield/axe. Every
  fight had Knockback II, Sharpness V, Protection IV, Fire Aspect II before, so the net learned
  hit-and-back-off; against a player in plain diamond who kept pressing it lost (the user's
  video 2026-10-29 22:21 + its fight log: it *did* press W 79%, sprint 77%, S 20% - the same as
  v1 in the sim with plain gear, where it also loses: 42% vs Counter). So it wasn't a key or
  input bug but untrained gear. The other fights now drop each enchantment 30-40% of the time.
  The net sees gear only as differences (`opWeapon`, `opToughness`), the rest from how hits land.
- **Varied kits** (`PvpSim.kitVariety`, `varyKit`; the trainer sets 0.5, `-Dnn.kitVariety`;
  tests and benchmarks 0 = the full kit): each item 40-80% present with 1..4 or 1..max, the
  shield 70%, a totem 40% (+0-3 spare). With the full kit in every fight, opponent shield,
  opponent totem, wind charges, pots etc. never changed in training; in the user's real fights
  (2026-10-29: no wind charges, no pots counted, opponents with totems and without a shield up)
  those inputs were 20-170 SDs off the training range and the net chose S 62-84% of the ticks
  (20-40% in the sim). Check with a replay of `neural/fights` (feature means real vs sim) when a
  real fight looks wrong.
- **Champion numbers survive a ship**: the checkpoint is keyed by the shipped net's hash, so a
  ship starts a new lineage - `readChampion` keeps the number when `aimassist_learned.bin`'s
  champion *is* the shipped net (was "v0" again after v1 shipped); `export` then says "already
  is" (exit 2, nothing rebuilt). The benchmark's reference is labelled "Champion vN"
  (`REFERENCE_NAME`, "OldNet" outside ContinuousTrainer).
- **No setting changes the network** (user, 2026-10-29): `isNeural()` is the Neural network
  checkbox alone (no Auto attack needed); the combo's settings (dodging, crouch dodge, fly-outs,
  combat style, aura farming, offhand-shield keeping) skip it; the shield breaker always runs for
  it (PvpSim does). Aim settings (smooth aim etc.) only shape the camera, which the net doesn't
  control.
- **Neural mode limits removed** (AimAssist): the net runs whatever the crosshair does (no 45
  degree / click gate), keeps its target while using items and behind cover (picked up only in
  sight). Range = the slider's max, FOV 360 were already so. Hits still need vanilla reach,
  `canLandGoodHit` (the sim trains only those) and HitCheck.
- Speed: FMA in `NeuralNet.forwardPlain` (`Math.fma`) and `VectorKernel` (bit-identical to each
  other; on this CPU neither faster nor slower); FFA opponents decide two at a time
  (`PvpSim.Batchable`, `VectorKernel.forwardPair`: each weight vector loaded once for both,
  ~1.6x a net; `pairSameAsPlain`); `OnlineLearner.parallelism` (tests: every core; game: 1);
  the far terrain every other space tick.
