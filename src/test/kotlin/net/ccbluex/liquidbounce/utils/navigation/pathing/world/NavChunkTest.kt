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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [NavChunk] against hand-built sections, with no Minecraft world involved.
 *
 * The height map is the part worth guarding: [NavChunk.updateSurfaceAfterSet] deliberately skips
 * the top-down rescan in three of its four cases, and a wrong shortcut there would silently feed
 * bad heuristics to every long-range search.
 */
class NavChunkTest {

    private val air = NavBlockClass.PACKED_AIR
    private val stone = NavBlockClass.pack(NavBlockClass.SOLID_FULL, 4)
    private val water = NavBlockClass.pack(NavBlockClass.WATER, 0)
    private val slab = NavBlockClass.pack(NavBlockClass.SOLID_TOP_HALF, 4)

    /** An overworld-shaped chunk: y from -64 to 320, all air. */
    private fun emptyChunk(): NavChunk {
        val chunk = NavChunk(originX = 0, originZ = 0, minY = -64, sectionCount = 24)
        for (index in 0 until 24) {
            chunk.putSection(index, NavSection.uniform(air))
        }
        return chunk
    }

    private fun chunkWithFloorAt(y: Int): NavChunk {
        val chunk = emptyChunk()
        for (x in 0 until NavSection.EDGE) {
            for (z in 0 until NavSection.EDGE) {
                chunk.set(x, y, z, stone)
            }
        }
        chunk.recomputeAllSurfaces()
        return chunk
    }

    // -----------------------------------------------------------------------
    //  Geometry and bounds
    // -----------------------------------------------------------------------

    @Test
    fun `build range covers the whole overworld column`() {
        val chunk = emptyChunk()

        assertEquals(-64, chunk.minY)
        assertEquals(320, chunk.maxY)
    }

    @Test
    fun `reads outside the build range report unknown rather than air`() {
        val chunk = emptyChunk()

        assertEquals(NavBlockClass.PACKED_UNKNOWN, chunk.get(0, -65, 0))
        assertEquals(NavBlockClass.PACKED_UNKNOWN, chunk.get(0, 320, 0))
        assertEquals(NavBlockClass.PACKED_UNKNOWN, chunk.get(0, 5000, 0))
        assertEquals(air, chunk.get(0, -64, 0), "the lowest buildable layer is inside the range")
        assertEquals(air, chunk.get(0, 319, 0), "the highest buildable layer is inside the range")
    }

    @Test
    fun `writes outside the build range are ignored instead of corrupting a section`() {
        val chunk = emptyChunk()

        chunk.set(0, -65, 0, stone)
        chunk.set(0, 320, 0, stone)

        assertEquals(NavBlockClass.PACKED_UNKNOWN, chunk.get(0, -65, 0))
        assertEquals(NavBlockClass.PACKED_UNKNOWN, chunk.get(0, 320, 0))
    }

    @Test
    fun `blocks round-trip across section boundaries`() {
        val chunk = emptyChunk()
        val boundaries = listOf(-64, -49, -48, -47, -1, 0, 1, 15, 16, 127, 128, 319)

        for (y in boundaries) {
            chunk.set(3, y, 11, stone)
        }
        for (y in boundaries) {
            assertEquals(stone, chunk.get(3, y, 11), "lost the block at y=$y")
        }
        assertEquals(air, chunk.get(3, 17, 11))
    }

    // -----------------------------------------------------------------------
    //  Surface height map
    // -----------------------------------------------------------------------

    @Test
    fun `empty columns report no surface`() {
        val chunk = emptyChunk()
        chunk.recomputeAllSurfaces()

        assertEquals(Int.MIN_VALUE, chunk.surfaceY(0, 0))
        assertEquals(Int.MIN_VALUE, chunk.surfaceY(15, 15))
    }

    @Test
    fun `full rescan finds the highest standable block`() {
        val chunk = chunkWithFloorAt(64)

        assertEquals(64, chunk.surfaceY(0, 0))
        assertEquals(64, chunk.surfaceY(15, 15))
    }

    @Test
    fun `only standable classes count as a surface`() {
        val chunk = emptyChunk()
        chunk.set(2, 70, 2, water)
        chunk.set(2, 60, 2, stone)
        chunk.recomputeAllSurfaces()

        assertEquals(60, chunk.surfaceY(2, 2), "water is passable, so it cannot be the surface")
    }

