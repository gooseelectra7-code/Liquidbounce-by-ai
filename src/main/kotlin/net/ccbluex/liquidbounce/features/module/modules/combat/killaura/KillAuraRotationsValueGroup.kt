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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.CloneAngleSmooth
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.minecraft.world.entity.Entity
import kotlin.random.Random

object KillAuraRotationsValueGroup : RotationsValueGroup(ModuleKillAura, combatSpecific = true) {

    val rotationTiming by enumChoice("RotationTiming", KillAuraRotationTiming.NORMAL)
    val aimThroughWalls by boolean("ThroughWalls", false)

    /**
     * Snap timings replace or bypass the selected processor, and OnTick overwrites its result with
     * an immediate raw rotation packet. Neither is the Clone that was selected. Keep its learned
     * trajectory and delayed action intact regardless of a stale timing value in the config.
     */
    val effectiveRotationTiming: KillAuraRotationTiming
        get() = if (activeAngleSmooth is CloneAngleSmooth) {
            KillAuraRotationTiming.NORMAL
        } else {
            rotationTiming
        }

    /**
     * Gate is open only on ticks where the clicker plans to click within the next tick. That
     * way the rotation target is set for a single tick (the AngleSmooth at 180/180 lands the
     * snap, anything slower takes a bit longer at the user's choice), then we stop feeding the
     * RotationManager — combined with the forced [SNAP_TICKS_UNTIL_RESET] this releases the
     * aim immediately so the cheek is "rotate only on hit" rather than "always aimed".
     */
    fun snapGateOpen(): Boolean = ModuleKillAura.clicker.willClickAt(1)

    private const val SNAP_TICKS_UNTIL_RESET = 1

    // --- GRIM_SNAP timing mode ---------------------------------------------------------------
    //
    // Grim-focused snap cycle. Between hits the aim rests on the player's own cursor — the
    // target is never held. When the weapon will be ready within [snapTime], the crosshair
    // walks onto the target in equal per-tick steps sized to LAND exactly on the strike tick;
    // the click itself is additionally gated on the rotation ON THE WIRE raytracing the
    // hitbox (see ModuleKillAura.attackTarget), so the hit can never fire off a client-side
    // plan the server has not seen. After the strike the override expires within one tick and
    // the same stepping walks the aim back to the cursor inside the same time budget.

    /** Time budget for the snap-in (and the release), rounded up to whole ticks. */
    private val snapTime by int("SnapTime", 100, 50..400, "ms")

    internal val snapTicks: Int
        get() = ((snapTime + MS_PER_TICK - 1) / MS_PER_TICK).coerceAtLeast(1)

    /**
     * Open only when the weapon will be ready within the snap window — driven by the item
     * cooldown itself (extrapolated `attackStrengthTicker`), not the CPS plan, so the snap
     * starts exactly [snapTicks] ticks before the sword recharges and the hit lands the tick
     * it is ready.
     */
    fun grimSnapGateOpen(): Boolean =
        ModuleKillAura.clicker.itemCooldown?.isCooldownPassed(snapTicks) != false

    private const val MS_PER_TICK = 50

    /**
     * Per-tick step of the GrimSnap cycle: divides the remaining angle evenly over the ticks
     * left until the weapon is ready, so the aim arrives on the strike tick and not before —
     * 100 ms means two flyings of roughly half the angle each. There is no grid or modulo
     * signature for Grim's AimProcessor in that (the GCD dither in [Rotation.normalize] stays
     * on top), and the per-tick jitter keeps consecutive snaps from sharing a step profile.
     * On the release path the same stepping walks back to the cursor within the same budget.
     */
    private object GrimSnapStep : RotationProcessor {
        override fun process(
            rotationTarget: RotationTarget,
            currentRotation: Rotation,
            targetRotation: Rotation
        ): Rotation {
            val ticksLeft = ticksUntilReady().coerceIn(1, snapTicks)
            val angle = currentRotation.angleTo(targetRotation)
            if (angle < ANGLE_EPSILON) {
                return targetRotation
            }

            val step = angle / ticksLeft * (1.0f - STEP_JITTER + Random.nextFloat() * 2 * STEP_JITTER)
            return currentRotation.towardsLinear(targetRotation, step, step)
        }

        private fun ticksUntilReady(): Int {
            val cooldown = ModuleKillAura.clicker.itemCooldown ?: return 1
            for (t in 0..snapTicks) {
                if (cooldown.isCooldownPassed(t)) {
                    return t
                }
            }
            return snapTicks
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

        return when (effectiveRotationTiming) {
            KillAuraRotationTiming.SNAP -> RotationTarget(
                base.rotation,
                base.entity,
                base.processors,
                // Force a 1-tick reset window so the rotation actually releases between hits.
                SNAP_TICKS_UNTIL_RESET,
                base.resetThreshold,
                base.considerInventory,
                base.movementCorrection,
                base.whenReached
            )
            KillAuraRotationTiming.GRIM_SNAP -> RotationTarget(
                base.rotation,
                base.entity,
                // The snap owns its motion: equal steps landing on the strike tick. The group's
                // AngleSmooth and humanizers are bypassed on purpose — they are shaped for held
                // aim, and holding is exactly what this mode exists to avoid.
                listOf(GrimSnapStep),
                SNAP_TICKS_UNTIL_RESET,
                base.resetThreshold,
                base.considerInventory,
                base.movementCorrection,
                base.whenReached
            )
            else -> base
        }
    }

    enum class KillAuraRotationTiming(override val tag: String) : Tagged {
        NORMAL("Normal"),
        SNAP("Snap"),
        ON_TICK("OnTick"),
        GRIM_SNAP("GrimSnap")
    }

}
