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

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import org.joml.Matrix3x2f
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Contour tessellation for the HudTest panels.
 *
 * A rounded rectangle is emitted as a closed point loop that is then either fanned (a fill) or
 * paired with a slightly larger copy of itself (a one-device-pixel ring fading to alpha 0 — the
 * antialiasing feather). The loop is walked **counter-clockwise in screen space**, so every fan
 * and ring built from it keeps the vertex winding the vanilla GUI quad pipeline expects.
 */
internal object HudTestGeometry {

    /** Steps per corner arc. The panels are small; eight steps are already sub-pixel smooth. */
    private const val CORNER_SEGMENTS = 8

    /**
     * The rounded-rect contour as a closed, x/y interleaved point loop. [offset] expands (positive)
     * or insets (negative) the whole shape, which is how the AA feather and the drop-shadow rings
     * are derived from a single shape definition.
     */
    @Suppress("LongParameterList")
    fun contour(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, offset: Float): FloatArray {
        val radius = r.coerceIn(0f, minOf(x1 - x0, y1 - y0) * 0.5f)
        val rr = (radius + offset).coerceAtLeast(0.001f)
        // cx, cy, fromDeg, toDeg per corner - decreasing angles keep the winding
        val corners = floatArrayOf(
            x0 + radius, y0 + radius, 270f, 180f,
            x0 + radius, y1 - radius, 180f, 90f,
            x1 - radius, y1 - radius, 90f, 0f,
            x1 - radius, y0 + radius, 360f, 270f,
        )

        val points = FloatArray((CORNER_SEGMENTS + 1) * 4 * 2)
        var index = 0
        for (corner in 0 until 4) {
            val cx = corners[corner * 4]
            val cy = corners[corner * 4 + 1]
            val from = corners[corner * 4 + 2]
            val step = (corners[corner * 4 + 3] - from) / CORNER_SEGMENTS
            for (i in 0..CORNER_SEGMENTS) {
                val angle = Math.toRadians((from + step * i).toDouble())
                points[index++] = cx + rr * cos(angle).toFloat()
                points[index++] = cy + rr * sin(angle).toFloat()
            }
        }
        return points
    }

    /** Fan fill of a closed contour from ([centerX], [centerY]); [vertexAt] writes one vertex. */
    inline fun fan(
        points: FloatArray,
        centerX: Float,
        centerY: Float,
        crossinline vertexAt: (Float, Float) -> Unit,
    ) {
        val count = points.size / 2
        for (i in 0 until count) {
            val j = (i + 1) % count
            vertexAt(centerX, centerY)
            vertexAt(points[i * 2], points[i * 2 + 1])
            vertexAt(points[j * 2], points[j * 2 + 1])
            vertexAt(points[j * 2], points[j * 2 + 1])
        }
    }

    /** Closed quad strip between two matching contours — the AA feather and the shadow rings. */
    inline fun ring(
        inner: FloatArray,
        outer: FloatArray,
        crossinline vertexInner: (Float, Float) -> Unit,
        crossinline vertexOuter: (Float, Float) -> Unit,
    ) {
        val count = inner.size / 2
        for (i in 0 until count) {
            val j = (i + 1) % count
            vertexInner(inner[i * 2], inner[i * 2 + 1])
            vertexOuter(outer[i * 2], outer[i * 2 + 1])
            vertexOuter(outer[j * 2], outer[j * 2 + 1])
            vertexInner(inner[j * 2], inner[j * 2 + 1])
        }
    }
}

/**
 * The untextured paint surface of one batched GUI element: everything drawn through it lands in a
 * single draw call. Coordinates are the panel's local native pixels.
 *
 * [density] is physical pixels per local unit, which is what the antialiasing [feather] is derived
 * from: one device pixel, never wider than 1.5 local units so it cannot smear a small glyph.
 */
