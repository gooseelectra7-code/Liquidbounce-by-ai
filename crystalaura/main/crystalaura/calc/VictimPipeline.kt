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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc

import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * Server-side victim damage pipeline of a crystal explosion, replicated stage by stage:
 * `Player.hurtServer` (`Player.java:676-705`) -> `LivingEntity.hurtServer` (`LivingEntity.java:1162-1211`) ->
 * `actuallyHurt` (`Player.java:743-766`, `LivingEntity.java:1927-1945`) with `getDamageAfterArmorAbsorb`
 * (`LivingEntity.java:1877-1884`) and `getDamageAfterMagicAbsorb` (`LivingEntity.java:1886-1925`).
 *
 * The damage source is `damageSources().explosion(crystal, attacker)` (`EndCrystal.java:97`) =
 * `DamageTypes.PLAYER_EXPLOSION` when both entities are known (`DamageSources.java:270-272`), registered with
 * `DamageScaling.ALWAYS` (`DamageTypes.java:106`) so `scalesWithDifficulty()` is true (`DamageSource.java:97-103`).
 * Tag membership of `player_explosion` (merged jar, `data/minecraft/tags/damage_type/<tag>.json`):
 * in `is_explosion`; not
 * in `bypasses_armor`, `bypasses_effects`, `bypasses_resistance`, `bypasses_enchantments`,
 * `bypasses_invulnerability`, `damages_helmet`, `is_freezing`; `bypasses_cooldown` has no data file at all (empty
 * tag). Hence every armor / effect / enchantment / cooldown branch below applies. `source.getWeaponItem()` is
 * `directEntity.getWeaponItem()` (`DamageSource.java:72-74`) = `null` for the crystal (`Entity.getWeaponItem`,
 * `Entity.java:3949-3951`), so the armor-effectiveness branch of `CombatRules.getDamageAfterAbsorb`
 * (`CombatRules.java:22-28`) never runs.
 *
 * Deliberate deviations: none in arithmetic. The side effects vanilla performs (storing `lastHurt` and
 * `invulnerableTime`, item durability, statistics, knockback) are not modelled; the ledger owns the i-frame
 * bookkeeping.
 */
object VictimPipeline {

