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

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row.TextRow
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW

/**
 * The SystemDLC ClickGUI's shared search field state — the home for the query, caret and focus that the
 * header field ([TopBar]), the sidebar Search tab ([SideBar]) and the module grid ([NavBar] ->
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.ContentArea])
 * all read.
 *
 * ## Why a header field, not the source's modal SearchBar
 * The source `client/gui/widget/SearchBar.java` is a full **centred modal** (448x304, `update13`
 * :138-144) that lists categories + suggestions + results and, on pick, NAVIGATES to the module's
 * category (`onCategoryModule3`, :243-252). This port instead follows the fork's proven native GUI
 * ([net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen]): a small
 * always-visible field in the header that FILTERS the module grid in place (`visibleModules`,
 * LeetClickGuiScreen.kt:837-846). The caret / blink / key handling mirror the fork's [TextRow] field.
 *
 * ## Filtering + ranking (cited)
 * Leet's `visibleModules()` filters by `it.name.contains(q, ignoreCase = true)` with **no ranking** — it
 * shows matches in registration order (LeetClickGuiScreen.kt:842). The C7 task asks for prefix matches
 * first, so [filtered] keeps the same `contains` match (widened to include [ClientModule.aliases], the
 * source's `isStringModule` matched names via `contains` too, SearchBar.java:231-233/305-307) and then
 * sorts prefix-of-name matches ahead of mid-string matches, alphabetically within each group. Empty
 * query -> empty list (the grid falls back to the selected category; see [NavBar]).
 *
 * ## Ctrl+1..9 quick-pick
 * Source `ClickGuiScreen.keyPressed` (:509-514) routes `Ctrl`+digit to `searchBar.isInt(n)`, which runs
 * the n-th result's runnable — a NAVIGATE-and-close in the source. In this integrated grid the n-th
 * visible result IS a module card, so [quickPick] TOGGLES that module (the C7 task's stated behaviour),
 * which is the useful action for an in-grid result. Requires the field to be focused with live results.
 *
 * ## Focus is mutually exclusive with a setting text field
 * There is one keyboard owner. [focus] commits/blurs any active [TextRow]; the host
 * ([net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.SystemClickGuiScreen])
 * blurs the search on a click outside the field and routes keys to the page (its focused [TextRow])
 * before the search, so the two never both consume a key.
 */
object SearchState {

    /** The live query; typing edits it directly (it is also the filter key, so results update per char). */
    var query: String = ""
        private set

    /** Whether the search field currently owns the keyboard. Drives `ModuleClickGui.isInSearchBar`. */
    var focused: Boolean = false
        private set

    /** Insertion caret index into [query], `0..query.length`. */
    private var caret: Int = 0

    /** Horizontal text scroll (px) keeping the caret visible for long queries (mirrors TextRow). */
    private var scrollPx: Float = 0f

    // Field rect published by [draw] each frame (virtual GUI coords) for hit-testing in the host.
    private var fieldX = 0f
    private var fieldY = 0f
    private var fieldW = 0f
    private var fieldH = 0f

    /** True when a non-blank query is active — the grid then shows [filtered] instead of a category. */
    val isFiltering: Boolean get() = query.isNotBlank()

    // ------------------------------------------------------------------ lifecycle

    /** Clear everything (called when the screen opens, so each session starts unfiltered). */
    fun reset() {
        query = ""
        focused = false
        caret = 0
        scrollPx = 0f
    }

    /** Focus the field (Ctrl+F / Tab / clicking the field / the sidebar Search tab). Keeps the query. */
    fun focus() {
        TextRow.commitActive() // mutual exclusion: a focused setting field loses the keyboard
        focused = true
        caret = query.length
    }

    /** Unfocus the field but KEEP the query so the filtered grid stays put (click-away). */
    fun blur() {
        focused = false
    }

    /** Clear the query and unfocus — ESC on the field, and any sidebar navigation. */
    fun clear() {
        query = ""
        caret = 0
        focused = false
        scrollPx = 0f
    }

    // ------------------------------------------------------------------ filter + ranking

