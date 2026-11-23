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
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavTestTerrain
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NavSectionGraphTest {

    private val caps = NavCaps.TRAVEL

    /**
     * The graph caches pockets by world position, and every test here builds a different world at
     * the same coordinates.
     */
    @BeforeEach
    fun clearCache() {
        NavSectionGraph.clear()
    }

    /** A floored plain, big enough to span many sections. */
    private fun plain(sizeX: Int = 160, sizeZ: Int = 160): NavTestTerrain =
        NavTestTerrain(sizeX, 16, sizeZ).apply { floor(0) }

    @Test
    fun `an open plain gets a corridor straight across it`() {
        val corridor = NavSectionGraph.route(plain(), BlockPos(8, 1, 8), BlockPos(150, 1, 8), caps)

        assertNotNull(corridor)
        assertTrue(corridor!!.points.size >= 2)
        // Nothing is in the way, so the corridor should be barely longer than the straight line.
        assertTrue(corridor.length < 200.0, "wandered: ${corridor.length} blocks for a 142-block hop")
    }

    @Test
    fun `a wall is routed around, not through`() {
        val terrain = plain()
        // A wall across the middle, open only along the far edge.
        terrain.box(80, 1, 0, 81, 15, 140, NavBlockClass.SOLID_FULL)

        val corridor = NavSectionGraph.route(terrain, BlockPos(8, 1, 8), BlockPos(150, 1, 8), caps)

        assertNotNull(corridor, "there is a way round the end of the wall")
        // Anything crossing the wall's line has to do it past the end of the wall.
        val crossings = corridor!!.points.filter { it.x in 78..84 }
        assertTrue(crossings.all { it.z > 138 }, "corridor crosses the wall itself: $crossings")
    }

    @Test
    fun `a walled-off destination has no corridor`() {
        val terrain = plain()
        terrain.box(80, 1, 0, 81, 15, 159, NavBlockClass.SOLID_FULL)

        assertNull(
            NavSectionGraph.route(terrain, BlockPos(8, 1, 8), BlockPos(150, 1, 8), caps),
            "nothing connects the two halves",
        )
    }

    @Test
    fun `a destination with nowhere to stand gets no corridor`() {
        // Not an error: the coarse layer simply has nothing to say, and the detailed search runs
        // exactly as it would have anyway.
        val terrain = plain()
        terrain.box(140, 0, 0, 159, 15, 159, NavBlockClass.SOLID_FULL)

        assertNull(NavSectionGraph.route(terrain, BlockPos(8, 1, 8), BlockPos(150, 8, 8), caps))
    }

    @Test
    fun `a goal in mid-air is resolved down onto the ground under it`() {
        // Goals come from crosshairs and from moving targets, so most of them are not standable
        // positions to begin with.
        val corridor = NavSectionGraph.route(plain(), BlockPos(8, 1, 8), BlockPos(150, 6, 8), caps)

        assertNotNull(corridor)
    }

    @Test
    fun `changing a block forgets the pocket it was in`() {
        val terrain = plain()
        assertNotNull(NavSectionGraph.route(terrain, BlockPos(8, 1, 8), BlockPos(150, 1, 8), caps))

        // Wall it off after the fact; without invalidation the graph would still route through.
        terrain.box(80, 1, 0, 81, 15, 159, NavBlockClass.SOLID_FULL)
        for (x in 80..81) {
            for (y in 1..15) {
                for (z in 0..159) {
                    NavSectionGraph.invalidate(x, y, z)
                }
            }
        }

        assertNull(
            NavSectionGraph.route(terrain, BlockPos(8, 1, 8), BlockPos(150, 1, 8), caps),
            "stale pockets would send the search down a corridor that no longer exists",
        )
    }
}
