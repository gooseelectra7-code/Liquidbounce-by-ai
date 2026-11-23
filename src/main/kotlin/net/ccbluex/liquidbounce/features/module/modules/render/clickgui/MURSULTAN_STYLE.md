# Mursultan GUI — Style Guide

Design system of the fourth native ClickGUI (`MursultanClickGuiScreen.kt`, this directory),
a reproduction of the Nursultan client's menu in LiquidBounce.

Read this before changing any Mursultan-GUI code. `LEET_STYLE.md` describes a DIFFERENT
design system — do not mix the two.

---

## 0. Provenance (how these numbers were obtained)

Two independent sources, both read on 2026-09-20:

1. **Decompiled Nursultan** (`/home/neist/nursultan-client`, Vineflower output of
   `Nursultan-Fabric-1.21.11.jar`). The menu is `Nursultan/class09222`; its layout tree is
   `Nursultan/class09210`; the style builder is `class09991` over the size record
   `class09962` (`y(f)` = FIXED px, `N(f)` = PERCENT, `N()` = FIT, `y(a,b)` = GROW).
   Verified facts taken from there:
   - sidebar width **270**, its inner column **269** (the 1 px remainder is the divider)
   - logo block **100% x 89**, centered; logo icon **52 x 38**, tinted with the accent
   - nav sections `tab.features` (combat, movement, visuals, player, misc) and
     `tab.manager` (presets, autobuy, accounts), avatar row at the bottom
   - default accent `-7623425` = **#8BACFF**
   - open/close **250 ms**; the menu scaler is fed a spring `(1100, 16, 4)`
   - icons are an **MTSDF atlas** (`icons/atlases/menu.png` + `menu.json`, 30 named glyphs)
   - fonts are **Inter** (`fonts/inter.ttf`, `interm.ttf`)
