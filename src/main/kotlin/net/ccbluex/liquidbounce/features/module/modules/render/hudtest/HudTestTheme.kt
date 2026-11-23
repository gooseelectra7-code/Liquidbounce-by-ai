/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

/**
 * The design tokens of the HudTest layout.
 *
 * Every number here was measured off the reference mock in **native screen pixels** — the module
 * renders in native-pixel space (one local unit is one physical pixel), so these values are used
 * verbatim as coordinates and sizes. Colors are packed ARGB.
 *
 * The palette is deliberately not user-configurable: the point of the module is to reproduce one
 * specific design, and a recolour would break it.
 */
internal object HudTestTheme {

    /** Packs an alpha (0..255) and a `0xRRGGBB` triple into an ARGB int. */
    fun argb(alpha: Int, rgb: Int): Int = (alpha shl 24) or (rgb and 0xFFFFFF)

    // --- Palette -------------------------------------------------------------------------------

    /** Every panel body: `#0F0F0F` at alpha 245. */
    val CARD = argb(245, 0x0F0F0F)

    /** Toggle fill and panel header icons. */
    val ACCENT = argb(255, 0xB4A8F7)

    /** The small lavender glyphs (rocket, bars, person, heart, compass…). */
    val ACCENT_ICON = argb(255, 0xD8D0FA)

    /** Titles and primary values. */
    val WHITE = argb(255, 0xFFFFFF)

    /** Trailing values: keybind key, cooldown seconds, potion time. */
    val VALUE_DIM = argb(255, 0xB4B4B4)

    /** Unit suffixes: `fps`, `ms`, `hp`, the `x`/`y`/`z` labels, the potion amplifier. */
    val UNIT_DIM = argb(255, 0x5A5A5A)

    /** Bar tracks. */
    val TRACK = argb(255, 0x1D1D1D)

    /** Generic bar fill; the target hud and the media panel use their own tone. */
    val BAR_FILL = argb(255, 0xE4DFFA)
    val BAR_FILL_TARGET = argb(255, 0xD5D0E8)
    val BAR_FILL_MEDIA = argb(255, 0xEEEAFF)

    /** Track of an "off" toggle. */
    val TOGGLE_OFF = argb(255, 0x2A2A2A)

    /** 1 px vertical rules in the status bar — white at 15 %. */
    val DIVIDER = argb(38, 0xFFFFFF)

    /** The horizontal rule under a panel header — white at 6 %. */
    val HAIRLINE = argb(15, 0xFFFFFF)

    /** Stand-in fill for a missing skin / empty gear cell. */
    val PLACEHOLDER = argb(255, 0x232323)

    // --- Typography ----------------------------------------------------------------------------

    /** Titles, labels and values — Inter SemiBold. */
    const val TEXT_PX = 13f

    /** Unit suffixes — Inter Medium. */
    const val UNIT_PX = 10f

    /** Target name and hp number — Inter SemiBold. */
    const val TITLE_PX = 14f

    // --- Shared list-panel system (Potions, Keybinds, Cooldowns, Settings) ----------------------

    const val LIST_WIDTH = 188f
    const val LIST_RADIUS = 12f
    const val PAD_X = 15f
    const val HEADER_HEIGHT = 36f
    const val LABEL_X = 33f
    const val ROW_START = 40f
    const val ROW_PITCH = 26f
    const val BOTTOM_PAD = 8f

    /**
     * The slot every **vector** mark is fitted into — the status bar's rocket, a panel header
     * glyph, a row's vector marker, the coordinate compass, the notification severity marks and
     * the media transport.
     *
     * Ten native pixels is the text's own cap height at [TEXT_PX], which is where the reference
     * puts its drawn glyphs: the heart in the Potions header measures exactly as tall as the
     * `Potions` beside it.
     */
    const val ICON = 10f

    /**
     * The slot a **textured** row marker takes — a mob effect sprite, an item. Deliberately larger
     * than [ICON]: the reference draws its row sprites at 1.26x the cap height, a pixel past the
     * cap above and a pixel past the baseline below, and a picture does need that bit more room
     * than a flat glyph to read at all. Every marker really does fill it, because the source art is
     * trimmed to its ink first — see `HudTestItemIcons.squaredInk`.
     */
    const val ROW_ICON = 12f

    /**
     * The axis both slots are centred on. They are not the same width, so left-aligning them to
     * [PAD_X] would leave the header glyph and the row markers on two different centres and at two
     * different distances from their labels.
     */
    const val ICON_CENTER_X = PAD_X + ROW_ICON * 0.5f

    /** Height of a list panel with [rows] rows: `48 + 26 * rows`. */
    fun listHeight(rows: Int): Float = ROW_START + ROW_PITCH * rows + BOTTOM_PAD

    // --- Animation -----------------------------------------------------------------------------

    /** Duration of the panel appear/disappear fade, in milliseconds. */
    const val FADE_MS = 220f

    /** `easeOutCubic` — the curve every fade and slide uses. */
    fun easeOutCubic(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /** Slight overshoot, used by the notification pop-in. */
    fun easeOutBack(t: Float): Float {
        val c1 = 1.2f
        val u = t.coerceIn(0f, 1f) - 1f
        return 1f + (c1 + 1f) * u * u * u + c1 * u * u
    }

    /**
     * Frame-rate independent follow factor: the fraction of the remaining distance a value
     * should cover in [dt] seconds when it halves its error every `1 / -log2(smoothing)` seconds.
     */
    fun damp(smoothing: Float, dt: Float): Float =
        if (dt <= 0f) 0f else (1f - Math.pow(smoothing.toDouble(), dt.toDouble()).toFloat()).coerceIn(0f, 1f)
}
