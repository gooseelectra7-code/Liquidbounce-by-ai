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
package net.ccbluex.liquidbounce.deeplearn.clone.eval

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Summary statistics of one window, in the shape a tree ensemble eats.
 *
 * These are the quantities a detector engineers by hand rather than learns: hand-written checks
 * and boosted-tree models both live on vectors like this. Naming them explicitly is deliberate —
 * when the ensemble reports which one carried the most split gain, the answer is directly
 * actionable, which is something an AUC on raw sequences never is.
 */
object AggregateFeatures {

    val NAMES = arrayOf(
        "mean |dyaw|",
        "mean |dpitch|",
        "std dyaw",
        "std dpitch",
        "still fraction yaw",
        "still fraction pitch",
        "mean still-run length",
        "longest still run",
        "max |dyaw|",
        "large-step fraction",
        "autocorr dyaw lag1",
        "autocorr dyaw lag2",
        "autocorr dpitch lag1",
        "yaw/pitch correlation",
        "path length",
        "net angle closed",
        "path efficiency",
        "mean angle to target",
        "min angle to target",
        "on-target fraction",
        "attacks",
        "mean angle at attack",
        "yaw/pitch magnitude ratio",
        "direction reversals",
        "aim point spread x",
        "aim point spread y"
    )

    val COUNT = NAMES.size

    /** A step counts as "large" past this many degrees — the flick end of the distribution. */
    private const val LARGE_STEP = 3f

    private const val ON_TARGET_ANGLE = 3f

    /**
     * Builds the vector for `ticks` ticks starting at [start] of a [CloneEval] stream.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun of(stream: FloatArray, start: Int, ticks: Int): FloatArray {
        val out = FloatArray(COUNT)
        if (ticks <= 2) {
            return out
        }

        val yaw = FloatArray(ticks)
        val pitch = FloatArray(ticks)
        val angle = FloatArray(ticks)
        val onTarget = BooleanArray(ticks)

        var pathLength = 0f
        var attacks = 0
        var attackAngleSum = 0f
        var reversals = 0
        var large = 0

        for (i in 0 until ticks) {
            val offset = (start + i) * CloneEval.CHANNELS
            yaw[i] = stream[offset + CloneEval.C_DX]
            pitch[i] = stream[offset + CloneEval.C_DY]

            val hasTarget = stream[offset + CloneEval.C_HAS_TARGET] > 0.5f
            angle[i] = if (hasTarget) {
                hypot(stream[offset + CloneEval.C_DELTA_YAW], stream[offset + CloneEval.C_DELTA_PITCH])
            } else {
                -1f
            }
            onTarget[i] = angle[i] in 0f..ON_TARGET_ANGLE

            val step = hypot(yaw[i], pitch[i])
            pathLength += step
            if (step > LARGE_STEP) {
                large++
            }

            if (stream[offset + CloneEval.C_ATTACKED] > 0.5f) {
                attacks++
                if (angle[i] >= 0f) {
                    attackAngleSum += angle[i]
                }
            }

            if (i > 0 && yaw[i] != 0f && yaw[i - 1] != 0f && (yaw[i] > 0f) != (yaw[i - 1] > 0f)) {
                reversals++
            }
        }

        out[0] = mean(yaw) { abs(it) }
        out[1] = mean(pitch) { abs(it) }
        out[2] = deviation(yaw)
        out[3] = deviation(pitch)
        out[4] = yaw.count { it == 0f }.toFloat() / ticks
        out[5] = pitch.count { it == 0f }.toFloat() / ticks

        val runs = stillRuns(yaw)
        out[6] = if (runs.isEmpty()) 0f else runs.average().toFloat()
        out[7] = (runs.maxOrNull() ?: 0).toFloat()

        out[8] = yaw.maxOf { abs(it) }
        out[9] = large.toFloat() / ticks
        out[10] = autocorrelation(yaw, 1)
        out[11] = autocorrelation(yaw, 2)
        out[12] = autocorrelation(pitch, 1)
        out[13] = correlation(yaw, pitch)

        // Path efficiency: how much crosshair travel it took to close the angle.
        //
        // This is the oldest aim tell there is and one the clone cannot fake by matching step
        // sizes alone. A hand arcs, overshoots and doubles back, so it covers noticeably more
        // ground than the straight line between where it started and where it ended; a generator
        // that converges cleanly covers barely more than the line itself.
        val tracked = angle.filter { it >= 0f }
        val netClosed = if (tracked.size >= 2) max(0f, tracked.first() - tracked.last()) else 0f
        out[14] = pathLength
        out[15] = netClosed
        out[16] = if (netClosed > MIN_CLOSED_ANGLE) pathLength / netClosed else 0f

        out[17] = if (tracked.isEmpty()) 0f else tracked.average().toFloat()
        out[18] = tracked.minOrNull() ?: 0f
        out[19] = onTarget.count { it }.toFloat() / ticks
        out[20] = attacks.toFloat()
        out[21] = if (attacks == 0) 0f else attackAngleSum / attacks
        out[22] = out[0] / max(out[1], 1e-3f)
        out[23] = reversals.toFloat() / ticks

        // How far the crosshair wanders inside the hitbox. A generator that always takes the same
        // spot leaves this near zero, which no hand does.
        out[24] = spread(stream, start, ticks, CloneEval.C_AIM_REL_X)
        out[25] = spread(stream, start, ticks, CloneEval.C_AIM_REL_Y)

        return out
    }

    private fun spread(stream: FloatArray, start: Int, ticks: Int, channel: Int): Float {
        var sum = 0.0
        var count = 0

        for (i in 0 until ticks) {
            val offset = (start + i) * CloneEval.CHANNELS
            if (stream[offset + CloneEval.C_HAS_TARGET] <= 0.5f) {
                continue
            }
            sum += stream[offset + channel]
            count++
        }
        if (count == 0) {
            return 0f
        }

        val average = sum / count
        var variance = 0.0
        for (i in 0 until ticks) {
            val offset = (start + i) * CloneEval.CHANNELS
            if (stream[offset + CloneEval.C_HAS_TARGET] <= 0.5f) {
                continue
            }
            val d = stream[offset + channel] - average
            variance += d * d
        }
        return sqrt(variance / count).toFloat()
    }

    /**
     * Path efficiency over a completed approach: the crosshair starts well off the target and
     * settles onto it. Returns one ratio per approach found in the stream.
     *
     * Measured over whole approaches rather than fixed windows because the quantity only means
     * something end to end — a window that happens to start mid-flick has no "net angle closed"
     * worth dividing by.
     */
    fun approachEfficiencies(stream: FloatArray): List<Float> {
        val ticks = stream.size / CloneEval.CHANNELS
        val result = mutableListOf<Float>()

        var start = -1
        var path = 0f
        var startAngle = 0f

        for (i in 0 until ticks) {
            val offset = i * CloneEval.CHANNELS
            if (stream[offset + CloneEval.C_HAS_TARGET] <= 0.5f) {
                start = -1
                continue
            }

            val angle = hypot(
                stream[offset + CloneEval.C_DELTA_YAW],
                stream[offset + CloneEval.C_DELTA_PITCH]
            )

            if (start < 0) {
                if (angle >= APPROACH_START_ANGLE) {
                    start = i
                    startAngle = angle
                    path = 0f
                }
                continue
            }

            path += hypot(
                stream[offset + CloneEval.C_DX],
                stream[offset + CloneEval.C_DY]
            )

            if (angle <= ON_TARGET_ANGLE) {
                val closed = startAngle - angle
                if (closed > MIN_CLOSED_ANGLE && path > 0f) {
                    result.add(path / closed)
                }
                start = -1
            } else if (i - start > APPROACH_TIMEOUT) {
                start = -1
            }
        }

        return result
    }

