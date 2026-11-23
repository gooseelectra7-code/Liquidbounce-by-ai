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

import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavPath

/**
 * One stretch of a journey travelled a single way.
 *
 * A leg owns the path its own planner produced. Where legs meet, the executor for one hands over to
 * the executor for the next — mounting a horse, pushing off in a boat, taking off, landing.
 */
class NavLeg(
    val mode: NavLocomotion,
    val path: NavPath,
) {
    val isEmpty: Boolean get() = path.isEmpty

    /** Predicted time to travel this leg, in ticks. */
    val cost: Double get() = path.totalCost

    override fun toString(): String = "${mode.label}(${path.size} nodes, %.0ft)".format(cost)
}

/**
 * A complete journey, possibly using several ways of travelling.
 *
 * Most routes are a single walking leg. The interesting ones are not: walk to the shore, take the
 * boat across, walk inland. Keeping legs separate rather than flattening everything into one node
 * space is what lets each mode be planned by something that understands it — and what keeps a
 * failure in one leg from invalidating the rest.
 */
class NavRoute(
    val legs: List<NavLeg>,

    /** Whether the last leg actually arrives, or the journey is a best-effort segment. */
    val reachedGoal: Boolean,
) {

    /** Total predicted travel time, in ticks. */
    val totalCost: Double get() = legs.sumOf { it.cost }

    val isEmpty: Boolean get() = legs.all { it.isEmpty }

    /** Modes used, in order, for the debug overlay and chat summaries. */
    fun describeModes(): String =
        if (legs.isEmpty()) "empty" else legs.joinToString(" → ") { it.mode.label }

    companion object {
        /** The ordinary case: one leg, on foot. */
        fun walking(path: NavPath): NavRoute =
            NavRoute(listOf(NavLeg(NavLocomotion.WALK, path)), path.reachedGoal)

        /** Nowhere to go. */
        val EMPTY = NavRoute(emptyList(), reachedGoal = false)
    }
}
