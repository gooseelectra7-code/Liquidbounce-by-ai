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
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.ColorMath
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Stateless keybind-tile draw helpers — ported from `client/gui/widget/PanelPainter.java` (the
 * SwapBinds HUD key tile). Every method re-expresses a scrambled `ShapeShader` call through
 * [SystemDraw]; the class holds no state.
 *
 * ## Scrambled-signature decode
 *  - `getIntByInt(count)` (:18-29) -> [panelFill]: `count` is the incoming accent colour, blended
 *    toward opaque background at 0.78, then — only if too close to the background
 *    (`rgbDistance < 0.1`) — nudged toward foreground at 0.12 for readable contrast.
 *  - `onFloatFloatFloatMatrix4fFloat(value, value2, value3, m, value4)` (:31-50) -> [drawToggle]:
 *    decoded from the identical geometry to `ToggleButton` — `value = y`, `value2 = alpha`,
 *    `value3 = x`, `value4 = 0..1 factor`. A fixed `28 x 16` pill (radius 8) + a `12 x 12` knob
 *    (radius 6) at `x + 2 + factor * 12`, `y + 2`.
 *  - `onFloatMatrix4fBooleanFloatFloatFloatTweenFloatFloat(...)` (:52-74) -> [drawBindChip]: a small
 *    rounded chip with the `0x1A000000` shadow (blur 3, `f5 = 3`) whose fill lerps
 *    `lerp(surface, elevated, 0.85) -> surface` by a caller-owned on/off [Tween].
 */
object PanelPainter {

    /**
     * Contrast-safe tile fill for an accent [accentArgb] (source `getIntByInt`, PanelPainter.java:18-29):
     * blend the accent toward the opaque background by 0.78; if the result lands within an RMS RGB
     * distance of 0.1 of the background, blend it toward the opaque foreground by 0.12. Returns an
     * opaque ARGB int.
     */
    fun panelFill(accentArgb: Int): Int {
        val bg = OPAQUE or (SystemDlcTheme.background().argb and RGB_MASK)
        val fg = OPAQUE or (SystemDlcTheme.foreground().argb and RGB_MASK)
        var k = AnimatedInt.lerpArgb(bg, FILL_BLEND, accentArgb)
        if (ColorMath.rgbDistance(bg, k) < CONTRAST_MIN) {
            k = AnimatedInt.lerpArgb(fg, CONTRAST_BLEND, k)
        }
        return k
    }

    /** [panelFill] on a [Color4b]. */
    fun panelFill(accent: Color4b): Color4b = Color4b(panelFill(accent.argb))

    /**
     * Draw a stateless toggle pill at ([x], [y]) with an already-resolved 0..1 [factor], faded by
     * [alpha] (source `onFloatFloatFloatMatrix4fFloat`, PanelPainter.java:31-50). Track colour lerps
     * **border -> primary**, knob colour lerps **foreground -> background**, both by [factor].
     */
    fun drawToggle(ctx: GuiGraphicsExtractor, x: Float, y: Float, alpha: Float, factor: Float) {
        val track = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.primary().argb, factor, SystemDlcTheme.border().argb))
        val knob = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.background().argb, factor, SystemDlcTheme.foreground().argb))

        SystemDraw.pill(ctx, x, y, TRACK_WIDTH, TRACK_HEIGHT, SystemDraw.withAlpha(track, alpha))

        val knobX = x + KNOB_INSET + factor * (TRACK_WIDTH - KNOB_SIZE - KNOB_INSET * 2f)
        val knobY = y + (TRACK_HEIGHT - KNOB_SIZE) / 2f
        SystemDraw.panel(ctx, knobX, knobY, KNOB_SIZE, KNOB_SIZE, KNOB_SIZE / 2f, SystemDraw.withAlpha(knob, alpha))
    }

    /**
     * Draw a bind chip at ([x], [y]) sized [width] x [height], radius [radius], faded by [alpha]
     * (source `onFloatMatrix4fBooleanFloatFloatFloatTweenFloatFloat`, PanelPainter.java:52-74). The
     * caller owns [tween] (the on/off highlight state); this advances it toward [active]. Fill lerps
     * `lerp(surface, elevated, 0.85)` toward [SystemDlcTheme.surface] as the chip activates. Carries
     * the source's `0x1A000000` shadow at blur 3.
     */
    @Suppress("LongParameterList")
    fun drawBindChip(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        alpha: Float,
        active: Boolean,
        tween: Tween,
    ) {
        tween.to(if (active) 1f else 0f)
        val f = tween.get()
        val base = AnimatedInt.lerpArgb(SystemDlcTheme.elevated().argb, BIND_BASE_BLEND, SystemDlcTheme.surface().argb)
        val fill = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.surface().argb, f, base))

        SystemDraw.panel(
            ctx,
            x,
            y,
            width,
            height,
            radius = radius,
            fill = SystemDraw.withAlpha(fill, alpha),
            shadow = SystemDlcTheme.SHADOW,
            shadowBlur = BIND_SHADOW_BLUR,
        )
    }

    // --- geometry (fixed toggle dimensions, PanelPainter.java:44-49) ---
    private const val TRACK_WIDTH = 28f
    private const val TRACK_HEIGHT = 16f
    private const val KNOB_SIZE = 12f
    private const val KNOB_INSET = 2f

    // --- panelFill constants (PanelPainter.java:11-13/21-25) ---
    private const val OPAQUE = -0x1000000
    private const val RGB_MASK = 0xFFFFFF
    private const val FILL_BLEND = 0.78f
    private const val CONTRAST_MIN = 0.1f
    private const val CONTRAST_BLEND = 0.12f

    // --- bind-chip constants (PanelPainter.java:59/66) ---
    private const val BIND_BASE_BLEND = 0.85f
    private const val BIND_SHADOW_BLUR = 3f
}
