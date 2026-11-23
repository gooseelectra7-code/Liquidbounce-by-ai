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
import net.ccbluex.fastutil.mapToArray
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.File

/**
 * Registry of the learned rotations on disk (`<config root>/learned/rotations/<name>.json`).
 *
 * Same shape as `CloneModels`: the libraries are loaded once and shared, every `Learned` smoother
 * owns a selector that only names one, and a reload rebuilds all selectors at once so none is left
 * pointing at a stale object. Unlike the clone models there is no engine to wait for, so the first
 * selector to register triggers the scan.
 */
object LearnedRotations {

    private val logger = clientLogger("AI/Learned")

    /** A plain Gson: the client's own instances carry exclusion strategies this format does not want. */
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    @Volatile
    private var loaded: Map<String, LearnedRotation> = emptyMap()

    @Volatile
    private var scanned = false

    /** Selectors to rebuild when the set of rotations changes; one per rotation config offering Learned. */
    private val selectors = mutableListOf<ModeValueGroup<Choice>>()

    val names: List<String>
        get() {
            ensureLoaded()
            return loaded.keys.sorted()
        }

    operator fun get(name: String): LearnedRotation? {
        ensureLoaded()
        return loaded[name]
    }

    fun fileOf(name: String): File = LearnedFiles.rotationsDir.resolve("$name.json")

    private fun ensureLoaded() {
        if (!scanned) {
            load()
        }
    }

    @Synchronized
    fun load() {
        val rotations = linkedMapOf<String, LearnedRotation>()
        val files = LearnedFiles.rotationsDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()
        for (file in files) {
            val name = file.nameWithoutExtension
            runCatching {
                val parsed = gson.fromJson(file.readText(), LearnedRotation::class.java)
                    ?: error("empty file")
                rotations[name] = parsed.sanitized(name)
            }.onFailure { error ->
                logger.error("Failed to load learned rotation '$name'.", error)
            }
        }
        loaded = rotations
        scanned = true
        refreshSelectors()
    }

    /** Writes [rotation] under its own name; false (and logged) on an I/O failure. */
    fun save(rotation: LearnedRotation): Boolean {
        val name = rotation.name ?: return false
        return runCatching {
            LearnedFiles.rotationsDir.mkdirs()
            fileOf(name).writeText(gson.toJson(rotation))
            true
        }.onFailure { error ->
            logger.error("Failed to save learned rotation '$name'.", error)
        }.getOrDefault(false)
    }

    /** Deletes the file and rebuilds the selectors; false when there was no such file. */
    fun delete(name: String): Boolean {
        val file = fileOf(name)
        if (!file.isFile) {
            return false
        }
        val deleted = file.delete()
        load()
        return deleted
    }

    /**
     * Registers a selector and returns the choices it should start with. Called from the smoother's
     * constructor, which is why the scan is lazy and synchronous: the saved config is applied to the
     * selector right after, and a name that is not in the list yet would be thrown away.
     */
    internal fun register(selector: ModeValueGroup<Choice>): Array<Choice> {
        ensureLoaded()
        if (selectors.none { it === selector }) {
            selectors.add(selector)
        }
        return choicesFor(selector)
    }

    /** Points every registered selector at [name]; false when no such rotation is loaded. */
    fun select(name: String): Boolean {
        if (name != NONE && loaded[name] == null) {
            return false
        }
        for (selector in selectors) {
            runCatching { selector.setByString(name) }
                .onFailure { logger.warn("Selector '${selector.name}' has no rotation '$name'.") }
        }
        return true
    }

    private fun choicesFor(selector: ModeValueGroup<Choice>): Array<Choice> {
        val names = listOf(NONE) + names
        return names.mapToArray { Choice(it, selector) }
    }

    private fun refreshSelectors() {
        for (selector in selectors) {
            val previous = selector.activeMode.name
            selector.modes = choicesFor(selector).toMutableList()

            runCatching { selector.setByString(previous) }
                .onFailure { selector.setByString(NONE) }
        }
    }

    const val NONE = "None"

    /** A rotation by name rather than by reference, so a reload never leaves a selector on a stale object. */
    class Choice(
        name: String,
        override val parent: ModeValueGroup<*>
    ) : Mode(name) {
        val rotation: LearnedRotation?
            get() = if (name == NONE) null else LearnedRotations[name]
    }
}
