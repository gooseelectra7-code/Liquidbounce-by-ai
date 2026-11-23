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
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavBlockSource
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.navClass
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * The line a route is actually travelled along, as opposed to the chain of block centres it is
 * made of.
 *
 * A search works on a lattice, so its answer is a staircase: to cross a room diagonally it turns a
 * right angle at every block. Nobody walks that, and the executor does not either — it heads for the
 * furthest node of a level run it can still see in a straight line, which turns the staircase into
 * the diagonal it was approximating.
 *
 * That cut belongs here rather than inside the executor because two things need to agree about it.
 * The executor walks it; the renderer draws it. When they each had their own idea of it the picture
 * showed the staircase while the body took the diagonal, and the difference is exactly the width of
 * a room.
 */
object NavRouteLine {

    /**
     * Index of the last move of the level run starting at [moveIndex] whose destination can be
     * reached from [from] in a straight line.
     *
     * Returns [moveIndex] itself for anything that is not level walking: a jump, a drop, a dig and a
     * placement are each driven by their own rules and cannot be merged into a run.
     */
    fun endOfRun(path: NavPath, blocks: NavBlockSource, moveIndex: Int, from: Vec3): Int {
        if (!isLevelWalk(path.kindAt(moveIndex))) return moveIndex

        val runY = path.yAt(moveIndex + 1)
        var best = moveIndex

        var probe = moveIndex + 1
        while (probe < path.moveCount && probe - moveIndex < MAX_RUN) {
            if (!isLevelWalk(path.kindAt(probe))) break
            if (path.yAt(probe + 1) != runY) break
            if (!isStraightWalkable(blocks, from, path.centerAt(probe + 1), runY)) break
            best = probe
            probe++
        }
        return best
    }

    /**
     * Nodes the travelled line actually passes through, from the start of [path] to its end.
     *
     * Every entry is a node index; the line is the straight segments between consecutive entries.
     * Runs are cut from the node they start at rather than from wherever a body happens to be, which
     * makes this a property of the route alone — the same route always yields the same line.
     */
    fun corners(path: NavPath, blocks: NavBlockSource): IntArray {
        val kept = ArrayList<Int>(MIN_CORNERS)
        kept.add(0)

        var index = 0
        while (index < path.moveCount) {
            val end = endOfRun(path, blocks, index, path.centerAt(index))
            kept.add(end + 1)
            index = end + 1
        }

        return kept.toIntArray()
    }

    /**
     * Can a body walk the straight line between two points without clipping anything?
     *
     * Two different questions are asked of two different places, and conflating them was a bug worth
     * describing. *Room* is asked of all four block columns a 0.6-wide hitbox can touch: anything the
     * shoulders would meet stops the line, which is also what refuses the diagonal between two blocks
     * touching at a corner. *Footing* is asked only of the column under the middle of the body,
     * because that is all vanilla asks: a hitbox may hang a third of a block over a drop and does not
     * fall. Demanding solid ground under the corners as well meant no line along the top of a cliff
     * ever merged — so the body walked the lattice staircase, turning ninety degrees at every block,
     * and spent the whole cliff spinning between two waypoints a block apart.
     *
     * Reads come from the navigation cache, so this is a handful of array accesses rather than
     * collision queries.
     */
    fun isStraightWalkable(blocks: NavBlockSource, from: Vec3, to: Vec3, runY: Int): Boolean {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < MIN_SEGMENT) return true

        val steps = (distance / SAMPLE_STEP).toInt() + 1
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val sampleX = from.x + dx * t
            val sampleZ = from.z + dz * t

            val footingX = Math.floor(sampleX).toInt()
            val footingZ = Math.floor(sampleZ).toInt()
            if (!NavBlockClass.isStandable(blocks.navClass(footingX, runY - 1, footingZ))) return false

            for (cornerX in CORNER_OFFSETS) {
                for (cornerZ in CORNER_OFFSETS) {
                    val blockX = Math.floor(sampleX + cornerX).toInt()
                    val blockZ = Math.floor(sampleZ + cornerZ).toInt()
                    if (!hasRoom(blocks, blockX, runY, blockZ)) return false
                }
            }
        }
        return true
    }

    private fun hasRoom(blocks: NavBlockSource, x: Int, y: Int, z: Int): Boolean =
        NavBlockClass.isPassable(blocks.navClass(x, y, z)) &&
            NavBlockClass.isPassable(blocks.navClass(x, y + 1, z))

    private fun isLevelWalk(kind: Int): Boolean = kind == NavMove.WALK || kind == NavMove.DIAGONAL

    /** Waypoints of a level run to consider merging into one straight line. */
    const val MAX_RUN = 12

    /** Spacing of line-of-sight samples along a merged segment, in blocks. */
    const val SAMPLE_STEP = 0.4

    /** Half-width of the player hitbox, so the check covers the corners it can clip. */
    @JvmField
    val CORNER_OFFSETS = doubleArrayOf(-0.31, 0.31)

    /** Below this a segment is shorter than the sampling step and trivially clear. */
    private const val MIN_SEGMENT = 1.0E-3

    /** Starting size for the corner list; enough for a short route without growing. */
    private const val MIN_CORNERS = 16
}
