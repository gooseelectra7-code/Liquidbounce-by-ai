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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * A marked place. Coordinates are the block a player standing there has their FEET in (the air
 * above the ground), which is what `blockPosition()` gives; the beam starts at `y`, the screen
 * marker floats at `y + 1`.
 */
class Waypoint(
    val id: UUID,
    name: String,
    var x: Int,
    var y: Int,
    var z: Int,
    var dimension: String,
    var color: Int,
    var visible: Boolean,
    val createdAt: Long,
) {
    var name: String = clampName(name)
        set(value) {
            field = clampName(value)
        }

    val centerX: Double get() = x + 0.5
    val centerZ: Double get() = z + 0.5

    fun distanceTo(px: Double, py: Double, pz: Double): Double {
        val dx = centerX - px
        val dy = y + 0.5 - py
        val dz = centerZ - pz
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        fun clampName(raw: String): String =
            raw.trim().take(WaypointStore.MAX_NAME_LENGTH).ifEmpty { "Waypoint" }
    }
}

/**
 * The waypoints of the server the client is on, persisted per server (see [MapIdentity]).
 *
 * Main-thread only. The list follows the connection by itself: every accessor first checks whether
 * the level object changed and, if the server key did too, swaps the list. Every mutation is saved
 * at once - the file is a few kilobytes and written atomically, so a crash never leaves half of it.
 */
object WaypointStore {

    const val MAX_NAME_LENGTH = 32
    private const val FILE_NAME = "waypoints.json"
    private const val FORMAT_VERSION = 1

