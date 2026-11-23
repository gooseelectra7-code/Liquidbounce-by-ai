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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont

/**
 * Which face the HUD is set in.
 *
 * [SF_PRO] is what the design asks for and stays the default. [INTER] exists because SF Pro
 * **Display** is a face cut for large sizes — thin strokes, tight fitting — and at the 8-10 px
 * labels this design is full of it rasterizes softer than Inter, which was drawn for exactly that
 * size of interface text. Reported as "the font feels low quality" on 2026-09-21; raising the
 * panel scale is the other half of that answer.
 *
 * Inter reuses `WatermarkFont.TEXT`, the instance the watermark and the ClickGUI already use, so
 * switching does not bake a second set of glyph atlases for the same face and size.
 */
internal enum class Hud3Typeface(
    override val tag: String,
    val font: WatermarkFont,
) : Tagged {
    SF_PRO("SfPro", WatermarkFont("resources/liquidbounce/fonts/SFProDisplay-Medium.otf")),
    INTER("Inter", WatermarkFont.TEXT),
}

/**
 * The design tokens of the Hud3 layout.
 *
 * Unlike [net.ccbluex.liquidbounce.features.module.modules.render.hudtest.HudTestTheme], whose
 * numbers were *measured off a screenshot*, everything here was **read out of the source design
 * file** through the Figma REST API — file `peW5mqUNF4v3xOKGZXRtt3` ("Shard"), page `1:4` (HUD),
 * frame `1:117`, components on page `1:2`. The node id each group of numbers comes from is named
 * next to it, so any of them can be re-checked against the file rather than re-guessed.
 *
 * Values are in the design's own pixels, which the module renders in verbatim: one local unit is
 * one native screen pixel, exactly as in the HudTest module. Colors are packed ARGB.
 *
 * The palette is deliberately not user-configurable — the point of the module is to reproduce one
 * specific design.
 */
internal object Hud3Theme {

    /** Packs an alpha (0..255) and a `0xRRGGBB` triple into an ARGB int. */
    fun argb(alpha: Int, rgb: Int): Int = (alpha shl 24) or (rgb and 0xFFFFFF)

    /** Packs a `0..1` opacity and a `0xRRGGBB` triple into an ARGB int. */
    fun rgba(rgb: Int, opacity: Float): Int =
        argb(Math.round(opacity.coerceIn(0f, 1f) * 255f), rgb)

    // --- Card ("pill") ----------------------------------------------------------------------
    // Every surface in the design is the same pill: `<3:489>`, `<3:376>`, `<3:382>`, `<3:485>`,
    // `<3:487>`, `<3:802>`, `<3:178>` all carry an identical fill / stroke / blur / radius.

    /** Body fill: `#000000` at 65 %. */
    val CARD = rgba(0x000000, 0.65f)

    /** Rim: `#000000` at 70 %, stroke weight 1.5, alignment OUTSIDE. */
    val CARD_RIM = rgba(0x000000, 0.70f)

    /** Corner radius of every pill. */
    const val RADIUS = 8f

    /**
     * Stroke weight of the rim. The design aligns it **OUTSIDE**, so a pill's stated size is its
     * fill box and the rim grows the drawn shape by this much on every side — which is why
     * [Hud3Panel.frame] is asked for `size + 2 * RIM` and the body is drawn at an offset of [RIM].
     */
    const val RIM = 1.5f

    /** `BACKGROUND_BLUR(radius = 12)` on every pill. */
    const val BLUR_RADIUS = 12f

    /** Inner padding of a pill, uniform on all four sides. */
    const val PAD = 5f

    /** Gap between a pill's icon and its text, and between the pills of a stack. */
    const val GAP = 5f

    // --- Palette ----------------------------------------------------------------------------

    /** Accent of every icon and both rings: `#5E8CFF`. */
    val ACCENT = argb(255, 0x5E8CFF)

    /** Primary text: `#F7F3F3`. */
    val TEXT = argb(255, 0xF7F3F3)

    /** Secondary text, key captions, the keyboard icon: `#F7F3F3` at 45 %. */
    val TEXT_DIM = rgba(0xF7F3F3, 0.45f)

    /** Notification text is pure white here, not the `#F7F3F3` the other pills use — `<3:162>`. */
    val TEXT_NOTIFICATION = argb(255, 0xFFFFFF)

    /** Severity colors of the notification marks and their timeout bars. */
    val OK = argb(255, 0x00C407)
    val FAIL = argb(255, 0xEE0909)
    val WARN = argb(255, 0xE5B402)

