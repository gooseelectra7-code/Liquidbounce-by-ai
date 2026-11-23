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

package net.ccbluex.liquidbounce.utils.navigation.pathing.cost

import kotlin.math.sqrt

/**
 * Base movement timings, in ticks. Every cost and every heuristic in the engine is denominated here,
 * so a path's total cost is a real prediction of how long walking it will take.
 *
 * The numbers come from vanilla ground speeds: walking is ~4.317 m/s and sprinting ~5.612 m/s, i.e.
 * 0.2806 blocks per tick when sprinting.
 *
 * ## The admissibility floor
 *
 * A* only returns optimal paths while the heuristic never overestimates. Since heuristics here are
 * "distance times the cheapest conceivable per-block cost", that cheapest cost has to account for
 * *every* speed-up the cost model can apply — most importantly ice. [MIN_COST_PER_BLOCK] is that
 * floor, and no edge the cost model produces may ever fall below it per *horizontal* block, nor
 * below [MIN_COST_PER_ASCENT] per block gained. Descent is exempt and has to be: a fall is far
 * cheaper per block than any step, which is why no heuristic here charges for going down.
 * `GroundPlannerTest` asserts the floor exhaustively over random terrain and `NavGoalTest` asserts
 * that no goal estimates a descent above what it costs. If you add a faster terrain multiplier,
 * lower this value with it or the search silently starts returning suboptimal paths.
 */
object NavTiming {

    /** Ticks to sprint one block on flat ground. The fastest ordinary ground movement. */
    const val SPRINT_PER_BLOCK = 3.56

    /** Ticks to walk one block on flat ground, i.e. sprinting disabled. */
    const val WALK_PER_BLOCK = 4.63

    /** Extra ticks a jump costs on top of the horizontal movement it overlaps with. */
    const val JUMP_PENALTY = 3.0

    /** Ticks per block climbing a ladder or vine upwards. */
    const val CLIMB_UP_PER_BLOCK = 8.0

    /** Ticks per block descending a ladder or vine. */
    const val CLIMB_DOWN_PER_BLOCK = 4.0

    /** Ticks per block of swimming. */
    const val SWIM_PER_BLOCK = 9.0

    /** Ticks to place one block, including the rotation and the swing. */
    const val PLACE = 4.0

    /** Diagonal steps cover this many blocks, so they are priced at this multiple of a straight step. */
    @JvmField
    val DIAGONAL_MULTIPLIER = sqrt(2.0)

    /**
     * Fastest terrain multiplier the cost model may apply. Blue ice is the quickest surface in the
     * game; nothing else moves a player faster per block.
     */
    const val FASTEST_TERRAIN_MULTIPLIER = 0.6

    /**
     * Lower bound on what one block of displacement can possibly cost.
     *
     * This is the constant every admissible heuristic multiplies distance by. Making it smaller
     * costs search time; making it larger than a reachable edge cost breaks optimality.
     */
    @JvmField
    val MIN_COST_PER_BLOCK = SPRINT_PER_BLOCK * FASTEST_TERRAIN_MULTIPLIER

    /**
     * Lower bound on what gaining one block of height can cost.
     *
     * Deliberately equal to [MIN_COST_PER_BLOCK]: rising a block always involves at least one move,
     * and no move is cheaper than the floor. A tighter bound would search less, but every candidate
     * for one (jump cost, pillar cost) can be undercut by some terrain multiplier.
     */
    @JvmField
    val MIN_COST_PER_ASCENT = MIN_COST_PER_BLOCK
}
