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
@file:Suppress("detekt:TooManyFunctions")

package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import kotlin.math.cos
import kotlin.math.sin

/**
 * The lavender glyph set of the reference: the status-bar markers, the panel header icons and the
 * two right-hand affordances.
 *
 * Every glyph is drawn as vector geometry into the caller's shape batch, normalized to a unit box
 * scaled by `size`, so a status marker and a header icon are literally the same shape. Parts that
 * have to punch a hole into a glyph paint with the opaque card color, which is what the reference
 * does as well — none of these icons has a transparent interior.
 *
 * The public entry point of each glyph is a [fitGlyph] wrapper: the geometry below is written
 * against whatever unit box reads best while drawing it, and the wrapper states the box that shape
 * *actually* covers so every mark ends up the same optical size, centred in its slot. Without it
 * the person marker came out 0.72 units wide against the ping bars' 1.0 and the rocket sat 0.04 off
 * the left edge, so a column of marks that share one x drifted apart by up to two pixels.
 */

/** Opaque card color, used to cut the dark details out of a filled glyph. */
private val PUNCH = HudTestTheme.argb(255, 0x0F0F0F)

/**
 * Runs [draw] with an origin and a unit size that place the glyph's real extent — the box
 * ([boxX0], [boxY0]) .. ([boxX1], [boxY1]) in its own unit space — centred inside the slot
 * ([x], [y], [size]), scaled so its longer side just fills the slot.
 *
 * `draw` receives the substituted origin and unit, so the glyph body keeps writing plain
 * `x + 0.3f * size` coordinates and needs to know nothing about the fit.
 */
@Suppress("LongParameterList")
internal inline fun fitGlyph(
    x: Float,
    y: Float,
    size: Float,
    boxX0: Float,
    boxY0: Float,
    boxX1: Float,
    boxY1: Float,
    draw: (Float, Float, Float) -> Unit,
) {
    val boxWidth = boxX1 - boxX0
    val boxHeight = boxY1 - boxY0
    if (boxWidth <= 0f || boxHeight <= 0f || size <= 0f) {
        return
    }
    val unit = size / maxOf(boxWidth, boxHeight)
    draw(
        x + (size - boxWidth * unit) * 0.5f - boxX0 * unit,
        y + (size - boxHeight * unit) * 0.5f - boxY0 * unit,
        unit,
    )
}

/** The FPS marker: a small rocket leaning to the upper right. */
internal fun HudTestShapes.iconRocket(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0.04f, 0.13f, 0.89f, 0.90f) { gx, gy, unit ->
        stroke(gx + 0.30f * unit, gy + 0.74f * unit, gx + 0.74f * unit, gy + 0.28f * unit, 0.30f * unit, argb)
        disc(gx + 0.74f * unit, gy + 0.28f * unit, 0.15f * unit, argb)
        roundRect(gx + 0.04f * unit, gy + 0.60f * unit, 0.30f * unit, 0.30f * unit, 0.10f * unit, argb)
    }

/** The ping marker: three bars of rising height, aligned to the baseline. */
internal fun HudTestShapes.iconBars(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0f, 1f, 1f) { gx, gy, unit ->
        val barWidth = 0.22f * unit
        val pitch = 0.39f * unit
        val heights = floatArrayOf(0.42f, 0.70f, 1f)
        for (index in 0 until 3) {
            val height = heights[index] * unit
            roundRect(gx + index * pitch, gy + unit - height, barWidth, height, barWidth * 0.35f, argb)
        }
    }

/** The third status slot's marker: a head over rounded shoulders. */
internal fun HudTestShapes.iconPerson(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0.14f, 0.05f, 0.86f, 1f) { gx, gy, unit ->
        disc(gx + 0.5f * unit, gy + 0.26f * unit, 0.21f * unit, argb)
        roundRect(gx + 0.14f * unit, gy + 0.55f * unit, 0.72f * unit, 0.45f * unit, 0.22f * unit, argb)
    }

