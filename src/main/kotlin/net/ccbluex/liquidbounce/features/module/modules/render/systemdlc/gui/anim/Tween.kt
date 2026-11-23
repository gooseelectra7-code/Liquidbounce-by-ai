/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/data/Tween.java`.
 *
 * A wall-clock, duration-based scalar tween. On [to] it captures the currently
 * displayed value as the new start, stores the new target, and stamps `System.nanoTime()`;
 * [get] then eases from start->target over [duration] seconds using the configured curve
 * (default easeOutCubic). Time is real time from `System.nanoTime()` (this runs in the
 * live client, not a workflow), so a tween advances on its own between frames without a
 * driving dt. No render dependencies.
 *
 * Source-field mapping (verified against Tween.java):
 *  - value2 = start        (Tween.java:8)
 *  - value3 = current      (Tween.java:9, the last displayed value)
 *  - value4 = target       (Tween.java:10)
 *  - value5 = duration (s)  (Tween.java:11)
 *  - function = curve       (Tween.java:12, default Easings::getFloatByFloat3)
 *  - time = start nanos     (Tween.java:13)
 *  - flag = animating       (Tween.java:14)
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

/**
 * Wall-clock scalar tween. Construct with a start value and a duration in seconds,
 * optionally set the curve via [easing]/[getTweenByFunction], then [to]/[setFloat2] to
 * animate and [get]/[getFloat] each frame to read.
 *
 * @param from initial value (start == current == target on construction)
 * @param durationSeconds animation duration in seconds
 */
class Tween(from: Float, durationSeconds: Float) {

    /** Start value the current animation eases from. */
    var start: Float = from
        private set

    /** Last displayed value (updated on every [get]; peeked by [current]). */
    var current: Float = from
        private set

    /** Target value the current animation eases to. */
    var target: Float = from
        private set

    /** Animation duration in seconds. */
    var duration: Float = durationSeconds

    /** Easing curve applied to normalized progress; default easeOutCubic. */
    var curve: (Float) -> Float = Easings::easeOutCubic

    private var startNanos: Long = System.nanoTime()
    private var animating: Boolean = false

    /** Snap start/current/target to [value] and stop animating (source `setFloat`). */
    fun snap(value: Float) {
        start = value
        current = value
        target = value
        animating = false
    }

    /**
     * Animate toward [value]. No-op if [value] already equals the current target.
     * Captures the displayed value as the new start (source `setFloat2`).
     */
    fun to(value: Float) {
        if (target != value) {
            start = current
            target = value
            startNanos = System.nanoTime()
            animating = true
        }
    }

    /**
     * Animate toward [value] but only begin after [delaySeconds] (the start timestamp is
     * pushed into the future, so [get] holds at the start until the delay elapses).
     * Source `onFloatFloat` (Tween.java:79-86).
     */
    fun toAfter(delaySeconds: Float, value: Float) {
        if (target != value) {
            start = current
            target = value
            startNanos = System.nanoTime() + (delaySeconds * NANOS_PER_SECOND).toLong()
            animating = true
        }
    }

    /** Set the easing curve and return this for chaining (source `getTweenByFunction`). */
    fun easing(curve: (Float) -> Float): Tween {
        this.curve = curve
        return this
    }

    /**
     * Advance to the current wall-clock time, update [current], and return it.
     * While the start timestamp is in the future (a [toAfter] delay), progress is <= 0 and
     * the value holds at [start]. Source `getFloat` (Tween.java:57-77).
     */
    fun get(): Float {
        if (animating && duration > 0f) {
            val elapsed = (System.nanoTime() - startNanos) / NANOS_PER_SECOND
            val t = elapsed.toFloat() / duration
            when {
                t <= 0f -> current = start
                t >= 1f -> {
                    current = target
                    animating = false
                }
                else -> {
                    val eased = curve(t)
                    current = start + (target - start) * eased
                }
            }
            return current
        }
        current = target
        animating = false
        return current
    }

    // --- machine-recovered source-name aliases ---
    fun setFloat(value: Float) = snap(value)
    fun setFloat2(value: Float) = to(value)
    fun getValue3(): Float = current
    fun getValue4(): Float = target
    fun setValue5(value: Float) { duration = value }
    fun getTweenByFunction(function: (Float) -> Float): Tween = easing(function)
    fun getFloat(): Float = get()
    fun onFloatFloat(delaySeconds: Float, value: Float) = toAfter(delaySeconds, value)

    private companion object {
        const val NANOS_PER_SECOND = 1.0E9
    }
}
