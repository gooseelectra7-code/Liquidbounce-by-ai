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

package net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.ModuleAutoBuff
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority

/**
 * Moves buff items from the inventory into EMPTY hotbar slots, one `SWAP` click per item into a slot picked here
 * (2026-10-03, the user's ask: swap into the slot instead of clicking).
 *
 * It used to be a `QUICK_MOVE` (shift-click), which lets the menu pick the destination
 * (`InventoryMenu.quickMoveStack`, `:107-166` in 26.1.2), and that had two defects:
 * - The space test counted the OFFHAND, but a shift-click of a buff item out of the main inventory only ever lands in
 *   the hotbar (`:137-140`; only offhand-EQUIPPABLE items such as a shield go to the offhand, `:133-136`). With the
 *   hotbar full and the offhand empty - e.g. on a pre-1.9 server, where nothing ever fills that slot - every cycle
 *   sent a click that moved nothing. The client sends the packet whatever the click changed
 *   (`MultiPlayerGameMode.handleContainerInput`, `:452-469`), so `InventoryManager` saw a click, asked again, got the
 *   same chain back and never left its loop: the inventory stayed open server-side with a click every `ClickDelay`.
 * - An item that has an armour slot goes there first when that slot is free (`:128-132`). A player head is equippable
 *   on the HEAD (`Items.PLAYER_HEAD`, `.equippableUnswappable(EquipmentSlot.HEAD)`), so a `Head` refill without a
 *   helmet on put the head on instead of into the hotbar.
 *
 * Only the nine main-hand slots are filled. The offhand never was in practice (see above), and the totem and offhand
 * modules own it.
 */
object Refill : ToggleableValueGroup(ModuleAutoBuff, "Refill", true) {

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    fun execute(event: ScheduleInventoryActionEvent) {
        val emptySlots = Slots.Hotbar.filter { it.itemStack.isEmpty }
        if (emptySlots.isEmpty()) {
            return
        }

        val validFeatures = ModuleAutoBuff.activeFeatures

        // Find valid items in the inventory
        val validItems = Slots.Inventory.filter {
            val itemStack = it.itemStack
            validFeatures.any { f -> f.isValidItem(itemStack, false) }
        }

        // Every pair targets a different empty slot. `InventoryManager` re-asks after every click it sends
        // (`requiresUpdate`), so only the first pair of a cycle goes out and the rest are re-derived from what it left.
        for ((from, to) in validItems.zip(emptySlots)) {
            event.schedule(
                inventoryConstraints,
                InventoryAction.Click.performSwap(from = from, to = to),
                Priority.IMPORTANT_FOR_USAGE_1
            )
        }
    }

}
