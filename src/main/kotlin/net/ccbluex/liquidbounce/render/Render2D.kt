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

@file:Suppress("detekt:TooManyFunctions", "NOTHING_TO_INLINE")

package net.ccbluex.liquidbounce.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import it.unimi.dsi.fastutil.floats.Float2IntFunction
import net.ccbluex.liquidbounce.render.engine.type.BoundingBox2f
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.GuiCircleLutAtlas
import net.ccbluex.liquidbounce.utils.math.ceilToInt
import net.ccbluex.liquidbounce.utils.math.floorToInt
import net.ccbluex.liquidbounce.utils.collection.Pools
import net.ccbluex.liquidbounce.render.gui.element.CircleGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.HawenRoundedRectGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.LambdaSimpleGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.LineGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.QuadGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.RoundedRectGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.RoundedRectShadowGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.TexQuadGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.TriangleGuiElementRenderState
import net.ccbluex.liquidbounce.utils.render.VerticesSetupHandler
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.world.phys.Vec2
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fStack
import org.joml.Matrix3x2fc
import org.joml.Vector2f

private val LEFT_TOP = Vector2f()
private val RIGHT_TOP = Vector2f()
private val LEFT_BOTTOM = Vector2f()
private val RIGHT_BOTTOM = Vector2f()

/**
 * Primitive version of [ScreenRectangle.transformMaxBounds]
 */
private fun Matrix3x2fc.transformMaxBounds(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): ScreenRectangle {
    val v1 = transformPosition(left, top, LEFT_TOP)
    val v2 = transformPosition(right, top, RIGHT_TOP)
    val v3 = transformPosition(left, bottom, LEFT_BOTTOM)
    val v4 = transformPosition(right, bottom, RIGHT_BOTTOM)
    val minX = minOf(v1.x, minOf(v3.x, v2.x, v4.x))
    val maxX = maxOf(v1.x, maxOf(v3.x, v2.x, v4.x))
    val minY = minOf(v1.y, minOf(v3.y, v2.y, v4.y))
    val maxY = maxOf(v1.y, maxOf(v3.y, v2.y, v4.y))
    return ScreenRectangle(
        minX.floorToInt(),
        minY.floorToInt(),
        (maxX - minX).ceilToInt(),
        (maxY - minY).ceilToInt(),
    )
}

/**
 * @see net.minecraft.client.gui.render.state.ColoredRectangleRenderState.getBounds
 */
fun GuiGraphicsExtractor.getBounds(left: Float, top: Float, right: Float, bottom: Float): ScreenRectangle {
    val rect = this.pose().transformMaxBounds(left, top, right, bottom)
    return this.scissorStack.peek()?.intersection(rect) ?: rect
}

/**
 * @see net.minecraft.client.gui.render.state.ColoredRectangleRenderState.getBounds
 */
fun GuiGraphicsExtractor.getBoundsXYWH(x: Float, y: Float, w: Float, h: Float): ScreenRectangle {
    return getBounds(x, y, x + w, y + h)
}

fun GuiGraphicsExtractor.getBounds(box: BoundingBox2f): ScreenRectangle =
    getBounds(box.xMin, box.yMin, box.xMax, box.yMax)

inline fun GuiGraphicsExtractor.copyPosePooled(): Matrix3x2f = Pools.Mat3x2f.borrow().set(this.pose())

inline fun GuiGraphicsExtractor.copyPose(): Matrix3x2f = Matrix3x2f(this.pose())

inline fun Matrix3x2fStack.withPush(block: Matrix3x2fStack.() -> Unit) {
    pushMatrix()
    try {
        block()
    } finally {
        popMatrix()
    }
}

inline fun GuiGraphicsExtractor.ScissorStack.withPush(
    rect: ScreenRectangle,
    block: GuiGraphicsExtractor.ScissorStack.() -> Unit,
) {
    push(rect)
    try {
        block()
    } finally {
        pop()
    }
}

