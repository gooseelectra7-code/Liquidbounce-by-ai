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

import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.NavSectionGraph
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pathfinder's own view of the world: every loaded block reduced to a packed navigation byte,
 * readable from any thread without touching `mc.level`.
 *
 * This is the foundation the whole engine rests on. The previous pathfinder called
 * `world.getBlockCollisions(player, box)` from a `Dispatchers.Default` coroutine — a data race with
 * the main thread, and the dominant cost of every calculation. Here the expensive part (tag lookups,
 * shape intersections, hardness) happens once per block when it enters the cache, on the
 * [ChunkScanner] worker pool that already exists for exactly this purpose. Search then reads plain
 * bytes out of arrays.
 *
 * Use [reader] to obtain a cursor for a single-threaded calculation; it caches the last chunk and
 * turns the common "read the column below/at/above me" pattern into three adjacent array accesses.
 *
 * The cache is reference counted: call [acquire] when a consumer starts needing navigation data and
 * [release] when it stops, so several modules can share one cache without fighting over the
 * [ChunkScanner] subscription.
 */
object NavWorld : ChunkScanner.BlockChangeSubscriber {

    private val chunks = ConcurrentHashMap<Long, NavChunk>()
    private val consumers = AtomicInteger(0)

    override val debugName: String get() = "NavWorld"

    /**
     * Chunk loads are handled by rebuilding the whole [NavChunk] from the section palettes, which is
     * far cheaper than 98304 individual callbacks.
     */
    override val shouldCallRecordBlockOnChunkUpdate: Boolean get() = false

    // -----------------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------------

    /** Number of consumers currently keeping the cache alive. */
    val consumerCount: Int get() = consumers.get()

    /** True while the cache is subscribed and being kept up to date. */
    val isActive: Boolean get() = consumers.get() > 0

    /** Registers interest in navigation data, subscribing to [ChunkScanner] on the first caller. */
    @Synchronized
    fun acquire() {
        if (consumers.getAndIncrement() == 0) {
            ChunkScanner.subscribe(this)
        }
    }

    /**
     * Drops interest; the last caller out unsubscribes and frees the cache.
     *
     * [ChunkScanner.unsubscribe] clears our chunks for us, so no separate teardown is needed.
     */
    @Synchronized
    fun release() {
        if (consumers.decrementAndGet() <= 0) {
            consumers.set(0)
            ChunkScanner.unsubscribe(this)
        }
    }

    // -----------------------------------------------------------------------
    //  Reads — safe from any thread
    // -----------------------------------------------------------------------

    /** A single-threaded cursor over this cache. One per calculation. */
    fun reader(): NavWorldReader = NavWorldReader(this)

    /** The navigation chunk covering a block position, or null when it is not loaded. */
    fun chunkAt(blockX: Int, blockZ: Int): NavChunk? = chunks[ChunkPos.pack(blockX shr 4, blockZ shr 4)]

    /** Packed navigation byte at a world position; unknown when the chunk is not loaded. */
    fun packedAt(x: Int, y: Int, z: Int): Byte =
        chunkAt(x, z)?.get(x, y, z) ?: NavBlockClass.PACKED_UNKNOWN

    /** Navigation class at a world position. */
    fun navClassAt(x: Int, y: Int, z: Int): Int = NavBlockClass.navClassOf(packedAt(x, y, z))

    /** Dig bucket at a world position. */
    fun digBucketAt(x: Int, y: Int, z: Int): Int = NavBlockClass.digBucketOf(packedAt(x, y, z))

    /** Whether the chunk containing this block column has been received. */
    fun isLoaded(blockX: Int, blockZ: Int): Boolean = chunkAt(blockX, blockZ) != null

    /** Highest standable Y in a column, or [Int.MIN_VALUE] when unknown or empty. */
    fun surfaceY(x: Int, z: Int): Int = chunkAt(x, z)?.surfaceY(x, z) ?: Int.MIN_VALUE

    /** Number of cached chunks. */
    val loadedChunkCount: Int get() = chunks.size

    /**
     * Exclusive upper bound of buildable Y in the current dimension, or [Int.MAX_VALUE] while
     * nothing has been cached yet.
     *
     * Recorded as chunks arrive rather than read from the level, so a planner on the search thread
     * can ask how much sky there is without touching the client. It is what stops a flight being
     * planned at a cruising height the dimension does not have — the Nether, whose ground is its
     * own roof, is the case that matters.
     */
    @Volatile
    var buildCeiling: Int = Int.MAX_VALUE
        private set

    /** Total heap held by dense sections, in bytes. Uniform sections cost nothing. */
    fun denseBytes(): Long = chunks.values.sumOf { it.denseBytes().toLong() }

    // -----------------------------------------------------------------------
    //  ChunkScanner.BlockChangeSubscriber — writes, on scanner worker threads
    // -----------------------------------------------------------------------

