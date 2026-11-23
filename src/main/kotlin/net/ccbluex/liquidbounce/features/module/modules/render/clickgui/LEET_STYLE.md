# Leet GUI — Style Guide

Design system of the native ClickGUI (`LeetClickGuiScreen.kt`, this directory).
Read this before adding or changing any native-GUI UI. The file-level KDoc and
`AGENTS.md` ("Native ClickGUI") describe the implementation; this document
describes the **style** — what to follow so new UI looks native.

---

## 1. Design philosophy

- **Dark, quiet, flat.** Near-black page (`overlayRgb` 11/11/13 + edge
  vignette), one neutral rounded backdrop, no gradients on cards, no glow.
- **One accent.** Lavender (`lav` 168/163/245) is the only hue. It marks
  activity: enabled/selected/focused states, active category icon, accent
  borders, iconography in settings contexts. Never introduce a second accent.
- **Compact density.** Information is packed: 7–8.5 px fonts, 12–20 px rows,
  4–8 px gaps. Whitespace comes from rhythm (consistent gaps), not from size.
- **Pixel-crisp at rest.** Text and glyph quads land exactly on device pixels
  when the GUI is idle. Motion may blur, rest may not (see §8, §10).
- **Everything reacts.** Hover, focus, selection, expand — all lerped, never
  snapped (see §7). Direct manipulations (drags, typing) stay 1:1.

## 2. Geometry (gui units)

The page is a fixed centered window; all layout happens in gui units before
the zoom transform.

| Constant | Value | Meaning |
|---|---|---|
| `WINDOW_W` / `WINDOW_H` | 640 × 340 | window size |
| `RAIL` / `RAIL_CELL` / `RAIL_RADIUS` | 30 / 30 / 8 | category strip |
| `HEADER_H` | 30 | header row height |
| `GAP` | 7 | rail ↔ content gap |
| `SEARCH_W` | 130 | search field width |
| `CARD_RADIUS` | 5 | universal corner radius |
| `CARD_PAD` | 10 | module-card inner padding |
| `COL_GAP` | 8 | gap between the two module columns |
| `SET_GAP` | 6 | universal settings rhythm (between settings, around group boxes, above/below settings blocks) |
| `CTRL_W` | 110 | default control width in module settings |
| `GUI_ROW_H` / `GUI_PAD` / `GUI_ROW_GAP` / `GUI_FIELD_W` | 20 / 6 / 4 / 104 | **compact metrics of the GUI-settings pane** |

Rules:
- One radius (`CARD_RADIUS = 5`) everywhere. One rhythm (`SET_GAP = 6`) in
  module settings; the GUI pane uses its compact set (above).
- Module cards live in TWO FIXED columns (even index → left, odd → right; a
  card never jumps columns when expanding).
- New panes/sections reuse `guiSettingCard` (compact plate) — do not invent
  new paddings.

## 3. Color system (`Theme`)

Neutral fills form a ladder; each state is +3..5 RGB brighter than the
previous. Hover = one step up the same ladder.

| Role | RGB | Used for |
|---|---|---|
| `backdrop` | 15/15/18 (a=252) | window plate |
| `backdropBorder` | 28/28/34 | window outline |
| `card` → `cardHover` | 19 → 22 | disabled module card |
| `cardOpen` → `cardOpenHover` | 23 → 26 | disabled+expanded |
| `cardEnabled` → `cardEnabledHover` | 27 → 30 | enabled |
| `cardEnabledOpen` → `cardEnabledOpenHover` | 32 → 35 | enabled+expanded |
| `groupHead` | 25/25/30 | group-box head, icon plates |
| `stripActive` | 30/30/37 | active rail cell |
| `rowBorder` | 31/31/38 | outlines (cards, fields) |
| `badge` | 30/30/36 | small badges (bind, k/n) |
| `listBg` / `listActive` | 22 / 31 | dropdown list bg / selected item |
| `lav` | 168/163/245 | **the accent** |
| `lavDark` | 27/27/42 | accent-tinted dark (bind capture bg) |
| `white` | 236/236/241 | primary text |
| `desc` | 143/143/151 | secondary text, setting labels |
| `dim` | 98/98/107 | tertiary text, inactive icons |

Rules:
- Never hardcode colors — always `Theme.*`. New states = new ladder step,
  not a new hue.
- State changes never snap: use `lerpColor(a, b, t)` / the `watchColor`
  resolved-color watcher (§7).
