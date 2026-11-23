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

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.NavGoal
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.GroundPlanner
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavTestTerrain
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearch
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchLimits
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchOutcome
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchResult
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchWorkspace
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the hierarchical layer is for: not finding routes the detailed search could not find, but
 * finding them without first exploring everything on the wrong side of a wall.
 */
class GuidedSearchTest {

    private val caps = NavCaps(allowParkour = false)

    @BeforeEach
    fun clearCache() {
        NavSectionGraph.clear()
    }

    /**
     * A plain with a long wall across it, open only at the far edge.
     *
     * The classic trap for a straight-line heuristic: every step toward the wall looks like progress
     * until the last one, so the whole near side gets expanded before the detour is even considered.
     */
    private fun walledPlain(): NavTestTerrain = NavTestTerrain(160, 16, 160).apply {
        floor(0)
        box(80, 1, 0, 81, 15, 140, NavBlockClass.SOLID_FULL)
    }

    private fun search(
        terrain: NavTestTerrain,
        goal: NavGoal,
        weight: Double = 1.0,
        budget: Int = 200_000,
    ): NavSearchResult = NavSearch.search(
        start = BlockPos.asLong(8, 1, 8),
        goal = goal,
        neighbors = GroundPlanner(terrain, caps),
        workspace = NavSearchWorkspace(),
        limits = NavSearchLimits(maxIterations = budget, heuristicWeight = weight),
    )

    @Test
    fun `a corridor cuts the work of getting round a wall`() {
        val terrain = walledPlain()
        val destination = BlockPos(150, 1, 8)
        val plain = GoalBlock(destination)

        val corridor = NavSectionGraph.route(terrain, BlockPos(8, 1, 8), destination, caps)
        assertNotNull(corridor, "the coarse layer should see the way round")

        val unguided = search(terrain, plain)
        val guided = search(terrain, GuidedGoal(plain, corridor!!))

        assertEquals(NavSearchOutcome.GOAL, unguided.outcome)
        assertEquals(NavSearchOutcome.GOAL, guided.outcome)
        assertTrue(
            guided.expansions < unguided.expansions * 0.75,
            "guide saved little: ${guided.expansions} expansions against ${unguided.expansions}",
        )
    }

    @Test
    fun `on the budget the engine really searches with, the guide is what reaches the goal`() {
        // The number that matters in play. A real search is weighted and capped, and the question is
        // not how many nodes it expands but whether it arrives at all before the budget runs out —
        // an unguided search spends its whole allowance on the near side of the wall and comes back
        // with a best-effort route that walks into it.
        val terrain = walledPlain()
        val destination = BlockPos(150, 1, 8)
        val plain = GoalBlock(destination)
        val corridor = NavSectionGraph.route(terrain, BlockPos(8, 1, 8), destination, caps)!!

        val unguided = search(terrain, plain, weight = 1.3)
        val guided = search(terrain, GuidedGoal(plain, corridor), weight = 1.3)

        assertEquals(NavSearchOutcome.GOAL, unguided.outcome)
        assertEquals(NavSearchOutcome.GOAL, guided.outcome)
        assertTrue(
            guided.expansions < unguided.expansions * 0.6,
            "weighted and guided took ${guided.expansions} where weighted alone took ${unguided.expansions}",
        )

        // Which is the whole point: on a budget that the unguided search cannot arrive within, the
        // guided one does — the difference between a route to the goal and a best-effort one that
        // walks into the wall and asks again.
        val budget = (guided.expansions + unguided.expansions) / 2
        assertEquals(
            NavSearchOutcome.GOAL,
            search(terrain, GuidedGoal(plain, corridor), weight = 1.3, budget = budget).outcome,
        )
        assertEquals(
            NavSearchOutcome.BEST_EFFORT,
            search(terrain, plain, weight = 1.3, budget = budget).outcome,
        )
    }

    @Test
    fun `the guided route is still a route, and barely longer`() {
        // The corridor is guidance, not a constraint, and buying speed with a wildly worse path
        // would not be a trade worth making.
        val terrain = walledPlain()
        val destination = BlockPos(150, 1, 8)
        val plain = GoalBlock(destination)
        val corridor = NavSectionGraph.route(terrain, BlockPos(8, 1, 8), destination, caps)!!

        val unguided = search(terrain, plain)
        val guided = search(terrain, GuidedGoal(plain, corridor))

        assertTrue(
            guided.totalCost <= unguided.totalCost * 1.25,
            "guided route costs ${guided.totalCost} against the optimum ${unguided.totalCost}",
        )
    }

    @Test
    fun `a corridor that lies is survivable`() {
        // Corridors go stale — a door closes, a bridge is broken — and the detailed search has to
        // stay able to leave one. Here the corridor is built on open ground and then a wall is put
        // across it without telling the graph.
        val terrain = NavTestTerrain(160, 16, 160).apply { floor(0) }
        val destination = BlockPos(150, 1, 8)
        val corridor = NavSectionGraph.route(terrain, BlockPos(8, 1, 8), destination, caps)!!

        terrain.box(80, 1, 0, 81, 15, 140, NavBlockClass.SOLID_FULL)

        val guided = search(terrain, GuidedGoal(GoalBlock(destination), corridor))

        assertEquals(NavSearchOutcome.GOAL, guided.outcome, "a stale corridor must not cost the route")
    }
}
