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
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * Block-only line-of-sight test with vanilla's exact ray marching, without a level.
 *
 * [isBlocked] answers `level.clip(ClipContext(from, to, COLLIDER, Fluid.NONE, entity)).getType() != MISS` by
 * replicating `BlockGetter.traverseBlocks` (`net/minecraft/world/level/BlockGetter.java:115-172`) together with the
 * per-cell consumer of `BlockGetter.clip` (`BlockGetter.java:65-82`):
 *
 * - `from.equals(to)` → miss (`:116-117`; `Vec3.equals` compares the three components,
 *   `net/minecraft/world/phys/Vec3.java:196-206`).
 * - Both endpoints are moved with `Mth.lerp(-1.0E-7, a, b)` (`:119-124`): the marched segment is EXTENDED by
 *   1e-7 of its length beyond both ends. The first cell is `Mth.floor` of the extended start (`:125-127`).
 * - The consumer runs on the first cell (`:128-131`), then once after every axis step; the step is picked by the
 *   `tX < tY`, `tX < tZ`, `tY < tZ` comparisons in that order (`:146-161`), `tDelta*` is `Double.MAX_VALUE` for a
 *   zero component (`:139-141`) and the initial `t*` use `1.0 - Mth.frac` for a positive step and `Mth.frac`
 *   otherwise (`:142-144`); the loop runs while any `t* <= 1.0` (`:146`).
 * - Per cell (`:66-77` with `COLLIDER`, `net/minecraft/world/level/ClipContext.java:48-50, 57`): the shape is
 *   `state.getCollisionShape(level, pos, collisionContext)` and `blockShape.clip(from, to, pos)` runs with the
 *   ORIGINAL endpoints (`context.getFrom()/getTo()`, `:69-72`). `clipWithInteractionOverride` (`:85-97`) only
 *   rewrites the direction of a non-null result, and the `Fluid.NONE` fluid shape is `Shapes.empty()`
 *   (`ClipContext.java:52-54, 97`) whose clip is `null` (`net/minecraft/world/phys/shapes/VoxelShape.java:161-162`),
 *   so a cell hits exactly when `blockShape.clip(from, to, pos) != null`.
 *
 * Why "the march visited a full cube ⇒ hit" would be WRONG: the endpoint extension lets the march enter the cell
 * that contains `to` (and even one past it), but `VoxelShape.clip` → `AABB.clip` → `clipPoint`
 * (`VoxelShape.java:159-179`, `net/minecraft/world/phys/AABB.java:308-326, 371-397`) only accepts a face with
 * `0.0 < s && s < 1.0` along the ORIGINAL segment. An endpoint lying exactly on the face of a full cube yields
 * `s == 1.0`: that cell is visited and is NOT a hit. Hence every visited cell goes through the real
 * `VoxelShape.clip`; the only shortcut is vanilla's own `shape.isEmpty() → null` (`VoxelShape.java:161-162`).
 *
 * Deliberate deviations: none in the arithmetic. Only the boolean outcome is produced (no `BlockHitResult`), and
 * the shapes come from a [ShapeSource] instead of `BlockGetter.getBlockState`.
 */
object ClipCore {

    /** True iff a `COLLIDER` / `Fluid.NONE` clip from [from] to [to] over [source] would not be a `MISS`. */
    fun isBlocked(from: Vec3, to: Vec3, source: ShapeSource): Boolean =
        isBlocked(from, to, source, BlockPos.MutableBlockPos())

    /**
     * Same as [isBlocked] but marches with the caller's scratch [pos], so that batches of rays (the 45 exposure
     * samples of [Exposure.seenPercent]) allocate nothing per ray. Per step nothing is allocated except what
     * `VoxelShape.clip` itself allocates for a hit.
     */
    internal fun isBlocked(from: Vec3, to: Vec3, source: ShapeSource, pos: BlockPos.MutableBlockPos): Boolean {
        if (from == to) return false

        val toX = Mth.lerp(-1.0E-7, to.x, from.x)
        val toY = Mth.lerp(-1.0E-7, to.y, from.y)
        val toZ = Mth.lerp(-1.0E-7, to.z, from.z)
        val fromX = Mth.lerp(-1.0E-7, from.x, to.x)
        val fromY = Mth.lerp(-1.0E-7, from.y, to.y)
        val fromZ = Mth.lerp(-1.0E-7, from.z, to.z)
        var x = Mth.floor(fromX)
        var y = Mth.floor(fromY)
        var z = Mth.floor(fromZ)
        if (hits(from, to, source, pos.set(x, y, z))) return true

        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val signX = Mth.sign(dx)
        val signY = Mth.sign(dy)
        val signZ = Mth.sign(dz)
        val tDeltaX = tDelta(signX, dx)
        val tDeltaY = tDelta(signY, dy)
        val tDeltaZ = tDelta(signZ, dz)
        var tX = tStart(signX, tDeltaX, fromX)
        var tY = tStart(signY, tDeltaY, fromY)
        var tZ = tStart(signZ, tDeltaZ, fromZ)

        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    x += signX
                    tX += tDeltaX
                } else {
                    z += signZ
                    tZ += tDeltaZ
                }
            } else if (tY < tZ) {
                y += signY
                tY += tDeltaY
            } else {
                z += signZ
                tZ += tDeltaZ
            }

            if (hits(from, to, source, pos.set(x, y, z))) return true
        }

        return false
    }

    /** `sign == 0 ? Double.MAX_VALUE : sign / delta` (`BlockGetter.java:139-141`); `Int / Double` as in Java. */
    private fun tDelta(sign: Int, delta: Double): Double = if (sign == 0) Double.MAX_VALUE else sign / delta

    /** `tDelta * (sign > 0 ? 1.0 - Mth.frac(from) : Mth.frac(from))` (`BlockGetter.java:142-144`). */
    private fun tStart(sign: Int, tDelta: Double, from: Double): Double =
        tDelta * (if (sign > 0) 1.0 - Mth.frac(from) else Mth.frac(from))

    /**
     * The `COLLIDER` / `Fluid.NONE` cell consumer of `BlockGetter.clip` (`BlockGetter.java:66-77`) reduced to its
     * boolean outcome: `shape.isEmpty()` short-circuits like `VoxelShape.clip` itself (`VoxelShape.java:161-162`),
     * otherwise the real `VoxelShape.clip(from, to, pos)` with the ORIGINAL endpoints decides.
     */
    private fun hits(from: Vec3, to: Vec3, source: ShapeSource, pos: BlockPos.MutableBlockPos): Boolean {
        val shape = source.collisionShape(pos.x, pos.y, pos.z)
        return !shape.isEmpty && shape.clip(from, to, pos) != null
    }
}
