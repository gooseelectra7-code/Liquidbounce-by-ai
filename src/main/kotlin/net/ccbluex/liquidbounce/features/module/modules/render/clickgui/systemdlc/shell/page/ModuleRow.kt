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
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row.SettingRow
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.FavoriteButton
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.ToggleButton
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * One module card — ported from `client/gui/widget/ModuleRow.java`.
 *
 * ## Geometry (all constants cited to ModuleRow.java)
 * The card is `radius 14`, `Theme.background()` fill with a `1 px Theme.border()` outline
 * (ModuleRow.java:188-197). The header band is `24` tall inset `16` from the top (`f236 + 16 .. + 24`,
 * :422-425): a `16` px category glyph at `content + 4`, the `16` px module name at `content + 16 + 6`
 * (:438-448), and — right-aligned — the [KeybindButton], the `16 x 16` [FavoriteButton] and the `28 x 16`
 * [ToggleButton], each separated by `4 px` (`getFloatByFloatFloat2`, :513-527). A collapsed card is
 * `56` tall (`16 + 24 + 16`, `getFloat2` = 56, :152). Expanded, a `1 px` divider sits at `+56` and the
 * setting rows begin at `+73` (`16 + 24 + 16 + 1 + 16`, `getFloat4` base = 73, :319), stacked with the
 * uniform `16 px` gap and a `16 px` bottom pad.
 *
 * ## Animation (cited to ModuleRow.java ctor lines 32-34)
 *  - **expand**: `tween4 = 0.32 s` easeOutCubic (`getTweenByFloatFloat2(0, 0.32)`), height eased between
 *    `56` and the measured expanded height; the settings are scissored to the animating card bottom so
 *    they wipe in.
 *  - **enable fade**: `tween5 = 0.22 s` easeOutCubic, fading the WHOLE card `1.0 <-> 0.5` with the
 *    module's enabled state (source `f = value * tween5`, :173). The knob/star/check sub-animations live
 *    in the C5 atoms.
 *
 * ## Deviation from source: default COLLAPSED
 * The source defaults cards with settings to EXPANDED and RMB COLLAPSES them (`flag5`, `check()`
 * inversion, :93-95/124-127). This port inverts that default to COLLAPSED (RMB EXPANDS) to match the C7
 * task description and to avoid opening a category as a wall of fully-expanded cards. The geometry and the
 * 0.32 s tween are unchanged; only the initial [expanded] value and the RMB sense differ. The source's
 * scroll-to-card highlight flash (`tween6`/`update6`, :296-301) is not ported (no search-to-module here).
 */
class ModuleRow(val module: ClientModule) : Widget() {

    private val toggleButton = ToggleButton(module.enabled).apply {
        onToggle = { module.enabled = state }
    }
    private val favoriteButton = FavoriteButton(FavoriteStore.isFavourite(module.name)).apply {
        onToggle = { FavoriteStore.setFavourite(module.name, state) }
    }

    /** The bind chip, editing the module's own `Value<InputBind>` (see [KeybindButton]). */
    val keybind = KeybindButton(module.bindValue)

    /** Visible setting rows (bind + Enabled/Hidden + notAnOption filtered out — see [buildRows]). */
    private val rows: List<SettingRow> = buildRows()

    /** Collapsed (default) vs expanded; RMB on the header toggles it when there are settings. */
    var expanded: Boolean = false
        private set

    private val expandTween: Tween = EasingPresets.cubicTween(0f, EXPAND_SECONDS)
    private val enableTween: Tween =
        EasingPresets.cubicTween(if (module.enabled) 1f else DISABLED_FADE, ENABLE_SECONDS)

    // last [update] sample, reused by [render] and the input gate so tweens advance once per frame
    private var enableFactor: Float = if (module.enabled) 1f else DISABLED_FADE
    private var expandProgress: Float = 0f

    // active pointer-drag capture: the header toggle (scrub) or a slider row
    private var dragToggle: ToggleButton? = null
    private var dragRow: SettingRow? = null

    private val categoryIcon: SystemIcon = iconFor(module.category)

    init {
        width = COLLAPSED_H // seeded; ContentArea sets the real card width each frame
        height = COLLAPSED_H
    }

    private fun buildRows(): List<SettingRow> = module.inner
        .filter { value ->
            value.name != "Enabled" && value.name != "Hidden" &&
                !value.notAnOption && value !== module.bindValue
        }
        .map { SettingRow.create(it) }

    /** True if the card has any visible setting rows to reveal. */
    fun hasSettings(): Boolean = rows.isNotEmpty()

    /** True while the pointer drag started on this card is still active (toggle scrub or slider). */
    fun isCapturing(): Boolean = dragToggle != null || (dragRow?.capturing == true)

