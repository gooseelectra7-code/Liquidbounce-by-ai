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

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.ColorMath
import net.ccbluex.liquidbounce.render.drawGradientRoundedRect
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawSweepRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.roundToInt

/**
 * A COLOR setting — an inline-expanding HSV/alpha colour picker. It edits a fork [Value] whose
 * `valueType == ValueType.COLOR` (its [Value.get] / [Value.set] carry a [Color4b], Value.kt:220/222),
 * so [SettingRow.create] hands it a raw `Value<*>` narrowed to `Value<Color4b>` here.
 *
 * ## Reference
 * The layout and behaviour mirror the proven native picker
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.drawColorSetting]
 * (LeetClickGuiScreen.kt:3413-3545) and its helpers `drawSvArea` (:3547-3574), `drawHueBar`
 * (:3576-3581) and `drawAlphaGradient` (:3583-3587), restyled to the SystemDLC palette via
 * [SystemDraw] / [SystemDlcTheme].
 *
 * ## Collapsed head (always drawn)
 * The setting name on the left, then on the right a `#RRGGBB alpha%` readout and a small swatch square
 * (rightmost). Same information the Leet row shows (`"#%02X%02X%02X %d%%"`, LeetClickGuiScreen.kt:3424),
 * with the alpha percentage from `color.a * 100 / 255` (:3424). The head band toggles [expanded] on LMB
 * or RMB, matching the Leet row hit that opens on `button == 0 || button == 1` (:3432-3443). The swatch
 * shows the RGB opaque (alpha stripped, LeetClickGuiScreen.kt:3430) so a transparent colour still reads.
 *
 * ## Expanded body
 * A square SV area (corners kept SQUARE — radius 0 gradients, mirroring `drawSvArea` which finishes with a
 * hard `drawQuad` border, LeetClickGuiScreen.kt:3573), a rounded hue bar and a rounded alpha bar
 * (`drawSweepRoundedRect`, matching `drawHueBar`/`drawAlphaGradient`), and a small hex readout. Geometry
 * follows the Leet body offsets (svH 46, barH 8, uniform gap; LeetClickGuiScreen.kt:3451-3457): head
 * height, then `pad + SV + pad + hue + pad + alpha + pad + hex`. [measure] returns the head height when
 * collapsed and that full stack when open, so the host card reflows the rows below inline
 * (ModuleRow.expandedHeight sums `row.measure`, ModuleRow.kt:131-144).
 *
 * ## Editing & HSV state
 * The SV square sets S/V, the hue bar sets hue, the alpha bar sets alpha; every change writes back to the
 * [Value]. H/S/V are kept in [hsv] (the Leet `pickerState` trick, LeetClickGuiScreen.kt:3407-3411) so the
 * hue survives when S or V reach 0 and the colour goes gray — the [Value] only stores RGBA. [hsv] is
 * re-synced from the current colour each time the picker opens (matching the Leet re-sync on open,
 * LeetClickGuiScreen.kt:3438-3439). A drag on SV/hue/alpha holds a pointer capture ([capturing]) so the
 * host keeps routing drags here (mirrors [SliderRow], ModuleRow.kt:301-321).
 */
class ColorRow(value: Value<*>) : SettingRow(value) {

    @Suppress("UNCHECKED_CAST")
    private val colorValue: Value<Color4b> = value as Value<Color4b>

    /** `[hue, saturation, value]`, each 0..1 — the `pickerState` local HSV kept across gray colours. */
    private val hsv = FloatArray(3)

    private var expanded = false

    private var dragMode = DragMode.NONE

    override val capturing: Boolean get() = dragMode != DragMode.NONE

    // ------------------------------------------------------------------ geometry
    // Recomputed from the current place() each access, so measure() and the hit rects never disagree.

