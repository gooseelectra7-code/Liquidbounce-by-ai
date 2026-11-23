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

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The base for the sidebar rows that carry a single label and can COLLAPSE to icon-only — ported
 * from `client/gui/widget/SliderWidget.java`. It is the base of [TabButton] (and thus CategoryTab /
 * IconTab), [AvatarSlider], [IconSlider] and [ServerLabel].
 *
 * ## What "slider" means here (NOT a value slider)
 *
 * Despite the name, this is not a numeric slider. [collapse] (source `value239`, SliderWidget.java:8)
 * is a `0..1` factor the sidebar drives as it narrows: `0` = fully expanded (label visible), `1` =
 * collapsed to icon-only. Rows read it two ways (see the concrete subclasses):
 *  - the label's opacity is `renderAlpha * (1 - collapse)` (source `value * (1 - value239)`), and
 *  - the icon / avatar x-position lerps between its expanded and collapsed anchor by `collapse`
 *    (source `EasingPresets.getFloatByFloatFloatFloat(expanded, value239, collapsed)` = a linear
 *    `lerp(expanded, collapsed, value239)`).
 *
 * ## The clipped-label helper
 *
 * [drawClippedLabel] reproduces the shared `onFloatMatrix4fFloatIntFloatTextDrawCallFloatFloatFloatFloat`
 * (SliderWidget.java:20-28): it scissors the label to the remaining horizontal space and draws
 * [text] once, skipping entirely when the space or the opacity has collapsed to nothing
 * (`value <= 0 || value2 <= 0.001`). The source dispatched the actual glyph draw through a
 * `TextDrawCall` function pointer that selected a font weight / script atlas; on the fork
 * [SystemDraw]/[SystemFont] pick the atlas per-glyph, so the weight is passed as a [SystemFont.Family]
 * (default body weight B).
 */
abstract class SliderWidget protected constructor(
    /** The row's label text (source `text`, SliderWidget.java:7). */
    protected val text: String,
    slotWidth: Float,
    slotHeight: Float,
) : Widget() {

    init {
        width = slotWidth
        height = slotHeight
    }

    /**
     * Collapse factor `0..1` (source `value239`): `0` = label visible, `1` = icon-only. The sidebar
     * writes this each frame; the row reads it for the label fade and icon slide.
     */
    var collapse: Float = 0f

    /** The row's label text (source `getText`). */
    fun label(): String = text

    /**
     * Draw [text] clipped to the rect `(x=labelX, y, width=clipWidth, height)`, at ([labelX],
     * [labelY]) in [size]px [family] [color], faded by [alpha] (source
     * `onFloatMatrix4fFloatIntFloatTextDrawCallFloatFloatFloatFloat`, SliderWidget.java:20-28).
     * No-op when [clipWidth] `<= 0` or [alpha] `<= 0.001`.
     */
    @Suppress("LongParameterList")
    protected fun drawClippedLabel(
        ctx: GuiGraphicsExtractor,
        clipWidth: Float,
        labelX: Float,
        labelY: Float,
        size: Float,
        color: Color4b,
        alpha: Float,
        family: SystemFont.Family = SystemDraw.DEFAULT_FAMILY,
    ) {
        if (clipWidth <= 0f || alpha <= LABEL_ALPHA_EPSILON) {
            return
        }
        SystemDraw.scissor(ctx, labelX, y, clipWidth, height) {
            SystemDraw.text(ctx, text, labelX, labelY, size, SystemDraw.withAlpha(color, alpha), family)
        }
    }

    private companion object {
        /** Below this opacity the label is not drawn (source `value2 <= 0.001`, SliderWidget.java:23). */
        const val LABEL_ALPHA_EPSILON = 0.001f
    }
}
