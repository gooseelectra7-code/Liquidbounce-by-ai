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
 * A sidebar action tab that runs a callback when clicked — ported from
 * `client/gui/widget/IconTab.java`. Unlike [CategoryTab] it owns its action: a left click inside
 * fires [onClick] and consumes (source `isIntDoubleDouble`, IconTab.java:14-22). Its selected state
 * ([active], source `flag4` / `check()`) is set by the owner and drives the fully-on fill.
 */
class IconTab(
    categoryType: CategoryType,
    text: String,
    /** Action fired on a left click inside (source `runnable`, IconTab.java:9/17). */
    private val onClick: () -> Unit,
) : TabButton(categoryType, text) {

    /** True when this tab is the active one (source `flag4`, `setFlag4`/`isFlag4`). */
    var active: Boolean = false

    override fun active(): Boolean = active

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            onClick()
            return true
        }
        return false
    }
}
