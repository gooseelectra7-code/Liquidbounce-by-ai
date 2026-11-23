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

import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.AvatarSlider
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.CategoryTab
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.IconSlider
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.IconTab
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.ServerLabel
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.SliderWidget
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.CategoryType
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The category rail — ported from `client/gui/widget/SideBar.java`. It reproduces the SystemDLC sidebar
 * SHAPE: a System-logo header, a "Client categories" group ("All" + one tab per category), a "Settings"
 * group (Configs / Friends / Waypoints), the bottom Settings/Search tabs and the profile [ServerLabel].
 * The rail collapses between an expanded 224 px (labels shown) and a collapsed 48 px (icons only), driven
 * by [SystemGuiState.sidebarExpanded] through the source's expo tween (`tween2`, 0.32 s).
 *
 * ## Categories come from OUR registry, not the source's fixed list
 * The source enumerated its own `Category.values()` (SideBar.java:51-55); this port enumerates
 * [ModuleCategories.entries] and maps each [ModuleCategory] to a [CategoryType] icon via [iconFor].
 * Selecting a tab writes [SystemGuiState] (the NavBar reads it each frame to pick the active page); no
 * per-tab callback plumbing is needed beyond that.
 *
 * ## Collapse factor decode (load-bearing)
 * The source `value240` is a COLLAPSE factor, not an expand factor: `sidebarWidth =
 * getFloatByFloatFloatFloat(48, value240, 224) = lerp(224, 48, value240)` (SideBar.java:147) — `0` gives
 * 224 (expanded), `1` gives 48 (collapsed). It is fed straight into each row's `collapse`
 * (`onSliderWidgetFloat`, :359-362), matching our [SliderWidget.collapse] semantics (`0` = label shown).
 */
class SideBar(private val quickMenu: QuickMenu) : Widget() {

    // Header + section labels
    private val logo = IconSlider(CategoryType.LOGO, "System")
    private val categoriesLabel = AvatarSlider("Client categories")
    private val settingsLabel = AvatarSlider("Settings")

    // "All" + the per-category tabs (from OUR registry)
    private val categoryAll = CategoryTab(CategoryType.ALL, "All").apply {
        onSelect = { SystemGuiState.selectCategory(null); SearchState.clear() }
    }
    private val categoryTabs: List<Pair<ModuleCategory, CategoryTab>> =
        ModuleCategories.entries.map { category ->
            val tab = CategoryTab(iconFor(category), category.tag).apply {
                onSelect = { SystemGuiState.selectCategory(category); SearchState.clear() }
            }
            category to tab
        }

    // Settings group
    private val configsTab = IconTab(CategoryType.CLOUDS, "Configs") {
        SystemGuiState.section = SystemGuiState.Section.CONFIGS
        SearchState.clear()
    }
    private val friendsTab = IconTab(CategoryType.FRIENDS, "Friends") {
        SystemGuiState.section = SystemGuiState.Section.FRIENDS
        SearchState.clear()
    }
    private val waypointsTab = IconTab(CategoryType.WAYPOINT, "Waypoints") {
        SystemGuiState.section = SystemGuiState.Section.WAYPOINTS
        SearchState.clear()
    }

    // Bottom group
    private val settingsTab = IconTab(CategoryType.SETTINGS, "Settings") { toggleQuickMenu() }
    // The Search tab focuses the header search field (source SideBar opened its SearchBar modal).
    private val searchTab = IconTab(CategoryType.SEARCH, "Search") { SearchState.focus() }

    private val serverLabel = ServerLabel()

    /** Collapse factor tween: `0` = expanded (224 px), `1` = collapsed (48 px). Source `tween2`. */
    private val collapseTween: Tween = EasingPresets.expoTween(0f, COLLAPSE_SECONDS)

    private val allTabs: List<IconTab> = listOf(configsTab, friendsTab, waypointsTab, settingsTab, searchTab)

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        collapseTween.to(if (SystemGuiState.sidebarExpanded) 0f else 1f)
        val collapse = collapseTween.get()
        width = EasingPresets.lerp(EXPANDED_W, COLLAPSED_W, collapse)
        val rowWidth = EasingPresets.lerp(ROW_EXPANDED_W, ROW_COLLAPSED_W, collapse)
        val rowX = x + ROW_INSET

        // sync selection highlights
        val modules = SystemGuiState.section == SystemGuiState.Section.MODULES
        categoryAll.active = modules && SystemGuiState.selectedCategory == null
        for ((category, tab) in categoryTabs) {
            tab.active = modules && SystemGuiState.selectedCategory == category
        }
        configsTab.active = SystemGuiState.section == SystemGuiState.Section.CONFIGS
        friendsTab.active = SystemGuiState.section == SystemGuiState.Section.FRIENDS
        waypointsTab.active = SystemGuiState.section == SystemGuiState.Section.WAYPOINTS
        settingsTab.active = quickMenu.isOpen

        // profile data
        serverLabel.username = runCatching { mc.user.name }.getOrNull()
        serverLabel.serverAddress = mc.currentServer?.ip

