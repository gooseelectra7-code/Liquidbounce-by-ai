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
package net.ccbluex.liquidbounce.utils.combat

import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.item.isSpear
import net.minecraft.core.component.DataComponents
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.AttackRange
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.phys.EntityHitResult

/**
 * Spear utilities for cooldown prediction and spear-related targeting.
 *
 * Ported from Echo client (SpearUtils.java + CombatUtils.java).
 */
@Suppress("TooManyFunctions")
object SpearUtils {

    /**
     * The spear attack range used for the "would a spear hit?" crosshair check.
     * Echo resolves this from the WOODEN_SPEAR item's ATTACK_RANGE component and
     * falls back to the vanilla spear values.
     */
    private val SPEAR_ATTACK_RANGE: AttackRange by lazy {
        Items.WOODEN_SPEAR.defaultInstance.get(DataComponents.ATTACK_RANGE)
            ?: AttackRange(2.0f, 4.5f, 2.0f, 6.5f, 0.125f, 0.5f)
    }

    /**
     * Tracks the player's tickCount at the time of the last hand swing.
     * On the server, ServerPlayer.swing() calls resetAttackStrengthTicker() which
     * resets both attackStrengthTicker and itemSwapTicker to 0. The client does NOT
     * reset these tickers on swing (only on attack/item change), causing a desync
     * when the player uses an item or places a block. We track swings ourselves to
     * mirror the server's behavior.
     */
    /**
     * `null` until the first swing is seen, meaning "long enough ago to be irrelevant".
     *
     * This used to be an `Int.MIN_VALUE` sentinel, which looked like the same idea and was the
     * exact opposite: `tickCount - Int.MIN_VALUE` overflows to a large *negative* ticker, the
     * projected scale clamps to 0, and every caller concludes the swap is still on cooldown. With
     * PaperSupport on — the default — that blocked the spear modules outright until the player
     * happened to swing at something.
     */
    private var lastSwingPlayerTick: Int? = null

    @JvmStatic
    fun onPlayerSwing(player: Player?) {
        if (player != null) {
            lastSwingPlayerTick = player.tickCount
        }
    }

    private fun getSwingTicker(player: Player?): Int {
        if (player == null) {
            return Int.MAX_VALUE
        }

        val last = lastSwingPlayerTick ?: return Int.MAX_VALUE

        // A respawn or world change rewinds tickCount; treat that as "no recent swing" rather than
        // letting a negative ticker read as a fresh one.
        return if (player.tickCount < last) Int.MAX_VALUE else player.tickCount - last
    }

    @JvmStatic
    fun isSpearFullyCooled(player: Player, spearStack: ItemStack, partialTicks: Float): Boolean =
        getProjectedSpearAttackStrengthScale(player, spearStack, partialTicks) >= 1.0f

    @JvmStatic
    fun isSpearSwapFullyCooled(player: Player, spearStack: ItemStack, partialTicks: Float): Boolean {
        val effectiveTicker = minOf(getItemSwapTicker(player), getSwingTicker(player))
        return getProjectedSpearScale(player, spearStack, partialTicks, effectiveTicker) >= 1.0f
    }

    fun getProjectedSpearAttackStrengthScale(player: Player, spearStack: ItemStack, partialTicks: Float): Float =
        getProjectedSpearScale(player, spearStack, partialTicks, getAttackStrengthTicker(player))

    fun getProjectedSpearSwapStrengthScale(player: Player, spearStack: ItemStack, partialTicks: Float): Float {
        val effectiveTicker = minOf(getItemSwapTicker(player), getSwingTicker(player))
        return getProjectedSpearScale(player, spearStack, partialTicks, effectiveTicker)
    }

    fun getRemainingSpearAttackCooldownSeconds(player: Player, spearStack: ItemStack, partialTicks: Float): Double =
        getRemainingCooldownSeconds(player, spearStack, partialTicks, getAttackStrengthTicker(player))

    fun getRemainingSpearSwapCooldownSeconds(player: Player, spearStack: ItemStack, partialTicks: Float): Double {
        val effectiveTicker = minOf(getItemSwapTicker(player), getSwingTicker(player))
        return getRemainingCooldownSeconds(player, spearStack, partialTicks, effectiveTicker)
    }

    private fun getAttackStrengthTicker(player: Player): Int =
        (player as LivingEntity).attackStrengthTicker

    private fun getItemSwapTicker(player: Player): Int =
        (player as LivingEntity).itemSwapTicker

    private fun getProjectedSpearScale(player: Player, spearStack: ItemStack, partialTicks: Float, ticker: Int): Float {
        val projectedDelay = getProjectedSpearAttackDelay(player, spearStack)
        if (projectedDelay <= 0.0f) return 0.0f
        return Mth.clamp((ticker + partialTicks) / projectedDelay, 0.0f, 1.0f)
    }

    private fun getRemainingCooldownSeconds(
        player: Player,
        spearStack: ItemStack,
        partialTicks: Float,
        ticker: Int
    ): Double {
        val projectedDelay = getProjectedSpearAttackDelay(player, spearStack)
        if (projectedDelay <= 0.0f) return 0.0
        return maxOf(0.0, (projectedDelay - (ticker + partialTicks)) / 20.0)
    }

