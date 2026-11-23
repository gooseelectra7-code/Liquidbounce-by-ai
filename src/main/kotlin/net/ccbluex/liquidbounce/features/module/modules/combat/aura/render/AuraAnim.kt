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
package net.ccbluex.liquidbounce.features.module.modules.combat.aura.render

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A wall-clock tween of a single float, the animation vocabulary shared by the combat auras' world overlays.
 *
 * Modelled on the competitor client's own animated float
 * (`rockstar-client-src/src/main/java/rockstar/client/IiiiIiIii_Class236.java:17-51`): the object is built from
 * `(duration, initial value, easing)`; assigning a NEW [target] records the value shown right now as the start of
 * a fresh tween and stamps the wall clock; reading [value] interpolates `start + (target - start) * ease(elapsed /
 * duration)` and snaps to the target once `elapsed >= duration`.
 *
 * Nothing here is tick- or frame-driven: the value is a pure function of [System.currentTimeMillis], so the
 * animation looks the same at 30 and at 300 fps and costs nothing while it is settled.
 *
 * @param durationMs nominal tween length, before the shared [speed] divisor.
 * @param initial the value the tween starts (and rests) at.
 * @param easing the shape of the interpolation; see [AuraEase].
 */
class AuraAnim(
    private val durationMs: Long,
    initial: Float = 0f,
    private val easing: AuraEase = AuraEase.SPRING,
) {

    companion object {
        /**
         * Global tween-rate divisor shared by every animation of the overlay, written once per frame from
         * `RenderSettings.animationSpeed`. `2.0` makes every tween take half as long, `0.5` twice as long.
         *
         * It is applied at read time rather than baked into [durationMs], so moving the slider is visible on
         * animations that are already running.
         */
        var speed: Float = 1f
            set(value) {
                field = value.coerceIn(MIN_SPEED, MAX_SPEED)
            }

        private const val MIN_SPEED = 0.25f
        private const val MAX_SPEED = 3f
    }

    /** Value the current tween started from, and the value it walks towards. */
    private var origin = initial
    private var goal = initial

    /** Wall clock of the last retarget; `0` means "settled since forever", so a fresh instance reads [goal]. */
    private var startedAt = 0L

    /** Effective length of one tween after the shared [speed] divisor; never zero, so the ratio stays finite. */
    private val scaledDuration: Long
        get() = (durationMs / speed).toLong().coerceAtLeast(1L)

    /**
     * The value the tween is walking towards. Assigning a value that differs from the current target restarts the
     * tween from whatever is on screen right now; assigning the same value again is a no-op, so pushing the same
     * target every frame does not freeze the animation at its first sample.
     */
    var target: Float
        get() = goal
        set(value) {
            if (value == goal) {
                return
            }
            val now = System.currentTimeMillis()
            origin = valueAt(now)
            goal = value
            startedAt = now
        }

    /** The interpolated value for this instant, clamped to [target] once the tween is over. */
    val value: Float
        get() = valueAt(System.currentTimeMillis())

    /** True once the tween reached [target] and nothing is moving any more. */
    val settled: Boolean
        get() = System.currentTimeMillis() - startedAt >= scaledDuration

    /** Drops the running tween and places the value at [value] immediately (no interpolation). */
    fun snap(value: Float) {
        origin = value
        goal = value
        startedAt = 0L
    }

    private fun valueAt(now: Long): Float {
        val duration = scaledDuration
        val elapsed = now - startedAt
        if (elapsed >= duration) {
            return goal
        }
        if (elapsed <= 0L) {
            return origin
        }
        return origin + (goal - origin) * easing.ease(elapsed.toFloat() / duration)
    }
}

/**
 * The easing shapes of the overlay.
 *
 * [SPRING] and [SOFT_SPRING] are the competitor client's own two workhorse curves, taken verbatim from
 * `rockstar-client-src/src/main/java/rockstar/client/IiiiIiiII_Class237.java:7-13`: cubic beziers
 * `(0.45, 1.45, 0.49, 1.15)` and `(0.27, 1.09, 0.49, 1.06)`. Both control points sit above `1.0`, i.e. the curve
 * deliberately overshoots and settles back — that over-unity is why their UI reads as alive instead of linear, and
 * it is what makes a box "arrive" rather than slide to a stop here.
 *
 * This is intentionally NOT an extension of `utils/math/Easing.kt:28`: that enum is a `Tagged` choice list used by
 * other modules' settings, so adding constants there would silently change their option lists.
 */
enum class AuraEase {
    /** No shaping; constant rate. */
    LINEAR,

    /** `1 - (1 - t)^3`: fast start, gentle stop. The default for fades. */
    OUT_CUBIC,

    /** Symmetric cubic acceleration and deceleration; used for cross-fades. */
    IN_OUT_CUBIC,

    /** The competitor's springy bezier `(0.45, 1.45, 0.49, 1.15)` — the strongest overshoot. */
    SPRING,

    /** The competitor's gentler bezier `(0.27, 1.09, 0.49, 1.06)`. */
    SOFT_SPRING,

    /** Closed-form back-out with the classic `1.70158` overshoot constant. */
    OUT_BACK;