- Accent usage of `lav`: enabled accents, focused borders (`rowBorder → lav`
  lerp), active category icon, settings-pane icons, selection dots.
- Text hierarchy: `white` primary, `desc` labels/secondary, `dim` inactive.

## 4. Typography

- Font: bundled Inter via `WatermarkFont` (`BRAND` for titles/labels,
  `TEXT` for everything else).
- Sizes: `FONT_TITLE` 10.5 (category name), `FONT_NAME` 8.5 (module names),
  `FONT_SEARCH` 7.5, `FONT_SET` 7.5 (setting names), `FONT_DESC` / `FONT_CTRL`
  7 (descriptions, control text).
- **ASCII + Cyrillic only** — the atlas bakes nothing else; fancy glyphs
  (`─ ≡ ▾ ·`) render as `?`. ASCII in all UI strings.
- Text is pixel-snapped through the zoom transform (§8). Never draw text at
  fractional device-pixel positions at rest.

## 5. Iconography

- Solar icon set (CC BY 4.0, 480 Design): white 64×64 RGBA PNGs,
  `textures/clickgui/<tag>.png` (flat, bold default) and
  `textures/clickgui/<tag>/<style>.png` (6 styles: bold, linear, outline,
  line-duotone, bold-duotone, broken).
- Always resolve through `styledIconName`/`styledIconNameFor` (styled path
  with flat fallback via `Icons.has`) — never construct icon paths inline.
- Icons are white on transparent; ALL color comes from the vertex tint at
  draw time: rail icons `dim` inactive → `white` hover → `lav` active;
  settings-context icons (GUI pane rows, dropdown previews) are `lav`.
- Icon sizes: rail ~14 px in a 30 px cell; pane rows 11 px; list previews
  9–10 px.

## 6. Widgets

- **Module card**: 4 fill states × hover variants (ladder above); expanded
  adds a `rowBorder` outline. Collapsed: whole card is a hitbox (LMB toggle,
  RMB expand, MMB bind capture). Expanded: hits on the head row only; right
  1/3-bars icon collapses (icon morphs with the eased expand progress).
- **Bind badge**: key name before the expand icon; capture shows `...` with
  `lavDark ↔ lav` lerp. ESC cancels, Delete/Backspace unbinds, mouse buttons
  bind.
- **Checkbox**: badge + border ↔ `lav` lerp on toggle (120 ms).
- **Slider**: value text (upper half) + track (lower half) as one block
  vertically centered on the row; nothing leaks past row bounds.
- **Dropdown** (Choice/Mode/MultiChoice): collapsed field (`listBg` fill,
  `rowBorder → lav` outline lerp, chevron vertex-morph); expands on RMB,
  height-scissor reveal (150 ms); MultiChoice shows `k/n`, keeps list open.
- **Group box** (nested ValueGroup): subtle rounded rect `x-4..x+w+4`,
  children inset (`x+4`, `w-8`), gating row gets a `groupHead` rect;
  150 ms height reveal.
- **Color setting**: row (hex + alpha % + swatch) + picker (square SV area
  with square corners, rounded hue/alpha bars via `drawSweepRoundedRect`);
  180 ms reveal; hue survives grays (`pickerState`).
- **Text field**: buffered (commit on Enter/click-away, invalid regex
  rejected); lists → read-only `N items` badge. Never cast text values
  blindly to `Value<String>` (may be Regex/list).
- **Registry list** (blocks/items/sounds/…): compact field with `k/total`
  count, RMB reveal; inside — a search field + scrollable multi-checkbox
  list (8-row cap with its own wheel scroll; wheel over the list never
  scrolls the pane). Entry names = registry path without namespace.
- **Items-choose**: same picker, but ordered: chosen rows with `x` remove
  badges on top, add-picker (unchosen only) below.
- **Mutable list** (textList/regexList): expandable rows of keyed text
  fields, `x` per row, `+` row appends and focuses a new entry.
- **File**: badge row with the file name; LMB opens the FileValue
  TinyFileDialogs dialog, RMB resets to default.
- **Vec2/Vec3**: one row of compact numeric fields (per-axis keyed edit,
  invalid input rejected).
- **Group list**: folder entries + `+` add badge and per-entry `−` badges
  (hidden when a single entry remains).
- **Bind**: key-capture badge + compact action dropdown
  (Toggle/Hold/Smart); modifiers display-only.
