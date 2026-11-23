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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import org.joml.Matrix3x2f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Contour tessellation for the Hud3 panels.
 *
 * Shapes are emitted as closed point loops that are then either fanned (a fill) or paired with an
 * expanded copy of themselves (a ring: the antialiasing feather, the pill rim, a progress arc).
 * Every loop is walked **counter-clockwise in screen space** — decreasing angles with y pointing
 * down — so fans and rings keep the vertex winding the vanilla GUI quad pipeline expects.
 */
internal object Hud3Geometry {

    /** Steps per corner arc of a rounded rectangle. */
    private const val CORNER_SEGMENTS = 8

    /** Steps per full turn of a circular ring or arc; 96 keeps a 12 px ring sub-pixel smooth. */
    const val CIRCLE_SEGMENTS = 96

    /**
     * The rounded-rect contour as a closed, x/y interleaved point loop. [offset] expands (positive)
     * or insets (negative) the whole shape, which is how the feather and the OUTSIDE-aligned rim
     * are derived from one shape definition.
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

    /** Closed quad strip between two matching contours — the feather and the pill rim. */
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
 * How far a card's rounded bottom corner pulls its boundary inwards, at a bar of [barHeight]
 * sitting on the bottom edge.
 *
 * The design puts its 2 px progress bars flush with the card's bottom and lets `clipsContent` cut
 * them, so a bar drawn edge to edge pokes out of both corners. A true clip would cut a curved
 * sliver; measuring the boundary at the bar's **vertical middle** straightens that sliver and is
 * within a pixel of it at these radii — `8 - sqrt(8^2 - 7^2)` = 4.13 for the design's own numbers.
 *
 * Both the armour pill and the notification stack inset their bars by this, so the two agree.
 */
internal fun bottomEdgeInset(radius: Float, barHeight: Float): Float {
    if (radius <= 0f) {
        return 0f
    }
    val dy = (radius - barHeight * 0.5f).coerceIn(0f, radius)
    return radius - sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
}

/**
 * The untextured paint surface of one batched GUI element: everything drawn through it lands in a
 * single draw call. Coordinates are the panel's local native pixels.
 *
 * [density] is physical pixels per local unit, which is what the antialiasing [feather] is derived
 * from: one device pixel, never wider than 1.5 local units so it cannot smear a small glyph.
 */
@Suppress("detekt:TooManyFunctions")
internal class Hud3Shapes(
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
     * because a full device pixel of fade would visibly bloat a 2 px bar.
     */
    @Suppress("LongParameterList")
    fun roundRect(x: Float, y: Float, width: Float, height: Float, radius: Float, argb: Int) {
        if (width <= 0f || height <= 0f || (argb ushr 24) == 0) {
            return
        }
        val fade = minOf(feather, minOf(width, height) * 0.25f)
        val edge = Hud3Geometry.contour(x, y, x + width, y + height, radius, 0f)
        val outer = Hud3Geometry.contour(x, y, x + width, y + height, radius, fade)
        fanWithFeather(edge, outer, x + width * 0.5f, y + height * 0.5f, argb)
    }

    /** Shared body of [roundRect]: the fan plus the one-pixel fade ring around it. */
    @Suppress("LongParameterList")
    private fun fanWithFeather(edge: FloatArray, outer: FloatArray, cx: Float, cy: Float, argb: Int) {
        Hud3Geometry.fan(edge, cx, cy) { vx, vy -> vertex(vx, vy, argb) }
        Hud3Geometry.ring(
            edge, outer,
            { vx, vy -> vertex(vx, vy, argb) },
            { vx, vy -> vertex(vx, vy, argb and 0xFFFFFF) },
        )
    }

    /**
     * The pill rim: a stroke of [weight] aligned **OUTSIDE** the given box, which is the alignment
     * the design uses on every card (`strokeAlign = OUTSIDE`, `strokeWeight = 1.5`).
     *
     * Drawn as the band between the box's own contour and a contour expanded by [weight], with a
     * feather on the outer side only — the inner side is covered by the body that is drawn over it.
     */
    @Suppress("LongParameterList")
    fun rimOutside(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        weight: Float,
        argb: Int,
    ) {
        if (width <= 0f || height <= 0f || weight <= 0f || (argb ushr 24) == 0) {
            return
        }
        val inner = Hud3Geometry.contour(x, y, x + width, y + height, radius, 0f)
        val outer = Hud3Geometry.contour(x, y, x + width, y + height, radius, weight)
        val fade = Hud3Geometry.contour(x, y, x + width, y + height, radius, weight + feather)
        Hud3Geometry.ring(
            inner, outer,
            { vx, vy -> vertex(vx, vy, argb) },
            { vx, vy -> vertex(vx, vy, argb) },
        )
        Hud3Geometry.ring(
            outer, fade,
            { vx, vy -> vertex(vx, vy, argb) },
            { vx, vy -> vertex(vx, vy, argb and 0xFFFFFF) },
        )
    }

    /**
     * Axis-aligned rectangle without a feather — for anything meant to be exactly one device pixel
     * wide and not to bleed into its neighbours.
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
     * A circular arc band — the real progress ring.
     *
     * [outerRadius] is the outside of the band and [thickness] grows inwards, which is the
     * `strokeAlign = INSIDE` the design's two rings use. The sweep starts at twelve o'clock and
     * runs **clockwise** over [fraction] of a full turn.
     *
     * Angles are generated from the high end downwards, because the contour convention of this
     * file is decreasing angles with y pointing down; emitting them the other way round would
     * invert the winding of every quad in the band.
     *
     * This is what replaces the design's own construction. There a ring is a 10x10 frame with
     * `cornerRadius = 180` and the progress is a **linear** gradient on its stroke (`<9:830>`:
     * `GRADIENT_LINEAR[#5E8CFF@0.65 / #5E8CFF@0.15@0.65]`). A linear gradient cuts a circle along
     * a straight line, so the "progress" reads as a lopsided smear rather than a sweep and the
     * same picture is produced by two different remaining times. An angular sweep is the shape the
     * design is clearly reaching for, and it is the one thing in this module that is deliberately
     * not a 1:1 port.
     */
    @Suppress("LongParameterList")
    fun arc(
        centerX: Float,
        centerY: Float,
        outerRadius: Float,
        thickness: Float,
        fraction: Float,
        argb: Int,
    ) {
        val sweep = fraction.coerceIn(0f, 1f) * FULL_TURN
        if (sweep <= 0.01f || outerRadius <= 0f || thickness <= 0f || (argb ushr 24) == 0) {
            return
        }
        val outer = outerRadius
        val inner = max(0f, outerRadius - thickness)
        // A band this thin cannot take a full device pixel of fade on both sides without losing
        // its own colour, so the feather is capped at a third of the band.
        val fade = minOf(feather, thickness / 3f)
        val steps = max(2, Math.round(Hud3Geometry.CIRCLE_SEGMENTS * sweep / FULL_TURN))
        val start = TOP_DEGREES
        val step = sweep / steps
        val clear = argb and 0xFFFFFF

        for (i in 0 until steps) {
            // i is the HIGHER angle of the pair, so the quads stay counter-clockwise
            val aHigh = Math.toRadians((start + sweep - step * i).toDouble())
            val aLow = Math.toRadians((start + sweep - step * (i + 1)).toDouble())
            val cosHigh = cos(aHigh).toFloat()
            val sinHigh = sin(aHigh).toFloat()
            val cosLow = cos(aLow).toFloat()
            val sinLow = sin(aLow).toFloat()

            // the band itself
            vertex(centerX + inner * cosHigh, centerY + inner * sinHigh, argb)
            vertex(centerX + outer * cosHigh, centerY + outer * sinHigh, argb)
            vertex(centerX + outer * cosLow, centerY + outer * sinLow, argb)
            vertex(centerX + inner * cosLow, centerY + inner * sinLow, argb)

            // outward feather
            val outerFade = outer + fade
            vertex(centerX + outer * cosHigh, centerY + outer * sinHigh, argb)
            vertex(centerX + outerFade * cosHigh, centerY + outerFade * sinHigh, clear)
            vertex(centerX + outerFade * cosLow, centerY + outerFade * sinLow, clear)
            vertex(centerX + outer * cosLow, centerY + outer * sinLow, argb)

            // inward feather, only while there is room for it
            if (inner > fade) {
                val innerFade = inner - fade
                vertex(centerX + innerFade * cosHigh, centerY + innerFade * sinHigh, clear)
                vertex(centerX + inner * cosHigh, centerY + inner * sinHigh, argb)
                vertex(centerX + inner * cosLow, centerY + inner * sinLow, argb)
                vertex(centerX + innerFade * cosLow, centerY + innerFade * sinLow, clear)
            }
        }
    }

    /** A full circular ring — [arc] over a whole turn. */
    @Suppress("LongParameterList")
    fun ring(centerX: Float, centerY: Float, outerRadius: Float, thickness: Float, argb: Int) =
        arc(centerX, centerY, outerRadius, thickness, 1f, argb)

    /**
     * A progress ring: the spent remainder at [Hud3Theme.SPENT_OPACITY] of the same hue, the live
     * part solid. That two-tone split is exactly what the design's gradient stops encode, only
     * swept angularly instead of linearly.
     */
    @Suppress("LongParameterList")
    fun progressRing(
        centerX: Float,
        centerY: Float,
        outerRadius: Float,
        thickness: Float,
        fraction: Float,
        argb: Int,
    ) {
        ring(centerX, centerY, outerRadius, thickness, scaleAlpha(argb, Hud3Theme.SPENT_OPACITY))
        arc(centerX, centerY, outerRadius, thickness, fraction, argb)
    }

    /**
     * A progress bar with the design's hard cut: the whole track in the same hue at
     * [Hud3Theme.SPENT_OPACITY], the live part solid on top.
     *
     * The design writes this as a linear gradient whose two stops sit at the *same* position
     * (`<3:434>`: `#E5B402@0.45 / #E5B402@0.15@0.45`), which is a step, not a fade — so a plain
     * two-rectangle draw reproduces it exactly.
     */
    @Suppress("LongParameterList")
    fun progressBar(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float,
        fraction: Float,
        argb: Int,
    ) {
        if (width <= 0f || height <= 0f) {
            return
        }
        roundRect(x, y, width, height, radius, scaleAlpha(argb, Hud3Theme.SPENT_OPACITY))
        val filled = width * fraction.coerceIn(0f, 1f)
        if (filled > 0.2f) {
            roundRect(x, y, filled, height, radius, argb)
        }
    }

    private companion object {
        const val FULL_TURN = 360f

        /** Twelve o'clock in this file's angle convention (y points down). */
        const val TOP_DEGREES = 270f
    }
}