    /**
     * `Player.hurtServer` -> `LivingEntity.hurtServer` -> `Player.actuallyHurt`, exact order and arithmetic:
     * skip -> difficulty (players only) -> clamp `< 0` -> shield (`BlocksAttacks` arc) -> i-frames (when
     * [respectIFrames] and `invulnerableTime > 10`: gate / delta against `lastHurt ?: 0f`) -> armor (`CombatRules`) ->
     * resistance -> protection cap -> absorption.
     *
     * [sourcePosition] is the crystal position: `DamageSource.getSourcePosition()` (`DamageSource.java:110-116`) falls
     * back to `directEntity.position()` and the crystal is the direct entity (`EndCrystal.java:97`).
     *
     * Early exits: [VictimState.skip] mirrors the `return false` guards of `Player.hurtServer` (`Player.java:678-686`).
     * `damage == 0.0F` after scaling mirrors `return damage == 0.0F ? false : super.hurtServer(...)`
     * (`Player.java:702`), which fires before `lastHurt` / `invulnerableTime` are touched. For non-players a zero
     * [raw] can only come from the `dist > 1.0` skip of `ServerExplosion.hurtEntities` (`ServerExplosion.java:183`; the
     * formula otherwise yields `>= 1`), where `hurtServer` is never called - so the zero result is exact for them too.
     *
     * Branches of `LivingEntity.hurtServer` that are dead for `player_explosion` and therefore omitted: `IS_FREEZING`
     * (`:1183-1185`), `DAMAGES_HELMET` (`:1187-1190`) and the NaN / infinity guard (`:1192-1194`, inputs are finite).
     *
     * I-frames (`LivingEntity.java:1196-1211`; `invulnerableTime` is an `int`, `Entity.java:261`, compared against
     * `10.0F`; `lastHurt` is a `float`, `LivingEntity.java:237`):
     * ```java
     * if (this.invulnerableTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
     *     if (damage <= this.lastHurt) return false;
     *     this.actuallyHurt(level, source, damage - this.lastHurt);
     *     this.lastHurt = damage;
     * } else {
     *     this.lastHurt = damage;
     *     this.invulnerableTime = 20;
     *     this.actuallyHurt(level, source, damage);
     * }
     * ```
     *
     * Absorption (`Player.java:748-758`, identical in `LivingEntity.java:1931-1940`):
     * `float rest = Math.max(dmg - this.getAbsorptionAmount(), 0.0F); float absorbedDamage = dmg - rest;
     * ... this.setHealth(this.getHealth() - rest);`.
     */
    fun apply(
        victim: VictimState,
        raw: Float,
        exposure: Float,
        sourcePosition: Vec3,
        respectIFrames: Boolean,
    ): DamageResult {
        if (victim.skip) return DamageResult.ZERO.copy(exposure = exposure, raw = raw)
        val afterDifficulty = scaleByDifficulty(raw, victim)
        if (afterDifficulty == 0f) return DamageResult.ZERO.copy(exposure = exposure, raw = raw)

        // LivingEntity.java:1175-1177 `if (damage < 0.0F) damage = 0.0F;`
        val clamped = if (afterDifficulty < 0f) 0f else afterDifficulty
        // LivingEntity.java:1180-1181 `float damageBlocked = this.applyItemBlocking(...); damage -= damageBlocked;`
        val afterBlocking = clamped - blockedDamage(victim.blocking, clamped, sourcePosition)

        val inWindow = respectIFrames && victim.invulnerableTime > 10
        val lastHurt = victim.lastHurt ?: 0f
        if (inWindow && afterBlocking <= lastHurt) {
            return DamageResult(
                exposure = exposure,
                raw = raw,
                afterDifficulty = afterDifficulty,
                afterBlocking = afterBlocking,
                iFrameGated = true,
                dealt = 0f,
                afterArmor = 0f,
                afterMagic = 0f,
                absorbed = 0f,
                healthLoss = 0f,
            )
        }
        val dealt = if (inWindow) afterBlocking - lastHurt else afterBlocking

        val afterArmor = damageAfterArmorAbsorb(victim, dealt)
        val afterMagic = damageAfterMagicAbsorb(victim, afterArmor)
        val healthLoss = max(afterMagic - victim.absorption, 0.0f)
        return DamageResult(
            exposure = exposure,
            raw = raw,
            afterDifficulty = afterDifficulty,
            afterBlocking = afterBlocking,
            iFrameGated = false,
            dealt = dealt,
            afterArmor = afterArmor,
            afterMagic = afterMagic,
            absorbed = afterMagic - healthLoss,
            healthLoss = healthLoss,
        )
    }

    /**
     * `Player.hurtServer` (`Player.java:688-702`); players only - `LivingEntity.hurtServer` has no difficulty step:
     * ```java
     * if (source.scalesWithDifficulty()) {
     *     if (level.getDifficulty() == Difficulty.PEACEFUL) damage = 0.0F;
     *     if (level.getDifficulty() == Difficulty.EASY) damage = Math.min(damage / 2.0F + 1.0F, damage);
     *     if (level.getDifficulty() == Difficulty.HARD) damage = damage * 3.0F / 2.0F;
     * }
     * return damage == 0.0F ? false : super.hurtServer(level, source, damage);
     * ```
     * `kotlin.math.min(Float, Float)` is `java.lang.Math.min`.
     */
    private fun scaleByDifficulty(damage: Float, victim: VictimState): Float {
        if (!victim.isPlayer) return damage
        return when (victim.difficulty) {
            Difficulty.PEACEFUL -> 0.0f
            Difficulty.EASY -> min(damage / 2.0f + 1.0f, damage)
            Difficulty.NORMAL -> damage
            Difficulty.HARD -> damage * 3.0f / 2.0f
        }
    }

