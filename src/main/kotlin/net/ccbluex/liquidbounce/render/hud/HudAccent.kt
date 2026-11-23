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

package net.ccbluex.liquidbounce.render.hud

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Screen-position *and* time dependent accent colors — the "flowing theme" math of the Rice/Rise
 * `Themes` class, reimplemented on top of [Color4b].
 *
 * Where [HudEffects] animates purely over time (every element in phase), this one also folds the
 * element's screen position into the phase, so a vertical stack of rows (the ArrayList) shows a
 * gradient that slowly travels down the list instead of all rows pulsing together.
 *
 * All functions are pure functions of the wall clock, so nothing has to be ticked or stored.
 */
object HudAccent {

    /** Phase speed: one full sine cycle every `2 * PI * 600 ms` (~3.8 s). */
    private const val TIME_DIVISOR = 600.0

    /** How strongly the horizontal / vertical screen position shifts the phase (radians per pixel). */
    private const val X_INFLUENCE = 0.005
    private const val Y_INFLUENCE = 0.06

    /** Rainbow hue cycle: `ceil(ms / 10) % 360` degrees, i.e. one full turn every 3.6 s. */
    private const val RAINBOW_STEP_MS = 10.0
    private const val HUE_DEGREES = 360.0

    /**
     * Blend factor in `[0,1]` for the point ([x], [y]) — a slow sine that travels down the screen.
     * Feed it to [mix] / [accent] to color a stack of HUD rows with one flowing gradient.
     */
    @JvmOverloads
    fun blendFactor(x: Float = 0f, y: Float = 0f): Float {
        val phase = System.currentTimeMillis() / TIME_DIVISOR + x * X_INFLUENCE + y * Y_INFLUENCE
        return (sin(phase) * 0.5 + 0.5).toFloat()
    }

    /**
     * `first * percent + second * (1 - percent)` — note the inverted argument order compared to
     * [Color4b.interpolateTo]: at `percent = 1` this is [first], at `percent = 0` it is [second].
     */
    fun mix(first: Color4b, second: Color4b, percent: Float): Color4b =
        second.interpolateTo(first, percent.toDouble().coerceIn(0.0, 1.0))

    /**
     * Three-stop ramp [first] -> [second] -> [third] driven by [blendFactor] at ([x], [y]).
     * Pass the same color twice for a plain two-color theme.
     */
    @JvmOverloads
    fun accent(
        first: Color4b,
        second: Color4b,
        third: Color4b,
        x: Float = 0f,
        y: Float = 0f,
    ): Color4b {
        val factor = blendFactor(x, y)
        return if (factor <= 0.5f) {
            mix(second, first, factor * 2f)
        } else {
            mix(third, second, (factor - 0.5f) * 2f)
        }
    }

    /**
     * [color] with its hue rotated by [delta] turns (0.12 ~= 43 degrees), saturation, brightness and
     * alpha untouched. Lets a single theme accent generate the second stop of a gradient, so a HUD
     * that only stores one color still gets a two-tone [accent] ramp.
     */
    fun shiftHue(color: Color4b, delta: Float): Color4b {
        val hsb = Color.RGBtoHSB(color.r, color.g, color.b, null)
        val hue = (((hsb[0] + delta) % 1f) + 1f) % 1f
        return Color4b.ofHSB(hue, hsb[1], hsb[2], color.a / 255f)
    }

    /**
     * Rice's rainbow — a 3.6 s hue cycle at reduced saturation (pastel, not neon), offset by
     * [delayMs] so neighbouring rows can trail each other.
     */
    @JvmOverloads
    fun rainbow(
        delayMs: Int = 0,
        saturation: Float = 0.6f,
        brightness: Float = 1f,
        alpha: Float = 1f,
    ): Color4b {
        val state = ceil((System.currentTimeMillis() + delayMs) / RAINBOW_STEP_MS) % HUE_DEGREES
        return Color4b.ofHSB((state / HUE_DEGREES).toFloat(), saturation, brightness, alpha)
    }
}
