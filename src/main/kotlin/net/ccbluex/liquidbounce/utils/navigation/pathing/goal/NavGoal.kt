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

package net.ccbluex.liquidbounce.utils.navigation.pathing.goal

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What a search is trying to reach, and how far away it thinks any given cell is.
 *
 * [heuristic] must never overestimate the true remaining cost — that is the single condition under
 * which A* returns optimal paths. Every implementation here builds its estimate out of
 * [NavTiming.MIN_COST_PER_BLOCK], the cheapest a block of travel can possibly be.
 *
 * Implementations are read from the search thread and must be immutable, or at least safe to read
 * concurrently. Goals that follow a moving target re-snapshot at the service layer instead of
 * mutating underneath a running search.
 */
interface NavGoal {

    /** Is a player standing at this block position done? */
    fun isGoal(x: Int, y: Int, z: Int): Boolean

    /** Admissible lower bound, in ticks, on the remaining cost from this block position. */
    fun heuristic(x: Int, y: Int, z: Int): Double

    /** Human-readable description for the debug overlay and chat. */
    fun describe(): String

    /**
     * The single position this goal is centred on, or null when it has none.
     *
     * The router needs somewhere concrete to aim a leg at — a glide is a line with an altitude
     * profile along it, and a goal that is only a predicate over the world does not give it one.
     * Such goals simply never get a flight, which costs nothing but a longer walk.
     */
    fun targetPosition(): BlockPos? = null

    fun isGoal(pos: BlockPos): Boolean = isGoal(pos.x, pos.y, pos.z)

    fun heuristic(pos: BlockPos): Double = heuristic(pos.x, pos.y, pos.z)
}

/**
 * Octile distance in blocks between two columns.
 *
 * Tighter than Euclidean on an eight-connected grid and still a lower bound, because a diagonal
 * step is priced at exactly √2 straight steps.
 */
internal fun octileDistance(dx: Int, dz: Int): Double {
    val ax = abs(dx)
    val az = abs(dz)
    val diagonal = min(ax, az)
    val straight = max(ax, az) - diagonal
    return straight + diagonal * NavTiming.DIAGONAL_MULTIPLIER
}

/**
 * The standard estimate: horizontal travel and vertical travel are each a lower bound on the
 * remaining cost, so the larger of the two is a lower bound as well — and a tighter one than either.
 *
 * Descending contributes nothing, since falling is close to free and counting it would risk
 * overestimating.
 */
internal fun travelHeuristic(dx: Int, dy: Int, dz: Int): Double {
    val horizontal = octileDistance(dx, dz) * NavTiming.MIN_COST_PER_BLOCK
    val vertical = if (dy > 0) dy * NavTiming.MIN_COST_PER_ASCENT else 0.0
    return max(horizontal, vertical)
}

/**
 * Estimate for a goal satisfied anywhere within [slack] blocks of a point.
 *
 * Built on [travelHeuristic] rather than on the straight-line distance these goals used to measure,
 * and the difference is not cosmetic: a straight line prices a descent at the walking floor, while
 * falling is far cheaper than walking — thirty-two blocks down take about thirty-two ticks against
 * an estimate of sixty-eight. An estimate above the truth does not merely slow a search down, it
 * stops it returning the best route, and with a closed set that never reopens it can miss the cheap
 * way down entirely.
 */
internal fun approachEstimate(dx: Int, dy: Int, dz: Int, slack: Double): Double {
    val estimate = travelHeuristic(dx, dy, dz)
    return max(0.0, estimate - slack * NavTiming.MIN_COST_PER_BLOCK)
}

/** Stand exactly on one block position. */
class GoalBlock(private val x: Int, private val y: Int, private val z: Int) : NavGoal {

    constructor(pos: BlockPos) : this(pos.x, pos.y, pos.z)

    override fun isGoal(x: Int, y: Int, z: Int): Boolean = x == this.x && y == this.y && z == this.z

    override fun heuristic(x: Int, y: Int, z: Int): Double =
        travelHeuristic(this.x - x, this.y - y, this.z - z)

    override fun targetPosition(): BlockPos = BlockPos(x, y, z)

    override fun describe(): String = "block ($x, $y, $z)"
}

/** Reach a column, at any height. Useful for "head that way" travel. */
class GoalXZ(private val x: Int, private val z: Int) : NavGoal {

    override fun isGoal(x: Int, y: Int, z: Int): Boolean = x == this.x && z == this.z

    override fun heuristic(x: Int, y: Int, z: Int): Double =
        octileDistance(this.x - x, this.z - z) * NavTiming.MIN_COST_PER_BLOCK

    override fun describe(): String = "column ($x, $z)"
}

/** Reach a height, anywhere. Digging straight down to diamond level, for instance. */
class GoalYLevel(private val y: Int) : NavGoal {

    override fun isGoal(x: Int, y: Int, z: Int): Boolean = y == this.y

    override fun heuristic(x: Int, y: Int, z: Int): Double {
        val dy = this.y - y
        return if (dy > 0) dy * NavTiming.MIN_COST_PER_ASCENT else 0.0
    }

    override fun describe(): String = "y = $y"
}

