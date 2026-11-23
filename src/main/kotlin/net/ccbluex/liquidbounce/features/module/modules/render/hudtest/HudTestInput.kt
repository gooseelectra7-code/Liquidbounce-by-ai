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
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import net.ccbluex.liquidbounce.utils.client.mc

/**
 * Mouse interaction for the HUD, active only while a screen (chat or a container) is open.
 *
 * Panels register their clickable sub-rectangles — the `...` and `x` affordances, the settings
 * toggles, the media transport buttons — while they render, in gui units and in draw order. A
 * click then walks that list backwards, so whatever was drawn last (and is therefore on top) wins.
 * Anything that hits no button instead starts a drag of the topmost panel under the cursor.
 */
internal object HudTestInput {

    private class Hit(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val action: () -> Unit,
    ) {
        fun contains(mouseX: Float, mouseY: Float): Boolean =
            mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }

    private val hits = ArrayList<Hit>()

    /** The panel currently being dragged, if any. */
    var dragging: HudTestPanel? = null
        private set

    /** Cursor offset inside the dragged panel, so it does not jump on grab. */
    var grabX = 0f
        private set
    var grabY = 0f
        private set

    /** Cursor position in gui units. */
    fun mouseX(): Float = (mc.mouseHandler.xpos() / mc.window.guiScale).toFloat()

    fun mouseY(): Float = (mc.mouseHandler.ypos() / mc.window.guiScale).toFloat()

    /** Drops the hit list; called once at the top of every render pass. */
    fun beginFrame() {
        hits.clear()
    }

    /**
     * Registers a clickable rectangle. [frame] and the local native-pixel rectangle are converted
     * to gui units here so panels never have to think about the pose.
     */
    @Suppress("LongParameterList")
    fun addButton(
        frame: HudTestFrame,
        localX: Float,
        localY: Float,
        localWidth: Float,
        localHeight: Float,
        action: () -> Unit,
    ) {
        hits += Hit(
            frame.guiX(localX),
            frame.guiY(localY),
            localWidth * frame.guiUnit,
            localHeight * frame.guiUnit,
            action,
        )
    }

    /** Runs the topmost registered button under the cursor; returns whether one was hit. */
    fun consumeClick(mouseX: Float, mouseY: Float): Boolean {
        for (index in hits.indices.reversed()) {
            val hit = hits[index]
            if (hit.contains(mouseX, mouseY)) {
                hit.action()
                return true
            }
        }
        return false
    }

    /** Grabs [panel] for dragging with the cursor at ([mouseX], [mouseY]) in gui units. */
    fun startDrag(panel: HudTestPanel, mouseX: Float, mouseY: Float) {
        dragging = panel
        grabX = mouseX - panel.guiLeft
        grabY = mouseY - panel.guiTop
    }

    /** Releases any active drag. */
    fun endDrag() {
        dragging = null
    }
}
