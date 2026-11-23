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
import net.ccbluex.liquidbounce.utils.aiming.point.PointTrackerContext
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sign

/**
 * Nudges the aim point along the bbox's Y axis based on the vertical motion of either party.
 *
 * Without this, a target jumping up while we're stationary makes the rotation "fall behind"
 * (the bbox center is still where the target was last tick), so the head visually dips below
 * the target — same problem in reverse when we jump. Adding a small lead matched to relative
 * Y-velocity keeps the crosshair on the chest instead of pulling down to the feet.
 *
 * Direction sign:
 *  - target moving up OR self moving down  → shift point UP
 *  - target moving down OR self moving up  → shift point DOWN
 *
 * Magnitude is proportional to `|relativeDeltaY| * leadStrength * boxHeight`, clamped to
 * `maxLead * boxHeight` so we never lead out of the hitbox or into clipping range.
 */
internal class PointProcessorJumpLead(
    tracker: PointTracker
) : PointProcessor(tracker, "JumpLead", false) {

    private val leadStrength by float("LeadStrength", 6.0f, 0.0f..20.0f)
    private val maxLead by float("MaxLead", 0.30f, 0.0f..0.49f, "bbox")
    private val minVelocity by float("MinVelocity", 0.08f, 0.0f..1.0f, "blk/t")

    override fun process(point: PointInsideBox): PointInsideBox {
        if (leadStrength <= 0f || maxLead <= 0f) return point
        val entity = PointTrackerContext.lastEntity ?: return point

        val targetVy = entity.deltaMovement.y
        val selfVy = player.deltaMovement.y
        val relativeVy = targetVy - selfVy

        if (abs(relativeVy) < minVelocity) return point

        val box = point.box
        val height = box.maxY - box.minY
        if (height <= 0.0) return point

        val rawOffset = relativeVy * leadStrength * height
        val cap = maxLead.toDouble() * height
        val offset = rawOffset.coerceIn(-cap, cap)
        if (sign(offset) == 0.0) return point

        val newY = (point.pos.y + offset).coerceIn(box.minY, box.maxY)
        return PointInsideBox(Vec3(point.pos.x, newY, point.pos.z), box)
    }
}
