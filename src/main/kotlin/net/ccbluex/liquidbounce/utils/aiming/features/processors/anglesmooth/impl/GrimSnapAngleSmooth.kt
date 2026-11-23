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
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import kotlin.math.ceil
import kotlin.random.Random

/**
 * GrimSnap, as a smoother usable by any module rather than a KillAura-only timing.
 *
 * The idea comes from KillAura's `RotationTiming.GrimSnap`, where it is bolted to the weapon
 * cooldown: the crosshair walks onto the target in equal per-tick steps sized to arrive exactly on
 * the tick the hit lands, instead of snapping and then sitting on the target. What makes that worth
 * having everywhere is not the cooldown coupling — it is the *motion profile*:
 *
 * - **The turn is spread over a fixed time**, not run at a fixed angular speed. A speed-limited
 *   smoother takes as long as the angle demands, so the time-to-target is a direct readout of how
 *   far the aim had to move. Here the duration is constant and the SPEED is whatever that requires,
 *   which is what a human flick actually looks like.
 * - **Every step is a fresh division of the REMAINING angle** by the ticks left, so a target that
 *   moves mid-turn is absorbed instead of producing a correction at the end.
 * - **[jitter] varies each step**, so two turns of the same size never share a step profile. A
 *   perfectly even division is its own signature.
 *
 * Unlike the KillAura version this cannot know when the module intends to act, so the budget is the
 * [snapTime] setting instead of "when the weapon is ready". Modules that do know — KillAura, and
 * AnchorOptimizer through its own `RotationTiming` — keep their cooldown-driven variants, which stay
 * more precise for those cases. This is the general one.
 */
class GrimSnapAngleSmooth(parent: ModeValueGroup<*>) : AngleSmooth("GrimSnap", parent) {

    /** How long the whole turn takes, whatever its size. */
    private val snapTime by int("SnapTime", 150, 50..600, "ms")

    /** Per-step variation, so an even division does not become the tell. */
    private val jitter by float("Jitter", 0.15f, 0.0f..0.5f)

    /**
     * Ticks spent on the current turn. Reset whenever the angle grows, which is how a NEW target is
     * detected without the smoother being told about one: a turn only ever shrinks the remaining
     * angle, so an increase means the aim was re-pointed somewhere else.
     */
    private var elapsed = 0
    private var lastAngle = 0f

    private val snapTicks: Int
        get() = ceil(snapTime / MS_PER_TICK.toDouble()).toInt().coerceAtLeast(1)

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val angle = currentRotation.angleTo(targetRotation)

        if (angle < ANGLE_EPSILON) {
            elapsed = 0
            lastAngle = 0f
            return targetRotation
        }

        // A turn only shrinks the gap; a gap that grew means we are aiming at something else now.
        if (angle > lastAngle + RETARGET_TOLERANCE) {
            elapsed = 0
        }
        lastAngle = angle

        val ticksLeft = (snapTicks - elapsed).coerceAtLeast(1)
        elapsed++

        val step = angle / ticksLeft * (1.0f - jitter + Random.nextFloat() * 2 * jitter)
        return currentRotation.towardsLinear(targetRotation, step, step)
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int =
        if (currentRotation.angleTo(targetRotation) < ANGLE_EPSILON) 0 else snapTicks

    private companion object {
        const val MS_PER_TICK = 50
        const val ANGLE_EPSILON = 0.01f

        /** Slack so ordinary target drift is not mistaken for a re-target. */
        const val RETARGET_TOLERANCE = 1.0f
    }

}
