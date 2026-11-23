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

package net.ccbluex.liquidbounce.utils.aiming.point.features

import net.ccbluex.liquidbounce.utils.aiming.point.PointInsideBox
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

/**
 * Replaces the resolved point with a random cell on a `variantsPerAxis³` lattice inside the
 * target's bbox, shrunken by [edgeMargin] on every axis so picks never land flush against the
 * edge (edges are where typical hitbox-exempt filters tend to clip points out and where ping /
 * extrapolation drift bites first).
 *
 * Each pick is held for [jumpInterval] ticks before re-randomizing — that's the "jumping" effect,
 * as opposed to per-tick Gaussian jitter (see [PointProcessorGaussian]) which dithers around a
 * single point. Sub-cell jitter still adds continuous variation within the chosen lattice cell so
 * pixel-equal aim is impossible even at low lattice resolutions.
 *
 * Hit-distribution control: a ring buffer of the most recent `uniqueRatio * total_cells` picks is
 * kept; new picks reroll up to [MAX_REJECT_TRIES] times to avoid hitting any of them. With the
 * default 70 %, that means out of every batch of attacks at most ~30 % land on a previously-used
 * lattice cell.
 *
 * Stand-alone by design: when enabled it overrides every upstream processor's choice (delay, lazy,
 * gaussian, learned). Place last in the chain so any of those still get to run their own logic if
 * the user toggles `RandomJump` off.
 */
internal class PointProcessorRandomJump(
    tracker: PointTracker
) : PointProcessor(tracker, "RandomJump", false) {

    private val edgeMargin by float("EdgeMargin", 0.15f, 0.0f..0.45f)
    private val variantsPerAxis by int("VariantsPerAxis", 5, 3..8)
    private val jumpInterval by int("JumpInterval", 8, 1..40)
    private val uniqueRatio by float("UniqueRatio", 0.7f, 0.0f..0.95f)
    private val subCellJitter by float("SubCellJitter", 0.6f, 0.0f..1.0f)

    private var ticksSinceJump: Int = Int.MAX_VALUE
    private var currentCell: Triple<Int, Int, Int>? = null
    private val recent = ArrayDeque<Triple<Int, Int, Int>>()

    override fun process(point: PointInsideBox): PointInsideBox {
        ticksSinceJump++
        val held = currentCell
        val cell = if (held == null || ticksSinceJump >= jumpInterval) {
            ticksSinceJump = 0
            pickFreshCell().also {
                currentCell = it
                pushRecent(it)
            }
        } else {
            held
        }
        val pos = cellToVec3(cell, point.box)
        return PointInsideBox(pos, point.box)
    }

    private fun pickFreshCell(): Triple<Int, Int, Int> {
        val rng = ThreadLocalRandom.current()
        val n = variantsPerAxis
        repeat(MAX_REJECT_TRIES) {
            val candidate = Triple(rng.nextInt(n), rng.nextInt(n), rng.nextInt(n))
            if (candidate !in recent) return candidate
        }
        return Triple(rng.nextInt(n), rng.nextInt(n), rng.nextInt(n))
    }

    private fun pushRecent(cell: Triple<Int, Int, Int>) {
        val total = variantsPerAxis * variantsPerAxis * variantsPerAxis
        val maxRecent = (uniqueRatio * total).toInt().coerceIn(0, total - 1)
        recent.addLast(cell)
        while (recent.size > maxRecent) recent.removeFirst()
    }

    private fun cellToVec3(cell: Triple<Int, Int, Int>, box: AABB): Vec3 {
        val rng = ThreadLocalRandom.current()
        val n = variantsPerAxis
        val cellSpan = 1.0 / n
        val jitter = cellSpan * subCellJitter
        val cxNorm = (cell.first + 0.5) * cellSpan + (rng.nextDouble() - 0.5) * jitter
        val cyNorm = (cell.second + 0.5) * cellSpan + (rng.nextDouble() - 0.5) * jitter
        val czNorm = (cell.third + 0.5) * cellSpan + (rng.nextDouble() - 0.5) * jitter
        val m = edgeMargin.toDouble()
        val usable = 1.0 - 2.0 * m
        val fx = (m + cxNorm.coerceIn(0.0, 1.0) * usable).coerceIn(m, 1.0 - m)
        val fy = (m + cyNorm.coerceIn(0.0, 1.0) * usable).coerceIn(m, 1.0 - m)
        val fz = (m + czNorm.coerceIn(0.0, 1.0) * usable).coerceIn(m, 1.0 - m)
        return Vec3(
            box.minX + fx * (box.maxX - box.minX),
            box.minY + fy * (box.maxY - box.minY),
            box.minZ + fz * (box.maxZ - box.minZ),
        )
    }

    companion object {
        private const val MAX_REJECT_TRIES = 8
    }
}
