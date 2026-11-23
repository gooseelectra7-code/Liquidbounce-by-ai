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

package net.ccbluex.liquidbounce.render.gui.systemdlc

import com.google.gson.JsonParser
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.toNativeImage
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-demand icon renderer for the ported SystemDLC vector icon set.
 *
 * Their client draws these icons through an SVG fragment shader (`svg.fsh`) fed by a per-element
 * uniform path array. This fork's GUI is vertex-attribute batched via `GuiGraphicsExtractor` and has
 * no such shader, so the icons are rasterized with Java2D and drawn as tinted textured quads through
 * the existing GUI textured path.
 *
 * ## Why per-size instead of one fixed atlas
 * A fixed 64px atlas cell drawn at the ~16px GUI size is a 4x LINEAR minification: bilinear
 * undersampling softens+thins the strokes ("quality so-so"). Instead each icon is rasterized at the
 * EXACT pixel size it is drawn at (aspect-fit), so the quad is a ~1:1 blit of a texture Java2D
 * anti-aliased at that size — crisp. Rendered textures are kept in a small LRU cache keyed by
 * (icon, drawn px); the GUI only uses a handful of sizes, so the cache stays tiny.
 *
 * Icons are rendered WHITE on transparent so the GUI's per-vertex color multiply tints them at draw
 * time. GL resources are created lazily on the render thread; never touch them off-thread.
 *
 * Supported SVG path commands: `M/m L/l H/h V/v C/c S/s Q/q T/t Z/z`. Arc (`A/a`) is not present in
 * the ported set and falls back to a straight line to the arc endpoint (logged once).
 */
object SystemIconAtlas {

    /** Supersampling factor for antialiased strokes; the hires render is box-averaged down. */
    private const val SS = 4

    /**
     * Stroke-weight bold factor. Our raster path lacks the SVG shader's exact-size crispness; a native
     * 1-unit stroke on a ~15-unit viewBox otherwise reads too thin. Widen it so the drawn icon has the
     * intended weight. Only affects stroked (non-filled) icons.
     */
    private const val STROKE_BOLD = 1.6

    /** Minimum stroke width in FINAL px, so a thin stroke never fades below a visible line. */
    private const val MIN_STROKE_PX = 1.35

    /** Max rendered icon dimension (px) — a guard on absurd draw sizes. */
    private const val MAX_PX = 512

    /** LRU cap on cached (icon, size) textures. The GUI uses ~a handful of sizes across 72 icons. */
    private const val MAX_CACHE = 320

    private const val RESOURCE_DIR = "icons/system"

    /** A rasterized icon texture at one specific pixel size, ready to blit ~1:1. */
    class RenderedIcon(
        val setup: TextureSetup,
        val texture: DynamicTexture,
        val pxW: Int,
        val pxH: Int,
    )

    private class IconSpec(
        val width: Double,
        val height: Double,
        val strokeWidth: Double,
        val filled: Boolean,
        val paths: List<String>,
    )

    /** Parsed specs (or null for icons that failed to load), keyed by icon. */
    private val specs = HashMap<SystemIcon, IconSpec?>()

