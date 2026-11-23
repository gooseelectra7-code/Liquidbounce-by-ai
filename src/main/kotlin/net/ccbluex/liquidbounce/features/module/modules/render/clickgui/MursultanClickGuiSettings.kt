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
 * Settings of the Mursultan ClickGUI itself, reachable through the burger button next to the
 * search field (the same place the original keeps them). Persisted to
 * `<gameDir>/LiquidBounce/clickgui-mursultan.json`.
 *
 * Native-only by design, exactly like [LeetClickGuiSettings]: no ConfigSystem values are
 * involved, so nothing here reaches the browser GUI.
 *
 * The accent defaults to the original's `#8BACFF` so the theme is coloured out of the box; the
 * white the reference captures happened to use is the second swatch.
 */
object MursultanClickGuiSettings {

    private const val FILE_NAME = "clickgui-mursultan.json"

    /**
     * Nursultan's stock accent (`class12023`: `-7623425`). The default, so the theme is visibly
     * coloured out of the box — the reference captures happened to use a white accent, and with
     * white as the default the switches read as colourless.
     */
    const val DEFAULT_ACCENT = 0xFF8BACFF.toInt()

    /** White, which is what the reference captures were taken with. */
    const val STOCK_ACCENT = 0xFFFFFFFF.toInt()

    /** Swatches offered in the settings popover, in display order. */
    val ACCENTS: List<Int> = listOf(
        DEFAULT_ACCENT,
        STOCK_ACCENT,
        0xFF4677FF.toInt(),
        0xFF8B7BFF.toInt(),
        0xFF6FE3A1.toInt(),
        0xFFFFB86B.toInt(),
        0xFFFF6B81.toInt(),
    )

    /**
     * Scale is a set of DISCRETE steps, exactly like the original's `menu-scale` list
     * (`class12023`: `scale_50` ... `scale_250`). It is deliberately not a slider: the font
     * atlases are rasterized per target pixel size, so a continuously changing scale rebuilds
     * them every frame — which is what made the slider flicker.
     */
    val SCALE_STEPS: List<Float> = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

    val SCALE_MIN = SCALE_STEPS.first()
    val SCALE_MAX = SCALE_STEPS.last()

    private var storedAccent = DEFAULT_ACCENT
    private var storedScale = 1.0f
    private var storedDescriptions = true

    private val storageFile: File
        get() = File(ConfigSystem.rootFolder, FILE_NAME)

    @Volatile
    private var loaded = false

    /** ARGB of the accent: the logo, the ON toggle and the active sidebar item. */
    var accent: Int
        get() {
            ensureLoaded()
            return storedAccent
        }
        set(value) {
            ensureLoaded()
            if (value != storedAccent) {
                storedAccent = value
                save()
            }
        }

    /**
     * User scale multiplier on top of the screen-fit scale. Always one of [SCALE_STEPS] — a
     * value from an older config is snapped to the nearest step on read.
     */
    var scale: Float
        get() {
            ensureLoaded()
            return SCALE_STEPS.minByOrNull { kotlin.math.abs(it - storedScale) } ?: 1.0f
        }
        set(value) {
            ensureLoaded()
            val step = SCALE_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: 1.0f
            if (step != storedScale) {
                storedScale = step
                save()
            }
        }

    /** Whether module descriptions are shown under the module name in the settings popup. */
    var descriptions: Boolean
        get() {
            ensureLoaded()
            return storedDescriptions
        }
        set(value) {
            ensureLoaded()
            if (value != storedDescriptions) {
                storedDescriptions = value
                save()
            }
        }

    /** Set while a slider drag is in flight so the disk write happens once, on release. */
    private var dirty = false

    /** Writes out anything the drag-driven setters left pending. */
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
            storedAccent = raw.accent
            storedScale = raw.scale
            storedDescriptions = raw.descriptions
        }.onFailure { logger.error("Failed to load Mursultan ClickGUI settings", it) }
    }

    @Synchronized
    fun save() {
        runCatching {
            storageFile.bufferedWriter().use {
                fileGson.toJson(StoredData(storedAccent, storedScale, storedDescriptions), it)
            }
        }.onFailure { logger.error("Failed to save Mursultan ClickGUI settings", it) }
    }

    private data class StoredData(
        val accent: Int = DEFAULT_ACCENT,
        val scale: Float = 1.0f,
        val descriptions: Boolean = true,
    )
}
