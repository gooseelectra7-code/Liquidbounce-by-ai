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
package net.ccbluex.liquidbounce.features.module.modules.player.offhand

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.RefreshArrayListEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleEagle
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ScaffoldBlockItemSelection
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.isNewerThanOrEquals1_16
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.SwapDebug
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.SwapJitter
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.SwapQuiet
import net.ccbluex.liquidbounce.utils.network.sendSwapHotbarSlotWithOffhand
import net.ccbluex.liquidbounce.utils.client.usesViaFabricPlus
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.item.getPotionEffects
import net.ccbluex.liquidbounce.utils.item.isSword
import net.minecraft.core.component.DataComponents
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import java.util.function.Predicate

/**
 * Offhand module
 *
 * Manages your offhand.
 */
object ModuleOffhand : ClientModule("Offhand", ModuleCategories.PLAYER, aliases = listOf("AutoTotem")) {

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    /** The pop-to-replenish spread for this module's totem swaps. */
    private val jitter = tree(SwapJitter(this))

    /**
     * The anticheat gate for this module's clicks.
     *
     * `windowUp` is constantly false here because this module has no in-flight flag of its own to
     * report: it asks for the stop explicitly on the branch where it refuses to click, and
     * `StopMovement.HoldTicks` is what carries the quiet past the click and over the
     * `CLOSE_WINDOW` that follows it.
     */
    private val quiet = tree(SwapQuiet(this) { false })

    /** This module's own text-only debug channel; the render `Debug` module is not involved. */
    private val swapDebug = tree(SwapDebug(this))
    private val switchMode by enumChoice(
        "SwitchMode",
        default = if (!usesViaFabricPlus) SwitchMode.SWITCH else SwitchMode.AUTOMATIC
    )
    private val switchDelay by int("SwitchDelay", 0, 0..500, "ms")
    private val cycleSlots by key("Cycle", GLFW.GLFW_KEY_H)

    private object Gapple : ToggleableValueGroup(this, "Gapple", true) {
        object WhileHoldingSword : ToggleableValueGroup(this, "WhileHoldingSword", true) {
            val onlyWhileKa by boolean("OnlyWhileKillAura", true)
        }

        val gappleBind by key("GappleBind")

        init {
            tree(WhileHoldingSword)
        }
    }

    private object Crystal : ToggleableValueGroup(this, "Crystal", true) {
        val onlyWhileCa by boolean("OnlyWhileCrystalAura", false)
        val whenNoTotems by boolean("WhenNoTotems", true)
        val crystalBind by key("CrystalBind")
    }

    private object Strength : ToggleableValueGroup(this, "StrengthPotion", false) {
        val onlyWhileHoldingSword by boolean("OnlyWhileHoldingSword", true)
        val onlyWhileKa by boolean("OnlyWhileKillAura", true)
        val strengthBind by key("StrengthBind")
    }

    private object Block : ToggleableValueGroup(this, "Block", false) {
        val whileScaffold by boolean("WhileScaffold", true)
        val whileEagle by boolean("WhileEagle", true)
    }

    init {
        treeAll(
            Totem,
            Crystal,
            Gapple,
            Strength,
            Block,
        )
    }

    private val INVENTORY_MAIN_PRIORITY = Slots.Inventory + Slots.Hotbar
    private val INVENTORY_HOTBAR_PRIORITY = Slots.Hotbar + Slots.Inventory
    private val chronometer = Chronometer()
    private var activeMode: Mode = Mode.NONE
    private var lastMode: Mode? = null
    private var lastTagMode: Mode = Mode.NONE
    private var staticMode = Mode.NONE
    private var last: Pair<Item, ItemSlot>? = null

    override val tag: String
        get() = activeMode.modeName

    override fun onEnabled() {
        staticMode = when {
            Crystal.enabled && Mode.CRYSTAL.canCycleTo() -> Mode.CRYSTAL
            Gapple.enabled -> Mode.GAPPLE
            Totem.enabled && !Totem.Health.enabled -> Mode.TOTEM
            else -> Mode.NONE
        }
    }

