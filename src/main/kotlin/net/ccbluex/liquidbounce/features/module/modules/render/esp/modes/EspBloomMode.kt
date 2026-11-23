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
package net.ccbluex.liquidbounce.features.module.modules.render.esp.modes

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP.getColor
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawGradientSides
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/**
 * Ported visual from hawen-output `Esp.java` 3D-Box / Bloom — soft halo around the entity bbox
 * plus an optional vertical color gradient on the side faces. The halo is faked by stacking a few
 * concentric inflated translucent boxes (cheap and correct on the blaze3d pipeline; no screen-space
 * shader required).
 */
object EspBloomMode : EspMode.BoxBased("Bloom") {

    private object Halo : ToggleableValueGroup(this, "Halo", true) {
        val layers by int("Layers", 4, 1..8)
        val spread by float("Spread", 0.32f, 0.05f..1.0f, "blocks")
        val intensity by float("Intensity", 0.55f, 0.05f..1.0f)
    }

    private object Gradient : ToggleableValueGroup(this, "Gradient", true) {
        val fadeFromTop by boolean("FadeFromTop", true)
        val baseAlpha by int("BaseAlpha", 22, 0..255)
        val topAlpha by int("TopAlpha", 110, 0..255)
    }

    private object Outline : ToggleableValueGroup(this, "Outline", true) {
        val alpha by int("Alpha", 220, 0..255)
    }

    init {
        tree(Halo)
        tree(Gradient)
        tree(Outline)
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        renderEnvironmentForWorld(event.matrixStack) {
            for ((entity, localBox, position) in collectPreparedBoxes(event.partialTicks)) {
                val color = getColor(entity)

                withPositionRelativeToCamera(position) {
                    if (Halo.enabled) {
                        drawHalo(localBox, color)
                    }

                    if (Gradient.enabled) {
                        drawGradient(localBox, color)
                    }

                    if (Outline.enabled) {
                        drawBox(localBox, faceColor = null, outlineColor = color.with(a = Outline.alpha))
                    }
                }
            }
        }
    }

    /**
     * Draws this mode for a single entity at the CURRENT pose origin — the ClickGUI visuals
     * preview positions the pose itself, so there is no camera-relative translation.
     *
     * Calls the same Halo/Gradient/Outline drawing the world path calls, so the preview shows
     * those groups exactly as they are configured.
     */
    internal fun WorldRenderEnvironment.drawPreview(entity: LivingEntity, localBox: AABB) {
        val color = getColor(entity)

        if (Halo.enabled) {
            drawHalo(localBox, color)
        }

        if (Gradient.enabled) {
            drawGradient(localBox, color)
        }

        if (Outline.enabled) {
            drawBox(localBox, faceColor = null, outlineColor = color.with(a = Outline.alpha))
        }
    }

    private fun WorldRenderEnvironment.drawHalo(box: AABB, color: Color4b) {
        val layers = Halo.layers
        val spread = Halo.spread.toDouble()
        val intensity = Halo.intensity

        // Outer layers are dimmest; inner layers tighter and brighter. The exponent gives a
        // soft falloff that reads like a true bloom rather than discrete rings.
        for (i in 0 until layers) {
            val t = (i + 1).toFloat() / layers
            val inflate = spread * (1.0 - t * t * 0.6)
            val alpha = (intensity * 70f * (1f - t).let { it * it }).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            drawBox(box.inflate(inflate), faceColor = color.with(a = alpha), outlineColor = null)
        }
    }

    private fun WorldRenderEnvironment.drawGradient(box: AABB, color: Color4b) {
        val height = box.maxY - box.minY
        if (height <= 0.0) return

        // drawGradientSides hardcodes the bottom edge to y=0 and uses the height parameter as the
        // top. We translate the local pose down to box.minY so the gradient spans the entity's
        // actual vertical extent.
        val low = color.with(a = Gradient.baseAlpha)
        val high = color.with(a = Gradient.topAlpha)
        val (bottom, top) = if (Gradient.fadeFromTop) high to low else low to high

        poseStack.pushPose()
        poseStack.translate(0.0, box.minY, 0.0)
        drawGradientSides(height, baseColor = bottom, topColor = top, box = box)
        poseStack.popPose()
    }
}
