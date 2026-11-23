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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.ArmorItemSlot
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

/**
 * ModuleElytraSwap
 *
 * Hotkey-driven chestplate/elytra swapper with a firework launcher key.
 *
 * Ported from SystemDLC (`system-source — копия`,
 * src/main/java/client/module/movement/ElytraSwap.java), replacing the previous
 * ScheduleInventoryActionEvent-based implementation wholesale. While the module is
 * enabled, the swap key (default G) exchanges the chest armor slot with the first
 * elytra (or, when an elytra is worn, the first chest-equippable item) found in the
 * hotbar first, then in the main inventory; the firework key (default F) uses a
 * firework rocket while an elytra is worn. The original's tick pacing is preserved:
 * a swap-clicked hotbar item goes 1 tick after the key press; an inventory item is
 * staged over ticks 2 and 3 (item -> selected hotbar slot, then armor swap + put-back
 * in one tick, mirroring the original's j -> 6 -> j SWAP-click chain built on
 * SphereItems.onIntInt4 / clickSlot(..., SWAP)). Movement keys and sprint are
 * suppressed for the duration of a swap, exactly like the original's
 * SneakState/KeyPressUtil hold (which never touches the sneak key).
 *
 * Grim-relevant decisions (all inventory traffic goes through sanctioned APIs only):
 * - Container clicks are emitted exclusively via
 *   [InventoryAction.Click.performSwap]`(...).performAction()`, i.e.
 *   `interaction.handleContainerInput(syncId, slot, button, SWAP, player)` with LB's
 *   slot-id mapping ([ArmorItemSlot.CHEST] -> server slot 6, matching the original's
 *   hardcoded chest armor slot). Direct `performAction()` outside the InventoryManager
 *   follows the existing SpeedMine/SwitchMethod/Totem precedent.
 * - The hotbar firework path uses [InventoryAction.UseItem] (SilentHotbar silent
 *   select + [useItem]), so the hotbar slot announcement rides the vanilla
 *   carried-item tracker (BadPacketsA-safe, never a raw SetCarriedItem) and the
 *   server sees the vanilla-ordered [carried-item, use] pair in one tick — the
 *   original changed `inventory.selectedSlot` one tick before interactItem, which
 *   coalesces into the same wire pattern.
 * - The inventory firework path (swap-in, use, swap-back on consecutive ticks) keeps
 *   the use packet and the container clicks in separate Grim tick-windows; the
 *   original also never produced a [use, click] pair inside one window.
 * - No rotations are involved; [useItem] defaults to the server-known rotation.
 */
