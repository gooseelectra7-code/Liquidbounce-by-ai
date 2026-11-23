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

package net.ccbluex.liquidbounce.features.module.modules.world.automine

import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavHotbar
import net.minecraft.world.level.block.Blocks

/**
 * What the route is allowed to do while it is carrying a miner around.
 *
 * Deliberately not [net.ccbluex.liquidbounce.features.module.modules.movement.nav.ModuleNav]'s
 * snapshot: that one is built from the Nav module's own settings, and a miner wants a different
 * bargain. Digging is the point here rather than a last resort, so its aversion is zero — otherwise
 * the route walks eight blocks around a wall it is about to mine anyway. Falling is refused
 * outright: a miner spends its life beside drops it dug itself.
 *
 * Main thread only. It counts real items and reads real health, which is exactly why
 * [net.ccbluex.liquidbounce.utils.navigation.pathing.NavService.request] is handed this as a
 * provider rather than a value — a mining trip outlives the inventory it set off with.
 */
object AutoMineCaps {

    fun build(): NavCaps = NavCaps(
        canSprint = true,
        allowParkour = false,
        // Asked of the mode, not assumed. A box-clearing mode does its own breaking and wants the
        // route to walk only through space it has already opened; an ore hunt needs the route to be
        // able to tunnel, or a vein two blocks inside a wall is simply unreachable.
        allowBreak = ModuleAutoMine.modes.activeMode.routeMayDig,
        allowPlace = ModuleAutoMine.allowPlace,
        placeableBlocks = if (ModuleAutoMine.allowPlace) NavHotbar.buildingBlockCount() else 0,
        breakTicksPerHardness = NavHotbar.breakTicksPerHardness(Blocks.STONE.defaultBlockState()),
        allowFallDamage = false,
        maxFallBlocks = MAX_FALL,
        health = player.health.toDouble(),
        allowSwim = true,
        allowElytra = false,
        // The one number that separates a miner's route from a traveller's. A traveller charges
        // thirty ticks on top of every swing, because a route that mines the floor instead of
        // stepping off it is a route nobody asked for; a miner charges much less, because tunnelling
        // is what it came to do. What it must not charge is *nothing* — at zero, breaking a block of
        // dirt costs less than the step that would have avoided it, and the route mines its way down
        // a one-block ledge in full view of the player.
        digAversion = ModuleAutoMine.routeDigAversion.toDouble(),
    )

    /** A miner works beside its own holes; nothing it plans should ever be a drop worth taking. */
    private const val MAX_FALL = 3
}
