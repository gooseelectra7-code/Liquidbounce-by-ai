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
package net.ccbluex.liquidbounce.config.types.group

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.gson.stategies.Exclude
import net.ccbluex.liquidbounce.config.types.ValueType

/**
 * A **mutable list of value groups**: entries can be added and removed at runtime, both from
 * code and from the theme (the `GROUP_LIST` setting UI ships add/remove buttons).
 *
 * Serialization is **positional**: on (de)serialization the entry list is rebuilt to match
 * the incoming JSON array — entry names are derived from the index by the [factory], so
 * add/remove operations from the theme survive a round trip.
 */
class MutableGroupListValueGroup<T : ValueGroup>(
    name: String,
    initialCount: Int = 1,
    @Exclude private val factory: (index: Int) -> T,
) : ValueGroup(name, valueType = ValueType.GROUP_LIST) {

    private val groups = ArrayList<T>()

    /** Current entries, in order. */
    val entries: List<T> get() = groups

    init {
        repeat(initialCount.coerceAtLeast(1)) {
            addEntry()
        }
    }

    /** Appends a fresh entry built by the [factory]. */
    fun addEntry(): T {
        val entry = factory(groups.size + 1)
        groups += entry
        tree(entry)
        return entry
    }

    private fun clearEntries() {
        for (group in groups) {
            drop(group)
        }
        groups.clear()
    }

    /**
     * Rebuilds the entries to match the JSON array and deserializes each entry positionally
     * (child values are still matched by name *inside* every entry). Invoked from
     * [ConfigSystem.deserializeValue] instead of the regular by-name group walk.
     */
    fun deserializePositional(jsonObject: JsonObject) {
        val children = jsonObject.getAsJsonArray("value") ?: return

        clearEntries()
        for (childElement in children) {
            val entry = addEntry()
            val childObject = childElement.asJsonObject
            val valuesByName = childObject.getAsJsonArray("value")
                .map { it.asJsonObject }
                .associateBy { it["name"].asString!! }

            for (value in entry.inner) {
                val element = valuesByName[value.name] ?: continue
                ConfigSystem.deserializeValue(value, element)
            }
        }

        // The theme clones an existing entry to add new ones — never run dry
        if (groups.isEmpty()) {
            addEntry()
        }
    }
}
