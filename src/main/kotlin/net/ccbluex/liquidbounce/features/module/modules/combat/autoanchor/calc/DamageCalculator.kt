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

import net.minecraft.world.phys.Vec3

/**
 * Damage one respawn-anchor explosion at [center] deals to a victim: the per-entity part of
 * `ServerExplosion.hurtEntities` (`net/minecraft/world/level/ServerExplosion.java:170-210`) followed by the victim's
 * `hurtServer` pipeline, assembled from the replicas in this package. No world access of its own — the world enters
 * through the [ShapeSource] and the frozen [VictimState].
 *
 * Vanilla order per entity (`ServerExplosion.java:182-190`):
 * ```java
 * double dist = Math.sqrt(entity.distanceToSqr(this.center)) / doubleRadius;                 // :182
 * if (!(dist > 1.0)) {                                                                        // :183
 *     float exposure = ... getSeenPercent(this.center, entity);                               // :188
 *     if (shouldDamageEntity) {
 *         entity.hurtServer(this.level, this.damageSource,
 *             this.damageCalculator.getEntityDamageAmount(this, entity, exposure));           // :190
 *     }
 * ```
 * mapped onto [AnchorMath.rawUpperBound] (the `dist > 1.0` skip), [Exposure.seenPercent] (`getSeenPercent`,
 * `ServerExplosion.java:78-109`), [AnchorMath.rawDamage] (`getEntityDamageAmount`,
 * `ExplosionDamageCalculator.java:29-35`) and [VictimPipeline.apply] (`Player.hurtServer` → `LivingEntity.hurtServer`
 * → `actuallyHurt`).
 *
 * `sourcePosition = center` is EXACT here, not an inference: `RespawnAnchorBlock.explode` computes
 * `Vec3 boomPos = pos.getCenter()` once (`net/minecraft/world/level/block/RespawnAnchorBlock.java:168`) and passes
 * the SAME vector both to `damageSources().badRespawnPointExplosion(boomPos)` and as the explosion centre (`:169`);
 * `badRespawnPointExplosion` stores it as the source's `damageSourcePosition`
 * (`net/minecraft/world/damagesource/DamageSources.java:278-280`), which `DamageSource.getSourcePosition()` returns
 * verbatim when non-null (`DamageSource.java:109-116`). So the shield arc is measured from the blast centre.
 *
 * Deliberate deviations: the exposure rays are not cast for a victim that can never be hurt ([VictimState.skip];
 * vanilla casts them and only then fails in `Player.hurtServer`, `Player.java:678-686`) nor for one outside the
 * radius (vanilla does not cast them either, `:183`). Both cases return [DamageResult.ZERO], whose `exposure` field
 * is therefore `0f` rather than the never-computed value. [respectIFrames] is forwarded unchanged to
 * [VictimPipeline.apply]; one instance per configuration value, shared by every victim.
 */
class DamageCalculator(private val respectIFrames: Boolean) {

    /**
     * Full evaluation for an anchor detonating at [center]: exposure of [VictimState.box] through [source], raw damage
     * at [VictimState.feet], then the victim pipeline with `sourcePosition = center`.
     *
     * The caller is responsible for building [source] so that the anchor's own cell reports `Shapes.empty()`:
     * `level.removeBlock(pos, false)` runs BEFORE `level.explode(...)` (`RespawnAnchorBlock.java:151` vs `:169`), so
     * for vanilla the cell is air by the time `getSeenPercent` marches. That is what `OverrideShapeSource` is for.
     *
     * The raycast is skipped (result [DamageResult.ZERO]) when [VictimState.skip] or when
     * [AnchorMath.rawUpperBound] is `0f`. The latter happens only through the `dist > 1.0` branch (the formula
     * otherwise yields at least `1.0F`); there [AnchorMath.rawDamage] is `0f` for every exposure and
     * [VictimPipeline.apply] returns [DamageResult.ZERO] for a raw of `0f` (players: `damage == 0.0F → false`,
     * `Player.java:702`; others: `hurtServer` is never called, `ServerExplosion.java:183`), so the shortcut changes
     * nothing but the `exposure` field.
     */
    fun damage(center: Vec3, victim: VictimState, source: ShapeSource): DamageResult {
        if (victim.skip || AnchorMath.rawUpperBound(victim.feet, center) == 0f) return DamageResult.ZERO

        val exposure = Exposure.seenPercent(center, victim.box, source)
        val raw = AnchorMath.rawDamage(victim.feet, center, exposure)
        return VictimPipeline.apply(
            victim = victim,
            raw = raw,
            exposure = exposure,
            sourcePosition = center,
            respectIFrames = respectIFrames,
        )
    }

    /**
     * [DamageResult.effective] of [damage] assuming full exposure (`1f`), without casting a single ray.
     *
     * This bounds `damage(center, victim, source).effective` from above for every [ShapeSource] because every stage
     * from the raw damage to [DamageResult.effective] is monotone non-decreasing in its input:
     * - `getEntityDamageAmount` (`ExplosionDamageCalculator.java:29-35`): `(pow * pow + pow) / 2 * 7 * 10 + 1` with
     *   `pow = (1 - dist) * exposure >= 0` grows with the exposure (see [AnchorMath.rawUpperBound]);
     * - difficulty (`Player.java:688-700`): `0`, `min(d / 2 + 1, d)`, `d`, `d * 3 / 2`;
     * - shield (`LivingEntity.java:1181`, `BlocksAttacks.java:146-154`): `d - clamp(base + factor * d, 0, d)`, which
     *   is non-decreasing whenever `factor <= 1` or `base >= 0` — true for every vanilla shield, whose only reduction
     *   is `DamageReduction(90.0F, Optional.empty(), 0.0F, 1.0F)` (`BlocksAttacks.java:43`); a data-pack reduction
     *   with `base < 0 && factor > 1` would break the bound (documented assumption);
     * - i-frames (`LivingEntity.java:1197-1202`): `0` while `d <= lastHurt`, then `d - lastHurt`;
     * - armor (`CombatRules.java:16-32`): `d * (1 - clamp(armor - d / toughness, 0.2 * armor, 20) / 25)` — a larger
     *   `d` shrinks the clamped armor, so both factors of the product are non-negative and non-decreasing;
     * - resistance (`LivingEntity.java:1890-1895`) and protection (`CombatRules.java:34-37`): linear with a
     *   non-negative slope (`max(v / 25, 0)` is constant `0` for amplifier `>= 4`).
     *
     * Because `healthLoss <= effective` ([VictimPipeline.apply]: `rest = max(dmg - absorption, 0)`), the value also
     * bounds [DamageResult.healthLoss], i.e. a lethal-override check can be pruned with it as well. A victim that is
     * [VictimState.skip] or outside the radius yields `0f`, matching [damage].
     */
    fun upperBound(center: Vec3, victim: VictimState): Float =
        VictimPipeline.apply(
            victim = victim,
            raw = AnchorMath.rawUpperBound(victim.feet, center),
            exposure = 1f,
            sourcePosition = center,
            respectIFrames = respectIFrames,
        ).effective
}
