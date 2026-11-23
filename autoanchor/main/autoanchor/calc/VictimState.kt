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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc

import net.minecraft.world.Difficulty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * One `BlocksAttacks.DamageReduction` of the item the victim is blocking with (`BlocksAttacks.java:124`:
 * `record DamageReduction(float horizontalBlockingAngle, Optional<HolderSet<DamageType>> type, float base,
 * float factor)`).
 *
 * [typeMatches] pre-resolves the registry-backed half of `DamageReduction.resolve` (`BlocksAttacks.java:150`): it is
 * `false` exactly when `this.type.isPresent() && !this.type.get().contains(source.typeHolder())` for the anchor's
 * `bad_respawn_point` damage type. Vanilla shields carry the default reduction
 * `new DamageReduction(90.0F, Optional.empty(), 0.0F, 1.0F)` (`BlocksAttacks.java:43`).
 */
data class BlockReduction(
    val horizontalBlockingAngle: Float,
    val typeMatches: Boolean,
    val base: Float,
    val factor: Float,
)

/**
 * Snapshot of the `LivingEntity.applyItemBlocking` inputs (`LivingEntity.java:1285-1322`), captured only while the
 * victim is blocking, i.e. `getItemBlockingWith()` is non-null (`LivingEntity.java:3566-3580`: using an item with
 * `DataComponents.BLOCKS_ATTACKS` for at least `blocksAttacks.blockDelayTicks()` ticks).
 *
 * - [reductions]: `blocksAttacks.damageReductions()` (`BlocksAttacks.java:31`).
 * - [bypassed]: `blocksAttacks.bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)`
 *   (`LivingEntity.java:1294`).
 * - [viewVector]: `this.calculateViewVector(0.0F, this.getYHeadRot())` (`LivingEntity.java:1301`;
 *   `Entity.java:1875-1883`, `LivingEntity.java:3302-3304`).
 * - [position]: `this.position()` (`LivingEntity.java:1302`). Vanilla reads it at explosion time, so the capture
 *   stores the same PREDICTED position the owning [VictimState.feet] carries - the arc and the distance must
 *   come from one position, or a victim predicted to run past the blast is scored with the wrong shield side
 *   (the world snapshot that builds this state owns that pairing).
 */
data class BlockingState(
    val reductions: List<BlockReduction>,
    val bypassed: Boolean,
    val viewVector: Vec3,
    val position: Vec3,
)

/**
 * Everything the damage pipeline needs about one victim, frozen for one evaluation.
 *
 * - [feet]: position used for the distance (`Entity.distanceToSqr`, `Entity.java:1775-1780`), predicted when
 *   applicable.
 * - [box]: bounding box at that position (exposure sampling, `ServerExplosion.getSeenPercent`).
 * - [isPlayer]: difficulty scaling lives in `Player.hurtServer` only (`Player.java:688-700`).
 * - [skip]: creative / spectator / dead / invulnerable - never damaged (the `return false` guards of
 *   `Player.hurtServer`, `Player.java:678-686`).
 * - [armor]: `getArmorValue()` = `Mth.floor(getAttributeValue(Attributes.ARMOR))` (`LivingEntity.java:1847-1849`).
 * - [toughness]: `(float) getAttributeValue(Attributes.ARMOR_TOUGHNESS)` (`LivingEntity.java:1880`).
 * - [resistanceAmplifier]: `getEffect(MobEffects.RESISTANCE).getAmplifier()` (`LivingEntity.java:1891`), `-1` when
 *   the effect is absent.
 * - [protectionPoints]: client-side replica of `EnchantmentHelper.getDamageProtection` (`LivingEntity.java:1913`),
 *   see `EnchantProtection`.
 * - [absorption]: `getAbsorptionAmount()` (`LivingEntity.java:3325-3327`).
 * - [health]: current health, compared by [DamageResult.isLethalFor].
 * - [blocking]: `null` when not blocking (or the block delay has not elapsed).
 * - [invulnerableTime]: `Entity.invulnerableTime` (`Entity.java:261`), already compensated for latency by the caller.
 * - [lastHurt]: ledger estimate of `LivingEntity.lastHurt` (`LivingEntity.java:237`); `null` = unknown (treated as 0).
 * - [difficulty]: `level.getDifficulty()` (`Player.java:689-697`).
 */
data class VictimState(
    val feet: Vec3,
    val box: AABB,
    val isPlayer: Boolean,
    val skip: Boolean,
    val armor: Int,
    val toughness: Float,
    val resistanceAmplifier: Int,
    val protectionPoints: Float,
    val absorption: Float,
    val health: Float,
    val blocking: BlockingState?,
    val invulnerableTime: Int,
    val lastHurt: Float?,
    val difficulty: Difficulty,
)

/**
 * Per-stage output of [VictimPipeline.apply]; every field is the value vanilla holds at that point of
 * `Player.hurtServer` -> `LivingEntity.hurtServer` -> `actuallyHurt`.
 *
 * - [exposure]: the `getSeenPercent` input; [raw]: `getEntityDamageAmount`.
 * - [afterDifficulty]: after the `Player.hurtServer` scaling (`Player.java:688-700`); unchanged for non-players.
 * - [afterBlocking]: `damage -= damageBlocked` (`LivingEntity.java:1181`) - what vanilla stores in `lastHurt` when the
 *   hit lands (`LivingEntity.java:1203,1206`). When [iFrameGated] it is still reported although vanilla returns early
 *   and leaves `lastHurt` untouched (`LivingEntity.java:1198-1199`).
 * - [iFrameGated]: `invulnerableTime > 10 && damage <= lastHurt` - nothing is dealt (`LivingEntity.java:1197-1199`).
 * - [dealt]: the argument of `actuallyHurt`: `damage - lastHurt` inside the window, else `damage`
 *   (`LivingEntity.java:1202,1208`).
 * - [afterArmor]: `getDamageAfterArmorAbsorb`; [afterMagic]: `getDamageAfterMagicAbsorb` (resistance + protection).
 * - [absorbed] / [healthLoss]: the absorption split of `actuallyHurt` (`Player.java:748-758`,
 *   `LivingEntity.java:1931-1940`).
 */
data class DamageResult(
    val exposure: Float,
    val raw: Float,
    val afterDifficulty: Float,
    val afterBlocking: Float,
    val iFrameGated: Boolean,
    val dealt: Float,
    val afterArmor: Float,
    val afterMagic: Float,
    val absorbed: Float,
    val healthLoss: Float,
) {

    /** Damage that reaches absorption + health, i.e. [afterMagic]. */
    val effective: Float get() = afterMagic

    /** `setHealth(getHealth() - healthLoss)` (`Player.java:758`, `LivingEntity.java:1940`) would reach zero. */
    fun isLethalFor(victim: VictimState): Boolean = healthLoss >= victim.health

    companion object {
        /** All zeros, [iFrameGated] = false. */
        val ZERO = DamageResult(
            exposure = 0f,
            raw = 0f,
            afterDifficulty = 0f,
            afterBlocking = 0f,
            iFrameGated = false,
            dealt = 0f,
            afterArmor = 0f,
            afterMagic = 0f,
            absorbed = 0f,
            healthLoss = 0f,
        )
    }
}
