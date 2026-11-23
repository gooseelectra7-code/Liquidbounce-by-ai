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

package net.ccbluex.liquidbounce.utils.aiming.point.features

import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.aiming.point.PointInsideBox
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.point.PointTrackerContext
import net.ccbluex.liquidbounce.utils.aiming.point.features.learned.AimLearningStorage
import net.ccbluex.liquidbounce.utils.aiming.point.features.learned.BucketKey
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

/**
 * Bias the resolved aim point toward cells inside the target's bbox where past attacks landed.
 *
 * Read-only — does not write to storage; recording is owned by `LearnedAimRecorder`
 * (a `ModuleDebugRecorder` mode). Sampling and recording are split so users can record without
 * biasing (warm-up), or sample without recording (frozen).
 *
 * When bucket samples are below [minSamples], passes through unchanged so geometry still drives aim
 * until learning warms up.
 *
 * Reads situation from [PointTrackerContext] rather than the tracker's per-instance fields, so the
 * same model also serves consumers that aren't inside a tracker's value-group tree.
 */
internal class PointProcessorLearned(
    tracker: PointTracker
) : PointProcessor(tracker, "Learned", false) {

    private val exploration by float("Exploration", 0.15f, 0.0f..1.0f)
    private val minSamples by int("MinSamples", 3, 0..50)
    private val intraCellJitter by float("IntraCellJitter", 0.5f, 0.0f..1.0f)
    private val onlyLivingTargets by boolean("OnlyLivingTargets", true)

    init {
        AimLearningStorage.ensureLoaded()
    }

    override fun process(point: PointInsideBox): PointInsideBox {
        val entity = PointTrackerContext.lastEntity ?: return point
        val eyes = PointTrackerContext.lastEyes ?: return point
        if (onlyLivingTargets && entity !is LivingEntity) return point

        val key = BucketKey.forSituation(entity, eyes)
        val count = AimLearningStorage.bucketSize(key)
        debugParameter("LearnedBucketSize") { count }
        if (count < minSamples) return point

        val rng = ThreadLocalRandom.current()
        if (rng.nextFloat() < exploration) return point

        val cell = AimLearningStorage.sampleCell(key, rng) ?: return point
        val newPos = cellToVec3(cell, point.box, rng)
        return PointInsideBox(newPos, point.box)
    }

    private fun cellToVec3(cell: Triple<Int, Int, Int>, box: AABB, rng: ThreadLocalRandom): Vec3 {
        val gridRes = AimLearningStorage.gridResolution
        val jx = (rng.nextFloat() - 0.5f) * intraCellJitter
        val jy = (rng.nextFloat() - 0.5f) * intraCellJitter
        val jz = (rng.nextFloat() - 0.5f) * intraCellJitter
        val nx = ((cell.first + 0.5f + jx) / gridRes).coerceIn(0f, 1f).toDouble()
        val ny = ((cell.second + 0.5f + jy) / gridRes).coerceIn(0f, 1f).toDouble()
        val nz = ((cell.third + 0.5f + jz) / gridRes).coerceIn(0f, 1f).toDouble()
        return Vec3(
            box.minX + nx * (box.maxX - box.minX),
            box.minY + ny * (box.maxY - box.minY),
            box.minZ + nz * (box.maxZ - box.minZ)
        )
    }
}
