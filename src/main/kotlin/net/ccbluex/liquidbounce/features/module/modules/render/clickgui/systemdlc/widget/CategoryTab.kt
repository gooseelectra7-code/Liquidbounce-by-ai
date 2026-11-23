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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.CategoryType

/**
 * A category selector tab in the sidebar — ported from `client/gui/widget/CategoryTab.java`.
 *
 * The source binds a `Category` (icon + display name); on the fork that pairing is passed directly as
 * a [CategoryType] key plus the display [label] so this atom stays free of the module-category model
 * (a later cluster supplies real categories). Selection is EXTERNAL: a left click inside consumes and
 * returns true (source `isIntDoubleDouble` = `count == 0 && isDoubleDouble`, CategoryTab.java:16-18),
 * and the owner marks the clicked tab active via [active]; the active tab renders the fully-on fill
 * (source `check()` returns `flag4`, CategoryTab.java:21-23).
 *
 * [onSelect] is a fork convenience — an optional callback fired on the consuming click so the sidebar
 * can react without inspecting the returned boolean. It is null (inert) by default; the source has no
 * such callback.
 */
class CategoryTab(
    categoryType: CategoryType,
    label: String,
) : TabButton(categoryType, label) {

    /** True when this is the selected category (source `flag4`, `setFlag4`/`isFlag4`). */
    var active: Boolean = false

    /** Optional selection callback fired on the consuming click (fork convenience; null by default). */
    var onSelect: (() -> Unit)? = null

    override fun active(): Boolean = active

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            onSelect?.invoke()
            return true
        }
        return false
    }
}