inline fun GuiGraphicsExtractor.drawCustomElement(
    pipeline: RenderPipeline = RenderPipelines.GUI, // PosColor + QUADS
    textureSetup: TextureSetup = TextureSetup.noTexture(),
    scissorArea: ScreenRectangle? = this.scissorStack.peek(),
    bounds: ScreenRectangle? = null,
    verticesSetupHandler: VerticesSetupHandler,
) = this.guiRenderState.addGuiElement(
    LambdaSimpleGuiElementRenderState(
        pipeline,
        textureSetup,
        copyPosePooled(),
        scissorArea,
        bounds,
        verticesSetupHandler
    )
)

fun GuiGraphicsExtractor.drawLines(
    points: FloatArray,
    argb: Int,
    bounds: ScreenRectangle,
    cull: Boolean = true,
) {
    this.guiRenderState.addGuiElement(
        LineGuiElementRenderState(
            points,
            argb,
            ClientRenderPipelines.GUI.lines(cull),
            copyPosePooled(),
            this.scissorStack.peek(),
            bounds,
        )
    )
}

fun GuiGraphicsExtractor.drawQuad(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    fillColor: Color4b? = Color4b.TRANSPARENT,
    outlineColor: Color4b? = Color4b.TRANSPARENT,
) {
    val x11 = minOf(x1, x2)
    val y11 = minOf(y1, y2)
    val x21 = maxOf(x1, x2)
    val y21 = maxOf(y1, y2)

    val bounds = getBounds(x11, y11, x21, y21)

    if (fillColor != null && !fillColor.isTransparent) {
        this.guiRenderState.addGuiElement(
            QuadGuiElementRenderState(
                x11,
                y11,
                x21,
                y21,
                fillColor.argb,
                copyPosePooled(),
                this.scissorStack.peek(),
                bounds,
            )
        )
    }
    if (outlineColor != null && !outlineColor.isTransparent) {
        val argb = outlineColor.argb

        drawLines(
            floatArrayOf(
                x11, y11,
                x11, y21,
                x11, y21,
                x21, y21,
                x21, y21,
                x21, y11,
                x21, y11,
                x11, y11,
            ),
            argb,
            bounds,
        )
    }
}

fun GuiGraphicsExtractor.drawRoundedRect(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    radius: Float,
    fillColor: Color4b? = Color4b.TRANSPARENT,
    outlineColor: Color4b? = Color4b.TRANSPARENT,
    outlineWidth: Float = 1.0f,
) {
    val x11 = minOf(x1, x2)
    val y11 = minOf(y1, y2)
    val x21 = maxOf(x1, x2)
    val y21 = maxOf(y1, y2)

    val fill = fillColor ?: Color4b.TRANSPARENT
    val outline = outlineColor ?: Color4b.TRANSPARENT
    if (fill.isTransparent && (outline.isTransparent || outlineWidth <= 0.0f)) {
        return
    }

    this.guiRenderState.addGuiElement(
        RoundedRectGuiElementRenderState(
            x11,
            y11,
            x21,
            y21,
            radius.coerceAtLeast(0.0f),
            fill.argb,
            outline.argb,
            outlineWidth.coerceAtLeast(0.0f),
            copyPosePooled(),
            this.scissorStack.peek(),
            getBounds(x11, y11, x21, y21),
        )
    )
}

inline fun GuiGraphicsExtractor.drawQuadXYWH(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    fillColor: Color4b? = Color4b.TRANSPARENT,
    outlineColor: Color4b? = Color4b.TRANSPARENT,
) = drawQuad(x, y, x + w, y + h, fillColor, outlineColor)

/**
 * Drop-shadow for a rounded rectangle: a blurred rounded silhouette of the rect ([x], [y], [w], [h],
 * corner [radius]) in [color], shifted by ([offsetX], [offsetY]) and fading out over [blur] GUI units
 * past the box edge. It renders the same SDF rounded box as [drawRoundedRect] through a dedicated
 * shadow pipeline ([ClientRenderPipelines.GUI.roundedRectShadow]) into an EXPANDED quad (grown by
 * `blur + slack` on each side), so the antialiased falloff is never scissor-clipped.
 *
 * Draw the shadow FIRST and the opaque fill on top: shadow-then-fill is pixel-identical to a single
 * composite because the opaque fill fully covers the silhouette it overlaps. All four corners share
 * [radius] (uniform), which covers every shadow use.
 *
 * @param blur fade distance past the box edge, in GUI units (0 = a hard-edged offset copy).
 * @see RoundedRectShadowGuiElementRenderState
 */
