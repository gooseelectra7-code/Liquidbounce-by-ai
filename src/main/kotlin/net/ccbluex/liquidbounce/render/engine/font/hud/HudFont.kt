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

package net.ccbluex.liquidbounce.render.engine.font.hud

import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.toNativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Crisp, resolution-independent HUD text rendered through the native [ClientRenderPipelines.GUI.msdfText]
 * pipeline — **not** LB's core [net.ccbluex.liquidbounce.render.FontManager] font, which rasterizes once at
 * 43px and downscales (blurry small text + uneven baselines).
 *
 * Each glyph is rasterized once at [EM_SIZE]px, converted to a signed distance field (supersampled
 * Euclidean distance transform) and packed into one linear-filtered atlas. The MSDF shader recovers
 * antialiased edges at any size via `median(rgb)` + screen-space derivatives.
 *
 * The atlas is stored grayscale (`R=G=B=signedDistance`); since `median(v,v,v) == v`, this *single-channel*
 * field renders through the very same shader as a true 3-channel MSDF. A baked `msdf-atlas-gen` atlas can
 * therefore be dropped in later with no shader or call-site changes — only this generator is replaced.
 *
 * GL resources are created lazily on the render thread (see [ensureReady]); never touch them from REST/Netty.
 */
object HudFont {

    /** Reference glyph raster size. Distance field is resolution-independent above/below this. */
    private const val EM_SIZE = 64

    /** Supersampling factor for a smooth (non-stair-stepped) distance field. */
    private const val SS = 4

    /**
     * Padding (in low-res texels) around each glyph cell. Must comfortably exceed half of
     * [DISTANCE_RANGE] so the transition band is never clipped.
     */
    private const val SPREAD = 4

    /**
     * Distance range, in texels, that the [0,1] field spans. **MUST equal `DISTANCE_RANGE` in
     * `shaders/gui/msdf_text.fsh`** (and `-pxrange` of any baked MSDF atlas), or edges are mis-sharpened.
     */
    private const val DISTANCE_RANGE = 4.0

    private const val CHARSET_START = 32   // printable ASCII
    private const val CHARSET_END = 126

    /** Cyrillic block (used by the target HUD design), inclusive. */
    private const val CYRILLIC_START = 0x0400
    private const val CYRILLIC_END = 0x045F

    /** 8 unit directions (diagonals normalized) for multi-pass outline/glow. */
    private val DIRS = arrayOf(
        1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f,
        0.707f to 0.707f, -0.707f to 0.707f, 0.707f to -0.707f, -0.707f to -0.707f,
    )

    private val frc = FontRenderContext(null, true, true)
    private val font = resolveFont()

    private var atlas: DynamicTexture? = null
    private var atlasSetup: TextureSetup? = null
    private var atlasSize = 0
    private val glyphs = HashMap<Char, Glyph>()
    private var ascent = 0f
    private var emLineHeight = 0f
    private var ready = false
    private var failed = false

    /** Atlas UV rect + placement of the SDF cell relative to the pen origin, in EM-px units. */
    private class Glyph(
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val offsetX: Float, val offsetY: Float,
        val cellW: Float, val cellH: Float,
        val advance: Float,
    )

    private fun resolveFont(): Font {
        // Prefer a clean UI sans with Cyrillic coverage; fall back to the logical sans.
        for (name in arrayOf("Inter", "Segoe UI", "SansSerif")) {
            val f = Font(name, Font.PLAIN, EM_SIZE)
            if (f.canDisplay('A')) return f
        }
        return Font(Font.SANS_SERIF, Font.PLAIN, EM_SIZE)
    }

    /** Lazily builds the atlas on the calling (render) thread. Returns false if unavailable. */
    private fun ensureReady(): Boolean {
        if (ready) return true
        if (failed) return false
        return try {
            buildAtlas()
            ready = true
            true
        } catch (e: Throwable) {
            failed = true
            logger.error("HudFont: failed to build atlas", e)
            false
        }
    }

    /** Height (px) of one text line at the given pixel size. */
    fun lineHeight(pxSize: Float): Float {
        if (!ensureReady()) return pxSize
        return emLineHeight * (pxSize / EM_SIZE)
    }

    /** Unscaled-to-px width of [text] at the given pixel size. */
    fun width(text: CharSequence, pxSize: Float): Float {
        if (!ensureReady()) return 0f
        val scale = pxSize / EM_SIZE
        var pen = 0f
        for (ch in text) pen += (glyphs[ch] ?: glyphs[' '])?.advance?.times(scale) ?: 0f
        return pen
    }

    /**
     * Draws [text] with its top-left at ([x], [y]) in a single [color].
     *
     * @param pxSize target pixel height of the EM square (≈ font size).
     * @return the pen X after the last glyph (left + width).
     */
    context(ctx: GuiGraphicsExtractor)
    fun draw(
        text: CharSequence,
        x: Float,
        y: Float,
        pxSize: Float,
        color: Color4b = Color4b.WHITE,
    ): Float {
        val argb = color.argb
        return drawCore(text, x, y, pxSize) { _, _ -> argb }
    }

