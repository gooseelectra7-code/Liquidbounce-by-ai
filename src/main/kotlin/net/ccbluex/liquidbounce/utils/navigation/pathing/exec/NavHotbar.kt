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

import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findBestToolToMineBlock
import net.ccbluex.liquidbounce.utils.item.getDestroySpeedWithEnchantment
import net.ccbluex.liquidbounce.utils.item.isFullBlock
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.ceil

/**
 * Picking tools and building blocks out of the hotbar.
 *
 * Main thread only — everything here reads live inventory state. The planner never calls into this;
 * it works from the capability snapshot in
 * [net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps] instead.
 */
object NavHotbar {

    /** Best mining tool in the hotbar for [state], or null to use a bare hand. */
    fun bestDigTool(state: BlockState): HotbarItemSlot? =
        Slots.Hotbar.findBestToolToMineBlock(state, ignoreDurability = false)

    /**
     * A hotbar slot holding a block worth building with.
     *
     * Gravity-affected blocks are skipped: a bridge of sand falls out from under the player who
     * just placed it.
     */
    fun buildingBlock(): HotbarItemSlot? = Slots.Hotbar.firstOrNull { slot ->
        val stack = slot.itemStack
        if (stack.isEmpty || !stack.isFullBlock()) return@firstOrNull false
        val block = (stack.item as? BlockItem)?.block ?: return@firstOrNull false
        block !is FallingBlock
    }

    /** Total blocks available to build with, across the hotbar. */
    fun buildingBlockCount(): Int = Slots.Hotbar.sumOf { slot ->
        val stack = slot.itemStack
        if (stack.isEmpty || !stack.isFullBlock()) return@sumOf 0
        val block = (stack.item as? BlockItem)?.block
        if (block == null || block is FallingBlock) 0 else stack.count
    }

    /**
     * Is a wing worn and fit to fly?
     *
     * One durability point from breaking counts as no wing at all: taking off on it means the glide
     * ends the moment it snaps, which is the worst possible moment for it to happen.
     */
    fun wearingElytra(): Boolean {
        if (player.isCreative) return false

        val chest = player.getItemBySlot(EquipmentSlot.CHEST)
        return chest.item === Items.ELYTRA && !chest.nextDamageWillBreak()
    }

    /**
     * Ticks to break one block of hardness 1 with the best tool currently carried.
     *
     * This is the single number the planner needs to price every dig edge, so it is measured once
     * against a reference block rather than recomputed per candidate. Vanilla's destroy-progress
     * divisor is 30 with a correct tool and 100 without.
     */
    fun breakTicksPerHardness(referenceState: BlockState): Double {
        if (player.isCreative) return CREATIVE_BREAK_TICKS

        val tool = bestDigTool(referenceState)
        val speed = (tool?.itemStack?.getDestroySpeedWithEnchantment(referenceState) ?: 1f).coerceAtLeast(1f)
        val correctTool = tool?.itemStack?.isCorrectToolForDrops(referenceState) ?: false
        val divisor = if (correctTool) CORRECT_TOOL_DIVISOR else BARE_HAND_DIVISOR

        return ceil(divisor / speed.toDouble()).coerceAtLeast(MIN_BREAK_TICKS)
    }

    /** Breaking is effectively instant in creative, but still costs a tick of swing. */
    private const val CREATIVE_BREAK_TICKS = 1.0

    private const val CORRECT_TOOL_DIVISOR = 30.0
    private const val BARE_HAND_DIVISOR = 100.0
    private const val MIN_BREAK_TICKS = 1.0
}
