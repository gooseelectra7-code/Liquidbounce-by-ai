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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget

import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw

/**
 * Layout constants for the tooltip / key-hint plate geometry — copied verbatim from
 * `client/gui/widget/LayoutMetrics.java` (the `value` … `value10` fields, LayoutMetrics.java:11-20),
 * plus the pure plate-width helper.
 *
 * The **values are exact from the source**. The role NAMES are a best-effort read of the source's
 * own tooltip-draw geometry (the plate is height 36, corner radius 14, 12 px label, an 8x5 arrow, and
 * small edge gaps of 6/5/1). Names for the small gaps (value4..value8) are INFERRED — treat the value,
 * not the name, as authoritative.
 *
 * The four directional tooltip-DRAW helpers from the source (`onFloatFloatFloatStringMatrix4f`
 * and siblings) are NOT ported here: they render a non-square arrow via the source's `SvgShader`
 * (arg order scrambled) and belong with the tooltip layer (KeyHintBar / TooltipLayer / UiInputHandler)
 * — a later cluster. Only the constants and [plateWidth] (the pure width helper the draws share) are
 * in the C4 core.
 */
object LayoutMetrics {

    /** Tooltip plate height (source `value = 36`). */
    const val PLATE_HEIGHT = 36f

    /** Plate label text size (source `value2 = 12`). */
    const val TEXT_SIZE = 12f

    /** Arrow long dimension (source `value3 = 8`). */
    const val ARROW_LONG = 8f

    /** Arrow short dimension (source `value4 = 5`). INFERRED role. */
    const val ARROW_SHORT = 5f

    /** Arrow-to-plate gap (source `value5 = 5`). INFERRED role. */
    const val ARROW_GAP = 5f

    /** Edge gap (source `value6 = 8`). INFERRED role. */
    const val EDGE_GAP = 8f

    /** One-pixel edge nudge (source `value7 = 1`). INFERRED role. */
    const val EDGE_NUDGE = 1f

    /** Anchor gap (source `value8 = 6`). INFERRED role. */
    const val ANCHOR_GAP = 6f

    /** Plate corner radius (source `value9 = 14`). */
    const val CORNER_RADIUS = 14f

    /** Secondary label size (source `value10 = 12`). */
    const val LABEL_SIZE = 12f

    /**
     * Width of a tooltip plate holding [text]: the 12 px advance width plus 12 px padding on each
     * side (source `getFloatByString`, LayoutMetrics.java:105-107: `TextShader.width(text, 12) + 24`).
     */
    fun plateWidth(text: String): Float = SystemDraw.textWidth(text, TEXT_SIZE) + PLATE_TEXT_PADDING

    /** Total horizontal padding around the plate label (source literal `+ 24`). */
    const val PLATE_TEXT_PADDING = 24f
}
