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

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The single global modal-focus register, ported from `client/gui/widget/WidgetState.java`.
 *
 * At most one [Widget] is the modal at a time (an open dropdown / colour picker / key hint). While
 * it is set, it:
 *  - captures all forwarded input (the Screen routes events here first), and
 *  - gets an extra late render pass ([renderModal]) drawn on top of everything.
 *
 * ## Faithful dispatch semantics (load-bearing)
 *
 * The source distinguishes two dispatch shapes (WidgetState.java):
 *  - **click** (`isIntDoubleDouble`, :44-46) returns the modal's OWN result, so a click that the
 *    modal does not handle (e.g. outside an open dropdown) falls through — that is how "click
 *    outside to close" works.
 *  - **every other input** (`isConsumer`, :31-38 — key press/release, char, scroll, drag, release)
 *    returns true whenever a modal exists, unconditionally consuming the event while still invoking
 *    the modal's handler for its side effects.
 *
 * Reproduced here exactly: [mouseClicked] returns the widget's result; the rest return [hasModal].
 *
 * The scrambled source reordered arguments per entry point; every method here forwards in the
 * natural `(mouseX, mouseY, ...)` order that [Widget] exposes.
 */
object WidgetState {

    /** The current modal widget, or null (source `widget`). */
    private var modal: Widget? = null

    /** True only while [renderModal] is running the modal's late pass (source `flag`). */
    private var rendering: Boolean = false

    /**
     * Make [widget] the global modal (source `setWidget2`, WidgetState.java:60-66). If a different
     * modal was set it is first notified via [Widget.onModalDismissed].
     */
    fun setModal(widget: Widget) {
        val previous = modal
        if (previous != null && previous !== widget) {
            previous.onModalDismissed()
        }
        modal = widget
    }

    /**
     * Unregister [widget] IFF it is the current modal (source `setWidget`, WidgetState.java:13-17).
     * A no-op if some other widget is the modal.
     */
    fun clearModal(widget: Widget) {
        if (modal === widget) {
            modal = null
        }
    }

    /** The current modal widget, or null (source `getWidget`). */
    fun current(): Widget? = modal

    /** True if a modal exists (source `isWidgetAsBoolean`, WidgetState.java:68). */
    fun hasModal(): Boolean = modal != null

    /**
     * True if a modal is set AND we are not currently inside its own late render pass (source
     * `check`, WidgetState.java:23-25). Used by [Widget.hoverFactor] to suppress hover on widgets
     * beneath an open modal, while still letting the modal itself hover during its render.
     */
    fun isModalActive(): Boolean = modal != null && !rendering

    /**
     * Run the modal's late render pass on top of everything (source `onMatrix4fFloatFloatFloat`,
     * WidgetState.java:48-58). The [rendering] guard is held so hover is not double-suppressed
     * while the modal paints itself.
     */
    fun renderModal(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val current = modal ?: return
        rendering = true
        try {
            current.renderModal(ctx, alpha, mouseX, mouseY)
        } finally {
            rendering = false
        }
    }

    // ---------------------------------------------------------------- input dispatch

    /** Click dispatch — returns the modal's OWN result so unhandled clicks fall through (source `isIntDoubleDouble`). */
    fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean =
        modal?.mouseClicked(mouseX, mouseY, button) ?: false

    /** Release dispatch — always consumes while a modal exists (source `isDoubleDoubleInt` via `isConsumer`). */
    fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        val current = modal ?: return false
        current.mouseReleased(mouseX, mouseY, button)
        return true
    }

    /** Drag dispatch — always consumes (source `isDoubleIntDoubleDoubleDouble` via `isConsumer`). */
    fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        val current = modal ?: return false
        current.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
        return true
    }

    /** Scroll dispatch — always consumes (source `isDoubleDoubleDouble` via `isConsumer`). */
    fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean {
        val current = modal ?: return false
        current.mouseScrolled(mouseX, mouseY, amount)
        return true
    }

    /** Key-press dispatch — always consumes; the modal's own ESC handling still fires (source `isIntIntInt2`). */
    fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val current = modal ?: return false
        current.keyPressed(keyCode, scanCode, modifiers)
        return true
    }

    /** Key-release dispatch — always consumes (source `isIntIntInt` via `isConsumer`). */
    fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val current = modal ?: return false
        current.keyReleased(keyCode, scanCode, modifiers)
        return true
    }

    /** Char dispatch — always consumes (source `isCharInt` via `isConsumer`). */
    fun charTyped(char: Char, modifiers: Int): Boolean {
        val current = modal ?: return false
        current.charTyped(char, modifiers)
        return true
    }
}
