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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.additions.shooter
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.Items

/**
 * The engine's supply of firework rockets: finding them, counting them and lighting them.
 *
 * Kept apart from the executor because where a rocket comes from is not a question about flying.
 * A rocket in the hotbar or the offhand is lit on the spot; one buried in the pack has to be
 * swapped out first, which is an inventory action with its own timing constraints and cannot happen
 * in the same tick it is asked for. The flight only ever asks for thrust and is told whether it got
 * any — the fetching happens underneath.
 *
 * Main thread only; every read here is live inventory state.
 */
object NavFireworks {

    /** Rockets that can be lit without touching the inventory window. */
    private val inHand = Slots.OffhandWithHotbar

    /** Everywhere a rocket might be, the armour slots aside. */
    private val everywhere = Slots.OffhandWithHotbar + Slots.Inventory

    /** Whether a rocket in the pack is wanted in hand. Cleared once a swap has been scheduled. */
    private var swapWanted = false

    /** A rocket that can be lit this tick. */
    fun readySlot(): HotbarItemSlot? = inHand.findSlot(Items.FIREWORK_ROCKET)

    /** A rocket anywhere, including one that would have to be swapped into the hotbar first. */
    fun storedSlot(): ItemSlot? = everywhere.findSlot(Items.FIREWORK_ROCKET)

    /** Rockets carried, wherever they are. */
    fun count(): Int = everywhere.sumOf { slot ->
        val stack = slot.itemStack
        if (stack.item === Items.FIREWORK_ROCKET) stack.count else 0
    }

    /** Rockets that need no swap to use. */
    fun readyCount(): Int = inHand.sumOf { slot ->
        val stack = slot.itemStack
        if (stack.item === Items.FIREWORK_ROCKET) stack.count else 0
    }

    /**
     * Is one of our own rockets still burning?
     *
     * Lighting a second while the first is pushing buys nothing and empties the pack twice as fast.
     */
    fun ownRocketInFlight(): Boolean = world.entitiesForRendering().any {
        it is FireworkRocketEntity && it.shooter === player
    }

    /**
     * Lights a rocket if one is to hand, and otherwise asks for one to be brought there.
     *
     * @return true when a rocket was actually used this tick.
     */
    fun launch(owner: ClientModule): Boolean {
        val ready = readySlot()
        if (ready != null) {
            with(owner) { useHotbarSlotOrOffhand(ready, ticksUntilReset = SLOT_RESET_TICKS) }
            return true
        }

        // Nothing to hand. Worth a swap only if there is something to swap in.
        swapWanted = storedSlot() != null
        return false
    }

    /** Forgets a pending request, e.g. when the flight is over. */
    fun cancelSwap() {
        swapWanted = false
    }

    /**
     * Schedules the swap that brings a rocket from the pack into the hand and uses it there.
     *
     * Swap, use, swap back — the same three actions any player performs to fire a rocket kept in
     * their inventory, run through the client's inventory scheduler so it obeys the configured
     * timings rather than happening instantly.
     */
    fun scheduleSwap(
        event: ScheduleInventoryActionEvent,
        constraints: InventoryConstraints,
        owner: EventListener,
    ) {
        if (!swapWanted) return
        swapWanted = false

        val stored = storedSlot() ?: return
        // It came within reach while we were waiting, so the ordinary path will light it.
        if (stored is HotbarItemSlot) return

        val target = if (HotbarItemSlot.OFFHAND.canBeSwapTarget) {
            HotbarItemSlot.OFFHAND
        } else {
            HotbarItemSlot(player.inventory.selectedSlot)
        }

        event.schedule(
            constraints,
            listOf(
                InventoryAction.Click.performSwap(from = stored, to = target),
                InventoryAction.UseItem(target, owner),
                InventoryAction.Click.performSwap(from = stored, to = target),
            ),
        )
    }

    /** Ticks the silently selected slot stays put after a rocket is lit. */
    private const val SLOT_RESET_TICKS = 4
}
