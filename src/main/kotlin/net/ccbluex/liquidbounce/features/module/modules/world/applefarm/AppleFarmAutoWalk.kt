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
package net.ccbluex.liquidbounce.features.module.modules.world.applefarm

import net.ccbluex.liquidbounce.utils.inventory.hasInventorySpace
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.navigation.NavigationBaseValueGroup
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Simple navigation for AutoAppleFarm: walks the player toward dropped apples/saplings within
 * range so they get picked up. NOT a full pathfinder (that's a separate future task) — it just
 * steers the directional input toward the nearest collectible drop, like [AutoFarmAutoWalk].
 */
object AppleFarmAutoWalk : NavigationBaseValueGroup<Vec3?>(ModuleAutoAppleFarm, "AutoWalk", false) {

    // Drops worth walking to: apples (loot), saplings (replanting) and logs (wood).
    private val COLLECTIBLE = setOf(Items.APPLE, Items.OAK_SAPLING, Items.OAK_LOG)

    private val minimumDistance by float("MinimumDistance", 1.5f, 1f..4f)
    private val range by float("Range", 16f, 4f..48f)

    override fun createNavigationContext(): Vec3? {
        // Only walk when there's nothing left to harvest in reach AND apples are on the ground.
        // Don't chase drops we can't store anyway.
        if (!hasInventorySpace()) return null
        // Leaves still to clear → stay put and keep farming in range.
        if (ModuleAutoAppleFarm.hasLeavesInRange()) return null
        return findNearestDrop()
    }

    override fun calculateGoalPosition(context: Vec3?): Vec3? {
        // Walk only toward dropped items (apples/saplings); never reposition for blocks.
        val target = context ?: return null

        if (player.distanceToSqr(target) < minimumDistance.sq()) {
            return null
        }
        return target
    }

    private fun findNearestDrop(): Vec3? {
        val rangeSq = range.sq().toDouble()
        return world.entitiesForRendering()
            .filterIsInstance<ItemEntity>()
            .filter { it.item.item in COLLECTIBLE }
            .filter { player.distanceToSqr(it) <= rangeSq }
            .minByOrNull { player.distanceToSqr(it) }
            ?.position()
    }
}
