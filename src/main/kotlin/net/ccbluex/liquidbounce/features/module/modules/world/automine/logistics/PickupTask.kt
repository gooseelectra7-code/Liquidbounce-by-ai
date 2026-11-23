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

package net.ccbluex.liquidbounce.features.module.modules.world.automine.logistics

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.world.automine.AutoMineCaps
import net.ccbluex.liquidbounce.features.module.modules.world.automine.AutoMineDebug
import net.ccbluex.liquidbounce.features.module.modules.world.automine.ModuleAutoMine
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.inventory.hasInventorySpace
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavHandle
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavService
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalNear
import net.minecraft.world.entity.item.ItemEntity

/**
 * Walks over what the mining dropped.
 *
 * **The rule: whenever there is no block being worked on, step onto the nearest dropped item that has
 * settled and is within range.** Collecting itself is vanilla's — a player does not press anything to
 * pick things up, they walk over them — so this task only ever produces movement.
 *
 * Two details keep it from being a nuisance. Items younger than [SETTLE_TICKS] are ignored, because
 * the drop from the block just mined is usually already flying into the player's own pickup radius and
 * chasing it would mean walking to where we are standing. And a drop that cannot be reached is given
 * up on after [TIMEOUT] rather than held onto: ore falls into lava, into a crack, behind a slab, and a
 * miner that will not leave one item is a miner that has stopped mining.
 */
class PickupTask(
    private val owner: ClientModule,
    private val rotations: RotationsValueGroup,
) {

    private var handle: NavHandle? = null
    private var target: ItemEntity? = null
    private var ticks = 0

    /** True while this task wants the module's attention. */
    val isBusy: Boolean get() = target != null

    /**
     * One tick of collecting. Returns true while it is busy and mining should wait.
     *
     * Called only when nothing is being mined, so it never competes with the dig for either the route
     * or the aim.
     */
    fun tick(): Boolean {
        val current = target ?: return begin()
        return follow(current)
    }

    fun stop() {
        handle?.takeIf { it.isActive }?.cancel()
        handle = null
        target = null
        ticks = 0
    }

    private fun begin(): Boolean {
        if (!ModuleAutoMine.pickup) return false

        // No point walking to something there is nowhere to put. The junk and unload tasks are what
        // will eventually answer this; until they exist, a full inventory simply stops collecting.
        if (!hasInventorySpace()) return false

        val item = nearest() ?: return false
        target = item
        ticks = 0
        handle = NavService.request(
            owner = owner,
            goal = GoalNear(item.blockPosition(), PICKUP_RADIUS),
            caps = AutoMineCaps.build(),
            rotations = rotations,
            priority = Priority.NORMAL,
            capsProvider = AutoMineCaps::build,
        )
        AutoMineDebug.event(
            "collecting ${item.item.count}x ${item.item.hoverName.string}" +
                " at ${AutoMineDebug.pos(item.blockPosition())}"
        )
        return true
    }

    private fun follow(item: ItemEntity): Boolean {
        ticks++

        // Gone is the only real signal of success: vanilla removes the entity when it is collected.
        if (!item.isAlive) {
            AutoMineDebug.event("collected after $ticks ticks")
            stop()
            return false
        }

        if (ticks > TIMEOUT) {
            AutoMineDebug.event("gave up on the drop at ${AutoMineDebug.pos(item.blockPosition())}")
            stop()
            return false
        }

        // Items settle, but water and pistons move them, so the goal is refreshed now and then rather
        // than once — and by retargeting the same request, which keeps what the search has learned.
        if (ticks % RETARGET_TICKS == 0) {
            handle?.takeIf { it.isActive }?.retarget(GoalNear(item.blockPosition(), PICKUP_RADIUS))
        }

        AutoMineDebug.state("walking to the drop at ${AutoMineDebug.pos(item.blockPosition())}")
        return true
    }

    private fun nearest(): ItemEntity? {
        val range = ModuleAutoMine.pickupRange.toDouble()
        val rangeSq = range * range

        return world.entitiesForRendering()
            .filterIsInstance<ItemEntity>()
            .filter {
                it.isAlive &&
                    it.tickCount >= SETTLE_TICKS &&
                    it.distanceToSqr(player) <= rangeSq &&
                    ModuleAutoMine.wantsDrop(it.item.item)
            }
            .minByOrNull { it.distanceToSqr(player) }
    }

    private companion object {
        /** How close the route is asked to get. Vanilla collects from about a block away. */
        const val PICKUP_RADIUS = 1.0

        /**
         * Ticks a drop has to have existed before it is worth walking to.
         *
         * Half a second, which is roughly how long the drop from the block just mined spends flying
         * into the player's own pickup radius. Without it the miner would set off towards items it is
         * about to be handed anyway.
         */
        const val SETTLE_TICKS = 10

        /** Five seconds of trying to reach one item, then it is somebody else's problem. */
        const val TIMEOUT = 100

        /** How often the goal is moved onto the item again, in ticks. */
        const val RETARGET_TICKS = 20
    }
}
