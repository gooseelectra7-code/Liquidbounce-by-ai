/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import java.util.concurrent.ThreadLocalRandom

/**
 * Experimental Kalman + gradient Perlin rotation, original implementation (2026-10-07).
 * Kalman equations: https://kalmanfilter.net/multiSummary.html; quintic gradient noise:
 * https://cs.nyu.edu/~perlin/noise/. Uses the caller's angle, never re-picks the live hitbox.
 *
 * No direct packets: RotationManager owns application and movement correction. The controller
 * quantizes its own steps within speed/acceleration bounds; Float rounding and pitch limits apply.
 * Return keeps motor momentum and the configured bounds, without filter/noise or threshold snaps.
 * The final residual may be below half a mouse count so the camera can be released exactly.
 * Fail/ShortStop/Jitter are suppressed. Caller action ray/range and use-item gates still apply.
 * No human-aim or anticheat compatibility claim. Perlin has a 256-cell period per engagement.
 */
class Test2AngleSmooth(parent: ModeValueGroup<*>) : AngleSmooth("Test2", parent, aliases = listOf("test2")) {
    override val suppressesPostProcessors: Boolean get() = true
    override val requiresLiveTick: Boolean get() = true
    override val preservesResetSteps: Boolean get() = true

    private val yawSpeed by float("YawSpeed", 45f, 1f..120f, "deg/tick")
    private val pitchSpeed by float("PitchSpeed", 25f, 1f..90f, "deg/tick")
    private val acceleration by float("Acceleration", 10f, 0.5f..30f, "deg/tick^2")
    private val response by float("Response", 0.45f, 0.1f..1f)

    private inner class Kalman : ToggleableValueGroup(this, "Kalman", true) {
        val processVariance by float("ProcessVariance", 0.5f, 0.01f..5f)
        val measurementVariance by float("MeasurementVariance", 0.25f, 0.01f..4f)
        val predictionTicks by float("PredictionTicks", 0.25f, 0f..1f, "ticks")
    }

    private inner class Perlin : ToggleableValueGroup(this, "Perlin", true) {
        val yawAmplitude by float("YawAmplitude", 0.35f, 0f..2f, "deg")
        val pitchAmplitude by float("PitchAmplitude", 0.18f, 0f..1f, "deg")
        val rate by float("Rate", 0.06f, 0.005f..0.5f, "cells/tick")
    }

    private val kalman = tree(Kalman())
    private val perlin = tree(Perlin())
    private val motion = Test2AimMotion(ThreadLocalRandom.current().nextInt())

    override fun disable() = motion.reset()

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation,
    ): Rotation {
        val current = currentRotation.toAngles()
        val target = targetRotation.toAngles()
        val entity = rotationTarget.entity
        if (entity == null) {
            return motion.advanceReturn(player.tickCount, current, target, settings(), RotationUtil.gcd)
                .toRotation(normalized = true)
        }
        return motion.advance(player.tickCount, entity, current, target, settings(), RotationUtil.gcd)
            .toRotation(normalized = true)
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int =
        Test2AimMotion.estimateTicks(currentRotation.toAngles(), targetRotation.toAngles(), settings())

    private fun settings() = Test2Settings(
        yawSpeed = yawSpeed.toDouble(),
        pitchSpeed = pitchSpeed.toDouble(),
        acceleration = acceleration.toDouble(),
        response = response.toDouble(),
        kalman = kalman.enabled,
        processVariance = kalman.processVariance.toDouble(),
        measurementVariance = kalman.measurementVariance.toDouble(),
        predictionTicks = kalman.predictionTicks.toDouble(),
        perlin = perlin.enabled,
        yawNoise = perlin.yawAmplitude.toDouble(),
        pitchNoise = perlin.pitchAmplitude.toDouble(),
        noiseRate = perlin.rate.toDouble(),
    )

    private fun Rotation.toAngles() = Test2Angles(yaw.toDouble(), pitch.toDouble())
    private fun Test2Angles.toRotation(normalized: Boolean) =
        Rotation(yaw.toFloat(), pitch.toFloat().coerceIn(-90f, 90f), isNormalized = normalized)
}
