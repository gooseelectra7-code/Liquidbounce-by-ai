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

package net.ccbluex.liquidbounce.utils.entity

import net.ccbluex.liquidbounce.utils.math.fma
import net.ccbluex.liquidbounce.utils.math.minus
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * A utility which predicts the position of something in n ticks.
 */
fun interface PositionExtrapolation {
    fun getPositionInTicks(ticks: Double): Vec3

    companion object {
        @JvmStatic
        fun getBestForEntity(target: Entity): PositionExtrapolation {
            return when (target) {
                is Player -> PlayerSimulationExtrapolation(target)
                else -> LinearPositionExtrapolation(target)
            }
        }

        @JvmStatic
        fun constant(pos: Vec3): PositionExtrapolation = PositionExtrapolation { pos }
    }
}

/**
 * A utility class which assumes that the subject is moving at a specified speed.
 */
class LinearPositionExtrapolation(
    private val basePosition: Vec3,
    private val velocity: Vec3
) : PositionExtrapolation {
    constructor(entity: Entity) : this(entity.position(), entity.position() - entity.lastPos)

    override fun getPositionInTicks(ticks: Double): Vec3 {
        return basePosition.fma(ticks, velocity)
    }

}

class PlayerSimulationExtrapolation(private val simulation: SimulatedPlayerCache) : PositionExtrapolation {
    constructor(player: Player) : this(PlayerSimulationCache.getSimulationForOtherPlayers(player))

    override fun getPositionInTicks(ticks: Double): Vec3 {
        val ticks = max(0, round(ticks.coerceAtMost(30.0)).toInt())
        return this.simulation.getSnapshotAt(ticks).pos
    }
}

/**
 * A player simulation read at fractional ticks: the position is interpolated between the two
 * neighbouring snapshots instead of rounded to one of them (as [PlayerSimulationExtrapolation]
 * does), so a solver scanning the flight time sees a continuous target, and the whole path is
 * shifted by [offset].
 */
class InterpolatedSimulationExtrapolation(
    private val simulation: SimulatedPlayerCache,
    private val offset: Vec3 = Vec3.ZERO,
) : PositionExtrapolation {

    override fun getPositionInTicks(ticks: Double): Vec3 {
        val clamped = max(0.0, ticks)
        val lower = floor(clamped).toInt()
        val fraction = clamped - lower
        val lowerPos = simulation.getSnapshotAt(lower).pos

        val pos = if (fraction > 0.0) {
            lowerPos.lerp(simulation.getSnapshotAt(lower + 1).pos, fraction)
        } else {
            lowerPos
        }

        return pos.add(offset)
    }

}

/**
 * Shifts a base extrapolation forward by a fixed lead and clamps the total look-ahead.
 *
 * The projectile solvers query the target position at `flightTicks` — the arrow's time of
 * flight. The shot itself is additionally delayed by the network: the position we see of the
 * target is already one half round-trip old, and the release packet needs the other half to
 * reach the server. A hit therefore has to lead by `flightTicks + roundTripTicks` counted
 * from what the client sees. That shift (plus any manual lead) is what this wrapper adds,
 * bounded by [maxTicks] because no extrapolation model stays honest forever — the player
 * simulation itself never simulates past 30 ticks.
 */
class LeadingPositionExtrapolation(
    private val base: PositionExtrapolation,
    private val leadTicks: Double,
    private val maxTicks: Double,
) : PositionExtrapolation {

    override fun getPositionInTicks(ticks: Double): Vec3 {
        val shifted = (ticks + leadTicks).coerceIn(0.0, maxTicks)
        return base.getPositionInTicks(shifted)
    }

}
