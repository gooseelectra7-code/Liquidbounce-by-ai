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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints

import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.ConvexClip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The minimap draws a round, rotating view straight from 512x512 tile textures by clipping the view
 * polygon against each tile. The pieces must tile the view exactly: no gap (a seam on screen), no
 * overlap (a double-bright stripe), whatever the rotation.
 */
class ConvexClipTest {

    private fun area(xs: DoubleArray, ys: DoubleArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(sum) / 2.0
    }

    private fun circle(
        radius: Double,
        segments: Int,
        rotation: Double,
        cx: Double,
        cy: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val xs = DoubleArray(segments)
        val ys = DoubleArray(segments)
        for (i in 0 until segments) {
            val angle = rotation + 2.0 * PI * i / segments
            xs[i] = cx + cos(angle) * radius
            ys[i] = cy + sin(angle) * radius
        }
        return xs to ys
    }

    @Test
    fun `polygon inside the rectangle is returned whole`() {
        val clip = ConvexClip()
        val xs = doubleArrayOf(1.0, 3.0, 3.0, 1.0)
        val ys = doubleArrayOf(1.0, 1.0, 3.0, 3.0)
        val n = clip.clip(xs, ys, 4, 0.0, 0.0, 10.0, 10.0)
        assertEquals(4, n)
        assertEquals(4.0, area(clip.xs, clip.ys, n), 1e-9)
    }

    @Test
    fun `polygon outside the rectangle vanishes`() {
        val clip = ConvexClip()
        val xs = doubleArrayOf(20.0, 30.0, 30.0, 20.0)
        val ys = doubleArrayOf(20.0, 20.0, 30.0, 30.0)
        assertEquals(0, clip.clip(xs, ys, 4, 0.0, 0.0, 10.0, 10.0))
    }

    @Test
    fun `square straddling a corner keeps only the overlap`() {
        val clip = ConvexClip()
        val xs = doubleArrayOf(-5.0, 5.0, 5.0, -5.0)
        val ys = doubleArrayOf(-5.0, -5.0, 5.0, 5.0)
        val n = clip.clip(xs, ys, 4, 0.0, 0.0, 10.0, 10.0)
        assertEquals(25.0, area(clip.xs, clip.ys, n), 1e-9)
    }

    @Test
    fun `pieces over a tile grid add up to the whole view at any rotation`() {
        val clip = ConvexClip()
        val tile = 512.0
        for (step in 0 until 12) {
            val rotation = step * PI / 7.0
            // A view centred near a tile corner, so it spans four tiles.
            val (xs, ys) = circle(300.0, 64, rotation, 510.0, -3.0)
            val whole = area(xs, ys, xs.size)

            var sum = 0.0
            for (rz in -2..2) {
                for (rx in -2..2) {
                    val n = clip.clip(xs, ys, xs.size, rx * tile, rz * tile, (rx + 1) * tile, (rz + 1) * tile)
                    if (n >= 3) sum += area(clip.xs, clip.ys, n)
                }
            }
            assertEquals(whole, sum, whole * 1e-9, "rotation step $step")
        }
    }

    @Test
    fun `clipping never leaves the rectangle`() {
        val clip = ConvexClip()
        val (xs, ys) = circle(40.0, 48, 0.3, 5.0, 5.0)
        val n = clip.clip(xs, ys, xs.size, 0.0, 0.0, 10.0, 10.0)
        for (i in 0 until n) {
            assert(clip.xs[i] in -1e-9..10.0 + 1e-9) { "x ${clip.xs[i]}" }
            assert(clip.ys[i] in -1e-9..10.0 + 1e-9) { "y ${clip.ys[i]}" }
        }
        // The circle covers the whole rectangle.
        assertEquals(100.0, area(clip.xs, clip.ys, n), 1e-9)
    }
}
