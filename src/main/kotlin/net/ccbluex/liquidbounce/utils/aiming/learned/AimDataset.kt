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
package net.ccbluex.liquidbounce.utils.aiming.learned

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where the learned-rotation feature keeps its files: `<config root>/learned/datasets` for the
 * recordings and `<config root>/learned/rotations` for the built libraries (the original client
 * used `<game dir>/trollxyz/{datasets,rotations}`).
 */
object LearnedFiles {

    private val NAME_FILTER = Regex("[^A-Za-z0-9_-]")

    val baseDir: File
        get() = ConfigSystem.rootFolder.resolve("learned")

    val datasetsDir: File
        get() = baseDir.resolve("datasets")

    val rotationsDir: File
        get() = baseDir.resolve("rotations")

    /** The original's `ConfigManager.sanitize`: only `A-Z a-z 0-9 _ -` survive. */
    fun sanitizeName(raw: String): String = NAME_FILTER.replace(raw, "")
}

/**
 * One dataset segment (`AiTrainer.Seg` in the original): a run of consecutive ticks in which the
 * mouse moved, as per-tick deltas plus a context flag per tick.
 *
 * v23 added the absolute head angles [my] / [mp] and the click / elytra / sneak flags; a v1
 * dataset loads without them.
 */
class AimSegment(
    /** Recording tick the segment starts on. */
    val t: Int,
    /** Yaw delta per tick, wrapped to [-180, 180]. */
    val dy: FloatArray,
    /** Pitch delta per tick. */
    val dp: FloatArray,
    /** Horizontal speed per tick (blocks per tick). */
    val sp: FloatArray,
    /** Context flags per tick, see the `FLAG_*` constants. */
    val fl: IntArray,
    /** v23: absolute yaw per tick, wrapped. */
    val my: FloatArray?,
    /** v23: absolute pitch per tick. */
    val mp: FloatArray?
) {
    val length: Int
        get() = dy.size

    /** Total path |yaw| + |pitch|. */
    fun absSum(): Double {
        var s = 0.0
        for (v in dy) {
            s += abs(v)
        }
        for (v in dp) {
            s += abs(v)
        }
        return s
    }

    fun sumYaw(): Double {
        var s = 0.0
        for (v in dy) {
            s += v
        }
        return s
    }

    fun sumPitch(): Double {
        var s = 0.0
        for (v in dp) {
            s += v
        }
        return s
    }

    companion object {
        const val FLAG_GROUND = 1
        const val FLAG_SWINGING = 2
        const val FLAG_SPRINT = 4
        const val FLAG_FALLING = 8
        /** The tick the swing animation STARTED on: a click. */
        const val FLAG_CLICK = 16
        const val FLAG_ELYTRA = 32
        const val FLAG_SNEAK = 64
    }
}

/**
 * Dataset files. The JSON layout is the original's (`version` 2 since v23):
 * ```
 * { "version": 2, "savedMs": ..., "ticks": <recorded ticks>, "pauseRate": ...,
 *   "segments": [ { "t": 12, "dy": [...], "dp": [...], "sp": [...], "fl": [...],
 *                   "my": [...], "mp": [...] }, ... ] }
 * ```
 * Deltas are rounded to three decimals on the way out, as in the original.
 */
object AimDatasets {

    private val logger = clientLogger("AI/Learned")

    /** A plain Gson: the client's own instances carry exclusion strategies this format does not want. */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    private const val DATASET_PREFIX = "dataset_"
    private const val DATASET_SUFFIX = ".json"
    private const val VERSION = 2
    private const val ROUNDING = 1000f

    /** Saves [segments] to [file]; false (and nothing written) when there is nothing to save. */
    fun save(file: File, segments: List<AimSegment>, recordedTicks: Int): Boolean {
        if (segments.isEmpty()) {
            return false
        }
        return runCatching {
            LearnedFiles.datasetsDir.mkdirs()
            val root = JsonObject()
            root.addProperty("version", VERSION)
            root.addProperty("savedMs", System.currentTimeMillis())
            root.addProperty("ticks", recordedTicks)
            val segmentTicks = segments.sumOf { it.length }
            root.addProperty(
                "pauseRate",
                if (recordedTicks > 0) {
                    max(0.0, 1.0 - segmentTicks / recordedTicks.toDouble())
                } else {
                    LearnedRotation.DEFAULT_PAUSE_RATE
                }
            )
            val array = JsonArray()
            for (segment in segments) {
                val o = JsonObject()
                o.addProperty("t", segment.t)
                o.add("dy", floatsToJson(segment.dy))
                o.add("dp", floatsToJson(segment.dp))
                o.add("sp", floatsToJson(segment.sp))
                val flags = JsonArray()
                for (v in segment.fl) {
                    flags.add(v)
                }
                o.add("fl", flags)
                val my = segment.my
                val mp = segment.mp
                if (my != null && mp != null) {
                    o.add("my", floatsToJson(my))
                    o.add("mp", floatsToJson(mp))
                }
                array.add(o)
            }
            root.add("segments", array)
            file.writeText(gson.toJson(root))
            true
        }.onFailure { error ->
            logger.error("Failed to save dataset ${file.name}", error)
        }.getOrDefault(false)
    }

    @Suppress("CognitiveComplexMethod")
    fun load(file: File): List<AimSegment> = runCatching {
        val root = gson.fromJson(file.readText(), JsonObject::class.java)
        if (root == null || !root.has("segments")) {
            return emptyList()
        }
        val out = ArrayList<AimSegment>()
        for (element in root.getAsJsonArray("segments")) {
            val o = element.asJsonObject
            val dy = jsonToFloats(o.getAsJsonArray("dy"))
            val dp = jsonToFloats(o.getAsJsonArray("dp"))
            if (dp.size != dy.size) {
                continue
            }
            val sp = if (o.has("sp")) jsonToFloats(o.getAsJsonArray("sp")) else FloatArray(dy.size)
            val fl = IntArray(dy.size)
            if (o.has("fl")) {
                val f = o.getAsJsonArray("fl")
                for (i in 0 until minOf(fl.size, f.size())) {
                    fl[i] = f[i].asInt
                }
            }
            val my = if (o.has("my")) jsonToFloats(o.getAsJsonArray("my")) else null
            val mp = if (o.has("mp")) jsonToFloats(o.getAsJsonArray("mp")) else null
            out.add(AimSegment(if (o.has("t")) o["t"].asInt else 0, dy, dp, sp, fl, my, mp))
        }
        out
    }.onFailure { error ->
        logger.error("Failed to load dataset ${file.name}", error)
    }.getOrDefault(emptyList())

    fun files(): List<File> = LearnedFiles.datasetsDir
        .listFiles { f -> f.isFile && f.name.startsWith(DATASET_PREFIX) && f.name.endsWith(DATASET_SUFFIX) }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun count(): Int = files().size

    /** The newest dataset by file name (the name carries the timestamp). */
    fun newest(): File? = files().maxByOrNull { it.name }

    fun newFile(): File = LearnedFiles.datasetsDir.resolve(
        DATASET_PREFIX + java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        ) + DATASET_SUFFIX
    )

    private fun floatsToJson(values: FloatArray): JsonArray {
        val array = JsonArray()
        for (v in values) {
            array.add((v * ROUNDING).roundToInt() / ROUNDING)
        }
        return array
    }

    private fun jsonToFloats(array: JsonArray): FloatArray = FloatArray(array.size()) { array[it].asFloat }
}
