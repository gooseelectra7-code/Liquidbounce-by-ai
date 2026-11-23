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

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavEdgeBuffer
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearch
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchLimits
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchOutcome
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchWorkspace
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Planning for something faster and taller-stepping than a player.
 *
 * The subtle part is not the extra reach — it is that a mount outruns a sprint, so edges become
 * cheaper than the goal heuristics assume. Left alone that turns the estimate into an
 * overestimate, and A* stops returning optimal paths without failing in any visible way.
 */
class MountedPlanningTest {

    private val floorY = 8
    private val tolerance = 1.0E-9

    private fun flat(size: Int = 24): NavTestTerrain =
        NavTestTerrain(size, 30, size).apply { floor(floorY) }

    /** A good horse: faster than sprinting, clears three blocks in a step. */
    private fun horseCaps() = NavCaps(
        travelTicksPerBlock = 1.0 / 0.45,
        maxStepUp = 3,
        allowParkour = false,
        allowBreak = false,
        allowPlace = false,
    )

    // -----------------------------------------------------------------------
    //  Admissibility under a faster mode
    // -----------------------------------------------------------------------

    @Test
    fun `a mount scales the heuristic down so it cannot overestimate`() {
        val caps = horseCaps()

        assertTrue(caps.minCostPerBlock < NavTiming.MIN_COST_PER_BLOCK, "a horse should outrun a sprint")
        assertTrue(caps.heuristicScale < 1.0, "a faster mode must shrink the estimate")
        assertEquals(
            caps.minCostPerBlock,
            NavTiming.MIN_COST_PER_BLOCK * caps.heuristicScale,
            tolerance,
            "the scaled floor must match what the mount can actually do",
        )
    }

    @Test
    fun `walking on foot leaves the heuristic untouched`() {
        assertEquals(1.0, NavCaps.TRAVEL.heuristicScale, tolerance)
        assertEquals(1.0, NavCaps.SAFE.heuristicScale, tolerance)
    }

    @Test
    fun `a mode slower than walking never scales the estimate up`() {
        // Over-scaling is the failure that matters: an inflated estimate is an overestimate, which
        // is precisely what breaks optimality.
        val slow = NavCaps(travelTicksPerBlock = 40.0)

        assertEquals(1.0, slow.heuristicScale, tolerance)
    }

