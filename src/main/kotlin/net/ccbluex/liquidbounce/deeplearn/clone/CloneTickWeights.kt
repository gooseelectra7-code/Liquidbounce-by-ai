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
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_PITCH
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_YAW
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DISTANCE
import kotlin.math.hypot

/**
 * How much a training tick counts, taken from what the player is doing at that angle off the target.
 *
 * The loss is a mean over ticks and the error to the target is heavy-tailed, so an unweighted mean is
 * almost entirely a description of small-correction behaviour: the clone tracks well and cannot start
 * a turn, which compounds, because falling behind puts it in exactly the geometry it learnt least
 * about.
 *
 * The tempting fix is to weight by inverse frequency, and it is wrong here. Measured across two
 * profiles, the rarest band is not the one that turns. Past 135° the player is motionless on 40 % of
 * ticks, is using an item on 42 %, sits four blocks away, and moves an average of 8.5° — *less* than
 * in the 45-90° band — with three quarters of those ticks inside stretches of eight or more. That is
 * not a flick, it is disengagement: back turned, eating, walking away. Emphasising it because it is
 * rare teaches the clone that a target behind it means stand still, which is exactly the behaviour a
 * rarity-weighted run produces in play.
 *
 * So the weight follows the *decisive* geometry instead. It peaks where the recording shows a hand
 * committing to a turn — 20 to 90°, where the mean step is largest and the hand is idle least — and
 * falls away on both sides: below it lies tracking the clone already learns from half the recording,
 * above it lies behaviour we would rather it did not reproduce on purpose.
 */
