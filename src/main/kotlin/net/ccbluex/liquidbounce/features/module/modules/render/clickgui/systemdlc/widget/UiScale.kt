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

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.UiClock

/**
 * The virtual-canvas coordinate contract the SystemDLC ClickGUI widgets are authored against —
 * the narrow slice of `client/gui/widget/UiContext.java` the C4 core needs. The full `UiContext`
 * (the HUD render-element list, focus/tooltip/input handlers) is a LATER cluster and is deliberately
 * NOT ported here.
 *
 * ## Why this exists
 *
 * The source lays every widget out in a fixed **1920 x 1080 virtual canvas** and scales the whole GUI
 * to the window at draw time (source `UiContext.value235 = 1920`, `value236 = 1080`, `getFloat3()`
 * returns 1920, and `update4` derives the scale, UiContext.java:34-35/138-148). That is why
 * [PanelWidget.DEFAULT_PANEL_WIDTH] is `269` — a width in this 1920-wide space, NOT window pixels.
 * The Screen-wiring cluster uses these to place the widget tree and set the render pose; the widgets
 * themselves only ever work in virtual units.
 *
 * The two frame concerns the source read off `UiContext` — the memo frame counter and the per-frame
 * time — live in the C0 [UiClock]; [frame] forwards to it so callers have one import.
 *
 * Pure math only (no `Minecraft` handle): the Screen feeds the live window metrics in.
 */
object UiScale {

    /** Virtual canvas width in GUI units (source `UiContext.value235` / `getFloat3()` = 1920). */
    const val VIRTUAL_WIDTH = 1920f

    /** Virtual canvas height in GUI units (source `UiContext.value236` = 1080). */
    const val VIRTUAL_HEIGHT = 1080f

    /** The per-frame memo key. Forwards to [UiClock.frame]. */
    fun frame(): Long = UiClock.frame()

    /**
     * The width-driven virtual->window scale factor (source `UiContext.value237` in `update4`,
     * UiContext.java:146): `windowWidthPx / 1920 / scaleFactor`, where `scaleFactor` is the GUI scale
     * of the window. Returns 0 for a non-positive window width.
     */
    fun scaleForWidth(windowWidthPx: Int, scaleFactor: Double): Float =
        if (windowWidthPx <= 0) 0f else (windowWidthPx / VIRTUAL_WIDTH / scaleFactor).toFloat()

    /**
     * The virtual canvas height for the current window aspect (source `UiContext.value238`,
     * UiContext.java:147): `windowHeightPx * 1920 / windowWidthPx`, falling back to 1080 for a
     * non-positive width. Because the width maps to a fixed 1920, a taller window yields a taller
     * virtual canvas.
     */
    fun virtualHeightForWindow(windowWidthPx: Int, windowHeightPx: Int): Float =
        if (windowWidthPx > 0) windowHeightPx * VIRTUAL_WIDTH / windowWidthPx else VIRTUAL_HEIGHT

    /**
     * The fit-to-canvas scale the source `ClickGuiScreen` itself uses (ClickGuiScreen.java:404):
     * `min(windowWidthPx / 1920, windowHeightPx / 1080)` times the GUI scale, so the whole 1920x1080
     * canvas fits inside the window with letterboxing. Provided for the Screen-wiring cluster; the
     * width-driven [scaleForWidth] is the HUD path.
     */
    fun fitScale(windowWidthPx: Int, windowHeightPx: Int): Float =
        minOf(windowWidthPx / VIRTUAL_WIDTH, windowHeightPx / VIRTUAL_HEIGHT)
}
