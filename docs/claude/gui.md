# ClickGUI look, bar, tabs and animations

Moved word for word from CLAUDE.md (2026-10-24); CLAUDE.md keeps the short version and points here.

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
