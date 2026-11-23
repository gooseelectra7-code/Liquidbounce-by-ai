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

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.ContentArea
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The module-list content pane — the SystemDLC `client/gui/widget/ContentArea.java` role.
 *
 * ## C7: the real card grid
 * This page hosts a [ContentArea] — the 3-column [net.ccbluex.liquidbounce.features.module.modules.render
 * .clickgui.systemdlc.shell.page.ModuleRow] masonry with working toggles, keybind capture and the simple
 * setting rows. The [Page] contract and the [show] retarget entry point are unchanged from the C6 stub;
 * only the body is real now. Coordinates from the host ([NavBar]) are handed to the [ContentArea] via
 * [ContentArea.place] before every draw AND before every input dispatch, so hit rects stay consistent
 * with the rect actually used (the host restores the un-risen rect for input — see [NavBar]).
 */
class ModulesPage : Page() {

    /** Retitled per category by the host; the TopBar reads it. "All" for the null-category tab. */
    override var title: String = ALL_TITLE

    /** The modules to display, set by the host via [show]. */
    var modules: List<ClientModule> = emptyList()
        private set

    /** Stable scroll key for this view (category tag or "all"); scroll persists under it. */
    var key: String = "all"
        private set

    private val contentArea = ContentArea()

    /**
     * Host call: retarget this page to [title] / [modules] with a stable scroll [key]. The [ContentArea]
     * rebuilds its cards (and re-seeds scroll) only when [key] actually changes.
     */
    fun show(title: String, key: String, modules: List<ClientModule>) {
        this.title = title
        this.key = key
        this.modules = modules
    }

    override fun onShown() {
        contentArea.onShown()
    }

    override fun render(ctx: GuiGraphicsExtractor, mouseX: Float, mouseY: Float, alpha: Float) {
        prepare()
        contentArea.render(ctx, alpha, mouseX, mouseY)
    }

    /** Sync the content area to the current bounds + module list (called before draw and input). */
    private fun prepare() {
        contentArea.place(contentX, contentY, contentWidth, contentHeight)
        contentArea.setContent(modules, key)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        prepare()
        return contentArea.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        prepare()
        return contentArea.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        prepare()
        return contentArea.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean {
        prepare()
        return contentArea.mouseScrolled(mouseX, mouseY, amount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        prepare()
        return contentArea.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean {
        prepare()
        return contentArea.charTyped(char, modifiers)
    }

    private companion object {
        const val ALL_TITLE = "All"
    }
}
