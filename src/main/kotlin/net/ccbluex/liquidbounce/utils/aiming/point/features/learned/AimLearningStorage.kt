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
package net.ccbluex.liquidbounce.utils.aiming.point.features.learned

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.fileGson
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory weighted-cell histogram of successful aim points, bucketed by combat situation.
 *
 * Each [BucketKey] maps to a small 3D grid (resolution = [gridResolution] per axis) of cells over
 * the entity's normalized bounding-box coordinates [0..1]³. [record] increments the cell weight
 * for the offset of a successful attack; [sample] draws a weighted-random cell to bias future aim.
 *
 * Persisted as JSON to `<gameDir>/LiquidBounce/aim-learning.json`. Saves are debounced through a
 * `dirty` flag — call [save] manually (e.g. on disable/world unload) rather than on every record.
 */
object AimLearningStorage {

    private const val FILE_NAME = "aim-learning.json"
    const val DEFAULT_GRID_RESOLUTION = 6

    private val storageFile: File
        get() = File(ConfigSystem.rootFolder, FILE_NAME)

    private val buckets =
        ConcurrentHashMap<BucketKey, ConcurrentHashMap<Triple<Int, Int, Int>, Float>>()

    @Volatile
    var gridResolution: Int = DEFAULT_GRID_RESOLUTION
        private set

    @Volatile
    private var dirty: Boolean = false

    @Volatile
    private var initialized: Boolean = false

    /**
     * Idempotent load. Multiple [net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorLearned]
     * instances (one per module using PointTracker) call this in init — first call hits disk, rest no-op.
     */
    @Synchronized
    fun ensureLoaded() {
        if (initialized) return
        load()
        initialized = true
    }

    fun bucketSize(key: BucketKey): Int = buckets[key]?.size ?: 0

    fun totalSamples(): Int = buckets.values.sumOf { it.size }

    fun stats(): StorageStats {
        val bucketCount = buckets.size
        val cellCount = buckets.values.sumOf { it.size }
        val totalWeight = buckets.values.sumOf { bucket ->
            bucket.values.sumOf { it.toDouble() }
        }.toFloat()
        return StorageStats(bucketCount, cellCount, totalWeight, gridResolution)
    }

    /**
     * Snapshot of per-bucket sample counts, sorted descending by weight. Used by the chat command
     * to print which (distance, hurtTime) bins are well-represented.
     */
    fun perBucketBreakdown(): List<BucketBreakdown> = buckets.entries
        .map { (key, cells) ->
            BucketBreakdown(
                key = key,
                cellCount = cells.size,
                totalWeight = cells.values.sumOf { it.toDouble() }.toFloat(),
            )
        }
        .sortedByDescending { it.totalWeight }

    fun record(key: BucketKey, cellX: Int, cellY: Int, cellZ: Int, weight: Float = 1f) {
        val bucket = buckets.getOrPut(key) { ConcurrentHashMap() }
        val cell = Triple(cellX, cellY, cellZ)
        bucket.merge(cell, weight) { old, add -> old + add }
        dirty = true
    }

    /**
     * Weighted-random cell pick. Returns null if the bucket has no samples.
     */
    fun sampleCell(key: BucketKey, rng: Random): Triple<Int, Int, Int>? {
        val bucket = buckets[key] ?: return null
        if (bucket.isEmpty()) return null
        val totalWeight = bucket.values.sumOf { it.toDouble() }
        if (totalWeight <= 0.0) return null
        var roll = rng.nextDouble() * totalWeight
        for ((cell, weight) in bucket) {
            roll -= weight
            if (roll <= 0.0) return cell
        }
        return bucket.keys.firstOrNull()
    }

    fun reset() {
        buckets.clear()
        dirty = true
    }

    @Synchronized
    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        val snapshot = StoredData(
            gridResolution = gridResolution,
            buckets = buckets.entries.map { (bk, cells) ->
                StoredBucket(
                    distBin = bk.distBin,
                    hurtBin = bk.hurtBin,
                    cells = cells.entries.map { (c, w) -> StoredCell(c.first, c.second, c.third, w) }
                )
            }
        )
        runCatching {
            storageFile.bufferedWriter().use { fileGson.toJson(snapshot, it) }
            dirty = false
            logger.debug("Saved aim-learning storage: ${snapshot.buckets.size} buckets")
        }.onFailure { logger.error("Failed to save aim-learning storage", it) }
    }

    @Synchronized
    fun load() {
        val file = storageFile
        if (!file.exists()) return
        runCatching {
            val raw = file.bufferedReader().use { fileGson.fromJson(it, StoredData::class.java) }
                ?: return@runCatching
            gridResolution = raw.gridResolution.coerceIn(MIN_GRID, MAX_GRID)
            buckets.clear()
            for (sb in raw.buckets) {
                val key = BucketKey(sb.distBin, sb.hurtBin)
                val map = buckets.getOrPut(key) { ConcurrentHashMap() }
                for (c in sb.cells) {
                    map[Triple(c.x, c.y, c.z)] = c.w
                }
            }
            dirty = false
            logger.debug("Loaded aim-learning storage: ${raw.buckets.size} buckets")
        }.onFailure { logger.error("Failed to load aim-learning storage", it) }
    }

    fun updateGridResolution(newResolution: Int) {
        val coerced = newResolution.coerceIn(MIN_GRID, MAX_GRID)
        if (coerced != gridResolution) {
            gridResolution = coerced
            // Existing cells are in old coordinates — clear to avoid mismatched offsets.
            buckets.clear()
            dirty = true
        }
    }

    private const val MIN_GRID = 3
    private const val MAX_GRID = 16
}
