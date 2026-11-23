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
@file:Suppress("detekt:TooManyFunctions")

package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGlass
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGuiElement
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.ccbluex.liquidbounce.features.module.modules.render.hudtest.HudTestItemIcons
import net.ccbluex.liquidbounce.features.module.modules.render.watermarkBounds
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The bridge between the Hud3 panels and the vanilla GUI render state.
 *
 * Nothing here registers a render pipeline of its own — `RenderPipelines.GUI` and
 * `RenderPipelines.GUI_TEXTURED` are used as-is, because the pipeline registry is frozen long
 * before a module could add to it.
 */

/**
 * Puts the pose into a card's local space: (0, 0) becomes the card's top-left **native pixel**, so
 * every number the Figma spec gives can be used verbatim. The appear animation scales about the
 * card's own centre, which is why the translation is done centre-first.
 */
internal fun GuiGraphicsExtractor.pushFrame(frame: Hud3Frame) {
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
 * call ends up in a single draw call, which is why a panel builds a whole card in one go.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3Shapes(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    density: Float,
    block: Hud3Shapes.() -> Unit,
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
            Hud3Shapes(this, pose, density).block()
        }
    )
}

/** A single textured quad in local space, sampling [setup] over the whole texture. */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3TexturedQuad(
    setup: TextureSetup,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    tint: Int,
) {
    if (width <= 0f || height <= 0f || (tint ushr 24) == 0) {
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
            addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(tint)
            addVertexWith2DPose(pose, x, y + height).setUv(0f, 1f).setColor(tint)
            addVertexWith2DPose(pose, x + width, y + height).setUv(1f, 1f).setColor(tint)
            addVertexWith2DPose(pose, x + width, y).setUv(1f, 0f).setColor(tint)
        }
    )
}

/**
 * The blurred frame sampled inside the pill's rounded shape — the design's
 * `BACKGROUND_BLUR(radius = 12)`. Silently does nothing when the blur capture is unavailable.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3GlassFill(
    glass: TextureSetup,
    x: Float,
    y: Float,
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
    val edge = Hud3Geometry.contour(x, y, x + width, y + height, radius, 0f)

    guiRenderState.addGuiElement(
        WatermarkGuiElement(
            RenderPipelines.GUI_TEXTURED,
            glass,
            poseSnapshot,
            scissorStack.peek(),
            watermarkBounds(x, y, x + width, y + height),
        ) { pose ->
            Hud3Geometry.fan(edge, x + width * 0.5f, y + height * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setUv(uAt(vx), vAt(vy)).setColor(tint)
            }
        }
    )
}

/**
 * One complete pill of the design: the background blur, the OUTSIDE-aligned rim and the body.
 *
 * ([x], [y], [width], [height]) is the **fill box** — the size the Figma node reports. The rim is
 * aligned OUTSIDE in the design, so it grows away from that box and is drawn outside the panel's
 * own frame rectangle; `Hud3Panel.frame` keeps [Hud3Theme.RIM] of slack against the screen edges
 * for exactly that reason.
 *
 * Order matters: the rim goes down first so the body's own antialiasing fade lands on top of it,
 * which is what keeps the seam between them from reading as a bright hairline.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3Pill(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    alpha: Float,
    density: Float,
    glass: TextureSetup?,
    radius: Float = Hud3Theme.RADIUS,
) {
    if (glass != null) {
        hud3GlassFill(glass, x, y, width, height, radius, alpha)
    }
    val margin = Hud3Theme.RIM + 2f
    hud3Shapes(x - margin, y - margin, x + width + margin, y + height + margin, density) {
        rimOutside(x, y, width, height, radius, Hud3Theme.RIM, scaleAlpha(Hud3Theme.CARD_RIM, alpha))
        roundRect(x, y, width, height, radius, scaleAlpha(Hud3Theme.CARD, alpha))
    }
}

/**
 * [argb] with its alpha multiplied by [alpha] — every panel's fade goes through this, so a colour
 * constant from [Hud3Theme] is never written to the wire at full opacity during an appear.
 */
internal fun scaled(argb: Int, alpha: Float): Int = scaleAlpha(argb, alpha)

/** Submits [text] with its line box starting at ([x], [yTop]) in a flat color. */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3Text(
    text: String,
    x: Float,
    yTop: Float,
    px: Float,
    density: Float,
    argb: Int,
    font: WatermarkFont = Hud3Theme.font,
) {
    if (text.isEmpty() || (argb ushr 24) == 0) {
        return
    }
    font.submit(this, text, x, yTop, px, density) { argb }
}

/**
 * The line-box top that vertically centres [px] text inside the band
 * `[bandY, bandY + bandHeight]`.
 */
internal fun textTopCentered(
    px: Float,
    density: Float,
    bandY: Float,
    bandHeight: Float,
    font: WatermarkFont = Hud3Theme.font,
): Float = bandY + (bandHeight - font.lineHeight(px, density)) * 0.5f

/**
 * The line-box top that puts [px] text on the shared [baseline]. Runs of different sizes — a value
 * and its unit suffix — must be placed this way or their bottoms drift apart, which is exactly
 * what the design does: it aligns `123` and `fps` on their bottom edge (`align = MAX`, `<3:362>`).
 */
internal fun textTopOnBaseline(
    px: Float,
    density: Float,
    baseline: Float,
    font: WatermarkFont = Hud3Theme.font,
): Float = baseline - font.ascent(px, density)

/** The baseline a run placed with its line box at [yTop] ends up on. */
internal fun textBaseline(
    px: Float,
    density: Float,
    yTop: Float,
    font: WatermarkFont = Hud3Theme.font,
): Float = yTop + font.ascent(px, density)