    /**
     * Draws [text] with a per-glyph color, e.g. a travelling chroma wave. [colorProvider] receives the
     * glyph index and the total glyph count and returns an ARGB int.
     */
    context(ctx: GuiGraphicsExtractor)
    fun draw(
        text: CharSequence,
        x: Float,
        y: Float,
        pxSize: Float,
        colorProvider: (index: Int, total: Int) -> Int,
    ): Float = drawCore(text, x, y, pxSize, colorProvider)

    /**
     * Draws [text] with an optional [outline] and/or soft [glow], via cheap multi-pass offset copies of
     * the distance-field glyphs (no extra shader). [outlineWidth] / [glowRadius] are in pixels.
     *
     * @return the pen X after the last glyph of the main pass.
     */
    context(ctx: GuiGraphicsExtractor)
    @JvmOverloads
    fun drawStyled(
        text: CharSequence,
        x: Float,
        y: Float,
        pxSize: Float,
        color: Color4b = Color4b.WHITE,
        outline: Color4b? = null,
        outlineWidth: Float = 1f,
        glow: Color4b? = null,
        glowRadius: Float = 4f,
    ): Float {
        // Glow: two rings of offset copies, fading outward (drawn first, behind everything).
        if (glow != null && glowRadius > 0f && !glow.isTransparent) {
            val rings = 2
            for (ring in 1..rings) {
                val radius = glowRadius * ring / rings
                val ringColor = glow.fade(0.5f * (1f - (ring - 1f) / rings))
                for ((dx, dy) in DIRS) {
                    draw(text, x + dx * radius, y + dy * radius, pxSize, ringColor)
                }
            }
        }

        // Outline: 8 offset copies at outlineWidth.
        if (outline != null && outlineWidth > 0f && !outline.isTransparent) {
            for ((dx, dy) in DIRS) {
                draw(text, x + dx * outlineWidth, y + dy * outlineWidth, pxSize, outline)
            }
        }

        return draw(text, x, y, pxSize, color)
    }

