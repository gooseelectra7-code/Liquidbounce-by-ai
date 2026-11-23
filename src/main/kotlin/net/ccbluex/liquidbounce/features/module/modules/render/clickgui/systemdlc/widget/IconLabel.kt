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
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.AnimatedInt
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.CategoryType
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A clickable icon that brightens on hover — ported from `client/gui/widget/IconLabel.java`.
 *
 * The widget box is `hitWidth x hitHeight`; the glyph itself is a smaller `iconWidth x iconHeight`
 * box centred inside it (source `f1 = x + (width - iconW)/2`, `f2 = y + (height - iconH)/2`, :30-31).
 * Its colour lerps **mutedFg -> foreground** by the base hover factor (source
 * `getIntByIntFloatInt(foreground, f, mutedFg)`, :26-29; hover from `getFloatByFloatFloat`, :25).
 *
 * The source glyph box may be non-square; [SystemDraw.icon] takes a square box, so the glyph is fit
 * into a centred square of its shorter side (identical for the common square case).
 */
class IconLabel(
    icon: CategoryType,
    hitWidth: Float,
    hitHeight: Float,
    private val iconWidth: Float,
    private val iconHeight: Float,
) : LabelWidget() {

    /** The icon key (source `categoryType`, `setCategoryType`). */
    var icon: CategoryType = icon

    /** Click callback (source `runnable`, `setRunnable`; fired by `isDoubleDouble2`, :42-49). */
    var onClickAction: (() -> Unit)? = null

    init {
        width = hitWidth
        height = hitHeight
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val f = hoverFactor(mouseX, mouseY)
        val color = Color4b(
            AnimatedInt.lerpArgb(SystemDlcTheme.foreground().argb, f, SystemDlcTheme.mutedFg().argb)
        )
        val sprite = SystemIcon.byName(icon.key) ?: return

        val boxX = x + (width - iconWidth) / 2f
        val boxY = y + (height - iconHeight) / 2f
        val s = minOf(iconWidth, iconHeight)
        SystemDraw.icon(
            ctx,
            sprite,
            boxX + (iconWidth - s) / 2f,
            boxY + (iconHeight - s) / 2f,
            s,
            SystemDraw.withAlpha(color, alpha),
        )
    }

    override fun onClick(): Boolean {
        onClickAction?.invoke()
        return true
    }
}
