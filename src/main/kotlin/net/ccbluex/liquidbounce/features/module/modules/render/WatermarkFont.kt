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

import com.google.common.base.Suppliers
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Matrix3x2f
import org.joml.Vector2f
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage

/**
 * Own TTF text renderer for the watermark, independent of both the Minecraft font and the
 * client's font engine.
 *
 * For crisp output the bundled Inter face is rasterized (Java2D, antialiased, fractional
 * metrics) at **exactly the physical pixel size the text occupies on screen** (gui size ×
 * watermark scale × gui scale) into a white glyph atlas, uploaded as a GPU texture and drawn
 * 1:1 as textured quads whose origin is snapped to the device pixel grid — no minification,
 * no mipmapping artifacts. The atlas is rebuilt automatically whenever the effective pixel
 * size changes. Per-vertex colors make gradient/tinted text free.
 *
 * All layout methods take a [density] — physical pixels per local gui unit — so layout and
 * rasterization always agree.
 */
internal class WatermarkFont(private val resourcePath: String) {

    companion object {
        /** Heavier face for the brand title. */
        val BRAND = WatermarkFont("resources/liquidbounce/fonts/Inter-SemiBold.ttf")

        /** Lighter face for the info sections. */
        val TEXT = WatermarkFont("resources/liquidbounce/fonts/Inter-Medium.ttf")

        private const val MIN_PX = 8
        private const val MAX_PX = 128
        private const val PADDING = 2

        /** Baked character ranges: ASCII + the Cyrillic block (Ѐ..џ covers Russian/Ukrainian). */
        private val CHAR_RANGES = listOf(32..126, 0x400..0x45F)

        /**
         * Max simultaneously cached atlas sizes per face. Ctrl+wheel ClickGUI zoom bakes one
         * atlas per device-pixel size; the zoom range (0.5-1.6 x guiScale) is finite, so the
         * cap is generous enough to keep them all cached instead of re-baking mid-zoom.
         */
        private const val MAX_ATLASES = 24
    }

    private class Glyph(
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        /** Left side bearing relative to the pen position, in atlas pixels. */
        val xOffset: Float,
        /** Offset of the glyph's top relative to the baseline (negative above), in atlas pixels. */
        val yOffset: Float,
        val width: Float,
        val height: Float,
        val advance: Float,
    )

    /** One rasterized atlas at a specific pixel size. */
    private class Atlas(
        val glyphs: Map<Int, Glyph>,
        val setup: TextureSetup,
        val texture: DynamicTexture,
        val ascent: Float,
        val lineHeight: Float,
    ) {
        var lastUsed = System.currentTimeMillis()

        fun glyphAt(char: Char): Glyph? = glyphs[char.code] ?: glyphs['?'.code]
    }

    // Atlases cached per pixel size, so HUD elements with different scales coexist
    private val atlases = HashMap<Int, Atlas>()
    private var failed = false

    private fun targetPx(px: Float, density: Float): Int =
        Math.round(px * density).coerceIn(MIN_PX, MAX_PX)

    private fun atlasOrNull(px: Float, density: Float): Atlas? =
        if (density > 0f) atlasFor(targetPx(px, density)) else null

    fun width(text: String, px: Float, density: Float): Float {
        val atlas = atlasOrNull(px, density) ?: return text.length * px * 0.5f
        var widthPx = 0f
        for (char in text) {
            widthPx += atlas.glyphAt(char)?.advance ?: 0f
        }
        return widthPx / density
    }

    fun lineHeight(px: Float, density: Float): Float {
        val atlas = atlasOrNull(px, density) ?: return px
        return atlas.lineHeight / density
    }

    fun ascent(px: Float, density: Float): Float {
        val atlas = atlasOrNull(px, density) ?: return px * 0.8f
        return atlas.ascent / density
    }

    /** Whether every character of [text] has a real glyph (no '?' fallbacks). */
    fun canRender(text: String, px: Float, density: Float): Boolean {
        val atlas = atlasOrNull(px, density) ?: return false
        for (char in text) {
            if (!atlas.glyphs.containsKey(char.code)) {
                return false
            }
        }
        return true
    }