    @Suppress("unused")
    val keyHandler = handler<KeyEvent> {
        if (it.action != GLFW.GLFW_PRESS) {
            return@handler
        }

        when (it.key.value) {
            Gapple.gappleBind.value -> Mode.GAPPLE.onBindPress()
            Crystal.crystalBind.value -> Mode.CRYSTAL.onBindPress()
            Strength.strengthBind.value -> {
                // since we can't cycle to strength, its status has to be checked here
                if (Strength.enabled) {
                    Mode.STRENGTH.onBindPress()
                }
            }

            cycleSlots.value -> {
                val entries = Mode.entries
                val startIndex = staticMode.ordinal
                var index = (startIndex + 1) % entries.size

                while (index != startIndex) {
                    val mode = entries[index]
                    if (mode.canCycleTo()) {
                        staticMode = mode
                        return@handler
                    }

                    index = (index + 1) % entries.size
                }
            }
        }
    }

    @Suppress("unused")
    private val autoTotemHandler = handler<ScheduleInventoryActionEvent>(priority = 100) {
        activeMode = Mode.entries.firstOrNull(Mode::shouldEquip) ?: staticMode
        if (activeMode == Mode.NONE && Totem.Health.switchBack && lastMode == Mode.TOTEM) {
            activeMode = Mode.BACK
        }

        if (activeMode != lastTagMode) {
            EventManager.callEvent(RefreshArrayListEvent)
            lastTagMode = activeMode
        }

        if (activeMode != lastMode && lastMode == Mode.TOTEM) {
            if (!Totem.switchBackStarted) {
                Totem.switchBack.reset()
            }

            Totem.switchBackStarted = true
            if (!Totem.switchBack.hasElapsed(Totem.switchBackDelay.toLong())) {
                return@handler
            }
        }

        Totem.switchBackStarted = false

        if (!chronometer.hasElapsed(activeMode.getDelay().toLong())) {
            return@handler
        }

        val slot = activeMode.getSlot() ?: return@handler
        lastMode = activeMode

        // the item is already located in Off-hand slot
        if (slot == HotbarItemSlot.OFFHAND) {
            return@handler
        }

        if (Totem.Health.switchBack) {
            last = slot.itemStack.item to slot
        }

        // Only the totem is jittered: the other modes are not what `AutoTotemB` samples, and holding
        // a gapple or a crystal back for up to eight ticks would be a cost with nothing bought.
        //
        // THE HOLD IS WAITED OUT BEFORE THE QUIET IS ASKED FOR, and that order is load-bearing. The
        // other way round the module asked for the stop, went quiet, and then sat out the jitter
        // with nothing asking for anything - so the stop expired mid-hold, the player sprinted
        // again, and the next tick asked for it all over again: a stutter of one stop per hold
        // instead of one stop right before the click, and none of those stops bought a swap.
        if (activeMode == Mode.TOTEM && !jitter.allows()) {
            logRefusal("jitter: roll=${jitter.rolledTicks} left=${jitter.remainingTicks}")
            return@handler
        }

        // Every path out of `performSwitch` is one of the two shapes the 2026-09-14 totem audit
        // covered, and none of them was gated:
        //   SWITCH / AUTOMATIC -> one `CLICK_WINDOW`, PICKUP -> three of them. Grim's
        //     `MultiActionsC` (checks/impl/multiactions/MultiActionsC.java:37-51, NOT experimental,
        //     enabled by the shipped punishments file) CANCELS any of them while we sprint, move or
        //     sneak, and a cancelled click is unrecoverable - `CompensatedInventory` applies it
        //     before the cancel (:261) so the client keeps an offhand the server never got.
        //   SMART -> the `SetCarriedItem` + `SWAP_ITEM_WITH_OFFHAND` burst, which Grim's
        //     `PacketOrderF` can cancel when a sprint/sneak action shares its tick.
        // The gate sits HERE, before the call, because SMART sends its packets as a SIDE EFFECT of
        // `performSwitch` and reports `emptyList()` - by the time we could inspect the result the
        // burst is already on the wire.
        //
        // Not restricted to `Mode.TOTEM`: the cancel is item-agnostic, so a ghosted gapple or
        // crystal is exactly as broken as a ghosted totem.
        if (!quiet.isQuiet) {
            quiet.requestStop()
            logRefusal("quiet: blocked by ${quiet.quietBlocker}; ${quiet.stopDiagnostic}")
            return@handler
        }

        val actions = switchMode.performSwitch(slot)
        if (actions.isEmpty()) {
            chronometer.reset()
            return@handler
        }

        if (activeMode != Mode.TOTEM || !Totem.send(actions)) {
            it.schedule(inventoryConstraints, actions)
        }

        if (activeMode == Mode.TOTEM) {
            jitter.release()
        }

        chronometer.reset()
    }

