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

package net.ccbluex.liquidbounce.render.gui.systemdlc

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.drawHawenRoundedRect
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawRoundedRectShadow
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.withPush
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The single drawing vocabulary every ported SystemDLC ClickGUI/HUD widget draws through.
 *
 * It collapses the source client's three shader call families into named primitives backed by the
 * fork's batched 2D render states, so widget code never touches [GuiGraphicsExtractor] element states
 * directly:
 *
 * - `render/ShapeShader` (rounded rect + border + shadow, `ShapeShader.onFloatIntInt…`; the SwapBindsHud
 *   tile is "radius 12, `Theme.background()` fill, 1 px border, shadow `0x1A000000`, blur 12" per the
 *   scope report §1d) -> [panel] / [panelPerCorner] / [pill], over [drawRoundedRect]
 *   (Render2D.kt:210), [drawHawenRoundedRect] (Render2D.kt:431) and [drawRoundedRectShadow]
 *   (Render2D.kt:272).
 * - `render/TextShader` (six MSDF atlases keyed `a/aa/b/bb/c/cc`, scope report cluster-2 shader table)
 *   -> [text] / [textWidth] / [lineHeight], over [SystemFont] (SystemFont.kt:133/105/117).
 * - `render/SvgShader` (`CategoryType` glyph paths) -> [icon], over [drawIcon] (SystemIcon.kt:130).
 *
 * Plus a [scissor] clip helper (the source client's `render/ScissorStack`) and the [lerp] / [withAlpha]
 * colour helpers the animated widgets need.
 *
 * Every entry point forwards to a verified primitive; nothing here rasterizes on its own. Like every
 * primitive it wraps, this MUST be called on the render thread.
 */
object SystemDraw {

    /**
     * The default body font. The scope report names weight `B` as the source client's primary body
     * weight (Latin `b` / Cyrillic `bb`); [SystemFont] falls back per-glyph across the script sibling,
     * so a caller only overrides [family] when it deliberately wants a different weight (A/AA lighter,
     * C/CC heavier).
     */
    val DEFAULT_FAMILY: SystemFont.Family = SystemFont.Family.B

    // ------------------------------------------------------------------ shapes

    /**
     * The workhorse rounded panel: an optional drop [shadow] drawn FIRST (so the opaque body covers the
     * silhouette it overlaps — the order [drawRoundedRectShadow] documents), then the [fill] body with an
     * optional [border] outline. Maps the source `ShapeShader` rounded-rect+shadow+border call in one go.
     *
     * @param radius uniform corner radius, in GUI units.
     * @param shadow shadow tint, or `null` for no shadow. [shadowBlur] is the fade distance past the box
     *   edge; [shadowOffsetX]/[shadowOffsetY] shift the shadow.
     */
    @Suppress("LongParameterList")
    fun panel(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        fill: Color4b,
        border: Color4b? = null,
        borderWidth: Float = 1f,
        shadow: Color4b? = null,
        shadowBlur: Float = 12f,
        shadowOffsetX: Float = 0f,
        shadowOffsetY: Float = 0f,
    ) {
        if (shadow != null) {
            ctx.drawRoundedRectShadow(x, y, w, h, radius, shadow, shadowBlur, shadowOffsetX, shadowOffsetY)
        }
        ctx.drawRoundedRect(x, y, x + w, y + h, radius, fill, border, borderWidth)
    }

    /**
     * Per-corner-radius variant of [panel] (e.g. a TopBar whose top two corners are rounded and bottom
     * two are square), over [drawHawenRoundedRect]. That primitive is fill-only — it has no outline path,
     * so this variant draws no border. The optional [shadow] uses a single uniform [shadowRadius]
     * (default = the largest of the four corner radii), which is visually indistinguishable behind the
     * opaque body.
     */
    @Suppress("LongParameterList")
    fun panelPerCorner(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radiusTopLeft: Float,
        radiusTopRight: Float,
        radiusBottomRight: Float,
        radiusBottomLeft: Float,
        fill: Color4b,
        shadow: Color4b? = null,
        shadowRadius: Float = maxOf(radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft),
        shadowBlur: Float = 12f,
        shadowOffsetX: Float = 0f,
        shadowOffsetY: Float = 0f,
    ) {
        if (shadow != null) {
            ctx.drawRoundedRectShadow(x, y, w, h, shadowRadius, shadow, shadowBlur, shadowOffsetX, shadowOffsetY)
        }
        ctx.drawHawenRoundedRect(
            x,
            y,
            x + w,
            y + h,
            radiusRT = radiusTopRight,
            radiusRB = radiusBottomRight,
            radiusLT = radiusTopLeft,
            radiusLB = radiusBottomLeft,
            color = fill,
        )
    }

    /**
     * A fully-rounded chip: [panel] with `radius = h / 2` (a stadium/pill shape). For bind chips and
     * toggle tracks. Same shadow/border options as [panel].
     */
    @Suppress("LongParameterList")
    fun pill(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fill: Color4b,
        border: Color4b? = null,
        borderWidth: Float = 1f,
        shadow: Color4b? = null,
        shadowBlur: Float = 12f,
        shadowOffsetX: Float = 0f,
        shadowOffsetY: Float = 0f,
    ) = panel(
        ctx,
        x,
        y,
        w,
        h,
        h / 2f,
        fill,
        border,
        borderWidth,
        shadow,
        shadowBlur,
        shadowOffsetX,
        shadowOffsetY,
    )

    // ------------------------------------------------------------------- text

    /**
     * Draws [text] at pixel size [size] in [color], with its top-left at ([x], [y]) for [TextAlign.LEFT].
     * [TextAlign.CENTER] centres the string on [x]; [TextAlign.RIGHT] right-aligns its end to [x] — both
     * by offsetting with [textWidth]. [y] is always the top of the line box (baseline handled by
     * [SystemFont]).
     *
     * @return the pen X after the last glyph (`drawX + advanced width`).
     */
    fun text(
        ctx: GuiGraphicsExtractor,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Color4b,
        family: SystemFont.Family = DEFAULT_FAMILY,
        align: TextAlign = TextAlign.LEFT,
    ): Float {
        val drawX = when (align) {
            TextAlign.LEFT -> x
            TextAlign.CENTER -> x - SystemFont.width(family, text, size) / 2f
            TextAlign.RIGHT -> x - SystemFont.width(family, text, size)
        }
        return SystemFont.draw(ctx, family, text, drawX, y, size, color)
    }

    /** Advanced pixel width of [text] at [size] in [family]. */
    fun textWidth(text: String, size: Float, family: SystemFont.Family = DEFAULT_FAMILY): Float =
        SystemFont.width(family, text, size)

    /** Height of one text line at [size] in [family]. */
    fun lineHeight(size: Float, family: SystemFont.Family = DEFAULT_FAMILY): Float =
        SystemFont.lineHeight(family, size)

    // ------------------------------------------------------------------ icons

    /** Draws [icon] tinted [color] into the square box `[x, y, x + size, y + size]`. See [drawIcon]. */
    fun icon(
        ctx: GuiGraphicsExtractor,
        icon: SystemIcon,
        x: Float,
        y: Float,
        size: Float,
        color: Color4b,
    ) = drawIcon(ctx, icon, x, y, size, color)

    // ----------------------------------------------------------------- scissor

    /**
     * Runs [block] with a scissor clip pushed over the rect ([x], [y], [w], [h]) and pops it afterwards
     * (even if [block] throws). The rect is transformed by the current pose and intersected with any
     * enclosing scissor via [getBoundsXYWH], then pushed with the [withPush] scissor helper
     * (`getBounds` + `scissorStack.withPush`).
     */
    inline fun scissor(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        block: () -> Unit,
    ) {
        ctx.scissorStack.withPush(ctx.getBoundsXYWH(x, y, w, h)) {
            block()
        }
    }

    // ------------------------------------------------------------------ colour

    /**
     * Per-channel ARGB interpolation between [a] and [b] at [t] (`0` -> [a], `1` -> [b]). Reuses
     * [SystemDlcTheme.lerpColor] so animated fills match the theme's own cross-fade maths exactly.
     */
    fun lerp(a: Color4b, b: Color4b, t: Float): Color4b = SystemDlcTheme.lerpColor(a, b, t)

    /**
     * Scales [color]'s alpha channel by [alpha] (clamped `0..1`), keeping RGB. Reuses
     * [SystemDlcTheme.withAlphaFactor] (the source client's per-element opacity multiply).
     */
    fun withAlpha(color: Color4b, alpha: Float): Color4b = SystemDlcTheme.withAlphaFactor(color, alpha)
}

/** Horizontal text anchor for [SystemDraw.text]. */
enum class TextAlign { LEFT, CENTER, RIGHT }