class CloneTickWeights(
    /** Upper bound in degrees of every bucket but the last, which catches everything above. */
    val edges: FloatArray,

    /**
     * Weight per bucket, averaging exactly 1 over the ticks it was fitted on so the loss keeps the
     * magnitude it would have had with every tick counting once.
     */
    val weights: FloatArray,

    /**
     * Upper bounds in blocks, eyes to box centre, of every range bucket but the last; empty when the
     * table carries no range factor (every model trained before 2026-09-30).
     */
    val rangeEdges: FloatArray = FloatArray(0),

    /**
     * Factor per range bucket, multiplied onto [weights]; scaled so that the PRODUCT averages 1 over
     * the ticks the table was fitted on. See [RANGE_EMPHASIS].
     */
    val rangeWeights: FloatArray = floatArrayOf(1f)
) {

    /**
     * The weight of a tick [errorDegrees] off the target and [distance] blocks from it. A tick with
     * no target carries distance 0 (`FeatureSchema.fillFromGeometry`) and gets no range factor.
     */
    fun weightFor(errorDegrees: Float, distance: Float): Float {
        val range = if (distance > 0f) rangeWeights[bucketOf(distance, rangeEdges)] else 1f
        return weights[bucketOf(errorDegrees, edges)] * range
    }

    /** Whether this table weights by range at all. */
    val weighsRange: Boolean
        get() = rangeEdges.isNotEmpty()

    /** The bucket the objective leans on hardest, for reporting what a run is being pulled toward. */
    val heaviest: Int
        get() = weights.indices.maxBy { weights[it] }

    /** Degree range of [bucket], for reporting. */
    fun describe(bucket: Int) = when (bucket) {
        0 -> "under %.0f°".format(edges[0])
        edges.size -> "past %.0f°".format(edges.last())
        else -> "%.0f-%.0f°".format(edges[bucket - 1], edges[bucket])
    }

    companion object {

        /**
         * Bucket edges in degrees. Fixed rather than read off the profile's own quantiles: two
         * clones are only comparable if the same geometry falls in the same bucket for both, and
         * quantile edges would make each model's objective private to its recording.
         *
         * They follow what the aim is doing rather than equal shares of the data. Measured shares
         * come out near 54/22/12/6/3/4 percent, which is enough in every bucket to fit from:
         *   under 8   — the crosshair is already there; tracking, hand noise, and where the swings are
         *   8 to 20   — an ordinary correction, the bulk of aiming
         *   20 to 45  — a turn, deliberate and quick; the hand is idle on 9 % of these ticks
         *   45 to 90  — a large turn, the widest mean step in the recording at 16°
         *   90 to 135 — half spin, and already half disengagement
         *   past 135  — the target is behind the player and mostly stays there
         */
        val EDGES = floatArrayOf(8f, 20f, 45f, 90f, 135f)

        /**
         * Relative emphasis per bucket of [EDGES], before the average is taken back to 1.
         *
         * Read as a shape rather than as six independent numbers: a gentle trough over the bulk, a
         * peak on the committed turn, and a roll-off past it. The two ends are the deliberate part.
         * The first bucket holds over half the recording *and* nearly every swing, so pushing it far
         * down starves the attack head — a run that put it at 0.63 measurably cost clicking. The last
         * is held below 1 on purpose: it is the only band where less attention is better than more,
         * because what it contains is a player who has stopped fighting.
         *
         * Being fixed rather than derived from the counts also makes a thin band safe. Inverse
         * frequency has to be capped or a bucket the player barely entered decides whole batches by
         * itself; a fixed multiple cannot run away no matter how few ticks land in it.
         */
        val EMPHASIS = floatArrayOf(0.85f, 1f, 1.6f, 2f, 1f, 0.5f)

        /** Range bucket bounds in blocks, eyes to box centre: 0-1, 1-2 ... 5-6, 6+. `HitboxHits`'s. */
        val RANGE_EDGES = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        /**
         * Relative emphasis per range bucket of [RANGE_EDGES], before scaling (2026-09-30).
         *
         * A GUESS in shape, fitted to one measurement and to be judged only by the per-range rollout:
         * `8gail` against the player on 121 held-out fights, share of ticks on the box, clone minus
         * player per bucket: +6.8, +5.5, -0.1, -3.1, -6.8, -12.7 and -5 to -7 past 6. The clone is
         * better than the hand close and worse from the edge of reach out, the gap was already in
         * the imitation model before any reinforcement (46 % against 63 % at 3-4 blocks in its own
         * rollouts), and a reward reweighting on top (`CloneReinforce.Options.rangeMatching`) could
         * not move it. Close ticks are the commonest; this tilts the loss off them. Past 6 blocks
         * stays mild: much of it is a player walking away, which the angle table already scores
         * down when it is also turned away.
         */
        val RANGE_EMPHASIS = floatArrayOf(0.8f, 0.9f, 1f, 1.3f, 1.6f, 1.6f, 1.2f)

        /**
         * Pairs [EMPHASIS] with the profile's own bucket occupancy so the table averages 1 over it.
         *
         * The shape is fixed but the average is not: a profile that spent more of its time in a
         * heavily weighted band would otherwise inflate the whole loss, and the number a run reports
         * would stop being comparable with the next one. Counted over the training split alone, for
         * the same reason the feature normalization is — a count taken over the held-out data folds
         * a property of it into the objective, and the held-out loss stops being a measurement.
         */
        fun fit(
            tensors: List<FeatureSchema.RowSource>,
            edges: FloatArray = EDGES,
            /** Add the [RANGE_EMPHASIS] factor on top of the angle table. */
            byRange: Boolean = false
        ): CloneTickWeights {
            val counts = countBuckets(tensors, edges)
            val total = counts.sum()
            if (total == 0L) {
                return CloneTickWeights(edges, FloatArray(counts.size) { 1f })
            }

            // Falls back to flat emphasis for a caller that passed its own edges, since the shape
            // above is paired with EDGES and means nothing against a different set of boundaries.
            val weights = FloatArray(counts.size) { EMPHASIS.getOrElse(it) { 1f } }

            var mean = 0.0
            for (bucket in weights.indices) {
                mean += weights[bucket].toDouble() * counts[bucket] / total
            }

            val scale = if (mean > 0.0) (1.0 / mean).toFloat() else 1f
            val angleWeights = FloatArray(weights.size) { weights[it] * scale }
            if (!byRange) {
                return CloneTickWeights(edges, angleWeights)
            }

            // The range factor is scaled against the angle table it multiplies, so the product -
            // what a tick actually counts for - still averages 1 over the training ticks.
            var product = 0.0
            var ticks = 0L
            for (tensor in tensors) {
                val rows = tensor.featureRows
                for (i in 0 until tensor.rowCount) {
                    val distance = rows[i * FEATURES + F_DISTANCE]
                    val range = if (distance > 0f) RANGE_EMPHASIS[bucketOf(distance, RANGE_EDGES)] else 1f
                    product += angleWeights[bucketOf(errorOf(rows, i), edges)].toDouble() * range
                    ticks++
                }
            }
            val rangeScale = if (product > 0.0) (ticks / product).toFloat() else 1f
            return CloneTickWeights(
                edges,
                angleWeights,
                RANGE_EDGES.copyOf(),
                FloatArray(RANGE_EMPHASIS.size) { RANGE_EMPHASIS[it] * rangeScale }
            )
        }

        /**
         * Ticks per bucket, read from the raw feature rows.
         *
         * Raw degrees, before standardization: an edge given in standard deviations would stand for
         * a different angle on every profile, and for no fixed angle at all on one whose delta-yaw
         * column varied too little to be scaled by its own spread.
         *
         * A tick with no target has no error to bucket and carries a zero delta yaw, so it lands in
         * the first bucket. That is the intent — moving around between fights is behaviour the clone
         * still has to produce, so it is not scored down to nothing, but it needs no emphasis to be
         * learnt either.
         */
        private fun countBuckets(tensors: List<FeatureSchema.RowSource>, edges: FloatArray): LongArray {
            val counts = LongArray(edges.size + 1)

            for (tensor in tensors) {
                val rows = tensor.featureRows
                for (i in 0 until tensor.rowCount) {
                    counts[bucketOf(errorOf(rows, i), edges)]++
                }
            }

            return counts
        }

        private fun bucketOf(errorDegrees: Float, edges: FloatArray): Int {
            for (bucket in edges.indices) {
                if (errorDegrees < edges[bucket]) {
                    return bucket
                }
            }
            return edges.size
        }

        /**
         * The TOTAL angle to the target, `hypot(dYaw, dPitch)` — not the yaw error alone.
         *
         * Weighting by yaw only meant a "look down" situation — large pitch error, small yaw error,
         * e.g. an opponent at your feet — landed in the lightest (under-8-degree) bucket and was
         * barely trained on. That is measured: 16.5% of combat needs to look down past 15 degrees,
         * yet the clone under-produced the large pitch flicks (mean pitch step 1.3 vs the player's
         * 2.2) and connected on 14% of hits at 3-4 blocks against the player's 35%. Bucketing by
         * the combined angle gives a pitch-heavy tick the same emphasis a yaw-heavy one of the same
         * magnitude gets. Callers must feed [weightFor] the same combined angle.
         */
        fun errorOf(rows: FloatArray, i: Int): Float = hypot(
            rows[i * FEATURES + F_DELTA_YAW],
            rows[i * FEATURES + F_DELTA_PITCH]
        )
    }
}
