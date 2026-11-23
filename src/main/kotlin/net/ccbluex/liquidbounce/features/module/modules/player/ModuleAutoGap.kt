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

import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.entity.usingItemOrNull
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items

/**
 * AutoGap module
 *
 * Keeps a golden apple in your off-hand and eats it whenever your health drops
 * below a threshold, with a custom cooldown between each apple.
 *
 * A standalone, self-contained alternative to the AutoBuff-based gapple.
 */
object ModuleAutoGap : ClientModule("AutoGap", ModuleCategories.PLAYER) {

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    // Use enchanted (notch) apples as well, otherwise only regular golden apples.
    private val enchanted by boolean("Enchanted", true)

    // Eat once health (optionally + absorption) falls to or below this value.
    private val healthThreshold by float("Health", 12f, 1f..20f, "HP")
    private val considerAbsorption by boolean("ConsiderAbsorption", true)

    // Automatically move a golden apple into the off-hand slot.
    private val moveToOffhand by boolean("MoveToOffhand", true)

    // Custom delay enforced after finishing an apple before the next one may start.
    private val cooldown by int("Cooldown", 500, 0..5000, "ms")

    private val cooldownTimer = Chronometer()

    /** Tracks whether we observed an apple being eaten on the previous key poll. */
    private var eating = false

    private fun isGapple(stack: ItemStack): Boolean =
        stack.`is`(Items.GOLDEN_APPLE) || (enchanted && stack.`is`(Items.ENCHANTED_GOLDEN_APPLE))

    private val effectiveHealth: Float
        get() = player.health + if (considerAbsorption) player.absorptionAmount else 0f

    /** True while the player is actually chewing an apple (gated on [isUsingItem]). */
    private val isEatingOffhand: Boolean
        get() = player.usingItemOrNull?.useAnimation == ItemUseAnimation.EAT

    private val onCooldown: Boolean
        get() = !cooldownTimer.hasElapsed(cooldown.toLong())

    override fun onDisabled() {
        eating = false
    }

    /**
     * Auto-eat only forces the use key down; it never releases or hijacks a key the player is
     * pressing themselves. The decision is made at poll time so vanilla's own use handling
     * (manual presses, auto-repeat) is left completely untouched.
     */
    @Suppress("unused")
    private val keyBindIsPressedHandler = handler<KeybindIsPressedEvent> { event ->
        if (event.keyBinding != mc.options.keyUse) {
            return@handler
        }

        // Detect the instant any apple finishes (manual or auto) and start the cooldown, so the
        // module's next auto-eat respects the delay - manual eating still works in between.
        val eatingNow = isEatingOffhand
        if (eating && !eatingNow) {
            cooldownTimer.reset()
        }
        eating = eatingNow

        // Auto-eat: only when a gapple is in the off-hand, health is low and we're off cooldown.
        // The condition stays true throughout the chew, so the key is held until the apple is done.
        if (isGapple(player.offhandItem) && !onCooldown && effectiveHealth <= healthThreshold) {
            event.isPressed = true
        }
    }

    @Suppress("unused")
    private val offhandHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (!moveToOffhand || eating || isGapple(player.offhandItem)) {
            return@handler
        }

        val slot = Slots.HotbarAndInventory.findSlot(::isGapple) ?: return@handler

        event.schedule(
            inventoryConstraints,
            InventoryAction.Click.performSwap(from = slot, to = HotbarItemSlot.OFFHAND)
        )
    }

}
