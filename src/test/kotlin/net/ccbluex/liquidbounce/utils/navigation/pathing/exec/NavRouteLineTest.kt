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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavTestTerrain
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The line the executor walks and the renderer draws.
 *
 * Both read it from here, so what these assert is not a detail of either: it is the agreement
 * between them.
 */
class NavRouteLineTest {

    /** A lattice staircase across open ground is one straight line, not eight right angles. */
    @Test
    fun `open staircase collapses to a single line`() {
        val terrain = flatTerrain()
        val path = staircase(from = BlockPos(4, FLOOR + 1, 4), steps = 4)

        val corners = NavRouteLine.corners(path, terrain)

        assertArrayEquals(intArrayOf(0, path.size - 1), corners)
    }

    /** A wall across the diagonal has to break the line at the node the body can still reach. */
    @Test
    fun `a wall stops the line being cut`() {
        val terrain = flatTerrain()
        // A pillar in the middle of the staircase, exactly where the cut line would pass.
        terrain.box(6, FLOOR + 1, 6, 6, FLOOR + 2, 6, NavBlockClass.SOLID_FULL)

        val path = staircase(from = BlockPos(4, FLOOR + 1, 4), steps = 4)
        val corners = NavRouteLine.corners(path, terrain)

        assertTrue(corners.size > 2, "expected the wall to break the line, got ${corners.toList()}")
    }

    /**
     * A body may hang over a drop; only the ground under its middle has to be there.
     *
     * This is the cliff-edge case reported from play. With footing demanded under all four corners
     * of the hitbox no run along the top of a cliff ever merged, and the character walked the
     * staircase — turning ninety degrees at every block, in full view of the drop.
     */
    @Test
    fun `a line along a cliff edge still merges`() {
        val terrain = NavTestTerrain(24, 16, 24)
        // Ground on one side of z = 10 only: everything beyond it is open air.
        for (x in 0 until 24) {
            for (z in 0..10) {
                terrain.set(x, FLOOR, z, NavBlockClass.SOLID_FULL)
            }
        }

        val walkable = NavRouteLine.isStraightWalkable(
            terrain,
            from = Vec3(4.5, 0.0, 10.5),
            to = Vec3(12.5, 0.0, 10.5),
            runY = FLOOR + 1,
        )

        assertTrue(walkable, "walking the row of blocks along a cliff top is legal")
    }

    /** A hole in the floor under the middle of the line is not. */
    @Test
    fun `a gap in the floor breaks the line`() {
        val terrain = flatTerrain()
        terrain.set(8, FLOOR, 10, NavBlockClass.AIR)

        val walkable = NavRouteLine.isStraightWalkable(
            terrain,
            from = Vec3(4.5, 0.0, 10.5),
            to = Vec3(12.5, 0.0, 10.5),
            runY = FLOOR + 1,
        )

        assertFalse(walkable)
    }

    /** Anything that is not level walking is its own leg: a run cannot swallow a jump or a drop. */
    @Test
    fun `a run ends at a move of another kind`() {
        val terrain = flatTerrain()
        val positions = longArrayOf(
            BlockPos.asLong(4, FLOOR + 1, 4),
            BlockPos.asLong(5, FLOOR + 1, 4),
            BlockPos.asLong(6, FLOOR + 1, 4),
            BlockPos.asLong(7, FLOOR + 2, 4),
        )
        val moves = byteArrayOf(
            NavMove.encode(NavMove.WALK, 0),
            NavMove.encode(NavMove.WALK, 0),
            NavMove.encode(NavMove.ASCEND, 0),
        )
        val path = NavPath(positions, moves, totalCost = 0.0, reachedGoal = true)

        val end = NavRouteLine.endOfRun(path, terrain, moveIndex = 0, from = path.centerAt(0))

        assertEquals(1, end, "the run may reach the foot of the step and no further")
    }

    // -----------------------------------------------------------------------

    private fun flatTerrain(): NavTestTerrain = NavTestTerrain(24, 16, 24).apply { floor(FLOOR) }

    /**
     * The lattice's answer to a diagonal: alternating cardinal steps, all on the level.
     *
     * Written as [NavMove.WALK] rather than [NavMove.DIAGONAL] on purpose — it is the shape that
     * makes a character spin, because consecutive legs meet at a right angle.
     */
    private fun staircase(from: BlockPos, steps: Int): NavPath {
        val positions = ArrayList<Long>(steps * 2 + 1)
        var x = from.x
        var z = from.z
        positions.add(BlockPos.asLong(x, from.y, z))

        repeat(steps) {
            x++
            positions.add(BlockPos.asLong(x, from.y, z))
            z++
            positions.add(BlockPos.asLong(x, from.y, z))
        }

        val moves = ByteArray(positions.size - 1) { NavMove.encode(NavMove.WALK, 0) }
        return NavPath(positions.toLongArray(), moves, totalCost = 0.0, reachedGoal = true)
    }

    private companion object {
        /** Y of the floor; bodies stand on [FLOOR] + 1. */
        const val FLOOR = 4
    }
}