- **Curve**: read-only `N points` badge (no editor in native GUI).
- **GUI-pane row** (`guiSettingCard`): 20 px compact card, small `lav` icon
  + `desc` label left, fixed-width control right (104 px). New GUI settings
  MUST use it.

## 7. Animation system

- ONE easing: `easeInOutCubic`. Durations (constants in the file):
  open/close 200 ms, expand/collapse 200 ms, reveals 150–180 ms
  (`REVEAL_SEC`/`PICKER_SEC`/`GROUP_SEC`), micro-interactions 100–150 ms
  (`CHECK_SEC`, `PICK_SEC`, `HOVER_SEC`, `FOCUS_SEC`, `BIND_SEC`,
  `CAT_ICON_SEC`), color lerps 150 ms (`COLOR_LERP_MS`), category intro
  150 ms (`INTRO_MS`).
- Mechanics to reuse:
  - **Progress maps** advanced once per frame (`advanceSetProgress`) —
    measure/draw only read them.
  - **Height reveals** = eased scissor (`shownH = fullH * ease(t)`); hits
    below the clip are suppressed via `Hit.clipBottom`.
  - **`watchColor(key, target)`** — universal resolved-color watcher: any
    color that can change state (card fills, text) goes through it.
  - **Category intro** = alpha × ease(t) + 6 px rise.
  - **Smooth scroll**: `scrollTarget` + exponential settle (`SCROLL_TAU`,
    ~150 ms), snapped to the device-pixel grid at rest — the only
    non-easeInOutCubic motion, and its rest snap is mandatory.
- Never animate: slider/picker drags (direct manipulation), text
  input/caret, zoom translation snap, text substitutions (badge labels).

## 8. Rendering & zoom

- Ctrl+wheel zoom (`uiScale` 0.5–1.6) = translate+scale; at rest the
  translation is snapped to the device-pixel grid (`1/dpr`), so
  `WatermarkFont` glyph quads land on exact pixels. Do not break this.
- Text density = `guiScale * uiScale` (font atlases are rasterized at the
  zoomed pixel size; `MAX_ATLASES` is sized for the zoom range).
- Scissor rects are computed in real pixels with floor(origin) /
  ceil(end)−floor(origin) — never truncate both ends.
- Vignette + overlay are part of the page; content draws on the backdrop.

## 9. Input model

- Press-capture + frame-driven drag: sliders/pickers capture on press;
  every frame `glfwGetMouseButton` is polled and the captured drag is
  applied from the current mouse position, released exactly when the button
  is up. No capture stealing. Release handling only for the capturing
  button.
- `Hit`s are rebuilt every frame, tested `asReversed()` (topmost last- added
  first), carry `inScroll` (viewport culling) and `clipBottom` (animation
  scissor). Value application is deduped (`set()` only on real change).
- Bind capture: while active, all other input is swallowed.

## 10. Measure/draw contract (hard rule)

Every layout function takes `ctx: GuiGraphicsExtractor?` — `null` = measure
only, and MUST return byte-identical heights in both passes. Animation
progress is advanced once per frame in the frame handler, never inside
measure/draw. Hits are only added when `ctx != null`.

## 11. Persistence

- Native-GUI settings live in `LiquidBounce/clickgui-native.json` via
  `LeetClickGuiSettings` (Gson, lazy load, save on change) — never in module
  Values (those serialize to `modules.json` AND broadcast to the browser
  GUI via WebSocket events; the browser GUI must stay untouched).
- UI state (selected category, expanded set, scroll, search, zoom,
  dropdowns, animation maps) persists across GUI reopenings in the screen's
  companion object, pruned on open.

## 12. Checklist for new UI

- [ ] Theme colors only; new states are ladder steps, not new hues.
- [ ] One radius (5), `SET_GAP` rhythm (or the GUI-pane compact metrics).
- [ ] `lav` is the only accent; icons tinted, never recolored in assets.
- [ ] Fonts: existing `FONT_*` sizes; ASCII (+Cyrillic) strings only.
- [ ] Any state change animates via progress maps / `watchColor`,
      easeInOutCubic, 100–200 ms; drags and typing stay un-animated.
- [ ] Measure/draw heights identical; hits with `inScroll`/`clipBottom`.
- [ ] Pixel-crisp at rest (device-pixel snap for text/glyphs).
- [ ] Native-only state → `clickgui-native.json`, never module Values.
- [ ] File stays UTF-8 **without BOM** (no PowerShell edits).
- [ ] Update this guide / `AGENTS.md` when conventions change.
