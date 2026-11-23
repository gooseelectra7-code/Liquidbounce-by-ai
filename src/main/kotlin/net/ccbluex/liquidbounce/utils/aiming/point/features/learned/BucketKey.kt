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
package net.ccbluex.liquidbounce.utils.aiming.point.features.learned

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/**
 * Coarse-grained combat situation key for aim-point learning.
 *
 * Two axes:
 * - distBin: quantized eye-to-entity distance in metres
 * - hurtBin: quantized target hurtTime (post-hit invulnerability window)
 *
 * Each bucket accumulates its own grid of successful aim offsets, so the learner can produce
 * different aim biases depending on how close the target is and whether it just took damage.
 */
@JvmRecord
data class BucketKey(val distBin: Int, val hurtBin: Int) {
    companion object {
        const val DIST_BIN_SIZE = 1.5
        const val MAX_DIST_BIN = 6
        const val HURT_BIN_SIZE = 3
        const val MAX_HURT_BIN = 3

        fun forSituation(entity: Entity, eyes: Vec3): BucketKey {
            val dist = eyes.distanceTo(entity.eyePosition)
            val distBin = (dist / DIST_BIN_SIZE).toInt().coerceIn(0, MAX_DIST_BIN)
            val hurt = (entity as? LivingEntity)?.hurtTime ?: 0
            val hurtBin = (hurt / HURT_BIN_SIZE).coerceIn(0, MAX_HURT_BIN)
            return BucketKey(distBin, hurtBin)
        }
    }
}
