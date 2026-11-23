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
package net.ccbluex.liquidbounce.integration.screen.native

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.fileGson
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File

/**
 * Which stack renders the client's menu screens (title, multiplayer, singleplayer,
 * alt/proxy managers).
 */
enum class InterfaceMode {
    /** The native Hellhit-styled screens. */
    NATIVE,

    /** No replacement at all — plain vanilla screens. */
    VANILLA,
}

/**
 * Persisted interface configuration, stored in `<gameDir>/LiquidBounce/interface.json`.
 *
 * Deliberately NOT a ConfigSystem value: the title screen is routed before
 * `ConfigSystem.loadAll` has run, so a module value would not be loaded yet when it matters.
 * Same standalone-JSON pattern as
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.HellhitClickGuiSettings].
 */
object InterfaceSettings {

    private const val FILE_NAME = "interface.json"

    private val storageFile: File
        get() = File(ConfigSystem.rootFolder, FILE_NAME)

    /**
     * NATIVE by default. A file saved while the browser integration existed can still say
     * `BROWSER`; `valueOf` rejects it on load and this default stands, which is the intended
     * migration.
     */
    private var storedMode = InterfaceMode.NATIVE

    /** Selected menu background of the native title screen (1..3). */
    private var storedMenuBackground = 1

    @Volatile
    private var loaded = false

    var mode: InterfaceMode
        get() {
            ensureLoaded()
            return storedMode
        }
        set(value) {
            ensureLoaded()
            if (value != storedMode) {
                storedMode = value
                save()
            }
        }

    var menuBackground: Int
        get() {
            ensureLoaded()
            return storedMenuBackground.coerceIn(1, 3)
        }
        set(value) {
            ensureLoaded()
            val clamped = value.coerceIn(1, 3)
            if (clamped != storedMenuBackground) {
                storedMenuBackground = clamped
                save()
            }
        }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = storageFile
        if (!file.exists()) return
        runCatching {
            val raw = file.bufferedReader().use { fileGson.fromJson(it, StoredData::class.java) }
                ?: return@runCatching
            storedMode = runCatching { InterfaceMode.valueOf(raw.mode) }.getOrDefault(storedMode)
            storedMenuBackground = raw.menuBackground
        }.onFailure { logger.error("Failed to load interface settings", it) }
    }

    @Synchronized
    private fun save() {
        runCatching {
            storageFile.bufferedWriter().use {
                fileGson.toJson(StoredData(storedMode.name, storedMenuBackground), it)
            }
        }.onFailure { logger.error("Failed to save interface settings", it) }
    }

    private data class StoredData(
        val mode: String = InterfaceMode.NATIVE.name,
        val menuBackground: Int = 1,
    )
}