    /** LRU cache of rendered textures; access-order so the eldest entry is the least-recently-used. */
    private val cache = object : LinkedHashMap<Long, RenderedIcon>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, RenderedIcon>): Boolean {
            if (size > MAX_CACHE) {
                runCatching { eldest.value.texture.close() }
                return true
            }
            return false
        }
    }

    private var warnedArc = false

    /**
     * Get (rendering + caching on first use) the icon rasterized to fit a `size`-px square box while
     * preserving its native aspect. Must be called on the render thread. Returns null if unavailable.
     */
    fun get(icon: SystemIcon, size: Float): RenderedIcon? {
        val spec = specOf(icon) ?: return null
        val aspect = (spec.width / spec.height).toFloat()
        var w = size
        var h = size
        if (aspect >= 1f) h = size / aspect else w = size * aspect
        val pxW = w.roundToInt().coerceIn(1, MAX_PX)
        val pxH = h.roundToInt().coerceIn(1, MAX_PX)

        val key = (icon.ordinal.toLong() shl 40) or (pxW.toLong() shl 20) or pxH.toLong()
        cache[key]?.let { return it }

        val rendered = try {
            renderAt(spec, pxW, pxH)
        } catch (e: Throwable) {
            logger.warn("SystemIconAtlas: failed to render '${icon.iconName}' at ${pxW}x$pxH", e)
            null
        } ?: return null
        cache[key] = rendered
        return rendered
    }

    private fun specOf(icon: SystemIcon): IconSpec? {
        if (specs.containsKey(icon)) return specs[icon]
        val spec = try {
            loadSpec(icon)
        } catch (e: Throwable) {
            logger.warn("SystemIconAtlas: failed to load '${icon.iconName}'", e)
            null
        }
        specs[icon] = spec
        return spec
    }

    private fun loadSpec(icon: SystemIcon): IconSpec {
        val text = LiquidBounce.resourceToString("$RESOURCE_DIR/${icon.iconName}.json")
        val obj = JsonParser.parseString(text).asJsonObject
        val width = obj.get("width")?.asDouble ?: 16.0
        val height = obj.get("height")?.asDouble ?: 16.0
        val strokeWidth = obj.get("strokeWidth")?.asDouble ?: 1.0
        val filled = obj.get("filled")?.asBoolean ?: false
        val paths = obj.getAsJsonArray("paths")?.map { it.asString } ?: emptyList()
        return IconSpec(
            width = if (width > 0.0) width else 16.0,
            height = if (height > 0.0) height else 16.0,
            strokeWidth = if (strokeWidth > 0.0) strokeWidth else 1.0,
            filled = filled,
            paths = paths,
        )
    }

    /**
     * Render [spec] white-on-transparent into a [pxW]x[pxH] texture (supersampled [SS]x then
     * box-downscaled), aspect-preserving. This is the exact GUI draw size, so the later blit is ~1:1.
     */
    private fun renderAt(spec: IconSpec, pxW: Int, pxH: Int): RenderedIcon? {
        val path = buildPath(spec.paths)
        val bounds = path.bounds2D
        if (bounds.width <= 0.0 && bounds.height <= 0.0) return null

        val hiW = pxW * SS
        val hiH = pxH * SS
        val hires = BufferedImage(hiW, hiH, BufferedImage.TYPE_INT_ARGB)
        val g = hires.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Map the native viewBox onto the hires pixels (uniform scale, centered).
        val hs = pxW / spec.width * SS
        g.translate((hiW - spec.width * hs) / 2.0, (hiH - spec.height * hs) / 2.0)
        g.scale(hs, hs)

        g.color = Color.WHITE
        if (spec.filled) {
            g.fill(path)
        } else {
            // strokeWidth is in native units; the active transform (hs) scales it to hires px.
            // Bold it and enforce a floor expressed in FINAL px (converted back through hs / SS).
            val boldUnits = spec.strokeWidth * STROKE_BOLD
            val floorUnits = MIN_STROKE_PX * SS / hs
            val strokeUnits = max(boldUnits, floorUnits).toFloat()
            g.stroke = BasicStroke(strokeUnits, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)
        }
        g.dispose()

        val cell = BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB)
        val g2 = cell.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.drawImage(hires, 0, 0, pxW, pxH, null)
        g2.dispose()

        val tex = cell.toNativeImage().asTexture { "SystemIcon ${pxW}x$pxH" }
        val setup = TextureSetup.singleTexture(
            tex.textureView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )
        return RenderedIcon(setup, tex, pxW, pxH)
    }

    // ------------------------------------------------------------------------------------------------
    // SVG path parsing (subset: M L H V C S Q T Z, absolute + relative)
    // ------------------------------------------------------------------------------------------------

    /** Builds a single [Path2D] from all path-`d` strings of an icon (NON_ZERO winding, SVG default). */
    private fun buildPath(paths: List<String>): Path2D.Double {
        val out = Path2D.Double(Path2D.WIND_NON_ZERO)
        for (d in paths) {
            appendPath(out, d)
        }
        return out
    }

    private fun appendPath(path: Path2D.Double, d: String) {
        val tokens = tokenize(d)
        var i = 0
        var cmd = ' '
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        // Last control point of the previous cubic (C/S) / quadratic (Q/T), for smooth-curve reflection.
        var ctrlX = 0.0
        var ctrlY = 0.0
        var prevWasCubic = false
        var prevWasQuad = false

        fun hasNum() = i < tokens.size && tokens[i] is Double
        fun num(): Double {
            val v = tokens[i] as Double
            i++
            return v
        }

        while (i < tokens.size) {
            val head = tokens[i]
            if (head is Char) {
                cmd = head
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.closePath()
                    cx = sx
                    cy = sy
                    prevWasCubic = false
                    prevWasQuad = false
                    continue
                }
            }
            val rel = cmd.isLowerCase()
            when (cmd) {
                'M', 'm' -> {
                    if (!hasNum()) break
                    val x = num() + if (rel) cx else 0.0
                    if (!hasNum()) break
                    val y = num() + if (rel) cy else 0.0
                    path.moveTo(x, y)
                    cx = x; cy = y; sx = x; sy = y
                    // Subsequent implicit coordinate pairs after a moveto are linetos.
                    cmd = if (rel) 'l' else 'L'
                    prevWasCubic = false; prevWasQuad = false
                }
                'L', 'l' -> {
                    if (!hasNum()) break
                    val x = num() + if (rel) cx else 0.0
                    if (!hasNum()) break
                    val y = num() + if (rel) cy else 0.0
                    path.lineTo(x, y)
                    cx = x; cy = y
                    prevWasCubic = false; prevWasQuad = false
                }
                'H', 'h' -> {
                    if (!hasNum()) break
                    val x = num() + if (rel) cx else 0.0
                    path.lineTo(x, cy)
                    cx = x
                    prevWasCubic = false; prevWasQuad = false
                }
                'V', 'v' -> {
                    if (!hasNum()) break
                    val y = num() + if (rel) cy else 0.0
                    path.lineTo(cx, y)
                    cy = y
                    prevWasCubic = false; prevWasQuad = false
                }
                'C', 'c' -> {
                    if (!hasNum()) break
                    val x1 = num() + if (rel) cx else 0.0
                    val y1 = num() + if (rel) cy else 0.0
                    val x2 = num() + if (rel) cx else 0.0
                    val y2 = num() + if (rel) cy else 0.0
                    val x = num() + if (rel) cx else 0.0
                    val y = num() + if (rel) cy else 0.0
                    path.curveTo(x1, y1, x2, y2, x, y)
                    ctrlX = x2; ctrlY = y2
                    cx = x; cy = y
                    prevWasCubic = true; prevWasQuad = false
                }
                'S', 's' -> {
                    if (!hasNum()) break
                    val x1 = if (prevWasCubic) 2.0 * cx - ctrlX else cx
                    val y1 = if (prevWasCubic) 2.0 * cy - ctrlY else cy
                    val x2 = num() + if (rel) cx else 0.0
                    val y2 = num() + if (rel) cy else 0.0
                    val x = num() + if (rel) cx else 0.0
                    val y = num() + if (rel) cy else 0.0
                    path.curveTo(x1, y1, x2, y2, x, y)
                    ctrlX = x2; ctrlY = y2
                    cx = x; cy = y
                    prevWasCubic = true; prevWasQuad = false
                }
                'Q', 'q' -> {
                    if (!hasNum()) break
                    val x1 = num() + if (rel) cx else 0.0
                    val y1 = num() + if (rel) cy else 0.0
                    val x = num() + if (rel) cx else 0.0
                    val y = num() + if (rel) cy else 0.0
                    path.quadTo(x1, y1, x, y)
                    ctrlX = x1; ctrlY = y1
                    cx = x; cy = y
                    prevWasCubic = false; prevWasQuad = true
                }
                'T', 't' -> {
                    if (!hasNum()) break
                    val x1 = if (prevWasQuad) 2.0 * cx - ctrlX else cx
                    val y1 = if (prevWasQuad) 2.0 * cy - ctrlY else cy
                    val x = num() + if (rel) cx else 0.0
                    val y = num() + if (rel) cy else 0.0
                    path.quadTo(x1, y1, x, y)
                    ctrlX = x1; ctrlY = y1
                    cx = x; cy = y
                    prevWasCubic = false; prevWasQuad = true
                }
                'A', 'a' -> {
                    // Arc is absent from the ported icon set. Consume its 7 params and approximate with a
                    // straight line to the endpoint rather than crash — logged once.
                    if (!warnedArc) {
                        logger.warn("SystemIconAtlas: SVG arc command '$cmd' approximated with a line (unsupported)")
                        warnedArc = true
                    }
                    if (!hasNum()) break
                    num(); num(); num(); num(); num() // rx ry xAxisRot largeArc sweep
                    val x = num() + if (rel) cx else 0.0
                    val y = num() + if (rel) cy else 0.0
                    path.lineTo(x, y)
                    cx = x; cy = y
                    prevWasCubic = false; prevWasQuad = false
                }
                else -> {
                    // Unknown command: skip a token to guarantee progress.
                    logger.warn("SystemIconAtlas: unhandled SVG path command '$cmd'")
                    if (hasNum()) i++ else break
                }
            }
        }
    }

    /** Tokenizes an SVG path-`d` string into command [Char]s and numeric [Double]s. */
    private fun tokenize(d: String): List<Any> {
        val tokens = ArrayList<Any>(d.length / 3 + 4)
        var i = 0
        val n = d.length
        while (i < n) {
            val c = d[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' -> i++
                // Command letters. 'e'/'E' are never SVG commands (only exponents inside numbers).
                c in 'A'..'Z' && c != 'E' || c in 'a'..'z' && c != 'e' -> {
                    tokens.add(c)
                    i++
                }
                else -> {
                    val start = i
                    if (c == '+' || c == '-') i++
                    while (i < n && d[i] in '0'..'9') i++
                    if (i < n && d[i] == '.') {
                        i++
                        while (i < n && d[i] in '0'..'9') i++
                    }
                    if (i < n && (d[i] == 'e' || d[i] == 'E')) {
                        i++
                        if (i < n && (d[i] == '+' || d[i] == '-')) i++
                        while (i < n && d[i] in '0'..'9') i++
                    }
                    if (i <= start) {
                        // No progress (stray character) -> skip it to avoid an infinite loop.
                        i = start + 1
                    } else {
                        val s = d.substring(start, i)
                        val v = s.toDoubleOrNull()
                        if (v != null) tokens.add(v)
                    }
                }
            }
        }
        return tokens
    }
}
