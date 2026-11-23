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

/**
 * One 16x16x16 cube of packed navigation bytes.
 *
 * **Layout.** Index is `x shl 8 | z shl 4 | y`, i.e. Y is the fastest-varying axis. Ground
 * pathfinding reads the below/feet/head triple of a column far more often than it reads sideways,
 * so keeping a column in three adjacent bytes puts the whole check on one cache line.
 *
 * **Compression.** A section whose blocks are all identical — every all-air section above the
 * terrain, every solid section deep underground — stores a single byte instead of 4 KiB. In a
 * normal world that is most sections, which is why this cache stays smaller than Baritone's flat
 * two-bits-per-block despite storing four times as much information per block.
 *
 * **Threading.** Written only by [net.ccbluex.liquidbounce.utils.block.ChunkScanner] workers, read
 * concurrently by planner threads. Element writes into [data] are plain byte stores: the JVM
 * forbids word tearing on `byte[]`, so a racing reader observes either the old or the new value,
 * never a mix. Expansion from uniform to dense is the one operation that must not interleave, and
 * it is the only thing guarded by a lock. A reader that catches a stale value plans against a block
 * that changed microseconds ago — which the executor re-validates on the main thread anyway.
 */
class NavSection private constructor(uniform: Byte, dense: ByteArray?) {

    /** Value of every block in this section while [data] is null. */
    @Volatile
    private var uniformValue: Byte = uniform

    /** Dense storage, allocated lazily the first time the section stops being homogeneous. */
    @Volatile
    private var data: ByteArray? = dense

    /** True while the section is still stored as a single byte. */
    val isUniform: Boolean get() = data == null

    /** Approximate heap footprint, for diagnostics. */
    val byteSize: Int get() = if (data == null) 0 else BLOCKS

    fun get(localX: Int, localY: Int, localZ: Int): Byte =
        data?.get(index(localX, localY, localZ)) ?: uniformValue

    fun get(index: Int): Byte = data?.get(index) ?: uniformValue

    fun set(localX: Int, localY: Int, localZ: Int, value: Byte) {
        val dense = data ?: expandFor(value) ?: return
        dense[index(localX, localY, localZ)] = value
    }

    /**
     * Ensures dense storage exists so [value] can be written, returning the array to write into.
     *
     * Returns null when the section is uniform and [value] already equals that uniform value —
     * the write is a no-op and there is no reason to spend 4 KiB on it. This is the common case
     * for "block updated to air in an all-air section".
     */
    private fun expandFor(value: Byte): ByteArray? {
        synchronized(this) {
            data?.let { return it }
            val current = uniformValue
            if (current == value) return null
            val dense = ByteArray(BLOCKS) { current }
            data = dense
            return dense
        }
    }

    companion object {
        /** Blocks per section edge. */
        const val EDGE = 16

        /** Total blocks in a section. */
        const val BLOCKS = EDGE * EDGE * EDGE

        /**
         * Index of a block within a section. Callers pass either local (0..15) or world
         * coordinates — the mask makes both correct.
         */
        fun index(x: Int, y: Int, z: Int): Int =
            ((x and 15) shl 8) or ((z and 15) shl 4) or (y and 15)

        /** A section where every block holds [value], stored in a single byte. */
        fun uniform(value: Byte): NavSection = NavSection(value, null)

        /**
         * A section backed by [dense], collapsed back to uniform storage when every block agrees.
         * [dense] must not be retained or mutated by the caller afterwards.
         */
        fun of(dense: ByteArray): NavSection {
            require(dense.size == BLOCKS) { "NavSection needs exactly $BLOCKS bytes, got ${dense.size}" }
            val first = dense[0]
            for (b in dense) {
                if (b != first) return NavSection(first, dense)
            }
            return NavSection(first, null)
        }
    }
}
