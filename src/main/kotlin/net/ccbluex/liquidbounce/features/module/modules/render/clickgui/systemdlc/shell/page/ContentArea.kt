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

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.SystemGuiState
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.ScrollableWidget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * The module-card masonry — ported from `client/gui/widget/ContentArea.java`.
 *
 * ## Masonry (cited to ContentArea.java)
 * A **3-column** shortest-column masonry (source packs into three slots, `floatArray[3]` picking the
 * lowest, ContentArea.java:37/439-449) with a **16 px gutter** and a **16 px** margin (`+ 16` everywhere,
 * e.g. :129/:159/:168). Card width tracks the available width (source clamps 301..358 via
 * `EasingPresets.getFloatByFloatFloatFloat(358, avail, 301)`, :354-356); this port divides the available
 * width evenly across the three columns so it never overflows when the sidebar collapses. Column
 * ASSIGNMENT uses each card's settled [ModuleRow.targetHeight] (source `getFloat2`, :227), while the
 * vertical STACK uses the animating [ModuleRow.height] (source `getFloat5`, :161-168), so an expanding
 * card animates in place instead of snapping the column.
 *
 * ## Scroll (cited to ScrollableWidget.java, via the C4 [ScrollableWidget])
 * Smoothed vertical scroll: wheel step 48, a 0.28 s easeOutExpo settle, `maxScroll = extent - height + 16`
 * (the trailing 16 is bottom pad). The TARGET offset is persisted per view in
 * [SystemGuiState.scrollByKey] under the category [key] (source `ContentArea.setFloat`/`getValue249`
 * round-tripped through the config, :123-126/327-329). Top and bottom fade masks appear as the scroll
 * leaves either edge (source `onMatrix4fFloat`, :369-401), and a thin scrollbar thumb rides the right
 * margin (source `onMatrix4fFloatFloat`, :403-420).
 *
 * ## Capture routing
 * While a [KeybindButton] is armed the NEXT click / key is routed to it regardless of position — the
 * fork's `pendingBind` swallow (LeetClickGuiScreen.kt:3646/3758). A press that starts a drag (header
 * toggle scrub or a slider) is remembered as [pressRow]; drags and the release go to it until released,
 * and new presses are ignored while it captures (LeetClickGuiScreen.kt:3642).
 */
class ContentArea : ScrollableWidget() {

    private val rows = ArrayList<ModuleRow>()
    private var currentKey: String? = null

    /** The keybind chip currently capturing the next input, or null. */
    private var armedBind: KeybindButton? = null

    /** The card that consumed the last press (drags + release route here). */
    private var pressRow: ModuleRow? = null

    /** Last-applied "collapse all" state, so only real transitions of the toggle act on the cards. */
    private var appliedCollapseAll: Boolean = SystemGuiState.collapseAllCards

    /** Set the content rect for this frame (host: [net.ccbluex.liquidbounce.features.module.modules
     *  .render.clickgui.systemdlc.shell.ModulesPage]). */
    fun place(x: Float, y: Float, width: Float, height: Float) {
        placeAt(x, y)
        this.width = width
        this.height = height
    }

    /**
     * Retarget the grid to [modules] under scroll [key]. Rebuilds the cards and re-seeds the scroll from
     * [SystemGuiState.scrollByKey] only when [key] changes, so an unrelated frame does not reset scroll or
     * replay the intro.
     */
    fun setContent(modules: List<ClientModule>, key: String) {
        if (key == currentKey) {
            return
        }
        currentKey = key
        rows.clear()
        modules.mapTo(rows) { ModuleRow(it) }

        armedBind = null
        pressRow = null
        appliedCollapseAll = SystemGuiState.collapseAllCards

        val stored = SystemGuiState.scrollByKey[key] ?: 0f
        scrollOffset = max(0f, stored)
        scrollTween.snap(scrollOffset)
    }

    /** Fired when the modules page becomes active — drop any transient capture state. */
    fun onShown() {
        armedBind = null
        pressRow = null
        rows.forEach { it.cancelKeybindCapture() }
    }

    // ---------------------------------------------------------------- layout + render

    private fun cardWidth(): Float =
        ((width - 2f * MARGIN - (COLUMNS - 1) * GUTTER) / COLUMNS).coerceAtLeast(MIN_CARD_WIDTH)

    /**
     * Assign columns and stack offsets, size every card, and return the tallest column's stacked height
     * (no trailing gutter). Also writes each card's on-screen [ModuleRow] position with the current scroll.
     */
    private fun layout(): Float {
        applyCollapseAll()

        val cardW = cardWidth()
        for (row in rows) {
            row.width = cardW
            row.update()
        }

        val colTarget = FloatArray(COLUMNS)
        val colStack = FloatArray(COLUMNS)
        val colCount = IntArray(COLUMNS)
        val scroll = scroll()

        for (row in rows) {
            var col = 0
            for (i in 1 until COLUMNS) {
                if (colTarget[i] < colTarget[col]) {
                    col = i
                }
            }
            val cardX = x + MARGIN + col * (cardW + GUTTER)
            val cardY = y + MARGIN + colStack[col] - scroll
            row.placeAt(cardX, cardY)

            colStack[col] += row.height + GUTTER
            colTarget[col] += row.targetHeight() + GUTTER
            colCount[col]++
        }

        var tallest = 0f
        for (i in 0 until COLUMNS) {
            if (colCount[i] > 0) {
                tallest = max(tallest, colStack[i] - GUTTER)
            }
        }
        return tallest
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val tallest = layout()
        setContentHeight(MARGIN + tallest)

        SystemDraw.scissor(ctx, x, y, width, height) {
            for (row in rows) {
                if (isVisible(row)) {
                    row.render(ctx, alpha, mouseX, mouseY)
                }
            }
        }

        drawEdgeFades(ctx, alpha)
        drawScrollbar(ctx, alpha)
    }

