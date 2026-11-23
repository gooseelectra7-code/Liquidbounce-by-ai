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
 * Binary min-heap of node ids ordered by f-score, with decrease-key.
 *
 * The previous engine used `PriorityQueue<QueueLongEntry>` with a comparator: every relaxation
 * allocated a record, and finding a cheaper route to an already-queued node pushed a *second* entry
 * rather than repositioning the first, so the queue grew far past the number of live nodes and every
 * pop had to check whether it was looking at a stale copy.
 *
 * Here [pushOrDecrease] moves the existing entry instead. [positions] maps a node id to its slot in
 * the heap, which is what makes that possible in logarithmic time. Nothing is allocated per
 * operation, and the heap never holds more entries than there are open nodes.
 */
class NavHeap(initialCapacity: Int = DEFAULT_CAPACITY) {

    /** Node ids, arranged as a binary heap. */
    private var ids = IntArray(initialCapacity)

    /** f-scores parallel to [ids]. Kept here rather than read back from the arena to stay cache-local. */
    private var keys = DoubleArray(initialCapacity)

    /** Slot of each node id within [ids], or [NOT_QUEUED]. Indexed by node id. */
    private var positions = IntArray(initialCapacity) { NOT_QUEUED }

    /** Number of entries currently in the heap. */
    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    /** Largest [size] this heap has reached, for diagnostics. */
    var peakSize: Int = 0
        private set

    /** Is this node currently queued? */
    fun contains(id: Int): Boolean = id < positions.size && positions[id] != NOT_QUEUED

    /**
     * Queues [id] at [key], or moves it up if it is already queued at a worse key.
     *
     * @return true if the heap changed, false when the node was already queued at least this cheaply
     */
    fun pushOrDecrease(id: Int, key: Double): Boolean {
        ensureNodeCapacity(id + 1)

        val existing = positions[id]
        if (existing != NOT_QUEUED) {
            if (key >= keys[existing]) return false
            keys[existing] = key
            siftUp(existing)
            return true
        }

        ensureHeapCapacity(size + 1)
        val slot = size++
        ids[slot] = id
        keys[slot] = key
        positions[id] = slot
        siftUp(slot)
        if (size > peakSize) peakSize = size
        return true
    }

    /** Removes and returns the cheapest node id, or [NOT_QUEUED] when the heap is empty. */
    fun pop(): Int {
        if (size == 0) return NOT_QUEUED

        val top = ids[0]
        positions[top] = NOT_QUEUED
        size--

        if (size > 0) {
            val moved = ids[size]
            ids[0] = moved
            keys[0] = keys[size]
            positions[moved] = 0
            siftDown(0)
        }
        return top
    }

    /** f-score of the cheapest queued node. Only valid while the heap is non-empty. */
    fun peekKey(): Double = keys[0]

    /** Empties the heap, keeping the allocated arrays for the next search. */
    fun clear() {
        // Only the slots actually in use need resetting, which keeps repeated searches cheap.
        for (slot in 0 until size) {
            positions[ids[slot]] = NOT_QUEUED
        }
        size = 0
        peakSize = 0
    }

    private fun siftUp(from: Int) {
        var slot = from
        val id = ids[slot]
        val key = keys[slot]

        while (slot > 0) {
            val parent = (slot - 1) shr 1
            if (keys[parent] <= key) break
            ids[slot] = ids[parent]
            keys[slot] = keys[parent]
            positions[ids[slot]] = slot
            slot = parent
        }

        ids[slot] = id
        keys[slot] = key
        positions[id] = slot
    }

    private fun siftDown(from: Int) {
        var slot = from
        val id = ids[slot]
        val key = keys[slot]
        val half = size shr 1

        while (slot < half) {
            var child = (slot shl 1) + 1
            val right = child + 1
            if (right < size && keys[right] < keys[child]) child = right
            if (keys[child] >= key) break
            ids[slot] = ids[child]
            keys[slot] = keys[child]
            positions[ids[slot]] = slot
            slot = child
        }

        ids[slot] = id
        keys[slot] = key
        positions[id] = slot
    }

    private fun ensureHeapCapacity(required: Int) {
        if (required <= ids.size) return
        val grown = grownSize(ids.size, required)
        ids = ids.copyOf(grown)
        keys = keys.copyOf(grown)
    }

    private fun ensureNodeCapacity(required: Int) {
        if (required <= positions.size) return
        val grown = grownSize(positions.size, required)
        val old = positions.size
        positions = positions.copyOf(grown)
        positions.fill(NOT_QUEUED, old, grown)
    }

    private fun grownSize(current: Int, required: Int): Int {
        var grown = if (current == 0) DEFAULT_CAPACITY else current
        while (grown < required) grown = grown shl 1
        return grown
    }

    companion object {
        /** Returned by [pop] on an empty heap, and stored for nodes that are not queued. */
        const val NOT_QUEUED = -1

        private const val DEFAULT_CAPACITY = 1 shl 12
    }
}
