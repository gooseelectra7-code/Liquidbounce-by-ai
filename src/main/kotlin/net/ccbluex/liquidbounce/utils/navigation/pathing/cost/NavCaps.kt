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

import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavBridgeStyle
import kotlin.math.ceil

/**
 * What the player is allowed and able to do on this route, snapshotted before a search starts.
 *
 * Immutable and free of live game state, so it can be read from the planner thread. Anything that
 * depends on the inventory or the player's condition is resolved once, on the main thread, when the
 * snapshot is taken — the search must never reach back into the game.
 */
data class NavCaps(
    /** May the route sprint? Off means every ground cost uses the walking timing. */
    val canSprint: Boolean = true,

    /** May the route include jumps across gaps? */
    val allowParkour: Boolean = true,

    /** Longest gap, in blocks, a parkour jump may cross. Vanilla sprint-jump reaches four. */
    val maxParkourGap: Int = 3,

    /** May the route break blocks? */
    val allowBreak: Boolean = false,

    /** May the route place blocks? */
    val allowPlace: Boolean = false,

    /**
     * How many blocks are available to place.
     *
     * Bounds how much bridging or pillaring a route may plan. Without it the search happily commits
     * to a three-hundred-block bridge on a stack of sixty-four.
     */
    val placeableBlocks: Int = 0,

    /**
     * Ticks to break one block of hardness 1 with the best tool the player is carrying.
     *
     * Folding the whole tool-and-enchantment calculation into a single number here is what lets the
     * search price a dig edge with one multiply instead of an inventory scan.
     */
    val breakTicksPerHardness: Double = DEFAULT_BREAK_TICKS_PER_HARDNESS,

    /** May the route take fall damage at all? */
    val allowFallDamage: Boolean = false,

    /**
     * Longest drop, in blocks, the route may take. A hard limit, whatever else is allowed.
     *
     * Three is the vanilla free-fall allowance, and with [allowFallDamage] on anything past it is
     * additionally priced against the player's health — but priced, not permitted: the limit still
     * binds. It used to be consulted only on the damage-free branch, which meant it did nothing at
     * all for any drop that hurt, and a profile with damage allowed would happily plan a
     * thirteen-block fall because the arithmetic said it was survivable. A player setting a maximum
     * drop means the maximum drop.
     *
     * A landing that cancels the fall — water, a cobweb — is exempt, since the height it was reached
     * from costs nothing.
     */
    val maxFallBlocks: Int = 3,

    /** Health available to spend on fall damage, in half-hearts. */
    val health: Double = DEFAULT_HEALTH,

    /** May the route swim through water? */
    val allowSwim: Boolean = true,

    /**
     * May the route take to the air?
     *
     * Set only when flying is both wanted and possible: the setting is on *and* a serviceable wing
     * is actually worn. Whether it is worth it for a particular journey is the router's decision,
     * not this flag's.
     */
    val allowElytra: Boolean = false,

    /**
     * Firework rockets carried where they can be reached in flight.
     *
     * A glide without rockets is a one-way trade of height for distance, so this is what decides
     * whether a flight can climb to its cruising altitude at all.
     */
    val fireworks: Int = 0,

    /**
     * May a flight be searched through space with a roof on it — the Nether, a cave system?
     *
     * Separate from [allowElytra] because it is a different proposition. Open sky is a straight
     * line over terrain the route can simply stay above; enclosed flight threads a searched course
     * between walls, where being wrong means meeting one.
     */
    val allowEnclosedFlight: Boolean = true,

    /**
     * Ticks per block of travel, when something other than the player's own legs is moving them.
     *
     * Set when riding: a horse outruns a sprint, and pricing its route at walking speed would make
     * the search prefer routes a rider would never take. Null means travel on foot.
     */
    val travelTicksPerBlock: Double? = null,

    /**
     * How many blocks of height a single step may gain.
     *
     * One for a player. A horse clears far more, which changes routing substantially — terrain that
     * forces a long detour on foot is a straight line on horseback.
     */
    val maxStepUp: Int = 1,

    /**
     * Width of the body being routed, in blocks.
     *
     * A player is slim enough to fit any gap the lattice offers, which is why this can be ignored
     * on foot. A horse is more than twice as wide and simply does not fit where a player does —
     * planning its route against a player's footprint produces gaps it cannot enter and destinations
     * it cannot reach.
     */
    val bodyWidth: Double = PLAYER_WIDTH,

    /** Height of the body being routed, in blocks. */
    val bodyHeight: Double = PLAYER_HEIGHT,

    /**
     * May a long search look at the terrain at the scale of rooms first?
     *
     * On by default, and worth being able to switch off: the coarse map is guidance, and comparing a
     * route with and without it is how the guidance is judged.
     */
    val allowHierarchy: Boolean = true,

    /**
     * What breaking one block is worth avoiding, in ticks, beyond the time it takes.
     *
     * Mining is often the quickest way through and almost never the one a player wants: a sharp
     * tool goes through soft ground faster than a step, so priced on the clock alone a route will
     * dig the floor rather than walk down off it. This is the price of the durability and of the
     * hole — set it to zero to get a pure stopwatch, or high to make digging a last resort.
     */
    val digAversion: Double = DEFAULT_DIG_AVERSION,

    /**
     * What placing one block is worth avoiding, in ticks, beyond the four it takes to swing.
     *
     * The mirror of [digAversion], and needed for the same reason: on the clock alone, throwing a
     * block across a one-block dip is cheaper than stepping down into it and back out, so a route
     * with blocks in its pocket will pave every ditch it meets rather than walk through it.
     */
    val placeAversion: Double = DEFAULT_PLACE_AVERSION,

    /**
     * How the executor builds across a gap.
     *
     * Carried here rather than read from a module so that every consumer — a travel command, an
     * autofarm, a combat follow — can choose for itself whether the route is allowed to be quick
     * about it or has to be certain.
     */
    val bridgeStyle: NavBridgeStyle = NavBridgeStyle.SNEAK,

    /**
     * How strongly to steer around hazards.
     *
     * Applied as an additive tick penalty rather than an outright ban, so a route still exists when
     * the only way through is past the lava — it just has to be worth it.
     */
    val hazardAversion: Double = DEFAULT_HAZARD_AVERSION,
) {

    /** Base ticks for one block of ordinary ground travel under these capabilities. */
    val groundTicksPerBlock: Double
        get() = travelTicksPerBlock ?: if (canSprint) NavTiming.SPRINT_PER_BLOCK else NavTiming.WALK_PER_BLOCK

    /**
     * Cheapest a block of travel can possibly cost under these capabilities.
     *
     * Every admissible heuristic is distance multiplied by this. The goals are written against
     * walking, so anything faster has to tell the search to scale their estimates down — see
     * [heuristicScale]. Getting this wrong does not fail loudly; it silently costs optimality.
     */
    val minCostPerBlock: Double
        get() = groundTicksPerBlock * NavTiming.FASTEST_TERRAIN_MULTIPLIER

    /**
     * Factor to apply to a goal's estimate so it stays admissible under these capabilities.
     *
     * One for a player on foot, below one for anything quicker. Never above one: over-scaling an
     * estimate is exactly the overestimation that breaks A*.
     */
    val heuristicScale: Double
        get() = (minCostPerBlock / NavTiming.MIN_COST_PER_BLOCK).coerceAtMost(1.0)

    /** True when the route may change the world in any way. */
    val canMutate: Boolean get() = allowBreak || allowPlace

    /** True when a glide is available at all: a wing to fly and rockets to fly it with. */
    val canFly: Boolean get() = allowElytra && fireworks > 0 && travelTicksPerBlock == null

    /**
     * How many columns to either side of the centre one the body overlaps.
     *
     * Zero for anything up to a block wide, which keeps the player's clearance check to the two
     * reads it has always been. A body centred on a block centre spans `0.5 ± width/2`, so this is
     * how far past that block it reaches.
     */
    val bodyColumnRadius: Int
        get() = ceil((bodyWidth - 1.0) / 2.0).toInt().coerceAtLeast(0)

    /** Blocks of vertical clearance the body needs. */
    val bodyHeightBlocks: Int
        get() = ceil(bodyHeight).toInt().coerceAtLeast(1)

    companion object {
        /**
         * Break timing for a bare hand on hardness-1 stone, used when no snapshot of the real
         * inventory is available. Pessimistic on purpose: over-estimating digging biases routes
         * toward walking, which is the safe direction to be wrong in.
         */
        const val DEFAULT_BREAK_TICKS_PER_HARDNESS = 100.0

        /** A full health bar, in half-hearts. */
        const val DEFAULT_HEALTH = 20.0

        /** Ticks of detour worth taking to avoid one hazardous cell. */
        const val DEFAULT_HAZARD_AVERSION = 60.0

        /**
         * Ticks of detour worth taking to leave one block standing.
         *
         * About eight blocks of walking. Digging still wins where it genuinely is a shortcut —
         * through a hill rather than round it — and loses everywhere it was only winning on the
         * technicality that a shovel is faster than a footstep.
         */
        const val DEFAULT_DIG_AVERSION = 30.0

        /**
         * Ticks of detour worth taking to keep one block in the pocket.
         *
         * The same eight blocks of walking as [DEFAULT_DIG_AVERSION]. Bridging a real gap still
         * wins, because the alternative there is not walking round — it is not arriving.
         */
        const val DEFAULT_PLACE_AVERSION = 30.0

        /** Vanilla player hitbox width. */
        const val PLAYER_WIDTH = 0.6

        /** Vanilla player hitbox height. */
        const val PLAYER_HEIGHT = 1.8

        /** Walk anywhere, touch nothing. What a plain travel request wants. */
        @JvmField
        val TRAVEL = NavCaps()

        /** Walking only, no world changes and no risk. The most conservative useful profile. */
        @JvmField
        val SAFE = NavCaps(allowParkour = false, allowFallDamage = false, maxFallBlocks = 3)
    }
}
