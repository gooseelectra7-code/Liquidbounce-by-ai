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
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.NavGoal
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class NavSearchTest {

    private val tolerance = 1.0E-9

    private fun workspace() = NavSearchWorkspace()

    /**
     * Checks that a returned route is internally consistent: every step follows the move that was
     * recorded for it, and the recorded costs really add up to the reported total.
     */
    private fun assertPathIsCoherent(grid: NavTestGrid, result: NavSearchResult) {
        assertEquals(
            result.path.size - 1,
            result.moves.size,
            "there must be exactly one move between each pair of positions",
        )

        var accumulated = 0.0
        for (step in 0 until result.moves.size) {
            val from = result.path[step]
            val expected = grid.applyMove(from, result.moves[step])
            assertEquals(
                expected,
                result.path[step + 1],
                "step $step: the recorded move does not lead to the next position",
            )
            val to = result.path[step + 1]
            assertTrue(
                grid.isOpen(BlockPos.getX(to), BlockPos.getY(to), BlockPos.getZ(to)),
                "step $step walks into a blocked cell",
            )
            accumulated += grid.costToEnter(BlockPos.getX(to), BlockPos.getY(to), BlockPos.getZ(to))
        }

        assertEquals(
            accumulated,
            result.totalCost,
            tolerance,
            "reported total cost does not match the sum of the steps",
        )
    }

    // -----------------------------------------------------------------------
    //  Optimality
    // -----------------------------------------------------------------------

    @Test
    fun `unweighted search matches Dijkstra exactly on open ground`() {
        val grid = NavTestGrid.open(sizeX = 20, sizeY = 4, sizeZ = 20)
        val start = grid.pack(0, 0, 0)
        val goalPos = BlockPos(19, 3, 19)
        val reference = grid.dijkstra(start)

        val result = NavSearch.search(
            start = start,
            goal = GoalBlock(goalPos),
            neighbors = grid.neighbors(),
            workspace = workspace(),
            limits = NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 1.0),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertEquals(reference.get(grid.pack(19, 3, 19)), result.totalCost, tolerance)
        assertPathIsCoherent(grid, result)
    }

    @Test
    fun `unweighted search matches Dijkstra across many random mazes`() {
        // The core correctness claim of the engine, checked against an independent oracle over
        // varied terrain rather than on one hand-built map.
        repeat(25) { seed ->
            val grid = NavTestGrid.random(sizeX = 16, sizeY = 6, sizeZ = 16, seed = seed)
            grid.open(0, 0, 0)
            grid.open(15, 5, 15)

            val start = grid.pack(0, 0, 0)
            val goalPacked = grid.pack(15, 5, 15)
            val reference = grid.dijkstra(start)
            val optimal = reference.get(goalPacked)

            val result = NavSearch.search(
                start = start,
                goal = GoalBlock(BlockPos(15, 5, 15)),
                neighbors = grid.neighbors(),
                workspace = workspace(),
                limits = NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 1.0),
            )

            if (optimal == Double.POSITIVE_INFINITY) {
                assertNotEquals(
                    NavSearchOutcome.GOAL,
                    result.outcome,
                    "seed $seed: reported reaching a goal Dijkstra says is unreachable",
                )
            } else {
                assertEquals(NavSearchOutcome.GOAL, result.outcome, "seed $seed: failed to reach a reachable goal")
                assertEquals(optimal, result.totalCost, tolerance, "seed $seed: path is not optimal")
                assertPathIsCoherent(grid, result)
            }
        }
    }

    @Test
    fun `weighted search stays within its guaranteed factor of optimal`() {
        // Weighted A* trades optimality for expansions; the guarantee is that the result never
        // exceeds the weight times the optimum. That bound is what makes the trade safe to take.
        val weight = 1.5
        repeat(15) { seed ->
            val grid = NavTestGrid.random(sizeX = 16, sizeY = 6, sizeZ = 16, seed = seed + 100)
            grid.open(0, 0, 0)
            grid.open(15, 5, 15)

            val start = grid.pack(0, 0, 0)
            val optimal = grid.dijkstra(start).get(grid.pack(15, 5, 15))
            if (optimal == Double.POSITIVE_INFINITY) return@repeat

            val result = NavSearch.search(
                start = start,
                goal = GoalBlock(BlockPos(15, 5, 15)),
                neighbors = grid.neighbors(),
                workspace = workspace(),
                limits = NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = weight),
            )

            assertEquals(NavSearchOutcome.GOAL, result.outcome, "seed $seed")
            assertTrue(
                result.totalCost <= optimal * weight + tolerance,
                "seed $seed: cost ${result.totalCost} exceeds the $weight x $optimal bound",
            )
            assertPathIsCoherent(grid, result)
        }
    }

    @Test
    fun `a heavier weight never expands more nodes than an unweighted search`() {
        val grid = NavTestGrid.open(sizeX = 30, sizeY = 3, sizeZ = 30)
        val start = grid.pack(0, 0, 0)
        val goal = GoalBlock(BlockPos(29, 2, 29))

        val unweighted = NavSearch.search(
            start, goal, grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 1.0),
        )
        val weighted = NavSearch.search(
            start, goal, grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000, heuristicWeight = 2.0),
        )

        assertEquals(NavSearchOutcome.GOAL, weighted.outcome)
        assertTrue(
            weighted.expansions <= unweighted.expansions,
            "weighting should not cost more expansions (${weighted.expansions} vs ${unweighted.expansions})",
        )
    }

    // -----------------------------------------------------------------------
    //  Trivial and degenerate cases
    // -----------------------------------------------------------------------

    @Test
    fun `starting on the goal returns immediately with a single-node path`() {
        val grid = NavTestGrid.open(4, 4, 4)
        val start = grid.pack(1, 1, 1)

        val result = NavSearch.search(start, GoalBlock(BlockPos(1, 1, 1)), grid.neighbors(), workspace())

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertEquals(1, result.path.size)
        assertEquals(start, result.path[0])
        assertEquals(0.0, result.totalCost)
        assertTrue(result.isEmpty, "a path with nowhere to walk must report itself empty")
    }

    @Test
    fun `a sealed start reports having nowhere to go`() {
        val grid = NavTestGrid.open(8, 8, 8)
        grid.seal(4, 4, 4)
        val start = grid.pack(4, 4, 4)

        val result = NavSearch.search(start, GoalBlock(BlockPos(7, 7, 7)), grid.neighbors(), workspace())

        assertEquals(NavSearchOutcome.NO_START, result.outcome)
        assertEquals(1, result.path.size)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `an unreachable goal exhausts the search and returns the closest cell found`() {
        val grid = NavTestGrid.open(12, 3, 12)
        // Wall the far half off completely.
        for (y in 0 until 3) {
            for (z in 0 until 12) {
                grid.block(6, y, z)
            }
        }
        val start = grid.pack(0, 0, 0)

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(11, 0, 0)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000),
        )

        assertEquals(NavSearchOutcome.EXHAUSTED, result.outcome)
        assertTrue(result.path.size > 1, "it should still walk as close to the wall as it can")
        val end = result.destination()
        assertEquals(5, BlockPos.getX(end), "the closest reachable column is the one against the wall")
        assertPathIsCoherent(grid, result)
    }

    // -----------------------------------------------------------------------
    //  Budgets and interruption
    // -----------------------------------------------------------------------

    @Test
    fun `running out of iterations yields a usable partial path`() {
        // Unbounded range depends on this: walk the partial route, replan from its far end, repeat.
        val grid = NavTestGrid.open(60, 3, 60)
        val start = grid.pack(0, 0, 0)

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(59, 2, 59)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 200),
        )

        assertEquals(NavSearchOutcome.BEST_EFFORT, result.outcome)
        assertTrue(result.expansions <= 200, "expansions must honour the budget")
        assertTrue(result.path.size > 1, "a partial path still has to be walkable")
        assertPathIsCoherent(grid, result)
    }

    @Test
    fun `the partial path heads toward the goal rather than away from it`() {
        val grid = NavTestGrid.open(60, 3, 60)
        val start = grid.pack(0, 0, 0)

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(59, 0, 59)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 400),
        )

        val end = result.destination()
        val startDistance = 59 + 59
        val endDistance = abs(59 - BlockPos.getX(end)) + abs(59 - BlockPos.getZ(end))
        assertTrue(endDistance < startDistance, "best effort ended no closer to the goal than the start")
    }

    @Test
    fun `cancellation stops the search early`() {
        val grid = NavTestGrid.open(200, 4, 200)
        val start = grid.pack(0, 0, 0)

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(199, 3, 199)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000),
            cancelled = { true },
        )

        assertEquals(NavSearchOutcome.BEST_EFFORT, result.outcome)
        // Cancellation is polled on an interval, so a bounded overshoot is expected and fine.
        assertTrue(result.expansions <= 512, "cancellation was not noticed promptly (${result.expansions})")
    }

    @Test
    fun `a timeout stops the search early`() {
        val grid = NavTestGrid.open(200, 4, 200)
        val start = grid.pack(0, 0, 0)

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(199, 3, 199)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 100_000_000, timeoutNanos = 1L),
        )

        assertEquals(NavSearchOutcome.BEST_EFFORT, result.outcome)
        assertTrue(result.path.size > 1)
    }

    @Test
    fun `maxCost keeps the search inside its cost budget`() {
        val grid = NavTestGrid.open(40, 2, 40)
        val start = grid.pack(0, 0, 0)
        val budget = 20.0

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(39, 1, 39)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000, maxCost = budget),
        )

        assertNotEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(result.totalCost <= budget, "returned a path costing ${result.totalCost} over a $budget budget")
    }

    // -----------------------------------------------------------------------
    //  Frontier preference
    // -----------------------------------------------------------------------

    @Test
    fun `a best-effort path prefers to end on the frontier when it is nearly as close`() {
        // Ending a segment where unloaded chunks begin is what lets the next ones stream in; ending
        // in explored interior just wastes the walk. The goal here sits outside the grid entirely,
        // which is what "the goal is beyond the loaded world" looks like to the search.
        val grid = NavTestGrid.open(40, 2, 40)
        val start = grid.pack(0, 0, 0)
        val frontierX = 30

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(200, 0, 0)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000, frontierTolerance = 40.0),
            isFrontier = { node -> BlockPos.getX(node) == frontierX },
        )

        assertEquals(NavSearchOutcome.EXHAUSTED, result.outcome)
        assertEquals(frontierX, BlockPos.getX(result.destination()), "did not stop on the frontier")
        assertPathIsCoherent(grid, result)
    }

    @Test
    fun `the frontier is ignored when it is much further from the goal`() {
        // Same setup, but the frontier is now well outside the tolerance, so the genuinely closest
        // cell wins and we do not get dragged backwards toward the map edge.
        val grid = NavTestGrid.open(40, 2, 40)
        val start = grid.pack(0, 0, 0)

        val result = NavSearch.search(
            start, GoalBlock(BlockPos(200, 0, 0)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 1_000_000, frontierTolerance = 4.0),
            isFrontier = { node -> BlockPos.getX(node) == 10 },
        )

        assertEquals(NavSearchOutcome.EXHAUSTED, result.outcome)
        assertEquals(39, BlockPos.getX(result.destination()), "should have used the closest cell, not the frontier")
    }

    @Test
    fun `a reachable goal beats any frontier`() {
        val grid = NavTestGrid.open(40, 2, 40)

        val result = NavSearch.search(
            grid.pack(20, 0, 20), GoalBlock(BlockPos(24, 0, 20)), grid.neighbors(), workspace(),
            NavSearchLimits(maxIterations = 5000, frontierTolerance = 1000.0),
            isFrontier = { node -> BlockPos.getX(node) == 0 },
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertEquals(24, BlockPos.getX(result.destination()))
    }

    // -----------------------------------------------------------------------
    //  Workspace reuse
    // -----------------------------------------------------------------------

    @Test
    fun `a reused workspace gives identical results`() {
        // Replanning reuses one workspace constantly; leftover state from the previous search would
        // show up here as a diverging second result.
        val grid = NavTestGrid.random(sizeX = 14, sizeY = 5, sizeZ = 14, seed = 7)
        grid.open(0, 0, 0)
        grid.open(13, 4, 13)
        val start = grid.pack(0, 0, 0)
        val goal: NavGoal = GoalBlock(BlockPos(13, 4, 13))
        val shared = workspace()

        val first = NavSearch.search(start, goal, grid.neighbors(), shared, NavSearchLimits(maxIterations = 100_000))
        val second = NavSearch.search(start, goal, grid.neighbors(), shared, NavSearchLimits(maxIterations = 100_000))
        val third = NavSearch.search(start, goal, grid.neighbors(), shared, NavSearchLimits(maxIterations = 100_000))

        assertEquals(first.outcome, second.outcome)
        assertEquals(first.totalCost, second.totalCost, tolerance)
        assertEquals(first.path.toList(), second.path.toList())
        assertEquals(first.path.toList(), third.path.toList())
        assertEquals(first.expansions, second.expansions, "expansion count drifted on reuse")
    }

    @Test
    fun `a workspace can be reused for a different start and goal`() {
        val grid = NavTestGrid.open(20, 3, 20)
        val shared = workspace()

        NavSearch.search(grid.pack(0, 0, 0), GoalBlock(BlockPos(19, 2, 19)), grid.neighbors(), shared)
        val second = NavSearch.search(
            grid.pack(19, 2, 19), GoalBlock(BlockPos(0, 0, 0)), grid.neighbors(), shared,
        )

        assertEquals(NavSearchOutcome.GOAL, second.outcome)
        assertEquals(grid.pack(19, 2, 19), second.path[0], "the second search must start where it was told")
        assertEquals(grid.pack(0, 0, 0), second.destination())
        assertPathIsCoherent(grid, second)
    }

    // -----------------------------------------------------------------------
    //  Diagnostics
    // -----------------------------------------------------------------------

    @Test
    fun `diagnostics describe the search that actually ran`() {
        val grid = NavTestGrid.open(20, 3, 20)

        val result = NavSearch.search(
            grid.pack(0, 0, 0), GoalBlock(BlockPos(19, 2, 19)), grid.neighbors(), workspace(),
        )

        assertTrue(result.expansions > 0, "a real search expands nodes")
        assertTrue(result.discovered >= result.expansions, "every expanded node must have been discovered")
        assertTrue(result.peakOpen > 0, "nodes must have been queued")
        assertTrue(result.elapsedNanos > 0, "elapsed time must be recorded")
    }
}
