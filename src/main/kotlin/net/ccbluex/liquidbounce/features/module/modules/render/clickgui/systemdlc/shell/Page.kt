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

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A content pane hosted by the [NavBar] inside the SystemClickGui window — the abstraction the source
 * client expressed as its concrete `ContentArea`/`ConfigsPage`/`FriendsPage`/`WaypointsPage` widgets
 * (the `client/gui/widget/` `*Page.java` files), unified here into one stable interface.
 *
 * ## Contract (STABLE — the C7 cluster implements [ModulesPage] against exactly this)
 *
 * The host (NavBar) owns the retained-widget lifecycle:
 *  1. Each frame, BEFORE drawing and BEFORE dispatching input, it calls [bounds] with the content
 *     rectangle (already in the window's virtual coordinate space, below the TopBar).
 *  2. It calls [render] once per frame; the page draws its body into the rectangle it was given,
 *     fading everything by [render]'s `alpha` (the GUI open/close + page-intro opacity).
 *  3. It forwards mouse/key input to the matching handlers; a page returns `true` to consume.
 *  4. [onShown] fires whenever this page becomes the active one (use it to reset scroll / start an
 *     intro), never on every frame.
 *
 * Coordinates are GUI units in the window's virtual space; a page never touches the pose/scale — the
 * screen has already applied it, and clipping is done via `SystemDraw.scissor`.
 */
abstract class Page {

    /** Title shown in the [TopBar]. May change over time (e.g. [ModulesPage] retitles per category). */
    abstract val title: String

    /** Left edge of the content rect (GUI units), set by the host via [bounds]. */
    var contentX: Float = 0f
        private set

    /** Top edge of the content rect. */
    var contentY: Float = 0f
        private set

    /** Width of the content rect. */
    var contentWidth: Float = 0f
        private set

    /** Height of the content rect. */
    var contentHeight: Float = 0f
        private set

    /** Host call: set the content rectangle for this frame (before [render] and input dispatch). */
    fun bounds(x: Float, y: Float, width: Float, height: Float) {
        contentX = x
        contentY = y
        contentWidth = width
        contentHeight = height
    }

    /**
     * Draw the page body into the current [bounds]. [mouseX]/[mouseY] are in window virtual coords;
     * [alpha] is the opacity multiplier (GUI open/close × page intro) — every draw fades by it.
     */
    abstract fun render(ctx: GuiGraphicsExtractor, mouseX: Float, mouseY: Float, alpha: Float)

    /** Called once when this page becomes active (reset scroll / start an intro here). */
    open fun onShown() {}

    open fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    open fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    open fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean = false

    open fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean = false

    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    open fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    open fun charTyped(char: Char, modifiers: Int): Boolean = false
}
