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
package net.ccbluex.liquidbounce.utils.combat.intel

import net.ccbluex.liquidbounce.utils.entity.armorItems
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.getEffectiveDamage
import net.ccbluex.liquidbounce.utils.entity.hasHealthScoreboard
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.item.attackDamage
import net.ccbluex.liquidbounce.utils.item.attackSpeed
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.item.getAttributeValue
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import kotlin.math.ceil

/**
 * Pure, cheap intel derived on demand for the current target — health the way nametags read it,
 * armor scoring, and an explainable win-chance estimate. No state of its own; the win-chance step
 * is throttled to ~1 Hz by the caller supplying [gameTicks].
 */
object TargetIntel {

    /**
     * Health the way the nametag renders it: [getActualHealth] (BELOW_NAME scoreboard, else synced
     * health) plus absorption, but never absorption on top of a scoreboard value — that already
     * folds everything in. Mirrors `NametagTextFormatter.HEALTH`. This is the single definition the
     * HUD looks all call so the number is identical everywhere.
     */
    fun LivingEntity.nametagHealth(): Float =
        getActualHealth() + if (hasHealthScoreboard()) 0f else absorptionAmount

    /** Worst armor piece durability as a percentage, or 100 when nothing is worn / indestructible. */
    fun Player.lowestArmorDurabilityPercent(): Float {
        var lowest = 100f
        for (stack in armorItems) {
            if (stack.isEmpty || stack.maxDamage <= 0) continue
            val percent = (stack.maxDamage - stack.damageValue) * 100f / stack.maxDamage
            if (percent < lowest) lowest = percent
        }
        return lowest
    }

    /**
     * A single comparable armor number: raw armor + a slice of toughness and Protection, scaled by
     * how much durability is left (a near-broken set protects less). Only for the "better than mine"
     * verdict, so absolute accuracy is unnecessary — the relative ordering is what matters.
     */
    fun Player.armorQualityScore(): Float {
        var score = 0f
        for (slot in ARMOR_SLOTS) {
            val stack = getItemBySlot(slot)
            if (stack.isEmpty) continue
            val armor = stack.getAttributeValue(Attributes.ARMOR, slot, 0.0).toFloat()
            val toughness = stack.getAttributeValue(Attributes.ARMOR_TOUGHNESS, slot, 0.0).toFloat()
            val protection = stack.getEnchantment(Enchantments.PROTECTION)
            val durabilityFraction = if (stack.maxDamage > 0) {
                (stack.maxDamage - stack.damageValue).toFloat() / stack.maxDamage
            } else {
                1f
            }
            score += (armor + 0.25f * toughness + 0.5f * protection) * durabilityFraction
        }
        return score
    }

    enum class ArmorVerdict { BETTER, EVEN, WORSE }

    private fun verdict(theirs: Float, mine: Float): ArmorVerdict = when {
        theirs > mine * 1.1f -> ArmorVerdict.BETTER
        theirs < mine * 0.9f -> ArmorVerdict.WORSE
        else -> ArmorVerdict.EVEN
    }

    fun Player.carriesShield(): Boolean =
        offhandItem.has(DataComponents.BLOCKS_ATTACKS) || mainHandItem.has(DataComponents.BLOCKS_ATTACKS)

    fun LivingEntity.isEating(): Boolean = isUsingItem

    data class TargetIntelSnapshot(
        val winChance: Float,
        val pops: Int,
        val gapples: PvpIntelTracker.GappleEstimate,
        val lowestArmorPct: Float,
        val armorVerdict: ArmorVerdict,
        val ping: Int,
        val carriesShield: Boolean,
        val eating: Boolean,
        val combo: Int,
        val damageByMe: Float,
        val damageToMe: Float,
        /** Server-confirmed hits landed on the target since this fight started. */
        val hitsByMe: Int,
        /** …and hits they landed on us. */
        val hitsToMe: Int,
        val teamPartner: String?,
    )

    fun build(self: LocalPlayer, target: Player, gameTicks: Long): TargetIntelSnapshot {
        val stats = PvpIntelTracker.combatStats(target.uuid)
        val partner = TeamDetector.partnersOf(target.uuid).firstOrNull()?.let {
            if (it.a == target.uuid) it.bName else it.aName
        }
        return TargetIntelSnapshot(
            winChance = winChance(self, target, stats, gameTicks),
            pops = PvpIntelTracker.popCount(target.uuid),
            gapples = PvpIntelTracker.gappleEstimate(target),
            lowestArmorPct = target.lowestArmorDurabilityPercent(),
            armorVerdict = verdict(target.armorQualityScore(), self.armorQualityScore()),
            ping = target.ping,
            carriesShield = target.carriesShield(),
            eating = target.isEating(),
            combo = stats.combo,
            damageByMe = stats.damageByMe,
            damageToMe = stats.damageToMe,
            hitsByMe = stats.hitsByMe,
            hitsToMe = stats.hitsToMe,
            teamPartner = partner,
        )
    }

