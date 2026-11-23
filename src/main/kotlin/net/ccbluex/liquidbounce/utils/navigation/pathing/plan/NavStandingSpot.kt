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

package net.ccbluex.liquidbounce.utils.navigation.pathing.plan

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos

/**
 * Whether a player can hold a given block position, and where to put them if not.
 *
 * The single answer to "can I stand here", shared by the planner's inner loop and by everything that
 * has to turn a position a user pointed at into one a route can actually end on. Keeping the two in
 * separate copies is how a goal ends up being something the search can never satisfy: the caller
 * thinks the spot is fine, the planner disagrees, and the search can only report that by exhausting
 * everything reachable first.
 */
object NavStandingSpot {

    /**
     * Does the body fit here, ignoring what holds it up?
     *
     * A player is under a block wide and needs only its own column checked, which is the fast path
     * and the overwhelmingly common one. Anything wider — a horse is more than twice a player —
     * overlaps its neighbours, and checking only the centre column plans it into gaps it cannot
     * enter and up against walls it cannot reach.
     */
    fun bodyFits(source: NavBlockSource, x: Int, y: Int, z: Int, caps: NavCaps = NavCaps.TRAVEL): Boolean {
        val height = caps.bodyHeightBlocks
        val radius = caps.bodyColumnRadius

        if (radius == 0) {
            for (level in 0 until height) {
                if (!NavBlockClass.isPassable(source.navClass(x, y + level, z))) return false
            }
            return true
        }

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                for (level in 0 until height) {
                    if (!NavBlockClass.isPassable(source.navClass(x + dx, y + level, z + dz))) return false
                }
            }
        }
        return true
    }

    /** Can the body hold this position, whether by standing, floating or hanging on? */
    fun canOccupy(source: NavBlockSource, x: Int, y: Int, z: Int, caps: NavCaps = NavCaps.TRAVEL): Boolean {
        if (!bodyFits(source, x, y, z, caps)) return false
        val feet = source.navClass(x, y, z)
        if (feet == NavBlockClass.CLIMBABLE) return true
        if (caps.allowSwim && feet == NavBlockClass.WATER) return true
        return NavBlockClass.isStandable(source.navClass(x, y - 1, z))
    }

    /**
     * Moves a requested position to somewhere a player can actually be.
     *
     * Two different failures need opposite corrections, so they are handled separately rather than
     * by searching blindly in both directions:
     *
     * - **No room for the body.** The position is inside something solid, which is what pointing at
     *   the ground gives you — plants have no collision, so the crosshair ray reports the dirt
     *   underneath. Climbing finds the space just above it.
     * - **Nothing underfoot.** The position is in mid-air. Falling down the column finds whatever
     *   is actually holding weight, which is where a player who walked there would end up anyway.
     *
     * Returns the original position when neither correction finds anything, leaving the caller to
     * report an honest failure rather than silently walking somewhere else.
     */
    fun resolve(
        source: NavBlockSource,
        target: BlockPos,
        caps: NavCaps = NavCaps.TRAVEL,
        maxLift: Int = DEFAULT_MAX_LIFT,
        maxDrop: Int = DEFAULT_MAX_DROP,
    ): BlockPos {
        if (canOccupy(source, target.x, target.y, target.z, caps)) return target

        if (bodyFits(source, target.x, target.y, target.z, caps)) {
            for (drop in 1..maxDrop) {
                val y = target.y - drop
                if (canOccupy(source, target.x, y, target.z, caps)) return BlockPos(target.x, y, target.z)
                // Stop at the first thing that blocks the body: anything past it is inside terrain
                // and belongs to a different surface entirely.
                if (!bodyFits(source, target.x, y, target.z, caps)) break
            }
            return target
        }

        for (lift in 1..maxLift) {
            val y = target.y + lift
            if (canOccupy(source, target.x, y, target.z, caps)) return BlockPos(target.x, y, target.z)
        }
        return target
    }

    /** Why [canOccupy] would refuse, phrased for a human. Returns [STANDABLE] when it would not. */
    fun describe(source: NavBlockSource, x: Int, y: Int, z: Int, caps: NavCaps = NavCaps.TRAVEL): String {
        val below = source.navClass(x, y - 1, z)
        val feet = source.navClass(x, y, z)
        val head = source.navClass(x, y + 1, z)

        val reasons = buildList {
            if (!NavBlockClass.isPassable(feet)) add("feet blocked by ${NavBlockClass.nameOf(feet)}")
            if (!NavBlockClass.isPassable(head)) add("head blocked by ${NavBlockClass.nameOf(head)}")
            // A wide body can be refused with its own column completely clear, which reads as a
            // baffling rejection unless it is spelled out.
            if (caps.bodyColumnRadius > 0 && !bodyFits(source, x, y, z, caps)) {
                add("too wide for the gap (needs ${caps.bodyColumnRadius * 2 + 1} blocks)")
            }
            val supported = NavBlockClass.isStandable(below) ||
                feet == NavBlockClass.CLIMBABLE ||
                (caps.allowSwim && feet == NavBlockClass.WATER)
            if (!supported) add("nothing to stand on (${NavBlockClass.nameOf(below)} below)")
        }

        return if (reasons.isEmpty()) STANDABLE else reasons.joinToString("; ")
    }

    /** What [describe] returns when the position is fine. */
    const val STANDABLE = "standable"

    /** Enough to step out of a floor, a slab and a carpet stacked together. */
    const val DEFAULT_MAX_LIFT = 3

    /** Enough to fall from build height to bedrock, so a mid-air target always finds ground. */
    const val DEFAULT_MAX_DROP = 384
}
