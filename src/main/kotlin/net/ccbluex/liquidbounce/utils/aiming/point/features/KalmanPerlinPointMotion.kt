/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.point.features

import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.Test2KalmanAxis
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.Test2PerlinNoise
import java.util.IdentityHashMap
import kotlin.math.sqrt
import kotlin.random.Random

/** Coordinates relative to the current box, not world positions. */
internal data class PointProportions(val x: Double, val y: Double, val z: Double)

internal data class KalmanPerlinPointSettings(
    val kalman: Boolean = true,
    val processVariance: Double = 0.5,
    val measurementVariance: Double = 4.0,
    val maxLag: Double = 0.12,
    val perlin: Boolean = true,
    val horizontalAmplitude: Double = 0.08,
    val verticalAmplitude: Double = 0.04,
    val rate: Double = 0.04,
    val edgeMargin: Double = 0.05,
) {
    // RangedValue's bounds describe the GUI; scripts/config loads can still supply invalid values.
    fun sanitized() = copy(
        processVariance = processVariance.finiteIn(0.01, 10.0, 0.5),
        measurementVariance = measurementVariance.finiteIn(0.1, 25.0, 4.0),
        maxLag = maxLag.finiteIn(0.0, 0.4, 0.12),
        horizontalAmplitude = horizontalAmplitude.finiteIn(0.0, 0.3, 0.08),
        verticalAmplitude = verticalAmplitude.finiteIn(0.0, 0.3, 0.04),
        rate = rate.finiteIn(0.005, 0.25, 0.04),
        edgeMargin = edgeMargin.finiteIn(0.0, 0.49, 0.05),
    )

    private fun Double.finiteIn(minimum: Double, maximum: Double, fallback: Double) =
        if (isFinite()) coerceIn(minimum, maximum) else fallback
}

/**
 * Reuses Test2's linear Kalman equations in PERCENT coordinates (0..100), with no yaw wrapping.
 * Its 45-unit innovation reset therefore means 45% of a box side, and variances are percent^2.
 * Perlin displacements are fractions of each CURRENT box side; translation is never filtered.
 *
 * Candidate searches may inspect several entities in one tick. Each identity gets independent,
 * once-per-tick state, rather than resetting the chosen target whenever another is probed.
 * Inactive entries expire after a skipped tick; the cache also has a hard size bound.
 * At capacity, uncached candidates get a margin-clamped input point. Never evict a live tick's
 * result merely because another candidate is probed before the chosen target is requested again.
 */
internal class KalmanPerlinPointMotion(seed: Int) {
    private val random = Random(seed)
    private val tracks = IdentityHashMap<Any, Track>()
    private var lastTick: Int? = null
    private var access = 0L

    private class Track(seed: Int) {
        val filters = Array(3) { Test2KalmanAxis(circular = false) }
        private val random = Random(seed)
        val noise = Array(3) { Test2PerlinNoise(random.nextInt()) }
        val tempo = Test2PerlinNoise(random.nextInt())
        val phases = DoubleArray(3) { random.nextDouble(0.0, PERLIN_PERIOD) }
        val baselines = DoubleArray(3)
        var tempoPhase = random.nextDouble(0.0, PERLIN_PERIOD)
        var lastTick: Int? = null
        var lastAccess = 0L
        var settings: KalmanPerlinPointSettings? = null
        var cached = PointProportions(0.5, 0.5, 0.5)
    }

    fun reset() {
        tracks.clear()
        lastTick = null
        access = 0L
    }

