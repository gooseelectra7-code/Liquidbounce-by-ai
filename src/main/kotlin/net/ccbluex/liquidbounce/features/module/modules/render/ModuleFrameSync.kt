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
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import kotlin.math.roundToInt

/**
 * FrameSync module
 *
 * Caps rendered frames at the monitor's refresh rate while the client loop keeps running
 * unthrottled. Unlike the vanilla FPS limit, which sleeps the whole loop, this skips only
 * the extract+render pair of a frame — input polling, picking and tick scheduling stay at
 * full rate, so GPU load drops without the input latency a sleep-based limiter adds.
 * For the full effect the vanilla Max Framerate should be set to Unlimited and VSync off.
 *
 * Idea ported from the "FrameSync" Fabric mod by rybya (1.21.4); the frame pacing here is
 * reimplemented because the original's smooth mode double-counted elapsed time between
 * skipped loop iterations and capped far above the requested rate.
 */
object ModuleFrameSync : ClientModule("FrameSync", ModuleCategories.RENDER) {

    private val autoDetect by boolean("AutoDetect", true)
    private val customHz by int("Hz", 144, 30..540, "Hz")
    private val pacing by enumChoice("Pacing", Pacing.SMOOTH)

    enum class Pacing(override val tag: String) : Tagged {
        /** Accumulator with one-interval carry-over: hits the target rate on average. */
        SMOOTH("Smooth"),
        /** Plain elapsed-time gate: simpler, drifts slightly below the target rate. */
        SIMPLE("Simple"),
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val REDETECT_INTERVAL = 2 * NANOS_PER_SECOND
    private const val FRAME_HISTORY_SIZE = 10

    private var skipFrame = false
    private var lastCheckTime = 0L
    private var lastRenderTime = 0L
    private var accumulator = 0L

    private var detectedHz = 0
    private var lastDetectTime = 0L

    private val frameIntervals = LongArray(FRAME_HISTORY_SIZE)
    private var frameIntervalIndex = 0

    override val tag: String?
        get() = if (running) "${measuredFps()} fps" else null

    override fun onEnabled() {
        val now = System.nanoTime()
        lastCheckTime = now
        lastRenderTime = now
        accumulator = 0L
        frameIntervals.fill(0L)
        frameIntervalIndex = 0
        detectedHz = 0
    }

    override fun onDisabled() {
        skipFrame = false
    }

    /**
     * Decides once per client loop iteration whether this frame runs. Called from the
     * `GameRenderer.extract` HEAD injection; the `render` HEAD injection then reads
     * [isFrameSkipped] so both phases of the same iteration stay paired.
     *
     * @return true when the frame should be skipped
     */
    fun startFrame(): Boolean {
        skipFrame = running && !tryFrame()
        return skipFrame
    }

    fun isFrameSkipped(): Boolean = skipFrame

    /**
     * The fps value the rest of the client should see: the measured rendered rate while
     * the limiter is active, [original] (vanilla's loop-iteration counter, inflated by
     * the skipped frames) otherwise. Wired into `Minecraft.getFps` by MixinMinecraft so
     * every readout — F3, HUDs, the web theme, logs — agrees automatically.
     */
    fun effectiveFps(original: Int): Int = if (running) measuredFps() else original

    private fun tryFrame(): Boolean {
        val interval = NANOS_PER_SECOND / targetHz()
        val now = System.nanoTime()

        return when (pacing) {
            Pacing.SMOOTH -> {
                val delta = now - lastCheckTime
                lastCheckTime = now

                if (delta > interval * 3) {
                    // Stall (lag spike, world load, module just resumed): drop the
                    // backlog instead of bursting frames to catch up.
                    accumulator = 0L
                    recordRender(now)
                    return true
                }

                accumulator += delta
                if (accumulator < interval) {
                    return false
                }

                accumulator = (accumulator - interval).coerceAtMost(interval)
                recordRender(now)
                true
            }

            Pacing.SIMPLE -> {
                if (now - lastRenderTime < interval) {
                    return false
                }

                recordRender(now)
                true
            }
        }
    }

    private fun recordRender(now: Long) {
        frameIntervals[frameIntervalIndex] = now - lastRenderTime
        frameIntervalIndex = (frameIntervalIndex + 1) % FRAME_HISTORY_SIZE
        lastRenderTime = now
    }

    /**
     * Refreshes the auto-detected rate when stale. Render thread only
     * (Window.getRefreshRate asserts it), so this is called solely from [startFrame].
     */
    private fun targetHz(): Int {
        if (!autoDetect) {
            return customHz
        }

        val now = System.nanoTime()
        if (detectedHz <= 0 || now - lastDetectTime > REDETECT_INTERVAL) {
            detectedHz = mc.window.refreshRate
            lastDetectTime = now
        }

        return if (detectedHz > 0) detectedHz else 60
    }

    /** Thread-safe view of the current target for display purposes. */
    private fun cachedTargetHz(): Int = when {
        !autoDetect -> customHz
        detectedHz > 0 -> detectedHz
        else -> 60
    }

    private fun measuredFps(): Int {
        var sum = 0L
        var count = 0
        for (intervalNanos in frameIntervals) {
            if (intervalNanos > 0L) {
                sum += intervalNanos
                count++
            }
        }

        if (count == 0) {
            return cachedTargetHz()
        }

        return (NANOS_PER_SECOND.toDouble() * count / sum).roundToInt()
    }

}
