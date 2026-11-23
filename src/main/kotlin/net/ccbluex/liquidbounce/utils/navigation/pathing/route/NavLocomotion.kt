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

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming

/** Vanilla boat on water, ~8.6 m/s. */
private const val BOAT_BLOCKS_PER_TICK = 0.43

/** A middling horse, ~9 m/s. Deliberately not the best possible one. */
private const val MOUNT_BLOCKS_PER_TICK = 0.45

/** Firework-boosted glide, conservatively. Unboosted gliding is slower but still well ahead. */
private const val ELYTRA_BLOCKS_PER_TICK = 1.5

/**
 * How the player is getting somewhere.
 *
 * Each mode has its own planner and its own executor, because they search different spaces and are
 * driven in different ways — a boat is steered by the player's yaw, an elytra by pitch, and a horse
 * jumps five blocks where a player jumps one. Trying to express all of that as one lattice was the
 * option we deliberately did not take.
 *
 * [blocksPerTick] is the cruising speed used for the router's first, cheap comparison between
 * strategies, before any of them are planned for real.
 */
enum class NavLocomotion(
    val blocksPerTick: Double,
    val label: String,
) {
    /** On foot. Always available, and always the fallback. */
    WALK(1.0 / NavTiming.SPRINT_PER_BLOCK, "walk"),

    /** Riding a boat across water. Fast and effortless, but only where there is water. */
    BOAT(BOAT_BLOCKS_PER_TICK, "boat"),

    /** Riding a horse, camel or similar. Faster than sprinting and clears bigger steps. */
    MOUNT(MOUNT_BLOCKS_PER_TICK, "mount"),

    /** Gliding. By far the fastest, and the only mode that ignores terrain entirely. */
    ELYTRA(ELYTRA_BLOCKS_PER_TICK, "elytra"),
    ;

    /** Ticks this mode would need to cover a distance, ignoring terrain. */
    fun estimateTicks(blocks: Double): Double = blocks / blocksPerTick
}
