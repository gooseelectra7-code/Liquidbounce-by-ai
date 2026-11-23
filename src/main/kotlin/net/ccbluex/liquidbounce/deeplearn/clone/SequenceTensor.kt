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
package net.ccbluex.liquidbounce.deeplearn.clone

import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.FEATURES
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import kotlin.math.hypot

/**
 * One recorded sequence expanded into the arrays the trainer works on.
 *
 * The counts stored on disk are turned into degrees here, using the sequence's own GCD. That is
 * what lets one profile mix recordings made at different sensitivities: the *behaviour* is in the
 * angles, and the sensitivity is only ever a display scale on top of it.
 */
class SequenceTensor(sequence: AimSequence) : FeatureSchema.RowSource {

    override val rowCount = sequence.ticks.size

    override val featureRows = FloatArray(rowCount * FEATURES)

    override val actionRows = FloatArray(rowCount * 2)

    /** One-hot `still`, `yaw only`, `pitch only`, `both` movement state per tick. */
    val movementStateRows = FloatArray(rowCount * FeatureSchema.MOVE_STATES)

    /** `1` on ticks where the player swung. */
    val attackRows = FloatArray(rowCount)

    /** Angle to the target per tick, or `-1` where there was no target. */
    val targetAngle = FloatArray(rowCount)

    init {
        val gcd = sequence.gcd.toFloat()
        val row = FloatArray(FEATURES)

        for (i in 0 until rowCount) {
            val tick = sequence.ticks[i]
            val previous = if (i > 0) sequence.ticks[i - 1] else null

            FeatureSchema.fillFromGeometry(
                out = row,
                // At i == 0 there is no preceding tick, which is exactly the state inference
                // starts from after a reset, so zeros here are the truth rather than padding.
                prevDxDeg = previous?.let { it.dx * gcd } ?: 0f,
                prevDyDeg = previous?.let { it.dy * gcd } ?: 0f,
                ownYaw = tick.ownYaw,
                ownPitch = tick.ownPitch,
                moveForward = tick.moveForward,
                moveStrafe = tick.moveStrafe,
                attackedPrev = previous?.attacked == true,
                hasTarget = tick.hasTarget,
                targetYaw = tick.targetYaw,
                targetPitch = tick.targetPitch,
                distance = tick.distance,
                hadPrevTarget = previous?.hasTarget == true,
                prevTargetYaw = previous?.targetYaw ?: 0f,
                prevTargetPitch = previous?.targetPitch ?: 0f,
                prevDistance = previous?.distance ?: 0f,
                hurtTime = tick.hurtTime,
                onTarget = tick.onTarget,
                cooldown = tick.attackCooldown,
                sprinting = tick.sprinting,
                sneaking = tick.sneaking,
                onGround = tick.onGround,
                usingItem = tick.usingItem,
                knockedBack = previous?.receivedVelocity == true,
                tookDamage = previous?.tookDamage == true,
                targetChanged = previous != null && tick.targetId != previous.targetId,
                gcd = gcd,
                targetWidth = tick.targetWidth,
                targetHeight = tick.targetHeight
            )
            row.copyInto(featureRows, i * FEATURES)

            actionRows[i * 2] = tick.dx * gcd
            actionRows[i * 2 + 1] = tick.dy * gcd
            val movementState = when {
                tick.dx == 0 && tick.dy == 0 -> 0
                tick.dy == 0 -> 1
                tick.dx == 0 -> 2
                else -> 3
            }
            movementStateRows[i * FeatureSchema.MOVE_STATES + movementState] = 1f
            attackRows[i] = if (tick.attacked) 1f else 0f

            targetAngle[i] = if (tick.hasTarget) {
                hypot(
                    angleDifference(tick.targetYaw, tick.ownYaw),
                    angleDifference(tick.targetPitch, tick.ownPitch)
                )
            } else {
                -1f
            }
        }
    }
}
