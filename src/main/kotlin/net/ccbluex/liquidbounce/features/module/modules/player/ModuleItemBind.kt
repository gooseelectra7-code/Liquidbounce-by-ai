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
package net.ccbluex.liquidbounce.features.module.modules.player

import com.mojang.blaze3d.platform.InputConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.ccbluex.liquidbounce.config.types.group.MutableGroupListValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.suspendHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.network.sendSwapHotbarSlotWithOffhand
import net.minecraft.world.InteractionHand
import org.lwjgl.glfw.GLFW

/**
 * ItemBind module
 *
 * Binds items to keys: on key press the item is swapped into the chosen hand (from the
 * hotbar instantly, or from the inventory through the click scheduler), used once, and the
 * previous item is swapped back after the configured delay.
 */
object ModuleItemBind : ClientModule("ItemBind", ModuleCategories.PLAYER) {

    private val constraints = tree(PlayerInventoryConstraints())

    /** Infinitely addable bind entries (the new mutable group list). */
    private val binds = tree(
        MutableGroupListValueGroup("Binds", initialCount = 1) { index ->
            ItemBindEntry("Bind-$index")
        }
    )

    /** A bind exposed for the HUD: one item + its short key label (assumes one item per key). */
    data class HudBind(val item: net.minecraft.world.item.Item, val keyLabel: String)

    /** Enabled binds that have both an item and a trigger key, for the Hud2 ItemBinds element. */
    val hudBinds: List<HudBind>
        get() = binds.entries.mapNotNull { bind ->
            if (!bind.enabled || bind.trigger == InputConstants.UNKNOWN) {
                return@mapNotNull null
            }
            val item = bind.items.firstOrNull() ?: return@mapNotNull null
            HudBind(item, keyLabel(bind.trigger))
        }

    /**
     * Always-English short key label: keyboard keys by name ("key.keyboard.r" → "R"), mouse
     * buttons as M-numbers (left → M1, right → M2, middle → M3, …).
     */
    private fun keyLabel(key: InputConstants.Key): String {
        if (key.type == InputConstants.Type.MOUSE) {
            return "M${key.value + 1}"
        }
        return key.name.removePrefix("key.keyboard.").substringAfterLast('.').uppercase().take(5)
    }

    @Suppress("unused")
    enum class BindHand(override val tag: String) : Tagged {
        MAINHAND("Mainhand"),
        OFFHAND("Offhand"),
    }

    @Suppress("unused")
    enum class BindSource(override val tag: String) : Tagged {
        HOTBAR("Hotbar"),
        INVENTORY("Inventory"),
        BOTH("Both"),
    }

    private class ItemBindEntry(name: String) : ToggleableValueGroup(ModuleItemBind, name, false) {
        val trigger by key("Trigger")

        /** Infinitely addable, ordered item list — tried top-to-bottom (priority). */
        val items by itemsChoose("Items")

        val hand by enumChoice("Hand", BindHand.MAINHAND)
        val source by enumChoice("Source", BindSource.BOTH)

        /** Ticks between the (instant) swap and the use. */
        val useDelay by int("UseDelay", 0, 0..20, "ticks")

        /** The swap itself is instant; this delay only applies before swapping back. */
        val swapBackDelay by int("SwapBackDelay", 4, 0..40, "ticks")

        var lastTriggerAt = 0L
    }

    // --- Inventory route: chains go through the click scheduler, so they stay in sync with
    // --- InventoryMove / silent inventory opening (the "GUI move" simulation)
    private class PendingChain(val actions: List<InventoryAction>, var delayTicks: Int)

    private val pending = ArrayDeque<PendingChain>()

