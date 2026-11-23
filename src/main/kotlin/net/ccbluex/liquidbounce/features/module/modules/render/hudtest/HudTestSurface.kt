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

import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGlass
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGuiElement
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.ccbluex.liquidbounce.features.module.modules.render.watermarkBounds
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix3x2f

/**
 * The bridge between the HudTest panels and the vanilla GUI render state: every draw call the
 * panels make goes through one of these three primitives — a batch of untextured shapes, a
 * textured quad, or a run of text.
 *
 * Nothing here registers a render pipeline of its own; `RenderPipelines.GUI` and
 * `RenderPipelines.GUI_TEXTURED` are used as-is, because the pipeline registry is frozen long
 * before a module could add to it.
 */

/**
 * Puts the pose into a card's local space: (0, 0) becomes the card's top-left **native pixel**, so
 * every number the spec gives can be used verbatim. The card's appear animation scales about its
 * own centre, which is why the translation is done centre-first.
 */
internal fun GuiGraphicsExtractor.pushFrame(frame: HudTestFrame) {
    pose().pushMatrix()
    pose().translate(
        frame.x + frame.width * frame.unit * 0.5f,
        frame.y + frame.height * frame.unit * 0.5f,
    )
    val scaled = frame.guiUnit
    pose().scale(scaled, scaled)
    pose().translate(-frame.width * 0.5f, -frame.height * 0.5f)
}

/** Pops the matrix [pushFrame] pushed. */
internal fun GuiGraphicsExtractor.popFrame() {
    pose().popMatrix()
}

/**
 * Opens one batched, untextured GUI element covering the local rectangle
 * ([x0], [y0]) .. ([x1], [y1]) and lets [block] paint into it. Everything painted inside a single
 * call ends up in a single draw call, which is why panels build a whole card in one go.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hudShapes(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    density: Float,
    block: HudTestShapes.() -> Unit,
) {
    val poseSnapshot = Matrix3x2f(pose())
    guiRenderState.addGuiElement(
        WatermarkGuiElement(
            RenderPipelines.GUI,
            TextureSetup.noTexture(),
            poseSnapshot,
            scissorStack.peek(),
            watermarkBounds(x0, y0, x1, y1),
        ) { pose ->
            HudTestShapes(this, pose, density).block()
        }
    )
}

/** A single textured quad in local space, sampling [setup] over the given UV rectangle. */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hudTexturedQuad(
    setup: TextureSetup,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    tint: Int,
    uv: FloatArray = UNIT_UV,
) {
    if (width <= 0f || height <= 0f) {
        return
    }
    guiRenderState.addGuiElement(
        WatermarkGuiElement(
            RenderPipelines.GUI_TEXTURED,
            setup,
            Matrix3x2f(pose()),
            scissorStack.peek(),
            watermarkBounds(x, y, x + width, y + height),
        ) { pose ->
            addVertexWith2DPose(pose, x, y).setUv(uv[0], uv[1]).setColor(tint)
            addVertexWith2DPose(pose, x, y + height).setUv(uv[0], uv[3]).setColor(tint)
            addVertexWith2DPose(pose, x + width, y + height).setUv(uv[2], uv[3]).setColor(tint)
            addVertexWith2DPose(pose, x + width, y).setUv(uv[2], uv[1]).setColor(tint)
        }
    )
}

/** `u0, v0, u1, v1` covering a whole texture. */
private val UNIT_UV = floatArrayOf(0f, 0f, 1f, 1f)

