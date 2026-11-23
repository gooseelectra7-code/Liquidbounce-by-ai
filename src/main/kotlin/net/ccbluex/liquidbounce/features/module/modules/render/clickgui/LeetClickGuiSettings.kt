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
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File

/**
 * Settings of the NATIVE (Leet) ClickGUI itself, shown in the virtual "GUI" category at the
 * bottom of the category rail. Persisted to `<gameDir>/LiquidBounce/clickgui-native.json`.
 *
 * Native-only by design: no ConfigSystem values are involved, so nothing here is visible to
 * (or serializable by) the browser GUI. Extensible - future native-GUI settings join
 * [StoredData] as fields and get an accessor here, following [styleFor]/[setStyle].
 *
 * Loaded lazily on first access ([ensureLoaded]); [setStyle] saves immediately (changes are
 * rare, user-driven clicks - no debounce needed).
 */
object LeetClickGuiSettings {

    private const val FILE_NAME = "clickgui-native.json"
    const val DEFAULT_ICON_STYLE = "bold"

    /** Solar icon style ids (kebab-case, matching the asset file names) in display order. */
    val ICON_STYLES: List<Pair<String, String>> = listOf(
        "bold" to "Bold",
        "linear" to "Linear",
        "outline" to "Outline",
        "line-duotone" to "Line Duotone",
        "bold-duotone" to "Bold Duotone",
        "broken" to "Broken",
    )

    /** Camera distance of the visuals preview stage, in blocks. */
    const val DEFAULT_PREVIEW_DISTANCE = 3.4f
    const val PREVIEW_DISTANCE_MIN = 1.8f
    const val PREVIEW_DISTANCE_MAX = 9.0f

    private val storageFile: File
        get() = File(ConfigSystem.rootFolder, FILE_NAME)

    /** Category tag (lowercase; "gui" for the virtual category) -> icon style id. */
    val iconStyles = mutableMapOf<String, String>()

    private var storedPreviewDistance = DEFAULT_PREVIEW_DISTANCE

    private var storedPreviewEnabled = true

    /**
     * Whether the visuals preview window is shown beside the Render category. Toggled by a
     * click, so unlike [previewDistance] this one persists immediately.
     */
    var previewEnabled: Boolean
        get() {
            ensureLoaded()
            return storedPreviewEnabled
        }
        set(value) {
            ensureLoaded()
            if (value != storedPreviewEnabled) {
                storedPreviewEnabled = value
                save()
            }
        }

    /** Set while a slider drag is in flight, so the disk write happens once, on release. */
    private var dirty = false

    @Volatile
    private var loaded = false

    /**
     * Camera distance of the visuals preview.
     *
     * The setter does NOT hit disk: it is driven by a slider drag and would otherwise write
     * every frame. It marks the value dirty instead; [flushDirty] persists it when the drag
     * ends.
     */
    var previewDistance: Float
        get() {
            ensureLoaded()
            return storedPreviewDistance.coerceIn(PREVIEW_DISTANCE_MIN, PREVIEW_DISTANCE_MAX)
        }
        set(value) {
            ensureLoaded()
            val clamped = value.coerceIn(PREVIEW_DISTANCE_MIN, PREVIEW_DISTANCE_MAX)
            if (clamped != storedPreviewDistance) {
                storedPreviewDistance = clamped
                dirty = true
            }
        }

    /** Writes out anything the drag-driven setters left pending. */
    fun flushDirty() {
        if (!dirty) return
        dirty = false
        save()
    }

    /** The icon style chosen for [tag] (any case), validated against [ICON_STYLES]. */
    fun styleFor(tag: String): String {
        ensureLoaded()
        val style = iconStyles[tag.lowercase()] ?: DEFAULT_ICON_STYLE
        return if (ICON_STYLES.any { it.first == style }) style else DEFAULT_ICON_STYLE
    }

    /** Persists [style] for [tag] (any case); a no-op when nothing changes. */
    fun setStyle(tag: String, style: String) {
        ensureLoaded()
        val key = tag.lowercase()
        if (iconStyles[key] == style) return
        iconStyles[key] = style
        save()
    }

    fun displayName(styleId: String): String =
        ICON_STYLES.firstOrNull { it.first == styleId }?.second ?: styleId

    fun styleId(displayName: String): String =
        ICON_STYLES.firstOrNull { it.second == displayName }?.first ?: DEFAULT_ICON_STYLE

    /** Idempotent lazy load: first call hits disk, the rest no-op. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = storageFile
        if (!file.exists()) return
        runCatching {
            val raw = file.bufferedReader().use { fileGson.fromJson(it, StoredData::class.java) }
                ?: return@runCatching
            iconStyles.clear()
            iconStyles.putAll(raw.iconStyles)
            storedPreviewDistance = raw.previewDistance
            storedPreviewEnabled = raw.previewEnabled
        }.onFailure { logger.error("Failed to load native ClickGUI settings", it) }
    }

    @Synchronized
    fun save() {
        runCatching {
            storageFile.bufferedWriter().use {
                fileGson.toJson(StoredData(iconStyles, storedPreviewDistance, storedPreviewEnabled), it)
            }
        }.onFailure { logger.error("Failed to save native ClickGUI settings", it) }
    }

    /** JSON payload; future native-GUI settings are added as fields here. */
    private data class StoredData(
        val iconStyles: MutableMap<String, String> = mutableMapOf(),
        val previewDistance: Float = DEFAULT_PREVIEW_DISTANCE,
        val previewEnabled: Boolean = true,
    )
}
