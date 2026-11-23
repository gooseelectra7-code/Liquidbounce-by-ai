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
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalAdjacentTo
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.NavGoal
import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearch
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchLimits
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchOutcome
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchResult
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchWorkspace
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The planner, the search and the goals working together on synthetic terrain.
 *
 * These are the tests that check the engine's central design claim: with honest costs a single
 * search decides for itself whether to walk around an obstacle or dig through it. The previous
 * engine needed two passes and a comparison precisely because its costs could not be trusted to
 * make that call.
 */
class GroundSearchTest {

    private val floorY = 8

    /** A corridor: floor at [floorY], walls of unloaded space at the box edges. */
    private fun corridor(sizeX: Int = 40, sizeZ: Int = 7): NavTestTerrain =
        NavTestTerrain(sizeX, 24, sizeZ).apply { floor(floorY) }

    private fun search(
        terrain: NavTestTerrain,
        caps: NavCaps,
        from: BlockPos,
        goal: NavGoal,
    ): NavSearchResult = NavSearch.search(
        start = BlockPos.asLong(from.x, from.y, from.z),
        goal = goal,
        neighbors = GroundPlanner(terrain, caps),
        workspace = NavSearchWorkspace(),
        limits = NavSearchLimits(maxIterations = 200_000, heuristicWeight = 1.0),
    )

    private fun kindCounts(result: NavSearchResult): Map<Int, Int> =
        result.moves.groupBy { NavMove.kindOf(it) }.mapValues { it.value.size }

    /** Fast pickaxe: makes digging a genuine competitor to walking rather than a last resort. */
    private fun diggingCaps() = NavCaps(allowBreak = true, breakTicksPerHardness = 10.0)

    // -----------------------------------------------------------------------
    //  Plain walking
    // -----------------------------------------------------------------------

    @Test
    fun `open ground produces a direct route with no world changes`() {
        val terrain = corridor()

        val result = search(
            terrain, NavCaps.SAFE,
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.moves.none { NavMove.mutatesWorld(NavMove.kindOf(it)) },
            "walking across open ground should not need to touch a single block",
        )
    }