@Suppress("detekt:TooManyFunctions")
internal class HudTestShapes(
    private val consumer: VertexConsumer,
    private val pose: Matrix3x2f,
    val density: Float,
) {

    /** One device pixel expressed in local units. */
    val feather: Float = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.15f, 1.5f)

    private fun vertex(x: Float, y: Float, argb: Int) {
        consumer.addVertexWith2DPose(pose, x, y).setColor(argb)
    }

    /**
     * Antialiased rounded rectangle. On a shape only a few pixels across the feather is narrowed,
     * because a full device pixel of fade would visibly bloat a 2 px bar or a 2 px dot.
     */
    @Suppress("LongParameterList")
    fun roundRect(x: Float, y: Float, width: Float, height: Float, radius: Float, argb: Int) {
        if (width <= 0f || height <= 0f || (argb ushr 24) == 0) {
            return
        }
        val fade = minOf(feather, minOf(width, height) * 0.25f)
        val edge = HudTestGeometry.contour(x, y, x + width, y + height, radius, 0f)
        val outer = HudTestGeometry.contour(x, y, x + width, y + height, radius, fade)
        val cx = x + width * 0.5f
        val cy = y + height * 0.5f
        HudTestGeometry.fan(edge, cx, cy) { vx, vy -> vertex(vx, vy, argb) }
        HudTestGeometry.ring(
            edge, outer,
            { vx, vy -> vertex(vx, vy, argb) },
            { vx, vy -> vertex(vx, vy, argb and 0xFFFFFF) },
        )
    }

    /**
     * Axis-aligned rectangle without a feather — for hairlines and dividers that are meant to be
     * exactly one device pixel wide and must not bleed into their neighbours.
     */
    fun rect(x: Float, y: Float, width: Float, height: Float, argb: Int) {
        if (width <= 0f || height <= 0f || (argb ushr 24) == 0) {
            return
        }
        vertex(x, y, argb)
        vertex(x, y + height, argb)
        vertex(x + width, y + height, argb)
        vertex(x + width, y, argb)
    }

    /** Antialiased disc. */
    fun disc(centerX: Float, centerY: Float, radius: Float, argb: Int) {
        roundRect(centerX - radius, centerY - radius, radius * 2f, radius * 2f, radius, argb)
    }

    /**
     * Polygon given as an x/y interleaved loop, fanned from its centroid and feathered by pushing
     * every vertex outwards from that centroid.
     *
     * The winding is normalized here, so glyph shapes can be written down in whichever direction
     * reads best and still come out with the vertex order the GUI quad pipeline wants. The shape
     * has to be star-shaped with respect to its centroid, which every glyph part below is.
     */
    fun polygon(points: FloatArray, argb: Int) {
        val count = points.size / 2
        if (count < 3 || (argb ushr 24) == 0) {
            return
        }
        var cx = 0f
        var cy = 0f
        var area = 0f
        for (i in 0 until count) {
            val j = (i + 1) % count
            cx += points[i * 2]
            cy += points[i * 2 + 1]
            area += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1]
        }
        cx /= count
        cy /= count

        // The contour convention is a negative shoelace sum (counter-clockwise with y pointing
        // down); anything wound the other way is flipped so fans and rings stay consistent.
        val loop = if (area > 0f) reversed(points) else points

        HudTestGeometry.fan(loop, cx, cy) { vx, vy -> vertex(vx, vy, argb) }

        val outer = FloatArray(loop.size)
        for (i in 0 until count) {
            val dx = loop[i * 2] - cx
            val dy = loop[i * 2 + 1] - cy
            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            outer[i * 2] = loop[i * 2] + dx / length * feather
            outer[i * 2 + 1] = loop[i * 2 + 1] + dy / length * feather
        }
        HudTestGeometry.ring(
            loop, outer,
            { vx, vy -> vertex(vx, vy, argb) },
            { vx, vy -> vertex(vx, vy, argb and 0xFFFFFF) },
        )
    }

    /** The same point loop walked the other way round. */
    private fun reversed(points: FloatArray): FloatArray {
        val count = points.size / 2
        val out = FloatArray(points.size)
        for (i in 0 until count) {
            out[i * 2] = points[(count - 1 - i) * 2]
            out[i * 2 + 1] = points[(count - 1 - i) * 2 + 1]
        }
        return out
    }

    /** Convenience wrapper around [polygon] for the many small triangular glyph parts. */
    @Suppress("LongParameterList")
    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, argb: Int) {
        polygon(floatArrayOf(x0, y0, x1, y1, x2, y2), argb)
    }

    /**
     * A thick line between two points, as a quad of [thickness] width — used by the needle-like
     * glyph parts where an axis-aligned rectangle would look wrong.
     */
    @Suppress("LongParameterList")
    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, argb: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) {
            disc(x0, y0, thickness * 0.5f, argb)
            return
        }
        val nx = -dy / length * thickness * 0.5f
        val ny = dx / length * thickness * 0.5f
        // Counter-clockwise in screen space: start-left, end-left, end-right, start-right
        polygon(floatArrayOf(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny), argb)
    }

    /**
     * A track plus its fill. [fraction] is clamped to `0..1`; a fill narrower than a couple of
     * tenths of a pixel is skipped so it cannot show up as a stray dot at the bar's left cap.
     */
    @Suppress("LongParameterList")
    fun bar(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        fraction: Float,
        trackArgb: Int,
        fillArgb: Int,
    ) {
        val radius = height * 0.5f
        roundRect(x, y, width, height, radius, trackArgb)
        val filled = width * fraction.coerceIn(0f, 1f)
        if (filled > 0.2f) {
            roundRect(x, y, filled, height, radius, fillArgb)
        }
    }

    /**
     * The dark device pixel that hugs the outside of a card — all the separation the reference
     * actually has.
     *
     * Measured off it rather than assumed: walking outwards from a card edge the scene holds a
     * completely flat luminance (34.9, 34.8, 34.8, 34.8 ...) and then drops to near black for a
     * single pixel before the `#0F0F0F` body, on dark and bright backgrounds alike. There is no
     * spread, no falloff and no offset — a soft drop shadow only bands and muddies the edge, which
     * is exactly what it did here before.
     *
     * Drawn under the body so the body's own antialiasing fade lands on top of it, which is what
     * makes the edge pixel come out darker than either the card or the scene.
     */
    fun contactEdge(width: Float, height: Float, radius: Float, opacity: Float) {
        if (opacity <= 0.001f) {
            return
        }
        val solid = scaleAlpha(BLACK, opacity)
        val clear = solid and 0xFFFFFF
        val edge = HudTestGeometry.contour(0f, 0f, width, height, radius, 0f)
        val core = HudTestGeometry.contour(0f, 0f, width, height, radius, feather)
        val fade = HudTestGeometry.contour(0f, 0f, width, height, radius, feather * 2f)
        HudTestGeometry.ring(
            edge, core,
            { vx, vy -> vertex(vx, vy, solid) },
            { vx, vy -> vertex(vx, vy, solid) },
        )
        HudTestGeometry.ring(
            core, fade,
            { vx, vy -> vertex(vx, vy, solid) },
            { vx, vy -> vertex(vx, vy, clear) },
        )
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