@Suppress("LongParameterList")
fun GuiGraphicsExtractor.drawRoundedRectShadow(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius: Float,
    color: Color4b,
    blur: Float,
    offsetX: Float = 0.0f,
    offsetY: Float = 0.0f,
) {
    if (color.isTransparent || w <= 0.0f || h <= 0.0f) {
        return
    }

    // Half-expansion per side: the blur reach plus a little slack so the falloff never touches the
    // quad edge (a hard cut there would show as a straight line through the soft shadow).
    val margin = blur.coerceAtLeast(0.0f) + 1.0f

    // Expanded quad = original rect shifted by the shadow offset and grown by `margin` on each side.
    // Its centre coincides with the shadow centre, which is what the shader assumes when it maps
    // UV0 (0..1 over this quad) back to box-local coordinates.
    val ex0 = x + offsetX - margin
    val ey0 = y + offsetY - margin
    val ex1 = x + offsetX + w + margin
    val ey1 = y + offsetY + h + margin

    this.guiRenderState.addGuiElement(
        RoundedRectShadowGuiElementRenderState(
            ex0,
            ey0,
            ex1,
            ey1,
            w,
            h,
            radius.coerceAtLeast(0.0f),
            margin,
            blur.coerceAtLeast(0.0f),
            color.argb,
            copyPose(),
            this.scissorStack.peek(),
            getBounds(ex0, ey0, ex1, ey1),
        )
    )
}

private const val ROUNDED_RECT_MAX_ENCODED = 32767

private fun encodeRoundedRect(value: Float): Int =
    Math.round(value.coerceIn(0.0f, ROUNDED_RECT_MAX_ENCODED.toFloat()))

/**
 * Rounded rectangle with a per-corner color gradient, reusing the SDF rounded-rect pipeline/shader
 * (so corners stay crisp and antialiased). Corner colors are ARGB ints in TL, BL, BR, TR order —
 * animate them per frame (see [net.ccbluex.liquidbounce.render.hud.HudEffects]) for a flowing fill.
 *
 * @see RoundedRectGuiElementRenderState (single-color path this mirrors)
 */
fun GuiGraphicsExtractor.drawGradientRoundedRect(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    radius: Float,
    colorTL: Int,
    colorBL: Int,
    colorBR: Int,
    colorTR: Int,
) {
    val width = x1 - x0
    val height = y1 - y0
    if (width <= 0.0f || height <= 0.0f) {
        return
    }

    val ew = encodeRoundedRect(width)
    val eh = encodeRoundedRect(height)
    val er = encodeRoundedRect(minOf(radius, minOf(width, height) * 0.5f))

    drawCustomElement(
        pipeline = ClientRenderPipelines.GUI.roundedRect(),
        bounds = getBoundsXYWH(x0, y0, width, height),
    ) { pose ->
        addVertexWith2DPose(pose, x0, y0).setUv(0.0f, 0.0f).setColor(colorTL).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
        addVertexWith2DPose(pose, x0, y1).setUv(0.0f, 1.0f).setColor(colorBL).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
        addVertexWith2DPose(pose, x1, y1).setUv(1.0f, 1.0f).setColor(colorBR).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
        addVertexWith2DPose(pose, x1, y0).setUv(1.0f, 0.0f).setColor(colorTR).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
    }
}

/**
 * Rounded rectangle filled by a horizontal sweep: the rect is tessellated into [columns] vertical
 * strips and [colorAt] is sampled at each strip boundary (fraction `0..1` along the width). Because the
 * color pattern can be a function of *both* position and time, this renders gradients that visibly
 * *travel along* the rectangle (a 4-corner gradient can only cycle in place). Still the SDF rounded-rect
 * shader, so corners stay crisp.
 */