    /**
     * Projects what the player's attack delay (in ticks) would be if [spearStack] was
     * held in the main hand: takes the ATTACK_SPEED attribute base value, applies all
     * non-item modifiers currently on the player (skipping the held item's
     * BASE_ATTACK_SPEED_ID modifier) plus the spear's own MAINHAND modifiers,
     * sanitizes and converts to ticks (20 / speed). Mirrors AttributeInstance.calculateValue().
     */
    private fun getProjectedSpearAttackDelay(player: Player, spearStack: ItemStack): Float {
        if (spearStack.isEmpty) return 0.0f

        val attackSpeedInstance = player.getAttribute(Attributes.ATTACK_SPEED)
            ?: return player.currentItemAttackStrengthDelay

        var add = 0.0
        var addMultBase = 0.0
        var addMultTotal = 1.0

        for (modifier in attackSpeedInstance.modifiers) {
            if (modifier.id() == Item.BASE_ATTACK_SPEED_ID) continue

            when (modifier.operation()) {
                AttributeModifier.Operation.ADD_VALUE -> add += modifier.amount()
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE -> addMultBase += modifier.amount()
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL -> addMultTotal *= 1.0 + modifier.amount()
            }
        }

        spearStack.forEachModifier(EquipmentSlot.MAINHAND) { holder, modifier ->
            if (holder == Attributes.ATTACK_SPEED) {
                when (modifier.operation()) {
                    AttributeModifier.Operation.ADD_VALUE -> add += modifier.amount()
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE -> addMultBase += modifier.amount()
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL -> addMultTotal *= 1.0 + modifier.amount()
                }
            }
        }

        val base = attackSpeedInstance.baseValue
        val withAdds = base + add
        val withBaseMultiplier = withAdds + (withAdds * addMultBase)
        var projectedSpeed = withBaseMultiplier * addMultTotal
        projectedSpeed = Attributes.ATTACK_SPEED.value().sanitizeValue(projectedSpeed)

        return if (projectedSpeed <= 0.0) 0.0f else (20.0 / projectedSpeed).toFloat()
    }

    /**
     * The living entity under the crosshair that a SPEAR could hit right now
     * (raycast with the spear's ATTACK_RANGE component).
     */
    @JvmStatic
    fun getSpearCrosshairTarget(player: Player?, tickDelta: Float): LivingEntity? =
        getCrosshairTarget(player, SPEAR_ATTACK_RANGE, tickDelta)

    /**
     * The living entity under the crosshair that the CURRENTLY HELD item can hit
     * (raycast with the held item's ATTACK_RANGE component, or the player default).
     */
    @JvmStatic
    fun getCurrentItemCrosshairTarget(player: Player?, tickDelta: Float): LivingEntity? {
        if (player == null || !player.isAlive) {
            return null
        }

        val held = player.mainHandItem
        val range = (if (held.isEmpty) null else held.get(DataComponents.ATTACK_RANGE))
            ?: AttackRange.defaultFor(player)

        return getCrosshairTarget(player, range, tickDelta)
    }

    @JvmStatic
    fun hasCurrentItemCrosshairTarget(player: Player?, tickDelta: Float): Boolean =
        getCurrentItemCrosshairTarget(player, tickDelta) != null

    /**
     * The living entity under the crosshair that [stack] could hit, using that stack's own
     * `ATTACK_RANGE` (falling back to the player default when it carries none).
     *
     * This is the same raycast vanilla runs in `LocalPlayer.raycastHitResult`, so asking it about a
     * stack we are *not* holding answers "would this reach, if it were in hand".
     */
    @JvmStatic
    fun getCrosshairTargetWith(player: Player?, stack: ItemStack, tickDelta: Float): LivingEntity? {
        if (player == null) {
            return null
        }

        val range = (if (stack.isEmpty) null else stack.get(DataComponents.ATTACK_RANGE))
            ?: AttackRange.defaultFor(player)

        return getCrosshairTarget(player, range, tickDelta)
    }

    /**
     * How far [stack] can reach for [player], for comparing candidates against what is held.
     */
    @JvmStatic
    fun maxReachOf(player: Player, stack: ItemStack): Float {
        val range = (if (stack.isEmpty) null else stack.get(DataComponents.ATTACK_RANGE))
            ?: AttackRange.defaultFor(player)

        return range.effectiveMaxRange(player)
    }

    private fun getCrosshairTarget(player: Player?, range: AttackRange?, tickDelta: Float): LivingEntity? {
        if (player == null || range == null || !player.isAlive) {
            return null
        }

        val hitResult = range.getClosesetHit(player, tickDelta, EntitySelector.CAN_BE_PICKED)
        val entityHitResult = hitResult as? EntityHitResult ?: return null
        if (!range.isInRange(player, entityHitResult.location)) {
            return null
        }

        val livingEntity = entityHitResult.entity as? LivingEntity ?: return null
        if (!livingEntity.isAlive || livingEntity.isRemoved) {
            return null
        }

        return livingEntity
    }

    @JvmStatic
    fun findSpearSlotWithoutLunge(): Int {
        val player = mc.player ?: return -1
        val inventory = player.inventory

        for (i in 0 until Inventory.getSelectionSize()) {
            val stack = inventory.getItem(i)
            if (!stack.isEmpty && stack.isSpear && stack.getEnchantment(Enchantments.LUNGE) == 0) {
                return i
            }
        }

        return -1
    }

    @JvmStatic
    fun findSpearSlotWithLunge(): Int {
        val player = mc.player ?: return -1
        val inventory = player.inventory

        for (i in 0 until Inventory.getSelectionSize()) {
            val stack = inventory.getItem(i)
            if (!stack.isEmpty && stack.isSpear && stack.getEnchantment(Enchantments.LUNGE) > 0) {
                return i
            }
        }

        return -1
    }

    @JvmStatic
    fun Player.isHoldingSpear(): Boolean = !mainHandItem.isEmpty && mainHandItem.isSpear
}