    /** Force the expansion state (used by "collapse/expand all"). No-op for a card without settings. */
    fun setExpanded(value: Boolean) {
        expanded = value && hasSettings()
    }

    /** Drop any pending keybind capture (page change / rebuild). */
    fun cancelKeybindCapture() {
        keybind.cancelCapture()
    }

    // ---------------------------------------------------------------- height

    private fun contentLeft(): Float = x + CARD_PAD

    private fun contentWidth(): Float = width - 2f * CARD_PAD

    /** Full expanded height at the current [width] (source `getFloat4`, ModuleRow.java:317-334). */
    private fun expandedHeight(): Float {
        if (rows.isEmpty()) {
            return COLLAPSED_H
        }
        val cw = contentWidth()
        var h = SETTINGS_TOP
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                h += GAP
            }
            h += row.measure(cw)
        }
        return h + CARD_PAD
    }

    /** The height used for masonry column assignment: the settled target, not the animating value. */
    fun targetHeight(): Float = if (expanded && hasSettings()) expandedHeight() else COLLAPSED_H

    // ---------------------------------------------------------------- update / render

    /**
     * Advance the enable/expand tweens and recompute [height] for this frame (no drawing). The host
     * ([ContentArea]) calls this for EVERY card before laying columns out, so the masonry sees final
     * heights, then [render]s only the on-screen ones.
     */
    fun update() {
        // keep the toggle knob in sync with external state changes without fighting an active drag
        if (toggleButton.state != module.enabled) {
            toggleButton.setStateSilently(module.enabled)
        }
        enableTween.to(if (module.enabled) 1f else DISABLED_FADE)
        enableFactor = enableTween.get()

        expandTween.to(if (expanded && hasSettings()) 1f else 0f)
        expandProgress = expandTween.get()
        val expH = expandedHeight()
        height = COLLAPSED_H + (expH - COLLAPSED_H) * expandProgress
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val cardAlpha = alpha * enableFactor
        val e = expandProgress

        // card body
        SystemDraw.panel(
            ctx, x, y, width, height, radius = CARD_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.background(), cardAlpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), cardAlpha),
            borderWidth = 1f,
        )

        drawHeader(ctx, cardAlpha, mouseX, mouseY)

        if (e > 0.001f) {
            drawSettings(ctx, cardAlpha, e, mouseX, mouseY)
        }
    }

    private fun drawHeader(ctx: GuiGraphicsExtractor, cardAlpha: Float, mouseX: Float, mouseY: Float) {
        val headerTop = y + CARD_PAD
        val glyphY = headerTop + GLYPH_INSET
        val contentLeft = contentLeft()

        // category glyph
        SystemDraw.icon(
            ctx, categoryIcon, contentLeft, glyphY, GLYPH_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), cardAlpha),
        )

        // right cluster: toggle (rightmost), favourite, keybind — right-aligned, 4 px apart
        val toggleX = x + width - CARD_PAD - toggleButton.width
        toggleButton.placeAt(toggleX, headerTop + (HEADER_H - toggleButton.height) / 2f)
        toggleButton.render(ctx, cardAlpha, mouseX, mouseY)

        val favX = toggleX - RIGHT_GAP - favoriteButton.width
        favoriteButton.placeAt(favX, headerTop + (HEADER_H - favoriteButton.height) / 2f)
        favoriteButton.render(ctx, cardAlpha, mouseX, mouseY)

        val keybindW = keybind.measureWidth()
        val keybindX = favX - RIGHT_GAP - keybindW
        keybind.placeAt(keybindX, headerTop + (HEADER_H - keybind.height) / 2f)
        keybind.render(ctx, cardAlpha, mouseX, mouseY)

        // module name, clipped so it never runs under the keybind chip
        val nameX = contentLeft + GLYPH_SIZE + NAME_GAP
        val nameMax = keybindX - NAME_CLIP_PAD - nameX
        val name = fitText(module.name, NAME_SIZE, nameMax)
        SystemDraw.text(
            ctx, name, nameX, glyphY, NAME_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), cardAlpha), align = TextAlign.LEFT,
        )
    }

    private fun drawSettings(
        ctx: GuiGraphicsExtractor,
        cardAlpha: Float,
        expand: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        val contentLeft = contentLeft()
        val cw = contentWidth()

        // divider under the header
        SystemDraw.panel(
            ctx, contentLeft, y + DIVIDER_Y, cw, 1f, radius = 0f,
            fill = SystemDraw.withAlpha(SystemDlcTheme.border(), cardAlpha * expand),
        )

        // clip the setting block to the animating card bottom so it wipes in
        val clipTop = y + SETTINGS_TOP
        val clipBottom = y + height - CARD_PAD
        val clipH = (clipBottom - clipTop).coerceAtLeast(0f)
        if (clipH <= 0f) {
            return
        }

        SystemDraw.scissor(ctx, x, clipTop, width, clipH) {
            layoutRows()
            val rowAlpha = cardAlpha * expand
            for (row in rows) {
                row.draw(ctx, rowAlpha, mouseX, mouseY)
            }
        }
    }

    /** Position every setting row from the current card geometry (no drawing). */
    private fun layoutRows() {
        val contentLeft = contentLeft()
        val cw = contentWidth()
        var ry = y + SETTINGS_TOP
        for ((index, row) in rows.withIndex()) {
            if (index > 0) {
                ry += GAP
            }
            row.place(contentLeft, ry, cw)
            ry += row.height
        }
    }

    // ---------------------------------------------------------------- input

    private fun inHeader(mouseX: Float, mouseY: Float): Boolean =
        Widget.rectContains(x, y + CARD_PAD, width, HEADER_H, mouseX, mouseY)

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        // header controls first
        if (keybind.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        if (favoriteButton.mouseClicked(mouseX, mouseY, button)) {
            return true
        }
        if (toggleButton.mouseClicked(mouseX, mouseY, button)) {
            dragToggle = toggleButton
            return true
        }
        // MMB on the header arms bind capture (mirrors the LeetClickGuiScreen card MMB affordance)
        if (button == MMB && inHeader(mouseX, mouseY)) {
            keybind.beginCapture()
            return true
        }
        // RMB on the header expands/collapses (only when there is something to show)
        if (button == 1 && inHeader(mouseX, mouseY) && hasSettings()) {
            expanded = !expanded
            return true
        }
        // setting rows, once the card is at least half-expanded (source gate `tween4 >= 0.5`)
        if (expandProgress >= HALF && hasSettings()) {
            layoutRows()
            for (row in rows) {
                if (row.mouseClicked(mouseX, mouseY, button)) {
                    if (row.capturing) {
                        dragRow = row
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        dragToggle?.let { return it.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) }
        dragRow?.let { return it.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) }
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        val toggle = dragToggle
        val row = dragRow
        dragToggle = null
        dragRow = null
        toggle?.let { return it.mouseReleased(mouseX, mouseY, button) }
        row?.let { return it.mouseReleased(mouseX, mouseY, button) }
        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (expandProgress >= HALF && hasSettings()) {
            layoutRows()
            for (row in rows) if (row.keyPressed(keyCode, scanCode, modifiers)) return true
        }
        return false
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean {
        if (expandProgress >= HALF && hasSettings()) {
            layoutRows()
            for (row in rows) if (row.charTyped(char, modifiers)) return true
        }
        return false
    }

    private companion object {
        const val CARD_PAD = 16f
        const val HEADER_H = 24f
        const val GAP = 16f
        const val CARD_RADIUS = 14f
        const val COLLAPSED_H = 56f // 16 + 24 + 16
        const val DIVIDER_Y = 56f // 16 + 24 + 16
        const val SETTINGS_TOP = 73f // 16 + 24 + 16 + 1 + 16

        const val GLYPH_SIZE = 16f
        const val GLYPH_INSET = 4f
        const val NAME_SIZE = 16f
        const val NAME_GAP = 6f
        const val NAME_CLIP_PAD = 8f
        const val RIGHT_GAP = 4f

        const val EXPAND_SECONDS = 0.32f
        const val ENABLE_SECONDS = 0.22f
        const val DISABLED_FADE = 0.5f
        const val HALF = 0.5f

        const val MMB = 2
        const val ELLIPSIS = ".."

        /** OUR module categories -> SystemDLC icon keys (mirrors SideBar.iconFor, as [SystemIcon]). */
        fun iconFor(category: ModuleCategory): SystemIcon = when (category.tag) {
            "Combat" -> SystemIcon.COMBAT
            "Movement" -> SystemIcon.MOVEMENT
            "Player" -> SystemIcon.PLAYER
            "Render" -> SystemIcon.RENDER
            "World" -> SystemIcon.GLOBE
            "Exploit" -> SystemIcon.BOLT
            "Fun" -> SystemIcon.HEART
            "Misc" -> SystemIcon.CUBE
            else -> SystemIcon.CUBE
        }

        /** Trim [text] with a trailing ".." so it fits within [maxWidth] at [size]. */
        fun fitText(text: String, size: Float, maxWidth: Float): String {
            if (maxWidth <= 0f) {
                return ""
            }
            if (SystemDraw.textWidth(text, size) <= maxWidth) {
                return text
            }
            var end = text.length
            while (end > 0 && SystemDraw.textWidth(text.substring(0, end) + ELLIPSIS, size) > maxWidth) {
                end--
            }
            return if (end <= 0) ELLIPSIS else text.substring(0, end) + ELLIPSIS
        }
    }
}
