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

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.AnimatedInt
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.exp

/**
 * The on/off pill+knob toggle — ported from `client/gui/widget/ToggleButton.java`.
 *
 * ## Geometry (ToggleButton.java, 28x16 from the ctor lines 24-25)
 *  - Track: a `28 x 16` pill, radius `height/2 = 8` (`f8`, :52). Colour lerps **border -> primary**
 *    by the smoothed factor (`AnimatedInt.getIntByIntFloatInt(primary, f1, border)`, :44-47).
 *  - Knob: a `12 x 12` pill, radius 6 (`f11/f10/f9`, :60-62), at `x + 2 + f1 * (width - 16)` (:58),
 *    `y + (height - 12)/2 = y + 2` (:59). Colour lerps **foreground -> background** by the same
 *    factor (:48-51).
 *
 * The ShapeShader rounded-rect used for both is `onFloatFloatIntMatrix4fFloatFloatFloatFloat(radius,
 * x, colorArgb, matrix, height, width, alpha, y)` — decoded from these very call sites (:57 / :63) and
 * re-expressed as [SystemDraw.pill] / [SystemDraw.panel].
 *
 * ## Animation
 * The displayed factor `value240` exponentially chases the target `value239` with rate 14/s
 * (`value240 += (value239 - value240) * (1 - exp(-14 * dt))`, :40-42) — a ~70 ms time constant, on
 * real wall-clock `dt`.
 *
 * ## Input (press / drag / release — scrambled-arg decode)
 * The source's three overrides decode, against [Widget]'s call-site table, to a
 * press-arm / drag-scrub / release-commit gesture (the recovered arg order is scrambled and mixes the
 * coordinate axes, but the INTENT — a horizontal track scrubbed by the pointer's X — is
 * unambiguous from the knob maths on :99, which reads the horizontal position). Reproduced here on
 * the natural `(mouseX, mouseY, button)` order:
 *  - **press** inside (`isDoubleDouble2`, :107-114): arm, record the press X and the pre-press state.
 *  - **drag** (`isDoubleDoubleIntDoubleDouble`, :90-105): once the pointer has moved >= 1.5 px it
 *    enters scrub mode and sets `value239 = clamp((mouseX - (x + 8)) / (width - 16), 0, 1)`.
 *  - **release** (`isDoubleDoubleInt`, :67-79): commit — if scrubbed, `on = value239 >= 0.5`, else
 *    `on = !pressState` (a plain click flips it); snap the target to the committed end.
 */
class ToggleButton(initialState: Boolean) : LabelWidget() {

    /** Current logical state (source `flag4`, `isFlag4`). */
    var state: Boolean = initialState
        private set

    /** Toggle callback fired when the state actually changes via user input (source `runnable`). */
    var onToggle: (() -> Unit)? = null

    /** Animation target `0..1` (source `value239`). */
    private var target: Float = if (initialState) 1f else 0f

    /** Displayed factor `0..1` chasing [target] (source `value240`). */
    private var displayed: Float = target

    private var lastNanos: Long = System.nanoTime()

    // gesture state (source flag5 armed / flag6 dragged / flag7 pressState / value241 pressX)
    private var armed = false
    private var dragged = false
    private var pressState = false
    private var pressX = 0.0

    init {
        width = TRACK_WIDTH
        height = TRACK_HEIGHT
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / NANOS_PER_SECOND).toFloat()
        lastNanos = now
        displayed += (target - displayed) * (1f - exp(-SMOOTH_RATE * dt))
        val f = displayed

        val border = SystemDlcTheme.border().argb
        val primary = SystemDlcTheme.primary().argb
        val foreground = SystemDlcTheme.foreground().argb
        val background = SystemDlcTheme.background().argb

        val trackColor = Color4b(AnimatedInt.lerpArgb(primary, f, border))
        val knobColor = Color4b(AnimatedInt.lerpArgb(background, f, foreground))

        // track: full pill (radius = height/2)
        SystemDraw.pill(ctx, x, y, width, height, SystemDraw.withAlpha(trackColor, alpha))

        // knob: 12x12, radius 6
        val knobX = x + KNOB_INSET + f * (width - KNOB_SIZE - KNOB_INSET * 2f)
        val knobY = y + (height - KNOB_SIZE) / 2f
        SystemDraw.panel(
            ctx,
            knobX,
            knobY,
            KNOB_SIZE,
            KNOB_SIZE,
            radius = KNOB_SIZE / 2f,
            fill = SystemDraw.withAlpha(knobColor, alpha),
        )
    }

    /** Not used: the toggle arms on a coordinate-bearing press instead (see [mouseClicked]). */
    override fun onClick(): Boolean = false

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            armed = true
            dragged = false
            pressState = state
            pressX = mouseX.toDouble()
            return true
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
        if (!armed || button != 0) {
            return false
        }
        if (!dragged && abs(mouseX - pressX) >= DRAG_THRESHOLD) {
            dragged = true
        }
        if (dragged) {
            val travel = width - KNOB_SIZE - KNOB_INSET * 2f
            target = ((mouseX - (x + KNOB_INSET + KNOB_SIZE / 2f)) / travel).coerceIn(0f, 1f)
        }
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (!armed || button != 0) {
            return false
        }
        armed = false
        val committed = if (dragged) target >= 0.5f else !pressState
        dragged = false
        setState(committed, fireCallback = true)
        target = if (committed) 1f else 0f
        return true
    }

    /** Set the state externally without firing [onToggle] (source `setBoolean2`, :116-121). */
    fun setStateSilently(on: Boolean) {
        setState(on, fireCallback = false)
        target = if (on) 1f else 0f
    }

    private fun setState(on: Boolean, fireCallback: Boolean) {
        if (state != on) {
            state = on
            if (fireCallback) {
                onToggle?.invoke()
            }
        }
    }

    private companion object {
        const val TRACK_WIDTH = 28f
        const val TRACK_HEIGHT = 16f
        const val KNOB_SIZE = 12f

        /** Knob edge inset (source `+ 2`, ToggleButton.java:58/59). */
        const val KNOB_INSET = 2f

        /** Chase rate, units/second (source `exp(-14 * dt)`, ToggleButton.java:42). */
        const val SMOOTH_RATE = 14f

        /** Pixels of travel before a press becomes a scrub (source `>= 1.5`, ToggleButton.java:92). */
        const val DRAG_THRESHOLD = 1.5

        const val NANOS_PER_SECOND = 1.0E9
    }
}
