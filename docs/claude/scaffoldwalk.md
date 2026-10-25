# ScaffoldWalk godbridge

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

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
