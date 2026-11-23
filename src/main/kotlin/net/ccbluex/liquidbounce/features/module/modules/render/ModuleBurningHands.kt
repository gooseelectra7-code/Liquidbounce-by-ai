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

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.burninghands.BurningHandsRenderer
import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * Module BurningHands
 *
 * Ported from the exploit client (`exploit.lon.client.modules.impl.render.BurningHands`).
 * Render-only: draws a fire glow and a flame trail around the first-person hands and
 * held items. Sends no packets — no anticheat surface.
 *
 * The hands are rasterized a second time into an offscreen mask FBO (via
 * [net.ccbluex.liquidbounce.injection.mixins.minecraft.render.MixinGameRendererBurningHands]),
 * then a glow -> trail -> composite shader chain ([BurningHandsRenderer]) blends the fire
 * effect onto the main framebuffer.
 */
object ModuleBurningHands : ClientModule("BurningHands", ModuleCategories.RENDER) {

    val fireColor by color("FireColor", Color4b(255, 112, 32, 255))

    val radius by float("Radius", 15f, 4f..30f)

    val strength by float("Strength", 1.18f, 0.2f..2f)

    val speed by float("Speed", 1.1f, 0.2f..3f)

    val colorMix by float("ColorMix", 0f, 0f..1f)

    val trailStrength by float("TrailStrength", 1.16f, 0f..2f)

    val flameHeight by float("FlameHeight", 28f, 8f..56f)

    val fillHands by boolean("FillHands", false)

    /**
     * Resolution of the glow and trail targets, as a fraction of the window size. The glow
     * pass is by far the most expensive one (72 samples per pixel), and both passes produce
     * soft, low-frequency output that survives being upscaled by the composite's linear
     * sampler. Halving this quarters their pixel count. The hand mask stays at full
     * resolution — the composite reads it for the sharp silhouette gate.
     */
    val renderScale by float("RenderScale", 0.5f, 0.25f..1f)

    override fun onEnabled() {
        BurningHandsRenderer.reset()
    }

    override fun onDisabled() {
        BurningHandsRenderer.release()
    }
}
