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
import kotlin.math.roundToInt

/**
 * A FLOAT / INT slider — ported in role from `client/gui/widget/SliderRow.java`, with the read/write and
 * value-formatting logic mirrored from
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.drawSlider]
 * (LeetClickGuiScreen.kt:1940-2058) so it edits the fork [RangedValue] correctly. Only single-value
 * FLOAT and INT are handled here; FLOAT_RANGE / INT_RANGE (two handles) fall to a placeholder and arrive
 * in a later cluster.
 *
 * ## Layout
 * The label sits top-left and the formatted value top-right; the track runs full-width below them with a
 * primary-filled portion and a knob — the SystemDLC "value above centre, track below centre" split
 * (SliderRow.java:92-104), kept inside the row so the uniform inter-setting gap is not disturbed.
 *
 * ## Drag capture
 * A left press anywhere on the row starts a drag that owns the pointer until release ([capturing]) — the
 * fork's press-capture model (LeetClickGuiScreen.kt:2030-2035/3687-3699), so passing over another slider
 * cannot steal it. The value is set from the pointer X each frame while dragging; no-op sets are skipped
 * so change listeners / autosave do not fire every frame (LeetClickGuiScreen.kt:2047-2054).
 */
class SliderRow(private val ranged: RangedValue<*>) : SettingRow(ranged) {

    private var dragging = false

    override val capturing: Boolean get() = dragging

    override fun measure(width: Float): Float =
        SystemDraw.lineHeight(VALUE_SIZE) + TEXT_TRACK_GAP + KNOB_DIAMETER

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val frac = currentFraction()

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

        // track (full width) on the lower half; filled portion + knob
        val trackY = rowY + textH + TEXT_TRACK_GAP + KNOB_RADIUS - TRACK_THICKNESS / 2f
        SystemDraw.panel(
            ctx, rowX, trackY, rowWidth, TRACK_THICKNESS, radius = TRACK_THICKNESS / 2f,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
        )
        val fillW = rowWidth * frac
        if (fillW > 0.01f) {
            SystemDraw.panel(
                ctx, rowX, trackY, fillW, TRACK_THICKNESS, radius = TRACK_THICKNESS / 2f,
                fill = SystemDraw.withAlpha(SystemDlcTheme.primary(), alpha),
            )
        }
        val knobX = rowX + rowWidth * frac - KNOB_RADIUS
        val knobY = trackY + TRACK_THICKNESS / 2f - KNOB_RADIUS
        SystemDraw.panel(
            ctx, knobX, knobY, KNOB_DIAMETER, KNOB_DIAMETER, radius = KNOB_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            dragging = true
            applyFraction(((mouseX - rowX) / rowWidth))
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
        applyFraction(((mouseX - rowX) / rowWidth))
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (dragging && button == 0) {
            dragging = false
            return true
        }
        return false
    }

    // ---- value <-> fraction (mirrors LeetClickGuiScreen.drawSlider / applySliderFrac) ----

    private fun currentFraction(): Float = when (ranged.valueType) {
        ValueType.FLOAT -> {
            val range = ranged.range as ClosedFloatingPointRange<Float>
            fracOf((ranged.get() as Float), range.start, range.endInclusive)
        }
        ValueType.INT -> {
            val range = ranged.range as IntRange
            fracOf((ranged.get() as Int).toFloat(), range.first.toFloat(), range.last.toFloat())
        }
        else -> 0f
    }

    private fun displayText(): String = when (ranged.valueType) {
        ValueType.FLOAT -> "%.1f%s".format(ranged.get() as Float, suffixText(ranged.suffix))
        ValueType.INT -> "%d%s".format(ranged.get() as Int, suffixText(ranged.suffix))
        else -> ranged.get().toString()
    }

    /** A non-blank suffix is shown with a leading space (matches LeetClickGuiScreen.suffixText). */
    private fun suffixText(suffix: String): String = if (suffix.isBlank()) "" else " $suffix"

    @Suppress("UNCHECKED_CAST")
    private fun applyFraction(rawFrac: Float) {
        val frac = rawFrac.coerceIn(0f, 1f)
        when (ranged.valueType) {
            ValueType.FLOAT -> {
                val range = ranged.range as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100f).roundToInt() / 100f
                if (v != ranged.get()) {
                    (ranged as RangedValue<Float>).set(v)
                }
            }
            ValueType.INT -> {
                val range = ranged.range as IntRange
                val raw = range.first + frac * (range.last - range.first)
                val v = raw.roundToInt().coerceIn(range)
                if (v != ranged.get()) {
                    (ranged as RangedValue<Int>).set(v)
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

        fun fracOf(v: Float, lo: Float, hi: Float): Float =
            if (hi <= lo) 0f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    }
}
