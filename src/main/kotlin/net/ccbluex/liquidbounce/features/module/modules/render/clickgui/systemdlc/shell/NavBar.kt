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

import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The content column — ported from `client/gui/widget/NavBar.java`. It stacks the [TopBar] over one
 * active [Page], routing to the page selected by [SystemGuiState] (the source's `guiTab` switch, NavBar.
 * java:302-315): the modules grid, or the Configs / Friends / Waypoints placeholder pages.
 *
 * Each frame it retargets the [ModulesPage] to the selected category's module list, sets the TopBar
 * title from the active page, and — on a page change — replays the source's content intro (a fade plus a
 * 10 px rise, `(1 - f) * 10`, NavBar.java:126). The content sits below the bar at `y + 49` (bar height
 * 48 + a 1 px border, `getFloat`/`getFloat2`, :79/:134) and is drawn under a scissor.
 */
class NavBar : Widget() {

    private val topBar = TopBar()
    private val modulesPage = ModulesPage()
    private val configsPage = PlaceholderPage("Configs", SystemIcon.CLOUDS)
    private val friendsPage = PlaceholderPage("Friends", SystemIcon.FRIENDS)
    private val waypointsPage = PlaceholderPage("Waypoints", SystemIcon.WAYPOINT)

    /** Key of the page shown last frame, to fire [Page.onShown] + the intro only on a real change. */
    private var shownKey: String? = null
    private var introStartMs: Long = 0L

    /** A non-blank search query overrides the section and shows the filtered module grid. */
    private fun activePage(): Page = when {
        SearchState.isFiltering -> modulesPage
        SystemGuiState.section == SystemGuiState.Section.CONFIGS -> configsPage
        SystemGuiState.section == SystemGuiState.Section.FRIENDS -> friendsPage
        SystemGuiState.section == SystemGuiState.Section.WAYPOINTS -> waypointsPage
        else -> modulesPage
    }

    /**
     * The key that fires [Page.onShown] + the content intro on a real page change. A stable "search" key
     * while filtering means the intro plays ONCE on entering search, not on every keystroke (the per-query
     * grid REBUILD is keyed separately, via the content key handed to [ModulesPage.show]).
     */
    private fun pageKey(): String = when {
        SearchState.isFiltering -> "search"
        SystemGuiState.section == SystemGuiState.Section.MODULES ->
            "modules:" + (SystemGuiState.selectedCategory?.tag ?: "all")
        else -> SystemGuiState.section.name
    }

    /** The per-query content key that drives the content grid to rebuild its cards as the query changes. */
    private fun searchContentKey(): String = "search:" + SearchState.query.trim().lowercase()

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        // retarget the modules page: search results across all categories, else the selected category
        if (SearchState.isFiltering) {
            modulesPage.show("Search", searchContentKey(), SearchState.filtered())
        } else if (SystemGuiState.section == SystemGuiState.Section.MODULES) {
            val category = SystemGuiState.selectedCategory
            if (category == null) {
                modulesPage.show("All", "all", ModuleManager.toList())
            } else {
                modulesPage.show(category.tag, category.tag, ModuleManager.filter { it.category == category })
            }
        }

        val page = activePage()
        val key = pageKey()
        if (key != shownKey) {
            shownKey = key
            page.onShown()
            introStartMs = System.currentTimeMillis()
        }

        // top bar
        topBar.setTitle(page.title)
        topBar.placeAt(x, y)
        topBar.width = width
        topBar.render(ctx, alpha, mouseX, mouseY)

        // content area below the bar
        val contentTop = y + CONTENT_TOP_OFFSET
        val contentHeight = height - CONTENT_TOP_OFFSET

        val introT = ((System.currentTimeMillis() - introStartMs) / INTRO_MS).coerceIn(0f, 1f)
        val introE = easeInOutCubic(introT)
        val introRise = (1f - introE) * INTRO_RISE

        SystemDraw.scissor(ctx, x, contentTop, width, contentHeight) {
            // draw with the intro rise applied to the content rect
            page.bounds(x, contentTop + introRise, width, contentHeight)
            page.render(ctx, mouseX, mouseY, alpha * introE)
        }
        // restore the true rect for input hit-testing (input runs between frames)
        page.bounds(x, contentTop, width, contentHeight)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (topBar.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        return activePage().mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean =
        activePage().mouseReleased(mouseX, mouseY, button)

    override fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean = activePage().mouseDragged(mouseX, mouseY, button, deltaX, deltaY)

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean =
        activePage().mouseScrolled(mouseX, mouseY, amount)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        activePage().keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean =
        activePage().keyReleased(keyCode, scanCode, modifiers)

    override fun charTyped(char: Char, modifiers: Int): Boolean =
        activePage().charTyped(char, modifiers)

    private companion object {
        /** Content top = bar height 48 + 1 px border (source `getFloat` = `value236 + 48 + 1`). */
        const val CONTENT_TOP_OFFSET = 49f
        const val INTRO_MS = 180f
        const val INTRO_RISE = 10f

        fun easeInOutCubic(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return if (x < 0.5f) {
                4f * x * x * x
            } else {
                val f = -2f * x + 2f
                1f - f * f * f / 2f
            }
        }
    }
}
