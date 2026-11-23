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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell

import net.ccbluex.liquidbounce.features.module.ModuleCategory

/**
 * The SystemDLC ClickGUI's persisted UI state — ported (in role) from `client/data/GuiState.java`.
 *
 * ## Persistence model (documented, per the C6 task)
 * The source persisted this through the client's `ConfigManager`/`ConfigData` (a disk-backed blob the
 * screen wrote on close, `ClickGuiScreen.close`/`getConfigData`). This port keeps the state
 * **IN-MEMORY only** — a JVM-lifetime singleton, exactly like [LeetClickGuiScreen]'s companion object:
 * the selected tab, sidebar collapse and per-page scroll survive closing and reopening the GUI within
 * a session, but are not written to disk. A later cluster may back this with `fileGson`
 * (the `LeetClickGuiSettings` pattern) if durable persistence is wanted; nothing else needs to change,
 * because every screen part reads and writes only through this object.
 *
 * The theme (dark/light) is intentionally NOT stored here — it lives in
 * [net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme] which already owns
 * the cross-fade and is shared with the ported HUD plates.
 */
object SystemGuiState {

    /** The four top-level panes the NavBar can host (source `client/enums/GuiTab`, minus FAVORITES). */
    enum class Section { MODULES, CONFIGS, FRIENDS, WAYPOINTS }

    /** Which pane the NavBar shows. */
    @JvmField
    var section: Section = Section.MODULES

    /**
     * The selected module category while [section] == [Section.MODULES]; `null` is the "All" tab
     * (every registered module). Ignored for the other sections.
     */
    @JvmField
    var selectedCategory: ModuleCategory? = null

    /** Sidebar expanded (labels shown, ~224 px) vs collapsed to icons only (~48 px). Default expanded. */
    @JvmField
    var sidebarExpanded: Boolean = true

    /** "Collapse all cards" toggle owned by the TopBar (consumed by the modules content in C7). */
    @JvmField
    var collapseAllCards: Boolean = false

    /** Window drag offset from centre, in GUI units (source `value239`/`value240`). */
    @JvmField
    var panX: Float = 0f

    @JvmField
    var panY: Float = 0f

    /** Per-page vertical scroll offset in GUI units, keyed by a stable page key. */
    @JvmField
    val scrollByKey: HashMap<String, Float> = HashMap()

    /** Selects a module category (or `null` for "All") and switches to the modules pane. */
    fun selectCategory(category: ModuleCategory?) {
        section = Section.MODULES
        selectedCategory = category
    }
}
