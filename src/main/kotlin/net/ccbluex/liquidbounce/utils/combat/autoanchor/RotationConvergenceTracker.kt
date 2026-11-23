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
package net.ccbluex.liquidbounce.utils.combat.autoanchor

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.client.mc
import kotlin.math.abs

/**
 * Gate for Grim's `DuplicateRotPlace` check (experimental), matched against its SOURCE rather than
 * ported blind: the echo original (and this class before 2026-08-26) keyed on the PITCH delta, but
 * the check actually compares YAW only:
 *
 * ```
 * // DuplicateRotPlace.onPostFlyingBlockPlace; deltaX = |player.yaw - player.lastYaw| (raw)
 * if (deltaX > 2) {
 *     if (Math.abs(deltaX - lastPlacedDeltaX) < 0.0001) flag(); else reward();
 * } else reward();
 * ```
 *
 * `lastPlacedDeltaX` carries across PLACEMENTS (however many ticks apart), and the delta consumed
 * at a placement is that of the flying packet PRECEDING the place packet — which is exactly what
 * a tick-head [update] samples. An interaction is therefore safe when last tick's yaw delta stayed
 * under the 2-degree gate (Grim's reward branch), or when it does not numerically repeat the delta
 * the previous interaction went out with.
 *
 * LB routes every outgoing rotation through [RotationManager.serverRotation] (silent or not) --
 * that is exactly what Grim sees, so only that rotation needs tracking. The delta is the RAW
 * difference of sent yaws, no wrapping, mirroring Grim's `player.yaw - player.lastYaw`.
 */
class RotationConvergenceTracker {
    private var prevYaw = 0f
    private var lastDeltaYaw = Float.MAX_VALUE
    private var lastInteractionYawDelta = 0f
    private var primed = false
    private var hasInteractionYawDelta = false

    fun reset() {
        primed = false
        hasInteractionYawDelta = false
    }

    /** Must run at tick HEAD, before this tick's rotation is applied/sent. */
    fun update() {
        if (mc.player == null) {
            primed = false
            return
        }

        val current = RotationManager.serverRotation
        // First sample of the session has no delta yet -- force "not settled".
        lastDeltaYaw = if (primed) abs(current.yaw - prevYaw) else Float.MAX_VALUE
        prevYaw = current.yaw
        primed = true
    }

    /**
     * True when an interaction sent this tick cannot trip DuplicateRotPlace. [threshold] must stay
     * below Grim's 2-degree gate (both callers use 1.7).
     */
    fun isDuplicateRotPlaceSafe(threshold: Float): Boolean {
        if (!primed) return false
        if (lastDeltaYaw < threshold) return true
        // Above the gate: safe only while the delta does not duplicate the previous interaction's.
        // Grim flags at < 0.0001; demand a 10x margin.
        return !hasInteractionYawDelta || abs(lastDeltaYaw - lastInteractionYawDelta) >= DUPLICATE_EPSILON
    }

    fun markInteraction() {
        if (!primed) return
        lastInteractionYawDelta = lastDeltaYaw
        hasInteractionYawDelta = true
    }

    private companion object {
        private const val DUPLICATE_EPSILON = 0.001f
    }
}