        // --- panel + separators ---
        SystemDraw.panelPerCorner(
            ctx, x, y, width, PANEL_H,
            radiusTopLeft = PANEL_RADIUS, radiusTopRight = 0f, radiusBottomRight = 0f, radiusBottomLeft = PANEL_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.surface(), alpha),
        )
        val border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha)
        SystemDraw.panel(ctx, x, y + HEADER_H - 1f, width, 1f, radius = 0f, fill = border) // header underline
        SystemDraw.panel(ctx, x + width, y, 1f, PANEL_H, radius = 0f, fill = border)       // right divider

        // --- header logo ---
        logo.placeAt(x, y)
        logo.width = width
        logo.collapse = collapse
        logo.render(ctx, alpha, mouseX, mouseY)

        // --- top: client categories ---
        var cy = y + HEADER_H + TOP_GAP
        drawRow(ctx, categoriesLabel, rowX, cy, rowWidth, collapse, alpha, mouseX, mouseY)
        cy += LABEL_ROW_H
        drawRow(ctx, categoryAll, rowX, cy, rowWidth, collapse, alpha, mouseX, mouseY)
        cy += TAB_PITCH
        for ((_, tab) in categoryTabs) {
            drawRow(ctx, tab, rowX, cy, rowWidth, collapse, alpha, mouseX, mouseY)
            cy += TAB_PITCH
        }

        // --- settings group ---
        cy += SECTION_GAP
        drawRow(ctx, settingsLabel, rowX, cy, rowWidth, collapse, alpha, mouseX, mouseY)
        cy += LABEL_ROW_H
        for (tab in listOf(configsTab, friendsTab, waypointsTab)) {
            drawRow(ctx, tab, rowX, cy, rowWidth, collapse, alpha, mouseX, mouseY)
            cy += TAB_PITCH
        }

        // --- bottom-anchored: server label, then the Settings/Search pair above it ---
        val serverY = y + PANEL_H - SERVER_BOTTOM_INSET - TAB_HEIGHT
        drawRow(ctx, serverLabel, rowX, serverY, rowWidth, collapse, alpha, mouseX, mouseY)
        val bottomListHeight = 2f * TAB_HEIGHT + TAB_GAP
        val bottomListY = serverY - bottomListHeight - BOTTOM_LIST_GAP
        drawRow(ctx, settingsTab, rowX, bottomListY, rowWidth, collapse, alpha, mouseX, mouseY)
        drawRow(ctx, searchTab, rowX, bottomListY + TAB_PITCH, rowWidth, collapse, alpha, mouseX, mouseY)

        // keep the pop-over glued to the (possibly collapsing) Settings tab
        if (quickMenu.isOpen) {
            reAnchorQuickMenu()
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (categoryAll.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        for ((_, tab) in categoryTabs) {
            if (tab.mouseClicked(mouseX, mouseY, button)) {
                return true
            }
        }
        for (tab in allTabs) {
            if (tab.mouseClicked(mouseX, mouseY, button)) {
                return true
            }
        }
        return false
    }

    private fun toggleQuickMenu() {
        if (quickMenu.isOpen) {
            quickMenu.dismiss()
        } else {
            val (ax, ay) = quickMenuAnchor()
            quickMenu.open(ax, ay)
        }
    }

    private fun reAnchorQuickMenu() {
        val (ax, ay) = quickMenuAnchor()
        quickMenu.reAnchor(ax, ay)
    }

    /** Anchor the pop-over to the right of the Settings tab, vertically centred on it and kept on-panel. */
    private fun quickMenuAnchor(): Pair<Float, Float> {
        val anchorX = settingsTab.x + settingsTab.width + QUICK_MENU_GAP
        val centred = settingsTab.y + settingsTab.height / 2f - quickMenu.height / 2f
        val anchorY = centred.coerceIn(y + QUICK_MENU_MARGIN, y + PANEL_H - quickMenu.height - QUICK_MENU_MARGIN)
        return anchorX to anchorY
    }

    private fun drawRow(
        ctx: GuiGraphicsExtractor,
        row: SliderWidget,
        rowX: Float,
        rowY: Float,
        rowWidth: Float,
        collapse: Float,
        alpha: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        row.placeAt(rowX, rowY)
        row.width = rowWidth
        row.collapse = collapse
        row.render(ctx, alpha, mouseX, mouseY)
    }

    private companion object {
        const val PANEL_H = 700f
        const val PANEL_RADIUS = 12f
        const val HEADER_H = 49f
        const val EXPANDED_W = 224f
        const val COLLAPSED_W = 48f
        const val ROW_EXPANDED_W = 208f
        const val ROW_COLLAPSED_W = 32f
        const val ROW_INSET = 8f

        const val TAB_HEIGHT = 32f
        const val TAB_GAP = 4f
        const val TAB_PITCH = TAB_HEIGHT + TAB_GAP // 36
        const val LABEL_ROW_H = 26f
        const val TOP_GAP = 8f
        const val SECTION_GAP = 8f

        /** Server row bottom inset (source `value238 - 18 - 32`, SideBar.java:234). */
        const val SERVER_BOTTOM_INSET = 18f

        /** Gap above the server row for the bottom Settings/Search pair (source `- 15`, SideBar.java:236). */
        const val BOTTOM_LIST_GAP = 15f

        const val COLLAPSE_SECONDS = 0.32f

        const val QUICK_MENU_GAP = 6f
        const val QUICK_MENU_MARGIN = 8f

        /** OUR module categories -> SystemDLC icon keys. Combat/Movement/Player/Render map 1:1; World,
         *  Exploit, Fun and Misc have no dedicated source icon, so the nearest generic glyphs are used. */
        fun iconFor(category: ModuleCategory): CategoryType = when (category.tag) {
            "Combat" -> CategoryType.COMBAT
            "Movement" -> CategoryType.MOVEMENT
            "Player" -> CategoryType.PLAYER
            "Render" -> CategoryType.RENDER
            "World" -> CategoryType.GLOBE
            "Exploit" -> CategoryType.BOLT
            "Fun" -> CategoryType.HEART
            "Misc" -> CategoryType.CUBE
            else -> CategoryType.CUBE
        }
    }
}
