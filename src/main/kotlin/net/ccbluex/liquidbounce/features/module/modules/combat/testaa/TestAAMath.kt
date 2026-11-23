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
package net.ccbluex.liquidbounce.features.module.modules.combat.testaa

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.CombatRules
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The damage model of donut's "Anchor Aura", ported 1:1 from the decompiled `c/IiIiIIiiii` (`DamageUtil`) and the
 * ray helpers it calls in `c/iiIiiIIIii` (`SocialCommand`). Paths below are relative to
 * `C:\Users\caves\Documents\donut\work\src_named\c\`.
 *
 * It is NOT vanilla's model and is deliberately not replaced by `utils/entity/EntityExtensions.getDamageFromExplosion`:
 * - no difficulty scaling at all (`DamageUtil.java` has no `Difficulty` reference) - i.e. Normal is assumed;
 * - armour is `getArmorValue()` + the `ARMOR_TOUGHNESS` attribute, read off the entity as the client sees it;
 * - the entity's box is translated by a predicted OFFSET before any of it is evaluated;
 * - exposure takes an explicit AIR cell, an explicit SOLID cell and an AIR set (the anchor cell, a planned cover block
 *   and the "Blast Through" cells), and reads COLLISION shapes without an entity context.
 */
@Suppress("MagicNumber")
internal object TestAAMath {

    /** `RespawnAnchorBlock.explode` always explodes with power 5; every anchor call site passes `5.0f`. */
    const val ANCHOR_POWER = 5.0f

    private val ARMOR_SLOTS = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /**
     * `DamageUtil.IIiIiIIIiIIIi(Vec3 offset, float exposure, long, LivingEntity, float power, Vec3 center)`,
     * `DamageUtil.java:533-568`: vanilla's `getEntityDamageAmount` curve, measured from the entity's FEET position plus
     * [offset] (`entity.position().add(offset)`, `:559`), then [reduce].
     */
    fun damage(offset: Vec3, exposure: Float, entity: LivingEntity, power: Float, center: Vec3): Float {
        val diameter = power.toDouble() * 2.0
        val distance = sqrt(entity.position().add(offset).distanceToSqr(center)) / diameter
        if (distance > 1.0) {
            return 0.0f
        }

        val impact = (1.0 - distance) * exposure.toDouble()
        val raw = ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0).toFloat()
        return reduce(raw, entity)
    }

    /**
     * `DamageUtil.IIiIiIIIIiiIi(float, LivingEntity, long)`, `DamageUtil.java:319-359`: armour + toughness, then
     * Resistance, then the EPF sum (Protection x1, Blast Protection x2 per level over the four armour slots) through
     * `CombatRules.getDamageAfterMagicAbsorb`.
     */
    fun reduce(damage: Float, entity: LivingEntity): Float {
        val armor = entity.armorValue.toFloat()
        val toughness = entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS).toFloat()
        val effective = Mth.clamp(armor - damage / (2.0f + toughness / 4.0f), armor * 0.2f, 20.0f)
        var result = damage * (1.0f - effective / 25.0f)

        val resistance = entity.getEffect(MobEffects.RESISTANCE)
        if (resistance != null) {
            result = max(result * (25 - (resistance.amplifier + 1) * 5).toFloat() / 25.0f, 0.0f)
        }
        if (result <= 0.0f) {
            return 0.0f
        }

        var protection = 0
        for (slot in ARMOR_SLOTS) {
            for (entry in EnchantmentHelper.getEnchantmentsForCrafting(entity.getItemBySlot(slot)).entrySet()) {
                if (entry.key.`is`(Enchantments.PROTECTION)) {
                    protection += entry.intValue
                }
                if (entry.key.`is`(Enchantments.BLAST_PROTECTION)) {
                    protection += entry.intValue * 2
                }
            }
        }
        return CombatRules.getDamageAfterMagicAbsorb(result, protection.toFloat())
    }

    /**
     * `DamageUtil.IIiIiIIIIiiII`, `DamageUtil.java:250-317` (reached through the `$default` bridge at `:153-176`):
     * vanilla's seen-percent grid over `entity.boundingBox.move(offset)`, where a sample counts when its ray to
     * [center] is NOT blocked by [rayBlocked].
     */
    fun exposure(
        level: Level,
        center: Vec3,
        entity: Entity,
        airPos: BlockPos?,
        solidPos: BlockPos? = null,
        airSet: Set<BlockPos>? = null,
        offset: Vec3 = Vec3.ZERO,
    ): Float {
        val box = entity.boundingBox.move(offset)
        val stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0)
        val stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0)
        val stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0)
        if (stepX < 0.0 || stepY < 0.0 || stepZ < 0.0) {
            return 0.0f
        }

        val offsetX = (1.0 - floor(1.0 / stepX) * stepX) / 2.0
        val offsetZ = (1.0 - floor(1.0 / stepZ) * stepZ) / 2.0

        var visible = 0
        var total = 0
        var x = 0.0
        while (x <= 1.0) {
            var y = 0.0
            while (y <= 1.0) {
                var z = 0.0
                while (z <= 1.0) {
                    val sample = Vec3(
                        Mth.lerp(x, box.minX, box.maxX) + offsetX,
                        Mth.lerp(y, box.minY, box.maxY),
                        Mth.lerp(z, box.minZ, box.maxZ) + offsetZ
                    )
                    if (!rayBlocked(level, sample, center, airPos, solidPos, airSet)) {
                        visible++
                    }
                    total++
                    z += stepZ
                }
                y += stepY
            }
            x += stepX
        }
        return visible.toFloat() / total.toFloat()
    }

    /**
     * `SocialCommand.iiiIiIIiIiIII`, `SocialCommand.java:7266-7352`: the same voxel walk as vanilla
     * `BlockGetter.traverseBlocks` (same -1e-7 nudges, start cell first, same DDA - compared line by line), with
     * donut's per-cell test [blocksRay] (`SocialCommand.java:69641-69690`), which clips against the ORIGINAL
     * endpoints like vanilla does.
     */
    @Suppress("LongParameterList")
    private fun rayBlocked(
        level: Level,
        sample: Vec3,
        center: Vec3,
        airPos: BlockPos?,
        solidPos: BlockPos?,
        airSet: Set<BlockPos>?,
    ): Boolean = traverse(sample, center) { pos ->
        if (blocksRay(level, pos, sample, center, airPos, solidPos, airSet)) true else null
    } == true

    /**
     * Vanilla `BlockGetter.traverseBlocks` (`BlockGetter.java:115-172`), line for line: [visit] runs on every cell the
     * segment [from]-[to] crosses, start cell first, and the first non-null answer ends the walk. Written out because
     * that method's `T` is non-null in 26.1.2 and so cannot express "keep walking" from Kotlin. The cell handed to
     * [visit] is a mutable cursor - copy it before keeping it.
     */
    @Suppress("CognitiveComplexMethod")
    inline fun <T : Any> traverse(from: Vec3, to: Vec3, visit: (BlockPos) -> T?): T? {
        if (from == to) {
            return null
        }

        val toX = Mth.lerp(-1.0E-7, to.x, from.x)
        val toY = Mth.lerp(-1.0E-7, to.y, from.y)
        val toZ = Mth.lerp(-1.0E-7, to.z, from.z)
        val fromX = Mth.lerp(-1.0E-7, from.x, to.x)
        val fromY = Mth.lerp(-1.0E-7, from.y, to.y)
        val fromZ = Mth.lerp(-1.0E-7, from.z, to.z)

        var x = Mth.floor(fromX)
        var y = Mth.floor(fromY)
        var z = Mth.floor(fromZ)
        val cursor = BlockPos.MutableBlockPos(x, y, z)
        visit(cursor)?.let { return it }

        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val signX = Mth.sign(dx)
        val signY = Mth.sign(dy)
        val signZ = Mth.sign(dz)
        val deltaX = if (signX == 0) Double.MAX_VALUE else signX / dx
        val deltaY = if (signY == 0) Double.MAX_VALUE else signY / dy
        val deltaZ = if (signZ == 0) Double.MAX_VALUE else signZ / dz
        var tX = deltaX * if (signX > 0) 1.0 - Mth.frac(fromX) else Mth.frac(fromX)
        var tY = deltaY * if (signY > 0) 1.0 - Mth.frac(fromY) else Mth.frac(fromY)
        var tZ = deltaZ * if (signZ > 0) 1.0 - Mth.frac(fromZ) else Mth.frac(fromZ)

        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    x += signX
                    tX += deltaX
                } else {
                    z += signZ
                    tZ += deltaZ
                }
            } else if (tY < tZ) {
                y += signY
                tY += deltaY
            } else {
                z += signZ
                tZ += deltaZ
            }

            visit(cursor.set(x, y, z))?.let { return it }
        }
        return null
    }

    /**
     * Per-cell test, `SocialCommand.java:69641-69690`. Precedence is load-bearing and kept: the AIR cell and the AIR
     * set are checked BEFORE the solid override, and ordinary cells use their collision shape with no entity context.
     */
    @Suppress("LongParameterList")
    private fun blocksRay(
        level: Level,
        pos: BlockPos,
        from: Vec3,
        to: Vec3,
        airPos: BlockPos?,
        solidPos: BlockPos?,
        airSet: Set<BlockPos>?,
    ): Boolean {
        if (pos == airPos) {
            return false
        }
        if (airSet != null && airSet.contains(pos)) {
            return false
        }
        if (pos == solidPos) {
            return Shapes.block().clip(from, to, pos) != null
        }

        val state = level.getBlockState(pos)
        if (state.isAir) {
            return false
        }
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) {
            return false
        }
        return shape.clip(from, to, pos) != null
    }

    /**
     * `SocialCommand.IIiIIIIiIIiiiI`, `SocialCommand.java:73649-73704`: the damage an anchor at [anchorPos] (centre
     * [center]) deals to [target] standing at `position + offset`. The unobstructed upper bound is tested first and
     * the exposure grid is only walked when it is positive; the anchor's own cell is AIR (`RespawnAnchorBlock.explode`
     * removes it before exploding).
     */
    fun anchorDamage(
        level: Level,
        anchorPos: BlockPos,
        center: Vec3,
        target: LivingEntity,
        offset: Vec3,
        airSet: Set<BlockPos>?,
    ): Float {
        if (damage(offset, 1.0f, target, ANCHOR_POWER, center) <= 0.0f) {
            return 0.0f
        }

        val exposure = exposure(level, center, target, anchorPos, null, airSet, offset)
        return damage(offset, exposure, target, ANCHOR_POWER, center)
    }

    /**
     * "Blast Through" damage: `SocialCommand.IIIiiiIiIiiIii` and its two twins (`:68379-68439`, `:19168-19228`,
     * `:67937-67997`; the opaque switch at `AnchorAura.java:805` only ever picks between identical copies) - the
     * [anchorDamage] with every cell of [blastCells] treated as air.
     */
    fun blastThroughDamage(level: Level, anchorPos: BlockPos, center: Vec3, target: LivingEntity, offset: Vec3): Float {
        val cells = blastCells(level, center, target.boundingBox.move(offset), ANCHOR_POWER, anchorPos)
        return anchorDamage(level, anchorPos, center, target, offset, cells)
    }

    /**
     * `DamageUtil.IIiIiIIIIiiiI`, `DamageUtil.java:361-442` (and the bytecode for the normalisation, which CFR prints
     * inside the short-circuit but which runs unconditionally): vanilla's explosion ray march at the MINIMUM intensity
     * roll (`power * 0.7`, not `* (0.7 + 0.6 * random)`), collecting every cell a ray still has intensity in - the
     * blocks the blast would break AND the air it crosses. Rays whose direction cannot reach [targetBox] inflated by 1
     * are skipped, unless the centre lies inside that box. The anchor's own cell costs nothing.
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth", "ComplexCondition")
    fun blastCells(level: Level, center: Vec3, targetBox: AABB?, power: Float, anchorPos: BlockPos): Set<BlockPos> {
        val cells = HashSet<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()
        val filter = targetBox?.inflate(1.0)?.takeUnless { it.contains(center) }
        val reach = power.toDouble() * 0.7 / 0.22500001 * 0.3

        for (i in 0 until 16) {
            for (j in 0 until 16) {
                rays@ for (k in 0 until 16) {
                    if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) {
                        continue
                    }

                    var dx = (i.toFloat() / 15.0f * 2.0f).toDouble() - 1.0
                    var dy = (j.toFloat() / 15.0f * 2.0f).toDouble() - 1.0
                    var dz = (k.toFloat() / 15.0f * 2.0f).toDouble() - 1.0
                    val length = sqrt(dx * dx + dy * dy + dz * dz)
                    dx /= length
                    dy /= length
                    dz /= length

                    if (filter != null &&
                        filter.clip(center, center.add(dx * reach, dy * reach, dz * reach)).isEmpty
                    ) {
                        continue
                    }

                    var x = center.x
                    var y = center.y
                    var z = center.z
                    var intensity = power * 0.7f
                    while (intensity > 0.0f) {
                        cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))
                        if (!level.isInWorldBounds(cursor)) {
                            continue@rays
                        }

                        val state = level.getBlockState(cursor)
                        val fluid = level.getFluidState(cursor)
                        if (!(cursor == anchorPos || state.isAir && fluid.isEmpty)) {
                            intensity -= (max(state.block.explosionResistance, fluid.explosionResistance) + 0.3f) * 0.3f
                        }
                        if (intensity > 0.0f) {
                            cells.add(cursor.immutable())
                        }

                        x += dx * 0.3f.toDouble()
                        y += dy * 0.3f.toDouble()
                        z += dz * 0.3f.toDouble()
                        intensity -= 0.22500001f
                    }
                }
            }
        }
        return cells
    }

    /**
     * `SocialCommand.iiiIiiiIiIiiI`, `SocialCommand.java:11338-11363`: squared distance from [point] to the segment
     * [a]-[b] (Cover keeps candidates within 3 blocks of the line from our box centre to the anchor).
     */
    fun distanceToSegmentSqr(point: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b.subtract(a)
        val lengthSqr = ab.lengthSqr()
        if (lengthSqr < 1.0E-7) {
            return point.distanceToSqr(a)
        }

        val t = (point.subtract(a).dot(ab) / lengthSqr).coerceIn(0.0, 1.0)
        return point.distanceToSqr(a.add(ab.scale(t)))
    }
}
