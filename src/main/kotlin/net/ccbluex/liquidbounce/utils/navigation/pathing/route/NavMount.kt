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

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.animal.equine.AbstractHorse
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import kotlin.math.floor

/**
 * What the thing the player is currently riding can do.
 *
 * Read once, on the main thread, and folded into the capability snapshot the planner works from —
 * the search itself never sees an entity.
 */
class NavMount(
    val mode: NavLocomotion,

    /** Ticks to travel one block on level ground. */
    val ticksPerBlock: Double,

    /** Height in blocks a single step may gain. */
    val maxStepUp: Int,

    /**
     * Width of the animal, in blocks.
     *
     * A horse is more than twice a player's width, so it does not fit where a player does. Routing
     * it against a player's footprint plans it through gaps it cannot enter and up to destinations
     * it cannot physically reach.
     */
    val bodyWidth: Double,

    /** Height of the animal, in blocks. */
    val bodyHeight: Double,
) {
    companion object {

        /**
         * Reads the vehicle the player is riding, or null when they are on their own feet or on
         * something that cannot be steered anywhere useful.
         */
        fun of(vehicle: Entity?): NavMount? = when (vehicle) {
            null -> null
            is AbstractHorse -> horse(vehicle)
            is AbstractBoat -> boat(vehicle)
            is LivingEntity -> genericMount(vehicle)
            else -> null
        }

        private fun boat(boat: AbstractBoat): NavMount = NavMount(
            mode = NavLocomotion.BOAT,
            ticksPerBlock = 1.0 / NavLocomotion.BOAT.blocksPerTick,
            maxStepUp = 1,
            bodyWidth = boat.bbWidth.toDouble(),
            bodyHeight = boat.bbHeight.toDouble(),
        )

        /**
         * Horses vary enormously — speed and jump strength are per-animal attributes, and a good
         * horse routes very differently from a poor one, clearing terrain that would force a long
         * detour.
         */
        private fun horse(horse: AbstractHorse): NavMount {
            val speed = horse.getAttributeValue(Attributes.MOVEMENT_SPEED)
            return NavMount(
                mode = NavLocomotion.MOUNT,
                ticksPerBlock = ticksPerBlockFor(speed),
                // Only what the animal clears by walking. Anything higher needs a charged jump —
                // held for several ticks and then released — which the executor cannot yet perform,
                // and a route that plans climbs the body cannot make is worse than a longer one: it
                // fails partway, replans, and tries the same impossible climb again.
                maxStepUp = stepUpForJumpStrength(horse.getAttributeValue(Attributes.JUMP_STRENGTH))
                    .coerceAtMost(WALKABLE_STEP_UP),
                bodyWidth = horse.bbWidth.toDouble(),
                bodyHeight = horse.bbHeight.toDouble(),
            )
        }

        private fun genericMount(entity: LivingEntity): NavMount = NavMount(
            mode = NavLocomotion.MOUNT,
            ticksPerBlock = ticksPerBlockFor(entity.getAttributeValue(Attributes.MOVEMENT_SPEED)),
            maxStepUp = 1,
            bodyWidth = entity.bbWidth.toDouble(),
            bodyHeight = entity.bbHeight.toDouble(),
        )

        /**
         * Converts a movement-speed attribute into travel time.
         *
         * The attribute is roughly blocks per tick already; the floor guards against a stat so low
         * that a route would be priced as effectively free to abandon.
         */
        private fun ticksPerBlockFor(speed: Double): Double =
            (1.0 / speed.coerceAtLeast(MIN_SPEED)).coerceAtMost(MAX_TICKS_PER_BLOCK)

        /**
         * Jump height from a horse's jump-strength attribute.
         *
         * Vanilla's own approximation of the curve, floored to whole blocks because the planner
         * works on a lattice and half a block of extra reach buys nothing.
         */
        private fun stepUpForJumpStrength(strength: Double): Int {
            if (strength <= 0.0) return 1
            val height = HEIGHT_A * strength * strength * strength +
                HEIGHT_B * strength * strength +
                HEIGHT_C * strength +
                HEIGHT_D
            return floor(height).toInt().coerceIn(1, MAX_STEP_UP)
        }

        /** Slowest speed worth pricing; below this the mount is not going anywhere. */
        private const val MIN_SPEED = 0.05

        /** Anything slower than this is priced as walking would be. */
        private const val MAX_TICKS_PER_BLOCK = 10.0

        /** Even the best horse is not clearing more than this on a block lattice. */
        private const val MAX_STEP_UP = 5

        /**
         * Height a mount clears by simply walking into it, no jump involved.
         *
         * The ceiling on planned climbs until charged jumping is implemented. Lifting it without
         * that is how a route ends up oscillating: the animal walks to the foot of a climb it
         * cannot make, the route is abandoned, and the replan proposes the very same climb.
         */
        private const val WALKABLE_STEP_UP = 1

        // Vanilla's jump-height polynomial for horses.
        private const val HEIGHT_A = -0.1817584952
        private const val HEIGHT_B = 3.689713992
        private const val HEIGHT_C = 2.128599134
        private const val HEIGHT_D = -0.343930367
    }
}
