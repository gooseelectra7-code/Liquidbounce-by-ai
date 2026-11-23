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

package net.ccbluex.liquidbounce.utils.navigation.pathing.cost

import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns terrain into ticks.
 *
 * The previous engine priced every move from a flat table of constants — soul sand cost the same as
 * stone, ice cost the same as gravel, and lava was indistinguishable from water. Because those
 * numbers could not be trusted, it had to run the search twice: once forbidding digging, then again
 * allowing it, and pick whichever looked better. With costs that actually mean something, a single
 * search prefers walking over digging on its own, and does so for the right reason.
 *
 * Every value here is in ticks and must respect [NavTiming.MIN_COST_PER_BLOCK] per block of
 * horizontal travel; `GroundPlannerTest` enforces that over random terrain, because the heuristics
 * depend on it for optimality. Falling is the one exception and is deliberately below the floor —
 * which is exactly why no goal may price a descent.
 */
object NavCostModel {

    // -----------------------------------------------------------------------
    //  Terrain
    // -----------------------------------------------------------------------

    /**
     * Speed multiplier for walking on top of a block of this class.
     *
     * [NavBlockClass.FAST] must stay at [NavTiming.FASTEST_TERRAIN_MULTIPLIER]; anything lower would
     * put an edge below the admissibility floor and quietly cost the search its optimality.
     */
    fun surfaceMultiplier(navClass: Int): Double = when (navClass) {
        NavBlockClass.FAST -> NavTiming.FASTEST_TERRAIN_MULTIPLIER
        NavBlockClass.SLOW -> SLOW_SURFACE_MULTIPLIER
        else -> 1.0
    }

    /**
     * Speed multiplier for moving through a block of this class at body height.
     *
     * Cobwebs dominate everything else here — crossing one costs more than walking around almost
     * any obstacle, which is exactly the routing decision a player would make.
     */
    fun occupancyMultiplier(navClass: Int): Double = when (navClass) {
        NavBlockClass.COBWEB -> COBWEB_MULTIPLIER
        NavBlockClass.WATER -> WATER_MULTIPLIER
        else -> 1.0
    }

    /**
     * Additive penalty, in ticks, for a cell the player's body would occupy.
     *
     * A penalty rather than a ban: when the only way onward is through fire or a berry bush, a route
     * that takes the damage still beats no route at all. [NavCaps.hazardAversion] sets how much
     * detour is worth spending to avoid one.
     *
     * Lava does not appear here. It is not passable at all — walking into it is death rather than a
     * cost, so it is a wall to the planner and never reaches this function.
     */
    fun hazardPenalty(navClass: Int, caps: NavCaps): Double =
        if (navClass == NavBlockClass.HAZARD) caps.hazardAversion else 0.0

    /** Additive penalty for standing on top of a damaging block, such as magma. */
    fun surfaceHazardPenalty(navClass: Int, caps: NavCaps): Double =
        if (navClass == NavBlockClass.HAZARD_SOLID) caps.hazardAversion else 0.0

    /**
     * Cost of one block of horizontal travel, given what is underfoot and what the body passes
     * through.
     *
     * @param surfaceClass class of the block being walked on
     * @param feetClass class of the block the feet occupy
     * @param headClass class of the block the head occupies
     */
    fun stepCost(surfaceClass: Int, feetClass: Int, headClass: Int, caps: NavCaps): Double {
        val base = caps.groundTicksPerBlock *
            surfaceMultiplier(surfaceClass) *
            max(occupancyMultiplier(feetClass), occupancyMultiplier(headClass))
        return base +
            hazardPenalty(feetClass, caps) +
            hazardPenalty(headClass, caps) +
            surfaceHazardPenalty(surfaceClass, caps)
    }

    // -----------------------------------------------------------------------
    //  Vertical movement
    // -----------------------------------------------------------------------

    /** Extra ticks for jumping up one block on top of the horizontal travel it overlaps. */
    fun ascendPenalty(): Double = NavTiming.JUMP_PENALTY

    /**
     * Ticks to fall [blocks] blocks, from the vanilla free-fall curve.
     *
     * Falling is fast, so this is small — the interesting part of a drop is [fallDamage].
     */
    fun fallTicks(blocks: Int): Double {
        if (blocks <= 0) return 0.0
        // t = sqrt(2h/g) in seconds, at 20 ticks per second, with Minecraft's ~0.08 blocks/tick².
        return sqrt(2.0 * blocks / GRAVITY_BLOCKS_PER_SECOND_SQUARED) * TICKS_PER_SECOND
    }

    /**
     * Fall damage in half-hearts for a drop of [blocks], or zero when [landingClass] cancels it.
     *
     * Vanilla deals one half-heart per block past the first three.
     */
    fun fallDamage(blocks: Int, landingClass: Int): Double {
        if (NavBlockClass.breaksFall(landingClass)) return 0.0
        return max(0.0, blocks - FREE_FALL_BLOCKS.toDouble())
    }

