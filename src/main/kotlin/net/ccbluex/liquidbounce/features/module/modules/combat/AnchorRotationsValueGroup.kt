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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.minecraft.world.entity.Entity
import kotlin.random.Random

/**
 * Rotation group for [ModuleAnchorOptimizer], adding the `RotationTiming` selector.
 *
 * The modes are named after [ModuleKillAura]'s, but the semantics had to be re-derived: KillAura's
 * timings hang off the weapon cooldown and its clicker plan, neither of which exists here. This
 * module's "strike tick" is instead the tick its own schedule allows the next interaction on
 * (`ModuleAnchorOptimizer.ticksUntilAction`), and every action is additionally gated on the rotation
 * ALREADY ON THE WIRE raytracing the anchor — so a mode that keeps the aim off the target simply
 * delays the interaction until the aim has landed, it can never fire on an aim the server has not
 * seen.
 */
object AnchorRotationsValueGroup : RotationsValueGroup(ModuleAnchorOptimizer) {

    val rotationTiming by enumChoice("RotationTiming", AnchorRotationTiming.NORMAL)

    /** Time budget for the GrimSnap walk-in, rounded up to whole ticks. */
    private val snapTime by int("SnapTime", 100, 50..400, "ms")

    internal val snapTicks: Int
        get() = ((snapTime + MS_PER_TICK - 1) / MS_PER_TICK).coerceAtLeast(1)

    private const val MS_PER_TICK = 50

    /** Forces the aim to release within a tick of the interaction instead of being held. */
    private const val SNAP_TICKS_UNTIL_RESET = 1

    /**
     * Per-tick step of the GrimSnap cycle: divides the remaining angle evenly over the ticks left
     * until the interaction is due, so the crosshair arrives ON the action tick and not before. The
     * per-tick jitter keeps consecutive approaches from sharing a step profile, and the GCD dither
     * in [Rotation.normalize] still applies on top.
     */
    private object GrimSnapStep : RotationProcessor {
        override fun process(
            rotationTarget: RotationTarget,
            currentRotation: Rotation,
            targetRotation: Rotation
        ): Rotation {
            val ticksLeft = ModuleAnchorOptimizer.ticksUntilAction().coerceIn(1, snapTicks)
            val angle = currentRotation.angleTo(targetRotation)
            if (angle < ANGLE_EPSILON) {
                return targetRotation
            }

            val step = angle / ticksLeft * (1.0f - STEP_JITTER + Random.nextFloat() * 2 * STEP_JITTER)
            return currentRotation.towardsLinear(targetRotation, step, step)
        }

        private const val ANGLE_EPSILON = 0.01f
        private const val STEP_JITTER = 0.15f
    }

    override fun toRotationTarget(
        rotation: Rotation,
        entity: Entity?,
        considerInventory: Boolean,
        whenReached: RestrictedSingleUseAction?
    ): RotationTarget {
        val base = super.toRotationTarget(rotation, entity, considerInventory, whenReached)

        return when (rotationTiming) {
            AnchorRotationTiming.SNAP -> base.withProcessors(base.processors)
            // The approach owns its motion: equal steps landing on the action tick. The group's
            // AngleSmooth is bypassed on purpose — it is shaped for a held aim, and holding is
            // exactly what this mode exists to avoid.
            AnchorRotationTiming.GRIM_SNAP -> base.withProcessors(listOf(GrimSnapStep))
            else -> base
        }
    }

    /** Copy of this target with [processors] swapped and the aim forced to release after a tick. */
    private fun RotationTarget.withProcessors(processors: List<RotationProcessor>) = RotationTarget(
        rotation = rotation,
        entity = entity,
        processors = processors,
        ticksUntilReset = SNAP_TICKS_UNTIL_RESET,
        resetThreshold = resetThreshold,
        considerInventory = considerInventory,
        movementCorrection = movementCorrection,
        whenReached = whenReached
    )

    enum class AnchorRotationTiming(override val tag: String) : Tagged {
        /** Hold the aim on the anchor the whole time one is selected. Fastest. */
        NORMAL("Normal"),

        /** Engage only when an interaction is due, release within a tick afterwards. */
        SNAP("Snap"),

        /**
         * No silent aim at all — a rotation packet carrying the aim is sent immediately before the
         * interaction and a second one restores the real rotation right after.
         *
         * This is the ONLY mode here that emits packets vanilla would not: three flying packets in
         * a tick where vanilla sends one. On modern Grim that is not a mere signature but an
         * instant detection — since 1.21.2 every flying pairs with CLIENT_TICK_END and TickTimer
         * (NOT experimental, setback on the first violation) counts flyings per tick, flagging
         * twice per use. The 1.17 duplicate-position exemption that once made extra flyings cheap
         * is hard-disabled for 1.21+ clients, so Timer additionally books +100 ms of balance per
         * use, and BadPacketsV / PacketOrderO (experimental) flag the unchanged-position packets
         * on top. Kept only for anticheats that do not track flying cadence. Not Grim-safe.
         */
        ON_TICK("OnTick"),

        /** Walk the aim in over `SnapTime` so it lands on the action tick, then release. */
        GRIM_SNAP("GrimSnap")
    }

}
