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

/**
 * Scratch space a planner writes candidate edges into, reused for every expansion.
 *
 * The old engine returned a fresh `ArrayList<WeightedLongEdge>` from its neighbour function and
 * filled it with newly constructed `Movement` objects — dozens of allocations per node, hundreds of
 * thousands per search. A planner writing into this buffer allocates nothing: the move is a byte
 * that the planner alone knows how to interpret, and concrete move objects are built only for the
 * handful of edges that end up on the final path.
 */
class NavEdgeBuffer(capacity: Int = DEFAULT_CAPACITY) {

    /** Packed destination positions. */
    var nodes = LongArray(capacity)
        private set

    /** Edge cost in ticks, parallel to [nodes]. */
    var costs = DoubleArray(capacity)
        private set

    /** Planner-defined move id, parallel to [nodes]. */
    var moves = ByteArray(capacity)
        private set

    /** Number of edges written. */
    var size: Int = 0
        private set

    /**
     * True when [add] ran out of room and had to grow the buffer.
     *
     * Not a failure any more — it used to mean an edge was thrown away — but still worth watching:
     * a planner that regularly outgrows its buffer is one whose worst case nobody has thought about
     * lately, and the tests assert it stays false for ordinary terrain.
     */
    var overflowed: Boolean = false
        private set

    val capacity: Int get() = nodes.size

    private fun grow() {
        val grown = if (nodes.isEmpty()) DEFAULT_CAPACITY else nodes.size * 2
        nodes = nodes.copyOf(grown)
        costs = costs.copyOf(grown)
        moves = moves.copyOf(grown)
    }

    fun clear() {
        size = 0
        overflowed = false
    }

    /**
     * Records one candidate edge.
     *
     * Edges of infinite or negative cost are rejected outright: infinity is how a planner says
     * "impossible", and a negative cost would break A* outright rather than merely mislead it.
     */
    fun add(node: Long, cost: Double, move: Byte) {
        if (!cost.isFinite() || cost < 0.0) return
        if (size == nodes.size) {
            // Grown rather than dropped. Nothing ever read the overflow flag, so a planner that
            // produced more successors than would fit — a taller step-up, a longer jump, anything
            // added later — silently lost the rest of them and the search reported the goal
            // unreachable with no way to tell why. The buffer is one per planner and reused for
            // every node, so this happens a handful of times at most and then never again.
            overflowed = true
            grow()
        }
        nodes[size] = node
        costs[size] = cost
        moves[size] = move
        size++
    }

    companion object {
        /**
         * Room for every edge a ground node can produce: four cardinals plus four diagonals, each
         * available as walk, ascend, descend and dig, plus falls, climbs, swims, pillar and place.
         * Sized generously — it is one array per planner, allocated once.
         */
        const val DEFAULT_CAPACITY = 64
    }
}