    context(ctx: GuiGraphicsExtractor)
    private fun drawCore(
        text: CharSequence,
        x: Float,
        y: Float,
        pxSize: Float,
        colorProvider: (index: Int, total: Int) -> Int,
    ): Float {
        if (!ensureReady() || text.isEmpty()) return x
        val setup = atlasSetup ?: return x

        val scale = pxSize / EM_SIZE
        val baseY = y + ascent * scale
        val total = text.length

        val width = width(text, pxSize)
        // Pad the element bounds: SDF cells extend ~SPREAD texels beyond the ink and glyphs can
        // reach slightly above the top-left, so a tight rect would scissor-clip the AA edges.
        val pad = (SPREAD + 2) * scale
        val bounds = ctx.getBoundsXYWH(x - pad, y - pad, width + 2f * pad, emLineHeight * scale + 2f * pad)

        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.GUI.msdfText(),
            textureSetup = setup,
            bounds = bounds,
        ) { pose ->
            var pen = x
            text.forEachIndexed { index, ch ->
                val g = glyphs[ch] ?: glyphs[' ']
                if (g != null && g.cellW > 0f) {
                    val argb = colorProvider(index, total)
                    val x0 = pen + g.offsetX * scale
                    val y0 = baseY + g.offsetY * scale
                    val x1 = x0 + g.cellW * scale
                    val y1 = y0 + g.cellH * scale

                    // POSITION_TEX_COLOR quad, winding per TexQuadGuiElementRenderState.
                    addVertexWith2DPose(pose, x0, y0).setUv(g.u0, g.v0).setColor(argb)
                    addVertexWith2DPose(pose, x0, y1).setUv(g.u0, g.v1).setColor(argb)
                    addVertexWith2DPose(pose, x1, y1).setUv(g.u1, g.v1).setColor(argb)
                    addVertexWith2DPose(pose, x1, y0).setUv(g.u1, g.v0).setColor(argb)
                }
                pen += (g?.advance ?: 0f) * scale
            }
        }
        return x + width
    }

    // ------------------------------------------------------------------------------------------------
    // Atlas generation (render thread)
    // ------------------------------------------------------------------------------------------------

    private fun buildAtlas() {
        val lm = font.getLineMetrics("Ag", frc)
        ascent = lm.ascent
        emLineHeight = lm.height

        val codepoints = (CHARSET_START..CHARSET_END) + (CYRILLIC_START..CYRILLIC_END)
        val cells = ArrayList<Pair<Char, CellData>>(codepoints.count())
        var totalArea = 0L
        for (code in codepoints) {
            val ch = code.toChar()
            if (!font.canDisplay(ch)) continue
            val cell = generateCell(ch) ?: continue
            cells.add(ch to cell)
            totalArea += (cell.w + 1).toLong() * (cell.h + 1).toLong()
        }

        // Square atlas sized from total glyph area with headroom; clamp to a sane max.
        atlasSize = (sqrt(totalArea.toDouble()) * 1.15).toInt().coerceIn(256, 4096)
        val image = BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB)

        var cx = 1
        var cy = 1
        var rowH = 0
        for ((ch, cell) in cells) {
            if (cx + cell.w + 1 > atlasSize) {
                cx = 1
                cy += rowH + 1
                rowH = 0
            }
            if (cy + cell.h + 1 > atlasSize) {
                logger.warn("HudFont: atlas overflow at '$ch', dropping remaining glyphs")
                break
            }
            for (py in 0 until cell.h) {
                for (px in 0 until cell.w) {
                    val v = cell.sdf[py * cell.w + px].toInt() and 0xFF
                    image.setRGB(cx + px, cy + py, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
                }
            }
            glyphs[ch] = Glyph(
                cx.toFloat() / atlasSize, cy.toFloat() / atlasSize,
                (cx + cell.w).toFloat() / atlasSize, (cy + cell.h).toFloat() / atlasSize,
                cell.offsetX, cell.offsetY, cell.w.toFloat(), cell.h.toFloat(), cell.advance,
            )
            cx += cell.w + 1
            rowH = maxOf(rowH, cell.h)
        }

        // Linear filtering is essential for distance-field antialiasing.
        val tex = image.toNativeImage().asTexture { "HudFont SDF atlas ${atlasSize}x$atlasSize" }
        atlas = tex
        atlasSetup = TextureSetup.singleTexture(
            tex.textureView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )
        logger.info("HudFont: built ${glyphs.size}-glyph SDF atlas ${atlasSize}x$atlasSize (${font.fontName})")
    }

    private class CellData(
        val sdf: ByteArray, val w: Int, val h: Int,
        val offsetX: Float, val offsetY: Float, val advance: Float,
    )

    private fun generateCell(ch: Char): CellData? {
        val gv = font.createGlyphVector(frc, ch.toString())
        val advance = gv.getGlyphMetrics(0).advanceX
        val bounds = gv.getGlyphOutline(0).bounds2D
        if (bounds.width <= 0.0 || bounds.height <= 0.0) {
            return CellData(ByteArray(0), 0, 0, 0f, 0f, advance) // whitespace
        }

        val w = ceil(bounds.width).toInt() + 2 * SPREAD
        val h = ceil(bounds.height).toInt() + 2 * SPREAD

        val hw = w * SS
        val hh = h * SS
        val img = BufferedImage(hw, hh, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(SS.toDouble(), SS.toDouble())
        g.color = Color.WHITE
        g.translate(-bounds.x + SPREAD, -bounds.y + SPREAD)
        g.fill(gv.getGlyphOutline(0))
        g.dispose()

        val inside = BooleanArray(hw * hh)
        for (i in 0 until hw * hh) {
            inside[i] = ((img.getRGB(i % hw, i / hw) ushr 24) and 0xFF) > 127
        }

        val signedHi = computeSignedDistance(inside, hw, hh) // hi-res px, + inside
        val sdf = ByteArray(w * h)
        for (cyk in 0 until h) {
            for (cxk in 0 until w) {
                var sum = 0.0
                for (sy in 0 until SS) {
                    for (sx in 0 until SS) {
                        sum += signedHi[(cyk * SS + sy) * hw + (cxk * SS + sx)]
                    }
                }
                val avgLow = (sum / (SS * SS)) / SS  // hi-res px -> low-res texels
                val norm = (0.5 + avgLow / DISTANCE_RANGE).coerceIn(0.0, 1.0)
                sdf[cyk * w + cxk] = (norm * 255.0).toInt().toByte()
            }
        }

        return CellData(sdf, w, h, bounds.x.toFloat() - SPREAD, bounds.y.toFloat() - SPREAD, advance)
    }

    /** Signed distance (+inside) in pixel units, via two EDT passes (Felzenszwalb). */
    private fun computeSignedDistance(inside: BooleanArray, w: Int, h: Int): DoubleArray {
        val inf = 1e20
        val gridIn = DoubleArray(w * h) { if (inside[it]) 0.0 else inf }
        val gridOut = DoubleArray(w * h) { if (inside[it]) inf else 0.0 }
        edt2d(gridIn, w, h)
        edt2d(gridOut, w, h)
        return DoubleArray(w * h) { sqrt(gridOut[it]) - sqrt(gridIn[it]) }
    }

    private fun edt2d(grid: DoubleArray, w: Int, h: Int) {
        val col = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = grid[y * w + x]
            val d = edt1d(col, h)
            for (y in 0 until h) grid[y * w + x] = d[y]
        }
        val row = DoubleArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = grid[y * w + x]
            val d = edt1d(row, w)
            for (x in 0 until w) grid[y * w + x] = d[x]
        }
    }

    private fun edt1d(f: DoubleArray, n: Int): DoubleArray {
        val d = DoubleArray(n)
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
        var k = 0
        v[0] = 0
        z[0] = -1e20
        z[1] = 1e20
        for (q in 1 until n) {
            var s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2.0 * q - 2.0 * v[k])
            while (k > 0 && s <= z[k]) {
                k--
                s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2.0 * q - 2.0 * v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e20
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dist = (q - v[k]).toDouble()
            d[q] = dist * dist + f[v[k]]
        }
        return d
    }
}