    /**
     * Submits [text] with its top-left corner at ([x], [yTop]) as a single GUI element.
     * The string origin is snapped to the device pixel grid and glyphs are drawn 1:1 with
     * the atlas. [colorAt] is sampled at each glyph edge with the horizontal fraction `0..1`
     * across the rendered string, enabling smooth horizontal gradients.
     */
    @Suppress("LongParameterList")
    fun submit(
        ctx: GuiGraphicsExtractor,
        text: String,
        x: Float,
        yTop: Float,
        px: Float,
        density: Float,
        colorAt: (Float) -> Int,
    ) {
        if (text.isEmpty()) {
            return
        }
        val atlas = atlasOrNull(px, density) ?: return
        val texture = atlas.setup

        val pose = Matrix3x2f(ctx.pose())

        // Snap the string origin X to the device pixel grid for 1:1 atlas sampling.
        // Local units map to device pixels by [density]; the pose's own scale is m00.
        var originX = x
        val originY = yTop
        val localScale = pose.m00
        if (localScale > 0f) {
            val transformed = pose.transformPosition(x, yTop, Vector2f())
            val guiToDevice = density / localScale
            val snappedX = Math.round(transformed.x * guiToDevice) / guiToDevice
            originX += (snappedX - transformed.x) / localScale
        }

        // Atlas pixels → local units; glyphs end up exactly atlas-pixel-sized on screen
        val scale = 1f / density
        // Vertically only the BASELINE is snapped (single rounding from the raw origin):
        // two faces sharing one baseline then land on the same device row, no per-row drift
        val baseline = Math.round((originY + atlas.ascent * scale) * density) / density
        val totalWidth = width(text, px, density).coerceAtLeast(0.001f)

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI_TEXTURED,
                texture,
                pose,
                ctx.scissorStack.peek(),
                ctx.watermarkBounds(originX, originY, originX + totalWidth, originY + atlas.lineHeight * scale),
            ) { poseMatrix ->
                var penX = originX
                for (char in text) {
                    val glyph = atlas.glyphAt(char) ?: continue
                    if (glyph.width > 0f && glyph.height > 0f) {
                        // Snap each glyph to the device pixel grid: advances stay fractional
                        // (correct typography), but every glyph samples the atlas exactly 1:1
                        val gx0 = Math.round((penX + glyph.xOffset * scale) * density) / density
                        val gy0 = baseline + glyph.yOffset * scale
                        val gx1 = gx0 + glyph.width * scale
                        val gy1 = gy0 + glyph.height * scale
                        val leftColor = colorAt(((gx0 - originX) / totalWidth).coerceIn(0f, 1f))
                        val rightColor = colorAt(((gx1 - originX) / totalWidth).coerceIn(0f, 1f))

                        addVertexWith2DPose(poseMatrix, gx0, gy0).setUv(glyph.u0, glyph.v0).setColor(leftColor)
                        addVertexWith2DPose(poseMatrix, gx0, gy1).setUv(glyph.u0, glyph.v1).setColor(leftColor)
                        addVertexWith2DPose(poseMatrix, gx1, gy1).setUv(glyph.u1, glyph.v1).setColor(rightColor)
                        addVertexWith2DPose(poseMatrix, gx1, gy0).setUv(glyph.u1, glyph.v0).setColor(rightColor)
                    }
                    penX += glyph.advance * scale
                }
            }
        )
    }

    /** Returns (building and caching if needed) the atlas for [targetPx]; evicts stale sizes. */
    private fun atlasFor(targetPx: Int): Atlas? {
        atlases[targetPx]?.let {
            it.lastUsed = System.currentTimeMillis()
            return it
        }
        if (failed) {
            return null
        }

        return try {
            val atlas = buildAtlas(targetPx)
            atlases[targetPx] = atlas

            // Evict the least recently used size when over the cap
            if (atlases.size > MAX_ATLASES) {
                val eldest = atlases.minByOrNull { it.value.lastUsed }
                if (eldest != null) {
                    eldest.value.texture.close()
                    atlases.remove(eldest.key)
                }
            }
            atlas
        } catch (e: Throwable) {
            failed = true
            logger.error("WatermarkFont: failed to build glyph atlas for $resourcePath at ${targetPx}px", e)
            null
        }
    }

    @Suppress("detekt:LongMethod")
    private fun buildAtlas(targetPx: Int): Atlas {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("missing classpath resource $resourcePath")
        val font = stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont(targetPx.toFloat())
        val frc = FontRenderContext(null, true, true)

        val lineMetrics = font.getLineMetrics("Ag", frc)
        val atlasWidth = if (targetPx <= 40) 512 else 1024

        // First pass: measure every glyph and shelf-pack the atlas
        class Cell(val char: Char, val bounds: java.awt.Rectangle, val advance: Float) {
            var x = 0
            var y = 0
        }

        val cells = ArrayList<Cell>(CHAR_RANGES.sumOf { it.last - it.first + 1 })
        for (range in CHAR_RANGES) {
            for (code in range) {
                val char = code.toChar()
                if (!font.canDisplay(char)) {
                    continue
                }
                val glyphVector = font.createGlyphVector(frc, char.toString())
                val bounds = glyphVector.getPixelBounds(frc, 0f, 0f)
                val advance = glyphVector.getGlyphMetrics(0).advanceX
                cells += Cell(char, bounds, advance)
            }
        }

        var penX = PADDING
        var penY = PADDING
        var rowHeight = 0
        for (cell in cells) {
            val w = cell.bounds.width
            val h = cell.bounds.height
            if (w <= 0 || h <= 0) {
                continue
            }
            if (penX + w + PADDING > atlasWidth) {
                penX = PADDING
                penY += rowHeight + PADDING
                rowHeight = 0
            }
            cell.x = penX
            cell.y = penY
            penX += w + PADDING
            rowHeight = maxOf(rowHeight, h)
        }
        val atlasHeight = penY + rowHeight + PADDING

        // Second pass: rasterize white glyphs into the atlas
        val image = BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            graphics.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON,
            )
            graphics.font = font
            graphics.color = Color.WHITE
            for (cell in cells) {
                if (cell.bounds.width <= 0 || cell.bounds.height <= 0) {
                    continue
                }
                graphics.drawString(
                    cell.char.toString(),
                    (cell.x - cell.bounds.x).toFloat(),
                    (cell.y - cell.bounds.y).toFloat(),
                )
            }
        } finally {
            graphics.dispose()
        }

        // Upload to the GPU
        val nativeImage = NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false)
        for (y in 0 until atlasHeight) {
            for (x in 0 until atlasWidth) {
                nativeImage.setPixel(x, y, image.getRGB(x, y))
            }
        }
        val texture = DynamicTexture(Suppliers.ofInstance("WatermarkFont $resourcePath ${targetPx}px"), nativeImage)
        val setup = TextureSetup.singleTexture(
            texture.textureView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )

        val table = HashMap<Int, Glyph>(cells.size * 2)
        for (cell in cells) {
            table[cell.char.code] = Glyph(
                u0 = cell.x.toFloat() / atlasWidth,
                v0 = cell.y.toFloat() / atlasHeight,
                u1 = (cell.x + cell.bounds.width).toFloat() / atlasWidth,
                v1 = (cell.y + cell.bounds.height).toFloat() / atlasHeight,
                xOffset = cell.bounds.x.toFloat(),
                yOffset = cell.bounds.y.toFloat(),
                width = cell.bounds.width.toFloat(),
                height = cell.bounds.height.toFloat(),
                advance = cell.advance,
            )
        }

        return Atlas(
            glyphs = table,
            setup = setup,
            texture = texture,
            ascent = lineMetrics.ascent,
            lineHeight = lineMetrics.ascent + lineMetrics.descent,
        )
    }
}
