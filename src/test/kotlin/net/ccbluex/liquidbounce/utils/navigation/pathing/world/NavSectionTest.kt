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

package net.ccbluex.liquidbounce.utils.navigation.pathing.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NavSectionTest {

    private val air = NavBlockClass.PACKED_AIR
    private val stone = NavBlockClass.pack(NavBlockClass.SOLID_FULL, 4)
    private val water = NavBlockClass.pack(NavBlockClass.WATER, 0)

    @Test
    fun `index is unique for every block in a section`() {
        val seen = BooleanArray(NavSection.BLOCKS)
        for (x in 0 until NavSection.EDGE) {
            for (y in 0 until NavSection.EDGE) {
                for (z in 0 until NavSection.EDGE) {
                    val index = NavSection.index(x, y, z)
                    assertFalse(seen[index], "index collision at ($x, $y, $z)")
                    seen[index] = true
                }
            }
        }
        assertTrue(seen.all { it }, "index does not cover the whole section")
    }

    @Test
    fun `index masks world coordinates down to section-local ones`() {
        assertEquals(NavSection.index(1, 2, 3), NavSection.index(16 * 5 + 1, 16 * -3 + 2, 16 * 7 + 3))
        assertEquals(NavSection.index(15, 15, 15), NavSection.index(-1, -1, -1))
    }

    @Test
    fun `a column occupies adjacent indices so vertical reads stay on one cache line`() {
        // The below/feet/head triple is the single hottest read pattern in ground search.
        val below = NavSection.index(7, 4, 9)
        val feet = NavSection.index(7, 5, 9)
        val head = NavSection.index(7, 6, 9)
        assertEquals(below + 1, feet)
        assertEquals(feet + 1, head)
    }

    @Test
    fun `uniform section reports one value everywhere and costs no memory`() {
        val section = NavSection.uniform(air)

        assertTrue(section.isUniform)
        assertEquals(0, section.byteSize)
        assertEquals(air, section.get(0, 0, 0))
        assertEquals(air, section.get(15, 15, 15))
        assertEquals(air, section.get(8, 3, 12))
    }

    @Test
    fun `writing the uniform value to a uniform section does not allocate`() {
        val section = NavSection.uniform(air)

        section.set(4, 4, 4, air)

        assertTrue(section.isUniform, "a no-op write must not expand the section")
        assertEquals(0, section.byteSize)
    }

    @Test
    fun `writing a different value expands the section and preserves every other block`() {
        val section = NavSection.uniform(air)

        section.set(4, 5, 6, stone)

        assertFalse(section.isUniform)
        assertEquals(NavSection.BLOCKS, section.byteSize)
        assertEquals(stone, section.get(4, 5, 6))
        assertEquals(air, section.get(4, 5, 7), "neighbouring blocks must keep the old uniform value")
        assertEquals(air, section.get(0, 0, 0))
        assertEquals(air, section.get(15, 15, 15))
    }

    @Test
    fun `of collapses a homogeneous array back to uniform storage`() {
        val section = NavSection.of(ByteArray(NavSection.BLOCKS) { stone })

        assertTrue(section.isUniform)
        assertEquals(0, section.byteSize)
        assertEquals(stone, section.get(9, 9, 9))
    }

    @Test
    fun `of keeps dense storage when a single block differs`() {
        val dense = ByteArray(NavSection.BLOCKS) { air }
        dense[NavSection.index(2, 3, 4)] = water

        val section = NavSection.of(dense)

        assertFalse(section.isUniform)
        assertEquals(water, section.get(2, 3, 4))
        assertEquals(air, section.get(2, 3, 5))
    }

    @Test
    fun `dense section round-trips every position`() {
        val random = Random(seed = 20260731)
        val expected = ByteArray(NavSection.BLOCKS) { random.nextInt(0, 256).toByte() }
        // Guarantee it stays dense regardless of what the random data looked like.
        expected[0] = air
        expected[1] = stone

        val section = NavSection.of(expected.copyOf())

        for (x in 0 until NavSection.EDGE) {
            for (y in 0 until NavSection.EDGE) {
                for (z in 0 until NavSection.EDGE) {
                    assertEquals(
                        expected[NavSection.index(x, y, z)],
                        section.get(x, y, z),
                        "mismatch at ($x, $y, $z)",
                    )
                }
            }
        }
    }

    @Test
    fun `concurrent expansion of a uniform section loses no writes`() {
        // Several ChunkScanner workers can touch the same section at once; expansion is the one
        // step where a lost update would silently corrupt the cache.
        repeat(50) {
            val section = NavSection.uniform(air)
            val threadCount = 8
            val start = CountDownLatch(1)
            val done = CountDownLatch(threadCount)

            val threads = (0 until threadCount).map { thread ->
                Thread {
                    start.await()
                    for (y in 0 until NavSection.EDGE) {
                        section.set(thread, y, thread, stone)
                    }
                    done.countDown()
                }
            }
            threads.forEach(Thread::start)
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "workers did not finish")
            threads.forEach(Thread::join)

            assertFalse(section.isUniform)
            for (thread in 0 until threadCount) {
                for (y in 0 until NavSection.EDGE) {
                    assertEquals(stone, section.get(thread, y, thread), "lost write at thread $thread, y $y")
                }
            }
            assertNotEquals(stone, section.get(15, 0, 15), "untouched blocks must stay air")
        }
    }
}
