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
import net.minecraft.world.phys.Vec3

/**
 * Holds the previously-resolved aim point for a short, configurable window so the crosshair
 * doesn't re-pick a different bbox cell every single tick. A perfectly tick-by-tick re-pick
 * (each tick lands at the geometrically optimal sub-pixel inside the hitbox) is a very
 * recognisable signature for behaviour classifiers — humans dwell on one spot for a few
 * frames before drifting.
 *
 * Auto-releases when:
 *  - The bbox center has drifted farther than [maxBoxDrift] (target jumped, teleported, or
 *    strafed enough that the held point is no longer in the same neighbourhood), OR
 *  - The hold timer expired.
 *
 * The held point is clamped to the current bbox each tick so a small target slide doesn't
 * leak us outside the hitbox. This processor is intentionally placed before [PointProcessorStayInHitbox]
 * — sticky picks the fallback point; stay-in-hitbox still snaps to the live crosshair-ray when
 * we're already on a good spot inside the box.
 */
internal class PointProcessorSticky(
    tracker: PointTracker
) : PointProcessor(tracker, "Sticky", false) {

    private val holdTicks by intRange("HoldTicks", 2..4, 1..20, "ticks")
    private val maxBoxDrift by float("MaxBoxDrift", 0.4f, 0.0f..3.0f, "blk")

    private var lastPoint: Vec3? = null
    private var lastBoxCenter: Vec3? = null
    private var ticksRemaining = 0

    override fun process(point: PointInsideBox): PointInsideBox {
        val center = point.box.center
        val prev = lastBoxCenter
        val drift = if (prev == null) Double.MAX_VALUE else prev.distanceTo(center)
        val held = lastPoint

        if (drift > maxBoxDrift.toDouble() || ticksRemaining <= 0 || held == null) {
            // Either the timer is up, the target shifted significantly, or we have no held
            // point yet — adopt the freshly-resolved point and start a new hold window.
            lastBoxCenter = center
            lastPoint = point.pos
            ticksRemaining = (holdTicks.random() - 1).coerceAtLeast(0)
            return point
        }

        ticksRemaining--
        val box = point.box
        val clamped = Vec3(
            held.x.coerceIn(box.minX, box.maxX),
            held.y.coerceIn(box.minY, box.maxY),
            held.z.coerceIn(box.minZ, box.maxZ),
        )
        return PointInsideBox(clamped, box)
    }
}