    private fun headHeight(): Float = maxOf(SWATCH_SIZE, SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE))

    private fun svTop(): Float = rowY + headHeight() + PAD
    private fun hueTop(): Float = svTop() + SV_H + PAD
    private fun alphaTop(): Float = hueTop() + BAR_H + PAD
    private fun hexTop(): Float = alphaTop() + BAR_H + PAD

    override fun measure(width: Float): Float {
        val head = headHeight()
        if (!expanded) {
            return head
        }
        // head + pad + SV + pad + hue + pad + alpha + pad + hex readout
        return head + PAD + SV_H + PAD + BAR_H + PAD + BAR_H + PAD + SystemDraw.lineHeight(HEX_SIZE)
    }

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val color = colorValue.get()
        val opaque = Color4b(color.r, color.g, color.b, 255)
        val head = headHeight()

        // label (left)
        SystemDraw.text(
            ctx, value.name, rowX, rowY + (head - SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE)) / 2f,
            SETTING_ROW_LABEL_SIZE, SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha),
            align = TextAlign.LEFT,
        )

        // right cluster: swatch square (rightmost) + "#RRGGBB alpha%" readout to its left
        val swatchX = rowX + rowWidth - SWATCH_SIZE
        val swatchY = rowY + (head - SWATCH_SIZE) / 2f
        SystemDraw.panel(
            ctx, swatchX, swatchY, SWATCH_SIZE, SWATCH_SIZE, radius = SWATCH_RADIUS,
            fill = SystemDraw.withAlpha(opaque, alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
        )
        val summary = hexSummary(color)
        SystemDraw.text(
            ctx, summary, swatchX - SWATCH_GAP, rowY + (head - SystemDraw.lineHeight(VALUE_SIZE)) / 2f,
            VALUE_SIZE, SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha), align = TextAlign.RIGHT,
        )

        if (!expanded) {
            return
        }

        drawSvArea(ctx, alpha, opaque)
        drawHueBar(ctx, alpha)
        drawAlphaBar(ctx, alpha, color)

        // small hex readout at the bottom of the picker body
        SystemDraw.text(
            ctx, summary, rowX, hexTop(), HEX_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha), align = TextAlign.LEFT,
        )
    }

    /** Saturation/value square: white -> hue (horizontal) then transparent -> black (vertical) overlay. */
    private fun drawSvArea(ctx: GuiGraphicsExtractor, alpha: Float, opaque: Color4b) {
        val x0 = rowX
        val y0 = svTop()
        val x1 = rowX + rowWidth
        val y1 = y0 + SV_H
        val hueFull = ColorMath.hsvToArgb(hsv[0], 1f, 1f)

        // layer 1: left white -> right hue (drawGradientRoundedRect corner order is TL, BL, BR, TR)
        ctx.drawGradientRoundedRect(
            x0, y0, x1, y1, 0f,
            colorTL = fade(Color4b.WHITE.argb, alpha),
            colorBL = fade(Color4b.WHITE.argb, alpha),
            colorBR = fade(hueFull, alpha),
            colorTR = fade(hueFull, alpha),
        )
        // layer 2: top transparent -> bottom black (this is the V axis, multiplying layer 1 down to black)
        ctx.drawGradientRoundedRect(
            x0, y0, x1, y1, 0f,
            colorTL = fade(TRANSPARENT_BLACK, alpha),
            colorBL = fade(Color4b.BLACK.argb, alpha),
            colorBR = fade(Color4b.BLACK.argb, alpha),
            colorTR = fade(TRANSPARENT_BLACK, alpha),
        )
        // square border
        ctx.drawRoundedRect(
            x0, y0, x1, y1, 0f, null, SystemDraw.withAlpha(SystemDlcTheme.border(), alpha), 1f,
        )
        // knob at (S, 1-V)
        val knobX = (x0 + hsv[1] * rowWidth).coerceIn(x0 + KNOB_R, x1 - KNOB_R)
        val knobY = (y0 + (1f - hsv[2]) * SV_H).coerceIn(y0 + KNOB_R, y1 - KNOB_R)
        drawKnob(ctx, knobX, knobY, SystemDraw.withAlpha(opaque, alpha), alpha)
    }

    /** Rounded hue bar (full saturation/value sweep). */
    private fun drawHueBar(ctx: GuiGraphicsExtractor, alpha: Float) {
        val x0 = rowX
        val y0 = hueTop()
        val x1 = rowX + rowWidth
        val y1 = y0 + BAR_H
        ctx.drawSweepRoundedRect(x0, y0, x1, y1, BAR_RADIUS, columns = 24) { fraction ->
            fade(ColorMath.hsvToArgb(fraction.coerceAtMost(0.999f), 1f, 1f), alpha)
        }
        ctx.drawRoundedRect(
            x0, y0, x1, y1, BAR_RADIUS, null, SystemDraw.withAlpha(SystemDlcTheme.border(), alpha), 1f,
        )
        val knobX = (x0 + hsv[0] * rowWidth).coerceIn(x0 + KNOB_R, x1 - KNOB_R)
        val knobFill = SystemDraw.withAlpha(Color4b(ColorMath.hsvToArgb(hsv[0], 1f, 1f)), alpha)
        drawKnob(ctx, knobX, y0 + BAR_H / 2f, knobFill, alpha)
    }

    /** Rounded alpha bar: an elevated field with a transparent -> opaque colour sweep inset inside it. */
    private fun drawAlphaBar(ctx: GuiGraphicsExtractor, alpha: Float, color: Color4b) {
        val x0 = rowX
        val y0 = alphaTop()
        val x1 = rowX + rowWidth
        val y1 = y0 + BAR_H
        ctx.drawRoundedRect(
            x0, y0, x1, y1, BAR_RADIUS,
            SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
            SystemDraw.withAlpha(SystemDlcTheme.border(), alpha), 1f,
        )
        ctx.drawSweepRoundedRect(
            x0 + 1f, y0 + 1f, x1 - 1f, y1 - 1f, (BAR_RADIUS - 1f).coerceAtLeast(0f), columns = 16,
        ) { fraction ->
            fade(color.with(a = (fraction * 255f).toInt().coerceIn(0, 255)).argb, alpha)
        }
        val knobX = (x0 + (color.a / 255f) * rowWidth).coerceIn(x0 + KNOB_R, x1 - KNOB_R)
        val knobFill = SystemDraw.withAlpha(Color4b(color.r, color.g, color.b, 255), alpha)
        drawKnob(ctx, knobX, y0 + BAR_H / 2f, knobFill, alpha)
    }

    /** A small circular marker: [fill] centre ringed by a white border. */
    private fun drawKnob(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, fill: Color4b, alpha: Float) {
        SystemDraw.panel(
            ctx, cx - KNOB_R, cy - KNOB_R, KNOB_R * 2f, KNOB_R * 2f, radius = KNOB_R,
            fill = fill, border = SystemDraw.withAlpha(Color4b.WHITE, alpha), borderWidth = 1.5f,
        )
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (expanded && button == 0) {
            if (Widget.rectContains(rowX, svTop(), rowWidth, SV_H, mouseX, mouseY)) {
                dragMode = DragMode.SV
                applySv(mouseX, mouseY)
                return true
            }
            if (Widget.rectContains(rowX, hueTop(), rowWidth, BAR_H, mouseX, mouseY)) {
                dragMode = DragMode.HUE
                applyHue(mouseX)
                return true
            }
            if (Widget.rectContains(rowX, alphaTop(), rowWidth, BAR_H, mouseX, mouseY)) {
                dragMode = DragMode.ALPHA
                applyAlpha(mouseX)
                return true
            }
        }
        // the head band toggles the picker on LMB or RMB (Leet: button 0 || 1)
        if ((button == 0 || button == 1) && Widget.rectContains(rowX, rowY, rowWidth, headHeight(), mouseX, mouseY)) {
            setExpanded(!expanded)
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
        when (dragMode) {
            DragMode.SV -> applySv(mouseX, mouseY)
            DragMode.HUE -> applyHue(mouseX)
            DragMode.ALPHA -> applyAlpha(mouseX)
            DragMode.NONE -> return false
        }
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (dragMode != DragMode.NONE && button == 0) {
            dragMode = DragMode.NONE
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ state <-> value

    private fun setExpanded(value: Boolean) {
        expanded = value
        if (value) {
            syncHsv()
        }
    }

    /** Re-read [hsv] from the current colour (on open) — the Leet re-sync, LeetClickGuiScreen.kt:3438. */
    private fun syncHsv() {
        val h = ColorMath.argbToHsv(colorValue.get())
        hsv[0] = h[0]
        hsv[1] = h[1]
        hsv[2] = h[2]
    }

    private fun applySv(mouseX: Float, mouseY: Float) {
        val s = ((mouseX - rowX) / rowWidth).coerceIn(0f, 1f)
        val v = (1f - (mouseY - svTop()) / SV_H).coerceIn(0f, 1f)
        if (s != hsv[1] || v != hsv[2]) {
            hsv[1] = s
            hsv[2] = v
            writeFromHsv()
        }
    }

    private fun applyHue(mouseX: Float) {
        val h = ((mouseX - rowX) / rowWidth).coerceIn(0f, 0.9999f)
        if (h != hsv[0]) {
            hsv[0] = h
            writeFromHsv()
        }
    }

    private fun applyAlpha(mouseX: Float) {
        val a = (((mouseX - rowX) / rowWidth).coerceIn(0f, 1f) * 255f).roundToInt()
        val current = colorValue.get()
        if (a != current.a) {
            // keep RGB exact; only the alpha byte moves (mirrors LeetClickGuiScreen.kt:3529)
            colorValue.set(current.with(a = a))
        }
    }

    /** Write the current [hsv] back to the [Value], preserving the colour's alpha byte. */
    private fun writeFromHsv() {
        val a = colorValue.get().a
        colorValue.set(Color4b(ColorMath.hsvToArgb(hsv[0], hsv[1], hsv[2], a)))
    }

    private fun hexSummary(color: Color4b): String =
        "#%02X%02X%02X %d%%".format(color.r, color.g, color.b, color.a * 100 / 255)

    /** Scale a packed ARGB by the card's fade [alpha] (keeps zero-alpha corners transparent). */
    private fun fade(argb: Int, alpha: Float): Int = ColorMath.scaleAlpha(argb, alpha)

    private enum class DragMode { NONE, SV, HUE, ALPHA }

    private companion object {
        const val VALUE_SIZE = 11f
        const val HEX_SIZE = 10f
        const val SWATCH_SIZE = 10f
        const val SWATCH_RADIUS = 2f
        const val SWATCH_GAP = 6f
        const val PAD = 6f
        const val SV_H = 46f
        const val BAR_H = 8f
        const val BAR_RADIUS = BAR_H / 2f
        const val KNOB_R = 3.4f

        /** Fully transparent black (`0x00000000`) for the SV overlay's top corners. */
        const val TRANSPARENT_BLACK = 0
    }
}
