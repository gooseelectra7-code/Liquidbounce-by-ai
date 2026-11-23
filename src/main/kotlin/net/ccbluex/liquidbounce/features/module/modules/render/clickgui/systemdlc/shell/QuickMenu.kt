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

import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.WidgetState
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW

/**
 * The Settings pop-over — ported from `client/gui/widget/QuickMenu.java`. A small floating panel of
 * four rows (Change Theme / Change Language / Panic & Clean / Close), opened from the sidebar Settings
 * tab and dismissed by a click or ESC.
 *
 * ## As a modal
 * It registers itself with [WidgetState] as the single global modal ([open]); it therefore draws in the
 * late [renderModal] pass (on top of everything) and receives input first. Because [WidgetState]'s click
 * dispatch returns the modal's OWN result, this consumes every click while open (row → action + close,
 * elsewhere-outside → close) so a dismissing click never leaks to the widgets beneath it.
 *
 * ## Geometry (QuickMenu.java): width 192, height `8 + n*32 + (n-1)*9` (`getFloat6`, :101-106); rows are
 * `184` wide inset `4`, `32` tall, separated by a 1 px border with `4 + 5` padding around it (:47-61).
 *
 * ## Handlers
 * Change Theme flips [SystemDlcTheme.dark]; the other three are wired by the owning screen (Close ends
 * the GUI). Change Language / Panic & Clean are provided as callbacks so the screen can leave them as
 * documented stubs for now (the source drove `Translations`/the Panic module).
 */
class QuickMenu(
    private val onChangeTheme: () -> Unit,
    private val onChangeLanguage: () -> Unit,
    private val onPanic: () -> Unit,
    private val onCloseGui: () -> Unit,
) : Widget() {

    private class Row(val label: String, val danger: Boolean, val action: () -> Unit)

    private val rows: List<Row> = listOf(
        Row("Change Theme", false, onChangeTheme),
        Row("Change Language", false, onChangeLanguage),
        Row("Panic & Clean", false, onPanic),
        Row("Close", true, onCloseGui),
    )

    init {
        width = MENU_WIDTH
        height = PAD * 2f + rows.size * ROW_H + (rows.size - 1) * ROW_GAP
    }

    /** True while this pop-over is the active global modal. */
    val isOpen: Boolean
        get() = WidgetState.current() === this

    /** Open the pop-over with its top-left at ([anchorX], [anchorY]) and take the modal focus. */
    fun open(anchorX: Float, anchorY: Float) {
        placeAt(anchorX, anchorY)
        WidgetState.setModal(this)
    }

    /** Move the pop-over's top-left to ([anchorX], [anchorY]) without changing modal state. */
    fun reAnchor(anchorX: Float, anchorY: Float) = placeAt(anchorX, anchorY)

    /** Release the modal focus if this pop-over holds it. */
    fun dismiss() = WidgetState.clearModal(this)

    // Nothing in the main pass — the pop-over is a modal drawn in the late pass.
    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {}

    override fun renderModal(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        SystemDraw.panel(
            ctx, x, y, width, height, radius = RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.background(), alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
            borderWidth = 1f,
            shadow = SystemDraw.withAlpha(SystemDlcTheme.SHADOW, alpha),
            shadowBlur = SHADOW_BLUR,
        )

        val rowX = x + INSET
        val rowW = width - INSET * 2f
        var rowY = y + PAD
        for ((i, row) in rows.withIndex()) {
            if (Widget.rectContains(rowX, rowY, rowW, ROW_H, mouseX, mouseY)) {
                SystemDraw.panel(
                    ctx, rowX, rowY, rowW, ROW_H, radius = ROW_RADIUS,
                    fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
                )
            }
            val color = if (row.danger) SystemDlcTheme.danger() else SystemDlcTheme.foreground()
            SystemDraw.text(
                ctx, row.label, rowX + LABEL_INSET, rowY + (ROW_H - LABEL_SIZE) / 2f, LABEL_SIZE,
                SystemDraw.withAlpha(color, alpha),
            )
            rowY += ROW_H
            if (i < rows.size - 1) {
                SystemDraw.panel(
                    ctx, rowX, rowY + ROW_GAP / 2f, rowW, 1f, radius = 0f,
                    fill = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
                )
                rowY += ROW_GAP
            }
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0) {
            val rowX = x + INSET
            val rowW = width - INSET * 2f
            var rowY = y + PAD
            for ((i, row) in rows.withIndex()) {
                if (Widget.rectContains(rowX, rowY, rowW, ROW_H, mouseX, mouseY)) {
                    dismiss()
                    row.action()
                    return true
                }
                rowY += ROW_H
                if (i < rows.size - 1) {
                    rowY += ROW_GAP
                }
            }
        }
        // A click that missed every row: dismiss when it fell outside the panel; always consume so the
        // dismissing click never also hits the widgets underneath.
        if (!contains(mouseX, mouseY)) {
            dismiss()
        }
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            dismiss()
            return true
        }
        return false
    }

    private companion object {
        const val MENU_WIDTH = 192f
        const val ROW_H = 32f
        const val ROW_GAP = 9f
        const val PAD = 4f
        const val INSET = 4f
        const val RADIUS = 8f
        const val ROW_RADIUS = 6f
        const val SHADOW_BLUR = 12f
        const val LABEL_SIZE = 14f
        const val LABEL_INSET = 12f
    }
}
