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
package net.ccbluex.liquidbounce.deeplearn.clone

import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.FEATURES
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_YAW
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Finds the stretches where the player had stopped fighting, so the objective can decline to learn
 * them as aim.
 *
 * A recording is cut whenever the player's hand leaves the mouse, but not when their attention
 * does. An opponent in range keeps being recorded while the player turns their back, eats and walks
 * away, and those ticks arrive looking like combat ticks that happen to sit at 150° of error.
 * Learnt as aim they teach the clone that a target behind it means stand still, which is what it
 * then does in play: it drifts off a target mid-fight and stays there, faithfully.
 *
 * The angle cannot find them on its own, because the same geometry also holds the rare urgent turn
 * — someone gets behind you and you snap round — and that is the behaviour the clone most lacks.
 * What separates the two is their shape in time. Measured across the `filya` and `shared` profiles,
 * the excursions past 45° that come back with the hand moving have a median length of 3 and 4
 * ticks and are over within 12 on 95 % and 93 % of occasions, at 21 and 17° per tick, two and a
 * half blocks out. The stretches marked here run 27 and 21 ticks at the median at 4.4 and 2.5° per
 * tick, with the hand completely idle on half their ticks, four to five blocks from the target, and
 * carry 0 of 1932 and 4 of 2340 of the profile's attacks between them.
 */
class CloneDisengagement(
    val openDegrees: Float = OPEN_DEGREES,
    val minTicks: Int = MIN_TICKS,
    val maxMeanStep: Float = MAX_MEAN_STEP,
    val rescueStep: Float = RESCUE_STEP,
    val weight: Float = WEIGHT
) {

    /**
     * Per-tick multipliers for [source], `1` on every tick the player was still fighting on.
     *
     * Read off the raw rows in degrees. The standardized copy the network is fed has no angles left
     * in it, and a threshold in standard deviations is not a threshold in degrees.
     */
    fun mark(source: FeatureSchema.RowSource): FloatArray {
        val marks = FloatArray(source.rowCount) { 1f }

        var start = 0
        while (start < source.rowCount) {
            if (!isOff(source, start)) {
                start++
                continue
            }

            var end = start
            while (end < source.rowCount && isOff(source, end)) {
                end++
            }

            if (end - start >= minTicks && meanStep(source, start, end) < maxMeanStep) {
                for (index in start until end) {
                    if (stepOf(source, index) < rescueStep) {
                        marks[index] = weight
                    }
                }
            }

            start = end
        }

        return marks
    }

    fun markAll(sources: List<FeatureSchema.RowSource>) = Marks(sources.map { mark(it) })

    /**
     * A tick with no target carries a zero delta yaw, so it can neither open a stretch nor hold one
     * open: walking around between fights is not disengagement from anything, and the clone still
     * has to produce it.
     */
    private fun isOff(source: FeatureSchema.RowSource, index: Int) =
        abs(source.featureRows[index * FEATURES + F_DELTA_YAW]) > openDegrees

    private fun stepOf(source: FeatureSchema.RowSource, index: Int) =
        hypot(source.actionRows[index * 2], source.actionRows[index * 2 + 1])

    private fun meanStep(source: FeatureSchema.RowSource, start: Int, end: Int): Float {
        var sum = 0.0
        for (index in start until end) {
            sum += stepOf(source, index)
        }
        return (sum / (end - start)).toFloat()
    }

    /** What [markAll] made of a whole split, so a run can say how much of its recording it dropped. */
    class Marks(val perTick: List<FloatArray>) {
        val total = perTick.sumOf { it.size }
        val disengaged = perTick.sumOf { marks -> marks.count { it < 1f } }

        val share: Float
            get() = if (total == 0) 0f else disengaged.toFloat() / total
    }

    companion object {

        /**
         * Error in degrees above which a tick can belong to a disengagement.
         *
         * An existing bucket edge rather than a threshold of its own, and the lowest one that is
         * safe: below it the crosshair is close enough that holding it still is a real technique —
         * waiting for a strafing target to walk into it — while above it a hand idle for half a
         * second is not aiming at anything. Starting at 90 instead would leave a fifth of the
         * behaviour behind, since 1.2 % of both profiles is long, idle and four to six blocks out
         * while sitting between 45 and 90, which is the band the tick weighting leans on hardest.
         */
        const val OPEN_DEGREES = 45f

        /**
         * Shortest stretch that can be a disengagement.
         *
         * A turn that comes back does it fast: of the excursions past 45° that closed with the hand
         * moving, the 95th percentile lasted 11 and 13 ticks across the two profiles, and 95 % and
         * 93 % were over inside 12. Anything shorter is left alone whatever the hand was doing,
         * because at that length the two classes do overlap and a flick is the more costly thing to
         * lose.
         */
        const val MIN_TICKS = 12

        /**
         * Mean degrees per tick below which a stretch cannot be a turn.
         *
         * Half the speed of one. The recording's committed turns average 16° per tick through the
         * 45-90° band and 21 and 17° per tick over every excursion this rule does not mark, against
         * 4.4 and 2.5 inside the ones it does — where the median tick moves 0.17 and 0.00°.
         */
        const val MAX_MEAN_STEP = 8f

        /**
         * Degrees per tick above which a tick keeps its full weight even inside a marked stretch.
         *
         * A disengagement usually ends by being interrupted, and the snap back onto the target
         * falls *inside* the stretch rather than after it — the longest ones close at 12 to 42° per
         * tick over their final three. Those are the most valuable ticks in the recording, so the
         * stretch they end must not take them down with it. This rescues 11 % and 6 % of the marked
         * ticks and cannot reach the idle part, which sits at a median of 0.17 and 0.00°.
         */
        const val RESCUE_STEP = 12f

        /**
         * What a marked tick counts for, against 1 for every other tick.
         *
         * Zero by design in schema v5. A slow, sustained turn-away is not a noisy example of aim;
         * it is a different task (disengaging) that the runtime is never asked to perform. Giving
         * it even a fifth of a tick still made the recurrent state learn how to enter and remain in
         * that mode. Large geometry remains covered by the fast turns rescued above.
         */
        const val WEIGHT = 0f
    }
}
