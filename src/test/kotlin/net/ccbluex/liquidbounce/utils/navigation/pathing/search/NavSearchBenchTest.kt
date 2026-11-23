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

package net.ccbluex.liquidbounce.utils.navigation.pathing.search

import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Performance guard rails.
 *
 * Assertions are on **expansions** wherever possible, because that number is deterministic and a
 * regression in the heuristic or the heap shows up in it immediately. The one wall-clock bound is
 * deliberately loose — it exists to catch an accidental quadratic, not to measure the machine.
 */
class NavSearchBenchTest {

    @Test
    fun `a direct route on open ground expands close to its own length`() {
        // With an admissible, well-scaled heuristic and no obstacles, A* should walk almost straight
        // at the goal. Ballooning here means the heuristic stopped guiding the search.
        val grid = NavTestGrid.open(sizeX = 64, sizeY = 2, sizeZ = 64)

        val result = NavSearch.search(
            start = grid.pack(0, 0, 0),
            goal = GoalBlock(BlockPos(63, 0, 0)),
            neighbors = grid.neighbors(),
            workspace = NavSearchWorkspace(),
            limits = NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 1.0),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertEquals(64, result.path.size)
        assertTrue(
            result.expansions < 2500,
            "a straight 63-block route took ${result.expansions} expansions",
        )
    }

    @Test
    fun `weighting cuts expansions substantially on a long route`() {
        // Weighted A* is how travelling past the render distance stays affordable; if the weight
        // stops paying for itself, the whole frontier strategy gets expensive.
        val grid = NavTestGrid.open(sizeX = 64, sizeY = 3, sizeZ = 64)
        val start = grid.pack(0, 0, 0)
        val goal = GoalBlock(BlockPos(63, 2, 63))

        val unweighted = NavSearch.search(
            start, goal, grid.neighbors(), NavSearchWorkspace(),
            NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 1.0),
        )
        val weighted = NavSearch.search(
            start, goal, grid.neighbors(), NavSearchWorkspace(),
            NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 1.3),
        )

        assertEquals(NavSearchOutcome.GOAL, unweighted.outcome)
        assertEquals(NavSearchOutcome.GOAL, weighted.outcome)
        assertTrue(
            weighted.expansions < unweighted.expansions,
            "weighting did not reduce expansions (${weighted.expansions} vs ${unweighted.expansions})",
        )
    }

    @Test
    fun `the heap never holds more entries than there are discovered nodes`() {
        // The old queue pushed a duplicate entry on every improvement, so it grew well past the
        // number of live nodes. Decrease-key is what keeps this bound true.
        val grid = NavTestGrid.random(sizeX = 32, sizeY = 8, sizeZ = 32, seed = 3)
        grid.open(0, 0, 0)
        grid.open(31, 7, 31)

        val result = NavSearch.search(
            grid.pack(0, 0, 0), GoalBlock(BlockPos(31, 7, 31)), grid.neighbors(), NavSearchWorkspace(),
            NavSearchLimits(maxIterations = 1_000_000),
        )

        assertTrue(
            result.peakOpen <= result.discovered,
            "heap peaked at ${result.peakOpen} for only ${result.discovered} discovered nodes",
        )
    }

    @Test
    fun `repeated replans on one workspace stay fast and consistent`() {
        // Following a path replans constantly. This is the pattern that used to allocate megabytes
        // per second; here it should reuse the same arrays every time.
        val grid = NavTestGrid.random(sizeX = 40, sizeY = 6, sizeZ = 40, seed = 11)
        grid.open(0, 0, 0)
        grid.open(39, 5, 39)
        val start = grid.pack(0, 0, 0)
        val goal = GoalBlock(BlockPos(39, 5, 39))
        val workspace = NavSearchWorkspace()
        val limits = NavSearchLimits(maxIterations = 1_000_000)

        // Warm up the JIT so the timing below measures steady state.
        repeat(20) { NavSearch.search(start, goal, grid.neighbors(), workspace, limits) }

        val reference = NavSearch.search(start, goal, grid.neighbors(), workspace, limits)
        val startedAt = System.nanoTime()
        repeat(REPLAN_COUNT) {
            val result = NavSearch.search(start, goal, grid.neighbors(), workspace, limits)
            assertEquals(reference.totalCost, result.totalCost, 1.0E-9, "replan $it diverged")
            assertEquals(reference.expansions, result.expansions, "replan $it expanded differently")
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000.0

        assertTrue(
            elapsedMillis < REPLAN_BUDGET_MILLIS,
            "$REPLAN_COUNT replans took ${elapsedMillis}ms, over the ${REPLAN_BUDGET_MILLIS}ms guard",
        )
    }

    private companion object {
        const val REPLAN_COUNT = 200

        /**
         * Loose on purpose. On any development machine this run finishes in a small fraction of the
         * budget; the number exists to catch an accidental quadratic, not to benchmark hardware.
         */
        const val REPLAN_BUDGET_MILLIS = 10_000.0
    }
}