2. **Screenshot forensics** (user-supplied captures, menu scale `entry.scale_80`). Pixels
   were sampled with PIL, never eyeballed — per `AGENTS.md` ("Reproducing UI from reference
   screenshots"). The captures were taken with the accent set to **white**, which is why
   every accent-tinted element reads white there.

**Scale calibration.** The sidebar is 270 design units and measured 215 px, so the capture
scale is `s = 0.7965`. Every measurement below is `px / s`, rounded. The window measured
956 x 600 px -> **1200 x 753** design units, which independently confirms `s`.

All values in this document are **design units**: the units the layout is authored in, before
the screen-fit scale. The screen renders them at `min(width, height ratio)`-derived scale like
the other native GUIs.

## 1. Design philosophy

- **Black, not grey.** The page is `#0D0D0D` over a blurred world. Surfaces do not have their
  own fill — a card is a 1 px outline, nothing else. Depth comes from outlines and dividers,
  never from lighter panels (this is the opposite of Leet).
- **One accent, and it is a setting.** The accent tints exactly three things: the logo, the
  ON toggle, and the active nav item's text/icon. Nothing else is ever coloured.
- **Big, airy, low density.** Authored at 1200 px wide: 20-unit padding everywhere, 49-67
  unit rows, 20-24 unit type. Do not "compact" it — the roominess is the look.
- **Thin line icons.** 1.5-unit stroke, rounded caps, from the Nursultan MTSDF atlas
  (extracted to white PNGs under `textures/clickgui/mursultan/`). Where Nursultan has no
  icon for an LB category (World, Exploit, Fun) the Solar **linear** variant is used — the
  only line-weight-compatible set already in the repo.

## 2. Geometry (design units)

| Constant | Value | Meaning |
|---|---|---|
| `WINDOW_W` / `WINDOW_H` | 1200 x 753 | window size |
| `WINDOW_RADIUS` | 14 | window corner radius |
| `SIDEBAR_W` | 270 | sidebar incl. its 1 px right divider |
| `HEADER_H` | 89 | logo/search row incl. its 1 px bottom divider |
| `PAD` | 20 | universal padding (content, card, popup, sidebar) |
| `COL_GAP` | 20 | gap between the two module columns |
| `COL_W` | 435 | module column = (1200 - 270 - 2*20 - 20) / 2 |
| `CARD_RADIUS` | 10 | card / popup corner radius |
| `CARD_HEAD_H` | 60 | card header row |
| `ROW_H` | 67 | module row |
| `SEARCH_H` | 44 | search field (and the burger button, 44 x 44) |
| `SEARCH_RADIUS` | 6 | search field / burger radius |
| `BURGER_GAP` | 12 | gap between search field and burger |
| `NAV_ITEM_W` / `NAV_ITEM_H` | 230 x 50 | sidebar item (270 - 2*20 wide) |
| `NAV_ITEM_GAP` | 6 | gap between sidebar items |
| `NAV_RADIUS` | 8 | sidebar item radius |
| `NAV_ICON` | 20 | sidebar icon size, inset 21 from the item's left |
| `TOGGLE_W` / `TOGGLE_H` | 44 x 22 | toggle track (radius = h/2), knob a CIRCLE of h-4 |
| `TOGGLE_KNOB_INSET` | 2 | knob inset inside the track |
| `DOTS_W` | 24 | the "..." settings button |
| `DOTS_GAP` | 21 | gap between the dots and the toggle |
| `POPUP_W` | 402 | module-settings panel |
| `POPUP_HEAD_H` | 56 | its header (module name + close) |
| `POPUP_DOCK_GAP` | 18 | gap between the window and the docked panel |
| `CHIP_W` / `CHIP_H` / `CHIP_GAP` | 46 / 26 / 6 | scale percent chips |
| `POPUP_ROW_H` | 49 | popup row (also the client-settings popover) |
| `PILL_W` / `PILL_H` | 151 x 30 | dropdown pill inside a popup row |
| `POPOVER_W` | 329 | client-settings popover (burger button) |
| `PICK_SV_H` | 150 | colour picker saturation/value field |
| `PICK_BAR_H` | 14 | hue / alpha bar |
| `PICK_PRESET` | 26 | quick swatch |
| `FOOTER_H` | 67 | sidebar footer (account block) |

## 3. Colour system (`Theme`)

| Role | Value | Used for |
|---|---|---|
| `page` | #0D0D0D @ 245 | the window plate, over a blurred backdrop |
| `border` | #171717 | window, card, popup, search field, pill outlines |
| `divider` | #151515 | header divider, card header divider, row separators |
| `navActive` | #111315 | active sidebar item fill |
| `navActiveBorder` | #2A2A2A | active sidebar item outline |
| `trackOff` | #1D1D1D | toggle track, OFF |
| `knobOff` | #313131 | toggle knob, OFF |
| `title` | #FFFFFF | card titles, active nav text, footer name |
| `textOn` | #E2E2EB | enabled module name, its dots |
| `textOff` | #929292 | disabled module name, its dots, popup labels |
| `navIdle` | #ACACAC | inactive sidebar item text + icon |
| `label` | #4F4F4F | section captions (FUNCTIONS / MANAGEMENT) |
| `placeholder` | #3B3B3B | search placeholder |
| `accent` | #8BACFF (default) | logo, ON toggle, active nav item; white is the second swatch |

The ON toggle is the accent at **61 %** for the track and **100 %** for the knob — that is
what produces the grey-track/white-knob pair in the captures.

## 4. Typography

Bundled Inter (`WatermarkFont.BRAND` = SemiBold, `TEXT` = Medium).

| Size | Where |
|---|---|
| 24 | card title (BRAND) |
| 20 | module name, nav item, search text, popup label |
| 19 | pill text, popup value text |
| 16 | footer name |
| 15 | section caption (uppercase), footer date |

ASCII + Cyrillic only — the atlas bakes nothing else.

## 5. Widgets

- **Card** — 1 px `border` outline, radius 10, no fill. Header `CARD_HEAD_H` with the title
  at `PAD` and a `squeeze` icon (16 x 23) at the right `PAD`, then a **full-width** `divider`.
  Rows follow; **a separator is drawn below every row except the first** (verified in three
  separate cards and in the settings popup — it is a real property of the original, not a
  measurement error). The last row's separator coincides with the card's bottom outline.
- **Row** — name at `PAD`, then `...` (the `dots` icon) and the toggle, both right-aligned.
  Clicking the row body or the toggle toggles the module; clicking the dots opens the
  settings popup.
- **Toggle** — 44 x 22 pill with a **round** knob (diameter `h - 2*inset`). OFF: `trackOff`
  track, `knobOff` knob left. ON: full `accent` track, white knob right. Both lerp with the
  slide. The original's 52 x 24 with a stadium knob read as overlong and stretched, so this is a
  deliberate deviation.
- **Sidebar item** — 230 x 50, radius 8. Active: `navActive` fill + `navActiveBorder`
  outline, accent icon/text. Idle: no fill, `navIdle` icon/text.
- **Settings panel** — a 402-wide panel that **docks beside the window**, in the empty margin
  next to it: the right margin by default, the left one when the right cannot hold it (large
  user scale), and inside the window's right edge only when neither can. It slides in
  horizontally from under the window edge and carries a header (module name + `xmark` close)
  so it is never a dead end. Its fill is **opaque** (`Theme.popup`) — at the window's own 245
  alpha the card titles behind it stayed readable through the panel. Rows of `POPUP_ROW_H`,
  separators following the same skip-the-first rule, inset by `PAD`.
  Row shapes: label + toggle (booleans), label + pill (choices), label + slider (ranges),
  label + swatch (colours), label + field (text), label + badge (binds).
- **Colour picker** — the swatch in a colour row expands (height reveal, 150 ms) into a
  saturation/value field for the current hue, a hue bar, an alpha bar, a preview with the hex
  and `R G B - A%` readout, and a row of quick swatches. Field and bars are **pointer-captured**:
  the grab survives the pointer leaving the widget. The hue is kept per value (`pickerHue`)
  because it cannot be recovered from RGB once the colour is grey or black — without it the hue
  bar snaps back to red on every frame at `#000000`. The SV field is a 14x8 vertex-coloured grid
  (`drawCustomElement`), the bars are `drawSweepRoundedRect` sweeps.
- **Dropdown pill** — 151 x 30, radius 8, 1 px `border`, value text clipped at the right
  under an `angles` chevron inset 9 from the pill's right edge.

## 6. Rendering & animation

**Text is always drawn at the SETTLED density** (`dpr * settledScale`), never at the animated
one. `WatermarkFont` keys its atlases on `round(px * density)`, so feeding an animating scale
into it rebuilds every atlas per frame — that is what made the GUI flicker when the scale was a
slider, and it also stuttered on every open through the 4 % pop. The pop now scales the quads
only. Distinct sizes are kept low (3 per face) so the 24-atlas LRU never thrashes across scale
steps.

Cached, because they are not per-frame work: the package-leaf grouping per category, the search
result per query, the name -> module lookup, and the text-truncation results (`clip` binary
searches the length instead of dropping one character at a time). Rows outside the viewport
register no hit boxes.


- Open/close **250 ms**, easing `easeInOutCubic`, with a 0.96 -> 1.0 scale pop and a small
  upward slide — the visible half of Nursultan's spring `(1100, 16, 4)`.
- Hover/active colour changes lerp over **150 ms** through the same `watchColor` watcher
  mechanism Leet uses.
- Toggle knob slide **120 ms**, popup reveal **150 ms**, scroll settle ~150 ms exponential
  with a device-pixel snap at rest.

## 7. Adaptations (where LiquidBounce is not Nursultan)

These are deliberate and documented; they are the only places the reproduction deviates.

1. **Card grouping.** Nursultan groups a category's features into named sub-cards
   ("Драка", "Инструменты", ...). LiquidBounce has no sub-categories, so cards are derived
   from the **package leaf** each module lives in (`modules/combat/totem/...` -> "Totem",
   `modules/combat/...` -> "Combat"). Cards are distributed over the two columns by
   balancing column height. This keeps the multi-card masonry that carries the look.
2. **Sidebar contents.** Nursultan's five feature entries become LiquidBounce's eight
   categories under the same "FUNCTIONS" caption; the "MANAGEMENT" section holds the
   native-GUI settings entry. Accounts/AutoBuy have no LB counterpart here and are omitted.
3. **Icons for World / Exploit / Fun** come from the Solar **linear** set already shipped in
   `textures/clickgui/<tag>/linear.png`; every other icon is the extracted Nursultan glyph.
4. **Logo** is the LiquidBounce mark (white silhouette of `icon_64x64.png`, accent-tinted)
   in the same 52 x 38 slot the Nursultan "N" occupied.
5. **Scale is a grid of percent chips (50 % .. 200 %)**, not the original's dropdown and not a
   slider — see section 6 for why a continuous scale cannot work here.
6. **Settings surface.** Nursultan's popup has hand-authored rows per feature; here the
   popup walks LiquidBounce's Value tree, so it must cover every `ValueType`. Types with no
   Nursultan equivalent (curve, file, registry lists) fall back to a badge row.

## 8. The HUD theme (`MursultanHud.kt`)

Measured the same way, off in-game captures of the original at 100 % HUD scale. Its constants
are the capture's **pixels**; `REFERENCE_GUI_SCALE = 3` turns them into GUI units once, in the
pose, so proportions hold at any resolution.

| Constant | Value | Meaning |
|---|---|---|
| `PILL_H` / `PILL_RADIUS` / `PILL_GAP` | 37 / 10 / 8 | top pills and the gap between them |
| `BRAND_PAD_L` / `BRAND_LOGO` / `BRAND_GAP` / `BRAND_PAD_R` | 13 / 12 / 24 / 10 | brand pill |
| `INFO_PAD_L` / `INFO_PAD_R` | 9 / 12 | segment pill padding |
| `ICON` / `ICON_GAP` / `SEGMENT_GAP` | 14 / 8 / 25 | one `icon + text` segment |
| `GUTTER` / `PANEL_PAD` / `PANEL_PAD_V` / `ROW_H` | 39 / 13 / 6.5 / 27 | panels (hotkeys, potions) |
| `BRAND_PX` / `TEXT_PX` | 15 / 14 | type |

Colours: plate `#1A1A1E` @ 238, border `#141417`, divider `#18181C`, text `#E3E3ED`, values
white, icons `#BEBEC6`. Verified against the capture: the pill fill reads `(34,35,40)` over a
bright sky, the gutter divider `(28,29,30)` at exactly `x = GUTTER`.

Icons are the original's own, extracted from its **HUD** MTSDF atlas (`icons/atlases/hud.png`,
16 glyphs: `player fps ping time coordinates bps tps hotkeys potions cooldowns target inventory
arrows staff x`) to white PNGs under `textures/clickgui/mursultan/hud/`.

