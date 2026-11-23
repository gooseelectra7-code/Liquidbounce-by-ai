/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/util/EasingPresets.java`.
 *
 * Factory helpers that hand widgets a ready-made [Tween] with a specific curve+duration,
 * plus a handful of scalar/transform presets. The recovered method names are preserved
 * (these are referenced by name from recovered widget code) and each carries a clean
 * alias. The only non-pure dependency is joml's `Matrix4f` for the pop-scale transform
 * ([popScaleMatrix]); that is a math type, not a render dependency.
 *
 * Constant mapping (source field -> clean name -> value), verified EasingPresets.java:7-13:
 *  - value  = SHORT_DURATION = 0.15  |  value2 = BASE_DURATION = 0.22
 *  - value3 = DURATION_032   = 0.32  |  value4 = DURATION_028  = 0.28
 *  - value5 = FALLOFF_SCALE  = 16.0  |  value6 = POP_MIN_SCALE = 0.92
 *  - value7 = POW_EXPONENT   = 1.7
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import org.joml.Matrix4f
import kotlin.math.pow

/**
 * SystemDLC tween/scalar presets. See file header for the source-field mapping.
 */
object EasingPresets {

    /** source `value`  — a short reveal duration in seconds. */
    const val SHORT_DURATION = 0.15f
    /** source `value2` — the base UI duration in seconds. */
    const val BASE_DURATION = 0.22f
    /** source `value3`. */
    const val DURATION_032 = 0.32f
    /** source `value4`. */
    const val DURATION_028 = 0.28f
    /** source `value5` — falloff multiplier used by [falloff16]. */
    const val FALLOFF_SCALE = 16.0f
    /** source `value6` — minimum scale of the pop-in ([popScale] floor). */
    const val POP_MIN_SCALE = 0.92f
    /** source `value7` — exponent used by [pow17]. */
    const val POW_EXPONENT = 1.7f

    /**
     * A [Tween] from [from] over [durationSeconds], eased by easeOutExpo.
     * Source `getTweenByFloatFloat` (EasingPresets.java:18-20). Used by [AnimatedColor].
     */
    fun expoTween(from: Float, durationSeconds: Float): Tween =
        Tween(from, durationSeconds).easing(Easings::easeOutExpo)

    /**
     * `16 * (1 - clamp(x,0,1))`. Source `getFloatByFloat` (EasingPresets.java:22-24).
     * A linear falloff (e.g. a shadow/blur radius shrinking as a reveal completes).
     */
    fun falloff16(x: Float): Float = FALLOFF_SCALE * (1f - x.coerceIn(0f, 1f))

    /**
     * `0.92 + 0.08 * clamp(x,0,1)` — a subtle 92%->100% pop scale.
     * Source `getFloatByFloat2` (EasingPresets.java:26-28). The `0.07999998` in the
     * source is the float representation of `1 - 0.92`; kept exact.
     */
    fun popScale(x: Float): Float = POP_MIN_SCALE + 0.07999998f * x.coerceIn(0f, 1f)

    /**
     * A [Tween] from [from] over [durationSeconds], eased by easeOutCubic.
     * Source `getTweenByFloatFloat2` (EasingPresets.java:30-32).
     */
    fun cubicTween(from: Float, durationSeconds: Float): Tween =
        Tween(from, durationSeconds).easing(Easings::easeOutCubic)

    /**
     * A [Tween] from [from] over [BASE_DURATION] (0.22s), eased by easeOutCubic.
     * Source `getTweenByFloat` (EasingPresets.java:34-36).
     */
    fun cubicTweenBase(from: Float): Tween =
        Tween(from, BASE_DURATION).easing(Easings::easeOutCubic)

    /**
     * Linear interpolation `from + (to - from) * t`.
     * Source `getFloatByFloatFloatFloat` (EasingPresets.java:38-40); the source positional
     * order is `(to, t, from)` — see the [getFloatByFloatFloatFloat] alias.
     */
    fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /**
     * A [Tween] from `0.0` over [SHORT_DURATION] (0.15s), eased by easeOutCubic.
     * Source `getTween` (EasingPresets.java:42-44).
     */
    fun cubicTweenShort(): Tween =
        Tween(0f, SHORT_DURATION).easing(Easings::easeOutCubic)

    /**
     * A copy of [matrix] scaled by [popScale] of [t] about the pivot ([pivotX],[pivotY]).
     * Source `getMatrix4fByMatrix4fFloatFloatFloat` (EasingPresets.java:46-49).
     */
    fun popScaleMatrix(matrix: Matrix4f, t: Float, pivotX: Float, pivotY: Float): Matrix4f {
        val s = popScale(t)
        return Matrix4f(matrix)
            .translate(pivotX, pivotY, 0f)
            .scale(s, s, 1f)
            .translate(-pivotX, -pivotY, 0f)
    }

    /**
     * `clamp(x,0,1) ^ 1.7`. Source `getFloatByFloat3` (EasingPresets.java:51-54).
     * NOTE: this is NOT `Easings.getFloatByFloat3` (easeOutCubic) — the recovered names
     * collide across the two source classes.
     */
    fun pow17(x: Float): Float = x.coerceIn(0f, 1f).toDouble().pow(POW_EXPONENT.toDouble()).toFloat()

    // --- machine-recovered source-name aliases ---
    fun getTweenByFloatFloat(from: Float, durationSeconds: Float): Tween = expoTween(from, durationSeconds)
    fun getFloatByFloat(x: Float): Float = falloff16(x)
    fun getFloatByFloat2(x: Float): Float = popScale(x)
    fun getTweenByFloatFloat2(from: Float, durationSeconds: Float): Tween = cubicTween(from, durationSeconds)
    fun getTweenByFloat(from: Float): Tween = cubicTweenBase(from)

    /** Source order `(to, t, from)`; delegates to [lerp]`(from, to, t)`. */
    fun getFloatByFloatFloatFloat(to: Float, t: Float, from: Float): Float = lerp(from, to, t)
    fun getTween(): Tween = cubicTweenShort()
    fun getMatrix4fByMatrix4fFloatFloatFloat(matrix: Matrix4f, t: Float, pivotX: Float, pivotY: Float): Matrix4f =
        popScaleMatrix(matrix, t, pivotX, pivotY)
    fun getFloatByFloat3(x: Float): Float = pow17(x)
}
