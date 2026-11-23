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
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.CategoryType
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The sidebar header row: a large leading icon + a heavy collapsing title — ported from
 * `client/gui/widget/IconSlider.java`.
 *
 * ## Geometry (IconSlider.java, 224x49 from the ctor line 14)
 *  - Icon: 20x20 (`f6`, :35) at `x + 16 - 2 = x + 14` (:20), `y + height - 15 - 20 = y + 14` (:21),
 *    colour [SystemDlcTheme.foreground].
 *  - Title: 16 px (`f9`, :36) at `iconX + 20 + 8 = iconX + 28` (:27), `iconY + 2` (:29), colour
 *    [SystemDlcTheme.primary], clipped to `max(0, x + width - 8 - labelX)` (:28), faded by
 *    `renderAlpha * (1 - collapse)` (:30).
 *
 * The source title used `onMatrix4fStringFloatFloatFloatIntFloat2` — the heavier "C"-weight atlas;
 * reproduced here with [SystemFont.Family.C]. UNVERIFIED-SYMBOL: the exact recovered-atlas -> fork
 * family mapping is inferred from the six-atlas table in the SystemDraw KDoc (a/aa/b/bb/c/cc), not
 * confirmed against a per-glyph render.
 */
class IconSlider(
    private val categoryType: CategoryType,
    text: String,
) : SliderWidget(text, ROW_WIDTH, ROW_HEIGHT) {

    private val icon: SystemIcon? = SystemIcon.byName(categoryType.key)

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val iconX = x + ICON_INSET
        val iconY = y + height - ICON_BOTTOM_PAD - ICON_SIZE
        if (icon != null) {
            SystemDraw.icon(ctx, icon, iconX, iconY, ICON_SIZE, SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha))
        }

        val labelX = iconX + ICON_SIZE + LABEL_GAP
        val clipWidth = maxOf(0f, x + width - EDGE_PAD - labelX)
        val labelY = iconY + LABEL_Y_NUDGE
        drawClippedLabel(
            ctx,
            clipWidth = clipWidth,
            labelX = labelX,
            labelY = labelY,
            size = TITLE_SIZE,
            color = SystemDlcTheme.primary(),
            alpha = alpha * (1f - collapse),
            family = SystemFont.Family.C,
        )
    }

    private companion object {
        const val ROW_WIDTH = 224f
        const val ROW_HEIGHT = 49f
        const val ICON_SIZE = 20f

        /** Icon left inset `16 - 2` (source `value235 + 16 - 2`, IconSlider.java:20). */
        const val ICON_INSET = 14f

        /** Icon bottom padding (source `- 15`, IconSlider.java:21). */
        const val ICON_BOTTOM_PAD = 15f

        /** Gap between icon and title (source `+ 8`, IconSlider.java:27). */
        const val LABEL_GAP = 8f

        /** Right edge padding (source `- 8`, IconSlider.java:28). */
        const val EDGE_PAD = 8f

        /** Title baseline nudge below the icon top (source `f1 + 2`, IconSlider.java:29). */
        const val LABEL_Y_NUDGE = 2f

        /** Title size (source `f9 = 16`, IconSlider.java:36). */
        const val TITLE_SIZE = 16f
    }
}
