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

import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.IconButton
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.TabPainter
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.CategoryType
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The content-column header — ported from `client/gui/widget/TopBar.java`. Left: a sidebar-collapse
 * icon, a divider and the current page title (a [TabPainter] morph, source `tween4` on `setString`).
 * Right (right-to-left, per source `onFloatMatrix4fFloatFloat`, :200-223): collapse/expand-all, the
 * theme moon/sun, language, and the favorites star — 14 px icons on a 7 px pitch with 1 px dividers.
 *
 * ## Geometry (TopBar.java, height 49 from the ctor line 42)
 *  - Left icon at `x + 18 - 1 = x + 17`, `y + 17`, size 14 (:149-152).
 *  - Divider at `leftIcon + 28 + 7.5`, `y + 15.5`, 1x17 (:153-159); title at `leftIcon + 28 + 24`,
 *    `y + 17`, size 14 (:160-178).
 *  - Right icons anchored at `x + width - 25`, each 14 wide stepping left by 7, with a 1 px `y + 15.5`
 *    17-tall divider between them (:204-222). Bottom border at `y + 48` (:179-187).
 *
 * ## Actions
 *  - Sidebar toggle -> [SystemGuiState.sidebarExpanded]. Collapse-all -> [SystemGuiState.collapseAllCards]
 *    (consumed by the C7 content grid; the icon swaps collapse<->expand). Theme -> [SystemDlcTheme.dark]
 *    (icon shows the moon in dark mode's opposite, mirroring source `getThemePalette()==INSTANCE2 ? SUN
 *    : MOON`, :203). Language / favorites are documented STUBS (the source drove `Translations` and a
 *    favorites view that this shell does not host yet).
 */
class TopBar : Widget() {

    private val panelToggle = IconButton(CategoryType.PANEL, ICON_SIZE).apply {
        tooltip = "Sidebar"
        onClickAction = { SystemGuiState.sidebarExpanded = !SystemGuiState.sidebarExpanded }
    }
    private val collapseAll = IconButton(CategoryType.COLLAPSE_ALL, ICON_SIZE).apply {
        tooltip = "Collapse all"
        onClickAction = { SystemGuiState.collapseAllCards = !SystemGuiState.collapseAllCards }
    }
    private val themeToggle = IconButton(CategoryType.MOON, ICON_SIZE).apply {
        tooltip = "Change theme"
        onClickAction = { SystemDlcTheme.dark = !SystemDlcTheme.dark }
    }
    private val language = IconButton(CategoryType.LANGUAGE, ICON_SIZE).apply {
        tooltip = "Language"
        // STUB: the source cycled `Translations`; the shell has no localization wired yet.
        onClickAction = {}
    }
    private val favorites = IconButton(CategoryType.STAR, ICON_SIZE).apply {
        altIcon = CategoryType.STAR_FILLED
        tooltip = "Favorites"
        // STUB: the source opened a favorites view; the shell does not host it yet.
        onClickAction = {}
    }

    /** Right-hand action icons in draw order (rightmost first). */
    private val rightIcons = listOf(collapseAll, themeToggle, language, favorites)

    private val titlePainter = TabPainter()

    init {
        height = TOPBAR_H
    }

    /** Set the title shown for the active page (morphs from the previous one, source `setString`). */
    fun setTitle(title: String) {
        titlePainter.setString(title)
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        // dynamic icon states
        themeToggle.icon = if (SystemDlcTheme.dark) CategoryType.SUN else CategoryType.MOON
        collapseAll.icon = if (SystemGuiState.collapseAllCards) CategoryType.EXPAND_ALL else CategoryType.COLLAPSE_ALL

        val border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha)
        val iconY = y + ICON_TOP

        // left: sidebar-collapse icon
        val leftX = x + LEFT_INSET
        panelToggle.placeAt(leftX, iconY)
        panelToggle.render(ctx, alpha, mouseX, mouseY)

        // divider after the left icon, then the title
        val afterLeft = leftX + ICON_SIZE + ICON_SIZE
        SystemDraw.panel(ctx, afterLeft + DIVIDER_NUDGE, y + DIVIDER_TOP, 1f, DIVIDER_H, radius = 0f, fill = border)
        titlePainter.render(
            ctx, afterLeft + TITLE_GAP, y + ICON_TOP, TITLE_SIZE,
            SystemDlcTheme.foreground(), alpha,
        )

        // right: action icons, laid out from the right edge leftward
        var cursor = x + width - RIGHT_MARGIN
        for ((i, button) in rightIcons.withIndex()) {
            val iconLeft = cursor - ICON_SIZE
            button.placeAt(iconLeft, iconY)
            button.render(ctx, alpha, mouseX, mouseY)
            cursor = iconLeft - ICON_GAP
            if (i < rightIcons.size - 1) {
                val dividerX = cursor - 1f
                SystemDraw.panel(ctx, dividerX, y + DIVIDER_TOP, 1f, DIVIDER_H, radius = 0f, fill = border)
                cursor = dividerX - ICON_GAP
            }
        }

        // search field: left of the right-icon cluster (the source drew a SearchBar near the top; this
        // integrated port puts a compact filter field in the header, like the fork's Leet GUI).
        val searchRight = cursor - SEARCH_GAP
        val searchLeft = searchRight - SEARCH_W
        SearchState.draw(ctx, searchLeft, y + SEARCH_TOP, SEARCH_W, SEARCH_H, alpha)

        // bottom border between the bar and the content
        SystemDraw.panel(ctx, x, y + BOTTOM_BORDER_Y, width, 1f, radius = 0f, fill = border)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (panelToggle.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        for (icon in rightIcons) {
            if (icon.mouseClicked(mouseX, mouseY, button)) {
                return true
            }
        }
        // a left click on the header search field focuses it
        if (button == 0 && SearchState.inField(mouseX, mouseY)) {
            SearchState.focus()
            return true
        }
        return false
    }

    private companion object {
        const val TOPBAR_H = 49f
        const val ICON_SIZE = 14f
        const val ICON_GAP = 7f

        /** Left icon inset `18 - 1` (source `value235 + 18 - 1`, TopBar.java:149). */
        const val LEFT_INSET = 17f

        /** Icon top offset (source `value236 + 17`, TopBar.java:151). */
        const val ICON_TOP = 17f

        /** Gap between the left divider and the title (source `+ 16 + 8` from `afterLeft`, minus the 7.5
         *  divider nudge is folded into [DIVIDER_NUDGE]). */
        const val TITLE_GAP = 24f

        /** Divider x nudge past the left icon (source `f2 + 7.5`, TopBar.java:157-158). */
        const val DIVIDER_NUDGE = 7.5f
        const val DIVIDER_TOP = 15.5f
        const val DIVIDER_H = 17f

        const val TITLE_SIZE = 14f

        /** Right-edge inset of the first action icon (source `value235 + value237 - 25`, TopBar.java:206). */
        const val RIGHT_MARGIN = 25f

        /** Bottom border y (source `value236 + 48`, TopBar.java:180). */
        const val BOTTOM_BORDER_Y = 48f

        // Header search field: a 200x20 pill vertically centred in the 49 px bar, sitting left of the
        // right-icon cluster with a 12 px gap.
        const val SEARCH_W = 200f
        const val SEARCH_H = 20f
        const val SEARCH_TOP = 14f
        const val SEARCH_GAP = 12f
    }
}