    /**
     * Cost of dropping [blocks] blocks onto [landingClass], or infinity when the route may not.
     *
     * The landing is decided before the drop length, because a soft landing makes the length
     * irrelevant: a fifty-block dive into water is free, and refusing it because it exceeds
     * [NavCaps.maxFallBlocks] would throw away the fastest route down most cliffs. Once damage is
     * actually on the table, it is priced so that a shortcut costing health competes honestly
     * against the longer way around — and refused outright if it could kill.
     */
    fun fallCost(blocks: Int, landingClass: Int, caps: NavCaps): Double {
        // The distance cap applies to every drop, not only to the harmless ones. Checking it inside
        // the damage-free branch made it dead above three blocks — which is every drop that hurts at
        // all — so the setting appeared to run to twenty and in fact did nothing past its fourth
        // notch. Soft landings are exempt, because water at the bottom of a ravine is a way down
        // rather than a risk.
        if (blocks > caps.maxFallBlocks && !NavBlockClass.breaksFall(landingClass)) {
            return Double.POSITIVE_INFINITY
        }

        val damage = fallDamage(blocks, landingClass)
        if (damage <= 0.0) return fallTicks(blocks)

        // Damage has to be permitted, and never enough to be the thing that kills us — the margin
        // covers the health lost to whatever else is going on while we walk.
        if (!caps.allowFallDamage || damage >= caps.health - LETHAL_HEALTH_MARGIN) {
            return Double.POSITIVE_INFINITY
        }

        return fallTicks(blocks) + damage * TICKS_PER_HALF_HEART
    }

    // -----------------------------------------------------------------------
    //  World mutation
    // -----------------------------------------------------------------------

    /**
     * Ticks to break a block in [digBucket], or infinity when it cannot be broken.
     *
     * The bucket is a logarithmic hardness class recorded once when the block entered the cache, so
     * this is a table lookup and a multiply rather than a tool-and-enchantment calculation in the
     * inner loop.
     */
    fun breakCost(digBucket: Int, caps: NavCaps): Double {
        if (!caps.allowBreak) return Double.POSITIVE_INFINITY
        if (digBucket >= NavBlockClass.DIG_UNBREAKABLE) return Double.POSITIVE_INFINITY

        val hardness = NavBlockClass.bucketToHardness(digBucket)
        val time = max(MIN_BREAK_TICKS, hardness * caps.breakTicksPerHardness)

        // Time is not the whole price of a block. A good tool goes through dirt in a couple of
        // ticks — less than a single walking step — so a model that counts only the clock will
        // cheerfully mine the floor out from under itself rather than step down off it, and tunnel
        // through a hillside it could have walked round. What that costs is durability and a hole
        // in someone's world, neither of which a stopwatch measures, so it is charged here.
        return time + caps.digAversion
    }

    /**
     * Ticks to place one block, or infinity when the route may not place.
     *
     * Carries the same surcharge as breaking one, and for the same reason: four ticks of animation
     * is all the clock sees, so a route will happily bridge over a one-block dip rather than step
     * down into it and out again. That block came out of somebody's inventory and is now part of
     * the landscape, and neither of those is free.
     */
    fun placeCost(caps: NavCaps): Double =
        if (caps.allowPlace) NavTiming.PLACE + caps.placeAversion else Double.POSITIVE_INFINITY

    /**
     * Extra ticks charged for swimming a block with the head under water.
     *
     * Not a speed difference — submerged and surface swimming are equally quick — but a cost all
     * the same, because the submerged one is paid for in air. Two blocks of swimming per block of
     * depth: enough that a crossing always prefers the surface, and little enough that a route
     * still ducks under an overhang or down to something it actually wants.
     */
    const val SUBMERGED_PENALTY = 6.0

    // -----------------------------------------------------------------------
    //  Tuning constants
    // -----------------------------------------------------------------------

    /** Soul sand, honey and mud roughly halve movement speed. */
    private const val SLOW_SURFACE_MULTIPLIER = 2.1

    /** Cobwebs are catastrophically slow — going around is nearly always right. */
    private const val COBWEB_MULTIPLIER = 12.0

    /** Wading and swimming are far slower than walking. */
    private const val WATER_MULTIPLIER = 2.5

    /** Blocks of free fall before vanilla starts dealing damage. */
    private const val FREE_FALL_BLOCKS = 3

    /** How many ticks of detour one half-heart of damage is worth avoiding. */
    private const val TICKS_PER_HALF_HEART = 20.0

    /** Health kept in reserve so a planned drop can never be the thing that kills us. */
    private const val LETHAL_HEALTH_MARGIN = 6.0

    /** Even an instantly-breakable block costs a tick of swing. */
    private const val MIN_BREAK_TICKS = 1.0

    /**
     * Minecraft's gravity, not Earth's.
     *
     * A falling body gains about 0.08 blocks per tick each tick, which is 32 blocks per second
     * squared — over three times the 9.8 this constant used to hold. That mistake made every drop
     * look about eighty per cent slower than it is, and the cost model reads drops against walking:
     * a one-block step down was priced at nearly thirteen ticks, so stepping down into a dip and
     * back out of it cost more than bridging straight over it, and the route bridged.
     */
    private const val GRAVITY_BLOCKS_PER_SECOND_SQUARED = 32.0
    private const val TICKS_PER_SECOND = 20.0
}
