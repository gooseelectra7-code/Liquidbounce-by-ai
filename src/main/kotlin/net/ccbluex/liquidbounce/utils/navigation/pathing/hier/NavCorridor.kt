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

package net.ccbluex.liquidbounce.utils.navigation.pathing.hier

import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.NavGoal
import net.minecraft.core.BlockPos
import kotlin.math.sqrt

/**
 * The coarse route, as a line the detailed search can be measured against.
 *
 * Distance to the goal in a straight line is what makes A* walk into the side of a mountain: every
 * step toward the rock looks like progress right up until it stops. This replaces that estimate with
 * "get to the corridor, then follow it", which is the same information a person reads off the
 * landscape and the reason they do not need to try the mountain first.
 */
class NavCorridor(
    points: List<BlockPos>,

    /** Cheapest a block of travel can cost, so the estimate comes out in ticks. */
    private val costPerBlock: Double,
) {

    /** The corridor's turning points, from the start to the destination. */
    val points: List<BlockPos> = simplify(points)

    /** Distance from each point to the end of the corridor, along the corridor. */
    private val remaining = DoubleArray(this.points.size)

    /** Total length of the corridor in blocks. */
    val length: Double get() = if (remaining.isEmpty()) 0.0 else remaining[0]

    init {
        for (index in this.points.size - 2 downTo 0) {
            remaining[index] = remaining[index + 1] + distance(this.points[index], this.points[index + 1])
        }
    }

    /**
     * Estimated blocks from a position to the destination, by way of the corridor.
     *
     * Measured to the nearest point on the line and then along it, so leaving the corridor costs
     * exactly what it takes to come back plus what is left — which is the honest price of a detour
     * and, unlike a hard corridor, still allows one when the terrain turns out to demand it.
     */
    fun estimateBlocks(x: Int, y: Int, z: Int): Double {
        if (points.size < 2) return 0.0

        // The segment is chosen by how near it is, and only then does its remaining length count.
        //
        // Choosing whichever segment gives the smallest total instead looks equivalent and is not:
        // the last segment always offers "the straight line to the destination, plus nothing left
        // to walk", so the smallest total is always the straight line and the corridor says exactly
        // nothing. That is a whole afternoon's worth of lesson in one comparison.
        var nearest = Double.POSITIVE_INFINITY
        var aheadOfNearest = 0.0

        for (index in 0 until points.size - 1) {
            val from = points[index]
            val to = points[index + 1]

            val segmentX = (to.x - from.x).toDouble()
            val segmentY = (to.y - from.y).toDouble()
            val segmentZ = (to.z - from.z).toDouble()
            val lengthSq = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ

            val toPointX = x - from.x.toDouble()
            val toPointY = y - from.y.toDouble()
            val toPointZ = z - from.z.toDouble()

            val along = if (lengthSq < EPSILON) {
                0.0
            } else {
                ((toPointX * segmentX + toPointY * segmentY + toPointZ * segmentZ) / lengthSq)
                    .coerceIn(0.0, 1.0)
            }

            val offX = toPointX - segmentX * along
            val offY = toPointY - segmentY * along
            val offZ = toPointZ - segmentZ * along
            val detour = sqrt(offX * offX + offY * offY + offZ * offZ)

            if (detour < nearest) {
                nearest = detour
                aheadOfNearest = remaining[index] - along * sqrt(lengthSq)
            }
        }
        return nearest * OFF_CORRIDOR_PRICE + aheadOfNearest
    }

    /** The same estimate, priced in ticks. */
    fun estimateTicks(x: Int, y: Int, z: Int): Double = estimateBlocks(x, y, z) * costPerBlock

    /**
     * How far a position is from the corridor itself, ignoring what is left of it.
     *
     * Used to decide whether a corridor built a while ago still describes where we are: guidance
     * does not go stale from age, only from being somewhere else.
     */
    fun detourFrom(x: Int, y: Int, z: Int): Double {
        if (points.size < 2) return Double.POSITIVE_INFINITY

        var nearest = Double.POSITIVE_INFINITY
        for (index in 0 until points.size - 1) {
            nearest = minOf(nearest, distanceToSegment(points[index], points[index + 1], x, y, z))
        }
        return nearest
    }

    private fun distanceToSegment(from: BlockPos, to: BlockPos, x: Int, y: Int, z: Int): Double {
        val segmentX = (to.x - from.x).toDouble()
        val segmentY = (to.y - from.y).toDouble()
        val segmentZ = (to.z - from.z).toDouble()
        val lengthSq = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ

        val toPointX = x - from.x.toDouble()
        val toPointY = y - from.y.toDouble()
        val toPointZ = z - from.z.toDouble()

        val along = if (lengthSq < EPSILON) {
            0.0
        } else {
            ((toPointX * segmentX + toPointY * segmentY + toPointZ * segmentZ) / lengthSq).coerceIn(0.0, 1.0)
        }

        val offX = toPointX - segmentX * along
        val offY = toPointY - segmentY * along
        val offZ = toPointZ - segmentZ * along
        return sqrt(offX * offX + offY * offY + offZ * offZ)
    }

    override fun toString(): String = "NavCorridor(${points.size} points, %.0f blocks)".format(length)

    private fun distance(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        /**
         * Turning points kept.
         *
         * Every estimate walks the whole line, and it is asked for once per expanded node, so the
         * line has to stay short. A very long route is sampled down instead: at this scale its
         * distant end is a direction, not a plan, and it will be re-planned long before we get there.
         */
        const val MAX_POINTS = 48

        /**
         * What a block of straying from the corridor is charged, in blocks.
         *
         * Priced above one because a step away from the corridor is not merely a step to be walked
         * back: the corridor runs where the terrain allows travel, so leaving it is usually the
         * beginning of a dead end. Charging it at cost makes the near side of a wall only a few per
         * cent worse than the way round, which A* will happily explore in full before believing it.
         */
        const val OFF_CORRIDOR_PRICE = 3.0

        const val EPSILON = 1.0E-9

        fun simplify(points: List<BlockPos>): List<BlockPos> {
            if (points.size <= MAX_POINTS) return points

            val stride = (points.size - 1).toDouble() / (MAX_POINTS - 1)
            return (0 until MAX_POINTS).map { index ->
                points[(index * stride).toInt().coerceAtMost(points.size - 1)]
            }
        }
    }
}