    @Suppress("unused")
    private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val chain = iterator.next()
            if (chain.delayTicks > 0) {
                chain.delayTicks--
                continue
            }
            event.schedule(constraints, chain.actions)
            iterator.remove()
        }
    }

    @Suppress("unused")
    private val keyHandler = suspendHandler<KeyEvent> { event ->
        fireTriggers(event.key, event.action)
    }

    // Mouse buttons fire a separate event (KeyEvent is keyboard-only)
    @Suppress("unused")
    private val mouseHandler = suspendHandler<net.ccbluex.liquidbounce.event.events.MouseButtonEvent> { event ->
        fireTriggers(event.key, event.action)
    }

    private fun CoroutineScope.fireTriggers(triggerKey: InputConstants.Key, action: Int) {
        if (action != GLFW.GLFW_PRESS || mc.screen != null || triggerKey == InputConstants.UNKNOWN) {
            return
        }

        val now = System.currentTimeMillis()
        for (bind in binds.entries) {
            if (!bind.enabled || bind.items.isEmpty()) {
                continue
            }
            if (bind.trigger != triggerKey) {
                continue
            }
            // Debounce: a bind cannot re-trigger while its use/swap-back is still pending
            if (now - bind.lastTriggerAt < (bind.useDelay + bind.swapBackDelay + 2) * 50L) {
                continue
            }
            bind.lastTriggerAt = now

            launch {
                execute(bind)
            }
        }
    }

    private suspend fun execute(bind: ItemBindEntry) {
        val useDelay = bind.useDelay
        val swapBack = bind.swapBackDelay.coerceAtLeast(1)
        val useHand = if (bind.hand == BindHand.MAINHAND) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND

        // Already in the right hand? Just use it (no swap happened — no use delay).
        // For crossbows the hand stack only counts when it is charged — otherwise a charged
        // one is searched for instead.
        val handStack = if (bind.hand == BindHand.MAINHAND) player.mainHandItem else player.offhandItem
        if (handStack.item in bind.items && isPreferredStack(handStack)) {
            useItem(useHand)
            return
        }

        // Hotbar route first (instant swaps); items are tried in priority order
        if (bind.source != BindSource.INVENTORY) {
            for (item in bind.items) {
                val slot = findHotbarSlot(item) ?: continue
                when (bind.hand) {
                    BindHand.MAINHAND -> {
                        // Instant silent select; SilentHotbar restores the previous slot by
                        // itself once the use delay + swap-back delay have passed
                        SilentHotbar.selectSlotSilently(
                            this, slot,
                            ticksUntilReset = useDelay + swapBack,
                        )
                        if (useDelay > 0) {
                            waitTicks(useDelay)
                        }
                        useItem(InteractionHand.MAIN_HAND)
                        return
                    }

                    BindHand.OFFHAND -> {
                        val index = slot.hotbarIndex ?: continue
                        swapHotbarWithOffhand(index)
                        if (useDelay > 0) {
                            waitTicks(useDelay)
                        }
                        useItem(InteractionHand.OFF_HAND)
                        waitTicks(swapBack)
                        swapHotbarWithOffhand(index)
                        return
                    }
                }
            }
        }
        if (bind.source == BindSource.HOTBAR) {
            return
        }

        // Inventory route: swap instantly, use after the use delay, swap back after the
        // swap-back delay — everything through the scheduler so it cooperates with GUI move
        // and silent inventory opening
        val inventorySlot = bind.items.firstNotNullOfOrNull { findInventorySlot(it) } ?: return
        val target = when (bind.hand) {
            BindHand.MAINHAND -> Slots.Hotbar[SilentHotbar.serversideSlot]
            BindHand.OFFHAND -> HotbarItemSlot.OFFHAND
        }

        pending += PendingChain(
            listOf(InventoryAction.Click.performSwap(from = inventorySlot, to = target)),
            delayTicks = 0,
        )
        pending += PendingChain(
            listOf(InventoryAction.UseItem(target, this)),
            delayTicks = useDelay,
        )
        pending += PendingChain(
            listOf(InventoryAction.Click.performSwap(from = inventorySlot, to = target)),
            delayTicks = useDelay + swapBack,
        )
    }

    /** Crossbows are only "preferred" when charged — charged ones are picked first. */
    private fun isPreferredStack(stack: net.minecraft.world.item.ItemStack): Boolean =
        stack.item !is net.minecraft.world.item.CrossbowItem ||
            net.minecraft.world.item.CrossbowItem.isCharged(stack)

    /** Hotbar slot for [item]; charged crossbows take priority over uncharged ones. */
    private fun findHotbarSlot(item: net.minecraft.world.item.Item): HotbarItemSlot? =
        Slots.Hotbar.findSlot { stack -> stack.item === item && isPreferredStack(stack) }
            ?: Slots.Hotbar.findSlot(item)

    /** Inventory slot for [item]; charged crossbows take priority over uncharged ones. */
    private fun findInventorySlot(item: net.minecraft.world.item.Item) =
        Slots.Inventory.findSlot { stack -> stack.item === item && isPreferredStack(stack) }
            ?: Slots.Inventory.findSlot(item)

    /**
     * Instant offhand swap of a hotbar slot via packets (held change → swap → held change), bracketed with the slot
     * the server holds - see the helper for why that is not `SilentHotbar.serversideSlot`.
     */
    private fun swapHotbarWithOffhand(slotIndex: Int) = network.sendSwapHotbarSlotWithOffhand(slotIndex)

    override fun onDisabled() {
        SilentHotbar.resetSlot(this)
        pending.clear()
        super.onDisabled()
    }
}