    /**
     * Names the gate that refused a swap. Text only, off by default - see [SwapDebug] for why the
     * `Debug` MODULE was the wrong switch for this.
     */
    private fun logRefusal(reason: String) = swapDebug.log("$activeMode swap refused - $reason")

    private fun performSwitch(from: ItemSlot, smart: Boolean): List<InventoryAction.Click> {
        // Both halves of the SMART burst are separate TotemGuard violations with a container up;
        // the rule and its switch live in `SwapQuiet.allowsHandSwapNow`. Reporting no actions makes
        // the caller wait out its delay and try again rather than fall back to the PICKUP shape,
        // which is the one shape TotemGuard's AutoTotemA can actually see.
        if (smart && !quiet.allowsHandSwapNow) {
            return emptyList()
        }

        return if (smart && from is HotbarItemSlot) {
            // Bracketed with the slot the SERVER holds. This used `inventory.selectedSlot`, the client's own slot,
            // which differs whenever another module holds a silent slot (`MixinInventory` never redirects
            // `getSelectedSlot`): the burst then either skipped the first `SetCarriedItem` and swapped the silent
            // slot's item into the offhand, or ended on a slot the vanilla tracker does not know it sent. See the
            // helper for why `SilentHotbar.serversideSlot` is not the answer either.
            network.sendSwapHotbarSlotWithOffhand(from.inventorySlot)
            emptyList()
        } else if (smart && HotbarItemSlot.OFFHAND.canBeSwapTarget) {
            // Not in the hotbar: one `SWAP` click on the stack with the offhand button (1.16+), the shape `SWITCH`
            // sends. It used to fall through to the 2-3 click `PICKUP` chain below, which carries the item on the
            // cursor between clicks and is the one shape TotemGuard's AutoTotemA can see.
            listOf(InventoryAction.Click.performSwap(from = from, to = HotbarItemSlot.OFFHAND))
        } else {
            buildList(3) {
                this += InventoryAction.Click.performPickup(slot = from)
                this += InventoryAction.Click.performPickup(slot = HotbarItemSlot.OFFHAND)
                if (!player.offhandItem.isEmpty) {
                    this += InventoryAction.Click.performPickup(slot = from)
                }
            }
        }
    }

    fun isOperating() = running && activeMode != Mode.NONE

