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

/**
 * The thin click-to-action base every small SystemDLC ClickGUI control extends — ported from
 * `client/gui/widget/LabelWidget.java`.
 *
 * ## Scrambled-signature decode
 *
 * The source `LabelWidget.isIntDoubleDouble(count, value, value2)` (LabelWidget.java:5-7) is the
 * click entry point: `count == 0 && isDoubleDouble(value, value2) ? isDoubleDouble2(value2, value)`.
 * Decoded against [Widget]'s call-site table (`isIntDoubleDouble(button, mouseX, mouseY)`), this is:
 * "a LEFT click (button 0) landing inside the widget fires the abstract handler `isDoubleDouble2`".
 * The source passes the two coordinates SWAPPED into `isDoubleDouble2(value2, value)`; since every
 * concrete `isDoubleDouble2` in the recovered source ignores its coordinate arguments (they act on
 * internal toggle state, not the exact hit point), the swap is immaterial and [onClick] takes no
 * coordinates here.
 *
 * Subclasses implement [onClick] to perform their action and return true to consume; the abstract
 * source method `isDoubleDouble2` returns a boolean the same way (LabelWidget.java:9).
 *
 * Subclasses that need press/drag/release (ToggleButton) or a different click rule (CategoryTab,
 * IconTab) override the [Widget] input methods directly instead of using [onClick].
 */
abstract class LabelWidget : Widget() {

    /**
     * Fired on a left click (button 0) landing inside this widget (source abstract
     * `isDoubleDouble2`, LabelWidget.java:9). Return true to consume the click.
     */
    protected abstract fun onClick(): Boolean

    /**
     * Left click inside consumes and fires [onClick] (source `isIntDoubleDouble`,
     * LabelWidget.java:5-7). Other buttons / outside clicks fall through.
     */
    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        return if (button == 0 && contains(mouseX, mouseY)) {
            onClick()
        } else {
            false
        }
    }
}