fun GuiGraphicsExtractor.drawSweepRoundedRect(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    radius: Float,
    columns: Int = 24,
    colorAt: (fraction: Float) -> Int,
) {
    val width = x1 - x0
    val height = y1 - y0
    if (width <= 0.0f || height <= 0.0f) {
        return
    }

    val cols = columns.coerceAtLeast(1)
    val ew = encodeRoundedRect(width)
    val eh = encodeRoundedRect(height)
    val er = encodeRoundedRect(minOf(radius, minOf(width, height) * 0.5f))

    drawCustomElement(
        pipeline = ClientRenderPipelines.GUI.roundedRect(),
        bounds = getBoundsXYWH(x0, y0, width, height),
    ) { pose ->
        for (i in 0 until cols) {
            val f0 = i.toFloat() / cols
            val f1 = (i + 1).toFloat() / cols
            val xa = x0 + width * f0
            val xb = x0 + width * f1
            val cl = colorAt(f0)
            val cr = colorAt(f1)

            addVertexWith2DPose(pose, xa, y0).setUv(f0, 0.0f).setColor(cl).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
            addVertexWith2DPose(pose, xa, y1).setUv(f0, 1.0f).setColor(cl).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
            addVertexWith2DPose(pose, xb, y1).setUv(f1, 1.0f).setColor(cr).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
            addVertexWith2DPose(pose, xb, y0).setUv(f1, 0.0f).setColor(cr).setUv1(ew, eh).setUv2(er, 0).setLineWidth(0.0f)
        }
    }
}

/**
 * Float version of [GuiGraphicsExtractor.drawHorizontalLine]
 */
fun GuiGraphicsExtractor.drawHorizontalLine(x1: Float, x2: Float, y: Float, thickness: Float, color: Color4b) {
    this.drawQuad(x1, y, x2, y + thickness, color)
}

/**
 * Float version of [GuiGraphicsExtractor.drawVerticalLine]
 */
fun GuiGraphicsExtractor.drawVerticalLine(x: Float, y1: Float, y2: Float, thickness: Float, color: Color4b) {
    this.drawQuad(x, y1, x + thickness, y2, color)
}

/**
 * Hawen-style rounded rectangle with per-corner radii.
 *
 * @param radiusRT Top-right corner radius
 * @param radiusRB Bottom-right corner radius
 * @param radiusLT Top-left corner radius
 * @param radiusLB Bottom-left corner radius
 */
fun GuiGraphicsExtractor.drawHawenRoundedRect(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    radiusRT: Float,
    radiusRB: Float,
    radiusLT: Float,
    radiusLB: Float,
    color: Color4b,
) {
    if (color.isTransparent) {
        return
    }

    val x11 = minOf(x1, x2)
    val y11 = minOf(y1, y2)
    val x21 = maxOf(x1, x2)
    val y21 = maxOf(y1, y2)

    this.guiRenderState.addGuiElement(
        HawenRoundedRectGuiElementRenderState(
            x11,
            y11,
            x21,
            y21,
            radiusRT.coerceAtLeast(0.0f),
            radiusRB.coerceAtLeast(0.0f),
            radiusLT.coerceAtLeast(0.0f),
            radiusLB.coerceAtLeast(0.0f),
            color.argb,
            copyPosePooled(),
            this.scissorStack.peek(),
            getBounds(x11, y11, x21, y21),
        )
    )
}


