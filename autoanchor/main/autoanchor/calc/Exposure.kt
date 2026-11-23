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
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Explosion exposure of a bounding box: `ServerExplosion.getSeenPercent(center, entity)`
 * (`net/minecraft/world/level/ServerExplosion.java:78-109`) for an arbitrary [AABB] over a [ShapeSource].
 *
 * Bit-for-bit replica of the sampling: steps `1.0 / (size * 2.0 + 1.0)` per axis (`:80-82`), x/z offsets
 * `(1.0 - Math.floor(1.0 / step) * step) / 2.0` (`:83-84`, there is NO y offset), samples from three nested
 * `for (xx = 0.0; xx <= 1.0; xx += xs)` loops that accumulate the step in doubles (`:89-91`), positions via
 * `Mth.lerp(xx, min, max)` (`:92-95`), one `COLLIDER` / `Fluid.NONE` clip FROM the sample TO [center] whose
 * `MISS` counts as a hit (`:96-98`), and finally `(float) hits / count` (`:105`). A negative step returns `0.0F`
 * (`:85, 106-108`; unreachable through the `AABB` constructor, which orders min/max, `AABB.java:22-29`).
 *
 * Deviation: the per-ray clip is [ClipCore.isBlocked] over [source] instead of `entity.level().clip(...)` with the
 * entity's own collision context; the caller chooses the context when building the [ShapeSource].
 */
object Exposure {

    /** Fraction (0..1) of the sample rays from [box] to [center] that reach it without touching a collision shape. */
    fun seenPercent(center: Vec3, box: AABB, source: ShapeSource): Float {
        var hits = 0
        var count = 0
        val pos = BlockPos.MutableBlockPos()
        val valid = forEachSample(box) { x, y, z ->
            if (!ClipCore.isBlocked(Vec3(x, y, z), center, source, pos)) hits++
            count++
        }
        return if (valid) hits.toFloat() / count else 0.0f
    }

    /** Number of rays [seenPercent] casts for [box] — 45 for a 0.6 x 1.8 x 0.6 player box. */
    internal fun sampleCount(box: AABB): Int {
        var count = 0
        val valid = forEachSample(box) { _, _, _ -> count++ }
        return if (valid) count else 0
    }

    /**
     * The sampling loops of `getSeenPercent` (`ServerExplosion.java:80-103`), calling [sample] with each ray
     * origin. Returns false without sampling when a step is negative — the negation of vanilla's
     * `!(xs < 0.0) && !(ys < 0.0) && !(zs < 0.0)` guard (`:85`), equivalent for NaN as well.
     */
    private inline fun forEachSample(box: AABB, sample: (x: Double, y: Double, z: Double) -> Unit): Boolean {
        val xs = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0)
        val ys = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0)
        val zs = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0)
        val xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0
        val zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return false

        var xx = 0.0
        while (xx <= 1.0) {
            var yy = 0.0
            while (yy <= 1.0) {
                var zz = 0.0
                while (zz <= 1.0) {
                    val x = Mth.lerp(xx, box.minX, box.maxX)
                    val y = Mth.lerp(yy, box.minY, box.maxY)
                    val z = Mth.lerp(zz, box.minZ, box.maxZ)
                    sample(x + xOffset, y, z + zOffset)
                    zz += zs
                }
                yy += ys
            }
            xx += xs
        }
        return true
    }
}
