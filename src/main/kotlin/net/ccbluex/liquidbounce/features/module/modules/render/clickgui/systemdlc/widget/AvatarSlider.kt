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

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A single collapsing label row (the username line beside a profile avatar) — ported from
 * `client/gui/widget/AvatarSlider.java`. The avatar image itself is drawn by the parent; this atom
 * owns only the label.
 *
 * ## Geometry (AvatarSlider.java, 208x32 from the ctor line 9)
 *  - Label at `x + 8 - 1 = x + 7` (:15), `y + (height - 12)/2 = y + 10` (:17), size 12 (`f6`, :23),
 *    colour [SystemDlcTheme.foreground], clipped to `max(0, x + width - 8 - labelX)` (:16) and faded
 *    by `renderAlpha * (1 - collapse)` (:18).
 *
 * The source's `TextDrawCall` was `onMatrix4fStringFloatFloatFloatIntFloat3` (the Cyrillic-primary
 * body atlas); the fork font falls back across scripts per-glyph, so the default body weight is used.
 */
class AvatarSlider(text: String) : SliderWidget(text, ROW_WIDTH, ROW_HEIGHT) {

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val labelX = x + LABEL_INSET
        val clipWidth = maxOf(0f, x + width - EDGE_PAD - labelX)
        val labelY = y + (height - LABEL_SIZE) / 2f
        drawClippedLabel(
            ctx,
            clipWidth = clipWidth,
            labelX = labelX,
            labelY = labelY,
            size = LABEL_SIZE,
            color = SystemDlcTheme.foreground(),
            alpha = alpha * (1f - collapse),
        )
    }

    private companion object {
        const val ROW_WIDTH = 208f
        const val ROW_HEIGHT = 32f

        /** Label left inset `8 - 1` (source `value235 + 8 - 1`, AvatarSlider.java:15). */
        const val LABEL_INSET = 7f

        /** Right edge padding (source `- 8`, AvatarSlider.java:16). */
        const val EDGE_PAD = 8f

        /** Label size (source `f6 = 12`, AvatarSlider.java:23). */
        const val LABEL_SIZE = 12f
    }
}
