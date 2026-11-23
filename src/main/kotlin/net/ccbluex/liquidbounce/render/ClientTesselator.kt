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

package net.ccbluex.liquidbounce.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import net.ccbluex.fastutil.Pool
import net.ccbluex.liquidbounce.utils.render.begin

object ClientTesselator {

    private const val SORT_SCRATCH_BUFFER_SIZE = 0xC0000

    /**
     * Initial size of a pooled allocator, *not* a cap — [ByteBufferBuilder] resizes itself on
     * demand.
     *
     * The pool grows to the peak number of distinct [net.ccbluex.liquidbounce.render.RenderBufferKey]s
     * in one flush and never shrinks (it is an unbounded free list). Seeding every entry at the
     * shared size meant a frame that emits many single-object batches — e.g. the target-ESP orbs,
     * which take a distinct key each because each carries its own uniform slice — permanently
     * parked 768 KiB of native memory per batch, most of it holding a few kilobytes of vertices.
     */
    private const val POOLED_BUFFER_SIZE = 0x4000

    /**
     * Scratch space for [com.mojang.blaze3d.vertex.MeshData.sortQuads] only, cleared once per frame
     * by `MixinRenderSystem.onFlipFrame`.
     *
     * It must **not** also back an open [BufferBuilder]: the quad sort reserves from the allocator
     * it is handed, so sorting a mesh while another builder is mid-write into the same allocator
     * interleaves the two. That is exactly what happened when an immediate draw was nested inside
     * another one — the inner draw's sort scribbled into the outer draw's vertex stream.
     */
    @JvmField
    val SortScratch = ByteBufferBuilder(SORT_SCRATCH_BUFFER_SIZE)

    private val bufferAllocatorPool = Pool(
        initializer = { ByteBufferBuilder(POOLED_BUFFER_SIZE) },
        finalizer = ByteBufferBuilder::clear,
    )

    @JvmStatic
    fun begin(
        pipeline: RenderPipeline,
        allocatorInUse: MutableCollection<ByteBufferBuilder>,
    ): BufferBuilder {
        val allocator = bufferAllocatorPool.borrow()
        allocatorInUse += allocator
        return allocator.begin(pipeline)
    }

    /**
     * Borrow a standalone allocator. The caller owns it until it hands it back to [recycle].
     */
    @JvmStatic
    fun borrowAllocator(): ByteBufferBuilder = bufferAllocatorPool.borrow()

    @JvmStatic
    fun recycle(allocator: ByteBufferBuilder) {
        bufferAllocatorPool.recycle(allocator)
    }

    @JvmStatic
    fun recycleAll(allocatorInUse: Iterable<ByteBufferBuilder>) {
        bufferAllocatorPool.recycleAll(allocatorInUse)
    }

}
