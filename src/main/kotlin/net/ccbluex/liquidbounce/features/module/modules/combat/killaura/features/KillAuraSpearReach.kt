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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.combat.SpearUtils
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.item.isSpear
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Lets KillAura strike past the held item's reach by borrowing a longer-reaching one — a spear, or
 * anything else whose `ATTACK_RANGE` component beats what is in hand.
 *
 * ## Why this is far simpler than the standalone SpearReach module
 *
 * [net.ccbluex.liquidbounce.features.module.modules.combat.ModuleSpearReach] has to fight vanilla's
 * pipeline: `startAttack` reads a `hitResult` that `pick` already built from the held item, so the
 * swap must land before `pick` or it changes nothing. KillAura never goes through that path — it
 * calls `MultiPlayerGameMode.attack` itself. All that is required here is that the spear is the
 * selected slot at the moment the packet leaves, and `attack` calls `ensureHasSentCarriedItem`
 * first, so the server is told about the slot before the interaction.
 *
 * The swap is real, not silent: the server resolves reach against the item *it* believes is held,
 * so a silent slot would be rejected for exactly the distances this exists to cover.
 */
object KillAuraSpearReach : ToggleableValueGroup(ModuleKillAura, "SpearReach", false) {

    /**
     * Restrict candidates to spears. Off by default — reach is a property of the component, not of
     * the item being a spear.
     */
    private val spearsOnly by boolean("SpearsOnly", false)

    /**
     * Skip Lunge-enchanted weapons: attacking with one turns the hit into a dash, which is rarely
     * what an aura user wants mid-combo.
     */
    private val avoidLunge by boolean("AvoidLunge", true)

    /** Ticks the borrowed slot is held before being handed back. */
    private const val SWITCH_DELAY = 1

    private var shouldSwitchBack = false
    private var switchBackTick = -1
    private var originalSlot = -1

    /**
     * The furthest reach available from the hotbar, or the held item's when nothing beats it.
     * KillAura widens its aim and range gates to this so a target it could actually hit is not
     * discarded before the swap is ever considered.
     */
    val bestAvailableReach: Float
        get() {
            val player = mc.player ?: return 0f
            var best = SpearUtils.maxReachOf(player, player.mainHandItem)

            if (!running) {
                return best
            }

            for (slot in 0 until Inventory.getSelectionSize()) {
                val stack = player.inventory.getItem(slot)
                if (isCandidate(stack)) {
                    best = maxOf(best, SpearUtils.maxReachOf(player, stack))
                }
            }

            return best
        }

    /**
     * The slot to borrow for a strike on [target], or `null` when the held item already connects or
     * nothing else would.
     *
     * Every candidate is confirmed with a **real raytrace along the rotation KillAura is actually
     * aiming with**, bounded by that item's own reach, and then re-checked against the item's
     * `AttackRange`. A plain distance test is not enough: it ignores the hitbox margin, the *minimum*
     * reach a spear also carries, and terrain in the way — so it green-lights swaps whose attack
     * then misses, and the only visible result is the held item flickering for nothing.
     */
    fun bestSlotFor(target: Entity, rotation: Rotation, throughWallsRange: Double): Int? {
        if (!running) {
            return null
        }

        val player = mc.player ?: return null

        var bestSlot: Int? = null
        var bestReach = KillAuraRange.getAttackRange(player.mainHandItem).effectiveMaxRange(player)

        for (slot in 0 until Inventory.getSelectionSize()) {
            val stack = player.inventory.getItem(slot)
            val reach = KillAuraRange.getAttackRange(stack).effectiveMaxRange(player)

            // The raytrace is the costly part, so it only runs for an item that is both allowed and
            // an actual improvement over what is held.
            if (isCandidate(stack) && reach > bestReach && connects(stack, target, rotation, throughWallsRange)) {
                bestSlot = slot
                bestReach = reach
            }
        }

        return bestSlot
    }

    /**
     * Whether [stack] would genuinely land on [target] along [rotation] — hitbox margin, minimum
     * reach and terrain included, which a bare distance comparison silently ignores.
     */
    private fun connects(
        stack: ItemStack,
        target: Entity,
        rotation: Rotation,
        throughWallsRange: Double
    ): Boolean {
        val player = mc.player ?: return false
        val range = KillAuraRange.getAttackRange(stack)

        val hit = isLookingAtEntity(
            toEntity = target,
            rotation = rotation,
            range = range.effectiveMaxRange(player).toDouble(),
            throughWallsRange = throughWallsRange
        ) ?: return false

        return range.isInRange(player, hit.location)
    }

    /**
     * Selects a slot resolved earlier by [bestSlotFor]. Call immediately before the attack — it is
     * handed back a tick later.
     */
    fun engage(slot: Int?) {
        if (!running || slot == null) {
            return
        }

        val player = mc.player ?: return
        if (slot == player.inventory.selectedSlot) {
            return
        }

        if (!shouldSwitchBack) {
            originalSlot = player.inventory.selectedSlot
        }

        player.inventory.selectedSlot = slot
        shouldSwitchBack = true
        switchBackTick = player.tickCount + SWITCH_DELAY
    }

    private fun isCandidate(stack: ItemStack): Boolean = when {
        stack.isEmpty -> false
        spearsOnly && !stack.isSpear -> false
        avoidLunge && stack.getEnchantment(Enchantments.LUNGE) > 0 -> false
        else -> true
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        handBackSlot()
    }

    private fun handBackSlot() {
        if (!shouldSwitchBack) {
            return
        }

        val player = mc.player
        if (player == null) {
            clear()
            return
        }

        // tickCount rewound (respawn / world change): drop the swap rather than restore onto a
        // hotbar that is no longer the one we borrowed from.
        if (player.tickCount + SWITCH_DELAY < switchBackTick) {
            clear()
            return
        }

        if (player.tickCount >= switchBackTick) {
            if (originalSlot in 0..8) {
                player.inventory.selectedSlot = originalSlot
            }
            clear()
        }
    }

    private fun clear() {
        shouldSwitchBack = false
        switchBackTick = -1
        originalSlot = -1
    }

    override fun onDisabled() {
        handBackSlot()
        clear()
    }
}
