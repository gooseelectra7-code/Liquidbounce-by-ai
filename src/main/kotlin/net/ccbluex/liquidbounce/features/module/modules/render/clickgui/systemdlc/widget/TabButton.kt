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
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A sidebar tab row: leading category icon + a collapsing label, with a hover/active elevated fill —
 * ported from `client/gui/widget/TabButton.java`. Abstract; [CategoryTab] and [IconTab] fill in the
 * active state and click behaviour.
 *
 * ## Geometry (all cited to TabButton.java, 208x32 row from the ctor line 20)
 *  - Row fill: full `width x height` rounded rect, radius 8 (`f11 = 8`, :42), colour
 *    [SystemDlcTheme.elevated] faded by `renderAlpha * fillTween` (:38-45).
 *  - Icon: 16x16 (`f13 = 16`, :52) at `x + 16 - 8 = x + 8` (:48), `y + (height - 16)/2` (:49),
 *    colour [SystemDlcTheme.foreground].
 *  - Label: 14 px (`f16 = 14`, :64) at `iconX + 16 + 8 = x + 32` (:55), `y + (height - 14)/2 = y + 9`
 *    (:57), clipped to `max(0, x + width - 8 - labelX)` (:56), faded by `renderAlpha * (1 - collapse)`
 *    (:58).
 *
 * ## Animation
 * The fill tween is `EasingPresets.getTween()` = 0.15 s easeOutCubic (source `tween4`, :16). Its
 * target each frame is `active ? 1 : (hovered ? 0.82 : 0)` (source `value240 = 0.82`, :14 / :30).
 * Hover is `contains(mouse) && !WidgetState.isModalActive()` (source `!WidgetState.check()`, :29).
 */
abstract class TabButton protected constructor(
    /** The tab's icon key (source `categoryType`, TabButton.java:15). */
    protected val categoryType: CategoryType,
    text: String,
) : SliderWidget(text, TAB_WIDTH, TAB_HEIGHT) {

    /** The resolved icon sprite for [categoryType], or null if the atlas has no such sprite. */
    private val icon: SystemIcon? = SystemIcon.byName(categoryType.key)

    /** Fill reveal tween — 0.15 s easeOutCubic (source `tween4 = EasingPresets.getTween()`). */
    private val fillTween: Tween = EasingPresets.cubicTweenShort()

    /**
     * Whether this tab is the currently-selected one (source `check()`, TabButton.java:23, default
     * false). Drives the fill to fully-on regardless of hover.
     */
    protected open fun active(): Boolean = false

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        hovered = contains(mouseX, mouseY) && !WidgetState.isModalActive()
        fillTween.to(if (active()) 1f else if (hovered) HOVER_FILL else 0f)
        val fill = fillTween.get()
        if (fill > FILL_EPSILON) {
            SystemDraw.panel(
                ctx,
                x,
                y,
                width,
                height,
                radius = ROW_RADIUS,
                fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha * fill),
            )
        }

        val foreground = SystemDlcTheme.foreground()
        val iconX = x + ICON_INSET
        val iconY = y + (height - ICON_SIZE) / 2f
        if (icon != null) {
            SystemDraw.icon(ctx, icon, iconX, iconY, ICON_SIZE, SystemDraw.withAlpha(foreground, alpha))
        }

        val labelX = iconX + ICON_SIZE + LABEL_GAP
        val clipWidth = maxOf(0f, x + width - EDGE_PAD - labelX)
        val labelY = y + (height - LABEL_SIZE) / 2f
        drawClippedLabel(
            ctx,
            clipWidth = clipWidth,
            labelX = labelX,
            labelY = labelY,
            size = LABEL_SIZE,
            color = foreground,
            alpha = alpha * (1f - collapse),
        )
    }

    private companion object {
        /** Row width, GUI units (source ctor `208`, TabButton.java:20). */
        const val TAB_WIDTH = 208f

        /** Row height, GUI units (source ctor `32`, TabButton.java:20). */
        const val TAB_HEIGHT = 32f

        /** Hover fill target (source `value240 = 0.82`, TabButton.java:14). */
        const val HOVER_FILL = 0.82f

        /** Row fill corner radius (source `f11 = 8`, TabButton.java:42). */
        const val ROW_RADIUS = 8f

        /** Icon box size (source `f13 = 16`, TabButton.java:52). */
        const val ICON_SIZE = 16f

        /** Icon left inset `16 - 8` (source `value235 + 16 - 8`, TabButton.java:48). */
        const val ICON_INSET = 8f

        /** Gap between icon and label (source `+ 8`, TabButton.java:55). */
        const val LABEL_GAP = 8f

        /** Right edge padding for the label clip (source `- 8`, TabButton.java:56). */
        const val EDGE_PAD = 8f

        /** Label text size (source `f16 = 14`, TabButton.java:64). */
        const val LABEL_SIZE = 14f

        /** Below this the fill is not drawn (source `f > 0.001`, TabButton.java:32). */
        const val FILL_EPSILON = 0.001f
    }
}
