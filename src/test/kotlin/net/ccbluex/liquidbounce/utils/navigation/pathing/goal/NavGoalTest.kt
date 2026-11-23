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

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCostModel
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one property every goal has to have: its estimate may never exceed what the route really
 * costs. A* returns optimal paths under exactly that condition, and the search's closed set never
 * reopens a node — so an estimate above the truth does not merely slow it down, it can hide the
 * cheap way to the goal entirely.
 *
 * The estimates that break this are always the ones that measure a straight line, because falling
 * is much cheaper per block than walking.
 */
class NavGoalTest {

    /**
     * The real cost of dropping straight down a shaft: the step off the edge plus the fall.
     *
     * A lower bound on nothing — it is what the planner actually charges — so any estimate above it
     * is an estimate above the truth.
     */
    private fun costOfDropping(blocks: Int): Double =
        NavTiming.SPRINT_PER_BLOCK +
            NavCostModel.fallCost(blocks, NavBlockClass.WATER, NavCaps(maxFallBlocks = 128))

    @Test
    fun `a goal below is never estimated above the cost of falling to it`() {
        // Straight down a shaft with water at the bottom, which is free of damage and of any drop
        // limit — the cheapest way down there is, and the case a straight-line estimate gets wrong.
        for (drop in 4..64) {
            val goal = GoalNear(0, 0, 0, radius = 1.0)
            val estimate = goal.heuristic(0, drop, 0)
            val real = costOfDropping(drop)

            assertTrue(
                estimate <= real,
                "GoalNear estimated $estimate ticks for a $drop-block drop that costs $real",
            )
        }
    }

    @Test
    fun `reaching a block from below is never estimated above the cost of getting there`() {
        for (drop in 4..64) {
            val goal = GoalAdjacentTo(0, 0, 0)
            val estimate = goal.heuristic(0, drop, 0)
            val real = costOfDropping(drop)

            assertTrue(
                estimate <= real,
                "GoalAdjacentTo estimated $estimate ticks for a $drop-block drop that costs $real",
            )
        }
    }

    @Test
    fun `no cell that already satisfies a goal carries a positive estimate`() {
        val near = GoalNear(10, 64, 10, radius = 3.0)
        val adjacent = GoalAdjacentTo(10, 64, 10)
        val runAway = GoalRunAway(0, 64, 0, minDistance = 20.0)

        for (x in 0..20) {
            for (y in 58..70) {
                for (z in 0..20) {
                    if (near.isGoal(x, y, z)) {
                        assertEquals(0.0, near.heuristic(x, y, z), "GoalNear: $x $y $z is a goal")
                    }
                    // The one that used to disagree with itself: arrival is measured from the eyes
                    // to the block centre, while the estimate was measured centre to centre, so a
                    // spot the search had already reached still looked a tick away from it.
                    if (adjacent.isGoal(x, y, z)) {
                        assertEquals(0.0, adjacent.heuristic(x, y, z), "GoalAdjacentTo: $x $y $z is a goal")
                    }
                    if (runAway.isGoal(x, y, z)) {
                        assertEquals(0.0, runAway.heuristic(x, y, z), "GoalRunAway: $x $y $z is a goal")
                    }
                }
            }
        }
    }

    @Test
    fun `running away is measured across the ground, not down a hole`() {
        val goal = GoalRunAway(0, 64, 0, minDistance = 20.0)

        // Dropping into a ravine does not get you away from anything, and pricing the height as
        // distance would also make the estimate a lie: a fall covers blocks far more cheaply than
        // the walking floor this heuristic charges them at.
        assertTrue(goal.heuristic(0, 20, 0) > 0.0, "height is not distance from a pursuer")
        assertTrue(goal.isGoal(21, 64, 0), "twenty-one blocks away across the ground is away")
    }
}