    /**
     * All modules whose name (or an alias) contains the trimmed query, prefix-of-name matches first, then
     * alphabetical. Empty query -> empty list. See the class KDoc for the citation to Leet's `contains`
     * filter and why the prefix sort is added here.
     */
    fun filtered(): List<ClientModule> {
        val q = query.trim()
        if (q.isEmpty()) {
            return emptyList()
        }
        val lower = q.lowercase()
        return ModuleManager
            .filter { module ->
                module.name.contains(q, ignoreCase = true) ||
                    module.aliases.any { it.contains(q, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<ClientModule> { it.name.lowercase().startsWith(lower) }
                    .thenBy { it.name.lowercase() },
            )
    }

    /** Toggle the [index]-th visible result (0-based). Returns false when out of range. */
    fun quickPick(index: Int): Boolean {
        val list = filtered()
        val module = list.getOrNull(index) ?: return false
        module.enabled = !module.enabled
        return true
    }

    // ------------------------------------------------------------------ keyboard (only while focused)

    /** Handle an editing key. Returns true (consumes) whenever the field is focused. */
    fun keyPressed(keyCode: Int): Boolean {
        if (!focused) {
            return false
        }
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> focused = false // keep the query, drop the caret
            GLFW.GLFW_KEY_ESCAPE -> clear()
            GLFW.GLFW_KEY_BACKSPACE -> backspace()
            GLFW.GLFW_KEY_DELETE -> deleteForward()
            GLFW.GLFW_KEY_LEFT -> caret = (caret - 1).coerceAtLeast(0)
            GLFW.GLFW_KEY_RIGHT -> caret = (caret + 1).coerceAtMost(query.length)
            GLFW.GLFW_KEY_HOME -> caret = 0
            GLFW.GLFW_KEY_END -> caret = query.length
            else -> Unit
        }
        return true
    }

    /** Insert a typed character at the caret (ignored when not focused / for control chars). */
    fun charTyped(char: Char): Boolean {
        if (!focused || char.isISOControl()) {
            return false
        }
        query = query.substring(0, caret) + char + query.substring(caret)
        caret++
        return true
    }

    private fun backspace() {
        if (caret == 0) {
            return
        }
        if (isCtrlDown()) {
            val start = wordStart(query, caret)
            query = query.substring(0, start) + query.substring(caret)
            caret = start
        } else {
            query = query.substring(0, caret - 1) + query.substring(caret)
            caret--
        }
    }

    private fun deleteForward() {
        if (caret < query.length) {
            query = query.substring(0, caret) + query.substring(caret + 1)
        }
    }

    // ------------------------------------------------------------------ mouse hit-test

    /** True when ([mouseX], [mouseY]) is inside the field rect published by the last [draw]. */
    fun inField(mouseX: Float, mouseY: Float): Boolean =
        Widget.rectContains(fieldX, fieldY, fieldW, fieldH, mouseX, mouseY)

    // ------------------------------------------------------------------ draw

    /**
     * Draw the field pill (icon + placeholder/query + blinking caret) and publish its rect for hit-testing.
     * Mirrors [TextRow]'s field body: pill background, scissor-clipped scrolling text and a 1 px caret.
     */
    fun draw(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        fieldX = x
        fieldY = y
        fieldW = w
        fieldH = h

        val borderCol = if (focused) SystemDlcTheme.primary() else SystemDlcTheme.border()
        SystemDraw.pill(
            ctx, x, y, w, h,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
            border = SystemDraw.withAlpha(borderCol, alpha), borderWidth = 1f,
        )

        val iconX = x + PAD_X
        val iconY = y + (h - ICON_SIZE) / 2f
        SystemDraw.icon(ctx, SystemIcon.SEARCH, iconX, iconY, ICON_SIZE, SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha))

        val innerX = iconX + ICON_SIZE + ICON_GAP
        val innerRight = x + w - PAD_X
        val innerW = (innerRight - innerX).coerceAtLeast(1f)

        val placeholder = query.isEmpty() && !focused
        val display = if (placeholder) "Search" else query
        val textColor = if (placeholder) SystemDlcTheme.mutedFg() else SystemDlcTheme.foreground()
        val textH = SystemDraw.lineHeight(FONT_SIZE)
        val textY = y + (h - textH) / 2f

        updateScroll(innerW)

        SystemDraw.scissor(ctx, innerX, y + 1f, innerW, h - 2f) {
            if (display.isNotEmpty()) {
                SystemDraw.text(
                    ctx, display, innerX - scrollPx, textY, FONT_SIZE,
                    SystemDraw.withAlpha(textColor, alpha), align = TextAlign.LEFT,
                )
            }
            if (focused && System.currentTimeMillis() / CARET_BLINK_MS % 2 == 0L) {
                val caretPx = SystemDraw.textWidth(query.substring(0, caret), FONT_SIZE)
                val caretX = innerX + caretPx - scrollPx
                SystemDraw.panel(
                    ctx, caretX, textY, CARET_W, textH, radius = 0f,
                    fill = SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha),
                )
            }
        }
    }

    /** Keep the caret inside the field ([innerW] px), mirroring TextRow.updateScroll. */
    private fun updateScroll(innerW: Float) {
        if (!focused) {
            scrollPx = 0f
            return
        }
        val fullW = SystemDraw.textWidth(query, FONT_SIZE)
        if (fullW <= innerW) {
            scrollPx = 0f
            return
        }
        val caretPx = SystemDraw.textWidth(query.substring(0, caret), FONT_SIZE)
        if (caretPx - scrollPx > innerW) {
            scrollPx = caretPx - innerW
        }
        if (caretPx - scrollPx < 0f) {
            scrollPx = caretPx
        }
        scrollPx = scrollPx.coerceIn(0f, fullW - innerW)
    }

    // ------------------------------------------------------------------ helpers

    private fun isCtrlDown(): Boolean {
        val window = mc.window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
    }

    /** Windows-style Ctrl+Backspace start index (mirrors TextRow.wordStart). */
    private fun wordStart(s: String, from: Int): Int {
        var i = from
        while (i > 0 && !s[i - 1].isLetterOrDigit()) {
            i--
        }
        while (i > 0 && s[i - 1].isLetterOrDigit()) {
            i--
        }
        return i
    }

    private const val PAD_X = 8f
    private const val ICON_SIZE = 10f
    private const val ICON_GAP = 6f
    private const val FONT_SIZE = 11f
    private const val CARET_W = 1f
    private const val CARET_BLINK_MS = 500L
}
