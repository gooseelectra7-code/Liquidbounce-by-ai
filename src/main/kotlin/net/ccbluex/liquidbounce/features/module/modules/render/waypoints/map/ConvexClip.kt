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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map

/**
 * Sutherland-Hodgman clipping of a CONVEX polygon against an axis-aligned rectangle.
 *
 * This is what lets the minimap be round and rotate while drawing straight from the per-tile
 * textures: the view shape is mapped into world space, clipped against each tile's square, and each
 * piece is drawn with that tile's texture - no offscreen target, no stencil. The result of clipping
 * a convex polygon by a half-plane is convex again, so the pieces stay trivially fan-triangulable.
 */
internal class ConvexClip {

    private var ax = DoubleArray(INITIAL)
    private var ay = DoubleArray(INITIAL)
    private var bx = DoubleArray(INITIAL)
    private var by = DoubleArray(INITIAL)

    /** Vertices of the last result, valid until the next [clip]. */
    val xs: DoubleArray get() = ax
    val ys: DoubleArray get() = ay

    /**
     * Clips the first [count] vertices of ([inX], [inY]) to the rectangle and returns the vertex
     * count of the result (0 when nothing is left). Read the result from [xs]/[ys].
     */
    @Suppress("LongParameterList")
    fun clip(
        inX: DoubleArray,
        inY: DoubleArray,
        count: Int,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): Int {
        ensure(count + 8)
        System.arraycopy(inX, 0, ax, 0, count)
        System.arraycopy(inY, 0, ay, 0, count)

        var n = count
        n = pass(n, EDGE_MIN_X, minX)
        if (n == 0) return 0
        n = pass(n, EDGE_MAX_X, maxX)
        if (n == 0) return 0
        n = pass(n, EDGE_MIN_Y, minY)
        if (n == 0) return 0
        n = pass(n, EDGE_MAX_Y, maxY)
        return n
    }

    private fun inside(edge: Int, x: Double, y: Double, bound: Double): Boolean = when (edge) {
        EDGE_MIN_X -> x >= bound
        EDGE_MAX_X -> x <= bound
        EDGE_MIN_Y -> y >= bound
        else -> y <= bound
    }

    /** Clips a -> b by one edge, then swaps so the result is in a again. */
    private fun pass(n: Int, edge: Int, bound: Double): Int {
        var out = 0
        for (i in 0 until n) {
            val cx = ax[i]
            val cy = ay[i]
            val px = ax[(i + n - 1) % n]
            val py = ay[(i + n - 1) % n]
            val currentIn = inside(edge, cx, cy, bound)
            val previousIn = inside(edge, px, py, bound)

            if (currentIn != previousIn) {
                val t = when (edge) {
                    EDGE_MIN_X, EDGE_MAX_X -> (bound - px) / (cx - px)
                    else -> (bound - py) / (cy - py)
                }
                bx[out] = px + (cx - px) * t
                by[out] = py + (cy - py) * t
                out++
            }
            if (currentIn) {
                bx[out] = cx
                by[out] = cy
                out++
            }
        }

        val tx = ax
        val ty = ay
        ax = bx
        ay = by
        bx = tx
        by = ty
        return out
    }

    private fun ensure(size: Int) {
        if (ax.size >= size) return
        ax = ax.copyOf(size)
        ay = ay.copyOf(size)
        bx = bx.copyOf(size)
        by = by.copyOf(size)
    }

    private companion object {
        const val INITIAL = 96
        const val EDGE_MIN_X = 0
        const val EDGE_MAX_X = 1
        const val EDGE_MIN_Y = 2
        const val EDGE_MAX_Y = 3
    }
}
