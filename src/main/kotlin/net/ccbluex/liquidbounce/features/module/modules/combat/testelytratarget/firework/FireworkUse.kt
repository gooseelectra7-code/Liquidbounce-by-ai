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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.firework

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.RocketWatch
import net.ccbluex.liquidbounce.features.module.modules.player.offhand.ModuleOffhand
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Spending fireworks.
 *
 * Using a rocket while gliding is plain vanilla: `FireworkRocketItem.use` launches one exactly when
 * the player is fall-flying, and the rocket then pushes along the wearer's look direction. For us
 * that look is the rotation on the wire rather than the camera, because `MixinFireworkRocketEntity`
 * substitutes `RotationManager.currentRotation`, so a boost always goes where the guidance law is
 * pointing and never where the player happens to be looking.
 *
 * The interesting part is not the firing, it is the restraint. A rocket is the only source of energy
 * in the air, and spending one to go faster in a direction that was already going to overshoot makes
 * the chase worse, not better. So the module only asks for a boost when it is short of energy
 * relative to the target *and* the rolled-forward flight says the boost genuinely shortens the
 * intercept; everything here is the mechanism for a decision taken elsewhere.
 */
class FireworkUse(module: ClientModule) : ToggleableValueGroup(module, "AutoFirework", true) {

    /**
     * How a rocket gets into a hand and used.
     *
     * The offhand is preferred because it is the only slot where a rocket can sit permanently
     * without costing us the weapon: swapping the main hand mid-chase would hand the aura a rocket
     * to hit people with.
     */
    enum class UseMode(override val tag: String) : Tagged {
        /** Use whatever rocket is already held. No inventory interaction at all. */
        PACKET("Packet"),

        /** Select a rocket silently for a single tick, use it, and let the selection lapse. */
        SILENT_SWAP("SilentSwap"),

        /** Keep a rocket in the offhand and use it from there. */
        PREFER_OFFHAND("PreferOffhand"),
    }

    private val useMode by enumChoice("UseMode", UseMode.PREFER_OFFHAND)

    /**
     * Floor on the gap between two rockets. Not an anticheat concern so much as an economic one:
     * rockets fired inside one another mostly overwrite each other's push, so the second one is
     * wasted.
     */
    private val minInterval by int("MinInterval", 300, 0..3000, "ms")

    /**
     * How much sooner the boost has to bring the target into reach before it is worth a rocket.
     * Consumed by the module, which owns the rollout comparison.
     */
    val boostGainTicks by int("BoostGainTicks", 4, 1..20, "ticks")

    private val cooldown = Chronometer()
    private val offhandSwapCooldown = Chronometer()
    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    /** Flight rating of the last rocket we actually fired, used to estimate how long it burns. */
    private var lastFiredFlight = DEFAULT_FLIGHT

    /**
     * Fires a rocket if one is wanted and everything agrees it may be fired.
     *
     * The cooldown is spent only on a real launch. A rejection by one of the gates costs nothing, so
     * a blocked tick does not push the next genuine opportunity further away.
     */
    fun consider(demand: Boolean, yRot: Float, xRot: Float): Boolean {
        if (!running || !demand || !player.isFallFlying) {
            return false
        }

        // A hand swap in flight owns the use packets for its whole window; firing inside it is how
        // you get a use that arrives attached to the wrong item.
        if (HandSwapGate.holdsUses || HandSwapGate.holdsAttacks) {
            return false
        }
        if (!cooldown.hasElapsed(minInterval.toLong())) {
            return false
        }

        val fired = when (useMode) {
            UseMode.PACKET -> fireFromHand(yRot, xRot)
            UseMode.SILENT_SWAP -> fireSilently(yRot, xRot)
            UseMode.PREFER_OFFHAND -> if (offhandHoldsRocket()) fireOffhand(yRot, xRot) else fireSilently(yRot, xRot)
        }

        if (fired) {
            cooldown.reset()
        }
        return fired
    }

    /** Ticks of push still expected from our own rockets, for the trajectory rollout. */
    fun ownBoostTicksLeft(): Int = RocketWatch.remainingBoostTicks(player, lastFiredFlight)

    fun reset() {
        cooldown.reset()
        offhandSwapCooldown.reset()
    }

    private fun fireFromHand(yRot: Float, xRot: Float): Boolean = when {
        offhandHoldsRocket() -> fireOffhand(yRot, xRot)
        player.mainHandItem.`is`(Items.FIREWORK_ROCKET) -> {
            rememberFlight(player.mainHandItem)
            useItem(InteractionHand.MAIN_HAND, yRot, xRot, SwingMode.DO_NOT_HIDE) is InteractionResult.Success
        }

        else -> false
    }

    private fun fireOffhand(yRot: Float, xRot: Float): Boolean {
        rememberFlight(HotbarItemSlot.OFFHAND.itemStack)
        return useItem(InteractionHand.OFF_HAND, yRot, xRot, SwingMode.DO_NOT_HIDE) is InteractionResult.Success
    }

    private fun fireSilently(yRot: Float, xRot: Float): Boolean {
        val slot = Slots.OffhandWithHotbar.findSlot(Items.FIREWORK_ROCKET) ?: return false
        rememberFlight(slot.itemStack)
        return useHotbarSlotOrOffhand(slot, ticksUntilReset = 1, yRot, xRot) is InteractionResult.Success
    }

    private fun offhandHoldsRocket() = HotbarItemSlot.OFFHAND.itemStack.`is`(Items.FIREWORK_ROCKET)

    private fun rememberFlight(stack: ItemStack) {
        lastFiredFlight = stack.get(DataComponents.FIREWORKS)?.flightDuration()?.coerceAtLeast(1) ?: DEFAULT_FLIGHT
    }

    /**
     * Keeps a rocket in the offhand while [UseMode.PREFER_OFFHAND] is selected.
     *
     * The offhand is contested property. Totem management, the inventory cleaner and the swap modules
     * all want it, and two of them moving items in the same window is how a client ends up holding
     * nothing at all, so this yields to anyone already operating and refuses to touch a slot that is
     * not a legal swap target. All of those checks happen before the cooldown is spent, so a refusal
     * does not delay the next legitimate attempt.
     */
    @Suppress("unused")
    private val offhandRefillHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (useMode != UseMode.PREFER_OFFHAND || !player.isFallFlying || offhandHoldsRocket()) {
            return@handler
        }
        if (ModuleOffhand.isOperating() || !HotbarItemSlot.OFFHAND.canBeSwapTarget) {
            return@handler
        }
        if (!offhandSwapCooldown.hasElapsed(OFFHAND_SWAP_COOLDOWN_MS)) {
            return@handler
        }

        val source = Slots.HotbarAndInventory.findSlot(Items.FIREWORK_ROCKET) ?: return@handler
        event.schedule(
            inventoryConstraints,
            listOf(InventoryAction.Click.performSwap(from = source, to = HotbarItemSlot.OFFHAND))
        )
        offhandSwapCooldown.reset()
    }

    private companion object {
        /** A plain rocket with no flight component still boosts; treat it as flight 1. */
        const val DEFAULT_FLIGHT = 1

        const val OFFHAND_SWAP_COOLDOWN_MS = 500L
    }
}