/** Get within [radius] blocks of a position. */
class GoalNear(
    private val x: Int,
    private val y: Int,
    private val z: Int,
    private val radius: Double,
) : NavGoal {

    constructor(pos: BlockPos, radius: Double) : this(pos.x, pos.y, pos.z, radius)

    private val radiusSq = radius * radius

    override fun isGoal(x: Int, y: Int, z: Int): Boolean {
        val dx = (this.x - x).toDouble()
        val dy = (this.y - y).toDouble()
        val dz = (this.z - z).toDouble()
        return dx * dx + dy * dy + dz * dz <= radiusSq
    }

    override fun heuristic(x: Int, y: Int, z: Int): Double {
        // Zero wherever the goal is already satisfied, so the estimate and the test can never
        // disagree about a cell that needs nothing more done to it.
        if (isGoal(x, y, z)) return 0.0
        // The radius is slack: cells just outside the sphere are estimated at nearly nothing rather
        // than at the distance to its centre.
        return approachEstimate(this.x - x, this.y - y, this.z - z, radius)
    }

    override fun targetPosition(): BlockPos = BlockPos(x, y, z)

    override fun describe(): String = "within $radius of ($x, $y, $z)"
}

/**
 * Stand somewhere a block can be reached from — the goal AutoMine and any "interact with that
 * block" consumer actually wants.
 *
 * Being *on* the target counts too, which matters for blocks you mine from above.
 */
class GoalAdjacentTo(
    private val x: Int,
    private val y: Int,
    private val z: Int,
    private val reach: Double = DEFAULT_REACH,
) : NavGoal {

    constructor(pos: BlockPos, reach: Double = DEFAULT_REACH) : this(pos.x, pos.y, pos.z, reach)

    private val reachSq = reach * reach

    override fun isGoal(x: Int, y: Int, z: Int): Boolean {
        // Measured from the player's eyes to the block centre, the same test the executor will make
        // when it decides whether it can actually start mining.
        val dx = (this.x + 0.5) - (x + 0.5)
        val dy = (this.y + 0.5) - (y + EYE_HEIGHT)
        val dz = (this.z + 0.5) - (z + 0.5)
        return dx * dx + dy * dy + dz * dz <= reachSq
    }

    override fun heuristic(x: Int, y: Int, z: Int): Double {
        // Measured with the same geometry as [isGoal], so that no cell which already satisfies the
        // goal carries a positive estimate — the two used to disagree by the height of a pair of
        // eyes, which is enough to make the search prefer the costlier of two standing spots.
        if (isGoal(x, y, z)) return 0.0
        return approachEstimate(this.x - x, this.y - y, this.z - z, reach)
    }

    override fun targetPosition(): BlockPos = BlockPos(x, y, z)

    override fun describe(): String = "reach of ($x, $y, $z)"

    companion object {
        /** Vanilla survival block reach. */
        const val DEFAULT_REACH = 4.5

        private const val EYE_HEIGHT = 1.62
    }
}

/** Get at least [minDistance] blocks away from a position. The inverse of [GoalNear]. */
class GoalRunAway(
    private val x: Int,
    private val y: Int,
    private val z: Int,
    private val minDistance: Double,
) : NavGoal {

    constructor(pos: BlockPos, minDistance: Double) : this(pos.x, pos.y, pos.z, minDistance)

    private val minDistanceSq = minDistance * minDistance

    override fun isGoal(x: Int, y: Int, z: Int): Boolean = distanceSq(x, z) >= minDistanceSq

    override fun heuristic(x: Int, y: Int, z: Int): Double {
        val missing = minDistance - sqrt(distanceSq(x, z))
        if (missing <= 0.0) return 0.0
        return missing * NavTiming.MIN_COST_PER_BLOCK
    }

    /**
     * Measured across the ground only, in both the test and the estimate.
     *
     * Height is not distance from something you are running from — dropping into the ravine below a
     * creeper does not get you away from it — and counting it would also make the estimate a lie:
     * a fall covers blocks for a fraction of the walking floor this heuristic prices them at, so a
     * route that gained its distance by falling would be estimated above what it really costs.
     */
    private fun distanceSq(x: Int, z: Int): Double {
        val dx = (this.x - x).toDouble()
        val dz = (this.z - z).toDouble()
        return dx * dx + dz * dz
    }

    override fun describe(): String = "at least $minDistance from ($x, $y, $z)"
}

/**
 * Reach whichever of several goals comes first.
 *
 * The heuristic is the minimum over the parts, which stays admissible because reaching the whole
 * composite can never cost more than reaching its cheapest member.
 */
class GoalComposite(private val goals: List<NavGoal>) : NavGoal {

    constructor(vararg goals: NavGoal) : this(goals.toList())

    init {
        require(goals.isNotEmpty()) { "GoalComposite needs at least one goal" }
    }

    override fun isGoal(x: Int, y: Int, z: Int): Boolean = goals.any { it.isGoal(x, y, z) }

    override fun heuristic(x: Int, y: Int, z: Int): Double {
        var best = Double.POSITIVE_INFINITY
        for (goal in goals) {
            val h = goal.heuristic(x, y, z)
            if (h < best) best = h
        }
        return best
    }

    override fun describe(): String = goals.joinToString(" or ") { it.describe() }
}