    private enum class Mode(
        val modeName: String,
        private val item: Predicate<ItemStack>? = null,
        private val fallBackItem: Predicate<ItemStack>? = null,
    ) {
        TOTEM("Totem", Predicate { it.has(DataComponents.DEATH_PROTECTION) }) {
            override fun shouldEquip() = Totem.shouldEquip()

            override fun getDelay() = Totem.switchDelay

            override fun getPrioritizedInventoryPart() = 1

            override fun getSlot(): ItemSlot? {
                val slot = super.getSlot()
                if (slot == null && Crystal.enabled && Crystal.whenNoTotems) {
                    return CRYSTAL.getSlot()
                }

                return slot
            }

            override fun canCycleTo() = Totem.enabled
        },
        STRENGTH("Strength", Predicate { stack ->
            stack.`is`(Items.POTION) && stack.getPotionEffects().any { it.effect == MobEffects.STRENGTH }
        }) {
            override fun shouldEquip(): Boolean {
                val killAura = Strength.onlyWhileKa && !ModuleKillAura.running
                if (!Strength.enabled || killAura || player.hasEffect(MobEffects.STRENGTH)) {
                    return false
                }

                return player.mainHandItem.isSword || !Strength.onlyWhileHoldingSword
            }
        },
        GAPPLE("Gapple", Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE) {
            override fun shouldEquip(): Boolean {
                if (!Gapple.enabled) {
                    return false
                }

                if (player.mainHandItem.isSword && Gapple.WhileHoldingSword.enabled) {
                    return if (Gapple.WhileHoldingSword.onlyWhileKa) {
                        ModuleKillAura.running
                    } else {
                        true
                    }
                }

                return false
            }

            override fun canCycleTo() = Gapple.enabled
        },
        CRYSTAL("Crystal", Items.END_CRYSTAL) {
            override fun canCycleTo() = Crystal.enabled && (!Crystal.onlyWhileCa || ModuleCrystalAura.running)
        },
        BLOCK("Block", ScaffoldBlockItemSelection::isValidBlock) {
            override fun shouldEquip(): Boolean =
                Block.enabled &&
                    ((Block.whileEagle && ModuleEagle.enabled) || (Block.whileScaffold && ModuleScaffold.enabled))

            override fun canCycleTo() = Block.enabled
        },
        BACK("Back") {
            override fun getSlot(): ItemSlot? {
                return last?.let {
                    if (it.first == it.second.itemStack.item) it.second else null
                }
            }
        },
        NONE("None");

        constructor(
            modeName: String,
            item: Item,
            fallBackItem: Item? = null,
        ) : this(modeName, { it.`is`(item) }, fallBackItem?.let { item -> { it.`is`(item) } })

        private var modeBeforeDirectSwitch: Mode? = null

        open fun shouldEquip() = false

        open fun getDelay() = switchDelay

        open fun canCycleTo() = false

        /**
         * 0 = Main inventory
         * 1 = Hotbar
         */
        open fun getPrioritizedInventoryPart() = 0

        fun onBindPress() {
            if (activeMode == this && modeBeforeDirectSwitch != null && modeBeforeDirectSwitch!!.canCycleTo()) {
                staticMode = modeBeforeDirectSwitch!!
                modeBeforeDirectSwitch = null
            } else if (canCycleTo()) {
                modeBeforeDirectSwitch = staticMode
                staticMode = this
            } else {
                modeBeforeDirectSwitch = null
            }
        }

        open fun getSlot(): ItemSlot? {
            if (item == null) {
                return null
            }

            if (item.test(player.offhandItem)) {
                return HotbarItemSlot.OFFHAND
            }

            val slots = if (getPrioritizedInventoryPart() == 0) {
                INVENTORY_MAIN_PRIORITY
            } else {
                INVENTORY_HOTBAR_PRIORITY
            }

            var itemSlot = slots.findSlot(item)
            if (itemSlot == null && fallBackItem != null) {
                if (fallBackItem.test(player.offhandItem)) {
                    return HotbarItemSlot.OFFHAND
                }

                itemSlot = slots.findSlot(fallBackItem)
            }

            return itemSlot
        }
    }

    @Suppress("unused")
    private enum class SwitchMode(override val tag: String) : Tagged {
        /**
         * A SWAP_ITEM_WITH_OFFHAND action for a hotbar item, otherwise one SWAP click like [SWITCH] (1.16+)
         * or, on older servers, the [PICKUP] chain. Works on all versions.
         *
         * It's not the default because some servers kick you when you perform a SWAP_ITEM_WITH_OFFHAND action
         * often and quickly.
         */
        SMART("Smart") {
            override fun performSwitch(from: ItemSlot) = performSwitch(from, true)
        },

        /**
         * Performs a switch action, works on 1.16.
         * The best method on newer servers.
         */
        SWITCH("Switch") {
            override fun performSwitch(from: ItemSlot) = listOf(
                InventoryAction.Click.performSwap(
                    from = from,
                    to = HotbarItemSlot.OFFHAND
                )
            )
        },

        /**
         * Performs 2-3 a pickup actions.
         * Works on all versions.
         */
        PICKUP("PickUp") {
            override fun performSwitch(from: ItemSlot) = performSwitch(from, false)
        },

        /**
         * Chooses the switch action based on the version. Only works if vfp is installed.
         */
        AUTOMATIC("Automatic") {
            override fun performSwitch(from: ItemSlot): List<InventoryAction.Click> {
                return if (isNewerThanOrEquals1_16) {
                    SWITCH.performSwitch(from)
                } else {
                    PICKUP.performSwitch(from)
                }
            }
        };

        abstract fun performSwitch(from: ItemSlot): List<InventoryAction.Click>
    }

}
