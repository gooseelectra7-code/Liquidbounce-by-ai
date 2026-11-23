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
package net.ccbluex.liquidbounce.utils.aiming.learned

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

/**
 * Humanization helpers for the learned aim. Stateless, dependency-free.
 *
 * Port of `troll.xyz.ai.AiHumanizer` (supplied by the user 2026-09-19), constants as written.
 */
object AiHumanizer {

    private const val ATTACK_SIGMA = 0.25f
    private const val ATTACK_LOW = 0.5f
    private const val ATTACK_HIGH = 1.5f

    private const val DIST_BASE = 0.85f
    private const val DIST_SPAN = 0.35f
    private const val DIST_FULL = 6.0f
    private const val ANGLE_BASE = 0.90f
    private const val ANGLE_SPAN = 0.45f
    private const val ANGLE_FULL = 90.0f

    private const val JITTER_YAW_SIGMA = 0.035
    private const val JITTER_PITCH_SIGMA = 0.02

    private const val SPEED_FLOOR = 0.05f
    private const val SPEED_LOW = 0.25f
    private const val SPEED_HIGH = 4.0f

    /** Gaussian multiplier around [mean] with relative sigma [relSigma], clamped to [lo, hi]. */
    fun gauss(mean: Float, relSigma: Float, lo: Float, hi: Float): Float {
        val v = (mean * (1.0 + ThreadLocalRandom.current().nextGaussian() * relSigma)).toFloat()
        return max(lo, min(hi, v))
    }

    /** Humanized attack delay: gaussian +-25% around [base], clamped [0.5x..1.5x], at least 1. */
    fun attackDelay(base: Float): Float {
        if (base <= 0) {
            return base
        }
        return max(1.0f, gauss(base, ATTACK_SIGMA, base * ATTACK_LOW, base * ATTACK_HIGH))
    }

    /** Adaptive speed: far / steep targets snap faster, close targets stay smooth. */
    fun adaptiveSpeed(base: Float, dist: Float, angleDelta: Float): Float {
        val distF = DIST_BASE + min(dist / DIST_FULL, 1.0f) * DIST_SPAN
        val angF = ANGLE_BASE + min(angleDelta / ANGLE_FULL, 1.0f) * ANGLE_SPAN
        return base * distF * angF
    }

    /** Micro-jitter so the accumulated aim never sits on a mathematically perfect line. */
    fun jitter(yawDelta: Float, pitchDelta: Float): FloatArray {
        val r = ThreadLocalRandom.current()
        return floatArrayOf(
            yawDelta + (r.nextGaussian() * JITTER_YAW_SIGMA).toFloat(),
            pitchDelta + (r.nextGaussian() * JITTER_PITCH_SIGMA).toFloat()
        )
    }

    /**
     * The yaw half of [jitter] on its own, so a step whose other axis the recording left at exactly
     * zero keeps that axis at exactly zero.
     */
    fun jitterYaw(yawDelta: Double): Double =
        yawDelta + ThreadLocalRandom.current().nextGaussian() * JITTER_YAW_SIGMA

    /** The pitch half of [jitter] on its own; see [jitterYaw]. */
    fun jitterPitch(pitchDelta: Double): Double =
        pitchDelta + ThreadLocalRandom.current().nextGaussian() * JITTER_PITCH_SIGMA

    /** Clamps an outlier segment speed around the learned [median]. */
    fun clampSpeed(speed: Float, median: Float): Float {
        val lo = max(SPEED_FLOOR, median * SPEED_LOW)
        return max(lo, min(median * SPEED_HIGH, speed))
    }
}
