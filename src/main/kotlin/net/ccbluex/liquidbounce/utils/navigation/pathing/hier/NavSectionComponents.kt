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
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavBlockSource
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavStandingSpot
import net.minecraft.core.BlockPos

/**
 * The places a body can stand inside one 16×16×16 section, grouped into what is reachable from what.
 *
 * This is the brick the hierarchical layer is built from. Two cells in the same component are
 * connected by ordinary walking *within this section*; two cells in different components are not,
 * however close they look — a wall through the middle of a section, or a ledge above a drop, is
 * exactly the situation a coarse "is there floor here" abstraction gets wrong and this one does not.
 *
 * Deliberately conservative about how cells connect: level steps and single steps up or down, and
 * nothing else. Jumps, falls and digging can join components the fill considers separate, so the
 * abstraction may claim two places are unconnected when the real planner could bridge them. That
 * direction is the safe one — the corridor is guidance for the detailed search, never a wall, and a
 * search that ignores it still finds what is really there.
 */
class NavSectionComponents(
    /** World position of the section's minimum corner. */
    val originX: Int,
    val originY: Int,
    val originZ: Int,

    /** Component index per cell, in `(x * EDGE + y) * EDGE + z` order, or [NONE]. */
    private val cells: ByteArray,

    /** How many components the section holds. */
    val count: Int,

    /** One packed position per component, near its middle, used for the abstract graph's geometry. */
    val representatives: LongArray,
) {

    /** Component index at a world position, or [NONE] when nothing can stand there. */
    fun componentAt(x: Int, y: Int, z: Int): Int {
        val localX = x - originX
        val localY = y - originY
        val localZ = z - originZ
        if (localX !in 0 until EDGE || localY !in 0 until EDGE || localZ !in 0 until EDGE) return NONE
        return cells[index(localX, localY, localZ)].toInt()
    }

    /** Component index at a local cell, without bounds checks. */
    internal fun localComponent(localX: Int, localY: Int, localZ: Int): Int =
        cells[index(localX, localY, localZ)].toInt()

    override fun toString(): String = "NavSectionComponents($originX, $originY, $originZ: $count)"

    companion object {

        /** Edge of a section, in blocks. */
        const val EDGE = 16

        /** No component: nothing can stand in this cell. */
        const val NONE = -1

        /**
         * Beyond this a section is not usefully abstracted anyway, and the index has to fit a byte.
         *
         * A section with this many separate standable pockets is a cave system in miniature; the
         * detailed search is the right tool for it, and the guide simply says less about it.
         */
        const val MAX_COMPONENTS = 120

        /**
         * Floods one section, or returns null when nothing in it can be stood on.
         *
         * Reads one block beyond the section in every direction, because standing is a property of
         * three cells stacked — a floor below the section's bottom row still holds a body up.
         */
        fun of(
            source: NavBlockSource,
            sectionX: Int,
            sectionY: Int,
            sectionZ: Int,
            caps: NavCaps,
        ): NavSectionComponents? {
            val originX = sectionX * EDGE
            val originY = sectionY * EDGE
            val originZ = sectionZ * EDGE

            val standable = BooleanArray(CELLS)
            var any = false
            for (localX in 0 until EDGE) {
                for (localY in 0 until EDGE) {
                    for (localZ in 0 until EDGE) {
                        val occupiable = NavStandingSpot.canOccupy(
                            source,
                            originX + localX,
                            originY + localY,
                            originZ + localZ,
                            caps,
                        )
                        if (occupiable) {
                            standable[index(localX, localY, localZ)] = true
                            any = true
                        }
                    }
                }
            }
            if (!any) return null

            val cells = ByteArray(CELLS) { NONE.toByte() }
            val representatives = ArrayList<Long>()
            val queue = IntArray(CELLS)

            var component = 0
            for (start in 0 until CELLS) {
                if (!standable[start] || cells[start].toInt() != NONE) continue
                if (component >= MAX_COMPONENTS) break

                representatives.add(fill(standable, cells, queue, start, component, originX, originY, originZ))
                component++
            }

            return NavSectionComponents(
                originX, originY, originZ,
                cells, component, representatives.toLongArray(),
            )
        }

        /**
         * Breadth-first fill of one component, returning a representative position for it.
         *
         * The representative is the cell nearest the component's own centre of mass, so the abstract
         * graph measures distances between places that are actually in the component rather than
         * between section corners.
         */
        @Suppress("LongParameterList")
        private fun fill(
            standable: BooleanArray,
            cells: ByteArray,
            queue: IntArray,
            start: Int,
            component: Int,
            originX: Int,
            originY: Int,
            originZ: Int,
        ): Long {
            var head = 0
            var tail = 0
            queue[tail++] = start
            cells[start] = component.toByte()

            var sumX = 0
            var sumY = 0
            var sumZ = 0
            var size = 0

            while (head < tail) {
                val cell = queue[head++]
                val localX = cell / (EDGE * EDGE)
                val localY = (cell / EDGE) % EDGE
                val localZ = cell % EDGE

                sumX += localX
                sumY += localY
                sumZ += localZ
                size++

                for (direction in NEIGHBOURS) {
                    val nx = localX + direction[0]
                    val ny = localY + direction[1]
                    val nz = localZ + direction[2]
                    if (nx !in 0 until EDGE || ny !in 0 until EDGE || nz !in 0 until EDGE) continue

                    val neighbour = index(nx, ny, nz)
                    if (!standable[neighbour] || cells[neighbour].toInt() != NONE) continue

                    cells[neighbour] = component.toByte()
                    queue[tail++] = neighbour
                }
            }

            // Centre of mass, snapped to a cell that is genuinely in the component.
            val centreX = sumX / size
            val centreY = sumY / size
            val centreZ = sumZ / size
            var best = queue[0]
            var bestDistance = Int.MAX_VALUE
            for (i in 0 until tail) {
                val cell = queue[i]
                val dx = cell / (EDGE * EDGE) - centreX
                val dy = (cell / EDGE) % EDGE - centreY
                val dz = cell % EDGE - centreZ
                val distance = dx * dx + dy * dy + dz * dz
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = cell
                }
            }

            return BlockPos.asLong(
                originX + best / (EDGE * EDGE),
                originY + (best / EDGE) % EDGE,
                originZ + best % EDGE,
            )
        }

        private fun index(localX: Int, localY: Int, localZ: Int): Int = (localX * EDGE + localY) * EDGE + localZ

        private const val CELLS = EDGE * EDGE * EDGE

        /**
         * How cells join up inside a section: the four level steps, and the same steps a block up or
         * down.
         *
         * Diagonals are left out on purpose. A diagonal step needs both of the cells it squeezes
         * between to be clear, which this fill does not check — and a fill that joins components
         * through a corner the body cannot pass would make the guide claim a way through that is not
         * there. Missing a connection is recoverable; inventing one is not.
         */
        private val NEIGHBOURS = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
            intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
            intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0),
            intArrayOf(0, 1, 1), intArrayOf(0, 1, -1),
            intArrayOf(1, -1, 0), intArrayOf(-1, -1, 0),
            intArrayOf(0, -1, 1), intArrayOf(0, -1, -1),
            // Straight up and down. Two standable cells can only be stacked when the upper one is a
            // ladder or water — standing needs solid ground below, and the lower cell's feet are not
            // that — so this step is always a climb or a swim, and always real.
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        )
    }
}