    /**
     * `LivingEntity.applyItemBlocking` (`LivingEntity.java:1285-1322`) + `BlocksAttacks.resolveBlockedDamage`
     * (`BlocksAttacks.java:114-122`):
     * ```java
     * if (damage <= 0.0F) return 0.0F;
     * ItemStack blockingWith = this.getItemBlockingWith();
     * if (blockingWith == null) return 0.0F;
     * BlocksAttacks blocksAttacks = blockingWith.get(DataComponents.BLOCKS_ATTACKS);
     * if (blocksAttacks != null
     *     && !blocksAttacks.bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)) {
     *     if (source.getDirectEntity() instanceof AbstractArrow abstractArrow
     *         && abstractArrow.getPierceLevel() > 0) return 0.0F;
     *     Vec3 sourcePosition = source.getSourcePosition();
     *     double angle;
     *     if (sourcePosition != null) {
     *         Vec3 viewVector = this.calculateViewVector(0.0F, this.getYHeadRot());
     *         Vec3 vectorTo = sourcePosition.subtract(this.position());
     *         vectorTo = new Vec3(vectorTo.x, 0.0, vectorTo.z).normalize();
     *         angle = Math.acos(vectorTo.dot(viewVector));
     *     } else {
     *         angle = (float) Math.PI;
     *     }
     *     float damageBlocked = blocksAttacks.resolveBlockedDamage(source, damage, angle);
     *     ...
     *     return damageBlocked;
     * } else {
     *     return 0.0F;
     * }
     * ```
     * `resolveBlockedDamage`: `float blockedDamage = 0.0F; for (DamageReduction reduction : this.damageReductions)
     * { blockedDamage += reduction.resolve(source, dealtDamage, angle); } return Mth.clamp(blockedDamage, 0.0F,
     * dealtDamage);`
     *
     * The crystal is never an `AbstractArrow`, and `getSourcePosition()` is never null for it, so the `(float) Math.PI`
     * fallback is not modelled. `Vec3.normalize()` (`Vec3.java:83-86`) returns `ZERO` for a length `< 1.0E-5F`, i.e. a
     * source straight above / below gives `acos(0.0)`. `kotlin.math.acos` is `java.lang.Math.acos`.
     */
    private fun blockedDamage(blocking: BlockingState?, damage: Float, sourcePosition: Vec3): Float {
        if (blocking == null || damage <= 0.0f || blocking.bypassed) return 0.0f
        val vectorTo = sourcePosition.subtract(blocking.position)
        val flat = Vec3(vectorTo.x, 0.0, vectorTo.z).normalize()
        val angle = acos(flat.dot(blocking.viewVector))
        var blocked = 0.0f
        for (reduction in blocking.reductions) {
            blocked += resolveReduction(reduction, damage, angle)
        }
        return Mth.clamp(blocked, 0.0f, damage)
    }

    /**
     * `BlocksAttacks.DamageReduction.resolve` (`BlocksAttacks.java:146-154`):
     * ```java
     * if (angle > (float) (Math.PI / 180.0) * this.horizontalBlockingAngle) return 0.0F;
     * return this.type.isPresent() && !this.type.get().contains(source.typeHolder())
     *     ? 0.0F
     *     : Mth.clamp(this.base + this.factor * dealtDamage, 0.0F, dealtDamage);
     * ```
     * The right-hand side of the angle test is a `float * float` product that Java promotes to double for the
     * comparison, hence the explicit `.toDouble()`. [BlockReduction.typeMatches] carries the pre-resolved type test.
     */
    private fun resolveReduction(reduction: BlockReduction, dealtDamage: Float, angle: Double): Float {
        val maxAngle = (Math.PI / 180.0).toFloat() * reduction.horizontalBlockingAngle
        if (angle > maxAngle.toDouble()) return 0.0f
        if (!reduction.typeMatches) return 0.0f
        return Mth.clamp(reduction.base + reduction.factor * dealtDamage, 0.0f, dealtDamage)
    }

