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
package net.ccbluex.liquidbounce.deeplearn.clone

import net.ccbluex.fastutil.mapToArray
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine.modelsFolder
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.File
import kotlin.time.measureTime

/**
 * Registry of trained clones on disk.
 *
 * Models are loaded once and shared: the selector in each rotation config only names one, so
 * reloading swaps the weights everywhere at once instead of leaving stale copies behind in
 * whichever modules happened to be open.
 *
 * This is a [ValueGroup] because the script API borrows its `list` builder — see
 * `ScriptSetting`.
 */
object CloneModels : EventListener, ValueGroup("AI") {

    private val logger = clientLogger("AI/Clone")

    @Volatile
    private var loaded = emptyMap<String, CloneModel>()

    /**
     * Selectors to rebuild when the set of models changes. One exists per rotation config that
     * offers the Clone smoother, and they all outlive any reload.
     */
    private val selectors = mutableListOf<ModeValueGroup<ModelChoice>>()

    val names: List<String>
        get() = loaded.keys.sorted()

    operator fun get(name: String): CloneModel? = loaded[name]

    fun folderOf(name: String): File = modelsFolder.resolve(name)

    /** Folders that hold a model this build can actually read. */
    private fun discover(): List<File> = modelsFolder
        .listFiles { file -> CloneModel.isModelFolder(file) }
        ?.sortedBy { it.name }
        ?: emptyList()

    @Synchronized
    fun load() {
        val models = linkedMapOf<String, CloneModel>()
        for (folder in discover()) {
            runCatching {
                var model: CloneModel? = null
                val time = measureTime { model = CloneModel.load(folder) }

                val instance = model
                if (instance != null) {
                    models[folder.name] = instance
                    logger.info("Loaded clone model '${folder.name}' in ${time.inWholeMilliseconds}ms.")
                } else {
                    // The metadata was readable when discover() listed the folder but not any
                    // more, or it stopped being compatible in between — either way nothing loaded,
                    // and saying "Loaded" here is how a missing model went unexplained.
                    logger.warn("Skipped '${folder.name}' — no compatible metadata.")
                }
            }.onFailure { error ->
                logger.error("Failed to load clone model '${folder.name}'.", error)
            }
        }

        val previous = loaded
        loaded = models
        refreshSelectors()
        previous.values.forEach(CloneModel::close)
    }

    @Synchronized
    fun unload() {
        val previous = loaded
        loaded = emptyMap()
        previous.values.forEach(CloneModel::close)
    }

    fun reload() {
        load()
    }

    /**
     * Registers a selector and returns the choices it should start with. Called from the smoother's
     * constructor, which runs long before the engine has loaded anything.
     */
    internal fun register(selector: ModeValueGroup<ModelChoice>): Array<ModelChoice> {
        if (selectors.none { it === selector }) {
            selectors.add(selector)
        }
        return choicesFor(selector)
    }

    private fun choicesFor(selector: ModeValueGroup<ModelChoice>): Array<ModelChoice> {
        val names = listOf(NONE) + names
        return names.mapToArray { ModelChoice(it, selector) }
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

    /**
     * A model by name rather than by reference, so a reload does not leave a selector pointing at
     * a closed model.
     */
    class ModelChoice(
        name: String,
        override val parent: ModeValueGroup<*>
    ) : Mode(name) {
        val model: CloneModel?
            get() = if (name == NONE) null else CloneModels[name]
    }
}
