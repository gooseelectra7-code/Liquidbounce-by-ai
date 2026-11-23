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
 * The checkbox-style card button — ported from `client/gui/widget/SmallButton.java`. A `16 x 16`
 * rounded card that fills toward the accent when on and reveals a check glyph.
 *
 * ## Geometry (SmallButton.java, 16x16 from the ctor lines 22-23)
 *  - Card: full `16 x 16`, radius 6 (`f10..f13 = 6`, :45-48), 1 px border (`f14 = 1`, :44), shadow
 *    `0x1A000000` (`k1 = 436207616`, :43) blurred 1 px (`f17 = 1`, :40). Drawn via the full
 *    per-corner ShapeShader (`onFloatIntIntFloat...`, :53). Decoded parameter roles: A=width,
 *    B=shadowColor, C=fill, D=x, E=y, F=shadowBlur, G/L/N/O=corner radii, H=height, J=alpha,
 *    K=borderColor, M=borderWidth (cross-checked against ToggleButton / ServerLabel call sites).
 *  - Fill lerps **background -> primary** by `tween5` (`getIntByIntFloatInt(primary, f, background)`,
 *    :31-34); border lerps **border -> primary** by the same (:35-38).
 *  - Check glyph: [SystemIcon.SUCCESS_CHECKBOX] in `7 x 6` (`f2/f3`, :57-58) centred in the card,
 *    colour [SystemDlcTheme.surface], faded by `renderAlpha * tween4` (:63), only while `tween4 > 0.001`.
 *
 * ## Animation
 * Both tweens are 0.22 s easeOutCubic: `tween4 = EasingPresets.getTweenByFloat(0)` (the check reveal)
 * and `tween5 = EasingPresets.getTweenByFloatFloat2(0, 0.22)` (the card colour) — source lines 17-18.
 */
class SmallButton(initialState: Boolean) : LabelWidget() {

    /** Current logical state (source `flag4`, `isFlag4`). */
    var state: Boolean = initialState
        private set

    /** Click callback fired when toggled by the user (source `runnable`). */
    var onClickAction: (() -> Unit)? = null

    /** Card colour tween — 0.22 s easeOutCubic (source `tween5`). */
    private val colorTween: Tween = EasingPresets.cubicTween(if (initialState) 1f else 0f, TWEEN_SECONDS)

    /** Check reveal tween — 0.22 s easeOutCubic (source `tween4`). */
    private val checkTween: Tween = EasingPresets.cubicTweenBase(if (initialState) 1f else 0f)

    init {
        width = SIZE
        height = SIZE
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val f = colorTween.get()
        val fill = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.primary().argb, f, SystemDlcTheme.background().argb))
        val borderColor = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.primary().argb, f, SystemDlcTheme.border().argb))

        SystemDraw.panel(
            ctx,
            x,
            y,
            width,
            height,
            radius = RADIUS,
            fill = SystemDraw.withAlpha(fill, alpha),
            border = SystemDraw.withAlpha(borderColor, alpha),
            borderWidth = BORDER_WIDTH,
            shadow = SystemDlcTheme.SHADOW,
            shadowBlur = SHADOW_BLUR,
        )

        val f1 = checkTween.get()
        if (f1 > CHECK_EPSILON) {
            val boxX = x + (width - CHECK_W) / 2f
            val boxY = y + (height - CHECK_H) / 2f
            // fit the 7x6 glyph box into a centred square of its shorter side
            val s = minOf(CHECK_W, CHECK_H)
            SystemDraw.icon(
                ctx,
                SystemIcon.SUCCESS_CHECKBOX,
                boxX + (CHECK_W - s) / 2f,
                boxY + (CHECK_H - s) / 2f,
                s,
                SystemDraw.withAlpha(SystemDlcTheme.surface(), alpha * f1),
            )
        }
    }

    override fun onClick(): Boolean {
        setState(!state)
        onClickAction?.invoke()
        return true
    }

    /** Set the state and animate the card/check to it (source `setBoolean`, :89-95). */
    fun setState(on: Boolean) {
        if (state != on) {
            state = on
            colorTween.to(if (on) 1f else 0f)
            checkTween.to(if (on) 1f else 0f)
        }
    }

    private companion object {
        const val SIZE = 16f
        const val RADIUS = 6f
        const val BORDER_WIDTH = 1f
        const val SHADOW_BLUR = 1f
        const val CHECK_W = 7f
        const val CHECK_H = 6f
        const val CHECK_EPSILON = 0.001f
        const val TWEEN_SECONDS = 0.22f
    }
}
