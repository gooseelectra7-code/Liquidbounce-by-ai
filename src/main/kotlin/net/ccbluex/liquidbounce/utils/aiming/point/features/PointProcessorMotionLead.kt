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
import kotlin.math.hypot

/**
 * Shifts the aim point along the bbox's XZ plane toward the target's horizontal motion vector,
 * so the crosshair leads a strafing opponent without leaving the current hitbox.
 *
 * Key constraint (per user request): the aim point stays **inside the current bbox** — we do
 * NOT extrapolate the box itself forward in time (that's `PositionExtrapolation`'s job, used
 * upstream in [PointTracker.findPoint] via the `ticks` parameter). All we do here is bias which
 * *part* of the hitbox we aim at, so by the time the swing lands, the target has walked into
 * the crosshair instead of stepping out of it.
 *
 * Companion to [PointProcessorJumpLead] which does the same on the Y axis.
 *
 * Offset magnitude: `relativeSpeed * leadStrength * leadTicks`, clipped to `maxLead × span` on
 * each axis (so we never aim flush against a face of the box where edge-exempt filters might
 * later reject the point). Uses *relative* velocity (target − self) so chasing a target moving
 * away leads forward, sidestepping leads sideways, etc.
 */
internal class PointProcessorMotionLead(
    tracker: PointTracker
) : PointProcessor(tracker, "MotionLead", false) {

    private val leadTicks by float("LeadTicks", 4.0f, 0.0f..20.0f, "ticks")
    private val leadStrength by float("LeadStrength", 1.0f, 0.0f..3.0f, "×")
    private val maxLead by float("MaxLead", 0.35f, 0.0f..0.49f, "bbox")
    private val minVelocity by float("MinVelocity", 0.05f, 0.0f..1.0f, "blk/t")
    private val relativeToSelf by boolean("RelativeToSelf", true)

    override fun process(point: PointInsideBox): PointInsideBox {
        if (leadTicks <= 0f || leadStrength <= 0f || maxLead <= 0f) return point
        val entity = PointTrackerContext.lastEntity ?: return point

        val targetV = entity.deltaMovement
        val (vx, vz) = if (relativeToSelf) {
            val self = player.deltaMovement
            (targetV.x - self.x) to (targetV.z - self.z)
        } else {
            targetV.x to targetV.z
        }
        val speed = hypot(vx, vz)
        if (speed < minVelocity) return point

        val box = point.box
        val spanX = box.maxX - box.minX
        val spanZ = box.maxZ - box.minZ
        if (spanX <= 0.0 || spanZ <= 0.0) return point

        // Scale by ticks-of-lead so the slider reads as "look ahead N ticks of velocity".
        val rawDx = vx * leadTicks * leadStrength
        val rawDz = vz * leadTicks * leadStrength
        val capX = maxLead.toDouble() * spanX
        val capZ = maxLead.toDouble() * spanZ
        val dx = rawDx.coerceIn(-capX, capX)
        val dz = rawDz.coerceIn(-capZ, capZ)

        val newX = (point.pos.x + dx).coerceIn(box.minX, box.maxX)
        val newZ = (point.pos.z + dz).coerceIn(box.minZ, box.maxZ)
        return PointInsideBox(Vec3(newX, point.pos.y, newZ), box)
    }
}
