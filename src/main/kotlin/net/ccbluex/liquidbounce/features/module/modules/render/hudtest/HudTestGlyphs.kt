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

import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.features.module.modules.render.lerpArgb

/**
 * The interactive and per-entry glyphs: the media transport buttons, the settings toggle and the
 * notification severity marker.
 *
 * Like the icon set they are fitted into their slot through [fitGlyph], so a play triangle and a
 * skip button carry the same optical weight even though the triangle is the narrower shape.
 */

/** Media transport: skip to the previous track. */
internal fun HudTestShapes.glyphPrevious(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0f, 1f, 1f) { gx, gy, unit ->
        rect(gx, gy + 0.06f * unit, 0.16f * unit, 0.88f * unit, argb)
        triangle(gx + unit, gy, gx + 0.26f * unit, gy + 0.5f * unit, gx + unit, gy + unit, argb)
    }

/** Media transport: skip to the next track. */
internal fun HudTestShapes.glyphNext(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0f, 0f, 1f, 1f) { gx, gy, unit ->
        rect(gx + 0.84f * unit, gy + 0.06f * unit, 0.16f * unit, 0.88f * unit, argb)
        triangle(gx, gy, gx, gy + unit, gx + 0.74f * unit, gy + 0.5f * unit, argb)
    }

/** Media transport: resume playback. */
internal fun HudTestShapes.glyphPlay(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0.08f, 0f, 0.92f, 1f) { gx, gy, unit ->
        triangle(gx + 0.08f * unit, gy, gx + 0.08f * unit, gy + unit, gx + 0.92f * unit, gy + 0.5f * unit, argb)
    }

/** Media transport: pause playback. */
internal fun HudTestShapes.glyphPause(x: Float, y: Float, size: Float, argb: Int) =
    fitGlyph(x, y, size, 0.06f, 0f, 0.94f, 1f) { gx, gy, unit ->
        val barWidth = 0.30f * unit
        roundRect(gx + 0.06f * unit, gy, barWidth, unit, barWidth * 0.25f, argb)
        roundRect(gx + 0.64f * unit, gy, barWidth, unit, barWidth * 0.25f, argb)
    }

/**
 * The settings row toggle: a 21x12 pill with a white knob. [knob] is the animated position,
 * `0` fully off and `1` fully on, and it drives both the knob travel and the track color.
 *
 * This one is sized by its row rather than dropped into a square icon slot, so it is laid out
 * directly instead of going through [fitGlyph].
 */
@Suppress("LongParameterList")
internal fun HudTestShapes.glyphToggle(x: Float, y: Float, width: Float, height: Float, knob: Float) {
    val eased = knob.coerceIn(0f, 1f)
    val track = lerpArgb(HudTestTheme.TOGGLE_OFF, HudTestTheme.ACCENT, eased)
    roundRect(x, y, width, height, height * 0.5f, track)

    val inset = 1f
    val knobRadius = (height - inset * 2f) * 0.5f
    val travel = width - inset * 2f - knobRadius * 2f
    disc(x + inset + knobRadius + travel * eased, y + height * 0.5f, knobRadius, HudTestTheme.WHITE)
}

/** The notification well marker, chosen by the event's severity. */
internal fun HudTestShapes.glyphSeverity(
    severity: NotificationEvent.Severity,
    x: Float,
    y: Float,
    size: Float,
    argb: Int,
) {
    when (severity) {
        NotificationEvent.Severity.SUCCESS, NotificationEvent.Severity.ENABLED ->
            fitGlyph(x, y, size, -0.005f, 0.12f, 1.01f, 0.905f) { gx, gy, unit ->
                stroke(gx + 0.06f * unit, gy + 0.52f * unit, gx + 0.38f * unit, gy + 0.84f * unit, 0.18f * unit, argb)
                stroke(gx + 0.38f * unit, gy + 0.84f * unit, gx + 0.94f * unit, gy + 0.18f * unit, 0.18f * unit, argb)
            }
        NotificationEvent.Severity.ERROR -> iconClose(x, y, size, argb)
        NotificationEvent.Severity.DISABLED ->
            fitGlyph(x, y, size, 0f, 0.44f, 1f, 0.58f) { gx, gy, unit ->
                rect(gx, gy + 0.44f * unit, unit, 0.14f * unit, argb)
            }
        NotificationEvent.Severity.INFO ->
            fitGlyph(x, y, size, 0.39f, 0.03f, 0.61f, 0.86f) { gx, gy, unit ->
                disc(gx + 0.5f * unit, gy + 0.14f * unit, 0.11f * unit, argb)
                roundRect(gx + 0.39f * unit, gy + 0.36f * unit, 0.22f * unit, 0.5f * unit, 0.11f * unit, argb)
            }
    }
}