    /**
     * The spent part of any progress track — the design expresses it as a gradient stop that
     * drops the same hue to 15 % alpha at the progress position (`<3:434>`, `<3:163>`,
     * `<9:830>`), i.e. a hard cut rather than a fade.
     */
    const val SPENT_OPACITY = 0.15f

    /** Durability / timeout bar height, and the radius of its top corners (`<3:434>`). */
    const val BAR_HEIGHT = 2f

    // --- Typography -------------------------------------------------------------------------
    // The design is set in SF Pro. `SFProDisplay-Medium.otf` is bundled, so the Medium runs are
    // exact; there is NO SF Pro Regular in the repository, so the Regular runs (`fps`, `ms`,
    // `lvl`, the key caption, the timers and the notification body) are drawn in the same Medium
    // face. At 8-10 px, dimmed to 45 %, the weight difference is far less visible than a switch
    // to a different family would be — but it IS a deviation, and the only one in the module.

    /**
     * The face every label and value is set in, as chosen by `ModuleHud3.Typeface`.
     *
     * Read fresh on every call rather than cached, which is what lets the setting take effect
     * without a reload. The module is only touched at draw time, so this cannot deadlock the
     * initialisation of either object.
     */
    val font: WatermarkFont get() = ModuleHud3.typeface.font

    /** Watermark, target name, item counts: 12 px, line height 14.32. */
    const val PX_12 = 12f

    /** Panel labels, keybind names, effect names, timers, notifications: 10 px, lh 11.93. */
    const val PX_10 = 10f

    /** The `lvl` suffix: 8 px, lh 9.55. */
    const val PX_8 = 8f

    // --- Icon slots -------------------------------------------------------------------------

    /** Icon slot inside the watermark — a 16 px instance holding a 12 px glyph (`<3:354>`). */
    const val ICON_16 = 16f

    /** Icon slot everywhere else — a 12 px instance holding a 9 px glyph (`<3:378>`). */
    const val ICON_12 = 12f

    // --- Row metrics ------------------------------------------------------------------------

    /** Height of the watermark, the armor bar, a notification: 26 px of fill box. */
    const val ROW_26 = 26f

    /** Height of a keybinds / potions pill, header included: 22 px (`<3:376>`). */
    const val ROW_22 = 22f

    /** Vertical gap between the pills of the keybinds / potions stacks (`<3:486>`). */
    const val STACK_GAP = 5f

    /** Item cell of the armor and itemlist panels (`<3:426>`). */
    const val ITEM_16 = 16f

    /** Item cell of the target hud's gear row (`<3:788>`). */
    const val ITEM_12 = 12f

    /** Item cell of the target hud's two hand slots (`<3:793>`). */
    const val ITEM_10 = 10f

    // --- Animation --------------------------------------------------------------------------

    // None of these come from the file: the design is a still frame. They are ours, and every one
    // of them is divided by `Animations.Speed` (see [Hud3Motion]).

    /** Duration of the panel appear / disappear fade, in milliseconds. */
    const val FADE_MS = 220f

    /** A row, a column or a toast arriving. */
    const val ENTER_MS = 260f

    /** A row, a column or a toast leaving — a touch quicker than the arrival, as exits should be. */
    const val EXIT_MS = 200f

    /** Half-life of a row sliding to its new place when the stack around it changes. */
    const val LAYOUT_HALF_LIFE_MS = 45f

    /** Half-life of a pill resizing to new content (a timer losing a digit, a new target name). */
    const val SIZE_HALF_LIFE_MS = 40f

    /** Half-life of a panel following its anchor, the drag and a change of its own width. */
    const val POSITION_HALF_LIFE_MS = 35f

    /** Half-life of a progress value (a ring, a durability bar) catching up with the real one. */
    const val PROGRESS_HALF_LIFE_MS = 90f

    /** How far, in native pixels, a row travels while it slides in or out. */
    const val SLIDE = 12f

    /** Decay of the flash on a value that changed (an item count, armour taking a hit). */
    const val PULSE_MS = 450f

    /** `easeOutCubic` — the curve every fade uses. */
    fun easeOutCubic(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /**
     * Frame-rate independent follow factor: the fraction of the remaining distance a value should
     * cover in [dt] seconds, given that it keeps [smoothing] of its error after one second.
     */
    fun damp(smoothing: Float, dt: Float): Float =
        if (dt <= 0f) 0f else (1f - Math.pow(smoothing.toDouble(), dt.toDouble()).toFloat()).coerceIn(0f, 1f)
}
