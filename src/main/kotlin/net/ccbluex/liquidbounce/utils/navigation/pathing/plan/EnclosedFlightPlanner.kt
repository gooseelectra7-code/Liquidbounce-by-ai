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

package net.ccbluex.liquidbounce.utils.navigation.pathing.plan

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.ccbluex.liquidbounce.utils.navigation.pathing.route.NavLocomotion
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavEdgeBuffer
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavNeighborFunction
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearch
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchLimits
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchResult
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchWorkspace
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Routes a glide through space that has a roof on it.
 *
 * The other half of elytra travel, and a genuinely different problem from the open sky. Above open
 * terrain the only obstacle is the ground and a straight line with an altitude profile is the whole
 * answer; under a roof — the Nether, a cave system — there are walls in every direction and the
 * route has to go round them, which is a real three-dimensional search or nothing.
 *
 * ## Why a coarse lattice
 *
 * Searching this at block resolution would be hundreds of times the work for a route no better: a
 * glider is not threading one-block gaps at cruising speed, and a route that depends on it would be
 * unflyable anyway. Cells of [CELL] blocks are required to be *entirely* clear, so a route through
 * them has margin built in — the search cannot propose a gap the aircraft has no room to take.
 *
 * Unloaded space is not flyable here, unlike in the open sky. A ceiling overhead is exactly the
 * situation where guessing about what has not arrived yet flies into rock; the search stops at the
 * frontier instead and the route is planned again when the chunks land.
 */
object EnclosedFlightPlanner {

    /**
     * Statistics from the most recent search, for the debug overlay and the performance tests.
     *
     * A three-dimensional search is the one part of the engine that can quietly become expensive —
     * its frontier grows as a sphere — so what it cost is worth being able to look at.
     */
    @Volatile
    var lastSearch: NavSearchResult? = null
        private set

    /**
     * Plans a route through enclosed space, or returns null when there is none to be had.
     *
     * @param workspace reused search scratch; the caller owns it and must not share it across
     *   threads
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun plan(
        source: NavBlockSource,
        from: BlockPos,
        to: BlockPos,
        workspace: NavSearchWorkspace,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        timeoutNanos: Long = Long.MAX_VALUE,
        cancelled: (() -> Boolean)? = null,
    ): ElytraRoute? {
        val lattice = FlightLattice(source)

        // Both ends have to be lifted into the air before they are search nodes at all. A player
        // stands on the floor and a destination is a place to stand on it, so the cells those
        // positions fall in contain that floor a good part of the time — quantising them and
        // stopping there would refuse perfectly ordinary flights on nothing more than where the
        // lattice happens to be aligned.
        val start = lattice.firstFlyableAbove(anchorOf(from)) ?: return null
        val goal = lattice.firstFlyableAbove(anchorOf(to)) ?: return null
        if (start == goal) return null

        val result = NavSearch.search(
            start = start,
            goal = GoalBlock(BlockPos.getX(goal), BlockPos.getY(goal), BlockPos.getZ(goal)),
            neighbors = lattice,
            workspace = workspace,
            limits = NavSearchLimits(
                maxIterations = maxIterations,
                // A frontier in three dimensions grows as a sphere, so plain A* spends most of its
                // budget on a cavern's volume rather than on the way across it. Greed is worth far
                // more here than it is on the ground.
                heuristicWeight = HEURISTIC_WEIGHT,
                // Goals estimate in walking ticks, and a glider is several times quicker than that.
                heuristicScale = HEURISTIC_SCALE,
                timeoutNanos = timeoutNanos,
            ),
            // The workspace is shared with every other search in the service, and a request that
            // has been superseded must let go of it rather than run its budget out inside it.
            cancelled = cancelled,
        )
        lastSearch = result
        if (result.isEmpty) return null

        val waypoints = smooth(lattice, result.path)
        if (waypoints.size < 2) return null

        // Finish on the destination itself when the search actually got there; a partial route ends
        // where it ended and is planned onward from the landing.
        if (result.reachedGoal) waypoints.add(Vec3(to.x + 0.5, to.y.toDouble(), to.z + 0.5))

        val route = ElytraRoute(waypoints, reachedGoal = result.reachedGoal, enclosed = true)
        // A flight of a few cells is a take-off and a landing with nothing in between, and it costs
        // rockets to achieve nothing. Walking that is simply better.
        return route.takeIf { it.length >= MIN_WORTHWHILE_LENGTH }
    }

    /**
     * Drops the corners the lattice put in.
     *
     * A route made of lattice steps turns every few blocks whether or not anything is in the way,
     * and a glider tracing that loses speed at every turn for nothing. Each point is kept only when
     * the line past it would not clear, which leaves the straight runs straight and the turns only
     * where a wall actually demands one.
     */
    private fun smooth(lattice: FlightLattice, path: LongArray): MutableList<Vec3> {
        val points = ArrayList<Vec3>(path.size)
        points.add(centerOf(path[0]))

        var anchor = 0
        for (probe in 1 until path.size) {
            // Extend the straight run as far as it reaches, then commit the last point that cleared.
            if (lattice.isLineClear(centerOf(path[anchor]), centerOf(path[probe]))) continue

            appendDistinct(points, centerOf(path[probe - 1]))
            anchor = probe - 1
        }

        appendDistinct(points, centerOf(path[path.size - 1]))
        return points
    }