@Suppress("LongParameterList")
fun GuiGraphicsExtractor.drawTriangle(
    x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float,
    fillColor: Color4b? = Color4b.TRANSPARENT,
    outlineColor: Color4b? = Color4b.TRANSPARENT,
    cull: Boolean = true,
) {
    val minX = minOf(x0, x1, x2)
    val minY = minOf(y0, y1, y2)
    val maxX = maxOf(x0, x1, x2)
    val maxY = maxOf(y0, y1, y2)
    val bounds = getBounds(minX, minY, maxX, maxY)

    if (fillColor != null && !fillColor.isTransparent) {
        this.guiRenderState.addGuiElement(
            TriangleGuiElementRenderState(
                x0, y0, x1, y1, x2, y2,
                fillColor.argb,
                ClientRenderPipelines.GUI.triangles(cull),
                copyPosePooled(),
                this.scissorStack.peek(),
                bounds,
            )
        )
    }

    if (outlineColor != null && !outlineColor.isTransparent) {
        drawLines(
            floatArrayOf(
                x0, y0,
                x1, y1,
                x1, y1,
                x2, y2,
                x2, y2,
                x0, y0,
            ),
            outlineColor.argb,
            bounds,
        )
    }
}

fun GuiGraphicsExtractor.drawTriangle(
    p1: Vec2, p2: Vec2, p3: Vec2,
    fillColor: Color4b? = Color4b.TRANSPARENT,
    outlineColor: Color4b? = Color4b.TRANSPARENT,
) = drawTriangle(
    p1.x, p1.y, p2.x, p2.y, p3.x, p3.y,
    fillColor, outlineColor,
)

@Suppress("LongParameterList")
inline fun GuiGraphicsExtractor.drawGlyphOnCurrentLayer(
    textureSetup: TextureSetup,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    u1: Float = 0f,
    v1: Float = 0f,
    u2: Float = 1f,
    v2: Float = 1f,
    argb: Int = -1,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
) {
    this.guiRenderState.addGlyphToCurrentLayer(
        TexQuadGuiElementRenderState(
            x0,
            y0,
            x1,
            y1,
            u1,
            v1,
            u2,
            v2,
            argb,
            pipeline,
            textureSetup,
            copyPosePooled(),
            this.scissorStack.peek(),
            null,
        )
    )
}

@Suppress("LongParameterList")
inline fun GuiGraphicsExtractor.drawTexQuad(
    textureSetup: TextureSetup,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    u1: Float = 0f,
    v1: Float = 0f,
    u2: Float = 1f,
    v2: Float = 1f,
    argb: Int = -1,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
) {
    this.guiRenderState.addGuiElement(
        TexQuadGuiElementRenderState(
            x0,
            y0,
            x1,
            y1,
            u1,
            v1,
            u2,
            v2,
            argb,
            pipeline,
            textureSetup,
            copyPosePooled(),
            this.scissorStack.peek(),
            getBounds(x0, y0, x1, y1),
        )
    )
}

@Suppress("LongParameterList")
inline fun GuiGraphicsExtractor.drawBlitOnCurrentLayer(
    textureSetup: TextureSetup,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    u1: Float = 0f,
    v1: Float = 0f,
    u2: Float = 1f,
    v2: Float = 1f,
    argb: Int = -1,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED,
) {
    this.guiRenderState.addBlitToCurrentLayer(
        BlitRenderState(
            pipeline,
            textureSetup,
            copyPose(),
            x0,
            y0,
            x1,
            y1,
            u1,
            v1,
            u2,
            v2,
            argb,
            this.scissorStack.peek(),
            null,
        )
    )
}

fun GuiGraphicsExtractor.drawCircle(
    x: Float,
    y: Float,
    radius: Float,
    innerRadius: Float = 0f,
    colorGetter: Float2IntFunction = Float2IntFunction { Color4b.WHITE.argb },
) {
    if (radius <= 0f) {
        return
    }

    val lut = GuiCircleLutAtlas.allocate(colorGetter)
    val innerRatio = (innerRadius / radius).coerceIn(0f, 1f)
    val bounds = getBoundsXYWH(x - radius, y - radius, radius * 2, radius * 2)

    this.guiRenderState.addGuiElement(
        CircleGuiElementRenderState(
            x,
            y,
            radius,
            innerRatio,
            lut.row,
            ClientRenderPipelines.GUI.circleLut(),
            lut.textureSetup,
            copyPosePooled(),
            this.scissorStack.peek(),
            bounds
        )
    )
}
