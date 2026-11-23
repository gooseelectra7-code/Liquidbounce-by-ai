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

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Pure arithmetic of an end-crystal explosion; no world access.
 *
 * An attacked crystal explodes with power 6 at its own position (`EndCrystal.hurtServer`, `EndCrystal.java:98`:
 * `level.explode(this, damageSource, null, this.getX(), this.getY(), this.getZ(), 6.0F, false, ...)`), which becomes
 * `ServerExplosion.radius` / `center` (`ServerLevel.java:1220` `Vec3 center = new Vec3(x, y, z);`,
 * `ServerExplosion.java:66-67`). Per entity, `ServerExplosion.hurtEntities` (`ServerExplosion.java:170-210`) does
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
 * The exposure argument (`getSeenPercent`) is computed by `Exposure`; this object only replicates the formula.
 * `kotlin.math.sqrt` is the same `java.lang.Math.sqrt` intrinsic vanilla calls, so results are bit-identical.
 */
object ExplosionMath {

    /** Explosion power of an end crystal: the `6.0F` passed to `level.explode` (`EndCrystal.java:98`). */
    const val POWER = 6.0f

    /** `radius * 2.0F` (`ServerExplosion.java:172`, `ExplosionDamageCalculator.java:30`) for [POWER]. */
    const val DOUBLE_RADIUS = 12.0f

    /**
     * `EntityType.END_CRYSTAL` is `.sized(2.0F, 2.0F)` (`EntityType.java:416-424`) and
     * `EntityDimensions.makeBoundingBox` (`EntityDimensions.java:19-23`) uses
     * `float w = this.width / 2.0F; float h = this.height;`.
     */
    private const val HALF_WIDTH = 1.0f
    private const val HEIGHT = 2.0f

    /**
     * Explosion center / crystal position for a crystal on top of [obsidian]: `(x + 0.5, y + 1, z + 0.5)`.
     *
     * `EndCrystalItem.useOn` (`EndCrystalItem.java:30-42`): `BlockPos above = pos.above(); ... double x = above.getX();
     * double y = above.getY(); double z = above.getZ(); ... new EndCrystal(level, x + 0.5, y, z + 0.5)`. The explosion
     * center is that entity position (`EndCrystal.java:98`, `ServerLevel.java:1220`).
     */
    fun crystalCenter(obsidian: BlockPos): Vec3 =
        Vec3(obsidian.x + 0.5, (obsidian.y + 1).toDouble(), obsidian.z + 0.5)

    /**
     * Hitbox of that crystal: `[x - 0.5, y + 1, z - 0.5] .. [x + 1.5, y + 3, z + 1.5]`.
     *
     * `EntityDimensions.makeBoundingBox(double x, double y, double z)` (`EntityDimensions.java:19-23`):
     * `float w = this.width / 2.0F; float h = this.height; return new AABB(x - w, y, z - w, x + w, y + h, z + w);`
     * with `width = height = 2.0F` (`EntityType.java:421`) and `(x, y, z)` = [crystalCenter]. The float half extents
     * are promoted to double exactly as in Java.
     */
    fun crystalBox(obsidian: BlockPos): AABB {
        val center = crystalCenter(obsidian)
        return AABB(
            center.x - HALF_WIDTH, center.y, center.z - HALF_WIDTH,
            center.x + HALF_WIDTH, center.y + HEIGHT, center.z + HALF_WIDTH,
        )
    }

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
     * at all (vanilla never calls `hurtServer` for it).
     *
     * `(1.0 - dist) * exposure` multiplies a double by the float exposure (promoted to double); `exposure == 0` gives
     * `pow == 0` and a damage of exactly `1.0F` - vanilla really deals 1 to a fully covered entity inside the radius.
     * The trailing `(float)` cast is the only narrowing. Range for an entity inside the radius: `[1, 85]`.
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
