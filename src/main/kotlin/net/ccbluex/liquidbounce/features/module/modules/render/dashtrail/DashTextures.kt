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
package net.ccbluex.liquidbounce.features.module.modules.render.dashtrail

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * A dash sprite plus the pixel size it was authored at.
 *
 * The size matters: the sprites are not square, and the original sized every quad from the source
 * resolution, so a wide smear stays wide instead of being squashed into a square.
 */
class DashSprite(val texture: AbstractTexture, val width: Int, val height: Int)

/**
 * The 135 dash sprites, loaded on first use.
 *
 * Loading is deliberately lazy: this is well over a hundred GPU textures, and someone who never
 * turns [ModuleDashTrail] on should not pay for them.
 */
object DashTextures {

    private const val STATIC_COUNT = 21

    /** Frame counts of the five animation groups, in order. */
    private val ANIMATED_FRAME_COUNTS = intArrayOf(11, 23, 32, 16, 32)

    val static: List<DashSprite> by lazy(LazyThreadSafetyMode.NONE) {
        (1..STATIC_COUNT).map { load("textures/dashtrail/static/dashcubic$it.png", "static $it") }
    }

    val animated: List<List<DashSprite>> by lazy(LazyThreadSafetyMode.NONE) {
        ANIMATED_FRAME_COUNTS.mapIndexed { index, frames ->
            val group = index + 1
            (1..frames).map { load("textures/dashtrail/anim/group$group/dashcubic$it.png", "anim $group/$it") }
        }
    }

    val bloom: DashSprite by lazy(LazyThreadSafetyMode.NONE) {
        load("textures/dashtrail/bloom.png", "bloom")
    }

    private fun load(path: String, label: String): DashSprite {
        val image = LiquidBounce.resource(path).readNativeImage()
        // Read the size before handing the image to the texture, which takes ownership of it.
        val width = image.width
        val height = image.height
        return DashSprite(image.asTexture { "DashTrail $label" }, width, height)
    }

}
