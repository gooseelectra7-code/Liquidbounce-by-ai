/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * The C0 frame/time provider for the SystemDLC GUI port — the dependency every widget
 * memoizes on. Reproduces the source `UiContext` frame counter plus a monotonic per-frame
 * time base. Not present as a single class in the recovered source (the frame counter and
 * the per-frame delta clock, `Interpolation.getFloat2`, were separate); collected here as
 * the one shared clock the widget layer reads.
 *
 * CONTRACT
 *  - Time unit: SECONDS. [nowSeconds]/[frameTimeSeconds] are monotonic `Double` seconds
 *    since the clock epoch (process start); [deltaSeconds] is a `Float`.
 *  - [frame] is a monotonically increasing `Long`, bumped exactly once per [beginFrame].
 *    It is the memoization key: cache a per-frame computed value against it and recompute
 *    only when it changes. Stable for the whole frame.
 *  - [beginFrame] must be called ONCE at the top of each rendered UI frame. It stamps the
 *    frame time, computes [deltaSeconds] (clamped to `[0, 0.1]`, first frame `1/60` — same
 *    policy as `Interpolation.deltaSeconds`), and bumps [frame].
 *  - [nowSeconds]/[nowMillis]/[nanoTime] are LIVE and safe to read without [beginFrame].
 *  - Single UI/render thread is assumed; fields are `@Volatile` for publication safety only.
 *
 * Time source is `System.nanoTime()` (monotonic) for durations and `System.currentTimeMillis()`
 * for wall-clock millis — both available at runtime (this runs in the live client). No render
 * dependencies.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

/**
 * Shared monotonic UI clock and frame counter. See file header for the contract.
 */
object UiClock {

    private val epochNanos: Long = System.nanoTime()

    @Volatile
    private var frameIndex: Long = 0L

    @Volatile
    private var frameNanos: Long = -1L

    @Volatile
    private var lastDelta: Float = Interpolation.DEFAULT_DELTA

    /**
     * Open a new UI frame: stamp the frame time, update [deltaSeconds], bump [frame].
     * Returns the new frame index. Call once per rendered frame.
     */
    fun beginFrame(): Long {
        val now = System.nanoTime()
        lastDelta = if (frameNanos < 0L) {
            Interpolation.DEFAULT_DELTA
        } else {
            val d = ((now - frameNanos) / NANOS_PER_SECOND).toFloat()
            if (d < 0f) 0f else d.coerceAtMost(Interpolation.MAX_DELTA)
        }
        frameNanos = now
        frameIndex++
        return frameIndex
    }

    /** The current frame index (memoization key). Stable within a frame. */
    fun frame(): Long = frameIndex

    /** Seconds elapsed during the most recently opened frame, clamped to `[0, 0.1]`. */
    fun deltaSeconds(): Float = lastDelta

    /**
     * Monotonic seconds since the clock epoch, captured at the last [beginFrame] (stable
     * within a frame). Falls back to [nowSeconds] before the first frame is opened.
     */
    fun frameTimeSeconds(): Double =
        if (frameNanos < 0L) nowSeconds() else (frameNanos - epochNanos) / NANOS_PER_SECOND

    /** Live monotonic seconds since the clock epoch. Safe without [beginFrame]. */
    fun nowSeconds(): Double = (System.nanoTime() - epochNanos) / NANOS_PER_SECOND

    /** Live wall-clock time in milliseconds (`System.currentTimeMillis()`; NOT monotonic). */
    fun nowMillis(): Long = System.currentTimeMillis()

    /** Live monotonic `System.nanoTime()` passthrough. */
    fun nanoTime(): Long = System.nanoTime()

    /** Reset the counter and delta (e.g. when the GUI is (re)opened). */
    fun reset() {
        frameIndex = 0L
        frameNanos = -1L
        lastDelta = Interpolation.DEFAULT_DELTA
    }

    private const val NANOS_PER_SECOND = 1.0E9
}
