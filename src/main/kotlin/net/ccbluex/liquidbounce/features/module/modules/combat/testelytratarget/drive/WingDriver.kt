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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.drive

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.shortestYawDelta
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.asin
import kotlin.math.atan2

/**
 * A processor that does nothing.
 *
 * [RotationTarget] treats an empty processor list as "no smoothing was requested" and takes a
 * different path through the reset logic, so a mode that genuinely wants the commanded angle applied
 * verbatim still has to hand it one processor. This is that processor.
 */
internal object PassThroughRotation : RotationProcessor {
    override fun process(rotationTarget: RotationTarget, currentRotation: Rotation, targetRotation: Rotation) =
        targetRotation
}

/**
 * Points the wing.
 *
 * Under an elytra the rotation is not decoration, it is the control surface: vanilla computes glide
 * from the look direction, and `MixinLivingEntity` makes it read the rotation this client is sending
 * rather than the camera. So steering happens here and nowhere else, and writing `player.yRot`
 * directly would desynchronise the wing from what the server is being told.
 *
 * The request is re-issued every tick without exception. A visible-camera rotation only lives for
 * one tick by design, and letting the request lapse for even a single tick hands the rotation system
 * a reset, which reads in the air as the wing snapping back and then out again.
 */
object WingDriver {

    /**
     * Steers with a visible camera and the smoothing the user configured.
     *
     * The default priority sits deliberately *below* the aura. Whenever the aura has a rotation of
     * its own it means the target is in reach, and at that moment the aura's aim is strictly better
     * than ours: it is picking an exact point on the hitbox while we are only flying a heading. Since
     * that heading points at the target anyway, letting the aura win costs the flight nothing and
     * buys a hit. The instant the aura goes quiet, our request is already top of the queue.
     */
    fun steerVisible(
        module: ClientModule,
        rotations: RotationsValueGroup,
        rotation: Rotation,
        target: LivingEntity,
        priority: Priority,
    ) {
        RotationManager.setRotationTarget(
            plan = rotations.toRotationTarget(rotation, entity = target, considerInventory = false),
            priority = priority,
            provider = module,
        )
    }

    /**
     * Steers silently, leaving the camera where the player left it.
     *
     * For the HvH modes, where hits do not need the crosshair on anyone. Smoothing is skipped on
     * purpose: a smoother exists to make a turn look human, and nothing here is pretending to be
     * human, while the rate limit it imposes is a real handling penalty on the aircraft.
     */
    fun steerSilent(
        module: ClientModule,
        rotation: Rotation,
        target: LivingEntity,
        priority: Priority,
        correction: MovementCorrection = MovementCorrection.STRICT,
    ) {
        RotationManager.setRotationTarget(
            plan = RotationTarget(
                rotation = rotation,
                entity = target,
                processors = listOf(PassThroughRotation),
                ticksUntilReset = 1,
                resetThreshold = 1f,
                considerInventory = false,
                movementCorrection = correction,
            ),
            priority = priority,
            provider = module,
        )
    }

    /** The rotation that flies along [direction]. Pitch is positive downwards, as vanilla has it. */
    fun rotationFor(direction: Vec3): Rotation {
        val unit = direction.normalize()
        val yaw = Math.toDegrees(atan2(-unit.x, unit.z)).toFloat()
        val pitch = Math.toDegrees(-asin(unit.y.coerceIn(-1.0, 1.0))).toFloat()
        return Rotation(yaw, pitch)
    }

    /**
     * Pulls a command back to what the wing can reach within [turnTicks], given a budget of
     * [allowedTicks].
     *
     * Commanding an angle the smoother will take fifteen ticks to deliver means flying fifteen ticks
     * of a plan that was only ever correct on the tick it was made. Clamping keeps the command and
     * the aircraft on the same page.
     */
    fun clampToReachable(desired: Rotation, current: Rotation, turnTicks: Int, allowedTicks: Int): Rotation {
        if (turnTicks <= allowedTicks) {
            return desired
        }

        val fraction = allowedTicks.toFloat() / turnTicks.toFloat()
        val yaw = current.yaw + shortestYawDelta(current.yaw, desired.yaw) * fraction
        val pitch = current.pitch + (desired.pitch - current.pitch) * fraction
        return Rotation(yaw, pitch.coerceIn(-90f, 90f))
    }
}
