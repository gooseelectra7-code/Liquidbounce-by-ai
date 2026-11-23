/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

internal data class Test2Angles(val yaw: Double, val pitch: Double)

internal data class Test2Settings(
    val yawSpeed: Double = 45.0,
    val pitchSpeed: Double = 25.0,
    val acceleration: Double = 10.0,
    val response: Double = 0.45,
    val kalman: Boolean = true,
    val processVariance: Double = 0.5,
    val measurementVariance: Double = 0.25,
    val predictionTicks: Double = 0.25,
    val perlin: Boolean = true,
    val yawNoise: Double = 0.35,
    val pitchNoise: Double = 0.18,
    val noiseRate: Double = 0.06,
) {
    fun sanitized() = copy(
        yawSpeed = yawSpeed.finiteIn(1.0, 120.0, 45.0),
        pitchSpeed = pitchSpeed.finiteIn(1.0, 90.0, 25.0),
        acceleration = acceleration.finiteIn(0.5, 30.0, 10.0),
        response = response.finiteIn(0.1, 1.0, 0.45),
        processVariance = processVariance.finiteIn(0.01, 5.0, 0.5),
        measurementVariance = measurementVariance.finiteIn(0.01, 4.0, 0.25),
        predictionTicks = predictionTicks.finiteIn(0.0, 1.0, 0.25),
        yawNoise = yawNoise.finiteIn(0.0, 2.0, 0.35),
        pitchNoise = pitchNoise.finiteIn(0.0, 1.0, 0.18),
        noiseRate = noiseRate.finiteIn(0.005, 0.5, 0.06),
    )

    private fun Double.finiteIn(minimum: Double, maximum: Double, fallback: Double) =
        if (isFinite()) coerceIn(minimum, maximum) else fallback
}

/** Wrap the error, never the emitted yaw. Pitch does not use this operation. */
internal fun test2YawError(delta: Double): Double = delta - floor((delta + 180.0) / 360.0) * 360.0

/** Constant-velocity Kalman filter with state [angle, angular velocity], in degrees and ticks. */
internal class Test2KalmanAxis(private val circular: Boolean) {
    var angle = 0.0
        private set
    var velocity = 0.0
        private set
    private var p00 = 1.0
    private var p01 = 0.0
    private var p11 = 1.0

    fun reset(measurement: Double, measurementVariance: Double) {
        angle = measurement
        velocity = 0.0
        p00 = measurementVariance
        p01 = 0.0
        p11 = 1.0
    }

    fun update(measurement: Double, processVariance: Double, measurementVariance: Double) {
        val predicted = angle + velocity
        val innovation = if (circular) test2YawError(measurement - predicted) else measurement - predicted
        // A discontinuous aim point is not evidence that the previous angular velocity persists.
        if (abs(innovation) > MAX_INNOVATION) {
            reset(predicted + innovation, measurementVariance)
            return
        }

        // F = [[1, 1], [0, 1]], Q = variance * [[1/4, 1/2], [1/2, 1]].
        val a = p00 + 2.0 * p01 + p11 + processVariance * 0.25
        val b = p01 + p11 + processVariance * 0.5
        val c = p11 + processVariance
        val s = a + measurementVariance
        val k0 = a / s
        val k1 = b / s
        angle = predicted + k0 * innovation
        velocity += k1 * innovation

        // Joseph form: (I - KH) P (I - KH)^T + K R K^T, preserving symmetry.
        val remaining = 1.0 - k0
        p00 = remaining * remaining * a + k0 * k0 * measurementVariance
        p01 = remaining * (b - k1 * a) + k0 * k1 * measurementVariance
        p11 = c - 2.0 * k1 * b + k1 * k1 * a + k1 * k1 * measurementVariance
    }

    private companion object {
        const val MAX_INNOVATION = 45.0
    }
}

/** Seeded 1-D gradient Perlin noise with the improved quintic fade, not interpolated value noise. */
internal class Test2PerlinNoise(seed: Int) {
    private val permutation = IntArray(256) { it }.also { values ->
        val random = Random(seed)
        for (i in values.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val previous = values[i]
            values[i] = values[j]
            values[j] = previous
        }
    }

    fun sample(coordinate: Double): Double {
        val cell = floor(coordinate)
        val t = coordinate - cell
        val index = cell.toInt()
        val left = gradient(index) * t
        val right = gradient(index + 1) * (t - 1.0)
        val fade = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
        return (2.0 * (left + fade * (right - left))).coerceIn(-1.0, 1.0)
    }

    private fun gradient(index: Int) = if (permutation[index and 255] and 1 == 0) 1.0 else -1.0
}

/** Pure tick-driven engine. An identity is the target entity; callers retain their own aim point. */
internal class Test2AimMotion(seed: Int) {
    private val random = Random(seed)
    private val yawFilter = Test2KalmanAxis(circular = true)
    private val pitchFilter = Test2KalmanAxis(circular = false)
    private val yawController = Test2AxisController()
    private val pitchController = Test2AxisController()
    private var yawNoise = Test2PerlinNoise(random.nextInt())
    private var pitchNoise = Test2PerlinNoise(random.nextInt())
    private var phase = 0.0
    private var lastTick: Int? = null
    private var identity: Any? = null
    private var filtering = false
    private var returning = false
    private var cached = Test2Angles(0.0, 0.0)

    fun reset() {
        lastTick = null
        identity = null
        returning = false
        yawController.reset()
        pitchController.reset()
    }

