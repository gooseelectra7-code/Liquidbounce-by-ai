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
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavStandingSpotTest {

    private val floorY = 8

    private fun flat(): NavTestTerrain = NavTestTerrain(16, 40, 16).apply { floor(floorY) }

    private fun resolve(terrain: NavTestTerrain, x: Int, y: Int, z: Int): BlockPos =
        NavStandingSpot.resolve(terrain, BlockPos(x, y, z))

    // -----------------------------------------------------------------------
    //  Occupancy
    // -----------------------------------------------------------------------

    @Test
    fun `a spot on the floor is occupiable`() {
        val terrain = flat()

        assertTrue(NavStandingSpot.canOccupy(terrain, 8, floorY + 1, 8, NavCaps.TRAVEL))
        assertEquals(NavStandingSpot.STANDABLE, NavStandingSpot.describe(terrain, 8, floorY + 1, 8, NavCaps.TRAVEL))
    }

    @Test
    fun `a spot inside solid ground is not occupiable`() {
        val terrain = flat()

        assertFalse(NavStandingSpot.canOccupy(terrain, 8, floorY, 8, NavCaps.TRAVEL))
        assertTrue(NavStandingSpot.describe(terrain, 8, floorY, 8, NavCaps.TRAVEL).contains("feet blocked"))
    }

    @Test
    fun `a spot in mid-air is not occupiable and says why`() {
        val terrain = flat()

        assertFalse(NavStandingSpot.canOccupy(terrain, 8, floorY + 10, 8, NavCaps.TRAVEL))
        assertTrue(NavStandingSpot.describe(terrain, 8, floorY + 10, 8, NavCaps.TRAVEL).contains("nothing to stand on"))
    }

    @Test
    fun `swimming counts as holding a position only when allowed`() {
        val terrain = NavTestTerrain(16, 40, 16)
        terrain.box(0, 0, 0, 15, 20, 15, NavBlockClass.WATER)

        assertTrue(NavStandingSpot.canOccupy(terrain, 8, 10, 8, NavCaps.TRAVEL))
        assertFalse(NavStandingSpot.canOccupy(terrain, 8, 10, 8, NavCaps(allowSwim = false)))
    }

    @Test
    fun `a ladder holds the player without any floor`() {
        val terrain = NavTestTerrain(16, 40, 16)
        terrain.box(8, 10, 8, 8, 20, 8, NavBlockClass.CLIMBABLE)

        assertTrue(NavStandingSpot.canOccupy(terrain, 8, 15, 8, NavCaps(allowSwim = false)))
    }

    // -----------------------------------------------------------------------
    //  Resolution
    // -----------------------------------------------------------------------

    @Test
    fun `an already valid spot is returned untouched`() {
        val terrain = flat()

        assertEquals(BlockPos(8, floorY + 1, 8), resolve(terrain, 8, floorY + 1, 8))
    }

    @Test
    fun `pointing at the ground resolves upward onto it`() {
        // Plants have no collision, so the crosshair ray reports the dirt underneath them. Asking to
        // stand inside that block is unreachable by construction.
        val terrain = flat()

        assertEquals(BlockPos(8, floorY + 1, 8), resolve(terrain, 8, floorY, 8))
    }

    @Test
    fun `a spot in mid-air resolves down to the ground below it`() {
        val terrain = flat()

        assertEquals(BlockPos(8, floorY + 1, 8), resolve(terrain, 8, floorY + 20, 8))
    }

    @Test
    fun `dropping stops at the first surface rather than falling through the world`() {
        val terrain = flat()
        // A ledge halfway up: a target above it should land on the ledge, not the floor.
        terrain.set(8, floorY + 10, 8, NavBlockClass.SOLID_FULL)

        assertEquals(BlockPos(8, floorY + 11, 8), resolve(terrain, 8, floorY + 20, 8))
    }

    @Test
    fun `dropping does not tunnel into a cave beneath solid ground`() {
        val terrain = flat()
        // Hollow room under the floor. A mid-air target above the floor must not resolve into it.
        terrain.box(0, floorY - 4, 0, 15, floorY - 2, 15, NavBlockClass.AIR)
        terrain.floor(floorY - 5)

        val resolved = resolve(terrain, 8, floorY + 15, 8)

        assertEquals(BlockPos(8, floorY + 1, 8), resolved, "should stop on top of the floor, not below it")
    }

    @Test
    fun `lifting is bounded so a wall does not retarget somewhere far above`() {
        val terrain = NavTestTerrain(16, 40, 16)
        terrain.floor(floorY)
        // A tall solid pillar: there is headroom, but only well out of reach.
        terrain.box(8, floorY + 1, 8, 8, floorY + 20, 8, NavBlockClass.SOLID_FULL)

        val resolved = resolve(terrain, 8, floorY + 1, 8)

        assertEquals(BlockPos(8, floorY + 1, 8), resolved, "unresolvable targets come back unchanged")
    }

    @Test
    fun `a target with nothing anywhere below comes back unchanged`() {
        val terrain = NavTestTerrain(16, 40, 16)

        assertEquals(BlockPos(8, 20, 8), resolve(terrain, 8, 20, 8))
    }

    @Test
    fun `resolution always produces something the planner accepts, or gives up honestly`() {
        // The property that matters: a resolved goal is either occupiable, or unchanged so the
        // caller can report a real failure. Silently moving the goal somewhere unreachable would
        // reproduce exactly the bug this exists to prevent.
        val terrain = flat()
        terrain.box(4, floorY + 1, 4, 6, floorY + 6, 6, NavBlockClass.SOLID_FULL)
        terrain.box(10, floorY + 1, 10, 12, floorY + 3, 12, NavBlockClass.WATER)

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in floorY - 2..floorY + 12) {
                    val requested = BlockPos(x, y, z)
                    val resolved = NavStandingSpot.resolve(terrain, requested)
                    val ok = NavStandingSpot.canOccupy(terrain, resolved.x, resolved.y, resolved.z, NavCaps.TRAVEL) ||
                        resolved == requested
                    assertTrue(ok, "resolved $requested to $resolved, which is neither valid nor unchanged")
                }
            }
        }
    }
}
