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

/**
 * JSON storage shape for [AimLearningStorage]. Fields are flat & primitive so Gson roundtrip
 * is stable without registering custom adapters.
 */
internal data class StoredCell(val x: Int, val y: Int, val z: Int, val w: Float)

internal data class StoredBucket(val distBin: Int, val hurtBin: Int, val cells: List<StoredCell>)

internal data class StoredData(val gridResolution: Int, val buckets: List<StoredBucket>)

/**
 * Public stats snapshot from [AimLearningStorage]. Exposed for chat commands / UI inspection.
 */
data class StorageStats(
    val bucketCount: Int,
    val cellCount: Int,
    val totalWeight: Float,
    val gridResolution: Int,
)

data class BucketBreakdown(
    val key: BucketKey,
    val cellCount: Int,
    val totalWeight: Float,
)
