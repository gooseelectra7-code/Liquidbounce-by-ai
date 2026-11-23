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

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.level.storage.LevelResource
import java.io.File

/**
 * Where waypoints and the collected world map of the CURRENT server live.
 *
 * Everything is bound to the server address the player typed (or picked from the server list),
 * normalised so `Play.Example.net`, `play.example.net.` and `play.example.net:25565` share one
 * folder. A singleplayer world is keyed by its save folder name.
 *
 * Known limit, by construction: one address is one store. Two addresses that reach the same server
 * get two stores, and a proxy network (lobby + survival + skyblock behind one address) shares ONE
 * store per dimension id - their overworlds will draw over each other on the map.
 *
 * Layout: `<LiquidBounce>/worldmap/<serverKey>/waypoints.json` and
 * `<LiquidBounce>/worldmap/<serverKey>/map/<dimensionKey>/<rx>.<rz>.png|.hgt`.
 */
object MapIdentity {

    private const val DEFAULT_PORT_SUFFIX = ":25565"
    private const val LOCAL_PREFIX = "local_"
    private const val FALLBACK_KEY = "unknown"

    val rootFolder: File
        get() = File(ConfigSystem.rootFolder, "worldmap")

    /**
     * Key of the server the client is in, or `null` outside a world.
     */
    fun currentServerKey(): String? {
        mc.level ?: return null

        val integrated = mc.singleplayerServer
        if (integrated != null) {
            val folder = runCatching {
                integrated.getWorldPath(LevelResource.ROOT).normalize().fileName?.toString()
            }.getOrNull()
            val name = folder?.takeIf { it.isNotBlank() } ?: integrated.worldData.levelName
            return LOCAL_PREFIX + sanitize(name)
        }

        val address = mc.currentServer?.ip ?: return null
        return normalizeAddress(address)
    }

    /**
     * Human-readable name of the current server for UI headers.
     */
    fun currentServerDisplay(): String {
        val integrated = mc.singleplayerServer
        if (integrated != null) {
            return "Singleplayer: " + integrated.worldData.levelName
        }
        return mc.currentServer?.ip?.trim()?.removeSuffix(DEFAULT_PORT_SUFFIX) ?: "-"
    }

    /**
     * Dimension id of the current level (`minecraft:overworld`), or `null` outside a world.
     */
    fun currentDimension(): String? = mc.level?.dimension()?.identifier()?.toString()

    fun dimensionFolderName(dimension: String): String = sanitize(dimension)

    fun serverFolder(serverKey: String): File = File(rootFolder, serverKey)

    fun displayDimension(dimension: String): String = when (dimension) {
        "minecraft:overworld" -> "Overworld"
        "minecraft:the_nether" -> "Nether"
        "minecraft:the_end" -> "End"
        else -> dimension.substringAfter(':').replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Folds the spellings of one address into one key: case, a trailing dot of a fully qualified
     * name, and the default port.
     */
    fun normalizeAddress(address: String): String {
        var key = address.trim().lowercase()
        if (key.endsWith(DEFAULT_PORT_SUFFIX)) {
            key = key.removeSuffix(DEFAULT_PORT_SUFFIX)
        }
        key = key.trimEnd('.')
        return sanitize(key)
    }

    /**
     * File-system safe: ASCII letters, digits, `.`, `-`, `_`; anything else becomes `_`. A name
     * made only of dots (`..`) could climb out of the root and is replaced.
     */
    fun sanitize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (char in raw) {
            val safe = (char in 'a'..'z') || (char in 'A'..'Z') || (char in '0'..'9') ||
                char == '.' || char == '-' || char == '_'
            builder.append(if (safe) char else '_')
        }
        val key = builder.toString().trimEnd('.')
        return if (key.isEmpty() || key.all { it == '.' }) FALLBACK_KEY else key
    }
}