The module list is the original's **hotkeys panel**, not a right-side arraylist, and the
distinction is the FILTER: it lists modules that have a **key assigned**, widest first, with a
bound-but-off module greyed rather than hidden. Listing every enabled module instead is an
arraylist wearing a keyboard icon — that is exactly what the first cut did. Rows fade in and give
their slot up over 180 ms so the panel never jumps.

**Toasts** (plate 233 x 37, radius 12, pads 10 / 23, gap 5, text 16) sit on a LIGHTER plate than
the pills (`#282832` vs `#1A1A1E`) and their toggle glyph is a fixed `#529DFF`, **not** the
accent — the reference capture had a white accent and the glyph was still blue. Track 47 x 20,
knob 14 inset 3.

**Icons are baked to the size they are drawn at** (`MursultanIcons`). The masters are 64 px
masks; drawing them at ~14-16 device pixels through a LINEAR sampler with no mip chain samples
one texel in twenty and looks soft. Every requested pixel size gets its own CPU-resampled
texture (box filter in premultiplied alpha), cached by name and size. Element origins are also
snapped to the device grid: the font snaps glyphs horizontally through the pose, but its
baseline snap happens in local units, so a fractional origin blurs every row.

**Text outside a pose scale carries its own size.** The pills and panels draw under a scaled
pose, so their font sizes are reference pixels. The hotbar and the toasts are anchored to the
screen and draw in GUI units, so there the font size must be multiplied by the element scale and
the density is the plain GUI scale. Passing reference pixels there drew both three times too
large. `tps` is measured from the gap between `ClientboundSetTimePacket`s (vanilla emits
one every 20 server ticks) and smoothed, so one late packet cannot make it jump.

## 9. Checklist for new UI here

- [ ] `Theme.*` only; no new hues, no new fills — outlines and dividers carry structure.
- [ ] `PAD` (20) for every inset; `CARD_RADIUS` (10) for panels, `SEARCH_RADIUS` (6) for fields.
- [ ] Accent touches only logo / ON toggle / active nav item.
- [ ] Separator skipped below the first row of any list.
- [ ] Measure and draw passes return identical heights; hits added only when `ctx != null`.
- [ ] Animations use `easeInOutCubic`, 120-250 ms.
- [ ] File stays UTF-8 without BOM.
- [ ] Update this guide when the conventions change.