    override fun chunkUpdate(chunk: LevelChunk) {
        // Cancellation is cooperative, and a rebuild already under way when the player changes
        // dimension has no suspension point to stop at — so it finishes and inserts an Overworld
        // column into a cache that was cleared for the Nether, taking the build ceiling with it.
        // A flight there is then planned at a cruising altitude the dimension does not have.
        if (chunk.level !== mc.level) return

        val navChunk = NavChunk.fromLevelChunk(chunk)
        chunks[chunk.pos.pack()] = navChunk
        buildCeiling = navChunk.maxY
    }

    override fun recordBlock(pos: BlockPos, state: BlockState, cleared: Boolean) {
        // The position may be a MutableBlockPos the scanner reuses, so read it out before anything
        // else happens.
        val x = pos.x
        val y = pos.y
        val z = pos.z
        val packed = NavBlockClass.classifyCached(state)

        // Written through the map rather than into a chunk looked up a moment ago. Both callbacks
        // run on the scanner pool, and a rebuild replacing this column between the lookup and the
        // write would take the update into an instance nothing will ever read again — and unlike a
        // block update that arrives *before* its chunk, which the following chunkUpdate covers,
        // nothing repairs that one: the block keeps its pre-update value until the server resends
        // the chunk, which is a doorway that never opens or a wall that never appears.
        val updated = chunks.computeIfPresent(ChunkPos.pack(x shr 4, z shr 4)) { _, chunk ->
            chunk.set(x, y, z, packed)
            chunk.updateSurfaceAfterSet(x, y, z, NavBlockClass.navClassOf(packed))
            chunk
        }
        if (updated == null) return

        // One block can join or split a whole pocket of standing room, and a stale pocket sends the
        // coarse layer confidently down a corridor that no longer exists.
        NavSectionGraph.invalidate(x, y, z)
    }

    override fun clearChunk(pos: ChunkPos) {
        chunks.remove(pos.pack())
        NavSectionGraph.invalidateColumn(pos.x, pos.z)
    }

    override fun clearAllChunks() {
        chunks.clear()
        buildCeiling = Int.MAX_VALUE
        NavSectionGraph.clear()
        // Classification reads block tags, and tags come from the server. Keeping the memo across a
        // world change means a class decided against the last server's tag set — or against none at
        // all, in the window before the first tag packet — is the answer for the rest of the
        // session. This is the only moment that hook has ever had a caller.
        NavBlockClass.invalidateClassificationCache()
    }
}

/**
 * Single-threaded cursor over [NavWorld].
 *
 * Ground search reads roughly twenty blocks per expanded node, nearly all of them in the same chunk
 * as the previous read. Remembering the last chunk turns almost every one of those into a bounds
 * check plus an array access, skipping the concurrent hash lookup entirely.
 *
 * Not thread-safe by design — create one per calculation and keep it on that thread.
 */
class NavWorldReader(private val world: NavWorld) {

    private var cachedKey: Long = NO_CHUNK
    private var cachedChunk: NavChunk? = null

    /** Chunk lookups served from the one-entry cache, for benchmarking. */
    var hits: Long = 0
        private set

    /** Chunk lookups that fell through to the shared map, for benchmarking. */
    var misses: Long = 0
        private set

    private fun chunkAt(x: Int, z: Int): NavChunk? {
        val key = ChunkPos.pack(x shr 4, z shr 4)
        if (key == cachedKey) {
            hits++
            return cachedChunk
        }
        misses++
        val chunk = world.chunkAt(x, z)
        cachedKey = key
        cachedChunk = chunk
        return chunk
    }

    /** Packed navigation byte at a world position; unknown when the chunk is not loaded. */
    fun packed(x: Int, y: Int, z: Int): Byte =
        chunkAt(x, z)?.get(x, y, z) ?: NavBlockClass.PACKED_UNKNOWN

    /** Packed navigation byte at a [BlockPos.asLong] packed position. */
    fun packed(packedPos: Long): Byte = packed(
        BlockPos.getX(packedPos),
        BlockPos.getY(packedPos),
        BlockPos.getZ(packedPos),
    )

    /** Navigation class at a world position. */
    fun navClass(x: Int, y: Int, z: Int): Int = NavBlockClass.navClassOf(packed(x, y, z))

    /** Dig bucket at a world position. */
    fun digBucket(x: Int, y: Int, z: Int): Int = NavBlockClass.digBucketOf(packed(x, y, z))

    /** Whether the chunk containing this block column has been received. */
    fun isLoaded(x: Int, z: Int): Boolean = chunkAt(x, z) != null

    /** Highest standable Y in a column, or [Int.MIN_VALUE] when unknown or empty. */
    fun surfaceY(x: Int, z: Int): Int = chunkAt(x, z)?.surfaceY(x, z) ?: Int.MIN_VALUE

    /** Forgets the cached chunk. Call after the world changes under a long-lived reader. */
    fun invalidate() {
        cachedKey = NO_CHUNK
        cachedChunk = null
    }

    private companion object {
        /**
         * Empty-cache marker. [ChunkPos.pack] only produces this for chunk (0, Integer.MIN_VALUE),
         * which lies far outside the world border, so no real chunk can collide with it.
         */
        const val NO_CHUNK = Long.MIN_VALUE
    }
}
