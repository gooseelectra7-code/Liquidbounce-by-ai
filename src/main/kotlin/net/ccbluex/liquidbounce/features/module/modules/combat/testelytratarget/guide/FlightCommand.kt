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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.world.phys.Vec3

/**
 * One tick of decision, produced during the plan phase and consumed during the execute phase.
 *
 * The split matters: planning happens at the head of the tick, before the rotation system arbitrates
 * requests, while acting has to happen where vanilla would have acted. Passing a value between the
 * two rather than recomputing keeps the two phases from ever disagreeing, and makes "the plan is
 * stale, do nothing" expressible as a null.
 *
 * @param rotation where to point the wing; in the holonomic mode it only points where we are going
 * @param boost whether a firework should be spent this tick
 * @param velocity the whole velocity to force, used only by the holonomic mode, where the flight
 *   model is a written velocity rather than a steered one; null in the modes that really fly
 * @param diagnostics the numbers behind the decision, kept for the debug overlay
 */
data class FlightCommand(
    val rotation: Rotation,
    val boost: Boolean,
    val velocity: Vec3?,
    val diagnostics: Diagnostics,
) {
    /**
     * Why the command looks the way it does. Carried on every command rather than recomputed for the
     * overlay, so what is drawn is provably the thing that was flown and not a second guess at it.
     */
    data class Diagnostics(
        val range: Double,
        val closure: Double,
        val standoffRange: Double,
        val orbitWeight: Double,
        val energy: Double,
        val targetEnergy: Double,
        val energyDeficit: Double,
        val ticksInBand: Int,
        val minClearance: Double,
        val recovered: Boolean,
    )
}
