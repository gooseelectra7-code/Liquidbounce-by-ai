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

package net.ccbluex.liquidbounce.features.module.modules.render.trajectories

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.Locale

object TrajectoryDetailedInfoRenderer : ToggleableValueGroup(
    ModuleTrajectories, "TimeLabel", true, aliases = listOf("ShowDetailedInfo")
) {
    private val otherThrown by boolean("OtherPlayersThrown", true)
    private val ownThrown by boolean("MyThrown", false)
    private val heldPreview by boolean("HeldItemPreview", false)
    private val scale by float("Scale", 0.82F, 0.5F..2F)
    private val color by color("TextColor", Color4b.WHITE)
    private val background by color("Background", Color4b(13, 14, 20, 205))
    private val border by color("Border", Color4b(145, 120, 255, 115))
    private val renderOffset by vec3d("RenderOffset", useLocateButton = false)
    private fun Vec3.calcScreenPosWithOffset(): Vec3f? {
        return WorldToScreen.calculateScreenPos(add(renderOffset))
    }

    private val fontRenderer get() = FontManager.FONT_RENDERER

    val renderHandler = handler<OverlayRenderEvent> { event ->
        with(event.context) {
            ModuleTrajectories.simulationResults.forEach { (renderer, result) ->
                when (renderer.type) {
                    TrajectoryInfoRenderer.Type.HYPOTHETICAL -> if (!heldPreview) return@forEach
                    TrajectoryInfoRenderer.Type.REAL -> {
                        val owner = renderer.owner as? Player ?: return@forEach
                        if (owner === mc.player && !ownThrown || owner !== mc.player && !otherThrown) {
                            return@forEach
                        }
                    }
                }
                val landing = result.hitResult?.location ?: result.positions.lastOrNull() ?: return@forEach
                val screenPos = landing.calcScreenPosWithOffset() ?: return@forEach
                val seconds = result.ticksToImpact * 0.05F
                val timeText = String.format(Locale.ROOT, "%.1fs", seconds).asPlainText()
                val alpha = result.displayAlpha
                val processedText = fontRenderer.process(timeText, color.fade(alpha))
                val fontScale = fontRenderer.scaleToVanillaFont
                val textWidth = fontRenderer.getStringWidth(processedText, shadow = false) * fontScale
                val pillWidth = 7F + 12F + 4F + textWidth + 7F
                val pillHeight = 18F

                pose().pushMatrix()
                pose().translate(screenPos.x, screenPos.y)
                pose().scale(scale)

                drawRoundedRect(
                    -pillWidth * 0.5F,
                    -pillHeight * 0.5F,
                    pillWidth * 0.5F,
                    pillHeight * 0.5F,
                    6F,
                    background.fade(alpha),
                    border.fade(alpha),
                    0.75F,
                )

                pose().pushMatrix()
                pose().translate(-pillWidth * 0.5F + 5F, -6F)
                pose().scale(0.75F)
                item(renderer.icon, 0, 0)
                pose().popMatrix()

                pose().pushMatrix()
                pose().translate(-pillWidth * 0.5F + 23F, -fontRenderer.height * fontScale * 0.5F)
                pose().scale(fontScale)
                fontRenderer.draw(processedText) { shadow = true }
                pose().popMatrix()

                pose().popMatrix()
            }
        }
    }
}
