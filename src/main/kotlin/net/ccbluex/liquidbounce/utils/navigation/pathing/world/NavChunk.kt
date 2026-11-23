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

import net.ccbluex.liquidbounce.utils.world.forEachBlock
import net.minecraft.world.level.chunk.LevelChunk
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Navigation view of one 16x16 world column, built from a [LevelChunk] and kept up to date by
 * [NavWorld].
 *
 * Holds the packed block bytes (see [NavBlockClass]) split into [NavSection]s, plus a surface
 * height map used by long-range heuristics and by the elytra planner's altitude profile.
 */
class NavChunk(
    /** World-space X of the chunk's first block. */
    val originX: Int,
    /** World-space Z of the chunk's first block. */
    val originZ: Int,
    /** Lowest buildable Y in this dimension, e.g. -64 in the overworld. */
    val minY: Int,
    /** Number of 16-block sections stacked in this column. */
    val sectionCount: Int,
) {

    /** Exclusive upper bound of buildable Y. */
    val maxY: Int get() = minY + sectionCount * NavSection.EDGE

    private val sections = AtomicReferenceArray<NavSection>(sectionCount)

    /**
     * Highest Y whose block can be stood on, per column, or [NO_SURFACE] where the column is empty.
     * Indexed by [columnIndex]. Plain `short` stores — readers may observe a stale height while a
     * column is being rebuilt, which only ever costs a slightly wrong heuristic.
     */
    private val surface = ShortArray(COLUMNS) { NO_SURFACE }

    // -----------------------------------------------------------------------
    //  Block access — coordinates are world-space
    // -----------------------------------------------------------------------

    /** Packed navigation byte at a world position, or unknown outside the build range. */
    fun get(x: Int, y: Int, z: Int): Byte {
        val sectionIndex = sectionIndexOf(y)
        if (sectionIndex < 0 || sectionIndex >= sectionCount) return NavBlockClass.PACKED_UNKNOWN
        val section = sections.get(sectionIndex) ?: return NavBlockClass.PACKED_UNKNOWN
        return section.get(x, y, z)
    }

    /** Writes a packed byte, ignoring positions outside the build range. */
    fun set(x: Int, y: Int, z: Int, value: Byte) {
        val sectionIndex = sectionIndexOf(y)
        if (sectionIndex < 0 || sectionIndex >= sectionCount) return
        val section = sections.get(sectionIndex) ?: return
        section.set(x, y, z, value)
    }

    /**
     * Replaces a whole section. Used while (re)building the chunk.
     *
     * The section becomes this chunk's alone. A [NavSection] is mutable — the first block written
     * into a uniform one promotes it to dense storage in place — so handing the same instance to
     * two slots makes every later write to one of them appear in all of them.
     */
    fun putSection(sectionIndex: Int, section: NavSection) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) return
        sections.set(sectionIndex, section)
    }

    private fun sectionIndexOf(y: Int): Int = (y - minY) shr 4

    private fun sectionBottomY(sectionIndex: Int): Int = minY + (sectionIndex shl 4)

    // -----------------------------------------------------------------------
    //  Surface height map
    // -----------------------------------------------------------------------

    /** Highest standable Y in this column, or [Int.MIN_VALUE] when there is nothing to stand on. */
    fun surfaceY(x: Int, z: Int): Int {
        val stored = surface[columnIndex(x, z)]
        return if (stored == NO_SURFACE) Int.MIN_VALUE else stored.toInt()
    }

    /** Recomputes one column's surface height by scanning it top-down. */
    fun recomputeSurface(x: Int, z: Int) {
        synchronized(surface) {
            surface[columnIndex(x, z)] = scanSurface(x, z)
        }
    }

    /**
     * Repairs the surface height after a single block at [y] became [navClass].
     *
     * Only one of the four cases actually needs a top-down rescan — removing the very block the
     * surface sat on. Everything else is decided in a couple of comparisons, which matters because
     * a single section-blocks-update packet can carry hundreds of changes.
     *
     * Locked, unlike the block bytes themselves. A stale *read* of a height is harmless and the
     * class doc says so, but this is a read-modify-write and two scanner workers do land in the
     * same column: "a block was placed at 70" and "the block at 64 was cleared" both read the old
     * height, and whichever stores last erases the other's answer for good. That is not staleness —
     * the column never converges again until the server resends the chunk, and a height that is
     * wrong by the height of a hill is what a glide's altitude profile is drawn from.
     */
    fun updateSurfaceAfterSet(x: Int, y: Int, z: Int, navClass: Int) {
        // A block the chunk never stored must not be allowed to set its surface: [set] silently
        // drops writes outside the build range, and `toShort()` below would wrap a wild Y into a
        // perfectly plausible one that no rescan can ever produce.
        if (y < minY || y >= maxY) return

        val column = columnIndex(x, z)
        synchronized(surface) {
            val stored = surface[column]
            val current = if (stored == NO_SURFACE) Int.MIN_VALUE else stored.toInt()

            if (NavBlockClass.isStandable(navClass)) {
                // Raised the surface, or filled in below it — either way no scan is needed.
                if (y > current) surface[column] = y.toShort()
                return
            }
            // Cleared the block the surface rested on; the next one down has to be found.
            if (y == current) surface[column] = scanSurface(x, z)
        }
    }

    /** Recomputes every column. Called once after a full rebuild. */
    fun recomputeAllSurfaces() {
        synchronized(surface) {
            for (localX in 0 until NavSection.EDGE) {
                for (localZ in 0 until NavSection.EDGE) {
                    surface[(localX shl 4) or localZ] = scanSurface(localX, localZ)
                }
            }
        }
    }

    private fun scanSurface(x: Int, z: Int): Short {
        for (sectionIndex in sectionCount - 1 downTo 0) {
            val section = sections.get(sectionIndex) ?: continue
            val bottomY = sectionBottomY(sectionIndex)
            for (localY in NavSection.EDGE - 1 downTo 0) {
                val navClass = NavBlockClass.navClassOf(section.get(x, localY, z))
                if (NavBlockClass.isStandable(navClass)) return (bottomY + localY).toShort()
            }
        }
        return NO_SURFACE
    }

    // -----------------------------------------------------------------------
    //  Diagnostics
    // -----------------------------------------------------------------------

    /** Heap held by dense sections, in bytes. Uniform sections cost nothing. */
    fun denseBytes(): Int {
        var total = 0
        for (i in 0 until sectionCount) {
            total += sections.get(i)?.byteSize ?: 0
        }
        return total
    }

    companion object {
        /** Columns in a chunk. */
        const val COLUMNS = NavSection.EDGE * NavSection.EDGE

        /** Sentinel height meaning "nothing standable in this column". */
        const val NO_SURFACE: Short = Short.MIN_VALUE

        private fun columnIndex(x: Int, z: Int): Int = ((x and 15) shl 4) or (z and 15)

        /**
         * Builds a navigation chunk from a live [LevelChunk].
         *
         * Runs on [net.ccbluex.liquidbounce.utils.block.ChunkScanner] worker threads. Sections the
         * server reported as empty, and everything above the highest filled section, collapse to a
         * single air byte without ever being touched — which is why scanning a chunk costs a
         * fraction of its nominal 98304 blocks.
         */
        fun fromLevelChunk(chunk: LevelChunk): NavChunk {
            val sectionCount = chunk.sectionsCount
            val navChunk = NavChunk(
                originX = chunk.pos.minBlockX,
                originZ = chunk.pos.minBlockZ,
                minY = chunk.minY,
                sectionCount = sectionCount,
            )

            val highestFilled = chunk.highestFilledSectionIndex

            for (sectionIndex in 0 until sectionCount) {
                // A fresh instance per slot, never one shared between them. Sharing costs nothing
                // until the first block is placed in an air section: that write promotes the shared
                // object to dense storage, and from then on the block exists at the same local
                // position in every section that held the same instance — a phantom floor every 16
                // blocks up the column, which the surface map then reports as ground.
                if (sectionIndex > highestFilled) {
                    navChunk.putSection(sectionIndex, NavSection.uniform(NavBlockClass.PACKED_AIR))
                    continue
                }
                val section = chunk.getSection(sectionIndex)
                if (section.hasOnlyAir()) {
                    navChunk.putSection(sectionIndex, NavSection.uniform(NavBlockClass.PACKED_AIR))
                    continue
                }
                val dense = ByteArray(NavSection.BLOCKS)
                section.forEachBlock { localX, localY, localZ, state ->
                    dense[NavSection.index(localX, localY, localZ)] = NavBlockClass.classifyCached(state)
                }
                navChunk.putSection(sectionIndex, NavSection.of(dense))
            }

            navChunk.recomputeAllSurfaces()
            return navChunk
        }
    }
}
