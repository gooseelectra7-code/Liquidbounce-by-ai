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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map

import com.mojang.blaze3d.platform.NativeImage
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.BlockChangeEvent
import net.ccbluex.liquidbounce.event.events.ChunkDeltaUpdateEvent
import net.ccbluex.liquidbounce.event.events.ChunkLoadEvent
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.ResourceReloadEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.MapIdentity
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.moveReplacing
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The collected world map of the current server and dimension.
 *
 * Capture: chunks the server sends ([ChunkLoadEvent]) and block changes ([BlockChangeEvent],
 * [ChunkDeltaUpdateEvent]) are QUEUED and sampled on the main thread inside a per-tick time budget
 * ([CAPTURE_BUDGET_NANOS]). Nothing reads the world off-thread - unlike the async `ChunkScanner`,
 * which races the main thread's block updates. A block change re-samples its column and the two
 * neighbours whose shading depends on it; a change far below a known surface is ignored.
 *
 * Only while [collecting] (WorldMap's `Collect`, on by default). Only what the server sends can be captured:
 * the map grows as far as the render distance reaches, never further.
 *
 * Storage: [MapTile]s of 512x512 blocks, at most [MAX_RESIDENT] in memory (least-recently-used
 * eviction, never a tile used in the last [EVICT_IDLE_NANOS]); a PNG plus a gzip height file per
 * tile on disk. Disk IO runs on one daemon thread, FIFO, so a load queued after a save of the same
 * tile always reads the saved file. The IO thread only ever touches detached [TileSnapshot]s.
 *
 * Tiles are created during frames but never closed while a frame is being drawn: a texture closed
 * mid-frame could already be referenced by a GUI element extracted earlier in that frame. They are
 * closed by the tick handler (eviction, capture switched off, context switch), at shutdown, and by a
 * context switch noticed in [beginFrame] - which runs before the frame draws any tile, and the HUD
 * minimap and the map screen never both draw in one frame.
 *
 * Which tiles exist on disk is indexed per FOLDER for the whole session, not per context: saves are
 * queued, so a folder listing taken when the player re-enters a dimension would miss a tile whose
 * first save is still waiting on the IO thread, and a blank tile would then overwrite it.
 */
internal object WorldMapStore : EventListener {

    private const val MAX_RESIDENT = 64
    private const val EVICT_IDLE_NANOS = 3_000_000_000L
    private const val SAVE_INTERVAL_MS = 30_000L
    private const val CAPTURE_BUDGET_NANOS = 2_500_000L
    private const val MAX_CHUNKS_PER_TICK = 64
    private const val MAX_COLUMNS_PER_TICK = 4096

    /** A block change this far below the captured surface cannot change the map. */
    private const val INVISIBLE_DEPTH = 24

    private const val SLOPE_CLAMP = 4f
    private const val SLOPE_BRIGHTNESS = 0.08f

    private const val HEIGHT_MAGIC = 0x4C424D48 // "LBMH"
    private const val HEIGHT_VERSION = 1

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiquidBounce WorldMap IO").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    private class Context(
        val serverKey: String,
        val dimension: String,
        val folder: File,
        /** Shared with every later context of the same folder, see [diskIndex]. */
        val onDisk: LongOpenHashSet,
    ) {
        val tiles = Long2ObjectOpenHashMap<MapTile>()
        val loading = LongOpenHashSet()

        /** Chunks whose tile is being read from disk, keyed by tile. */
        val waiting = Long2ObjectOpenHashMap<LongOpenHashSet>()
    }

    private class LoadResult(
        val context: Context,
        val rx: Int,
        val rz: Int,
        val image: NativeImage?,
        val heights: ShortArray,
    )

    private var context: Context? = null
    private var lastLevel: ClientLevel? = null
    private var ceiling = false
    private var lastSaveMs = System.currentTimeMillis()

    private val loadResults = ConcurrentLinkedQueue<LoadResult>()
    private val pendingChunks = LongLinkedOpenHashSet()
    private val pendingColumns = LongLinkedOpenHashSet()
    private var enqueueLoadedChunks = false

    private val cursor = BlockPos.MutableBlockPos()
    private val sample = MapSampler.Sample()

    /** Tile keys on disk (or queued to be written), per map folder, for the whole session. */
    private val diskIndex = HashMap<String, LongOpenHashSet>()

    private var collectCondition: () -> Boolean = { false }

    /**
     * Captures while [condition] holds; polled every tick. Owned by the Minimap module.
     */
    fun collectWhile(condition: () -> Boolean) {
        collectCondition = condition
    }

    /**
     * Whether the world is being captured. Switching it on captures every chunk already loaded,
     * switching it off saves and releases every tile. Changed only from the tick handler.
     */
    private var collecting = false
        set(value) {
            if (value == field) return
            field = value
            if (value) {
                enqueueLoadedChunks = true
            } else {
                pendingChunks.clear()
                pendingColumns.clear()
                context?.let { releaseAll(it) }
            }
        }

    /** Dimension the map currently shows, `null` outside a world. */
    val dimension: String? get() = context?.dimension

    // ------------------------------------------------------------------------------------------
    //  Read side (HUD and the map screen)
    // ------------------------------------------------------------------------------------------

    /**
     * Integrates finished disk reads. Called at the start of every map draw so a tile that arrives
     * mid-frame shows at once rather than on the next tick.
     */
    fun beginFrame() {
        syncContext()
        pollLoads()
    }

    /**
     * The tile, if it is in memory. Requests it from disk otherwise and returns `null` for now.
     */
    fun tile(rx: Int, rz: Int): MapTile? {
        val ctx = context ?: return null
        val key = MapTile.key(rx, rz)
        ctx.tiles.get(key)?.let { return it }
        if (ctx.onDisk.contains(key)) {
            requestLoad(ctx, key)
        }
        return null
    }

    /**
     * Captured height of a column, `null` when unknown or not in memory.
     */
    fun heightAt(x: Int, z: Int): Int? {
        val ctx = context ?: return null
        val height = heightOf(ctx, x, z)
        return if (height == MapTile.UNKNOWN) null else height.toInt()
    }

    /** Whether a tile exists for the square, in memory or on disk. */
    fun hasTile(rx: Int, rz: Int): Boolean {
        val ctx = context ?: return false
        val key = MapTile.key(rx, rz)
        return ctx.tiles.containsKey(key) || ctx.onDisk.contains(key)
    }

    val residentTiles: Int get() = context?.tiles?.size ?: 0
    val loadingTiles: Int get() = context?.loading?.size ?: 0

    // ------------------------------------------------------------------------------------------
    //  Events
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        collecting = collectCondition()
        syncContext()
        pollLoads()

        val ctx = context ?: return@handler
        val level = mc.level ?: return@handler

        if (collecting) {
            capture(ctx, level)
        }

        evictIdle(ctx)

        val now = System.currentTimeMillis()
        if (now - lastSaveMs >= SAVE_INTERVAL_MS) {
            lastSaveMs = now
            saveAll(ctx)
        }
    }

    @Suppress("unused")
    private val chunkLoadHandler = handler<ChunkLoadEvent> { event ->
        if (collecting && context != null) {
            pendingChunks.add(packChunk(event.x, event.z))
        }
    }

    @Suppress("unused")
    private val blockChangeHandler = handler<BlockChangeEvent> { event ->
        if (!collecting) return@handler
        val ctx = context ?: return@handler
        val pos = event.blockPos

        if (!ceiling) {
            val known = heightOf(ctx, pos.x, pos.z)
            if (known != MapTile.UNKNOWN && pos.y < known - INVISIBLE_DEPTH) return@handler
        }

        pendingColumns.add(packColumn(pos.x, pos.z))
    }

    @Suppress("unused")
    private val chunkDeltaHandler = handler<ChunkDeltaUpdateEvent> { event ->
        if (collecting && context != null) {
            val section = event.packet.sectionPos
            pendingChunks.add(packChunk(section.x(), section.z()))
        }
    }

    /** A resource pack change changes block textures, and with them the map colours. */
    @Suppress("unused")
    private val resourceReloadHandler = handler<ResourceReloadEvent> {
        BlockTopColors.clear()
    }

    @Suppress("unused")
    private val shutdownHandler = handler<ClientShutdownEvent> {
        closeContext()
        io.shutdown()
        runCatching { io.awaitTermination(10, TimeUnit.SECONDS) }
    }

    // ------------------------------------------------------------------------------------------
    //  Context
    // ------------------------------------------------------------------------------------------

    private fun syncContext() {
        val level = mc.level
        if (level === lastLevel) return
        lastLevel = level

        pendingChunks.clear()
        pendingColumns.clear()

        val serverKey = level?.let { MapIdentity.currentServerKey() }
        val dimension = level?.let { MapIdentity.currentDimension() }
        if (level == null || serverKey == null || dimension == null) {
            closeContext()
            return
        }

        ceiling = level.dimensionType().hasCeiling()

        val current = context
        if (current == null || current.serverKey != serverKey || current.dimension != dimension) {
            closeContext()
            openContext(serverKey, dimension)
        }

        if (collecting) {
            enqueueLoadedChunks = true
        }
    }

    private fun openContext(serverKey: String, dimension: String) {
        val folder = File(
            File(MapIdentity.serverFolder(serverKey), "map"),
            MapIdentity.dimensionFolderName(dimension),
        )
        val onDisk = diskIndex.getOrPut(folder.absolutePath) { listTiles(folder) }
        context = Context(serverKey, dimension, folder, onDisk)
        lastSaveMs = System.currentTimeMillis()
    }

    private fun listTiles(folder: File): LongOpenHashSet {
        val keys = LongOpenHashSet()
        folder.listFiles()?.forEach { file ->
            val name = file.name
            if (!name.endsWith(".png")) return@forEach
            val parts = name.removeSuffix(".png").split('.')
            if (parts.size != 2) return@forEach
            val rx = parts[0].toIntOrNull() ?: return@forEach
            val rz = parts[1].toIntOrNull() ?: return@forEach
            keys.add(MapTile.key(rx, rz))
        }
        return keys
    }

    private fun closeContext() {
        val ctx = context ?: return
        context = null
        releaseAll(ctx)
        ctx.waiting.clear()
    }

    private fun releaseAll(ctx: Context) {
        for (tile in ctx.tiles.values) {
            save(ctx, tile)
            tile.close()
        }
        ctx.tiles.clear()
    }

    // ------------------------------------------------------------------------------------------
    //  Capture
    // ------------------------------------------------------------------------------------------

    private fun capture(ctx: Context, level: ClientLevel) {
        if (enqueueLoadedChunks) {
            enqueueLoadedChunks = false
            enqueueLoaded()
        }

        val deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS

        var columns = 0
        while (!pendingColumns.isEmpty() && columns < MAX_COLUMNS_PER_TICK) {
            val packed = pendingColumns.removeFirstLong()
            val x = unpackA(packed)
            val z = unpackB(packed)
            captureColumn(ctx, level, x, z)
            // Its shading feeds the south and east neighbours.
            captureColumn(ctx, level, x, z + 1)
            captureColumn(ctx, level, x + 1, z)
            columns++
            if ((columns and 63) == 0 && System.nanoTime() > deadline) return
        }

        var chunks = 0
        while (!pendingChunks.isEmpty() && chunks < MAX_CHUNKS_PER_TICK && System.nanoTime() < deadline) {
            val packed = pendingChunks.removeFirstLong()
            captureChunk(ctx, level, unpackA(packed), unpackB(packed))
            chunks++
        }
    }

    /**
     * Every loaded chunk around the player, nearest first.
     */
    private fun enqueueLoaded() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val centerX = player.blockX shr 4
        val centerZ = player.blockZ shr 4
        val radius = mc.options.renderDistance().get() + 2

        val found = ArrayList<Long>()
        for (dz in -radius..radius) {
            for (dx in -radius..radius) {
                val chunk = level.chunkSource.getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false)
                if (chunk != null && !chunk.isEmpty) {
                    found += packChunk(centerX + dx, centerZ + dz)
                }
            }
        }
        found.sortBy { packed ->
            val dx = unpackA(packed) - centerX
            val dz = unpackB(packed) - centerZ
            dx * dx + dz * dz
        }
        found.forEach { pendingChunks.add(it) }
    }

    private fun loadedChunk(level: ClientLevel, chunkX: Int, chunkZ: Int): LevelChunk? =
        level.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)?.takeUnless { it.isEmpty }

    private fun captureChunk(ctx: Context, level: ClientLevel, chunkX: Int, chunkZ: Int) {
        val chunk = loadedChunk(level, chunkX, chunkZ) ?: return
        val tile = tileForCapture(ctx, chunkX shr 5, chunkZ shr 5)
        if (tile == null) {
            val tileKey = MapTile.key(chunkX shr 5, chunkZ shr 5)
            ctx.waiting.computeIfAbsent(tileKey) { LongOpenHashSet() }.add(packChunk(chunkX, chunkZ))
            return
        }

        val baseX = chunkX shl 4
        val baseZ = chunkZ shl 4
        for (lz in 0..15) {
            for (lx in 0..15) {
                writeColumn(ctx, level, chunk, tile, baseX + lx, baseZ + lz)
            }
        }

        // The chunks to the south and east shade their edge against this one; they may have been
        // captured before this one existed.
        loadedChunk(level, chunkX, chunkZ + 1)?.let { south ->
            val southTile = ctx.tiles.get(MapTile.key(chunkX shr 5, (chunkZ + 1) shr 5)) ?: return@let
            for (lx in 0..15) writeColumn(ctx, level, south, southTile, baseX + lx, baseZ + 16)
        }
        loadedChunk(level, chunkX + 1, chunkZ)?.let { east ->
            val eastTile = ctx.tiles.get(MapTile.key((chunkX + 1) shr 5, chunkZ shr 5)) ?: return@let
            for (lz in 0..15) writeColumn(ctx, level, east, eastTile, baseX + 16, baseZ + lz)
        }
    }

    private fun captureColumn(ctx: Context, level: ClientLevel, x: Int, z: Int) {
        val chunkX = x shr 4
        val chunkZ = z shr 4
        val chunk = loadedChunk(level, chunkX, chunkZ) ?: return
        val tile = tileForCapture(ctx, x shr MapTile.SHIFT, z shr MapTile.SHIFT)
        if (tile == null) {
            val tileKey = MapTile.key(x shr MapTile.SHIFT, z shr MapTile.SHIFT)
            ctx.waiting.computeIfAbsent(tileKey) { LongOpenHashSet() }.add(packChunk(chunkX, chunkZ))
            return
        }
        writeColumn(ctx, level, chunk, tile, x, z)
    }

    private fun writeColumn(ctx: Context, level: ClientLevel, chunk: LevelChunk, tile: MapTile, x: Int, z: Int) {
        MapSampler.sample(level, chunk, x, z, ceiling, cursor, sample)

        var color = sample.color
        if (!sample.flat) {
            val height = sample.height
            val north = heightOf(ctx, x, z - 1)
            val west = heightOf(ctx, x - 1, z)
            var slope = 0f
            if (north != MapTile.UNKNOWN) slope += height - north
            if (west != MapTile.UNKNOWN) slope += height - west
            slope = (slope * 0.5f).coerceIn(-SLOPE_CLAMP, SLOPE_CLAMP)
            if (slope != 0f) {
                color = scaleClamped(color, 1f + slope * SLOPE_BRIGHTNESS)
            }
        }

        tile.set(x and MapTile.MASK, z and MapTile.MASK, color, sample.height)
        tile.lastUsed = System.nanoTime()
    }

    private fun scaleClamped(argb: Int, factor: Float): Int = ARGB.color(
        ARGB.alpha(argb),
        (ARGB.red(argb) * factor).toInt().coerceIn(0, 255),
        (ARGB.green(argb) * factor).toInt().coerceIn(0, 255),
        (ARGB.blue(argb) * factor).toInt().coerceIn(0, 255),
    )

    private fun heightOf(ctx: Context, x: Int, z: Int): Short {
        val tile = ctx.tiles.get(MapTile.key(x shr MapTile.SHIFT, z shr MapTile.SHIFT)) ?: return MapTile.UNKNOWN
        return tile.height(x and MapTile.MASK, z and MapTile.MASK)
    }

    private fun tileForCapture(ctx: Context, rx: Int, rz: Int): MapTile? {
        val key = MapTile.key(rx, rz)
        ctx.tiles.get(key)?.let { return it }

        if (ctx.onDisk.contains(key)) {
            requestLoad(ctx, key)
            return null
        }

        val tile = MapTile.blank(rx, rz)
        ctx.tiles.put(key, tile)
        return tile
    }

    // ------------------------------------------------------------------------------------------
    //  Memory
    // ------------------------------------------------------------------------------------------

    private fun evictIdle(ctx: Context) {
        // Not capturing: nothing needs a tile that is not on screen (the map screen loads on demand).
        val budget = if (collecting) MAX_RESIDENT else 0
        if (ctx.tiles.size <= budget) return

        val now = System.nanoTime()
        val candidates = ctx.tiles.values
            .filter { now - it.lastUsed > EVICT_IDLE_NANOS }
            .sortedBy { it.lastUsed }

        var excess = ctx.tiles.size - budget
        for (tile in candidates) {
            if (excess <= 0) break
            save(ctx, tile)
            tile.close()
            ctx.tiles.remove(tile.key)
            excess--
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Disk
    // ------------------------------------------------------------------------------------------

    private fun saveAll(ctx: Context) {
        for (tile in ctx.tiles.values) {
            save(ctx, tile)
        }
    }

    private fun save(ctx: Context, tile: MapTile) {
        if (!tile.diskDirty || tile.closed) return
        tile.diskDirty = false
        ctx.onDisk.add(tile.key)

        val snapshot = tile.snapshot()
        val folder = ctx.folder
        try {
            io.execute { write(folder, snapshot) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            snapshot.image.close()
            logger.warn("[WorldMap] Tile ${tile.rx},${tile.rz} not saved: IO already shut down", e)
        }
    }

    private fun requestLoad(ctx: Context, key: Long) {
        if (!ctx.loading.add(key)) return
        val rx = MapTile.keyX(key)
        val rz = MapTile.keyZ(key)
        val folder = ctx.folder
        runCatching {
            io.execute {
                loadResults.add(read(ctx, folder, rx, rz))
            }
        }.onFailure {
            // Executor already shut down (game closing).
            ctx.loading.remove(key)
        }
    }

    private fun pollLoads() {
        while (true) {
            val result = loadResults.poll() ?: return
            val ctx = context
            if (result.context !== ctx || ctx == null) {
                result.image?.close()
                continue
            }

            val key = MapTile.key(result.rx, result.rz)
            ctx.loading.remove(key)

            if (result.image == null) {
                // Unreadable: forget the file, a fresh tile will replace it.
                ctx.onDisk.remove(key)
            } else if (ctx.tiles.containsKey(key)) {
                result.image.close()
            } else {
                ctx.tiles.put(key, MapTile(result.rx, result.rz, result.image, result.heights))
            }

            ctx.waiting.remove(key)?.forEach { pendingChunks.add(it) }
        }
    }

    private fun write(folder: File, snapshot: TileSnapshot) {
        try {
            folder.mkdirs()
            val base = "${snapshot.rx}.${snapshot.rz}"

            val pngTemp = File(folder, "$base.png.tmp")
            snapshot.image.writeToFile(pngTemp)
            moveReplacing(pngTemp, File(folder, "$base.png"))

            val heightTemp = File(folder, "$base.hgt.tmp")
            val bytes = ByteBuffer.allocate(snapshot.heights.size * 2)
            bytes.asShortBuffer().put(snapshot.heights)
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(heightTemp.outputStream()))).use { out ->
                out.writeInt(HEIGHT_MAGIC)
                out.writeInt(HEIGHT_VERSION)
                out.write(bytes.array())
            }
            moveReplacing(heightTemp, File(folder, "$base.hgt"))
        } catch (e: Exception) {
            logger.warn("[WorldMap] Failed to save tile ${snapshot.rx},${snapshot.rz} in $folder", e)
        } finally {
            snapshot.image.close()
        }
    }

    private fun read(ctx: Context, folder: File, rx: Int, rz: Int): LoadResult {
        val base = "$rx.$rz"
        val png = File(folder, "$base.png")

        val image = try {
            NativeImage.read(png.inputStream()).takeIf { image ->
                val valid = image.width == MapTile.SIZE && image.height == MapTile.SIZE &&
                    image.format() == NativeImage.Format.RGBA
                if (!valid) image.close()
                valid
            }
        } catch (e: Exception) {
            logger.warn("[WorldMap] Unreadable tile $png, it will be rebuilt", e)
            null
        }

        if (image == null) {
            png.renameTo(File(folder, "$base.png.corrupt"))
            return LoadResult(ctx, rx, rz, null, MapTile.newHeights())
        }

        val heights = runCatching { readHeights(File(folder, "$base.hgt")) }.getOrNull()
            ?: MapTile.newHeights()
        return LoadResult(ctx, rx, rz, image, heights)
    }

    private fun readHeights(file: File): ShortArray? {
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use { input ->
            if (input.readInt() != HEIGHT_MAGIC || input.readInt() != HEIGHT_VERSION) return null
            val bytes = ByteArray(MapTile.SIZE * MapTile.SIZE * 2)
            input.readFully(bytes)
            val heights = ShortArray(MapTile.SIZE * MapTile.SIZE)
            ByteBuffer.wrap(bytes).asShortBuffer().get(heights)
            return heights
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Packing
    // ------------------------------------------------------------------------------------------

    private fun packChunk(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    private fun packColumn(x: Int, z: Int): Long = packChunk(x, z)
    private fun unpackA(packed: Long): Int = (packed shr 32).toInt()
    private fun unpackB(packed: Long): Int = packed.toInt()
}
