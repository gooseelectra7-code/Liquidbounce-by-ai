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
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor

/**
 * An [AngleSmooth]'er, but as choice
 */
abstract class AngleSmooth(
    name: String,
    override val parent: ModeValueGroup<*>,
    aliases: List<String> = emptyList()
) : Mode(name, aliases), RotationProcessor {
    abstract fun calculateTicks(
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Int

    /**
     * Whether this smoother wants a *fanned* (randomized) aim anchor instead of the single
     * deterministic best point. A smoother that moves the crosshair convincingly makes a fixed
     * per-click anchor its weakest tell — the click target is identical every time for the same
     * geometry. Returning true makes KillAura pick a random spot from the few closest valid points
     * each click, killing that determinism while keeping misses low (picks stay visible, in-range,
     * near the current rotation).
     */
    open val prefersFannedAimPoint: Boolean get() = false

    /**
     * Whether the humanization processors (Fail, ShortStop, Jitter) should be left out of the
     * chain. True only for smoothers whose output is already a complete, self-consistent movement —
     * layering hand-made deviations on top of a learned distribution replaces the thing that made
     * it convincing with a signature of its own.
     */
    open val suppressesPostProcessors: Boolean get() = false

    /**
     * Whether the point this smoother aims at comes from
     * [net.ccbluex.liquidbounce.utils.aiming.RotationTarget.entity] rather than from the target
     * rotation it is handed.
     *
     * True only where the smoother has its own idea of what to look at and overrides the caller's.
     * That makes it impossible to drive over anything but a live entity — hand it recorded
     * geometry and it will aim at whatever entity happens to be in the target instead.
     */
    open val derivesTargetFromEntity: Boolean get() = false

    /** Tick-cached live state cannot be advanced by an offline replay on the same instance. */
    open val requiresLiveTick: Boolean get() = false

    /** The manager must keep this smoother's bounded return instead of applying ResetThreshold. */
    open val preservesResetSteps: Boolean get() = false
}
