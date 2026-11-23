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

package net.ccbluex.liquidbounce.features.module.modules.movement.nav

import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavPath
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavRouteLine
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.asBlockSource
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * A route turned into the line it will actually be walked along.
 *
 * The route the search produces is a chain of block centres, and drawing it as such shows the
 * lattice rather than the journey: a staircase of right angles nobody walks. The executor already
 * cuts those corners — it steers at the far end of a straight run — so the picture worth drawing is
 * the smoothed line, not the nodes it was derived from.
 *
 * Also carries the distance along itself for every point, which is what lets the ribbon fade with
 * progress and animate along its own length without recomputing anything per frame.
 */
class NavRouteRibbon private constructor(
    /** Points of the smoothed line, in world space. */
    val points: List<Vec3>,

    /** Distance from the start of the route to each point, in blocks. */
    val travelled: DoubleArray,

    /** Index into the original path each point came from, for matching progress to geometry. */
    val sourceIndex: IntArray,
) {

    val length: Double get() = if (travelled.isEmpty()) 0.0 else travelled[travelled.size - 1]

    val isEmpty: Boolean get() = points.size < 2

    companion object {

        /**
         * Wraps a line that is already smooth — a glide, whose points are hundreds of blocks apart
         * and were never on a lattice to begin with.
         */
        fun of(points: List<Vec3>): NavRouteRibbon? {
            if (points.size < 2) return null
            return NavRouteRibbon(points, cumulative(points), IntArray(points.size))
        }

        /**
         * Builds the ribbon for a path, or null when there is nothing to draw.
         *
         * The shape comes from [NavRouteLine], which is the executor's own idea of where the body
         * will go — every stretch of the lattice it can cut across, replaced by the line it cuts.
         * Deriving the picture from anything else is how the drawn route came to disagree with the
         * walked one: the line showed the search's staircase while the player watched the character
         * cross the room diagonally.
         *
         * Cached on the path it was built from. A route changes when it is replanned, which is
         * seldom; the line-of-sight work behind it is far too much to redo sixty times a second.
         */
        fun of(path: NavPath): NavRouteRibbon? {
            if (path.isEmpty) return null
            if (cachedPath === path) return cached

            val built = build(path)
            cachedPath = path
            cached = built
            return built
        }

        private fun build(path: NavPath): NavRouteRibbon? {
            val corners = NavRouteLine.corners(path, NavWorld.reader().asBlockSource())

            val coarse = ArrayList<Vec3>(corners.size * 2)
            val coarseIndex = ArrayList<Int>(corners.size * 2)
            for (corner in corners.indices) {
                val index = corners[corner]
                if (corner > 0) {
                    subdivide(path, corners[corner - 1], index, coarse, coarseIndex)
                }
                coarse.add(pointAt(path, index))
                coarseIndex.add(index)
            }
            if (coarse.size < 2) return null

            val smoothed = smooth(coarse, coarseIndex)
            return NavRouteRibbon(smoothed.first, cumulative(smoothed.first), smoothed.second)
        }

        /**
         * Fills a long straight leg with points along it, so the corner cutting below rounds the
         * corner rather than the whole leg.
         *
         * Chaikin moves each point a quarter of the way along its neighbours, which on a
         * twelve-block diagonal would bow the drawn line more than a block away from the straight
         * one the body walks. Points every [RESAMPLE_STEP] cap that bow at a fraction of a block,
         * which is about what momentum rounds a corner by anyway.
         */
        private fun subdivide(
            path: NavPath,
            fromIndex: Int,
            toIndex: Int,
            points: MutableList<Vec3>,
            indices: MutableList<Int>,
        ) {
            val from = pointAt(path, fromIndex)
            val to = pointAt(path, toIndex)
            val steps = (from.distanceTo(to) / RESAMPLE_STEP).toInt()

            for (step in 1 until steps) {
                points.add(from.lerp(to, step.toDouble() / steps))
                // The colour of a leg is the colour of the move arriving at its far end.
                indices.add(toIndex)
            }
        }

        /** Middle of a node's block, lifted just clear of the floor it sits on. */
        private fun pointAt(path: NavPath, index: Int): Vec3 = Vec3(
            path.xAt(index) + 0.5,
            path.yAt(index) + LIFT,
            path.zAt(index) + 0.5,
        )

        /**
         * Chaikin corner cutting, which turns the lattice's right angles into the arcs a body
         * actually travels.
         *
         * Each pass replaces every corner with two points a quarter and three quarters along its
         * legs. Two passes is enough to read as a curve and cheap enough to redo whenever the route
         * changes, which is rarely.
         */
        private fun smooth(points: List<Vec3>, indices: List<Int>): Pair<List<Vec3>, IntArray> {
            var current = points
            var currentIndices = indices

            repeat(SMOOTHING_PASSES) {
                if (current.size < 3) return@repeat

                val next = ArrayList<Vec3>(current.size * 2)
                val nextIndices = ArrayList<Int>(current.size * 2)

                next.add(current.first())
                nextIndices.add(currentIndices.first())

                for (i in 0 until current.size - 1) {
                    val from = current[i]
                    val to = current[i + 1]
                    next.add(from.lerp(to, QUARTER))
                    next.add(from.lerp(to, THREE_QUARTERS))
                    nextIndices.add(currentIndices[i])
                    nextIndices.add(currentIndices[i + 1])
                }

                next.add(current.last())
                nextIndices.add(currentIndices.last())

                current = next
                currentIndices = nextIndices
            }

            return current to currentIndices.toIntArray()
        }

        private fun cumulative(points: List<Vec3>): DoubleArray {
            val travelled = DoubleArray(points.size)
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val point = points[i]
                val dx = point.x - previous.x
                val dy = point.y - previous.y
                val dz = point.z - previous.z
                travelled[i] = travelled[i - 1] + sqrt(dx * dx + dy * dy + dz * dz)
            }
            return travelled
        }

        /** The last ribbon built, kept so a stationary route is not rebuilt every frame. */
        private var cachedPath: NavPath? = null
        private var cached: NavRouteRibbon? = null

        /** Height above the block's floor the ribbon lies at. */
        const val LIFT = 0.06

        /** Blocks between points inserted along a straight leg before corner cutting. */
        private const val RESAMPLE_STEP = 1.5

        private const val SMOOTHING_PASSES = 2
        private const val QUARTER = 0.25
        private const val THREE_QUARTERS = 0.75
    }
}
