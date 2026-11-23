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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A FLOAT_RANGE / INT_RANGE two-handle range slider — the range sibling of [SliderRow]. It edits the fork
 * [RangedValue] whose scalar value is itself a [ClosedRange] (a [ClosedFloatingPointRange] for FLOAT_RANGE,
 * an [IntRange] for INT_RANGE). The track/knob geometry, value-text layout, capture-on-drag model and the
 * per-frame no-op-skip are copied from [SliderRow]; the two-handle read/write logic is mirrored from
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.drawSlider]
 * (LeetClickGuiScreen.kt:1957-2132 — `pickRangeHandle` + `applyRangeFrac`), restyled to the SystemDLC look.
 *
 * ## Layout
 * Same split as [SliderRow]: the label sits top-left and the `low .. high` value text top-right; the track
 * runs full-width below them (elevated). The primary-filled portion is drawn BETWEEN the two knobs (not
 * from the track start), and a knob is drawn at each of the low and high positions.
 *
 * ## Drag capture
 * A left press anywhere on the row starts a drag that owns the pointer until release ([capturing]) — the
 * fork's press-capture model, so passing over another slider cannot steal it. The grabbed handle (0 = low,
 * 1 = high) is picked on the FIRST application of the drag and LOCKED for its whole life: the nearer knob
 * in fraction space, or — when the two handles are collapsed onto the same spot — decided by drag direction
 * once the pointer leaves the overlap (right grabs high, left grabs low), which is what keeps a collapsed
 * range draggable at all. Handles clamp against each other (`low <= high`) and can never cross; the new
 * range is snapped to step and written each change, skipping no-op sets so listeners / autosave do not fire
 * every frame (LeetClickGuiScreen.kt:2113/2126).
 */
class RangeRow(private val ranged: RangedValue<*>) : SettingRow(ranged) {

    private var dragging = false

    /** The drag-locked handle: 0 = low bound, 1 = high bound, -1 = not yet decided this drag. */
    private var handle = -1

    override val capturing: Boolean get() = dragging

    override fun measure(width: Float): Float =
        SystemDraw.lineHeight(VALUE_SIZE) + TEXT_TRACK_GAP + KNOB_DIAMETER

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val (frac0, frac1) = currentFractions()