    // ── Win chance ───────────────────────────────────────────────────────────

    private var cacheKey = -1L
    private var cacheTargetId = -1
    private var cachedWinChance = 0.5f

    /**
     * A deterministic, explainable win estimate, recomputed at most once per second:
     *
     * - Effective HP pool each side = [nametagHealth] + 10 per visible held totem + 4 per estimated
     *   gapple (capped at three) — the sustain a fight actually has to chew through.
     * - Damage per hit each way through [getEffectiveDamage] with the attacker's weapon damage
     *   (base + Sharpness), so armor / Protection / Resistance are priced exactly as vanilla would.
     * - Time-to-kill = ceil(pool / dmg) hits ÷ weapon attack speed.
     * - Momentum from the tracker's 60 s ledger, Laplace-smoothed so an empty ledger reads 0.5.
     * - winChance = 0.65 · (ttkTarget-normalized base) + 0.35 · momentum, clamped to [0.02, 0.98].
     */
    private fun winChance(self: LocalPlayer, target: Player, stats: PvpIntelTracker.CombatStats, ticks: Long): Float {
        val key = ticks / TICKS_PER_SECOND
        if (key == cacheKey && cacheTargetId == target.id) {
            return cachedWinChance
        }
        cacheKey = key
        cacheTargetId = target.id

        val myPool = pool(self, self)
        val targetPool = pool(target, self)

        val myDmgPerHit = self.mainHandItem.attackDamage.toFloat()
        val targetDmgPerHit = target.mainHandItem.attackDamage.toFloat()

        val ttkTarget = timeToKill(target, self, myDmgPerHit, targetPool, self.mainHandItem.attackSpeed)
        val ttkMe = timeToKill(self, target, targetDmgPerHit, myPool, target.mainHandItem.attackSpeed)

        val base = ttkMe / (ttkMe + ttkTarget).coerceAtLeast(1.0E-3f)
        val momentum = (stats.damageByMe + 4f) / (stats.damageByMe + stats.damageToMe + 8f)

        cachedWinChance = (WIN_BASE_WEIGHT * base + WIN_MOMENTUM_WEIGHT * momentum)
            .coerceIn(WIN_FLOOR, WIN_CEIL)
        return cachedWinChance
    }

    private fun pool(entity: Player, self: LocalPlayer): Float {
        val totems = countTotems(entity)
        val gapples = if (entity === self) 0 else PvpIntelTracker.gappleEstimate(entity).count
        return entity.nametagHealth() +
            TOTEM_HP * totems +
            GAPPLE_HP * gapples.coerceAtMost(GAPPLE_CAP)
    }

    private fun countTotems(entity: Player): Int {
        var totems = 0
        if (entity.offhandItem.`is`(Items.TOTEM_OF_UNDYING)) totems++
        if (entity.mainHandItem.`is`(Items.TOTEM_OF_UNDYING)) totems++
        return totems
    }

    private fun timeToKill(
        victim: LivingEntity,
        attacker: LivingEntity,
        rawDamage: Float,
        pool: Float,
        attackSpeed: Double,
    ): Float {
        val source = victim.level().damageSources().playerAttack(attacker as? Player ?: return Float.MAX_VALUE)
        val perHit = victim.getEffectiveDamage(source, rawDamage).coerceAtLeast(MIN_DAMAGE_PER_HIT)
        val hits = ceil(pool / perHit).coerceAtLeast(1f)
        val speed = attackSpeed.toFloat().coerceAtLeast(MIN_ATTACK_SPEED)
        return hits / speed
    }

    private val ARMOR_SLOTS = arrayOf(
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    )

    private const val TOTEM_HP = 10f
    private const val GAPPLE_HP = 4f
    private const val GAPPLE_CAP = 3
    private const val MIN_DAMAGE_PER_HIT = 0.5f
    private const val MIN_ATTACK_SPEED = 0.5f
    private const val WIN_BASE_WEIGHT = 0.65f
    private const val WIN_MOMENTUM_WEIGHT = 0.35f
    private const val WIN_FLOOR = 0.02f
    private const val WIN_CEIL = 0.98f
    private const val TICKS_PER_SECOND = 20L
}