/**
 * The blurred frame sampled inside the panel's rounded shape — real glassmorphism rather than a
 * flat tint. Silently does nothing when the blur capture is unavailable.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hudGlassFill(
    glass: TextureSetup,
    width: Float,
    height: Float,
    radius: Float,
    alpha: Float,
) {
    val textureWidth = WatermarkGlass.textureWidth.toFloat()
    val textureHeight = WatermarkGlass.textureHeight.toFloat()
    if (textureWidth <= 0f || textureHeight <= 0f) {
        return
    }

    val poseSnapshot = Matrix3x2f(pose())
    val guiScale = mc.window.guiScale.toFloat()
    // local -> device pixel -> UV; the render target texture is y-flipped
    val uAt = { lx: Float -> (poseSnapshot.m00 * lx + poseSnapshot.m20) * guiScale / textureWidth }
    val vAt = { ly: Float -> 1f - (poseSnapshot.m11 * ly + poseSnapshot.m21) * guiScale / textureHeight }
    val tint = scaleAlpha(-1, alpha)
    val edge = HudTestGeometry.contour(0f, 0f, width, height, radius, 0f)

    guiRenderState.addGuiElement(
        WatermarkGuiElement(
            RenderPipelines.GUI_TEXTURED,
            glass,
            poseSnapshot,
            scissorStack.peek(),
            watermarkBounds(0f, 0f, width, height),
        ) { pose ->
            HudTestGeometry.fan(edge, width * 0.5f, height * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setUv(uAt(vx), vAt(vy)).setColor(tint)
            }
        }
    )
}

/**
 * A complete card: the blurred glass, the one-pixel contact edge and the `#0F0F0F` body with its
 * antialiasing feather. The card has no visible border and no drop shadow — the reference has
 * neither; see [HudTestShapes.contactEdge] for the measurement behind that.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hudCard(
    width: Float,
    height: Float,
    radius: Float,
    alpha: Float,
    density: Float,
    glass: TextureSetup?,
    bodyArgb: Int = HudTestTheme.CARD,
) {
    if (glass != null) {
        hudGlassFill(glass, width, height, radius, alpha)
    }
    // The body keeps the reference's measured alpha (245) rather than being thinned out for the
    // glass: the reference cards really are near-opaque, and the blur only shows through the last
    // few percent and along the antialiased rounded edge.
    val body = scaleAlpha(bodyArgb, alpha)
    hudShapes(-EDGE_MARGIN, -EDGE_MARGIN, width + EDGE_MARGIN, height + EDGE_MARGIN, density) {
        contactEdge(width, height, radius, CONTACT_OPACITY * alpha)
        roundRect(0f, 0f, width, height, radius, body)
    }
}

/** Room for the contact edge, which never reaches further than two device pixels out. */
private const val EDGE_MARGIN = 3f

/** Measured off the reference: the pixel outside the card lands near black on any background. */
private const val CONTACT_OPACITY = 0.55f

/** Submits [text] with its line box starting at ([x], [yTop]) in a flat color. */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hudText(
    font: WatermarkFont,
    text: String,
    x: Float,
    yTop: Float,
    px: Float,
    density: Float,
    argb: Int,
) {
    if (text.isEmpty() || (argb ushr 24) == 0) {
        return
    }
    font.submit(this, text, x, yTop, px, density) { argb }
}

/**
 * The line-box top that vertically centres [font] at [px] inside the band
 * `[bandY, bandY + bandHeight]`. Every row in the shared list-panel system is placed this way.
 */
@Suppress("LongParameterList")
internal fun textTopCentered(
    font: WatermarkFont,
    px: Float,
    density: Float,
    bandY: Float,
    bandHeight: Float,
): Float = bandY + (bandHeight - font.lineHeight(px, density)) * 0.5f

/**
 * The line-box top that puts [font] at [px] on the shared [baseline]. Runs of different sizes
 * (a value and its unit suffix) must be placed this way or their bottoms drift apart.
 */
internal fun textTopOnBaseline(font: WatermarkFont, px: Float, density: Float, baseline: Float): Float =
    baseline - font.ascent(px, density)

/** The baseline a run placed with its line box at [yTop] ends up on. */
internal fun textBaseline(font: WatermarkFont, px: Float, density: Float, yTop: Float): Float =
    yTop + font.ascent(px, density)

/**
 * [text] cut down until it fits [maxWidth], with an ellipsis marking the cut.
 *
 * Panels here are a fixed 188 wide with a value pinned to the right edge, so a label that is
 * allowed to run at its natural width will sooner or later be drawn straight through that value —
 * a sixteen-character nickname next to `20hp`, `Totem of Undying` next to `58s`. Every label that
 * shares its row with something else goes through this.
 *
 * The ellipsis is three ASCII dots on purpose: [WatermarkFont] only bakes ASCII and Cyrillic, so a
 * real `U+2026` would come out of the atlas as the missing-glyph `?`.
 */
@Suppress("LongParameterList")
internal fun fitText(font: WatermarkFont, text: String, px: Float, density: Float, maxWidth: Float): String {
    if (maxWidth <= 0f) {
        return ""
    }
    if (font.width(text, px, density) <= maxWidth) {
        return text
    }
    val ellipsisWidth = font.width(ELLIPSIS, px, density)
    if (ellipsisWidth > maxWidth) {
        return ""
    }
    var end = text.length - 1
    while (end > 0 && font.width(text.substring(0, end), px, density) + ellipsisWidth > maxWidth) {
        end--
    }
    return text.take(end) + ELLIPSIS
}

private const val ELLIPSIS = "..."
