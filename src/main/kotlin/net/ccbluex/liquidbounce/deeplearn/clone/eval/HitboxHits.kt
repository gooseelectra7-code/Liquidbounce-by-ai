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

import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.math.firstHit
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/**
 * How often the view ray was inside the target's hitbox, overall and by range.
 *
 * Every other statistic here describes the *shape* of the aim; this one says whether it would have
 * connected, which is what the aim is for. Splitting it by whole blocks of range is what makes it
 * readable: holding a crosshair on a target two blocks away and holding it on the same target at
 * seven are different behaviours, and one average over both hides which of them moved.
 *
 * Both streams are scored against the box the recording stored for that tick, so the figure for
 * generated aim answers "would this have connected with the target the player was really fighting"
 * rather than with a stand-in for it.
 */
class HitboxHits {

    private val hits = IntArray(BUCKETS)
    private val ticks = IntArray(BUCKETS)

    /** Ticks that had a box to hit. Everything else is excluded rather than counted as a miss. */
    val samples: Int get() = ticks.sum()

    val overall: Float get() = share(hits.sum(), samples)

    /**
     * Scores one tick, [aim] being where the crosshair sat at the start of it — the same instant the
     * delta channels are measured from, so the two describe the same moment.
     *
     * A tick with no target is not a miss, it is not a measurement at all: there was no box, and
     * calling it a miss would let a profile's idle stretches set the figure.
     */
    fun record(tick: AimTick, aim: Rotation) {
        if (!tick.hasTarget || tick.targetWidth <= 0f || tick.targetHeight <= 0f) {
            return
        }

        val bucket = tick.distance.toInt().coerceIn(0, BUCKETS - 1)
        ticks[bucket]++
        if (Companion.entersBox(tick, aim)) {
            hits[bucket]++
        }
    }

    /** The same share over the ticks inside [blocks] blocks, where a swing could have landed. */
    fun within(blocks: Int): Float {
        var hit = 0
        var total = 0

        for (bucket in 0 until blocks.coerceIn(0, BUCKETS)) {
            hit += hits[bucket]
            total += ticks[bucket]
        }
        return share(hit, total)
    }

    private fun share(hit: Int, total: Int) = if (total == 0) Float.NaN else hit.toFloat() / total

    companion object {

        /** Whole blocks of range the breakdown splits into; the last one is everything beyond. */
        private const val BUCKETS = 9

        /**
         * Rebuilds the tick's geometry with the eyes at the origin and reports whether the ray enters
         * the box.
         *
         * The recorded angles are world directions and a hitbox is axis-aligned whatever the target's
         * own facing, so sliding the pair until the eyes sit at zero leaves both exactly as they
         * stood. This is the recording's geometry rather than a small-angle stand-in for it, and it
         * needs nothing of the world the recording was made in.
         *
         * Shared with the rollout's feature fill, which has to answer the same question the recorder
         * answered when it wrote the training label. An angular threshold cannot: a box is far more
         * forgiving vertically than horizontally, so no single angle stands in for it.
         */
        fun entersBox(tick: AimTick, aim: Rotation): Boolean {
            if (!tick.hasTarget || tick.targetWidth <= 0f || tick.targetHeight <= 0f) {
                return false
            }

            val centre = Rotation(tick.targetYaw, tick.targetPitch)
                .directionVector
                .scale(tick.distance.toDouble())

            val box = AABB.ofSize(
                centre,
                tick.targetWidth.toDouble(),
                tick.targetHeight.toDouble(),
                tick.targetWidth.toDouble()
            )

            // Carried a box-diagonal past the centre, because the face the ray enters by can be that
            // much further out than the centre is, and a segment stopping short of it reads as a miss.
            val reach = tick.distance + hypot(tick.targetWidth, tick.targetHeight)
            return box.firstHit(Vec3.ZERO, aim.directionVector.scale(reach.toDouble())) != null
        }

        /**
         * The two streams side by side, one entry per block of range: `2-3 42.9/38.1`. Ranges the
         * player was never at are left out instead of printed as an empty percentage.
         */
        fun byRange(real: HitboxHits, generated: HitboxHits): String = (0 until BUCKETS)
            .filter { real.ticks[it] > 0 }
            .joinToString(", ") { bucket ->
                "%s %.1f/%.1f".format(
                    label(bucket),
                    real.share(real.hits[bucket], real.ticks[bucket]) * 100f,
                    generated.share(generated.hits[bucket], generated.ticks[bucket]) * 100f
                )
            }
            .ifEmpty { "nothing was tracked in this profile" }

        private fun label(bucket: Int) =
            if (bucket == BUCKETS - 1) "$bucket+" else "$bucket-${bucket + 1}"
    }
}
