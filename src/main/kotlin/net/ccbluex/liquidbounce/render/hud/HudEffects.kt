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

package net.ccbluex.liquidbounce.render.hud

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.math.Easing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cheap, CPU-side animated effects for native HUD components — chroma/wave, pulse, fade/slide.
 *
 * Everything is a pure function of wall-clock time (anchored to [System.nanoTime], the same clock as
 * [net.ccbluex.liquidbounce.render.utils.rainbow], so independently-animated elements stay in phase)
 * or of an explicit element start time. No per-pixel shader work — colors/alphas are computed here and
 * fed to the existing primitives ([net.ccbluex.liquidbounce.render.drawRoundedRect], [HudFont] etc.).
 */
object HudEffects {

    /** Normalized phase in `[0,1)` over [periodMs], plus a fractional [offset] (e.g. per-glyph for a wave). */
    fun phase(periodMs: Long, offset: Float = 0f): Float {
        val cycles = (System.nanoTime() / 1_000_000.0) / periodMs.toDouble()
        // Do the wrap in double to keep precision after long uptimes, then fold negatives.
        val f = (cycles + offset.toDouble()) % 1.0
        return ((f + 1.0) % 1.0).toFloat()
    }

    /**
     * Time-cycling chroma color. Give neighbouring elements/glyphs an increasing [offset] to get a
     * travelling rainbow wave (e.g. `offset = indexFraction * spread`).
     */
    @JvmOverloads
    fun chroma(
        offset: Float = 0f,
        periodMs: Long = 10_000,
        saturation: Float = 1f,
        brightness: Float = 1f,
        alpha: Float = 1f,
    ): Color4b = Color4b.ofHSB(phase(periodMs, offset), saturation, brightness, alpha)

    /**
     * Smooth two-color "перелив": flows [colorA] → [colorB] → [colorA] over [periodMs] using a cosine
     * ping-pong (no hard seam). Give neighbouring elements/glyphs an increasing [offset] for a wave,
     * exactly like [chroma] but between two configured colors instead of the full rainbow.
     */
    @JvmOverloads
    fun gradient(
        colorA: Color4b,
        colorB: Color4b,
        offset: Float = 0f,
        periodMs: Long = 4_000,
    ): Color4b {
        val t = 0.5 - 0.5 * cos(phase(periodMs, offset) * 2.0 * PI) // smooth 0..1..0
        return colorA.interpolateTo(colorB, t)
    }

    /** Fractional phase offset for a left-to-right wave across [total] items, spanning [spread] of a cycle. */
    fun waveOffset(index: Int, total: Int, spread: Float = 0.6f): Float =
        if (total > 1) index.toFloat() / (total - 1) * spread else 0f

    /** Smooth sine "breathing" value in `[min,max]` over [periodMs]. Useful for pulsing alpha/brightness. */
    @JvmOverloads
    fun pulse(periodMs: Long = 1_500, min: Float = 0.4f, max: Float = 1f): Float {
        val s = sin(phase(periodMs) * 2.0 * PI) * 0.5 + 0.5 // 0..1
        return (min + (max - min) * s).toFloat()
    }

    /**
     * Eased `[0,1]` progress since [startTime] over [durationMs]. Drives fade/slide/scale on appear.
     * Reuses [Easing.getFactor] so it matches the rest of the client's animation feel.
     */
    @JvmOverloads
    fun appear(startTime: Long, durationMs: Float, easing: Easing = Easing.QUAD_OUT): Float =
        easing.getFactor(startTime, System.currentTimeMillis(), durationMs)

    /** Convenience: a color faded in by [appear] progress. */
    @JvmOverloads
    fun fadeIn(color: Color4b, startTime: Long, durationMs: Float, easing: Easing = Easing.QUAD_OUT): Color4b =
        color.fade(appear(startTime, durationMs, easing))
}