    private fun isVisible(row: ModuleRow): Boolean =
        row.y + row.height > y && row.y < y + height

    private fun drawEdgeFades(ctx: GuiGraphicsExtractor, alpha: Float) {
        val maxScroll = maxScroll()
        if (maxScroll <= 0.001f) {
            return
        }
        val scroll = scroll()
        val topStrength = (scroll / FADE_RAMP).coerceIn(0f, 1f)
        val bottomStrength = ((maxScroll - scroll) / FADE_RAMP).coerceIn(0f, 1f)
        if (topStrength > 0.001f) {
            drawFade(ctx, alpha * topStrength, fromTop = true)
        }
        if (bottomStrength > 0.001f) {
            drawFade(ctx, alpha * bottomStrength, fromTop = false)
        }
    }

    private fun drawFade(ctx: GuiGraphicsExtractor, strength: Float, fromTop: Boolean) {
        val stepH = FADE_HEIGHT / FADE_STEPS
        for (i in 0 until FADE_STEPS) {
            // opaque at the very edge, fading to nothing FADE_HEIGHT in
            val a = strength * (1f - i / FADE_STEPS.toFloat())
            val stripY = if (fromTop) y + i * stepH else y + height - (i + 1) * stepH
            SystemDraw.panel(
                ctx, x, stripY, width, stepH + 0.5f, radius = 0f,
                fill = SystemDraw.withAlpha(SystemDlcTheme.background(), a),
            )
        }
    }

    private fun drawScrollbar(ctx: GuiGraphicsExtractor, alpha: Float) {
        val maxScroll = maxScroll()
        if (maxScroll <= 0.001f) {
            return
        }
        val total = height + maxScroll
        val thumbH = max(SCROLLBAR_MIN, height * height / total)
        val scroll = scroll()
        val thumbY = y + (scroll / maxScroll) * (height - thumbH)
        val thumbX = x + width - SCROLLBAR_INSET - SCROLLBAR_WIDTH
        SystemDraw.pill(
            ctx, thumbX, thumbY, SCROLLBAR_WIDTH, thumbH,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
        )
    }

    private fun applyCollapseAll() {
        val want = SystemGuiState.collapseAllCards
        if (want == appliedCollapseAll) {
            return
        }
        appliedCollapseAll = want
        // want == true -> collapse every card; want == false -> expand every card with settings
        rows.forEach { it.setExpanded(!want) }
    }

    // ---------------------------------------------------------------- input

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean {
        val consumed = super.mouseScrolled(mouseX, mouseY, amount)
        if (consumed) {
            currentKey?.let { SystemGuiState.scrollByKey[it] = scrollOffset }
        }
        return consumed
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        // 1) armed keybind swallows the next click (anywhere on screen)
        armedBind?.let { chip ->
            val consumed = chip.onCaptureMouse(mouseX, mouseY, button)
            if (!chip.armed) {
                armedBind = null
            }
            return consumed
        }
        // 2) an active drag capture blocks new presses (no drag re-targeting)
        if (pressRow?.isCapturing() == true) {
            return true
        }
        // 3) dispatch to the cards inside the viewport
        if (!contains(mouseX, mouseY)) {
            return false
        }
        // re-place cards at the CURRENT rect (the host restores the un-risen rect for input), so hit
        // rects match what the click sees rather than the last frame's intro-risen draw.
        layout()
        for (row in rows) {
            if (!isVisible(row)) {
                continue
            }
            if (row.mouseClicked(mouseX, mouseY, button)) {
                pressRow = row
                syncArmed()
                return true
            }
        }
        syncArmed()
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        val row = pressRow
        pressRow = null
        return row?.mouseReleased(mouseX, mouseY, button) ?: false
    }

    override fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean = pressRow?.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) ?: false

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        armedBind?.let { chip ->
            val consumed = chip.onCaptureKey(keyCode)
            if (!chip.armed) {
                armedBind = null
            }
            return consumed
        }
        // Offer to every card; only a focused text field (in an expanded card) consumes.
        for (row in rows) if (row.keyPressed(keyCode, scanCode, modifiers)) return true
        return false
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean {
        for (row in rows) if (row.charTyped(char, modifiers)) return true
        return false
    }

    /** After a click was dispatched, pick up a keybind chip that armed itself. */
    private fun syncArmed() {
        armedBind = rows.firstOrNull { it.keybind.armed }?.keybind
    }

    private companion object {
        const val COLUMNS = 3
        const val MARGIN = 16f
        const val GUTTER = 16f
        const val MIN_CARD_WIDTH = 180f

        const val FADE_HEIGHT = 24f
        const val FADE_STEPS = 8
        const val FADE_RAMP = 16f

        const val SCROLLBAR_WIDTH = 2f
        const val SCROLLBAR_INSET = 3f
        const val SCROLLBAR_MIN = 24f
    }
}
