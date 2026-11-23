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

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Pure arithmetic of a respawn-anchor explosion; no world access.
 *
 * A detonated anchor explodes with power 5 at the centre of its OWN cell. `RespawnAnchorBlock.explode`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:150-170`):
 *
 * ```java
 * level.removeBlock(pos, false);                                                      // :151
 * ...
 * Vec3 boomPos = pos.getCenter();                                                     // :168
 * level.explode(null, level.damageSources().badRespawnPointExplosion(boomPos),
 *     damageCalculator, boomPos, 5.0F, true, Level.ExplosionInteraction.BLOCK);        // :169
 * ```
 *
 * Two consequences the crystal model does not have:
 * - The anchor's own cell is ALREADY AIR when the exposure rays are cast (`:151` runs before `:169`), so a
 *   [ShapeSource] scoring a standing anchor must override that cell with `Shapes.empty()`.
 * - The centre is `(x + 0.5, y + 0.5, z + 0.5)`, i.e. INSIDE the block that was placed
 *   (`BlockPos.getCenter` -> `Vec3.atCenterOf`, `net/minecraft/world/phys/Vec3.java:53-55`), not one block above a
 *   pedestal the way an end crystal's is. That is the whole reason an anchor can be put at a victim's foot or head
 *   cell and reach damage a crystal cannot.
 *
 * The anonymous `ExplosionDamageCalculator` built at `:154-167` overrides ONLY `getBlockExplosionResistance`, so
 * ENTITY damage is the unmodified base implementation. Per entity, `ServerExplosion.hurtEntities`
 * (`net/minecraft/world/level/ServerExplosion.java:170-210`) does
 *
 * ```java
 * float doubleRadius = this.radius * 2.0F;                                          // :172
 * double dist = Math.sqrt(entity.distanceToSqr(this.center)) / doubleRadius;         // :182
 * if (!(dist > 1.0)) {                                                               // :183
 *     float exposure = ... getSeenPercent(this.center, entity);                       // :188
 *     if (shouldDamageEntity) {
 *         entity.hurtServer(this.level, this.damageSource,
 *             this.damageCalculator.getEntityDamageAmount(this, entity, exposure));   // :190
 *     }
 * ```
 *
 * and `ExplosionDamageCalculator.getEntityDamageAmount` (`ExplosionDamageCalculator.java:29-35`) is
 *
 * ```java
 * float doubleRadius = explosion.radius() * 2.0F;
 * Vec3 center = explosion.center();
 * double dist = Math.sqrt(entity.distanceToSqr(center)) / doubleRadius;
 * double pow = (1.0 - dist) * exposure;
 * return (float)((pow * pow + pow) / 2.0 * 7.0 * doubleRadius + 1.0);
 * ```
 *
 * The exposure argument (`getSeenPercent`) is computed by [Exposure]; this object only replicates the formula.
 * `kotlin.math.sqrt` is the same `java.lang.Math.sqrt` intrinsic vanilla calls, so results are bit-identical.
 */
object AnchorMath {

    /** Explosion power of a respawn anchor: the `5.0F` passed to `level.explode` (`RespawnAnchorBlock.java:169`). */
    const val POWER = 5.0f

    /** `radius * 2.0F` (`ServerExplosion.java:172`, `ExplosionDamageCalculator.java:30`) for [POWER]. */
    const val DOUBLE_RADIUS = 10.0f

    /**
     * Explosion centre of an anchor standing at [pos]: `(x + 0.5, y + 0.5, z + 0.5)`.
     *
     * `Vec3 boomPos = pos.getCenter()` (`RespawnAnchorBlock.java:168`), passed both as the damage source position and
     * as the explosion centre at `:169`. `BlockPos.getCenter()` is `Vec3.atCenterOf(this)`
     * (`net/minecraft/world/phys/Vec3.java:53-55`: `return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5,
     * pos.getZ() + 0.5)`), which is exactly what the Kotlin `pos.center` accessor calls.
     *
     * There is no companion hitbox function here on purpose: an anchor is a BLOCK, not an entity, so nothing in the
     * pipeline ever needs its bounding box the way the crystal's 2x2x2 `EntityType.END_CRYSTAL` box was needed.
     */
    fun anchorCenter(pos: BlockPos): Vec3 = pos.center

    /**
     * `Math.sqrt(entity.distanceToSqr(center)) / doubleRadius` (`ServerExplosion.java:182`,
     * `ExplosionDamageCalculator.java:32`) with [feet] standing in for the entity position.
     *
     * `Entity.distanceToSqr(Vec3)` (`Entity.java:1775-1780`) computes `xd = this.getX() - pos.x; ...;
     * return xd * xd + yd * yd + zd * zd` where `getX()` is `this.position.x` (`Entity.java:3681-3683`).
     * `Vec3.distanceToSqr(Vec3)` (`Vec3.java:131-136`) computes `xd = vec.x - this.x; ...`. The subtraction order is
     * reversed, which does not matter: `a - b` and `b - a` are exact negations of each other in IEEE 754 and their
     * squares are bit-identical, so `feet.distanceToSqr(center)` equals `entity.distanceToSqr(center)` whenever
     * `feet == entity.position()`. The float `doubleRadius` is promoted to double by Java's binary numeric promotion,
     * hence `DOUBLE_RADIUS.toDouble()`.
     */
    fun distanceFraction(feet: Vec3, center: Vec3): Double =
        sqrt(feet.distanceToSqr(center)) / DOUBLE_RADIUS.toDouble()

    /**
     * `getEntityDamageAmount` replica (`ExplosionDamageCalculator.java:29-35`) with the `dist > 1.0` skip of
     * `ServerExplosion.hurtEntities` (`ServerExplosion.java:183`) folded in: returns `0f` when the entity is not hurt
     * at all (vanilla never calls `hurtServer` for it). The cut-off is 10 blocks from the victim's FEET, half a
     * crystal's 12.
     *
     * `(1.0 - dist) * exposure` multiplies a double by the float exposure (promoted to double); `exposure == 0` gives
     * `pow == 0` and a damage of exactly `1.0F` - vanilla really deals 1 to a fully covered entity inside the radius.
     * The trailing `(float)` cast is the only narrowing. Range for an entity inside the radius: `[1, 71]` (the peak is
     * `(1 + 1) / 2 * 7 * 10 + 1`; the crystal's is 85).
     */
    fun rawDamage(feet: Vec3, center: Vec3, exposure: Float): Float {
        val dist = distanceFraction(feet, center)
        if (dist > 1.0) return 0f
        val pow = (1.0 - dist) * exposure.toDouble()
        return ((pow * pow + pow) / 2.0 * 7.0 * DOUBLE_RADIUS.toDouble() + 1.0).toFloat()
    }

    /**
     * [rawDamage] at full exposure. Every rounding step of the formula is monotone in `pow >= 0`, so this bounds
     * [rawDamage] for any exposure in `[0, 1]` without tolerance.
     */
    fun rawUpperBound(feet: Vec3, center: Vec3): Float = rawDamage(feet, center, 1f)
}
