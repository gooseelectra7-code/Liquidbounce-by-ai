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
package net.ccbluex.liquidbounce.integration.screen.native.ui

import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.HellhitClickGuiSettings
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import kotlin.math.roundToInt

/**
 * The Hellhit design tokens, shared by the native menu screens. Values mirror the
 * (private) palette of `HellhitClickGuiScreen`; the accent is live from the Themes panel.
 */
object HellhitPalette {
    val panel = Color4b(14, 14, 16, 250)
    val panelBorder = Color4b(255, 255, 255, 15)
    val hairline = Color4b(255, 255, 255, 11)
    val row = Color4b(29, 29, 32, 255)
    val rowHover = Color4b(37, 37, 41, 255)
    val well = Color4b(9, 9, 11, 255)
    val white = Color4b(236, 236, 240, 255)
    val textOff = Color4b(198, 198, 204, 255)
    val desc = Color4b(148, 148, 155, 255)
    val dim = Color4b(102, 102, 110, 255)
    val ink = Color4b(15, 15, 17, 255)
    val rose = Color4b(244, 63, 94, 255)
    val emerald = Color4b(16, 185, 129, 255)

    val accent: Color4b get() = HellhitClickGuiSettings.accent

    const val R_PANEL = 6.5f
    const val R_ROW = 4f
    const val R_CTRL = 3f

    fun Color4b.alphaF(f: Float): Color4b =
        with(a = (a * f.coerceIn(0f, 1f)).toInt().coerceIn(0, 255))

    fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        return Color4b(ch(a.r, b.r), ch(a.g, b.g), ch(a.b, b.b), ch(a.a, b.a))
    }
}
