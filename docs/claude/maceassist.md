# MaceAssistHack

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

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
- **Pearl catch, solved exactly** (user, 2026-10-17: "calculates the EXACT trajectory of the
  pearl ... and the angle you need to throw the wind-charge"; `util/PearlCatchSolver`,
  `PearlCatchSolverTest`). The catch = the **pearl** hitting your own wind charge (wind
  charges are pickable, pearls aren't) - you land at the pearl's position before that move,
  **keeping your velocity**. Vanilla, read from 26.1.2 and checked against server traces in
  the gametest (every predicted wind spawn = the server's; 5 of 5 traced throws predicted
  right):
  - both throws: `shootFromRotation` - float-trig direction (`Mth`), normalized, + random
    `triangle(0, 0.0172275)` per axis (server-side, unknowable), x1.5, + the thrower's
    **known movement** = the last movement packet's position change (`handleMovePlayer`
    `clientDeltaMovement`; ZERO if a tick had none), its y only if not on the ground; the
    server uses the rotation in the use packet (wrapped). The use goes out at tick start, so
    the known movement is the last tick's.
  - pearl from feet x/z, eye y - 0.1F: velocity -0.03 y, x0.99F, move; wind charge from eye
    height, constant velocity. Pearl's spawn packet comes **inside a bundle** (missed it at
    first: never exact, never fired) - it gives the exact start incl. the spread.
  - hit: the pearl's move must **enter** the wind charge's box (0.3125, from 0.15 below its
    position) grown by `clamp((tickCount - 2)/20, 0, 0.3)` - `getEntityHitResult` is
    `bb.clip(from, to)` only, starting inside misses (counting "inside" rated angled shots
    90%+ that missed). The pearl ticks first; the wind charge is at its previous position.
  The solver: every fire tick x meeting point, aimed centred, plus straight up and at the
  camera's yaw (no turn), only shots the camera can turn to in time (yaw+pitch distance -
  near straight up a yaw swing counted as 9 ticks made every shot late), rated by **hit
  rate over 200 spread samples x the server a tick early/on time/late** (client ticks vs
  server ticks; head-on meetings with the falling pearl miss on a tick, chases don't).
  Highest catch with 85%+, else the best rate. MaceAssist locks a plan (re-checked every
  tick; a new plan each tick made the camera chase), fires on the actual rotation's rate.
  Throws: steeper than 45° waits for a jump, flatter for a sprint-jump, thrown on the first
  airborne tick (the jump in the pearl's speed). Launch first waits for its own wind charge
  cooldown (<= 2 ticks) before throwing - the sure window is 2-4 ticks after the pearl.
  Gametest `MaceTriggerTest` (`gametest mace -parts catch` runs it alone, first): straight
  up 5/5 (~35 blocks), 70° 4/5, Launch first 3/5 (33); 30°/10° 0/5 in game though the
  simulation says ~95% - not found yet. A solve is ~1.7 ms. **Chaining** (checkbox, off):
  catch after catch until a fresh right-click of yours.
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