    /** Maps a `0..1` progress onto the eased `0..1` (or slightly beyond, for the overshooting shapes). */
    fun ease(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            OUT_CUBIC -> outCubic(t)
            IN_OUT_CUBIC -> inOutCubic(t)
            SPRING -> SPRING_CURVE.solve(t)
            SOFT_SPRING -> SOFT_SPRING_CURVE.solve(t)
            OUT_BACK -> outBack(t)
        }
    }

    private companion object {
        val SPRING_CURVE = UnitBezier(0.45f, 1.45f, 0.49f, 1.15f)
        val SOFT_SPRING_CURVE = UnitBezier(0.27f, 1.09f, 0.49f, 1.06f)

        const val BACK_OVERSHOOT = 1.70158f
        const val HALF = 0.5f

        fun outCubic(t: Float): Float {
            val rest = 1f - t
            return 1f - rest * rest * rest
        }

        fun inOutCubic(t: Float): Float {
            if (t < HALF) {
                return 4f * t * t * t
            }
            val rest = -2f * t + 2f
            return 1f - rest * rest * rest / 2f
        }

        fun outBack(t: Float): Float {
            val u = t - 1f
            return 1f + (BACK_OVERSHOOT + 1f) * u * u * u + BACK_OVERSHOOT * u * u
        }
    }
}

/**
 * A unit cubic-bezier `(0,0) -> (x1,y1) -> (x2,y2) -> (1,1)` evaluated the standard way: Newton-Raphson on the x
 * polynomial to recover the curve parameter for a given time, then the y polynomial at that parameter.
 *
 * Same solver the competitor uses for its bezier easings
 * (`rockstar-client-src/src/main/java/rockstar/client/IiiiIiiII_Class237.java:132-174`): 8 iterations, an absolute
 * error tolerance of `1e-5`, a `1e-6` floor on the slope to avoid dividing by a flat spot, and the parameter
 * clamped into `0..1` after every step. Only the two control points may leave `0..1`, which is what produces the
 * overshoot.
 */
private class UnitBezier(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {

    /** The eased value for time [x]; may exceed `1` when the control points do. */
    fun solve(x: Float): Float = polynomial(parameterFor(x), y1, y2)

    private fun parameterFor(x: Float): Float {
        var t = x
        repeat(NEWTON_STEPS) {
            val error = polynomial(t, x1, x2) - x
            val slope = derivative(t, x1, x2)
            if (abs(error) < TOLERANCE || abs(slope) < SLOPE_EPSILON) {
                return t
            }
            t = (t - error / slope).coerceIn(0f, 1f)
        }
        return t
    }

    /** `3(1-t)^2 t a + 3(1-t) t^2 b + t^3`, the bezier polynomial for one axis with endpoints `0` and `1`. */
    private fun polynomial(t: Float, a: Float, b: Float): Float {
        val rest = 1f - t
        return 3f * rest * rest * t * a + 3f * rest * t * t * b + t * t * t
    }

    private fun derivative(t: Float, a: Float, b: Float): Float =
        3f * ((1f - t) * (1f - 3f * t) * a + (2f * t - 3f * t * t) * b) + 3f * t * t

    private companion object {
        const val NEWTON_STEPS = 8
        const val TOLERANCE = 1e-5f
        const val SLOPE_EPSILON = 1e-6f
    }
}

/**
 * The colour counterpart of [AuraAnim]: a `0..1` tween driving `Color4b.interpolateTo`
 * (`render/engine/type/Color4b.kt:189-213`, per-channel `fma` with every channel clamped to `0..255`), so a
 * setting change or a heat shift cross-fades instead of switching.
 */
class AuraAnimColor(
    durationMs: Long,
    initial: Color4b,
    easing: AuraEase = AuraEase.OUT_CUBIC,
) {

    private val progress = AuraAnim(durationMs, 1f, easing)
    private var origin = initial
    private var goal = initial

    /** The colour being walked towards; assigning a different one restarts the cross-fade from what is drawn. */
    var target: Color4b
        get() = goal
        set(value) {
            if (value == goal) {
                return
            }
            origin = this.value
            goal = value
            progress.snap(0f)
            progress.target = 1f
        }

    /** The cross-faded colour for this instant. */
    val value: Color4b
        get() = origin.interpolateTo(goal, progress.value.toDouble())

    /** True once the cross-fade finished. */
    val settled: Boolean
        get() = progress.settled

    /** Drops the running cross-fade and places the colour at [color] immediately. */
    fun snap(color: Color4b) {
        origin = color
        goal = color
        progress.snap(1f)
    }
}

/**
 * A continuous `0..1` sine of the wall clock: `0.5 + 0.5 * sin(2π * now / period)`. Used for the breathing and
 * pulsing elements, which have no target to tween towards and just have to keep moving.
 */
fun pulse(nowMs: Long, periodMs: Long): Float {
    if (periodMs <= 0L) {
        return 1f
    }
    val phase = (nowMs % periodMs).toDouble() / periodMs
    return 0.5f + 0.5f * sin(2.0 * PI * phase).toFloat()
}
