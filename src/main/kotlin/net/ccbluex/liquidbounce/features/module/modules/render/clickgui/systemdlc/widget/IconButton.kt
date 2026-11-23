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
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A square icon button that tints on hover and can cross-fade to a second icon — ported from
 * `client/gui/widget/IconButton.java`.
 *
 * ## Geometry (IconButton.java, square `size x size` from the ctor lines 19-22)
 * The icon fills the whole widget box `(x, y, size, size)` (`SvgShader.onFloatIntMatrix4f...`, :47 /
 * :57). Its colour lerps **foreground -> primary** by `max(hover, crossfade)`
 * (`getIntByIntFloatInt(primary, max(f, f1), foreground)`, :34-39). When a second icon ([altIcon]) is
 * set and [showAlt] flips, [crossTween] cross-fades: the primary icon draws at `alpha * (1 - f1)`
 * (:41) and the alt at `alpha * f1` (:51).
 *
 * ## Animation
 * [crossTween] is `EasingPresets.getTweenByFloatFloat2(0, 0.15)` = 0.15 s easeOutCubic (source
 * `tween4`, :15). Hover uses the base [Widget.hoverFactor] (source `getFloatByFloatFloat`, :31).
 *
 * ## Tooltip (deferred)
 * The source registered a hover tooltip through `HeaderPainter` (:60-66). The floating-tooltip layer
 * is NOT part of this cluster — the C4 core explicitly deferred those directional tooltip draws to a
 * later tooltip cluster (LayoutMetrics.kt:33-37). So [tooltip] is only STORED here for that later
 * cluster to render; this atom draws no tooltip itself.
 */
class IconButton(
    icon: CategoryType,
    private val size: Float,
) : LabelWidget() {

    /** The primary icon key (source `categoryType`, settable via `setCategoryType`). */
    var icon: CategoryType = icon

    /** The optional cross-fade target icon (source `categoryType2`, `setCategoryType2`). */
    var altIcon: CategoryType? = null

    /** When true, cross-fades to [altIcon] (source `flag4`, `setFlag4`, :69-71). */
    var showAlt: Boolean = false

    /** Hover tooltip text; stored for the deferred tooltip layer (source `text`, `setText`). */
    var tooltip: String? = null

    /** Click callback (source `runnable`, `setRunnable`; fired by `isDoubleDouble2`, :86-91). */
    var onClickAction: (() -> Unit)? = null

    /** Cross-fade tween — 0.15 s easeOutCubic (source `tween4`). */
    private val crossTween: Tween = EasingPresets.cubicTween(0f, CROSS_SECONDS)

    init {
        width = size
        height = size
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val hover = hoverFactor(mouseX, mouseY)
        crossTween.to(if (showAlt) 1f else 0f)
        val f1 = if (altIcon != null) crossTween.get() else 0f

        val blend = maxOf(hover, f1)
        val color = Color4b(
            AnimatedInt.lerpArgb(SystemDlcTheme.primary().argb, blend, SystemDlcTheme.foreground().argb)
        )

        if (f1 < CROSS_HIGH) {
            SystemIcon.byName(icon.key)?.let {
                drawIconFit(ctx, it, SystemDraw.withAlpha(color, alpha * (1f - f1)))
            }
        }
        if (f1 > CROSS_LOW) {
            altIcon?.let { alt ->
                SystemIcon.byName(alt.key)?.let {
                    drawIconFit(ctx, it, SystemDraw.withAlpha(color, alpha * f1))
                }
            }
        }
    }

    private fun drawIconFit(ctx: GuiGraphicsExtractor, sprite: SystemIcon, color: Color4b) {
        SystemDraw.icon(ctx, sprite, x, y, size, color)
    }

    override fun onClick(): Boolean {
        onClickAction?.invoke()
        return true
    }

    private companion object {
        const val CROSS_SECONDS = 0.15f
        const val CROSS_LOW = 0.001f
        const val CROSS_HIGH = 0.999f
    }
}
