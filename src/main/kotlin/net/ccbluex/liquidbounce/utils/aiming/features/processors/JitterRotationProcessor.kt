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
package net.ccbluex.liquidbounce.utils.aiming.features.processors

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.kotlin.random
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random

/**
 * Maximally randomized rotation noise, layered to guarantee non-repeating offsets across long sessions.
 *
 * Three independent stages combine additively:
 *  1. **Micro** — fresh sign-flipped uniform shift on every [process] call.
 *  2. **Burst** — sustained sign-flipped shift that triggers by chance, held for a random tick window.
 *  3. **Drift** — slow Ornstein–Uhlenbeck random walk (mean reversion to 0) using Gaussian steps.
 *
 * Each layer has its own randomness source, sampling cadence and amplitude range, so the joint
 * state space is effectively continuous — the same yaw/pitch combo is never replayed.
 */
class JitterRotationProcessor(owner: EventListener? = null)
    : ToggleableValueGroup(owner, "Jitter", false), RotationProcessor {

    private inner class MicroNoise : ToggleableValueGroup(this, "MicroNoise", true) {
        val yaw by floatRange("Yaw", 0f..0.6f, 0f..15f, "°")
        val pitch by floatRange("Pitch", 0f..0.3f, 0f..15f, "°")
    }

    private inner class Burst : ToggleableValueGroup(this, "Burst", true) {
        val rate by int("Rate", 25, 0..100, "%")
        val duration by intRange("Duration", 2..6, 1..40, "ticks")
        val yaw by floatRange("YawShift", 0.5f..2.5f, 0f..25f, "°")
        val pitch by floatRange("PitchShift", 0f..1f, 0f..25f, "°")
    }

    private inner class Drift : ToggleableValueGroup(this, "Drift", true) {
        val maxYaw by float("MaxYaw", 2f, 0f..20f, "°")
        val maxPitch by float("MaxPitch", 1f, 0f..20f, "°")
        val stepStrength by float("StepStrength", 0.15f, 0.01f..2f, "°")
        val meanReversion by float("MeanReversion", 0.05f, 0f..1f)
    }

    private val micro = tree(MicroNoise())
    private val burst = tree(Burst())
    private val drift = tree(Drift())

    // burst layer state
    private var burstTicks = 0
    private var burstYaw = 0f
    private var burstPitch = 0f

    // drift layer state
    private var driftYaw = 0f
    private var driftPitch = 0f

    @Suppress("unused")
    private val gameTick = handler<GameTickEvent>(priority = EventPriorityConvention.FIRST_PRIORITY) {
        // --- Burst ---
        if (burst.enabled) {
            if (burstTicks > 0) {
                burstTicks--
                if (burstTicks == 0) {
                    burstYaw = 0f
                    burstPitch = 0f
                }
            } else if (burst.rate > (0..100).random()) {
                burstTicks = burst.duration.random()
                burstYaw = burst.yaw.random().withRandomSign()
                burstPitch = burst.pitch.random().withRandomSign()
            }
        } else {
            burstTicks = 0
            burstYaw = 0f
            burstPitch = 0f
        }

        // --- Drift (Ornstein–Uhlenbeck) ---
        if (drift.enabled) {
            val rng = ThreadLocalRandom.current()
            driftYaw = ((1f - drift.meanReversion) * driftYaw +
                rng.nextGaussian().toFloat() * drift.stepStrength)
                .coerceIn(-drift.maxYaw, drift.maxYaw)
            driftPitch = ((1f - drift.meanReversion) * driftPitch +
                rng.nextGaussian().toFloat() * drift.stepStrength)
                .coerceIn(-drift.maxPitch, drift.maxPitch)
        } else {
            driftYaw = 0f
            driftPitch = 0f
        }

        ModuleDebug.debugParameter(this, "BurstTicks", burstTicks)
        ModuleDebug.debugParameter(this, "BurstYaw", burstYaw)
        ModuleDebug.debugParameter(this, "BurstPitch", burstPitch)
        ModuleDebug.debugParameter(this, "DriftYaw", driftYaw)
        ModuleDebug.debugParameter(this, "DriftPitch", driftPitch)
    }

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        if (!this.running) {
            return targetRotation
        }

        var dy = 0f
        var dp = 0f

        if (micro.enabled) {
            dy += micro.yaw.random().withRandomSign()
            dp += micro.pitch.random().withRandomSign()
        }

        dy += burstYaw
        dp += burstPitch
        dy += driftYaw
        dp += driftPitch

        ModuleDebug.debugParameter(this, "TotalDY", dy)
        ModuleDebug.debugParameter(this, "TotalDP", dp)

        return Rotation(
            targetRotation.yaw + dy,
            targetRotation.pitch + dp
        )
    }

    private fun Float.withRandomSign(): Float = if (Random.nextBoolean()) this else -this

}