    /**
     * Adds a point unless it is the one already there.
     *
     * A step whose own line does not clear — a diagonal that grazes a wall inside the margin — would
     * otherwise commit its own anchor a second time, leaving a zero-length leg the executor would
     * count as instantly reached.
     */
    private fun appendDistinct(points: MutableList<Vec3>, point: Vec3) {
        if (points.isEmpty() || points.last().distanceToSqr(point) > MIN_LEG_SQ) points.add(point)
    }

    /** Block position of the corner of the cell a position falls in. */
    private fun anchorOf(pos: BlockPos): Long = BlockPos.asLong(
        Math.floorDiv(pos.x, CELL) * CELL,
        Math.floorDiv(pos.y, CELL) * CELL,
        Math.floorDiv(pos.z, CELL) * CELL,
    )

    /** Middle of a cell, which is where the route actually runs. */
    private fun centerOf(node: Long): Vec3 = Vec3(
        BlockPos.getX(node) + CELL / 2.0,
        BlockPos.getY(node) + CELL / 2.0,
        BlockPos.getZ(node) + CELL / 2.0,
    )

    /**
     * The search space: cells of clear air, twenty-six ways out of each.
     *
     * Cell clearance is memoised because the same cells are tested from every neighbour that could
     * enter them, and each test is sixty-four block reads.
     */
    private class FlightLattice(private val source: NavBlockSource) : NavNeighborFunction {

        private val flyable = Long2ByteOpenHashMap().apply { defaultReturnValue(UNTESTED) }

