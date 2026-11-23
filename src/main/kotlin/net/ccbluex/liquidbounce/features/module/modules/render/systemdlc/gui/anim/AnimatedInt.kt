/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/data/AnimatedInt.java`.
 *
 * An ARGB colour tween. It holds a start colour and a target colour and drives a 0->1
 * blend factor with an internal [Tween] (duration in seconds, eased easeOutCubic). Each
 * [set] snapshots the currently displayed colour as the new start, adopts the new target
 * and restarts the factor from 0. Reads come in two flavours mirroring the source: [peek]
 * uses the tween's last stored factor WITHOUT advancing wall-clock time, while [get]
 * advances the tween then reads. Colours are packed ARGB ints; Color4b overloads are
 * provided for convenience. No render dependencies.
 *
 * Source-field mapping (verified against AnimatedInt.java):
 *  - value  = startColor (:7)  | value2 = targetColor (:8)  | tween = factor (:6)
 *  - getInt (peek, tween.getValue3) :17-24 ; getInt2 (advance, tween.getFloat) :57-62
 *  - getIntByIntFloatInt(to,t,from) = per-channel ARGB lerp :35-55
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * ARGB colour tween. See file header.
 *
 * @param argb initial colour (start == target on construction, factor pinned to 1)
 * @param durationSeconds blend duration in seconds
 */
class AnimatedInt(argb: Int, durationSeconds: Float) {

    /** Colour the current blend eases FROM (source `value`). */
    var startColor: Int = argb
        private set

    /** Colour the current blend eases TO (source `value2`). */
    var targetColor: Int = argb
        private set

    private val factor: Tween = Tween(1f, durationSeconds).easing(Easings::easeOutCubic)

    init {
        factor.snap(1f)
    }

    constructor(color: Color4b, durationSeconds: Float) : this(color.argb, durationSeconds)

    /**
     * Current displayed colour using the tween's last stored factor, WITHOUT advancing
     * time. Source `getInt` (:17-24).
     */
    fun peek(): Int = lerpArgb(targetColor, factor.current, startColor)

    /** [peek] as a [Color4b]. */
    fun peekColor(): Color4b = Color4b(peek())

    /**
     * Advance the tween to the current wall-clock time, then read the blended colour.
     * Source `getInt2` (:57-62).
     */
    fun get(): Int = lerpArgb(targetColor, factor.get(), startColor)

    /** [get] as a [Color4b]. */
    fun getColor(): Color4b = Color4b(get())

    /**
     * Animate toward [argb]. No-op if it already equals the current target; otherwise
     * snapshots the displayed colour as the new start and restarts the factor 0->1.
     * Source `setInt` (:26-33).
     */
    fun set(argb: Int) {
        if (targetColor != argb) {
            startColor = peek()
            targetColor = argb
            factor.snap(0f)
            factor.to(1f)
        }
    }

    /** [set] from a [Color4b]. */
    fun set(color: Color4b) = set(color.argb)

    /** Snap start and target to [argb] and pin the factor to 1. Source `setInt2` (:64-68). */
    fun snap(argb: Int) {
        startColor = argb
        targetColor = argb
        factor.snap(1f)
    }

    /** [snap] to a [Color4b]. */
    fun snap(color: Color4b) = snap(color.argb)

    // --- machine-recovered source-name aliases ---
    fun getInt(): Int = peek()
    fun getInt2(): Int = get()
    fun setInt(argb: Int) = set(argb)
    fun setInt2(argb: Int) = snap(argb)

    companion object {
        /**
         * Per-channel ARGB linear interpolation from [from] to [to] by [t].
         * `t <= 0` returns [from]; `t >= 1` returns [to]. Channels are truncated toward
         * zero exactly as the Java `(int)` cast. Source `getIntByIntFloatInt` (:35-55).
         */
        @JvmStatic
        fun lerpArgb(to: Int, t: Float, from: Int): Int {
            if (t <= 0f) return from
            if (t >= 1f) return to
            val fa = from ushr 24 and 0xFF
            val fr = from ushr 16 and 0xFF
            val fg = from ushr 8 and 0xFF
            val fb = from and 0xFF
            val ta = to ushr 24 and 0xFF
            val tr = to ushr 16 and 0xFF
            val tg = to ushr 8 and 0xFF
            val tb = to and 0xFF
            val a = (fa + (ta - fa) * t).toInt()
            val r = (fr + (tr - fr) * t).toInt()
            val g = (fg + (tg - fg) * t).toInt()
            val b = (fb + (tb - fb) * t).toInt()
            return a shl 24 or (r shl 16) or (g shl 8) or b
        }

        /** [lerpArgb] on [Color4b]s. */
        @JvmStatic
        fun lerp(to: Color4b, t: Float, from: Color4b): Color4b =
            Color4b(lerpArgb(to.argb, t, from.argb))

        /** Source-name alias for [lerpArgb]. */
        @JvmStatic
        fun getIntByIntFloatInt(to: Int, t: Float, from: Int): Int = lerpArgb(to, t, from)
    }
}