    fun advance(
        tick: Int,
        identity: Any,
        observation: PointProportions,
        requestedSettings: KalmanPerlinPointSettings,
    ): PointProportions {
        if (!observation.x.isFinite() || !observation.y.isFinite() || !observation.z.isFinite()) {
            reset()
            return observation
        }
        // With both subfeatures off, even the edge margin must leave the caller's point alone.
        if (!requestedSettings.kalman && !requestedSettings.perlin) {
            reset()
            return observation
        }
        val settings = requestedSettings.sanitized()
        prepareTick(tick)
        val track = tracks[identity] ?: newTrack(identity, tick) ?: return observation.withMargin(settings.edgeMargin)
        track.lastAccess = ++access
        if (track.lastTick == tick && track.settings == settings) return track.cached

        val measurement = doubleArrayOf(observation.x, observation.y, observation.z)
        val restarting = track.lastTick == null || track.settings != settings
        val margin = settings.edgeMargin.coerceIn(0.0, 0.49)
        val result = DoubleArray(3) { axis ->
            val raw = measurement[axis].coerceIn(0.0, 1.0)
            val filter = track.filters[axis]
            if (restarting) {
                filter.reset(raw * PERCENT, settings.measurementVariance)
                track.baselines[axis] = track.noise[axis].sample(track.phases[axis])
            } else if (settings.kalman) {
                filter.update(raw * PERCENT, settings.processVariance, settings.measurementVariance)
            }
            val filtered = if (settings.kalman) {
                raw + (filter.angle / PERCENT - raw).coerceIn(-settings.maxLag, settings.maxLag)
            } else raw
            val amplitude = if (axis == 1) settings.verticalAmplitude else settings.horizontalAmplitude
            // Difference from the initial sample starts at zero without a fixed gain ramp.
            val offset = if (settings.perlin) {
                (track.noise[axis].sample(track.phases[axis]) - track.baselines[axis]) * amplitude * 0.5
            } else 0.0
            if (settings.perlin) softMargin(filtered + offset, margin) else filtered.coerceIn(margin, 1.0 - margin)
        }
        for (axis in 0..2) {
            val tempo = 1.0 + 0.35 * track.tempo.sample(track.tempoPhase + axis * 37.37)
            track.phases[axis] = (track.phases[axis] + settings.rate * tempo) % PERLIN_PERIOD
        }
        track.tempoPhase = (track.tempoPhase + settings.rate * 0.1) % PERLIN_PERIOD
        track.lastTick = tick
        track.settings = settings
        track.cached = PointProportions(result[0], result[1], result[2])
        return track.cached
    }

    private fun prepareTick(tick: Int) {
        val previous = lastTick
        if (previous != null && tick != previous) {
            if (tick.toLong() - previous.toLong() != 1L) {
                reset()
            } else {
                tracks.entries.removeIf { (_, track) ->
                    track.lastTick?.let { tick.toLong() - it.toLong() > 1L } ?: true
                }
            }
        }
        lastTick = tick
    }

    private fun newTrack(identity: Any, tick: Int): Track? {
        if (tracks.size >= MAX_TRACKS) {
            val oldest = tracks.entries.filter { it.value.lastTick != tick }
                .minByOrNull { it.value.lastAccess }?.key ?: return null
            tracks.remove(oldest)
        }
        return Track(random.nextInt()).also { tracks[identity] = it }
    }

    private fun PointProportions.withMargin(edgeMargin: Double): PointProportions {
        val margin = edgeMargin.coerceIn(0.0, 0.49)
        return PointProportions(x.coerceIn(margin, 1.0 - margin), y.coerceIn(margin, 1.0 - margin),
            z.coerceIn(margin, 1.0 - margin))
    }

    private companion object {
        const val PERCENT = 100.0
        const val PERLIN_PERIOD = 256.0
        const val MAX_TRACKS = 64

        // Smoothly compress excursions toward a face instead of clipping a half-cycle flat.
        fun softMargin(value: Double, margin: Double): Double {
            val high = 1.0 - margin
            val softness = minOf(0.02, (high - margin) * 0.1)
            // Algebraic tails survive rounding even for a .49 margin and face observations.
            fun softplus(x: Double): Double {
                val radius = sqrt(x * x + softness * softness)
                return if (x >= 0.0) (x + radius) * 0.5 else
                    softness * softness / (2.0 * (radius - x))
            }
            return (margin + softplus(value - margin) - softplus(value - high)).coerceIn(margin, high)
        }
    }
}
