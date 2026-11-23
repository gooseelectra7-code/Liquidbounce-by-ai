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

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.minecraft.core.BlockPos
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * A synthetic voxel graph for exercising the search engine without a Minecraft world.
 *
 * Cells are either open or blocked, and entering an open cell costs a per-cell amount drawn from
 * `[MIN_COST_PER_BLOCK, 3 x MIN_COST_PER_BLOCK]`. Staying at or above
 * [NavTiming.MIN_COST_PER_BLOCK] is what keeps the goal heuristics admissible, so an optimal search
 * over this graph must agree exactly with Dijkstra.
 *
 * Movement is six-connected (four cardinals plus up and down). The goal heuristics assume diagonals
 * are available and therefore under-estimate here, which is fine — under-estimating is the whole
 * requirement.
 */
internal class NavTestGrid(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    private val blocked: BooleanArray,
    private val enterCost: DoubleArray,
) {

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ

    fun isOpen(x: Int, y: Int, z: Int): Boolean = contains(x, y, z) && !blocked[index(x, y, z)]

    fun costToEnter(x: Int, y: Int, z: Int): Double = enterCost[index(x, y, z)]

    fun block(x: Int, y: Int, z: Int) {
        blocked[index(x, y, z)] = true
    }

    fun open(x: Int, y: Int, z: Int) {
        blocked[index(x, y, z)] = false
    }

    /** Seals every cell around [x], [y], [z] so nothing can leave it. */
    fun seal(x: Int, y: Int, z: Int) {
        for (direction in DIRECTIONS) {
            val nx = x + direction[0]
            val ny = y + direction[1]
            val nz = z + direction[2]
            if (contains(nx, ny, nz)) block(nx, ny, nz)
        }
    }

    private fun index(x: Int, y: Int, z: Int): Int = (x * sizeY + y) * sizeZ + z

    fun pack(x: Int, y: Int, z: Int): Long = BlockPos.asLong(x, y, z)

    /** Six-connected expansion, usable directly as the search's neighbour function. */
    fun neighbors(): NavNeighborFunction = NavNeighborFunction { node, out ->
        val x = BlockPos.getX(node)
        val y = BlockPos.getY(node)
        val z = BlockPos.getZ(node)
        for (moveId in DIRECTIONS.indices) {
            val direction = DIRECTIONS[moveId]
            val nx = x + direction[0]
            val ny = y + direction[1]
            val nz = z + direction[2]
            if (!isOpen(nx, ny, nz)) continue
            out.add(pack(nx, ny, nz), costToEnter(nx, ny, nz), moveId.toByte())
        }
    }

    /** Applies the move byte the neighbour function recorded, for path validation. */
    fun applyMove(node: Long, move: Byte): Long {
        val direction = DIRECTIONS[move.toInt()]
        return pack(
            BlockPos.getX(node) + direction[0],
            BlockPos.getY(node) + direction[1],
            BlockPos.getZ(node) + direction[2],
        )
    }

    /**
     * Reference shortest-path costs from [start] to every reachable cell.
     *
     * Deliberately a plain, obvious Dijkstra with a boxed priority queue — it is the oracle, so it
     * should be easy to read rather than fast, and it must share no code with the engine under test.
     */
    fun dijkstra(start: Long): Long2DoubleOpenHashMap {
        val distances = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        distances.put(start, 0.0)

        val queue = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        queue.add(start to 0.0)

        while (queue.isNotEmpty()) {
            val (node, distance) = queue.poll()
            if (distance > distances.get(node)) continue

            val x = BlockPos.getX(node)
            val y = BlockPos.getY(node)
            val z = BlockPos.getZ(node)
            for (direction in DIRECTIONS) {
                val nx = x + direction[0]
                val ny = y + direction[1]
                val nz = z + direction[2]
                if (!isOpen(nx, ny, nz)) continue

                val neighbor = pack(nx, ny, nz)
                val candidate = distance + costToEnter(nx, ny, nz)
                if (candidate < distances.get(neighbor)) {
                    distances.put(neighbor, candidate)
                    queue.add(neighbor to candidate)
                }
            }
        }
        return distances
    }

    companion object {
        /** Cardinals plus vertical. Index doubles as the move byte. */
        private val DIRECTIONS = arrayOf(
            intArrayOf(1, 0, 0),
            intArrayOf(-1, 0, 0),
            intArrayOf(0, 0, 1),
            intArrayOf(0, 0, -1),
            intArrayOf(0, 1, 0),
            intArrayOf(0, -1, 0),
        )

        /** An entirely open grid with uniform costs. */
        fun open(sizeX: Int, sizeY: Int, sizeZ: Int): NavTestGrid {
            val cells = sizeX * sizeY * sizeZ
            return NavTestGrid(
                sizeX, sizeY, sizeZ,
                blocked = BooleanArray(cells),
                enterCost = DoubleArray(cells) { NavTiming.MIN_COST_PER_BLOCK },
            )
        }

        /**
         * A grid with random obstacles and random per-cell costs.
         *
         * @param blockedFraction share of cells made impassable, 0..1
         */
        fun random(
            sizeX: Int,
            sizeY: Int,
            sizeZ: Int,
            seed: Int,
            blockedFraction: Double = 0.25,
        ): NavTestGrid {
            val random = Random(seed)
            val cells = sizeX * sizeY * sizeZ
            return NavTestGrid(
                sizeX, sizeY, sizeZ,
                blocked = BooleanArray(cells) { random.nextDouble() < blockedFraction },
                enterCost = DoubleArray(cells) {
                    // Never below the admissibility floor, or the heuristic would overestimate and
                    // the comparison against Dijkstra would fail for the right reason but the wrong
                    // component.
                    NavTiming.MIN_COST_PER_BLOCK * (1.0 + random.nextDouble() * 2.0)
                },
            )
        }
    }
}