    /**
     * The colours offered in the editor and cycled through by quick-add. Bright on purpose: every
     * one of them has to read over grass, sand, water and the dark glass of the HUD.
     */
    val PALETTE: IntArray = intArrayOf(
        0xFF4FD1FF.toInt(), // aqua
        0xFF5BF5A8.toInt(), // mint
        0xFFB6F25B.toInt(), // lime
        0xFFFFD24F.toInt(), // gold
        0xFFFF9F43.toInt(), // orange
        0xFFFF6B6B.toInt(), // coral
        0xFFFF6BD5.toInt(), // pink
        0xFFA98BFF.toInt(), // violet
        0xFF5B8CFF.toInt(), // blue
        0xFFF2F4F8.toInt(), // white
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val waypoints = ArrayList<Waypoint>()
    private var loadedKey: String? = null
    private var lastLevel: ClientLevel? = null

    /** Bumped on every change, so views can cache derived lists. */
    var revision = 0
        private set

    private var dimensionCacheKey: String? = null
    private var dimensionCacheRevision = -1
    private var dimensionCache: List<Waypoint> = emptyList()

    /**
     * Binds the list to the current server. Returns its key, or `null` when not in a world.
     */
    fun sync(): String? {
        val level = mc.level
        if (level === lastLevel) {
            return loadedKey
        }
        lastLevel = level

        val key = MapIdentity.currentServerKey()
        if (key != loadedKey) {
            load(key)
        }
        return loadedKey
    }

    val all: List<Waypoint>
        get() {
            sync()
            return waypoints
        }

    fun inDimension(dimension: String): List<Waypoint> {
        sync()
        if (dimension != dimensionCacheKey || revision != dimensionCacheRevision) {
            dimensionCache = waypoints.filter { it.dimension == dimension }
            dimensionCacheKey = dimension
            dimensionCacheRevision = revision
        }
        return dimensionCache
    }

    fun find(name: String, dimension: String? = null): Waypoint? {
        val candidates = if (dimension == null) all else inDimension(dimension)
        return candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Adds a waypoint to the current server. `null` outside a world.
     */
    @Suppress("LongParameterList")
    fun add(name: String, x: Int, y: Int, z: Int, dimension: String, color: Int = nextColor()): Waypoint? {
        sync() ?: return null

        val waypoint = Waypoint(
            id = UUID.randomUUID(),
            name = name,
            x = x,
            y = y,
            z = z,
            dimension = dimension,
            color = color or OPAQUE,
            visible = true,
            createdAt = System.currentTimeMillis(),
        )
        waypoints += waypoint
        changed()
        return waypoint
    }

    fun remove(waypoint: Waypoint): Boolean {
        sync()
        val removed = waypoints.removeIf { it.id == waypoint.id }
        if (removed) {
            changed()
        }
        return removed
    }

    /**
     * Removes every waypoint of [dimension], or of the whole server when it is `null`.
     */
    fun clear(dimension: String?): Int {
        sync()
        val before = waypoints.size
        waypoints.removeIf { dimension == null || it.dimension == dimension }
        val removed = before - waypoints.size
        if (removed > 0) {
            changed()
        }
        return removed
    }

    /**
     * Call after mutating a waypoint's fields in place.
     */
    fun changed() {
        revision++
        save()
    }

    fun nextColor(): Int = PALETTE[waypoints.size % PALETTE.size]

    /**
     * `base`, or `base 2`, `base 3`... - whichever is not taken on this server yet.
     */
    fun uniqueName(base: String = "Waypoint"): String {
        val taken = all.mapTo(HashSet()) { it.name.lowercase() }
        if (base.lowercase() !in taken) {
            return base
        }
        var index = 2
        while ("$base $index".lowercase() in taken) {
            index++
        }
        return "$base $index"
    }

    private fun load(key: String?) {
        waypoints.clear()
        loadedKey = key
        revision++
        key ?: return

        val file = File(MapIdentity.serverFolder(key), FILE_NAME)
        if (!file.isFile) {
            return
        }

        runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val array = root.getAsJsonArray("waypoints") ?: JsonArray()
            for (element in array) {
                parse(element.asJsonObject)?.let(waypoints::add)
            }
        }.onFailure {
            // The next save would rewrite the file from the (empty or partial) list; keep the original.
            val backup = File(file.parentFile, "$FILE_NAME.broken-${System.currentTimeMillis()}")
            val kept = file.renameTo(backup)
            logger.error("[Waypoints] Failed to read $file" + if (kept) ", kept as ${backup.name}" else "", it)
        }
    }

    private fun parse(json: JsonObject): Waypoint? = runCatching {
        Waypoint(
            id = json.get("id")?.asString?.let(UUID::fromString) ?: UUID.randomUUID(),
            name = json.get("name")?.asString ?: "Waypoint",
            x = json.get("x").asInt,
            y = json.get("y").asInt,
            z = json.get("z").asInt,
            dimension = json.get("dimension")?.asString ?: "minecraft:overworld",
            color = parseColor(json.get("color")?.asString) ?: PALETTE[0],
            visible = json.get("visible")?.asBoolean ?: true,
            createdAt = json.get("created")?.asLong ?: 0L,
        )
    }.getOrNull()

    private fun parseColor(raw: String?): Int? {
        val hex = raw?.removePrefix("#") ?: return null
        val value = hex.toLongOrNull(16) ?: return null
        return value.toInt() or OPAQUE
    }

    private fun save() {
        val key = loadedKey ?: return
        val folder = MapIdentity.serverFolder(key)

        val array = JsonArray()
        for (waypoint in waypoints) {
            array.add(JsonObject().apply {
                addProperty("id", waypoint.id.toString())
                addProperty("name", waypoint.name)
                addProperty("x", waypoint.x)
                addProperty("y", waypoint.y)
                addProperty("z", waypoint.z)
                addProperty("dimension", waypoint.dimension)
                addProperty("color", "#%06X".format(waypoint.color and RGB_MASK))
                addProperty("visible", waypoint.visible)
                addProperty("created", waypoint.createdAt)
            })
        }
        val root = JsonObject().apply {
            addProperty("version", FORMAT_VERSION)
            addProperty("server", MapIdentity.currentServerDisplay())
            add("waypoints", array)
        }

        runCatching {
            folder.mkdirs()
            val target = File(folder, FILE_NAME)
            val temp = File(folder, "$FILE_NAME.tmp")
            temp.writeText(gson.toJson(root))
            moveReplacing(temp, target)
        }.onFailure {
            logger.error("[Waypoints] Failed to save waypoints of $key", it)
        }
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val RGB_MASK = 0xFFFFFF
}

/**
 * Atomic where the file system allows it, a plain replace otherwise.
 */
internal fun moveReplacing(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
