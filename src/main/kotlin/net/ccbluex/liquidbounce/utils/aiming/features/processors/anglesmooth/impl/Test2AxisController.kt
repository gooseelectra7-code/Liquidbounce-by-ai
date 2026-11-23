/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.utils.aiming.data.quantizeMouseDeltaWithin
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Smooth velocity following: both desired velocity and acceleration saturate continuously.
 * A reversed goal brakes existing momentum rather than resetting it instantly. Overshoot is possible.
 * Quantization constrains emitted speed and acceleration; the minimum acceleration is one count.
 * An exact reset endpoint is allowed only below half a count (or numerical epsilon without a grid).
 */
internal class Test2AxisController {
    private var velocity = 0.0
    private var emittedStep = 0.0
    private var countResidual = 0.0

    fun reset() {
        velocity = 0.0
        emittedStep = 0.0
        countResidual = 0.0
    }

    fun step(
        error: Double,
        speed: Double,
        acceleration: Double,
        response: Double,
        quantum: Double = 0.0,
        exactEndpoint: Boolean = false,
        minimumResolution: Double = 0.0,
    ): Double {
        // Continuous yaw can accumulate until Float cannot represent a single fine mouse count.
        // Coarsen within speed limits. Float ULP greater than speed has no representable legal step.
        val grid = if (quantum.isFinite() && quantum > 0.0) {
            val resolutionCounts = max(1.0, ceil(minimumResolution / quantum))
            val speedCounts = max(1.0, floor(speed / quantum))
            quantum * min(resolutionCounts, speedCounts)
        } else 0.0
        val limit = max(acceleration, grid)
        // A settings edit can lower speed below the previous step; restart that axis coherently.
        if (abs(emittedStep) > speed + 1e-9) reset()
        val desired = speed * tanh(error * response / speed)
        velocity += limit * tanh((desired - velocity) * response / limit)
        velocity = velocity.coerceIn(-speed, speed)
        val endpointTolerance = max(1e-8, grid * 0.5)
        if (exactEndpoint && abs(error) <= endpointTolerance && abs(error) <= speed &&
            abs(error - emittedStep) <= limit && abs(velocity) <= limit) {
            velocity = error
            emittedStep = error
            countResidual = 0.0
            return error
        }
        // Carry sub-count motion forward so small errors cannot remain stuck above the endpoint.
        val requested = velocity + countResidual
        emittedStep = quantizeMouseDeltaWithin(
            requested, grid, max(-speed, emittedStep - limit), min(speed, emittedStep + limit)
        )
        countResidual = (requested - emittedStep).coerceIn(-grid * 0.5, grid * 0.5)
        return emittedStep
    }

    /** Pitch is physically clamped by vanilla; discard outward momentum at that boundary. */
    fun constrainAtPitchBoundary(actualStep: Double) {
        if (abs(actualStep - emittedStep) > 1e-9) {
            emittedStep = actualStep
            velocity = actualStep
            countResidual = 0.0
        }
    }
}
