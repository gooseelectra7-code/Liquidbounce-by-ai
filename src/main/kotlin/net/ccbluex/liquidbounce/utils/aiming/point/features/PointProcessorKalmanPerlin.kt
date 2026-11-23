/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.point.features

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.utils.aiming.point.PointInsideBox
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.point.PointTrackerContext
import net.ccbluex.liquidbounce.utils.math.pointAtProportion
import net.minecraft.world.phys.AABB
import java.util.concurrent.ThreadLocalRandom

/**
 * Optional point-space companion to Test2, independent of the selected angle smoother.
 * Runs after Sticky and before StayInHitbox, which retains its final correction priority.
 * Only changes the preferred point, not the box or the caller's ray/range/visibility gates.
 * KillAura's fan picker can replace this preference (ModuleKillAura.findRotation).
 * No direct packets, human-aim claim or live anticheat verdict.
 */
internal class PointProcessorKalmanPerlin(tracker: PointTracker) : PointProcessor(tracker, "KalmanPerlin", false) {
    private val edgeMarginValue = float("EdgeMargin", 0.05f, 0f..0.49f, "bbox")
    private val edgeMargin by edgeMarginValue

    private inner class Kalman : ToggleableValueGroup(this, "Kalman", true) {
        val processVariance by float("ProcessVariance", 0.5f, 0.01f..10f, "%^2")
        val measurementVariance by float("MeasurementVariance", 4f, 0.1f..25f, "%^2")
        val maxLag by float("MaxLag", 0.12f, 0f..0.4f, "bbox")
    }

    private inner class Perlin : ToggleableValueGroup(this, "Perlin", true) {
        val horizontalAmplitude by float("HorizontalAmplitude", 0.08f, 0f..0.3f, "bbox")
        val verticalAmplitude by float("VerticalAmplitude", 0.04f, 0f..0.3f, "bbox")
        val rate by float("Rate", 0.04f, 0.005f..0.25f, "cells/tick")
    }

    private val kalman = tree(Kalman())
    private val perlin = tree(Perlin())
    private val motion = KalmanPerlinPointMotion(ThreadLocalRandom.current().nextInt())

    init {
        kalman.enabledValue.onChanged { syncMarginVisibility() }
        perlin.enabledValue.onChanged { syncMarginVisibility() }
        syncMarginVisibility()
    }

    private fun syncMarginVisibility() = edgeMarginValue.notAnOption(!kalman.enabled && !perlin.enabled)

    override fun onDisabled() = motion.reset()
    override fun onEnabled() = motion.reset()

    override fun process(point: PointInsideBox): PointInsideBox {
        val entity = PointTrackerContext.lastEntity ?: return point.also { motion.reset() }
        if (!kalman.enabled && !perlin.enabled) return point.also { motion.reset() }
        val settings = KalmanPerlinPointSettings(
            kalman = kalman.enabled,
            processVariance = kalman.processVariance.toDouble(),
            measurementVariance = kalman.measurementVariance.toDouble(),
            maxLag = kalman.maxLag.toDouble(),
            perlin = perlin.enabled,
            horizontalAmplitude = perlin.horizontalAmplitude.toDouble(),
            verticalAmplitude = perlin.verticalAmplitude.toDouble(),
            rate = perlin.rate.toDouble(),
            edgeMargin = edgeMargin.toDouble(),
        )
        val proportions = motion.advance(player.tickCount, entity, point.proportions(), settings)
        return point.box.pointAt(proportions)
    }
}

internal fun PointInsideBox.proportions() = PointProportions(
    if (box.xsize > 0.0) ((pos.x - box.minX) / box.xsize).coerceIn(0.0, 1.0) else 0.5,
    if (box.ysize > 0.0) ((pos.y - box.minY) / box.ysize).coerceIn(0.0, 1.0) else 0.5,
    if (box.zsize > 0.0) ((pos.z - box.minZ) / box.zsize).coerceIn(0.0, 1.0) else 0.5,
)

internal fun AABB.pointAt(proportions: PointProportions) =
    PointInsideBox(pointAtProportion(proportions.x, proportions.y, proportions.z), this)
