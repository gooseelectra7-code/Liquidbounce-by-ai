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

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Easings
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A per-tab animated label that MORPHS when its text changes — ported from
 * `client/gui/widget/TabPainter.java`. On [setString] it keeps the common leading prefix in place,
 * fades the changed OLD suffix out, then fades the NEW suffix in.
 *
 * ## Split + timeline (TabPainter.java)
 * `setString` (:34-49) computes the common-prefix length (`getIntByStringString`, :114-123) and stores
 * `text3` = common prefix, `text4` = old suffix, `text5` = new suffix, then restarts the tween 0->1.
 * During render (:63-96):
 *  - the prefix draws at full [alpha];
 *  - while `f < 1/7` (`value2 = 0.14285715`, :10) the old suffix fades out at `alpha * (1 - f/(1/7))`;
 *  - afterwards the new suffix fades in at `alpha * (f - 1/7)/(6/7)` (`0.85714287`, :81);
 *  - at `f >= 0.999` the morph state collapses (prefix := whole text).
 *
 * ## Animation
 * `tween` is 0.35 s (source ctor default `value = 0.35`, :11/23) easeOutCubic (`getFloatByFloat3`).
 *
 * ## Retarget note
 * The source split the draw by a `ScriptType` (LATIN vs CYRILLIC atlas) and gave each morphing suffix
 * a sub-pixel positional SLIDE (an extra float threaded through the scrambled `TextShader` overloads).
 * The fork font selects the script atlas per-glyph, so [ScriptType] is dropped; and [SystemDraw.text]
 * has no per-glyph slide, so the morph is reproduced as the OPACITY crossfade above (the slide is the
 * one omitted flourish). Timing, the prefix preservation and the 1/7 split point are exact.
 */
class TabPainter(
    /** Morph duration in seconds (source ctor arg, default 0.35, TabPainter.java:11/26). */
    durationSeconds: Float = MORPH_SECONDS,
    /** Label weight; the source's `ScriptType` is subsumed by the fork's per-glyph atlas fallback. */
    private val family: SystemFont.Family = SystemDraw.DEFAULT_FAMILY,
) {

    private var currentText: String = ""
    private var previousText: String = ""
    private var commonPrefix: String = ""
    private var oldSuffix: String = ""
    private var newSuffix: String = ""
    private var hasMorph: Boolean = false

    private val tween: Tween = Tween(1f, durationSeconds).easing(Easings::easeOutCubic)

    /** Current target text (source `getText`, TabPainter.java:154). */
    fun text(): String = currentText

    /** Raw morph progress `0..1` (source `getFloat` = `tween.getValue3`, TabPainter.java:158). */
    fun progress(): Float = tween.current

    /**
     * Animate to [newText], preserving the common prefix (source `setString`, TabPainter.java:34-49).
     * A no-op if unchanged.
     */
    fun setString(newText: String?) {
        val s = newText ?: ""
        if (s == currentText) {
            return
        }
        previousText = currentText
        currentText = s
        val i = commonPrefixLength(currentText, previousText)
        commonPrefix = currentText.substring(0, i)
        oldSuffix = previousText.substring(i)
        newSuffix = currentText.substring(i)
        hasMorph = oldSuffix.isNotEmpty() || newSuffix.isNotEmpty()
        tween.snap(0f)
        tween.to(1f)
    }

    /** Snap to [newText] with no animation (source `setString2`, TabPainter.java:125-133). */
    fun setStringImmediate(newText: String?) {
        currentText = newText ?: ""
        previousText = ""
        commonPrefix = currentText
        oldSuffix = ""
        newSuffix = ""
        hasMorph = false
        tween.snap(1f)
    }

    /**
     * Reveal the whole current text from scratch, optionally after [delaySeconds] (source `setFloat`,
     * TabPainter.java:139-152): the entire string becomes the "new suffix" and fades in.
     */
    fun reveal(delaySeconds: Float = 0f) {
        previousText = ""
        commonPrefix = ""
        oldSuffix = ""
        newSuffix = currentText
        hasMorph = false
        tween.snap(0f)
        if (delaySeconds > 0f) {
            tween.toAfter(delaySeconds, 1f)
        } else {
            tween.to(1f)
        }
    }

    /** Reveal immediately (source `update` = `setFloat(0)`, TabPainter.java:135-137). */
    fun update() = reveal(0f)

    /**
     * Draw the (possibly morphing) label with its top-left at ([x], [y]) in [size]px [color], faded
     * by [alpha] (source `onMatrix4fFloatIntFloatFloatFloat`, TabPainter.java:63-96 — decoded render
     * args `x, color, y, size, alpha`).
     */
    fun render(ctx: GuiGraphicsExtractor, x: Float, y: Float, size: Float, color: Color4b, alpha: Float) {
        val f = tween.get()
        if (alpha <= ALPHA_EPSILON) {
            return
        }

        var prefixWidth = 0f
        if (commonPrefix.isNotEmpty()) {
            SystemDraw.text(ctx, commonPrefix, x, y, size, SystemDraw.withAlpha(color, alpha), family)
            prefixWidth = SystemDraw.textWidth(commonPrefix, size, family)
        }
        val suffixX = x + prefixWidth

        if (hasMorph && f < MORPH_SPLIT) {
            val fOut = f / MORPH_SPLIT
            val outAlpha = alpha * (1f - fOut)
            if (oldSuffix.isNotEmpty() && outAlpha > ALPHA_EPSILON) {
                SystemDraw.text(ctx, oldSuffix, suffixX, y, size, SystemDraw.withAlpha(color, outAlpha), family)
            }
        } else if (newSuffix.isNotEmpty()) {
            val reveal = if (hasMorph) (f - MORPH_SPLIT) / (1f - MORPH_SPLIT) else f
            val inAlpha = alpha * reveal
            if (inAlpha > ALPHA_EPSILON) {
                SystemDraw.text(ctx, newSuffix, suffixX, y, size, SystemDraw.withAlpha(color, inAlpha), family)
            }
        }

        if (f >= MORPH_DONE) {
            hasMorph = false
            previousText = ""
            commonPrefix = currentText
            oldSuffix = ""
            newSuffix = ""
        }
    }

    private companion object {
        /** Default morph duration, seconds (source `value = 0.35`, TabPainter.java:11). */
        const val MORPH_SECONDS = 0.35f

        /** Old-suffix fade-out window (source `value2 = 0.14285715` = 1/7, TabPainter.java:10). */
        const val MORPH_SPLIT = 0.14285715f

        /** Progress at which the morph collapses (source `f >= 0.999`, TabPainter.java:88). */
        const val MORPH_DONE = 0.999f

        const val ALPHA_EPSILON = 0.001f

        /** Length of the common leading prefix of [a] and [b] (source `getIntByStringString`). */
        fun commonPrefixLength(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) {
                i++
            }
            return i
        }
    }
}
