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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page

/**
 * The "favourited modules" set backing every card's [net.ccbluex.liquidbounce.features.module.modules
 * .render.clickgui.systemdlc.widget.FavoriteButton].
 *
 * The SystemDLC source persisted favourites through its own `ConfigManager` (`ContentArea.getSet` /
 * `ClientAccess.isString` / `ClientAccess.onStringBoolean`, ContentArea.java:331-334/46-47). The fork has
 * no equivalent module-favourites registry, so this cluster keeps the state IN-MEMORY only — a
 * JVM-lifetime singleton keyed by module name, exactly like [net.ccbluex.liquidbounce.features.module
 * .modules.render.clickgui.systemdlc.shell.SystemGuiState]. A later cluster can back this with `fileGson`
 * (the `LeetClickGuiSettings` pattern) without changing any caller, because every card reads and writes
 * only through this object.
 *
 * NOT VERIFIED to affect anything outside the SystemDLC ClickGUI — the fork's HUD/arraylist does not read
 * this set (there is no upstream favourites feature); it exists so the ported FavoriteButton is functional.
 */
object FavoriteStore {

    private val favourites: MutableSet<String> = HashSet()

    /** True if [moduleName] is currently favourited. */
    fun isFavourite(moduleName: String): Boolean = moduleName in favourites

    /** Set the favourite state of [moduleName]. */
    fun setFavourite(moduleName: String, favourite: Boolean) {
        if (favourite) {
            favourites += moduleName
        } else {
            favourites -= moduleName
        }
    }
}
