/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/util/Animation.java`.
 *
 * A RATE-BASED progress integrator, distinct from the wall-clock [Tween]. The caller
 * feeds a per-frame delta (seconds) to [integrate]; progress moves toward 1 while
 * [forward] is true and toward 0 otherwise, at `rate` units/second. So `Animation(5f)`
 * completes a full 0->1 sweep in `1/5 s = 200 ms` (verified: sum(dt)*rate = 1 =>
 * time = 1/rate). [eased] applies the easing curve to `clamp(progress,0,1)`.
 *
 * Optional secondary "value track": a second scalar ([valueTarget]) that [integrate]
 * exponentially chases (enabled via [enableValueTrack]); used for values that must slew
 * independently of the open/close progress. A [delay] budget (seconds) can be set on a
 * direction change so the integrator idles before it starts moving. Time is the caller's
 * dt in seconds; no render dependencies.
 *
 * Source-field mapping (verified against Animation.java):
 *  - value4 = rate          (Animation.java:13)   | value5 = progress (Animation.java:14)
 *  - flag   = forward       (Animation.java:15)   | value6 = delay    (Animation.java:16)
 *  - function2 = easing     (Animation.java:17, default easeOutCubic)
 *  - flag2  = trackValue    (Animation.java:18)   | flag3  = valueInit (Animation.java:19)
 *  - value7 = valueCurrent  (Animation.java:20)   | value8 = valueTarget (Animation.java:21)
 *  - constants value=14 (VALUE_TRACK_RATE), value2=0.001 (IDLE_EPSILON),
 *    value3=0.05 (VALUE_SNAP_EPSILON)  (Animation.java:6-8)
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import kotlin.math.abs

/**
 * Rate-based animation integrator. See file header for semantics.
 *
 * @param rate progress units per second (a full 0->1 sweep takes `1/rate` seconds)
 */
class Animation(val rate: Float) {

    /** Raw, un-eased progress in [0,1] (source `value5`). */
    var progress: Float = 0f
        private set

    /** Direction/target state: true animates toward 1, false toward 0 (source `flag`). */
    var forward: Boolean = false
        private set

    /** Remaining delay budget in seconds before progress starts moving (source `value6`). */
    private var delay: Float = 0f

    /** Easing curve applied by [eased]; default easeOutCubic (source `function2`). */
    var curve: (Float) -> Float = Easings::easeOutCubic

    private var trackValue: Boolean = false
    private var valueInit: Boolean = false

    /** Current interpolated secondary value (source `value7`). */
    var valueCurrent: Float = 0f
        private set

    /** Target secondary value chased by the value track (source `value8`). */
    var valueTarget: Float = 0f
        private set

    /**
     * Integrate by [dt] seconds. Consumes the [delay] budget first, then moves [progress]
     * toward 1 (if [forward]) or 0 by `dt*rate`, clamped to [0,1]. Advances the secondary
     * value track when enabled. Source `setFloat2` (Animation.java:46-52).
     */
    fun integrate(dt: Float) {
        val f = consumeDelay(dt)
        progress = if (forward) {
            (progress + f * rate).coerceAtMost(1f)
        } else {
            (progress - f * rate).coerceAtLeast(0f)
        }
        if (trackValue && valueInit) {
            updateValueTrack(dt)
        }
    }

    /** Eased progress: `curve(clamp(progress,0,1))`. Source `getFloat` (Animation.java:126-128). */
    fun eased(): Float = curve(progress.coerceIn(0f, 1f))

    /** Snap progress to 0 and stop (target = closed). Source `update` (Animation.java:41-44). */
    fun reset() {
        progress = 0f
        forward = false
    }

    /** Snap progress to 1 and set forward (target = open). Source `update2` (Animation.java:72-75). */
    fun setOpen() {
        progress = 1f
        forward = true
    }

    /** True once the animation is fully closed and idle (`!forward && progress<=0.001`). Source `check`. */
    fun isIdle(): Boolean = !forward && progress <= IDLE_EPSILON

    /**
     * Set the direction with no delay. Source `setBoolean` (Animation.java:109-114).
     * Only resets the delay budget when the direction actually changes.
     */
    fun setDirection(enabled: Boolean) {
        if (forward != enabled) {
            forward = enabled
            delay = 0f
        }
    }

    /**
     * Set the direction and idle for [delaySeconds] before progress moves.
     * Source `onFloatBoolean` (Animation.java:102-107).
     */
    fun setDirection(delaySeconds: Float, enabled: Boolean) {
        if (forward != enabled) {
            forward = enabled
            delay = delaySeconds.coerceAtLeast(0f)
        }
    }

    /** Set both current and target of the value track immediately. Source `setFloat`. */
    fun setValueImmediate(value: Float) {
        valueCurrent = value
        valueTarget = value
        valueInit = true
    }

    /**
     * Set only the target of the value track; initializes current to the same value on the
     * first ever call. Source `setFloat4` (Animation.java:94-100). (Named `setTrackTarget`
     * rather than `setValueTarget` to avoid a JVM clash with the [valueTarget] property.)
     */
    fun setTrackTarget(value: Float) {
        valueTarget = value
        if (!valueInit) {
            valueCurrent = value
            valueInit = true
        }
    }

    /** Enable the secondary value track and return this for chaining. Source `getAnimation`. */
    fun enableValueTrack(): Animation {
        trackValue = true
        return this
    }

    /** Set the easing curve and return this for chaining. Source `getAnimationByFunction`. */
    fun easing(curve: (Float) -> Float): Animation {
        this.curve = curve
        return this
    }

    /**
     * Exponentially chase [valueTarget] with [valueCurrent]; snaps when within
     * [VALUE_SNAP_EPSILON]. Source `setFloat3` (Animation.java:62-70).
     */
    private fun updateValueTrack(dt: Float) {
        val f = valueTarget - valueCurrent
        if (abs(f) < VALUE_SNAP_EPSILON) {
            valueCurrent = valueTarget
        } else {
            val f1 = (dt * VALUE_TRACK_RATE).coerceAtMost(1f)
            valueCurrent += f * f1
        }
    }

    /**
     * Subtract [dt] from the [delay] budget and return the remaining dt that should drive
     * progress this frame. Source `getFloatByFloat` (Animation.java:81-92).
     */
    private fun consumeDelay(dt: Float): Float {
        return when {
            delay <= 0f -> dt
            dt >= delay -> {
                val f = dt - delay
                delay = 0f
                f
            }
            else -> {
                delay -= dt
                0f
            }
        }
    }

    // --- machine-recovered source-name aliases ---
    fun isFlag(): Boolean = forward
    fun getValue5(): Float = progress
    fun getValue7(): Float = valueCurrent
    fun getValue8(): Float = valueTarget
    fun setFloat2(dt: Float) = integrate(dt)
    fun getFloat(): Float = eased()
    fun update() = reset()
    fun update2() = setOpen()
    fun check(): Boolean = isIdle()
    fun setBoolean(enabled: Boolean) = setDirection(enabled)
    fun onFloatBoolean(delaySeconds: Float, enabled: Boolean) = setDirection(delaySeconds, enabled)
    fun setFloat(value: Float) = setValueImmediate(value)
    fun setFloat4(value: Float) = setTrackTarget(value)
    fun getAnimation(): Animation = enableValueTrack()
    fun getAnimationByFunction(curve: (Float) -> Float): Animation = easing(curve)

    private companion object {
        const val VALUE_TRACK_RATE = 14.0f
        const val IDLE_EPSILON = 0.001f
        const val VALUE_SNAP_EPSILON = 0.05f
    }
}
