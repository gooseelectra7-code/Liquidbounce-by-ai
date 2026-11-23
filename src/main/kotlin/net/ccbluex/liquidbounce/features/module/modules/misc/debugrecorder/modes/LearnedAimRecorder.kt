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

package net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.ModuleDebugRecorder
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.aiming.point.PointTrackerContext
import net.ccbluex.liquidbounce.utils.aiming.point.features.learned.AimLearningStorage
import net.ccbluex.liquidbounce.utils.aiming.point.features.learned.BucketKey
import net.ccbluex.liquidbounce.utils.client.FloatValueProvider
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

/**
 * Records the resolved aim point of each successful [AttackEntityEvent] into [AimLearningStorage],
 * but only during 1-on-1 combat: the local [TargetTracker] must see exactly one valid target in
 * range. This avoids polluting the learned model with noisy multi-target scenarios where the
 * attack target isn't necessarily the one the user was tracking with care.
 *
 * Lives inside [ModuleDebugRecorder] so recording is opt-in / dev-controlled — same UI as the
 * other dataset recorders. Each disable also writes the per-attack samples to a JSON log for
 * inspection (in addition to the persistent `aim-learning.json` histogram).
 */
object LearnedAimRecorder : ModuleDebugRecorder.DebugRecorderMode<LearnedAimSample>("LearnedAim") {

    private val targetTracker = tree(
        TargetTracker(
            defaultPriority = TargetPriority.DIRECTION,
            rangeValue = FloatValueProvider("Range", 8f, 4f..16f)
        )
    )

    private val gridResolution by int("GridResolution", AimLearningStorage.DEFAULT_GRID_RESOLUTION, 3..16)
        .onChanged { newRes ->
            AimLearningStorage.updateGridResolution(newRes)
        }
    private val sampleRate by float("SampleRate", 1.0f, 0.0f..1.0f)
    private val persistent by boolean("Persistent", true)

    private val sessionBucketCounts = Object2IntOpenHashMap<BucketKey>()
    private var sessionSkipped: Int = 0

    init {
        AimLearningStorage.ensureLoaded()
        AimLearningStorage.updateGridResolution(gridResolution)
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (targetTracker.targets().size != 1) return@handler

        val entity = event.entity as? LivingEntity ?: return@handler
        val resolved = PointTrackerContext.lastResolvedPoint ?: return@handler
        val eyes = PointTrackerContext.lastEyes ?: return@handler
        if (PointTrackerContext.lastEntity !== entity) return@handler

        // SampleRate dropout — avoids overfitting on long mono-bucket attack streaks.
        if (sampleRate < 1.0f && ThreadLocalRandom.current().nextFloat() >= sampleRate) {
            sessionSkipped++
            return@handler
        }

        val key = BucketKey.forSituation(entity, eyes)
        val (cx, cy, cz) = vec3ToCell(resolved.pos, resolved.box)
        AimLearningStorage.record(key, cx, cy, cz)
        sessionBucketCounts.addTo(key, 1)
        debugParameter("LearnedRecord") { "$key -> ($cx,$cy,$cz)" }

        if (persistent) {
            AimLearningStorage.save()
        }

        recordPacket(
            LearnedAimSample(
                time = System.currentTimeMillis(),
                distBin = key.distBin,
                hurtBin = key.hurtBin,
                cellX = cx,
                cellY = cy,
                cellZ = cz
            )
        )
    }

    override fun enable() {
        sessionBucketCounts.clear()
        sessionSkipped = 0
        super.enable()
    }

    override fun disable() {
        printSessionSummary()
        super.disable()
    }

    private fun printSessionSummary() {
        val total = sessionBucketCounts.values.sumOf { it }
        if (total == 0 && sessionSkipped == 0) return

        chat(regular("LearnedAim: recorded "), variable(total.toString()), regular(" sample(s)"))
        if (sessionSkipped > 0) {
            chat(regular("  skipped by sample-rate: "), variable(sessionSkipped.toString()))
        }

        val stats = AimLearningStorage.stats()
        chat(
            regular("  storage total: "),
            variable("${stats.bucketCount} buckets / ${stats.cellCount} cells"),
        )

        sessionBucketCounts.object2IntEntrySet()
            .sortedByDescending { it.intValue }
            .take(SUMMARY_TOP_BUCKETS)
            .forEach { entry ->
                val k = entry.key
                chat(regular("  bucket dist=${k.distBin} hurt=${k.hurtBin}: "), variable(entry.intValue.toString()))
            }
    }

    private fun vec3ToCell(pos: Vec3, box: AABB): Triple<Int, Int, Int> {
        val gridRes = AimLearningStorage.gridResolution
        val nx = axisFraction(pos.x, box.minX, box.maxX)
        val ny = axisFraction(pos.y, box.minY, box.maxY)
        val nz = axisFraction(pos.z, box.minZ, box.maxZ)
        return Triple(
            (nx * gridRes).toInt().coerceIn(0, gridRes - 1),
            (ny * gridRes).toInt().coerceIn(0, gridRes - 1),
            (nz * gridRes).toInt().coerceIn(0, gridRes - 1),
        )
    }

    private fun axisFraction(value: Double, min: Double, max: Double): Double {
        val span = max - min
        return if (span <= EPS) 0.5 else ((value - min) / span).coerceIn(0.0, 1.0)
    }

    private const val EPS = 1e-6
    private const val SUMMARY_TOP_BUCKETS = 5
}

data class LearnedAimSample(
    val time: Long,
    val distBin: Int,
    val hurtBin: Int,
    val cellX: Int,
    val cellY: Int,
    val cellZ: Int,
)