/**
 * A goal that keeps its own definition of arrival but takes its sense of direction from a corridor.
 *
 * Wrapping rather than replacing is what makes the hierarchical layer safe to switch on: the search,
 * the executor and the consumer all still see the goal they asked for, and the only thing that
 * changes is which way the search looks first.
 *
 * The estimate is the larger of the two, since the straight line is a lower bound the corridor
 * should never undercut. It is not admissible — a corridor through room centres is longer than the
 * route a body actually walks — and that is the deliberate trade this layer exists to make, the same
 * one the weighted heuristic already makes on every search.
 */
class GuidedGoal(
    private val inner: NavGoal,
    private val corridor: NavCorridor,
) : NavGoal {

    override fun isGoal(x: Int, y: Int, z: Int): Boolean = inner.isGoal(x, y, z)

    override fun heuristic(x: Int, y: Int, z: Int): Double {
        val direct = inner.heuristic(x, y, z)
        // Capped, so a corridor that has gone badly wrong — routed round three sides of a mountain
        // that has since been tunnelled through — costs some search time rather than the route.
        val guided = corridor.estimateTicks(x, y, z).coerceAtMost(direct * MAX_DETOUR_TRUST)
        return maxOf(direct, guided)
    }

    override fun targetPosition(): BlockPos? = inner.targetPosition()

    override fun describe(): String = "${inner.describe()} via ${corridor.points.size}-point corridor"

    private companion object {
        /**
         * How far round the houses the corridor is believed, as a multiple of the straight line.
         *
         * Real detours are rarely worse than this — a wall, a canyon, a lake all come in well under
         * it — so the cap almost never binds. What it rules out is the pathological case where a
         * stale or badly abstracted corridor claims a route twenty times longer than the truth and
         * the search dutifully follows it.
         */
        const val MAX_DETOUR_TRUST = 6.0
    }
}