        // top line: label (left) + value text (right)
        val textH = SystemDraw.lineHeight(VALUE_SIZE)
        SystemDraw.text(
            ctx, value.name, rowX, rowY, SETTING_ROW_LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )
        SystemDraw.text(
            ctx, displayText(), rowX + rowWidth, rowY, VALUE_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.primary(), alpha), align = TextAlign.RIGHT,
        )

        // track (full width) on the lower half; filled portion drawn BETWEEN the two knobs + both knobs
        val trackY = rowY + textH + TEXT_TRACK_GAP + KNOB_RADIUS - TRACK_THICKNESS / 2f
        SystemDraw.panel(
            ctx, rowX, trackY, rowWidth, TRACK_THICKNESS, radius = TRACK_THICKNESS / 2f,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
        )
        val fillX = rowX + rowWidth * frac0
        val fillW = rowWidth * (frac1 - frac0)
        if (fillW > 0.01f) {
            SystemDraw.panel(
                ctx, fillX, trackY, fillW, TRACK_THICKNESS, radius = TRACK_THICKNESS / 2f,
                fill = SystemDraw.withAlpha(SystemDlcTheme.primary(), alpha),
            )
        }
        val knobY = trackY + TRACK_THICKNESS / 2f - KNOB_RADIUS
        drawKnob(ctx, rowX + rowWidth * frac0 - KNOB_RADIUS, knobY, alpha)
        drawKnob(ctx, rowX + rowWidth * frac1 - KNOB_RADIUS, knobY, alpha)
    }

    private fun drawKnob(ctx: GuiGraphicsExtractor, knobX: Float, knobY: Float, alpha: Float) {
        SystemDraw.panel(
            ctx, knobX, knobY, KNOB_DIAMETER, KNOB_DIAMETER, radius = KNOB_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            dragging = true
            handle = -1
            applyFraction((mouseX - rowX) / rowWidth)
            return true
        }
        return false
    }

    override fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        if (!dragging) {
            return false
        }
        applyFraction((mouseX - rowX) / rowWidth)
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (dragging && button == 0) {
            dragging = false
            handle = -1
            return true
        }
        return false
    }

    // ---- value <-> fraction (mirrors LeetClickGuiScreen.rangeFracs / pickRangeHandle / applyRangeFrac) ----

    /** Current (low, high) handle positions in fraction space [0..1], or (0, 0) if not a range type. */
    @Suppress("UNCHECKED_CAST")
    private fun currentFractions(): Pair<Float, Float> = when (ranged.valueType) {
        ValueType.FLOAT_RANGE -> {
            val range = ranged.range as ClosedFloatingPointRange<Float>
            val cur = ranged.get() as ClosedFloatingPointRange<Float>
            fracOf(cur.start, range.start, range.endInclusive) to
                fracOf(cur.endInclusive, range.start, range.endInclusive)
        }
        ValueType.INT_RANGE -> {
            val range = ranged.range as IntRange
            val cur = ranged.get() as IntRange
            fracOf(cur.first.toFloat(), range.first.toFloat(), range.last.toFloat()) to
                fracOf(cur.last.toFloat(), range.first.toFloat(), range.last.toFloat())
        }
        else -> 0f to 0f
    }

    @Suppress("UNCHECKED_CAST")
    private fun displayText(): String = when (ranged.valueType) {
        ValueType.FLOAT_RANGE -> {
            val cur = ranged.get() as ClosedFloatingPointRange<Float>
            "%.1f .. %.1f%s".format(cur.start, cur.endInclusive, suffixText(ranged.suffix))
        }
        ValueType.INT_RANGE -> {
            val cur = ranged.get() as IntRange
            "%d .. %d%s".format(cur.first, cur.last, suffixText(ranged.suffix))
        }
        else -> ranged.get().toString()
    }

    /** A non-blank suffix is shown with a leading space (matches SliderRow / LeetClickGuiScreen.suffixText). */
    private fun suffixText(suffix: String): String = if (suffix.isBlank()) "" else " $suffix"

    private fun applyFraction(rawFrac: Float) {
        val frac = rawFrac.coerceIn(0f, 1f)
        if (handle < 0) {
            handle = pickHandle(frac)
        }
        if (handle >= 0) {
            applyRangeFrac(frac, handle)
        }
    }

    /**
     * Which handle a range drag grabs: the nearer one in fraction space (mirrors
     * LeetClickGuiScreen.pickRangeHandle, LeetClickGuiScreen.kt:2086-2096). Collapsed handles (both at the
     * same spot) are decided by drag DIRECTION once the pointer leaves the overlap — right grabs the high
     * handle, left grabs the low one; returns -1 while the direction is not yet clear (nothing applied that
     * frame), which is what makes a collapsed range draggable at all.
     */
    private fun pickHandle(frac: Float): Int {
        val (f0, f1) = currentFractions()
        if (abs(f1 - f0) < COLLAPSE_EPS) {
            return when {
                frac > f0 + COLLAPSE_EPS -> 1
                frac < f0 - COLLAPSE_EPS -> 0
                else -> -1
            }
        }
        return if (abs(frac - f0) <= abs(frac - f1)) 0 else 1
    }

    /**
     * Applies [frac] to the drag-locked [handle] (0 = low, 1 = high), clamping `low <= high` and snapping to
     * step, then writes the new range (mirrors LeetClickGuiScreen.applyRangeFrac, LeetClickGuiScreen.kt
     * :2100-2132). No-op sets are skipped by endpoint comparison — ranges have no value equality.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyRangeFrac(frac: Float, handle: Int) {
        when (ranged.valueType) {
            ValueType.FLOAT_RANGE -> {
                val range = ranged.range as ClosedFloatingPointRange<Float>
                val cur = ranged.get() as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100f).roundToInt() / 100f
                val newRange = if (handle == 0) {
                    v.coerceAtMost(cur.endInclusive)..cur.endInclusive
                } else {
                    cur.start..v.coerceAtLeast(cur.start)
                }
                if (newRange.start != cur.start || newRange.endInclusive != cur.endInclusive) {
                    (ranged as RangedValue<ClosedFloatingPointRange<Float>>).set(newRange)
                }
            }
            ValueType.INT_RANGE -> {
                val range = ranged.range as IntRange
                val cur = ranged.get() as IntRange
                val v = (range.first + frac * (range.last - range.first)).roundToInt()
                val newRange = if (handle == 0) {
                    v.coerceAtMost(cur.last)..cur.last
                } else {
                    cur.first..v.coerceAtLeast(cur.first)
                }
                if (newRange.first != cur.first || newRange.last != cur.last) {
                    (ranged as RangedValue<IntRange>).set(newRange)
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val VALUE_SIZE = 11f
        const val TEXT_TRACK_GAP = 3f
        const val TRACK_THICKNESS = 2f
        const val KNOB_RADIUS = 3.2f
        const val KNOB_DIAMETER = KNOB_RADIUS * 2f

        /** Two handles closer than this (fraction space) count as collapsed — pick by direction. */
        const val COLLAPSE_EPS = 1.0e-3f

        fun fracOf(v: Float, lo: Float, hi: Float): Float =
            if (hi <= lo) 0f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    }
}
