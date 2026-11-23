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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.fileGson
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File

/**
 * Settings of the Hellhit native ClickGUI, persisted to
 * `<gameDir>/LiquidBounce/clickgui-hellhit.json`.
 *
 * Same design as [LeetClickGuiSettings]: native-only, no ConfigSystem values involved, so
 * nothing here is visible to (or serializable by) the browser GUI. Stores the dragged panel
 * positions and the selected accent theme (the "Themes" panel of the GUI).
 *
 * Panel positions change during a drag, every frame — the setter only marks the data dirty
 * and [flushDirty] (called on mouse release) performs the single disk write.
 */
object HellhitClickGuiSettings {

    private const val FILE_NAME = "clickgui-hellhit.json"
    const val DEFAULT_THEME = "client"

    /**
     * The accent palette of the Themes panel, in display order. Keys are stored in the
     * JSON; display names are shown on the rows.
     *
     * "Client" is the white default — the achromatic look of the reference design.
     */
    val THEMES: List<Theme> = listOf(
        Theme("rose", "Rose", Color4b(244, 63, 94, 255)),
        Theme("amber", "Amber", Color4b(245, 158, 11, 255)),
        Theme("gold", "Gold", Color4b(234, 179, 8, 255)),
        Theme("emerald", "Emerald", Color4b(16, 185, 129, 255)),
        Theme("lime", "Lime", Color4b(132, 204, 22, 255)),
        Theme("malachite", "Malachite", Color4b(20, 164, 77, 255)),
        Theme("cyan", "Cyan", Color4b(6, 182, 212, 255)),
        Theme("sapphire", "Sapphire", Color4b(15, 108, 186, 255)),
        Theme("azure", "Azure", Color4b(59, 130, 246, 255)),
        Theme("midnight", "Midnight", Color4b(99, 102, 241, 255)),
        Theme("amethyst", "Amethyst", Color4b(153, 102, 204, 255)),
        Theme("client", "Client", Color4b(242, 242, 244, 255)),
    )

    data class Theme(val key: String, val label: String, val accent: Color4b)

    private val storageFile: File
        get() = File(ConfigSystem.rootFolder, FILE_NAME)

    private var storedTheme = DEFAULT_THEME

    /** Panel key -> (x, y) in gui units. Only panels the user actually dragged appear here. */
    private val storedPanels = mutableMapOf<String, FloatArray>()

    private var dirty = false

    @Volatile
    private var loaded = false

    var themeKey: String
        get() {
            ensureLoaded()
            return if (THEMES.any { it.key == storedTheme }) storedTheme else DEFAULT_THEME
        }
        set(value) {
            ensureLoaded()
            if (value != storedTheme && THEMES.any { it.key == value }) {
                storedTheme = value
                save()
            }
        }

    val accent: Color4b
        get() = THEMES.first { it.key == themeKey }.accent

    fun panelPosition(key: String): FloatArray? {
        ensureLoaded()
        return storedPanels[key]
    }

    /** Drag-driven: marks dirty, no disk write — [flushDirty] persists on release. */
    fun setPanelPosition(key: String, x: Float, y: Float) {
        ensureLoaded()
        val cur = storedPanels[key]
        if (cur != null && cur[0] == x && cur[1] == y) return
        storedPanels[key] = floatArrayOf(x, y)
        dirty = true
    }

    fun flushDirty() {
        if (!dirty) return
        dirty = false
        save()
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
            storedTheme = raw.theme
            storedPanels.clear()
            storedPanels.putAll(raw.panels)
        }.onFailure { logger.error("Failed to load Hellhit ClickGUI settings", it) }
    }

    @Synchronized
    fun save() {
        runCatching {
            storageFile.bufferedWriter().use {
                fileGson.toJson(StoredData(storedTheme, storedPanels), it)
            }
        }.onFailure { logger.error("Failed to save Hellhit ClickGUI settings", it) }
    }

    private data class StoredData(
        val theme: String = DEFAULT_THEME,
        val panels: MutableMap<String, FloatArray> = mutableMapOf(),
    )
}