    @Test
    fun `a step down is taken rather than the floor mined out from under us`() {
        // A sharp tool goes through soft ground in less time than a footstep takes, so priced on
        // the clock alone the shortest way down one block is to mine the one you are standing on.
        // It is also a wasted tool and a hole in someone's world, which is what the dig aversion is
        // there to price.
        val terrain = corridor()
        // A ledge: the far half of the corridor is a block lower, with a soft floor to stand on.
        terrain.box(20, floorY, 0, 39, floorY, 6, NavBlockClass.AIR)
        terrain.box(20, floorY - 1, 0, 39, floorY - 1, 6, NavBlockClass.SOLID_FULL, digBucket = 1)

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.moves.none { NavMove.mutatesWorld(NavMove.kindOf(it)) },
            "should have walked off the ledge: ${kindCounts(result)}",
        )
    }

    @Test
    fun `digging still wins when it is a real shortcut rather than a technicality`() {
        // The aversion must not turn into a ban: through a hill is still better than round it when
        // round it is far enough.
        val terrain = corridor(sizeZ = 3)
        terrain.box(20, floorY + 1, 0, 21, floorY + 2, 2, NavBlockClass.SOLID_FULL, digBucket = 1)

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 1),
            GoalBlock(BlockPos(38, floorY + 1, 1)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.moves.any { NavMove.kindOf(it) == NavMove.DIG },
            "a wall with no way round has to be dug: ${kindCounts(result)}",
        )
    }

    @Test
    fun `a wall with water behind it is left alone`() {
        // A block is also a dam. Breaking the last dry one lets whatever it was holding back into
        // the tunnel — and into the player standing in it — which the block's own hardness says
        // nothing about.
        val terrain = corridor(sizeZ = 3)
        terrain.box(20, floorY + 1, 0, 20, floorY + 2, 2, NavBlockClass.SOLID_FULL)
        terrain.box(21, floorY + 1, 0, 21, floorY + 2, 2, NavBlockClass.WATER)

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 1),
            GoalBlock(BlockPos(38, floorY + 1, 1)),
        )

        assertNotEquals(
            NavSearchOutcome.GOAL,
            result.outcome,
            "should refuse rather than open the wall into the water: ${kindCounts(result)}",
        )
    }

    @Test
    fun `dry rock is still dug through`() {
        // The fluid rule must not turn into a ban on mining: the same wall without the water behind
        // it is an ordinary obstacle.
        val terrain = corridor(sizeZ = 3)
        terrain.box(20, floorY + 1, 0, 20, floorY + 2, 2, NavBlockClass.SOLID_FULL)

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 1),
            GoalBlock(BlockPos(38, floorY + 1, 1)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(result.moves.any { NavMove.kindOf(it) == NavMove.DIG })
    }

    @Test
    fun `a one-block dip is stepped through rather than paved over`() {
        // Four ticks of swing is all a placement costs on the clock, and stepping down and back up
        // costs more than that — so a route with blocks in its pocket used to bridge every ditch it
        // met. The block came out of an inventory and stayed in the landscape, neither of which the
        // stopwatch can see.
        val terrain = corridor()
        terrain.box(20, floorY, 0, 20, floorY, 6, NavBlockClass.AIR)
        terrain.box(20, floorY - 1, 0, 20, floorY - 1, 6, NavBlockClass.SOLID_FULL)

        val result = search(
            terrain, NavCaps(allowPlace = true, placeableBlocks = 64),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.moves.none { NavMove.mutatesWorld(NavMove.kindOf(it)) },
            "should have walked down and up again: ${kindCounts(result)}",
        )
    }

    @Test
    fun `a real gap is still bridged`() {
        // The aversion must not become a ban: where there is no way round, the block goes down.
        //
        // Six blocks wide, because a narrower one is not a real gap — the planner jumps anything up
        // to four, and jumping is both quicker and free.
        val terrain = corridor(sizeZ = 3)
        terrain.box(20, floorY, 0, 25, floorY, 2, NavBlockClass.AIR)

        val result = search(
            terrain, NavCaps(allowPlace = true, placeableBlocks = 64),
            BlockPos(1, floorY + 1, 1),
            GoalBlock(BlockPos(38, floorY + 1, 1)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.moves.any { NavMove.kindOf(it) == NavMove.PLACE },
            "a gap with nothing under it has to be bridged: ${kindCounts(result)}",
        )
    }

    @Test
    fun `a lake is crossed along its surface rather than through the middle of it`() {
        // Swimming is the same speed at any depth, so on time alone the search has no reason to
        // prefer one. Air is the difference: it is only spent with the head under, and a crossing
        // planned two blocks down arrives out of breath for nothing.
        val terrain = NavTestTerrain(30, 20, 8)
        terrain.floor(floorY)
        terrain.box(0, floorY + 1, 0, 29, floorY + 8, 7, NavBlockClass.WATER)

        val surface = floorY + 8
        val result = search(
            terrain, NavCaps(allowSwim = true),
            BlockPos(1, surface, 3),
            GoalBlock(BlockPos(28, surface, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        for (index in result.path.indices) {
            assertEquals(
                surface,
                BlockPos.getY(result.path[index]),
                "the crossing dived to ${BlockPos.getY(result.path[index])} instead of staying up",
            )
        }
    }

    // -----------------------------------------------------------------------
    //  Walk versus dig, decided by cost alone
    // -----------------------------------------------------------------------

    @Test
    fun `a wall with a nearby gap is walked around even though digging is allowed`() {
        val terrain = corridor()
        // Full-height wall across the corridor, with a doorway one block off the direct line.
        terrain.box(20, floorY + 1, 0, 20, floorY + 3, 6, NavBlockClass.SOLID_FULL)
        terrain.box(20, floorY + 1, 4, 20, floorY + 2, 4, NavBlockClass.AIR)

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertEquals(
            0,
            kindCounts(result)[NavMove.DIG] ?: 0,
            "a doorway one block away is cheaper than mining, so nothing should be dug",
        )
    }

    @Test
    fun `a wall with no way around is dug through`() {
        val terrain = corridor()
        terrain.box(20, floorY + 1, 0, 20, floorY + 3, 6, NavBlockClass.SOLID_FULL)

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue((kindCounts(result)[NavMove.DIG] ?: 0) > 0, "the only way through is to mine")
    }

    @Test
    fun `the same wall is impassable when breaking is forbidden`() {
        // The honest answer for a consumer that must not touch the world, rather than a route it
        // cannot actually walk.
        val terrain = corridor()
        terrain.box(20, floorY + 1, 0, 20, floorY + 3, 6, NavBlockClass.SOLID_FULL)

        val result = search(
            terrain, NavCaps(allowBreak = false),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertNotEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue(
            result.path.all { BlockPos.getX(it) < 20 },
            "no part of the route may cross a wall it is not allowed to break",
        )
    }

    @Test
    fun `bedrock is not dug through even with breaking allowed`() {
        val terrain = corridor()
        terrain.box(
            20, floorY + 1, 0, 20, floorY + 3, 6,
            NavBlockClass.SOLID_FULL, NavBlockClass.DIG_UNBREAKABLE,
        )

        val result = search(
            terrain, diggingCaps(),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertNotEquals(NavSearchOutcome.GOAL, result.outcome)
    }

    @Test
    fun `a slow tool tips the decision back toward walking around`() {
        // Same terrain, same permissions - only the tool differs. The route changes because the
        // cost model actually reflects how long mining will take.
        //
        // The way round is deliberately a long one. Every block broken now carries a fixed price
        // beyond the seconds it takes — see NavCaps.digAversion — so a short detour beats digging
        // whatever the tool, and it takes a real detour for the tool to be the deciding factor.
        val terrain = corridor(sizeX = 40, sizeZ = 44)
        terrain.box(20, floorY + 1, 0, 20, floorY + 3, 42, NavBlockClass.SOLID_FULL)
        terrain.box(20, floorY + 1, 43, 20, floorY + 2, 43, NavBlockClass.AIR)

        val withFastTool = search(
            terrain, NavCaps(allowBreak = true, breakTicksPerHardness = 1.0),
            BlockPos(1, floorY + 1, 0),
            GoalBlock(BlockPos(38, floorY + 1, 0)),
        )
        val withSlowTool = search(
            terrain, NavCaps(allowBreak = true, breakTicksPerHardness = 400.0),
            BlockPos(1, floorY + 1, 0),
            GoalBlock(BlockPos(38, floorY + 1, 0)),
        )

        assertEquals(NavSearchOutcome.GOAL, withFastTool.outcome)
        assertEquals(NavSearchOutcome.GOAL, withSlowTool.outcome)
        assertTrue(
            (kindCounts(withFastTool)[NavMove.DIG] ?: 0) > 0,
            "with an instant tool, mining straight through should win",
        )
        assertEquals(
            0,
            kindCounts(withSlowTool)[NavMove.DIG] ?: 0,
            "with a hopeless tool, the detour should win",
        )
    }

    @Test
    fun `a goal buried in solid rock is tunnelled to within a sane budget`() {
        // Underground goals are the case where the heuristic is least useful: it prices remaining
        // distance at walking speed while every actual move costs a mining swing, so the search is
        // tempted to exhaust every cheap detour first. If this needs an implausible iteration budget
        // then digging routes do not work in practice, whatever the edges say.
        val size = 48
        val terrain = NavTestTerrain(size, 24, size, fill = NavBlockClass.SOLID_FULL)
        // A small pocket of air to stand in.
        terrain.box(1, floorY + 1, 1, 3, floorY + 2, 3, NavBlockClass.AIR)

        val goal = BlockPos(40, floorY + 1, 40)
        terrain.box(goal.x, goal.y, goal.z, goal.x, goal.y + 1, goal.z, NavBlockClass.AIR)

        val result = search(
            terrain,
            NavCaps(allowBreak = true, breakTicksPerHardness = 100.0),
            BlockPos(2, floorY + 1, 2),
            GoalBlock(goal),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome, "failed to tunnel to a buried goal")
        assertTrue((kindCounts(result)[NavMove.DIG] ?: 0) > 0, "reaching it requires mining")
        assertTrue(
            result.expansions < 50_000,
            "tunnelling took ${result.expansions} expansions, past the service's budget",
        )
    }

    // -----------------------------------------------------------------------
    //  Building
    // -----------------------------------------------------------------------

    @Test
    fun `a gap in the floor is bridged when placing is allowed`() {
        val terrain = corridor()
        for (z in 0 until 7) {
            for (x in 18..21) {
                terrain.set(x, floorY, z, NavBlockClass.AIR)
            }
        }

        val result = search(
            terrain, NavCaps(allowPlace = true, placeableBlocks = 64, allowParkour = false),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        assertTrue((kindCounts(result)[NavMove.PLACE] ?: 0) > 0, "the gap has to be bridged")
    }

    @Test
    fun `placement count is reported so a route can be checked against what is carried`() {
        // The capability flag only says placing is permitted; how much a whole route adds up to is
        // what decides whether the player can actually finish it.
        val terrain = corridor()
        for (z in 0 until 7) {
            for (x in 18..21) {
                terrain.set(x, floorY, z, NavBlockClass.AIR)
            }
        }

        val result = search(
            terrain, NavCaps(allowPlace = true, placeableBlocks = 64, allowParkour = false),
            BlockPos(1, floorY + 1, 3),
            GoalBlock(BlockPos(38, floorY + 1, 3)),
        )

        val counted = NavMove.placementsIn(result.moves)
        assertTrue(counted > 0)
        assertEquals(
            counted,
            result.moves.count { NavMove.consumesBlock(NavMove.kindOf(it)) },
            "the helper the service budgets with must agree with the moves on the route",
        )
    }

    // -----------------------------------------------------------------------
    //  Goals used by mining consumers
    // -----------------------------------------------------------------------

    @Test
    fun `GoalAdjacentTo stops within reach instead of on top of the target`() {
        // What a mining consumer wants: get close enough to interact, do not try to occupy the
        // block itself, which is impossible for anything solid.
        val terrain = corridor()
        val ore = BlockPos(30, floorY + 1, 3)
        terrain.set(ore.x, ore.y, ore.z, NavBlockClass.SOLID_FULL)

        val result = search(
            terrain, NavCaps.SAFE,
            BlockPos(1, floorY + 1, 3),
            GoalAdjacentTo(ore),
        )

        assertEquals(NavSearchOutcome.GOAL, result.outcome)
        val end = result.destination()
        assertNotEquals(
            BlockPos.asLong(ore.x, ore.y, ore.z),
            end,
            "the route must not end inside the block it is meant to reach",
        )

        val dx = BlockPos.getX(end) - ore.x
        val dy = BlockPos.getY(end) - ore.y
        val dz = BlockPos.getZ(end) - ore.z
        val distance = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        assertTrue(distance <= GoalAdjacentTo.DEFAULT_REACH, "ended $distance away, out of reach")
    }
}