    /** An approach only starts once the crosshair is this far off the target. */
    private const val APPROACH_START_ANGLE = 15f

    /** Give up on an approach that has not landed within this many ticks. */
    private const val APPROACH_TIMEOUT = 60

    /** Below this the ratio is dominated by noise in the denominator. */
    private const val MIN_CLOSED_ANGLE = 5f

    private fun stillRuns(values: FloatArray): List<Int> {
        val runs = mutableListOf<Int>()
        var current = 0

        for (value in values) {
            if (value == 0f) {
                current++
            } else if (current > 0) {
                runs.add(current)
                current = 0
            }
        }
        if (current > 0) {
            runs.add(current)
        }
        return runs
    }

    private inline fun mean(values: FloatArray, transform: (Float) -> Float): Float {
        var sum = 0.0
        for (value in values) {
            sum += transform(value)
        }
        return (sum / values.size).toFloat()
    }

    private fun deviation(values: FloatArray): Float {
        val average = mean(values) { it }
        var sum = 0.0
        for (value in values) {
            val d = value - average
            sum += d * d
        }
        return sqrt(sum / values.size).toFloat()
    }

    private fun autocorrelation(values: FloatArray, lag: Int): Float {
        if (values.size <= lag + 1) {
            return 0f
        }

        val average = mean(values) { it }
        var covariance = 0.0
        var variance = 0.0

        for (i in values.indices) {
            val d = values[i] - average
            variance += d * d
            if (i + lag < values.size) {
                covariance += d * (values[i + lag] - average)
            }
        }

        return if (variance <= 0.0) 0f else (covariance / variance).toFloat()
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val meanA = mean(a) { it }
        val meanB = mean(b) { it }

        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0

        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            covariance += da * db
            varianceA += da * da
            varianceB += db * db
        }

        val denominator = sqrt(varianceA * varianceB)
        return if (denominator <= 0.0) 0f else (covariance / denominator).toFloat()
    }
}