    @Test
    fun `no mounted edge is cheaper than the scaled floor`() {
        // The same exhaustive invariant the walking planner is held to, restated for a mode whose
        // floor is lower.
        val random = Random(seed = 20260731)
        val caps = horseCaps()

        repeat(8) { round ->
            val terrain = randomTerrain(random, round)
            val planner = GroundPlanner(terrain, caps)
            val buffer = NavEdgeBuffer()

            for (x in 0 until TERRAIN_SIZE) {
                for (y in 0 until TERRAIN_HEIGHT) {
                    for (z in 0 until TERRAIN_SIZE) {
                        buffer.clear()
                        planner.expand(BlockPos.asLong(x, y, z), buffer)

                        for (edge in 0 until buffer.size) {
                            val destination = buffer.nodes[edge]
                            val dx = BlockPos.getX(destination) - x
                            val dy = BlockPos.getY(destination) - y
                            val dz = BlockPos.getZ(destination) - z

                            val horizontal = sqrt((dx * dx + dz * dz).toDouble()) * caps.minCostPerBlock
                            val vertical = if (dy > 0) dy * caps.minCostPerBlock else 0.0
                            val floor = max(horizontal, vertical)

                            assertTrue(
                                buffer.costs[edge] >= floor - tolerance,
                                "round $round: ${NavMove.nameOf(NavMove.kindOf(buffer.moves[edge]))} edge " +
                                    "costs ${buffer.costs[edge]}, below the mounted floor of $floor",
                            )
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Reach
    // -----------------------------------------------------------------------

    @Test
    fun `a player cannot step up more than one block`() {
        val terrain = flat()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)

        val buffer = NavEdgeBuffer()
        GroundPlanner(terrain, NavCaps.SAFE).expand(BlockPos.asLong(8, floorY + 1, 8), buffer)

        val destinations = (0 until buffer.size).map { buffer.nodes[it] }.toSet()
        assertFalse(BlockPos.asLong(9, floorY + 3, 8) in destinations, "a player cannot clear two blocks")
    }

    @Test
    fun `a mount steps up onto ledges a player would have to walk around`() {
        val terrain = flat()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)

        val buffer = NavEdgeBuffer()
        GroundPlanner(terrain, horseCaps()).expand(BlockPos.asLong(8, floorY + 1, 8), buffer)

        val destinations = (0 until buffer.size).map { buffer.nodes[it] }.toSet()
        assertTrue(BlockPos.asLong(9, floorY + 3, 8) in destinations, "a horse should clear a two-block ledge")
    }

    @Test
    fun `a step up is still refused when the climb itself is blocked`() {
        val terrain = flat()
        terrain.box(9, floorY + 1, 8, 9, floorY + 4, 8, NavBlockClass.SOLID_FULL)
        // Ceiling right above the rider: there is no room to rise at all.
        terrain.set(8, floorY + 3, 8, NavBlockClass.SOLID_FULL)

        val buffer = NavEdgeBuffer()
        GroundPlanner(terrain, horseCaps()).expand(BlockPos.asLong(8, floorY + 1, 8), buffer)

        val destinations = (0 until buffer.size).map { buffer.nodes[it] }.toSet()
        assertFalse(BlockPos.asLong(9, floorY + 5, 8) in destinations, "no headroom means no climb")
    }

    @Test
    fun `a wall a player must detour around is climbed directly on a mount`() {
        val terrain = flat(size = 24)
        // A three-block step across the whole corridor, with a way round at the far edge.
        terrain.box(12, floorY + 1, 0, 12, floorY + 3, 22, NavBlockClass.SOLID_FULL)
        for (x in 13 until 24) {
            for (z in 0 until 24) {
                terrain.box(x, floorY + 1, z, x, floorY + 3, z, NavBlockClass.SOLID_FULL)
            }
        }
        // Walkable surface on top of the raised half.
        for (x in 12 until 24) {
            for (z in 0 until 24) {
                terrain.set(x, floorY + 4, z, NavBlockClass.AIR)
                terrain.set(x, floorY + 5, z, NavBlockClass.AIR)
            }
        }

        val start = BlockPos.asLong(2, floorY + 1, 12)
        val goal = GoalBlock(BlockPos(20, floorY + 4, 12))

        val onFoot = NavSearch.search(
            start, goal, GroundPlanner(terrain, NavCaps.SAFE), NavSearchWorkspace(),
            NavSearchLimits(maxIterations = 200_000, heuristicScale = NavCaps.SAFE.heuristicScale),
        )
        val mounted = NavSearch.search(
            start, goal, GroundPlanner(terrain, horseCaps()), NavSearchWorkspace(),
            NavSearchLimits(maxIterations = 200_000, heuristicScale = horseCaps().heuristicScale),
        )

        assertEquals(NavSearchOutcome.GOAL, mounted.outcome, "a horse can climb the step directly")
        assertFalse(onFoot.reachedGoal, "on foot the raised half is out of reach entirely")
    }

    // -----------------------------------------------------------------------
    //  Body size
    // -----------------------------------------------------------------------

    /** A horse: wide enough that it does not fit where a player does. */
    private fun wideCaps() = horseCaps().copy(bodyWidth = 1.4, bodyHeight = 1.6)

    @Test
    fun `a player-sized body occupies a single column`() {
        assertEquals(0, NavCaps.TRAVEL.bodyColumnRadius)
        assertEquals(2, NavCaps.TRAVEL.bodyHeightBlocks)
    }

    @Test
    fun `a horse-sized body spills into the neighbouring columns`() {
        // Centred on a block centre, a 1.4-wide body reaches 0.7 either way and so overlaps the
        // columns on both sides. Checking only the centre one is what let routes be planned into
        // gaps a horse cannot enter.
        assertEquals(1, wideCaps().bodyColumnRadius)
    }

    @Test
    fun `a wide body does not fit a one-block gap that a player walks through`() {
        val terrain = flat()
        // A slot one block wide, walls on both sides.
        terrain.box(8, floorY + 1, 7, 8, floorY + 2, 7, NavBlockClass.SOLID_FULL)
        terrain.box(8, floorY + 1, 9, 8, floorY + 2, 9, NavBlockClass.SOLID_FULL)

        assertTrue(
            NavStandingSpot.canOccupy(terrain, 8, floorY + 1, 8, NavCaps.SAFE),
            "a player fits the slot",
        )
        assertFalse(
            NavStandingSpot.canOccupy(terrain, 8, floorY + 1, 8, wideCaps()),
            "a horse does not, and planning it there is why it got stuck against walls",
        )
    }

    @Test
    fun `a wide body cannot reach a spot tucked against a wall`() {
        val terrain = flat()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)

        assertTrue(NavStandingSpot.canOccupy(terrain, 8, floorY + 1, 8, NavCaps.SAFE))
        assertFalse(NavStandingSpot.canOccupy(terrain, 8, floorY + 1, 8, wideCaps()))
    }

    @Test
    fun `refusal explains that the body is too wide rather than blaming the column`() {
        // Its own column is completely clear, so without saying so the rejection is baffling.
        val terrain = flat()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)

        val reason = NavStandingSpot.describe(terrain, 8, floorY + 1, 8, wideCaps())

        assertTrue(reason.contains("too wide"), "unhelpful refusal: $reason")
    }

    @Test
    fun `a wide body still fits open ground`() {
        val terrain = flat()

        assertTrue(NavStandingSpot.canOccupy(terrain, 8, floorY + 1, 8, wideCaps()))
    }

    @Test
    fun `a route for a wide body never passes through a gap it cannot fit`() {
        val terrain = flat(size = 24)
        // A wall across the map with a single one-block doorway, and a three-wide one further along.
        for (z in 0 until 24) {
            terrain.box(12, floorY + 1, z, 12, floorY + 3, z, NavBlockClass.SOLID_FULL)
        }
        terrain.box(12, floorY + 1, 4, 12, floorY + 2, 4, NavBlockClass.AIR)
        terrain.box(12, floorY + 1, 17, 12, floorY + 2, 19, NavBlockClass.AIR)

        val result = NavSearch.search(
            BlockPos.asLong(2, floorY + 1, 4),
            GoalBlock(BlockPos(20, floorY + 1, 18)),
            GroundPlanner(terrain, wideCaps()),
            NavSearchWorkspace(),
            NavSearchLimits(maxIterations = 200_000, heuristicScale = wideCaps().heuristicScale),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.path.none { BlockPos.getX(it) == 12 && BlockPos.getZ(it) == 4 },
            "the route squeezed a horse through a one-block doorway",
        )
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private fun randomTerrain(random: Random, round: Int): NavTestTerrain {
        val terrain = NavTestTerrain(TERRAIN_SIZE, TERRAIN_HEIGHT, TERRAIN_SIZE)
        val classes = intArrayOf(
            NavBlockClass.AIR, NavBlockClass.AIR,
            NavBlockClass.SOLID_FULL, NavBlockClass.SOLID_FULL,
            NavBlockClass.WATER, NavBlockClass.LAVA,
            NavBlockClass.CLIMBABLE, NavBlockClass.COBWEB,
            NavBlockClass.HAZARD, NavBlockClass.HAZARD_SOLID,
            NavBlockClass.SLOW, NavBlockClass.FAST,
            NavBlockClass.SOLID_TOP_HALF, NavBlockClass.CARPET_LIKE,
            NavBlockClass.BLOCKING_TALL, NavBlockClass.OTHER_SOLID,
        )
        for (x in 0 until TERRAIN_SIZE) {
            for (y in 0 until TERRAIN_HEIGHT) {
                for (z in 0 until TERRAIN_SIZE) {
                    terrain.set(x, y, z, classes[random.nextInt(classes.size)], random.nextInt(0, 16))
                }
            }
        }
        terrain.set(0, 0, 0, NavBlockClass.SOLID_FULL, round % 15)
        return terrain
    }

    private companion object {
        const val TERRAIN_SIZE = 10
        const val TERRAIN_HEIGHT = 12
    }
}
