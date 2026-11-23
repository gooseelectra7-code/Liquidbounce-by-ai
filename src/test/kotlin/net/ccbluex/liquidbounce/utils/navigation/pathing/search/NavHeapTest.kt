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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The heap is the one component where a subtle bug produces plausible-looking but wrong paths
 * instead of an obvious crash, so it is checked against a reference implementation under random
 * operation sequences rather than only on hand-picked cases.
 */
class NavHeapTest {

    @Test
    fun `a fresh heap is empty`() {
        val heap = NavHeap()

        assertTrue(heap.isEmpty)
        assertEquals(0, heap.size)
        assertEquals(NavHeap.NOT_QUEUED, heap.pop())
    }

    @Test
    fun `pops come out in key order`() {
        val heap = NavHeap()
        val keys = doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0)
        keys.forEachIndexed { id, key -> heap.pushOrDecrease(id, key) }

        val popped = generateSequence { heap.pop().takeIf { it != NavHeap.NOT_QUEUED } }.toList()

        assertEquals(listOf(1, 3, 4, 2, 0), popped)
        assertTrue(heap.isEmpty)
    }

    @Test
    fun `pushing a worse key for a queued node changes nothing`() {
        val heap = NavHeap()
        heap.pushOrDecrease(7, 1.0)

        assertFalse(heap.pushOrDecrease(7, 2.0), "a worse key must be rejected")
        assertEquals(1, heap.size, "the node must not be queued twice")
        assertEquals(1.0, heap.peekKey())
    }

    @Test
    fun `pushing an equal key for a queued node changes nothing`() {
        val heap = NavHeap()
        heap.pushOrDecrease(7, 1.0)

        assertFalse(heap.pushOrDecrease(7, 1.0))
        assertEquals(1, heap.size)
    }

    @Test
    fun `decreasing a key repositions the existing entry instead of duplicating it`() {
        // This is the whole reason the heap exists: the old engine pushed a second entry here and
        // then had to filter stale copies on every pop.
        val heap = NavHeap()
        heap.pushOrDecrease(0, 1.0)
        heap.pushOrDecrease(1, 2.0)
        heap.pushOrDecrease(2, 3.0)

        assertTrue(heap.pushOrDecrease(2, 0.5))

        assertEquals(3, heap.size, "decrease-key must not grow the heap")
        assertEquals(2, heap.pop())
        assertEquals(0, heap.pop())
        assertEquals(1, heap.pop())
        assertTrue(heap.isEmpty)
    }

    @Test
    fun `contains tracks membership across pushes and pops`() {
        val heap = NavHeap()

        assertFalse(heap.contains(3))
        heap.pushOrDecrease(3, 1.0)
        assertTrue(heap.contains(3))
        heap.pop()
        assertFalse(heap.contains(3))
    }

    @Test
    fun `a popped node can be queued again`() {
        val heap = NavHeap()
        heap.pushOrDecrease(4, 5.0)
        assertEquals(4, heap.pop())

        assertTrue(heap.pushOrDecrease(4, 9.0), "re-queueing after a pop must work")
        assertEquals(1, heap.size)
        assertEquals(9.0, heap.peekKey())
    }

    @Test
    fun `clear empties the heap and frees every node for re-queueing`() {
        val heap = NavHeap()
        repeat(100) { heap.pushOrDecrease(it, it.toDouble()) }

        heap.clear()

        assertTrue(heap.isEmpty)
        assertEquals(0, heap.size)
        repeat(100) { assertFalse(heap.contains(it), "node $it still looks queued after clear") }
        // Reusing a cleared heap is the normal case: one workspace serves every replan.
        assertTrue(heap.pushOrDecrease(50, 1.0))
        assertEquals(50, heap.pop())
    }

    @Test
    fun `grows past its initial capacity in both dimensions`() {
        val heap = NavHeap(initialCapacity = 4)
        val count = 5000

        repeat(count) { heap.pushOrDecrease(it, (count - it).toDouble()) }

        assertEquals(count, heap.size)
        var previous = Double.NEGATIVE_INFINITY
        repeat(count) {
            val key = heap.peekKey()
            assertTrue(key >= previous, "heap order broken after growth: $key came after $previous")
            previous = key
            heap.pop()
        }
    }

    @Test
    fun `peak size records the high-water mark`() {
        val heap = NavHeap()
        repeat(10) { heap.pushOrDecrease(it, it.toDouble()) }
        repeat(7) { heap.pop() }

        assertEquals(3, heap.size)
        assertEquals(10, heap.peakSize)
    }

    @Test
    fun `random operation sequences match a reference priority queue`() {
        val random = Random(seed = 20260731)

        repeat(200) { round ->
            val heap = NavHeap(initialCapacity = 4)
            val reference = HashMap<Int, Double>()
            val nodeCount = 200

            repeat(2000) {
                if (random.nextInt(100) < 70) {
                    val id = random.nextInt(nodeCount)
                    val key = random.nextDouble() * 1000.0
                    val changed = heap.pushOrDecrease(id, key)
                    val known = reference[id]
                    if (known == null || key < known) {
                        reference[id] = key
                        assertTrue(changed, "round $round: push of $id at $key should have been accepted")
                    } else {
                        assertFalse(changed, "round $round: push of $id at $key should have been rejected")
                    }
                } else {
                    val popped = heap.pop()
                    val expected = reference.minByOrNull { it.value }
                    if (expected == null) {
                        assertEquals(NavHeap.NOT_QUEUED, popped, "round $round: pop from an empty heap")
                    } else {
                        assertEquals(
                            expected.value,
                            reference.getValue(popped),
                            "round $round: popped $popped, but ${expected.key} was cheaper",
                        )
                        reference.remove(popped)
                    }
                }
                assertEquals(reference.size, heap.size, "round $round: size diverged")
            }

            // Drain and confirm the remaining order is still monotone.
            var previous = Double.NEGATIVE_INFINITY
            while (!heap.isEmpty) {
                val key = heap.peekKey()
                assertTrue(key >= previous, "round $round: drain order broken ($key after $previous)")
                previous = key
                val id = heap.pop()
                assertEquals(reference.remove(id), key, "round $round: key mismatch for $id")
            }
            assertTrue(reference.isEmpty(), "round $round: heap dropped ${reference.size} nodes")
        }
    }
}
