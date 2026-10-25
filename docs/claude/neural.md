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
  same seeds and only while it does better in the real fight. The general net trains against
  every copied opponent **every 3rd slice during fights and after them** (`generalSlice`,
  150 gens a round, kept only if it beats its base >= 50% and doesn't do worse vs the copies;
  `adoptNewerGeneral` picks up the nightly trainer's newer champion).
  Files in `.minecraft/wurst/neural/`: `opponents/<uuid>.bin` (format BOP2: magic, shipped
  hash, reach, samples seen, clone net, tuned net, the FightAdapter offset; BOP1 still loads),
  `aimassist_learned.bin` (shipped net's hash, general net, version - a different shipped net
  = ignored). Saved 30 s after the last fight (never in the tick), at most 32 opponents.
  `OnlineLearnerTest`: copy fidelity, tuned beats general vs the copy by 2+ points on the same
  seeds (80 -> 84%; vs the real opponent 74 -> 76%), the in-fight offset survives save/load.
- **Offline, self-play only - `ContinuousTrainer`** (src/test, `NeuralTrainer forever <dir>
  [HH:mm]`): rounds of 300 ES gens in a league (`trainer/hall.bin`, up to 30 past champions +
  itself, PFSP), 128 pairs x 24 games; the candidate duels the champion over 4000 games:
  >= 53% = new champion (written to `aimassist_learned.bin`, AimAssist takes it at the next
  start or after its next fight), >= 47% = keep training from it, else back to the champion.
  **No more teacher** unless it's doing something terribly wrong: yardstick vs the scripted
  fighters (400 games, fixed seed) mean < 60% or any < 40% -> one guided round; a candidate
  that drops 5+ points on the yardstick isn't promoted (forgetting gate). Stops on
  `trainer/stop`, at the given time, or Ctrl+C; one trainer at a time (`trainer/lock`, `pid`).
  Log: `trainer/train.log`.
  **Commands** (repo root; `scripts/train.ps1`, the jar is `gradlew neuralTrainerJar` ->
  `build/neural/beast-trainer.jar`, copied to `trainer/beast-trainer.jar`, rebuilt at each start
  and the old one kept if the build fails): `train` (all cores, below-normal priority, 1 GB),
  `train -Until 07:00`, **`train lite`** (user: "super lightweight ... practically no system
  resources ... in the background while I'm doing other stuff": 1 thread, `-Dnn.duty=0.1` =
  works 10% of the time and sleeps 9x as long as it worked after every generation and around
  the duel/yardstick (`NeuralTrainer.dutyPause`), 16 pairs x 12 games, idle priority,
  `-Xmx128m` SerialGC - measured 1.3% of the PC's CPU, 54 MB, ~670 fights/s), `stoptrain`
  (= `train stop`: stop file, waits for the round check, kills after 60 s), `train status`,
  `train install` / `uninstall`.
  **Nightly** (user: 22:30-07:30): scheduled task "Beast Client nightly training", daily 22:30,
  start-when-available, 9.5 h limit, runs `train.ps1 nightly`: skips (noted in
  `trainer/nightly.log`) if already training, Minecraft running, < 2 GB RAM free, CPU > 30%
  over 10 s, or on battery; trains at idle priority until 07:30 and stops within 30 s if
  Minecraft starts. Minecraft is detected by window title (`^Minecraft\*? \d` - the launcher's
  is "Minecraft Launcher") and, where readable, the java command line (here it's empty for
  processes of other sessions).
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
