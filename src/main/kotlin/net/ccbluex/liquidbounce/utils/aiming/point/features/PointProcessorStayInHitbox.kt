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

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.point.PointInsideBox
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.point.PointTrackerContext

/**
 * Slides the resolved aim point toward whatever the current rotation's crosshair-ray already hits
 * inside the bbox. When the crosshair is already on the target, ΔRot drops to ~0 and the
 * AngleSmooth pipeline stops moving the head — the visible effect is the crosshair "settling"
 * into the hitbox instead of micro-correcting.
 *
 * Implementation: ray-cast eyes→(eyes + dir * far) against the resolved bbox via `AABB.clip`.
 * If the ray hits, lerp [PointInsideBox.pos] toward the hit location by [strength]. If the
 * crosshair is off-target, this is a pass-through.
 *
 * Why not do this in AngleSmooth: smoothing operates on rotations, so its only way to "stop"
 * is to clamp the per-tick delta. Working in point-space lets us literally move the target onto
 * the current ray — zero delta without touching the speed cap.
 *
 * Place last in the chain so any prior processor (delay/lazy/gaussian/learned/randomJump) still
 * gets to choose a base point first; we just snap it onto the current view.
 */
internal class PointProcessorStayInHitbox(
    tracker: PointTracker
) : PointProcessor(tracker, "StayInHitbox", false) {

    private val strength by float("Strength", 0.8f, 0.0f..1.0f)

    override fun process(point: PointInsideBox): PointInsideBox {
        if (strength <= 0f) return point
        val eyes = PointTrackerContext.lastEyes ?: return point
        val rotation = RotationManager.currentRotation ?: return point

        val direction = rotation.directionVector
        val rayEnd = eyes.add(direction.scale(RAY_DISTANCE))
        val hit = point.box.clip(eyes, rayEnd).orElse(null) ?: return point

        val newPos = point.pos.lerp(hit, strength.toDouble())
        return PointInsideBox(newPos, point.box)
    }

    private companion object {
        const val RAY_DISTANCE = 16.0
    }
}
