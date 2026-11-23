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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent

/** How following a route ended, or that it has not yet. */
enum class NavExecStatus {
    /** Still travelling. */
    RUNNING,

    /** The end of the route was reached. */
    FINISHED,

    /** Something went wrong and the route should be recalculated. */
    NEEDS_REPLAN,
}

/**
 * Drives the player along one leg of a route, one tick at a time.
 *
 * There is one implementation per way of travelling, because they are driven in genuinely different
 * ways: a walking body is steered by its input keys, a boat by its rudder, a glider by its pitch.
 * What they share is this contract and one hard rule — everything they do goes through
 * [MovementInputEvent], [SprintEvent] and
 * [net.ccbluex.liquidbounce.utils.aiming.RotationManager]. No executor writes velocity or forges a
 * movement packet; everything here is something a person could do with a keyboard.
 *
 * An executor must never report [NavExecStatus.FINISHED] or [NavExecStatus.NEEDS_REPLAN] from a
 * position the next planner cannot plan from — most concretely, a glider hands back control only
 * once it is on the ground, since a lattice search started in mid-air has no floor to stand on and
 * would simply call the goal unreachable.
 */
interface NavExecutor {

    /** Advances the leg by one tick, writing whatever input this way of travelling needs. */
    fun tick(event: MovementInputEvent): NavExecStatus

    /** Chance to hold sprint down; irrelevant to anything that is not moving on its own legs. */
    fun onSprint(event: SprintEvent) {}

    /** How far along this leg we are, 0..1, for the debug overlay. */
    val progress: Float
}
