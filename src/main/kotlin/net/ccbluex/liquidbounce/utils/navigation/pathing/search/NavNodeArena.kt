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

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

/**
 * Storage for every node a search has touched, laid out as parallel primitive arrays.
 *
 * The old engine allocated a `Movement` object per candidate edge plus a boxed queue entry per
 * relaxation, and recorded the chosen edge in a nested map of wrapper objects — roughly forty
 * allocations for every node it expanded. Here a node is an `int` index into flat arrays and the
 * chosen edge is a single byte, so expanding a node allocates nothing at all.
 *
 * Node ids are handed out in discovery order and stay stable for the life of a search, which is what
 * lets [NavHeap] address nodes directly.
 */
class NavNodeArena(initialCapacity: Int = DEFAULT_CAPACITY) {

    /** Packed [net.minecraft.core.BlockPos] of each node. */
    var positions = LongArray(initialCapacity)
        private set

    /** Best known cost from the start, in ticks. */
    var gScore = DoubleArray(initialCapacity)
        private set

    /** Raw (unweighted) heuristic, kept for best-effort ranking. */
    var hScore = DoubleArray(initialCapacity)
        private set

    /** Node id this node was reached from, or [NO_PARENT] for the start. */
    var parent = IntArray(initialCapacity)
        private set

    /** Move id that produced this node from its parent. Interpreted by the planner. */
    var incomingMove = ByteArray(initialCapacity)
        private set

    /** [UNSEEN], [OPEN] or [CLOSED]. */
    var state = ByteArray(initialCapacity)
        private set

    /** Number of nodes discovered so far. */
    var size: Int = 0
        private set

    private val index = Long2IntOpenHashMap(initialCapacity).apply { defaultReturnValue(NOT_FOUND) }

    /** Node id for a packed position, or [NOT_FOUND] if it has never been touched. */
    fun find(packedPos: Long): Int = index.get(packedPos)

    /**
     * Node id for a packed position, allocating one if this is the first time we see it.
     *
     * A freshly allocated node starts [UNSEEN] with an infinite g-score, so the caller can treat
     * "new" and "known but unreached" identically.
     */
    fun obtain(packedPos: Long): Int {
        val existing = index.get(packedPos)
        if (existing != NOT_FOUND) return existing

        val id = size
        ensureCapacity(id + 1)
        positions[id] = packedPos
        gScore[id] = Double.POSITIVE_INFINITY
        hScore[id] = Double.POSITIVE_INFINITY
        parent[id] = NO_PARENT
        incomingMove[id] = 0
        state[id] = UNSEEN
        size = id + 1
        index.put(packedPos, id)
        return id
    }

    /** Empties the arena, keeping its arrays for the next search. */
    fun clear() {
        index.clear()
        size = 0
    }

    /**
     * Walks parent links from [id] back to the start, returning the positions in travel order.
     *
     * Returns an empty array if the chain is broken, which can only happen if a caller passes an id
     * from a different search.
     */
    fun reconstructPositions(id: Int): LongArray {
        val length = chainLength(id)
        if (length == 0) return LongArray(0)

        val path = LongArray(length)
        var current = id
        for (slot in length - 1 downTo 0) {
            path[slot] = positions[current]
            current = parent[current]
        }
        return path
    }

    /**
     * Move ids along the chain ending at [id]. Element `i` is the move that led from position `i`
     * to position `i + 1`, so this is always one shorter than [reconstructPositions].
     */
    fun reconstructMoves(id: Int): ByteArray {
        val length = chainLength(id)
        if (length <= 1) return ByteArray(0)

        val moves = ByteArray(length - 1)
        var current = id
        for (slot in length - 2 downTo 0) {
            moves[slot] = incomingMove[current]
            current = parent[current]
        }
        return moves
    }

    private fun chainLength(id: Int): Int {
        if (id < 0 || id >= size) return 0
        var length = 0
        var current = id
        while (current != NO_PARENT) {
            length++
            // A cycle would mean a corrupted parent chain; bail rather than spin forever.
            if (length > size) return 0
            current = parent[current]
        }
        return length
    }

    private fun ensureCapacity(required: Int) {
        if (required <= positions.size) return
        // Doubling from zero is still zero, which is a hang rather than a crash — and on the search
        // thread, where nothing would ever report it. NavHeap guards the same case.
        var grown = if (positions.isEmpty()) DEFAULT_CAPACITY else positions.size
        while (grown < required) grown = grown shl 1

        positions = positions.copyOf(grown)
        gScore = gScore.copyOf(grown)
        hScore = hScore.copyOf(grown)
        parent = parent.copyOf(grown)
        incomingMove = incomingMove.copyOf(grown)
        state = state.copyOf(grown)
    }

    companion object {
        /** Returned by [find] when a position has never been discovered. */
        const val NOT_FOUND = -1

        /** Parent of the start node. */
        const val NO_PARENT = -1

        /** Discovered but never queued. */
        const val UNSEEN: Byte = 0

        /** Queued in the heap. */
        const val OPEN: Byte = 1

        /** Expanded; its g-score is final for this search. */
        const val CLOSED: Byte = 2

        private const val DEFAULT_CAPACITY = 1 shl 12
    }
}