    fun advance(
        tick: Int,
        targetIdentity: Any,
        current: Test2Angles,
        target: Test2Angles,
        requestedSettings: Test2Settings,
        quantum: Double = 0.0,
    ): Test2Angles {
        if (!target.yaw.isFinite() || !target.pitch.isFinite()) {
            reset()
            return current
        }
        val settings = requestedSettings.sanitized()
        val previousTick = lastTick
        if (previousTick == tick) return cached
        val consecutive = previousTick != null && tick.toLong() - previousTick.toLong() == 1L
        val continuing = consecutive && identity === targetIdentity && !returning
        if (!consecutive) reset()

        val measurement = Test2Angles(target.yaw, target.pitch.coerceIn(-90.0, 90.0))
        if (!continuing) {
            identity = targetIdentity
            yawNoise = Test2PerlinNoise(random.nextInt())
            pitchNoise = Test2PerlinNoise(random.nextInt())
            phase = random.nextDouble(0.0, 256.0)
        }
        if (!continuing || settings.kalman != filtering) {
            yawFilter.reset(measurement.yaw, settings.measurementVariance)
            pitchFilter.reset(measurement.pitch, settings.measurementVariance)
        } else if (settings.kalman) {
            yawFilter.update(measurement.yaw, settings.processVariance, settings.measurementVariance)
            pitchFilter.update(measurement.pitch, settings.processVariance, settings.measurementVariance)
        }
        filtering = settings.kalman
        returning = false
        lastTick = tick
        phase += settings.noiseRate

        // Cap the filter/lead's displacement relative to the caller's current observation.
        // It is a tracking heuristic, not a claim that the resulting ray enters the target box.
        val yawOffset = if (settings.kalman) test2YawError(
            yawFilter.angle + yawFilter.velocity * settings.predictionTicks - measurement.yaw
        ).coerceIn(-MAX_FILTER_OFFSET, MAX_FILTER_OFFSET) else 0.0
        val pitchOffset = if (settings.kalman) {
            (pitchFilter.angle + pitchFilter.velocity * settings.predictionTicks - measurement.pitch)
                .coerceIn(-MAX_FILTER_OFFSET, MAX_FILTER_OFFSET)
        } else 0.0
        val distance = hypot(test2YawError(measurement.yaw - current.yaw), measurement.pitch - current.pitch)
        val relativeError = distance / NOISE_FADE_ANGLE
        val noiseScale = if (settings.perlin) 1.0 / (1.0 + relativeError * relativeError) else 0.0
        val goalYaw = measurement.yaw + yawOffset + yawNoise.sample(phase) * settings.yawNoise * noiseScale
        val goalPitch = (measurement.pitch + pitchOffset +
            pitchNoise.sample(phase + 37.37) * settings.pitchNoise * noiseScale).coerceIn(-90.0, 90.0)

        cached = move(current, Test2Angles(goalYaw, goalPitch), settings, quantum, exactEndpoint = false)
        return cached
    }

    /** Keep motor momentum across engagement -> return, but run no Kalman or Perlin on return. */
    fun advanceReturn(
        tick: Int,
        current: Test2Angles,
        target: Test2Angles,
        requestedSettings: Test2Settings,
        quantum: Double,
    ): Test2Angles {
        if (lastTick == tick) return cached
        val previousTick = lastTick
        if (previousTick == null || tick.toLong() - previousTick.toLong() != 1L) reset()
        val settings = requestedSettings.sanitized()
        returning = true
        identity = null
        lastTick = tick
        cached = move(current, target, settings, quantum, exactEndpoint = true)
        return cached
    }

    private fun move(
        current: Test2Angles, target: Test2Angles, settings: Test2Settings, quantum: Double, exactEndpoint: Boolean,
    ): Test2Angles {
        val yawStep = yawController.step(test2YawError(target.yaw - current.yaw), settings.yawSpeed,
            settings.acceleration, settings.response, quantum, exactEndpoint,
            minimumResolution = Math.ulp(current.yaw.toFloat()).toDouble())
        val pitchStep = pitchController.step(target.pitch.coerceIn(-90.0, 90.0) - current.pitch,
            settings.pitchSpeed, settings.acceleration, settings.response, quantum, exactEndpoint,
            minimumResolution = Math.ulp(current.pitch.toFloat()).toDouble())
        val pitch = (current.pitch + pitchStep).coerceIn(-90.0, 90.0)
        pitchController.constrainAtPitchBoundary(pitch - current.pitch)
        return Test2Angles(current.yaw + yawStep, pitch)
    }

    companion object {
        private const val MAX_FILTER_OFFSET = 3.0
        private const val NOISE_FADE_ANGLE = 30.0

        /** Stationary, noise-free timing estimate; never advances the engagement's filters/RNG. */
        fun estimateTicks(current: Test2Angles, target: Test2Angles, requestedSettings: Test2Settings): Int {
            val settings = requestedSettings.sanitized()
            var yaw = abs(test2YawError(target.yaw - current.yaw))
            var pitch = abs(target.pitch.coerceIn(-90.0, 90.0) - current.pitch)
            if (yaw <= 0.5 && pitch <= 0.5) return 1
            val yawController = Test2AxisController()
            val pitchController = Test2AxisController()
            var ticks = 0
            while (ticks < 200 && (abs(yaw) > 0.5 || abs(pitch) > 0.5)) {
                yaw -= yawController.step(yaw, settings.yawSpeed, settings.acceleration, settings.response)
                pitch -= pitchController.step(pitch, settings.pitchSpeed, settings.acceleration, settings.response)
                ticks++
            }
            return min(ticks + 4, 200).coerceAtLeast(1)
        }
    }
}
