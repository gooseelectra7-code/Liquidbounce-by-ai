/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC easing library (`client/util/Easings.java`).
 *
 * The C0 animation foundation every ClickGUI/HUD widget in the SystemDLC port depends
 * on. Pure math, no render dependencies. Every curve maps a normalized input in [0,1]
 * to an eased output; the input is NOT clamped here (callers clamp) except where the
 * original does. The machine-recovered source names (`getFloatByFloatN`) are preserved
 * as aliases at the bottom so recovered widget code that references a curve by number
 * still resolves; new code should use the clean names.
 *
 * Curve mapping (clean name = source method = formula), verified against source:
 *  - easeInOutQuint = getFloatByFloat  (Easings.java:7-14)
 *  - easeInBack     = getFloatByFloat2 (Easings.java:16-20)
 *  - easeOutCubic   = getFloatByFloat3 (Easings.java:22-25)
 *  - easeInCubic    = getFloatByFloat4 (Easings.java:27-29)
 *  - easeOutBack    = getFloatByFloat5 (Easings.java:31-36)
 *  - easeOutExpo    = getFloatByFloat6 (Easings.java:38-40)
 *  - easeInOutCubic = getFloatByFloat7 (Easings.java:42-49)  <-- the universal GUI curve
 *  - linear         = getFloatByFloat8 (Easings.java:51-53)
 *  - easeOutQuint   = getFloatByFloat9 (Easings.java:55-58)
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import kotlin.math.exp

/**
 * SystemDLC easing curves. See file header for the clean-name <-> source-name mapping.
 * Reference a curve as e.g. `Easings::easeInOutCubic` for [Tween]/[Animation].
 */
object Easings {

    /** Back-overshoot constant used by [easeInBack]/[easeOutBack] (source `1.70158`). */
    const val BACK_C1 = 1.70158f

    /**
     * easeInOutQuint. Source `Easings.getFloatByFloat` (Easings.java:7-14).
     * `x < 0.5 -> 16x^5 ; else 1 - (-2x+2)^5 / 2`.
     */
    fun easeInOutQuint(x: Float): Float =
        if (x < 0.5f) {
            16f * x * x * x * x * x
        } else {
            val f = -2f * x + 2f
            1f - f * f * f * f * f * 0.5f
        }

    /**
     * easeInBack. Source `Easings.getFloatByFloat2` (Easings.java:16-20).
     * `(c1+1)x^3 - c1*x^2`, c1 = 1.70158.
     */
    fun easeInBack(x: Float): Float {
        val f1 = BACK_C1 + 1f
        return f1 * x * x * x - BACK_C1 * x * x
    }

    /**
     * easeOutCubic. Source `Easings.getFloatByFloat3` (Easings.java:22-25).
     * `1 - (1-x)^3`. Default curve for [Tween] and [Animation].
     */
    fun easeOutCubic(x: Float): Float {
        val f = 1f - x
        return 1f - f * f * f
    }

    /**
     * easeInCubic. Source `Easings.getFloatByFloat4` (Easings.java:27-29). `x^3`.
     */
    fun easeInCubic(x: Float): Float = x * x * x

    /**
     * easeOutBack. Source `Easings.getFloatByFloat5` (Easings.java:31-36).
     * `1 + (c1+1)(x-1)^3 + c1(x-1)^2`, c1 = 1.70158.
     */
    fun easeOutBack(x: Float): Float {
        val f1 = BACK_C1 + 1f
        val f2 = x - 1f
        return 1f + f1 * f2 * f2 * f2 + BACK_C1 * f2 * f2
    }

    /**
     * easeOutExpo. Source `Easings.getFloatByFloat6` (Easings.java:38-40).
     * `x >= 1 -> 1 ; else 1 - e^(-10*ln2*x)` (== `1 - 2^(-10x)`). The magic constant
     * `6.931471805599453` is `10 * ln(2)`.
     */
    fun easeOutExpo(x: Float): Float =
        if (x >= 1f) 1f else 1f - exp(-6.931471805599453 * x).toFloat()

    /**
     * easeInOutCubic. Source `Easings.getFloatByFloat7` (Easings.java:42-49).
     * `x < 0.5 -> 4x^3 ; else 1 - (-2x+2)^3 / 2`. The universal SystemDLC GUI curve
     * (matches this fork's native ClickGUI, which also runs every animation on
     * easeInOutCubic).
     */
    fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) {
            4f * x * x * x
        } else {
            val f = -2f * x + 2f
            1f - f * f * f * 0.5f
        }

    /** linear (identity). Source `Easings.getFloatByFloat8` (Easings.java:51-53). */
    fun linear(x: Float): Float = x

    /**
     * easeOutQuint. Source `Easings.getFloatByFloat9` (Easings.java:55-58).
     * `1 - (1-x)^5`.
     */
    fun easeOutQuint(x: Float): Float {
        val f = 1f - x
        return 1f - f * f * f * f * f
    }

    // --- machine-recovered source-name aliases (do not use in new code) ---
    fun getFloatByFloat(x: Float): Float = easeInOutQuint(x)
    fun getFloatByFloat2(x: Float): Float = easeInBack(x)
    fun getFloatByFloat3(x: Float): Float = easeOutCubic(x)
    fun getFloatByFloat4(x: Float): Float = easeInCubic(x)
    fun getFloatByFloat5(x: Float): Float = easeOutBack(x)
    fun getFloatByFloat6(x: Float): Float = easeOutExpo(x)
    fun getFloatByFloat7(x: Float): Float = easeInOutCubic(x)
    fun getFloatByFloat8(x: Float): Float = linear(x)
    fun getFloatByFloat9(x: Float): Float = easeOutQuint(x)
}
