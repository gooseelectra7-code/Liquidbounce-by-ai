/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/util/Interpolation.java`.
 *
 * Two independent concerns:
 *  1. A stateless, half-life exponential smoother ([towards]) that eases a stored `current`
 *     toward a `target` frame-rate-independently. Half-life semantics: after `halfLife`
 *     seconds the remaining distance is halved (`2^(-dt/halfLife)`). A negative `current`
 *     is the "uninitialized" sentinel and snaps straight to the target (pair it with
 *     [UNINITIALIZED] = -1).
 *  2. A per-instance frame clock ([deltaSeconds]) that returns the wall-clock seconds since
 *     the last call, clamped to [0, 0.1]; the first call returns `1/60`. Time comes from
 *     `System.nanoTime()`. No render dependencies.
 *
 * Source mapping (verified against Interpolation.java):
 *  - getFloatByFloatFloatFloatFloat(target,dt,current,halfLife) -> [towards]  (:10-12)
 *  - getFloatByFloatFloatFloatFloat2(target,current,dt,halfLife) -> [smooth]  (:34-46)
 *  - getFloat() = -1 sentinel  (:14-16) ; setTime()/getFloat2() = the frame clock (:18-32)
 *  - constants: value=1/60 default dt, value2=0.1 clamp, value3=-1 sentinel,
 *    value4 = -ln2  (:4-7)
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import kotlin.math.abs
import kotlin.math.exp

/**
 * Per-instance frame-delta clock plus static half-life smoothing helpers. See file header.
 */
class Interpolation {

    private var time: Long = -1L

    /** Reset the frame clock so the next [deltaSeconds] returns the default `1/60`. */
    fun resetClock() {
        time = -1L
    }

    /**
     * Wall-clock seconds since the previous call, clamped to `[0, 0.1]`. The first call
     * (and the first after [resetClock]) returns `1/60`. Source `getFloat2` (:22-32).
     */
    fun deltaSeconds(): Float {
        val now = System.nanoTime()
        return if (time < 0L) {
            time = now
            DEFAULT_DELTA
        } else {
            val f = ((now - time) / NANOS_PER_SECOND).toFloat()
            time = now
            if (f < 0f) 0f else f.coerceAtMost(MAX_DELTA)
        }
    }

    // --- machine-recovered source-name aliases ---
    fun setTime() = resetClock()
    fun getFloat2(): Float = deltaSeconds()

    companion object {
        /** `1/60` — the first-frame default delta (source `value`). */
        const val DEFAULT_DELTA = 0.016666668f
        /** `0.1` — the maximum reported frame delta (source `value2`). */
        const val MAX_DELTA = 0.1f
        /** `-1` — the "no previous value" sentinel for [towards]/`current` (source `value3`). */
        const val UNINITIALIZED = -1.0f

        private const val NEG_LN2 = -0.6931471805599453
        private const val NANOS_PER_SECOND = 1.0E9

        /**
         * Frame-rate-independent smoothing of [current] toward [target].
         * Returns [target] unchanged if [current] < 0 (the uninitialized sentinel).
         * Otherwise `target - (target-current) * 2^(-dt/halfLife)`.
         * Source `getFloatByFloatFloatFloatFloat` (:10-12).
         */
        @JvmStatic
        fun towards(target: Float, dt: Float, current: Float, halfLife: Float): Float =
            if (current < 0f) target else smooth(target, current, dt, halfLife)

        /**
         * `target - (target-current) * 2^(-dt/halfLife)` (== `2^x = e^(x*ln2)`).
         * Returns [current] when [halfLife] <= 0 or the two are already within `0.01`.
         * Source `getFloatByFloatFloatFloatFloat2` (:34-46).
         */
        @JvmStatic
        fun smooth(target: Float, current: Float, dt: Float, halfLife: Float): Float {
            if (halfLife <= 0f) {
                return target
            }
            val f = target - current
            if (abs(f) < 0.01f) {
                return target
            }
            val decay = exp(NEG_LN2 * dt / halfLife).toFloat()
            return target - f * decay
        }

        /** The uninitialized-current sentinel, `-1`. Source `getFloat` (:14-16). */
        @JvmStatic
        fun sentinel(): Float = UNINITIALIZED

        // --- machine-recovered source-name aliases ---
        @JvmStatic
        fun getFloatByFloatFloatFloatFloat(target: Float, dt: Float, current: Float, halfLife: Float): Float =
            towards(target, dt, current, halfLife)

        @JvmStatic
        fun getFloatByFloatFloatFloatFloat2(target: Float, current: Float, dt: Float, halfLife: Float): Float =
            smooth(target, current, dt, halfLife)

        @JvmStatic
        fun getFloat(): Float = UNINITIALIZED
    }
}
