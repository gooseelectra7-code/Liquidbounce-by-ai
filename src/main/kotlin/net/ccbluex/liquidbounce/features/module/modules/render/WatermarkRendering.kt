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

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import org.joml.Vector2f
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Self-contained [GuiElementRenderState] used by the watermark renderer: a vertex builder lambda
 * plus the pipeline, texture, pose snapshot, scissor and bounds it should be drawn with.
 * Built directly on the vanilla GUI render pipeline, independent of the client's render utilities.
 */
internal class WatermarkGuiElement(
    private val renderPipeline: RenderPipeline,
    private val texture: TextureSetup,
    private val pose: Matrix3x2f,
    private val scissor: ScreenRectangle?,
    private val elementBounds: ScreenRectangle?,
    private val vertexBuilder: VertexConsumer.(Matrix3x2f) -> Unit,
) : GuiElementRenderState {
    override fun buildVertices(vertices: VertexConsumer) = vertices.vertexBuilder(pose)
    override fun pipeline(): RenderPipeline = renderPipeline
    override fun textureSetup(): TextureSetup = texture
    override fun scissorArea(): ScreenRectangle? = scissor
    override fun bounds(): ScreenRectangle? = elementBounds
}

/** Transforms the rectangle's corners by the current pose and clips against the active scissor. */
internal fun GuiGraphicsExtractor.watermarkBounds(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
): ScreenRectangle? {
    val pose = pose()
    val point = Vector2f()
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    for ((cornerX, cornerY) in arrayOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
        pose.transformPosition(cornerX, cornerY, point)
        minX = minOf(minX, point.x)
        minY = minOf(minY, point.y)
        maxX = maxOf(maxX, point.x)
        maxY = maxOf(maxY, point.y)
    }

    val rect = ScreenRectangle(
        floor(minX).toInt(),
        floor(minY).toInt(),
        ceil(maxX - minX).toInt(),
        ceil(maxY - minY).toInt(),
    )
    return scissorStack.peek()?.intersection(rect) ?: rect
}

/** Per-channel linear interpolation between two ARGB colors. */
internal fun lerpArgb(from: Int, to: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    val a = ((from ushr 24 and 0xFF) + ((to ushr 24 and 0xFF) - (from ushr 24 and 0xFF)) * f).toInt()
    val r = ((from ushr 16 and 0xFF) + ((to ushr 16 and 0xFF) - (from ushr 16 and 0xFF)) * f).toInt()
    val g = ((from ushr 8 and 0xFF) + ((to ushr 8 and 0xFF) - (from ushr 8 and 0xFF)) * f).toInt()
    val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Multiplies only the alpha channel of an ARGB color by [factor]. */
internal fun scaleAlpha(argb: Int, factor: Float): Int {
    val a = ((argb ushr 24 and 0xFF) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0xFFFFFF)
}
