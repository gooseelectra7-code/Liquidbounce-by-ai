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

package net.ccbluex.liquidbounce.utils.navigation.pathing.route

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps

/**
 * Chooses how a journey should be travelled, before anything is planned for real.
 *
 * The comparison is deliberately cheap and deliberately biased. Walking is estimated along a
 * straight line at full sprint — a lower bound no real route ever achieves, since terrain only makes
 * it worse — so a strategy has to beat walking at its theoretical best before it is even tried.
 * Flying is estimated with the whole overhead attached: getting off the ground, climbing to cruise,
 * and coming back down again.
 *
 * On top of that comparison sits a distance floor, because the clock is not the only cost. Rockets
 * are consumable, and a router that spends two of them to save four seconds is spending something it
 * cannot price. Below the floor we walk, whatever the arithmetic says.
 */
object NavRouter {

    /** Should this journey be flown rather than walked? */
    fun preferFlight(distance: Double, caps: NavCaps): Boolean {
        if (!caps.canFly) return false
        if (caps.fireworks < MIN_ROCKETS) return false
        if (distance < MIN_FLIGHT_DISTANCE) return false

        return estimateFlightTicks(distance) < estimateWalkTicks(distance, caps)
    }

    /** Ticks a glide would take, overheads included. */
    fun estimateFlightTicks(distance: Double): Double =
        TAKEOFF_TICKS + LANDING_TICKS + NavLocomotion.ELYTRA.estimateTicks(distance)

    /** Ticks walking would take in a straight line at this profile's best speed. */
    fun estimateWalkTicks(distance: Double, caps: NavCaps): Double = distance * caps.groundTicksPerBlock

    /**
     * Rockets needed before a take-off is worth attempting.
     *
     * One to leave the ground with speed, one to reach cruising height. Fewer than that and the
     * glide is over before it has climbed, which is worse than never having taken off.
     */
    const val MIN_ROCKETS = 2

    /**
     * Shortest journey worth flying.
     *
     * Well above the point where the clock says flying wins, because the clock cannot price the
     * rockets or the risk of arriving somewhere slightly wrong.
     */
    const val MIN_FLIGHT_DISTANCE = 150.0

    /** Jumping, casting the wing and climbing out to cruising height. */
    private const val TAKEOFF_TICKS = 100.0

    /** The descent, the flare and the touchdown. */
    private const val LANDING_TICKS = 60.0
}