object ModuleElytraSwap : ClientModule(
    "ElytraSwap",
    ModuleCategories.PLAYER,
    aliases = listOf("ChestSwap"),
    disableOnQuit = true
) {

    // Original settings (defaults preserved): "Клавиша свапа" (swap key) = GLFW 71 (G),
    // "Клавиша фейерверка" (firework key) = GLFW 70 (F), "Открывать инвентарь"
    // (open inventory while swapping) = false.
    private val swapKey by key("SwapKey", GLFW.GLFW_KEY_G)
    private val fireworkKey by key("FireworkKey", GLFW.GLFW_KEY_F)
    private val openInventory by boolean("OpenInventory", false)

    /**
     * One scheduled step of a sequence: runs on the [tick]-th game tick after the key press.
     */
    private class TimedAction(val tick: Int, val action: () -> Unit)

    private const val IDLE = -1

    /**
     * The swap state machine stays alive (keys guarded, movement suppressed) through tick 3
     * after the press and resets at tick 4 — the original holds its busy flag for 2 ticks
     * after the scheduled runnable fired and releases the key hold on that same tick.
     */
    private const val SWAP_END_TICK = 4

    private var swapTicks = IDLE
    private var swapPlan: List<TimedAction> = emptyList()

    private var fireworkTicks = IDLE
    private var fireworkPlan: List<TimedAction> = emptyList()
    private var fireworkEndTick = 0
    private var fireworkBusyUntil = 0
    private var fireworkSuppressesInput = false

    /**
     * While busy, new key presses are ignored (original `flag` guard).
     */
    private val busy: Boolean
        get() = swapTicks != IDLE || (fireworkTicks != IDLE && fireworkTicks < fireworkBusyUntil)

    /**
     * Movement-key/sprint hold: always during a swap; during a firework sequence only for
     * the inventory-rocket path (the original's hotbar-rocket path never arms the key hold).
     */
    private val suppressingInput: Boolean
        get() = swapTicks != IDLE || (fireworkTicks != IDLE && fireworkSuppressesInput)

    override fun onEnabled() = reset()

    override fun onDisabled() = reset()

    private fun reset() {
        swapTicks = IDLE
        swapPlan = emptyList()
        fireworkTicks = IDLE
        fireworkPlan = emptyList()
        fireworkEndTick = 0
        fireworkBusyUntil = 0
        fireworkSuppressesInput = false
    }

    /**
     * Chest-equippable non-elytra item, the original `isItemStack` check:
     * non-empty, not an elytra, and its EQUIPPABLE component targets the chest slot.
     */
    private fun ItemStack.isChestEquippable(): Boolean =
        !isEmpty && !`is`(Items.ELYTRA) &&
            get(DataComponents.EQUIPPABLE)?.slot == EquipmentSlot.CHEST

    // [KeyEvent] only fires while no screen is open (MixinKeyboardHandler), matching the
    // original's GLFW poll that required `currentScreen == null`.
    @Suppress("unused")
    private val keyHandler = handler<KeyEvent> { event ->
        if (event.action != GLFW.GLFW_PRESS || !inGame || busy) {
            return@handler
        }

        when (event.key.value) {
            swapKey.value -> onSwapKeyPressed()
            fireworkKey.value -> onFireworkKeyPressed()
        }
    }

    private fun onSwapKeyPressed() {
        val wearingElytra = ArmorItemSlot.CHEST.itemStack.`is`(Items.ELYTRA)

        // Original search order: hotbar 0..8 first, then main inventory 9..35.
        // No offhand, no durability filtering. When not wearing an elytra (chestplate
        // worn OR chest slot empty) the target is an elytra; otherwise a chestplate.
        val hotbarSlot = if (wearingElytra) {
            Slots.Hotbar.findSlot { it.isChestEquippable() }
        } else {
            Slots.Hotbar.findSlot(Items.ELYTRA)
        }
        val inventorySlot = if (wearingElytra) {
            Slots.Inventory.findSlot { it.isChestEquippable() }
        } else {
            Slots.Inventory.findSlot(Items.ELYTRA)
        }

        when {
            hotbarSlot != null ->
                // Single SWAP click: chest armor slot <-> found hotbar slot,
                // 1 tick after the press (original value235 = 1 delay).
                swapPlan = listOf(
                    TimedAction(tick = 1) {
                        InventoryAction.Click.performSwap(
                            from = ArmorItemSlot.CHEST,
                            to = hotbarSlot
                        ).performAction()
                    }
                )

            inventorySlot != null -> {
                // The selected hotbar slot is captured at press time (original `selectedSlot`
                // capture in the scheduling method) and used as the intermediary for all
                // three SWAP clicks: item -> hotbar, armor <-> hotbar, put-back -> item slot.
                val selected = HotbarItemSlot(player.inventory.selectedSlot)

                swapPlan = listOf(
                    TimedAction(tick = 2) {
                        InventoryAction.Click.performSwap(
                            from = inventorySlot,
                            to = selected
                        ).performAction()
                    },
                    TimedAction(tick = 3) {
                        // Both clicks share one tick in the original (armor swap, then the
                        // put-back runs immediately in the same scheduler pass).
                        InventoryAction.Click.performSwap(
                            from = ArmorItemSlot.CHEST,
                            to = selected
                        ).performAction()
                        InventoryAction.Click.performSwap(
                            from = inventorySlot,
                            to = selected
                        ).performAction()
                    }
                )
            }

            // Nothing to swap to — the original does nothing (it never strips the chest
            // slot to empty and never equips a chestplate over an empty chest slot).
            else -> return
        }

        swapTicks = 0

        // "Открывать инвентарь": client-side inventory screen, opened at swap start and
        // never auto-closed (original update14). Purely visual, no packets involved.
        if (openInventory && mc.screen == null) {
            mc.setScreen(InventoryScreen(player))
        }

        // Original swap start: sprint is dropped immediately along with the key hold.
        player.isSprinting = false

        notification(
            name,
            if (wearingElytra) "Swapped to chestplate" else "Swapped to elytra",
            NotificationEvent.Severity.INFO
        )
    }

    private fun onFireworkKeyPressed() {
        // Original: fireworks only while an elytra is actually worn.
        if (!ArmorItemSlot.CHEST.itemStack.`is`(Items.ELYTRA)) {
            return
        }

        val hotbarRocket = Slots.Hotbar.findSlot(Items.FIREWORK_ROCKET)
        if (hotbarRocket != null) {
            // Original: select the rocket slot at press, interactItem one tick later,
            // restore the slot one tick after that. UseItem (SilentHotbar + useItem)
            // produces the identical wire pattern; no movement-key hold on this path.
            fireworkPlan = listOf(
                TimedAction(tick = 1) {
                    InventoryAction.UseItem(hotbarItemSlot = hotbarRocket, requester = this).performAction()
                }
            )
            fireworkEndTick = 2
            fireworkBusyUntil = 2
            fireworkSuppressesInput = false
        } else {
            val inventoryRocket = Slots.Inventory.findSlot(Items.FIREWORK_ROCKET) ?: return
            val selected = HotbarItemSlot(player.inventory.selectedSlot)

            // Original ActionQueue: SWAP the rocket into the selected hotbar slot, use it
            // on the next tick (no slot change — it sits in the selected slot), SWAP it
            // back afterwards; movement keys are held for the whole sequence plus one tick.
            fireworkPlan = listOf(
                TimedAction(tick = 1) {
                    InventoryAction.Click.performSwap(
                        from = inventoryRocket,
                        to = selected
                    ).performAction()
                },
                TimedAction(tick = 2) {
                    useItem(InteractionHand.MAIN_HAND)
                },
                TimedAction(tick = 3) {
                    InventoryAction.Click.performSwap(
                        from = inventoryRocket,
                        to = selected
                    ).performAction()
                }
            )
            fireworkEndTick = 4
            fireworkBusyUntil = 3
            fireworkSuppressesInput = true
            player.isSprinting = false
        }

        fireworkTicks = 0

        notification(name, "Used firework", NotificationEvent.Severity.INFO)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!inGame) {
            reset()
            return@handler
        }

        if (swapTicks != IDLE) {
            swapTicks++
            for (step in swapPlan) {
                if (step.tick == swapTicks) {
                    step.action()
                }
            }
            if (swapTicks >= SWAP_END_TICK) {
                swapTicks = IDLE
                swapPlan = emptyList()
            }
        }

        if (fireworkTicks != IDLE) {
            fireworkTicks++
            for (step in fireworkPlan) {
                if (step.tick == fireworkTicks) {
                    step.action()
                }
            }
            if (fireworkTicks >= fireworkEndTick) {
                fireworkTicks = IDLE
                fireworkPlan = emptyList()
                fireworkSuppressesInput = false
            }
        }

        // Original re-asserts the hold every tick while busy (SneakState.update4 in both
        // tick phases), which also keeps sprint off.
        if (suppressingInput) {
            player.isSprinting = false
        }
    }

    // The original's KeyPressUtil hold releases forward/back/left/right/jump/sprint —
    // deliberately not the sneak key. Restoring afterwards is implicit here: once the
    // hold ends, vanilla reads the real key states again.
    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (suppressingInput) {
            event.directionalInput = DirectionalInput.NONE
            event.jump = false
        }
    }

}