    @Test
    fun `placing a block above the surface raises it`() {
        val chunk = chunkWithFloorAt(64)

        chunk.set(4, 70, 4, stone)
        chunk.updateSurfaceAfterSet(4, 70, 4, NavBlockClass.SOLID_FULL)

        assertEquals(70, chunk.surfaceY(4, 4))
        assertEquals(64, chunk.surfaceY(5, 4), "neighbouring columns are untouched")
    }

    @Test
    fun `placing a block below the surface leaves it alone`() {
        val chunk = chunkWithFloorAt(64)

        chunk.set(4, 30, 4, stone)
        chunk.updateSurfaceAfterSet(4, 30, 4, NavBlockClass.SOLID_FULL)

        assertEquals(64, chunk.surfaceY(4, 4))
    }

    @Test
    fun `clearing a block below the surface leaves it alone`() {
        val chunk = chunkWithFloorAt(64)
        chunk.set(4, 30, 4, stone)
        chunk.updateSurfaceAfterSet(4, 30, 4, NavBlockClass.SOLID_FULL)

        chunk.set(4, 30, 4, air)
        chunk.updateSurfaceAfterSet(4, 30, 4, NavBlockClass.AIR)

        assertEquals(64, chunk.surfaceY(4, 4))
    }

    @Test
    fun `mining the surface block drops the height to the next one down`() {
        val chunk = emptyChunk()
        chunk.set(6, 64, 6, stone)
        chunk.set(6, 40, 6, stone)
        chunk.recomputeAllSurfaces()
        assertEquals(64, chunk.surfaceY(6, 6))

        chunk.set(6, 64, 6, air)
        chunk.updateSurfaceAfterSet(6, 64, 6, NavBlockClass.AIR)

        assertEquals(40, chunk.surfaceY(6, 6), "the rescan must find the block underneath")
    }

    @Test
    fun `mining the only block in a column leaves it with no surface`() {
        val chunk = emptyChunk()
        chunk.set(6, 64, 6, stone)
        chunk.recomputeAllSurfaces()

        chunk.set(6, 64, 6, air)
        chunk.updateSurfaceAfterSet(6, 64, 6, NavBlockClass.AIR)

        assertEquals(Int.MIN_VALUE, chunk.surfaceY(6, 6))
    }

    @Test
    fun `replacing the surface block with another standable one keeps the height`() {
        val chunk = chunkWithFloorAt(64)

        chunk.set(4, 64, 4, slab)
        chunk.updateSurfaceAfterSet(4, 64, 4, NavBlockClass.SOLID_TOP_HALF)

        assertEquals(64, chunk.surfaceY(4, 4))
    }

    @Test
    fun `incremental updates agree with a full rescan after a random edit sequence`() {
        // The shortcut path and the authoritative scan must never diverge, whatever order the
        // server sends block updates in.
        val random = kotlin.random.Random(seed = 20260731)
        val chunk = chunkWithFloorAt(64)
        val classes = listOf(NavBlockClass.AIR to air, NavBlockClass.SOLID_FULL to stone)

        repeat(2000) {
            val x = random.nextInt(NavSection.EDGE)
            val z = random.nextInt(NavSection.EDGE)
            val y = random.nextInt(56, 72)
            val (navClass, packed) = classes[random.nextInt(classes.size)]

            chunk.set(x, y, z, packed)
            chunk.updateSurfaceAfterSet(x, y, z, navClass)
        }

        val incremental = Array(NavSection.EDGE) { x -> IntArray(NavSection.EDGE) { z -> chunk.surfaceY(x, z) } }
        chunk.recomputeAllSurfaces()

        for (x in 0 until NavSection.EDGE) {
            for (z in 0 until NavSection.EDGE) {
                assertEquals(
                    chunk.surfaceY(x, z),
                    incremental[x][z],
                    "incremental height diverged from a full rescan at ($x, $z)",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Memory
    // -----------------------------------------------------------------------

    @Test
    fun `an all-air chunk holds no dense storage at all`() {
        assertEquals(0, emptyChunk().denseBytes())
    }

    @Test
    fun `only the touched section becomes dense`() {
        val chunk = chunkWithFloorAt(64)

        // One section of 24 was written to; the rest must still be single bytes.
        assertEquals(NavSection.BLOCKS, chunk.denseBytes())
        assertTrue(chunk.denseBytes() < 24 * NavSection.BLOCKS)
    }
}