    /**
     * `LivingEntity.getDamageAfterArmorAbsorb` (`LivingEntity.java:1877-1884`):
     * `damage = CombatRules.getDamageAfterAbsorb(this, damage, damageSource, this.getArmorValue(),
     * (float)this.getAttributeValue(Attributes.ARMOR_TOUGHNESS));` - the `int` armor value is widened to the
     * `float totalArmor` parameter, hence `victim.armor.toFloat()`.
     *
     * `CombatRules.getDamageAfterAbsorb` (`CombatRules.java:16-32`):
     * ```java
     * float toughness = 2.0F + armorToughness / 4.0F;
     * float realArmor = Mth.clamp(totalArmor - damage / toughness, totalArmor * 0.2F, 20.0F);
     * float armorFraction = realArmor / 25.0F;
     * ItemStack weaponItem = source.getWeaponItem();   // null for a crystal -> modifiedArmorFraction = armorFraction
     * float damageMultiplier = 1.0F - modifiedArmorFraction;
     * return damage * damageMultiplier;
     * ```
     */
    private fun damageAfterArmorAbsorb(victim: VictimState, damage: Float): Float {
        val totalArmor = victim.armor.toFloat()
        val toughness = 2.0f + victim.toughness / 4.0f
        val realArmor = Mth.clamp(totalArmor - damage / toughness, totalArmor * 0.2f, 20.0f)
        val armorFraction = realArmor / 25.0f
        val damageMultiplier = 1.0f - armorFraction
        return damage * damageMultiplier
    }

    /**
     * `LivingEntity.getDamageAfterMagicAbsorb` (`LivingEntity.java:1886-1925`):
     * ```java
     * if (damageSource.is(DamageTypeTags.BYPASSES_EFFECTS)) return damage;                // not player_explosion
     * if (this.hasEffect(MobEffects.RESISTANCE) && !damageSource.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
     *     int absorbValue = (this.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1) * 5;
     *     int absorb = 25 - absorbValue;
     *     float v = damage * absorb;
     *     damage = Math.max(v / 25.0F, 0.0F);
     * }
     * if (damage <= 0.0F) return 0.0F;
     * if (damageSource.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) return damage;            // not player_explosion
     * float enchantmentArmor = EnchantmentHelper.getDamageProtection(serverLevel, this, damageSource); // server only
     * if (enchantmentArmor > 0.0F) damage = CombatRules.getDamageAfterMagicAbsorb(damage, enchantmentArmor);
     * return damage;
     * ```
     * `CombatRules.getDamageAfterMagicAbsorb` (`CombatRules.java:34-37`):
     * `float realArmor = Mth.clamp(totalMagicArmor, 0.0F, 20.0F); return damage * (1.0F - realArmor / 25.0F);`.
     * [VictimState.resistanceAmplifier] `< 0` means no Resistance effect; [VictimState.protectionPoints] replaces the
     * server-only `getDamageProtection`. `float * int` promotes the `int` to float, exactly as `Float.times(Int)` does.
     */
    private fun damageAfterMagicAbsorb(victim: VictimState, afterArmor: Float): Float {
        var damage = afterArmor
        if (victim.resistanceAmplifier >= 0) {
            val absorbValue = (victim.resistanceAmplifier + 1) * 5
            val absorb = 25 - absorbValue
            val v = damage * absorb
            damage = max(v / 25.0f, 0.0f)
        }
        if (damage <= 0.0f) return 0.0f
        if (victim.protectionPoints > 0.0f) {
            val realArmor = Mth.clamp(victim.protectionPoints, 0.0f, 20.0f)
            damage *= 1.0f - realArmor / 25.0f
        }
        return damage
    }
}
