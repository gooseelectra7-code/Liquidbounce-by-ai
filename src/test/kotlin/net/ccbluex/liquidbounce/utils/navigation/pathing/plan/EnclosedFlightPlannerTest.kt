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

import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchWorkspace
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnclosedFlightPlannerTest {

    private val workspace = NavSearchWorkspace()

    /** A roofed hall: air throughout, floor at the bottom, ceiling at the top. */
    private fun hall(sizeX: Int = 160, sizeY: Int = 48, sizeZ: Int = 64): NavTestTerrain =
        NavTestTerrain(sizeX, sizeY, sizeZ).apply {
            floor(0)
            floor(sizeY - 1)
        }

    private fun plan(
        terrain: NavTestTerrain,
        from: BlockPos = BlockPos(8, 24, 32),
        to: BlockPos = BlockPos(150, 24, 32),
    ) = EnclosedFlightPlanner.plan(terrain, from, to, workspace)

    @Test
    fun `a route is found across a roofed hall`() {
        val route = plan(hall())

        assertNotNull(route)
        assertTrue(route!!.enclosed, "a searched route is not an open-sky one")
        assertTrue(route.reachedGoal)
        assertTrue(route.waypoints.size >= 2)
    }

    @Test
    fun `the route stays inside the hall`() {
        val terrain = hall()
        val route = plan(terrain)!!

        for (point in route.waypoints) {
            assertTrue(point.y > 0.0 && point.y < 47.0, "left the hall at $point")
        }
    }

    @Test
    fun `a straight run is not cluttered with the lattice's corners`() {
        // Nothing is in the way, so the whole crossing should be a couple of points rather than one
        // per cell — a glider that turns every four blocks arrives slowly or not at all.
        val route = plan(hall())!!

        assertTrue(route.waypoints.size <= 4, "unsmoothed: ${route.waypoints.size} points")
    }

    @Test
    fun `a wall across the hall is flown around, not through`() {
        val terrain = hall()
        // Full-height wall at x = 80, open only past z = 44.
        terrain.box(80, 1, 0, 82, 46, 44, NavBlockClass.SOLID_FULL)

        val route = plan(terrain)
        assertNotNull(route, "there is a way round")

        // The route is a polyline, and smoothing means its corners need not land near the wall —
        // so the thing to check is the line itself, not the points that describe it.
        for (point in samplesAlong(route!!)) {
            val insideWall = point.x >= 80.0 && point.x <= 83.0 && point.z <= 45.0 && point.y >= 1.0
            assertTrue(!insideWall, "flew through the wall at $point")
        }
    }

    @Test
    fun `crossing a large cavern stays within its budget`() {
        // A big open volume is the worst case for a search in three dimensions: nothing prunes it
        // but the heuristic, and a frontier that grows as a sphere fills the cavern rather than
        // crossing it. The bound is deliberately loose — it is here to catch that, not to measure
        // the machine.
        val cavern = NavTestTerrain(256, 64, 256).apply {
            floor(0)
            floor(63)
        }

        // Warm the code paths first: this is a bound on the algorithm, not on the JIT.
        EnclosedFlightPlanner.plan(cavern, BlockPos(8, 32, 128), BlockPos(248, 32, 128), workspace)

        val startedAt = System.nanoTime()
        val route = EnclosedFlightPlanner.plan(cavern, BlockPos(8, 32, 128), BlockPos(248, 32, 128), workspace)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0

        val search = EnclosedFlightPlanner.lastSearch!!
        assertNotNull(route)
        assertTrue(route!!.reachedGoal, "a clear cavern should be crossed, not merely entered")

        // Sixty cells of crossing should cost about sixty expansions. Ballooning here is the search
        // filling the cavern instead of crossing it, which is the failure this test exists for.
        assertTrue(
            search.expansions < CAVERN_EXPANSION_BUDGET,
            "crossing took ${search.expansions} expansions, ${search.discovered} seen",
        )
        assertTrue(
            elapsedMs < CAVERN_MILLIS_BUDGET,
            "took %.0f ms to cross an empty cavern (%d expansions)".format(elapsedMs, search.expansions),
        )
    }

    /** Sixty cells of crossing, with room for the heuristic to wander a little. */
    private val CAVERN_EXPANSION_BUDGET = 250

    /** Loose: warm, the crossing measures about two milliseconds. */
    private val CAVERN_MILLIS_BUDGET = 50.0

    /** Walks a route's polyline in one-block steps, which is what actually gets flown. */
    private fun samplesAlong(route: ElytraRoute): List<Vec3> = buildList {
        for ((from, to) in route.waypoints.zipWithNext()) {
            val steps = from.distanceTo(to).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                add(from.lerp(to, step.toDouble() / steps))
            }
        }
    }

    @Test
    fun `a sealed hall has no route`() {
        val terrain = hall()
        terrain.box(80, 0, 0, 82, 47, 63, NavBlockClass.SOLID_FULL)

        val route = plan(terrain)
        // Best effort may still return the reachable side, but it must never claim the goal.
        assertTrue(route == null || !route.reachedGoal, "claimed to reach a sealed-off goal")
    }

    @Test
    fun `no room to fly where we stand means no flight`() {
        val terrain = hall()
        // Packed solid from the feet to well above them, so no take-off clears it.
        terrain.box(4, 20, 28, 12, 44, 36, NavBlockClass.SOLID_FULL)

        assertNull(plan(terrain), "buried in rock is not a flight, it is a dig")
    }

    @Test
    fun `standing where the lattice happens to be aligned badly is still a flight`() {
        // The cell a player's feet fall in contains the floor a good part of the time, and refusing
        // on that alone would refuse a quarter of all flights for no reason anyone could see.
        val terrain = NavTestTerrain(160, 48, 64).apply {
            floor(0)
            floor(46)
            floor(47)
        }
        // Feet at y = 23 with the ground at 22: the enclosing cell runs 20..23 and holds that floor.
        terrain.floor(22)

        assertNotNull(
            EnclosedFlightPlanner.plan(terrain, BlockPos(8, 23, 32), BlockPos(150, 23, 32), workspace),
            "should have lifted the start into the air above the floor",
        )
    }

    @Test
    fun `unloaded space is not flown into`() {
        // Under a roof, space we have not received is exactly where guessing flies into rock. The
        // hall is 64 deep; a goal beyond the edge of it is beyond what has been loaded.
        val route = plan(hall(), to = BlockPos(300, 24, 32))

        assertTrue(route == null || !route.reachedGoal, "planned a route into unloaded space")
        route?.waypoints?.forEach { point: Vec3 ->
            assertTrue(point.x < 160.0, "left the loaded box at $point")
        }
    }
}