/** The Potions header icon. */
internal fun HudTestShapes.iconHeart(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0.05f, 1f, 0.96f) { gx, gy, unit ->
        disc(gx + 0.29f * unit, gy + 0.34f * unit, 0.29f * unit, argb)
        disc(gx + 0.71f * unit, gy + 0.34f * unit, 0.29f * unit, argb)
        triangle(
            gx + 0.02f * unit, gy + 0.40f * unit,
            gx + 0.5f * unit, gy + 0.96f * unit,
            gx + 0.98f * unit, gy + 0.40f * unit,
            argb,
        )
    }

/** The Keybinds header icon: a filled key pad with its keys cut out. */
internal fun HudTestShapes.iconKeyboard(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0.14f, 1f, 0.86f) { gx, gy, unit ->
        roundRect(gx, gy + 0.14f * unit, unit, 0.72f * unit, 0.20f * unit, argb)
        val key = 0.13f * unit
        for (index in 0 until 3) {
            rect(gx + (0.20f + index * 0.24f) * unit, gy + 0.34f * unit, key, key, PUNCH)
        }
        rect(gx + 0.28f * unit, gy + 0.58f * unit, 0.44f * unit, key, PUNCH)
    }

/** The Cooldowns header icon. */
internal fun HudTestShapes.iconClock(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0f, 1f, 1f) { gx, gy, unit ->
        val cx = gx + unit * 0.5f
        val cy = gy + unit * 0.5f
        disc(cx, cy, unit * 0.5f, argb)
        stroke(cx, cy, cx, gy + 0.20f * unit, 0.13f * unit, PUNCH)
        stroke(cx, cy, gx + 0.76f * unit, cy, 0.13f * unit, PUNCH)
    }

/** The Settings popover header icon. */
internal fun HudTestShapes.iconGear(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0f, 1f, 1f) { gx, gy, unit ->
        val cx = gx + unit * 0.5f
        val cy = gy + unit * 0.5f
        for (tooth in 0 until 6) {
            val angle = Math.toRadians(tooth * 60.0)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            stroke(
                cx + dx * 0.20f * unit, cy + dy * 0.20f * unit,
                cx + dx * 0.50f * unit, cy + dy * 0.50f * unit,
                0.28f * unit, argb,
            )
        }
        disc(cx, cy, 0.34f * unit, argb)
        disc(cx, cy, 0.13f * unit, PUNCH)
    }

/** The Coords marker: a compass rose. */
internal fun HudTestShapes.iconCompass(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0f, 1f, 1f) { gx, gy, unit ->
        val cx = gx + unit * 0.5f
        val cy = gy + unit * 0.5f
        disc(cx, cy, unit * 0.5f, argb)
        polygon(
            floatArrayOf(
                cx, gy + 0.16f * unit,
                gx + 0.68f * unit, cy,
                cx, gy + 0.84f * unit,
                gx + 0.32f * unit, cy,
            ),
            PUNCH,
        )
        disc(cx, cy, 0.09f * unit, argb)
    }

/**
 * The "open the settings" affordance: three dots. Drawn rather than typeset, because Inter's
 * ellipsis glyph spaces its dots differently from the reference.
 *
 * Unlike the marks above this one is laid out by the caller against a text run rather than dropped
 * into an icon slot, so [y] is its centre line and [width] its full run — no fit is applied.
 */
internal fun HudTestShapes.iconEllipsis(x: Float, y: Float, width: Float, argb: Int) {
    val radius = width * 0.10f
    val pitch = (width - radius * 2f) * 0.5f
    for (index in 0 until 3) {
        disc(x + radius + index * pitch, y, radius, argb)
    }
}

/** The "close the popover" affordance. */
internal fun HudTestShapes.iconClose(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, -0.065f, -0.065f, 1.065f, 1.065f) { gx, gy, unit ->
        val thickness = unit * 0.13f
        stroke(gx, gy, gx + unit, gy + unit, thickness, argb)
        stroke(gx + unit, gy, gx, gy + unit, thickness, argb)
    }
