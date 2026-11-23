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
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The star favourite toggle — ported from `client/gui/widget/FavoriteButton.java`. A `16 x 16` icon
 * that cross-fades between an outline star and a filled star as it toggles.
 *
 * ## Geometry (FavoriteButton.java, 16x16 from the ctor lines 20-21)
 * Both glyphs fill the whole box `(x, y, 16, 16)`. The colour lerps **mutedFg -> primary** by the
 * toggle factor `f` (`getIntByIntFloatInt(primary, f, mutedFg)`, :29-32). Outline [SystemIcon.STAR]
 * draws at `alpha * (1 - f)` while `f < 0.999` (:33-40); filled [SystemIcon.STAR_FILLED] at `alpha * f`
 * while `f > 0.001` (:43-50).
 *
 * ## Animation
 * `tween4 = EasingPresets.getTweenByFloatFloat2(state ? 1 : 0, 0.15)` = 0.15 s easeOutCubic (source
 * :22). The source played a sound on favouriting (:66); sound is not part of the render port and is
 * omitted (a callback [onToggle] fires instead).
 */
class FavoriteButton(initialState: Boolean) : LabelWidget() {

    /** Current favourite state (source `flag4`, `isFlag4`). */
    var state: Boolean = initialState
        private set

    /** Fired when toggled by the user (source `runnable`; also where the source plays its sound). */
    var onToggle: (() -> Unit)? = null

    /** Cross-fade tween — 0.15 s easeOutCubic (source `tween4`). */
    private val tween: Tween = EasingPresets.cubicTween(if (initialState) 1f else 0f, TWEEN_SECONDS)

    init {
        width = SIZE
        height = SIZE
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        tween.to(if (state) 1f else 0f)
        val f = tween.get()
        val color = Color4b(
            AnimatedInt.lerpArgb(SystemDlcTheme.primary().argb, f, SystemDlcTheme.mutedFg().argb)
        )

        if (f < HIGH) {
            SystemDraw.icon(ctx, SystemIcon.STAR, x, y, SIZE, SystemDraw.withAlpha(color, alpha * (1f - f)))
        }
        if (f > LOW) {
            SystemDraw.icon(ctx, SystemIcon.STAR_FILLED, x, y, SIZE, SystemDraw.withAlpha(color, alpha * f))
        }
    }

    override fun onClick(): Boolean {
        state = !state
        onToggle?.invoke()
        return true
    }

    private companion object {
        const val SIZE = 16f
        const val LOW = 0.001f
        const val HIGH = 0.999f
        const val TWEEN_SECONDS = 0.15f
    }
}