        override fun expand(node: Long, out: NavEdgeBuffer) {
            val x = BlockPos.getX(node)
            val y = BlockPos.getY(node)
            val z = BlockPos.getZ(node)

            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        addEdge(x, y, z, dx, dy, dz, out)
                    }
                }
            }
        }

        @Suppress("LongParameterList", "ReturnCount")
        private fun addEdge(x: Int, y: Int, z: Int, dx: Int, dy: Int, dz: Int, out: NavEdgeBuffer) {
            val target = BlockPos.asLong(x + dx * CELL, y + dy * CELL, z + dz * CELL)
            if (!isFlyable(target)) return

            // No cutting corners: a diagonal is only available when the cells it squeezes between
            // are clear as well, or the route clips whatever fills them.
            if (dx != 0 && !isFlyable(BlockPos.asLong(x + dx * CELL, y, z))) return
            if (dy != 0 && !isFlyable(BlockPos.asLong(x, y + dy * CELL, z))) return
            if (dz != 0 && !isFlyable(BlockPos.asLong(x, y, z + dz * CELL))) return

            val blocks = sqrt(((dx * dx + dy * dy + dz * dz) * CELL * CELL).toDouble())
            // Height has to be bought with a rocket, so it is priced well above the distance it
            // covers; losing height costs nothing beyond the travel itself.
            val climb = if (dy > 0) dy * CELL * CLIMB_SURCHARGE_PER_BLOCK else 0.0

            out.add(target, blocks * FLIGHT_TICKS_PER_BLOCK + climb, FLY_MOVE)
        }

        /**
         * The lowest clear cell at or above this one, within reach of a take-off.
         *
         * Null when the column is packed solid that far up, which is the honest answer for a
         * position buried in rock or roofed over immediately.
         */
        fun firstFlyableAbove(anchor: Long): Long? {
            val x = BlockPos.getX(anchor)
            val y = BlockPos.getY(anchor)
            val z = BlockPos.getZ(anchor)

            for (lift in 0..MAX_LIFT_CELLS) {
                val candidate = BlockPos.asLong(x, y + lift * CELL, z)
                if (isFlyable(candidate)) return candidate
            }
            return null
        }

        /** Is this whole cell clear air? */
        fun isFlyable(node: Long): Boolean {
            val cached = flyable.get(node)
            if (cached != UNTESTED) return cached == CLEAR

            val clear = testFlyable(node)
            flyable.put(node, if (clear) CLEAR else BLOCKED)
            return clear
        }

        private fun testFlyable(node: Long): Boolean {
            val originX = BlockPos.getX(node)
            val originY = BlockPos.getY(node)
            val originZ = BlockPos.getZ(node)

            for (offsetX in 0 until CELL) {
                for (offsetY in 0 until CELL) {
                    for (offsetZ in 0 until CELL) {
                        val navClass = source.navClass(
                            originX + offsetX,
                            originY + offsetY,
                            originZ + offsetZ,
                        )
                        // Air and nothing else. Water ends a glide, lava ends more than that, and
                        // unloaded space under a roof is the one place guessing is unaffordable.
                        if (navClass != NavBlockClass.AIR) return false
                    }
                }
            }
            return true
        }

        /**
         * Could the aircraft fly this line?
         *
         * Sampled rather than swept, with a block of margin around each sample, which is the same
         * bargain the lattice itself makes: enough room that the route is flyable, coarse enough
         * that testing it is cheap.
         */
        fun isLineClear(from: Vec3, to: Vec3): Boolean {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance < SAMPLE_STEP) return true

            val steps = (distance / SAMPLE_STEP).toInt()
            for (step in 0..steps) {
                val t = step.toDouble() / steps
                if (!isPointClear(from.x + dx * t, from.y + dy * t, from.z + dz * t)) return false
            }
            return true
        }

        private fun isPointClear(x: Double, y: Double, z: Double): Boolean {
            val blockX = Math.floor(x).toInt()
            val blockY = Math.floor(y).toInt()
            val blockZ = Math.floor(z).toInt()

            for (offsetX in -MARGIN..MARGIN) {
                for (offsetY in -MARGIN..MARGIN) {
                    for (offsetZ in -MARGIN..MARGIN) {
                        val navClass = source.navClass(blockX + offsetX, blockY + offsetY, blockZ + offsetZ)
                        if (navClass != NavBlockClass.AIR) return false
                    }
                }
            }
            return true
        }

        private companion object {
            const val UNTESTED: Byte = 0
            const val CLEAR: Byte = 1
            const val BLOCKED: Byte = 2

            /** Blocks of clearance kept around a smoothed line. */
            const val MARGIN = 1

            /** How far above a position to look for air, in cells, before giving up on flying. */
            const val MAX_LIFT_CELLS = 3

            /** Spacing of clearance samples along a smoothed line, in blocks. */
            const val SAMPLE_STEP = 2.0
        }
    }

    /**
     * Edge of the search lattice, in blocks.
     *
     * Four is the smallest cell with real margin around a flying player, and small enough that the
     * lattice still finds its way through a Nether tunnel rather than declaring it solid.
     */
    const val CELL = 4

    /** Ticks per block of level flight. The same cruise the router prices flights at. */
    private val FLIGHT_TICKS_PER_BLOCK = 1.0 / NavLocomotion.ELYTRA.blocksPerTick

    /**
     * Extra ticks charged per block of climb.
     *
     * A glider gains height only by spending a rocket, so a route that climbs is genuinely more
     * expensive than one that goes round — and pricing it flat is how a search ends up proposing
     * staircases through a cavern roof.
     */
    private const val CLIMB_SURCHARGE_PER_BLOCK = 2.0

    /**
     * Factor that keeps a walking goal estimate admissible for a flight.
     *
     * Two corrections in one number. Flying is several times quicker per block than the walking the
     * goals are written against, and their octile distance — right for an eight-connected ground
     * grid — runs up to about eight per cent above the straight line this lattice can actually fly.
     * Over-scaling here does not fail loudly; it quietly stops the search being optimal.
     */
    private val HEURISTIC_SCALE =
        (FLIGHT_TICKS_PER_BLOCK / NavTiming.MIN_COST_PER_BLOCK) * OCTILE_SAFETY

    /** Node expansions before a route is called good enough. */
    const val DEFAULT_MAX_ITERATIONS = 20_000

    /**
     * Weighted-A* multiplier.
     *
     * Well above what the ground search uses, because a three-dimensional frontier grows as a
     * sphere: unweighted, a search across a large cavern spends its whole budget filling the cavern
     * instead of crossing it. The route pays for that with some length, which a glider covers in a
     * second or two.
     */
    const val HEURISTIC_WEIGHT = 1.8

    /** Shortest flight worth taking off for, in blocks. */
    private const val MIN_WORTHWHILE_LENGTH = 48.0

    /** Opaque to the search: every edge here is the same kind of move. */
    private const val FLY_MOVE: Byte = 0

    /** Margin covering the gap between octile distance and the straight line. */
    private const val OCTILE_SAFETY = 0.9

    /** Squared length below which two route points are the same point. */
    private const val MIN_LEG_SQ = 1.0
}
