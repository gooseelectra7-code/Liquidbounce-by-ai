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
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import kotlin.math.hypot

/**
 * Speedometer module
 *
 * A native (in-game, not the browser HUD) readout of the player's average horizontal speed in blocks
 * per second, for tuning movement cheats against the server's real caps. Styled like the hellhit HUD:
 * a plain shadowed value, no chrome.
 *
 * The number is measured from the actual per-tick horizontal position delta - the same thing a
 * threshold anti-cheat sees - and averaged over a short window so it reads steady. Ground and air are
 * shown separately because their limits differ.
 */
object ModuleSpeedometer : ClientModule("Speedometer", ModuleCategories.RENDER) {

    private const val TICKS_PER_SECOND = 20.0

    private val window by int("Window", 20, 1..100, "ticks")
    private val hudX by int("X", 4, 0..4000)
    private val hudY by int("Y", 40, 0..4000)
    private val valueColor by color("Color", Color4b(0xFFFFFFFF.toInt()))
    private val showState by boolean("ShowGroundAir", true)

    private var lastX = 0.0
    private var lastZ = 0.0
    private var hasLast = false

    private val samples = ArrayDeque<Double>()
    private var sum = 0.0

    /** true = the last measured tick was on ground. */
    private var onGroundNow = true

    override fun onEnabled() {
        reset()
    }

    override fun onDisabled() {
        reset()
    }

    private fun reset() {
        hasLast = false
        samples.clear()
        sum = 0.0
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        // Measure once per tick, after the movement is committed, from the real position delta.
        if (event.state != EventState.POST) {
            return@handler
        }

        val x = player.x
        val z = player.z

        if (hasLast) {
            val dist = hypot(x - lastX, z - lastZ)
            samples.addLast(dist)
            sum += dist
            while (samples.size > window) {
                sum -= samples.removeFirst()
            }
        }

        lastX = x
        lastZ = z
        hasLast = true
        onGroundNow = player.onGround()
    }

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> {
        val font = FontManager.FONT_RENDERER
        val vanillaScale = font.scaleToVanillaFont

        val perTick = if (samples.isEmpty()) 0.0 else sum / samples.size
        val blocksPerSecond = perTick * TICKS_PER_SECOND

        val state = if (!showState) "" else if (onGroundNow) "  ground" else "  air"
        val text = ("%.2f b/s%s".format(blocksPerSecond, state))
            .asPlainText(Style.EMPTY.withColor(TextColor.fromRgb(valueColor.argb and 0xFFFFFF)))

        with(it.context) {
            font.draw(text) {
                x = hudX.toFloat()
                y = hudY.toFloat()
                shadow = true
                scale = vanillaScale
            }
        }
    }
}