/** Width of [text] at [px] — the measure every pill's auto-layout width is derived from. */
internal fun textWidth(
    text: String,
    px: Float,
    density: Float,
    font: WatermarkFont = Hud3Theme.font,
): Float = if (text.isEmpty()) 0f else font.width(text, px, density)

/**
 * [text] cut down until it fits [maxWidth], with an ellipsis marking the cut.
 *
 * The ellipsis is three ASCII dots on purpose: the glyph atlas only bakes ASCII and Cyrillic, so a
 * real `U+2026` would come out as the missing-glyph `?`.
 */
@Suppress("LongParameterList")
internal fun fitText(
    text: String,
    px: Float,
    density: Float,
    maxWidth: Float,
    font: WatermarkFont = Hud3Theme.font,
): String {
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

/**
 * An item sprite in a [cell]-sized local slot at ([localX], [localY]), scaled about the slot's
 * centre by [scale]. Called OUTSIDE the pose: item sprites do their own gui-space arithmetic.
 *
 * At rest (the card fully grown, no pop running) it goes through the crisp resampled path, which
 * bakes one texture per device size. Anything in motion goes through the transient path instead,
 * because a size that changes every frame would otherwise bake a texture every frame.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3Item(
    stack: ItemStack,
    frame: Hud3Frame,
    localX: Float,
    localY: Float,
    cell: Float,
    scale: Float = 1f,
) {
    if (stack.isEmpty || scale <= MIN_ITEM_SCALE) {
        return
    }
    val inset = cell * (1f - scale) * 0.5f
    val guiX = frame.guiX(localX + inset)
    val guiY = frame.guiY(localY + inset)
    if (abs(scale * frame.grow - 1f) < AT_REST) {
        val device = (cell * frame.density).roundToInt().coerceAtLeast(MIN_ITEM_DEVICE)
        HudTestItemIcons.draw(this, stack, guiX, guiY, device)
    } else {
        HudTestItemIcons.drawTransient(this, stack, guiX, guiY, cell * scale * frame.guiUnit)
    }
}

/** Below this an item is too small to be worth a draw call. */
private const val MIN_ITEM_SCALE = 0.05f

/** How close to its natural size an item has to be to count as at rest. */
private const val AT_REST = 0.002f

/** The smallest device size the resampled path is asked for, as the panels always did. */
private const val MIN_ITEM_DEVICE = 8

/** A face is 8x8 skin texels, and the head only stays square when it covers a whole multiple. */
private const val FACE_TEXELS = 8

/**
 * A player head drawn straight from their skin: the face (skin pixels 8..16 of 64) with the hat
 * layer (40..48) over it, both mapped across one rounded square and sampled with NEAREST so the
 * skin's own pixels stay square.
 *
 * The head is fitted to a whole multiple of [FACE_TEXELS] device pixels off a device-aligned
 * origin first: NEAREST hands every destination pixel the texel its centre lands in, so at a size
 * that is not a multiple the eight face texels come out unequal widths and the face renders
 * asymmetric. The same reasoning as `HudTestPanelsTarget.hudSkinHead`, which this follows.
 *
 * Called **inside** the panel's pushed pose, in local native pixels — [density] is physical pixels
 * per local unit, so the fit and the origin snap are both measured in device pixels. This is the
 * opposite of `HudTestItemIcons.draw`, which does its own gui-scale arithmetic and therefore has
 * to be called outside the pose.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hud3SkinHead(
    skin: Identifier?,
    x: Float,
    y: Float,
    size: Float,
    radius: Float,
    density: Float,
    alpha: Float,
    flash: Int = 0,
) {
    val scale = density.coerceAtLeast(0.01f)
    val deviceSize = (size * scale / FACE_TEXELS).roundToInt().coerceAtLeast(1) * FACE_TEXELS
    val fitted = deviceSize / scale
    val left = Math.round((x + (size - fitted) * 0.5f) * scale) / scale
    val top = Math.round((y + (size - fitted) * 0.5f) * scale) / scale
    val fittedRadius = if (size > 0f) radius * fitted / size else radius

    if (skin == null) {
        hud3Shapes(left, top, left + fitted, top + fitted, density) {
            roundRect(left, top, fitted, fitted, fittedRadius, scaleAlpha(Hud3Theme.CARD_RIM, alpha))
        }
        return
    }

    val texture = mc.textureManager.getTexture(skin)
    val setup = TextureSetup.singleTexture(
        texture.textureView,
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
    )
    val contour = Hud3Geometry.contour(left, top, left + fitted, top + fitted, fittedRadius, 0f)
    val tint = scaleAlpha(-1, alpha)
    val faceU = { vx: Float -> 0.125f + (vx - left) / fitted * 0.125f }
    val faceV = { vy: Float -> 0.125f + (vy - top) / fitted * 0.125f }
    val hatU = { vx: Float -> 0.625f + (vx - left) / fitted * 0.125f }

    guiRenderState.addGuiElement(
        WatermarkGuiElement(
            RenderPipelines.GUI_TEXTURED,
            setup,
            Matrix3x2f(pose()),
            scissorStack.peek(),
            watermarkBounds(left, top, left + fitted, top + fitted),
        ) { pose ->
            Hud3Geometry.fan(contour, left + fitted * 0.5f, top + fitted * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setUv(faceU(vx), faceV(vy)).setColor(tint)
            }
            Hud3Geometry.fan(contour, left + fitted * 0.5f, top + fitted * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setUv(hatU(vx), faceV(vy)).setColor(tint)
            }
        }
    )

    // A tint over the face (the target hud's hurt flash), on the same fitted shape so it cannot
    // spill past the rounded corners of the head it is laid over.
    if ((flash ushr 24) != 0) {
        hud3Shapes(left, top, left + fitted, top + fitted, density) {
            roundRect(left, top, fitted, fitted, fittedRadius, flash)
        }
    }
}
