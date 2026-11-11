# MaceAssistHack

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

### MaceAssistHack (COMBAT)

A port of the standalone **BetterMaceSwap** mod (`C:\Users\rbnst\BetterMaceSwap`, package
`com.ritesh`, keybind-driven with an AutoConfig screen) into one Wurst hack — every mod keybind
became a checkbox, every config value a slider or dropdown. Implements `UpdateListener`,
`RenderListener`, `PlayerAttacksEntityListener`, `LeftClickListener`, `RightClickListener`.

Features: attribute swapping (best Density/Breach mace, smart switch, swap-back delay, swap scope),
stun slam (axe shield break, mace smash the next tick - see below), pearl catching (pitch lock + wind
charge, return slot Previous/Sword/Axe/Elytra/**Pearl**), wind-on-right-click, air pots, lunge
swapping (spear flick), mace aim assist (now the shared per-frame `CameraAim` / `HumanAim` engine, the
same one AimAssist uses; "Humanize aim" switches the human-like curve on), mace trigger bot, auto
chestplate.

## Stun slam - what the videos teach (user, 2026-11-11)

Sources, both watched in full: XCOW "Stun Slamming Is Easy, Actually" (5:42, the transcript only
in Bengali) and Corzen01 "FULL COURSE on Spear Mace PvP | All 17 Techniques" (17:35). Notes with
timestamps are in the session scratchpad (`video-notes.md`). Every vanilla claim below was checked
against the 26.1.2 bytecode. There are no decompiled sources: use `javap -c -p` on
`.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*-26.1.2.jar`.

**The technique.**
- Tick N: axe key + click. Tick N+1: mace key + click (XCOW 0:46 / 4:30, Corzen #14 10:53).
- Both clicks in tick N is only a "stun" (XCOW 4:36, a diagram of [axe, click, click | mace]).
  `Minecraft.handleKeybinds` runs every hotbar key before the attack clicks, so both clicks go
  out with the item selected in that tick: the axe.
- XCOW's "tick lockout" (1:15-1:30): when two slot keys land in one tick, the later one in the loop
  (the higher slot) wins. His hotbar has the mace on slot 1 and the axe on slot 9, so pressing
  both together gives the axe. "Always stunning" can mean your mace key landed in the axe's tick
  and lost (4:42-4:45): you were too fast.
- Corzen's "the axe key ends the tick" diagram (11:53) contradicts the handleKeybinds order. Only
  the order explains the timing.
- Any height works (XCOW 4:51).
- Pre-click at the first moment you can (Corzen trick 4): on a fast dive you are in reach for only 1-3
  ticks, and the mace needs the tick after the axe.

**Vanilla, from the bytecode (council MECHANICS).**
- `MaceItem.canSmashAttack` = fallDistance > 1.5 (strict) and not gliding.
- Smash bonus: 4/block up to 3, 2/block up to 8, then 1/block, plus Density 0.5 × level per block.
  `Player.attack` adds it after the cooldown scaling, so it is not reduced by attack strength. That is
  why the mace still smashes one tick after the axe reset your cooldown.
- Shield (`LivingEntity.applyItemBlocking`):
  - Blocks within 90° of the target's head yaw, horizontally only. Straight above (zero horizontal
    vector) counts as blocked.
  - Needs 5 ticks raised.
  - An axe hit that is blocked disables it for 5 s (100 ticks). A mace never disables a shield.
- A fully blocked axe hit leaves lastHurt = 0, so the mace in the i-frames deals its full damage.
- Any hit within ~10 ticks after the mace deals only the part above the mace's damage, which is
  nothing for a smash (it could matter after a very short drop). That is why the old 2-3 tick
  "follow-up" hit was removed: it mostly just reset your own charge.
- Elytra: while gliding, a y-velocity above -0.5 caps fallDistance at 1
  (`Entity.checkFallDistanceAccumulation`). That is XCOW's "don't aim too high in a dive bomb or
  your damage resets" ("Didn't Pop", 5:09).

**How MaceAssist does it now.**
- `doStunSlam` only selects the axe. The click or trigger-bot hit that fired the event is the axe
  hit. It used to send a second axe attack of its own in the same tick.
- When `willSmashNextTick` (in the air, not gliding, fallDistance plus this tick's fall above
  max(1.5, Min fall distance)), `hacks/maceassist/StunSlamPlan` (`StunSlamPlanTest`) runs from N+1.
- **Firing the mace:** SetCarried(mace), Attack, Swing, at the first tick `HitCheck.isVerifiable`
  passes. The slot goes back after, held to the next tick's start.
- **Waiting for aim:** the slot goes back at once, so you don't hold the axe.
- **Giving up:** after 10 ticks, on landing, or if the target is gone or the mace was moved.
- The tick of the axe hit itself is skipped via `PacketOrder.ticksEnded()`. A click (handleKeybinds)
  and the trigger bot both come before the hack's tick.
- **Backstab** (Corzen #10): if the shield can't block you (`canShieldBlockYou`: > 90° + 20° margin
  for the lagging head yaw), there is no axe and the attribute swap sends the mace straight in. An
  unblocked axe hit would deal damage first and leave the mace only the difference.
- **Spear stun slam** (Corzen #15, "Spear stun slam" checkbox, **off by default**):
  - The spear's 4.5 reach is the item component ATTACK_RANGE (min 2.0, max 4.5), not an attribute.
  - Vanilla's crosshair picks with the held item's range (`LocalPlayer.raycastHitResult`).
    `startAttack` re-checks only a new item that has its own range, so an axe has none to check.
  - The vanilla server (`ServerGamePacketListenerImpl.handleAttack` -> `isWithinAttackRange(main
    hand, box, 3.0)` -> `AttackRange.isInRange`) allows max range + hitbox margin + 3.0, so it
    accepts the hit.
  - Grim's `Reach` uses the start-of-tick stack's max range and margin when the current stack has
    none (Reach.java:134-180), not its 2.0 minimum. `CompensatedInventory` sets the start stack at
    each tick packet. This was read from Grim's source, not tested against a Grim server.
  - Paper and Intave are unknown, hence off by default.
  - So with the spear held since the tick before (`sameSlotTicks >= 2`), the trigger bot starts the
    slam from 4.5 - 0.25 (`HitCheck.isVerifiable(target, reach)`). The mace hit at N+1 is back to
    normal reach.
  - By hand, a plain click with a spear goes out as a STAB, so you press the axe key with the click.
- **Guards:**
  - No new swaps while a slam is in flight. The trigger bot yields.
  - Your own mace click during the slam replaces ours (`userMaced`).
  - A slam started while an attribute swap was still waiting to switch back returns to your
    pre-swap slot.
  - `fullReset` on a new world or respawn (identity hashes).
  - Trigger hits per fall resets on landing or on a real launch (rise faster than 0.6 b/t, not the
    smash's 0.01 hop).
- `PacketOrder` counts a spear STAB (ServerboundPlayerActionPacket) as acting, which Grim's
  PacketOrderE does. `PacketBudgetTest` counts it too.
- Gametest `MaceTriggerTest` (`gametest mace -parts stun`): a NoAI husk with a raised shield
  (`startUsingItem` server-side), a 12-block fall from 1.2 blocks in front of it, and the trigger
  bot. It checks the packets that went out (`PacketTrace`). Only a Player's shield can be disabled
  (`Player.blockUsingItem` overrides it with `BlocksAttacks.disable`), so a mob keeps blocking.
  - Facing you: the wire must read `S2 A | S7 A | S0`, the axe hit then the mace one tick later,
    one attack each.
  - From behind: no axe, one mace hit, a smash landed (2026-11-11: 45 damage), and the shield
    still up.
  - Both: you end on the sword.
  - Passed 2026-11-11 (1 tick apart).

**Not automated (and why).**
- #9 d-tap (spear jab pops the totem, mace finishes it, 7:05): not this round. It needs the spear's
  held charge, then a release (RELEASE_USE_ITEM, so the mace swap waits a tick), then the mace.
  There's no test target that carries a totem yet.
- #16 wind-charge reset: it slows your fall, the opposite of a smash. It's for repositioning.
- #13 grappling (pearl at the target's future feet): it would need its own solver.
- #3/#17 spear dash and dash chain: already covered by Lunge swapping and SpearAssist. Lunge
  (`lunge.json`) pushes 0.458 × level along your look. Corzen (2:42, 7:05): the more level you look,
  the farther the dash. How pitch scales it wasn't checked in the bytecode.

Design points that were deliberate, don't "fix" them blindly:
- **Trigger bot = real geometry, once per tick, in the tick.** It used to check sub-tick slices with
  both positions moved ahead along their velocities, plus once per rendered frame - hits on positions
  the server never saw (Grim "HITBOX: hit without any intersection", user report 2026-10-02). Now
  `isCrosshairOnTarget`: current eyes, current look, min(Trigger range, `entityInteractionRange()`),
  hitbox + pick radius, block line of sight. Don't bring the prediction back.
- **Mace aim turns like AimAssist and gives way (user, 2026-11-03: "like the one in
  whataimassistturningshouldbelike.mp4 / MaceAimAssistTurning Inspirations, smooth, EXTREMELY
  human, and easy to pull away from").** `CameraAim.assistYaw`: `HumanAim.pidStep` (AimAssist's
  video-matched turn, `max(340, 8 x degrees left)` capped by Aim speed, tolerance = the
  footprint's half width as seen), yaw only, scaled by `util/AimYield` (Kclient's ideas, no
  code): your mouse dragging against the pull drops it to 3% within ~50 ms (at most ~10°/s
  against you; 10% still held a 100°/s drag to 29° in 0.5 s), back over ~0.12 s once the mouse
  is still for 150 ms; the target changing direction (> 6°/s both ways) eases the pull off for
  300-400 ms, not again for 450 ms. `AimYieldTest.pullAwayThenBack`: 100°/s drag -> 38° off in
  0.5 s, let go -> back from 37° to 0.5° in 0.3 s, no swing past. Mouse friction (`Aim
  stickiness` 50%, `onMouseUpdate`, `AimFriction.slow(..., leaveStickiness 0)`) only while
  closing in - never while pulling away. The spring below (`springYawStep`) is no longer used
  by MaceAssist; the hold-on-footprint / turn / settle logic stays.
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
