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
package net.ccbluex.liquidbounce.features.module.modules.world.scaffold.techniques.normal

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerAfterJumpEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.techniques.ScaffoldNormalTechnique
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.ccbluex.liquidbounce.utils.entity.moving

/**
 * Telly feature
 *
 * This is based on the telly technique and means that the player will jump when moving.
 * That allows for a faster scaffold.
 * Depending on the SameY setting, we might scaffold upwards.
 *
 * @see ModuleScaffold
 */
object ScaffoldTellyFeature : ToggleableValueGroup(ScaffoldNormalTechnique, "Telly", false) {

    val doNotAim: Boolean
        get() = player.airTicks <= effectiveStraightTicks &&
                ticksUntilJump >= jumpTicks &&
                !(ModuleScaffold.isTowering && aimOnTower)

    /** New val to determine if the player is telly bridging */
    val isTellyBridging: Boolean
        get() = ticksUntilJump >= jumpTicks && player.moving && enabled

    private var ticksUntilJump = 0

    private val resetModeValue = enumChoice("ResetMode", Mode.RESET)
    val resetMode by resetModeValue

    /** Hidden under [Mode.RANDOMIZED], which draws the forward time per jump instead. */
    private val straightTicksValue = int("Straight", 0, 0..5, "ticks")
    private val straightTicks by straightTicksValue

    /** [straightTicks] is hidden under [Mode.RANDOMIZED], so its fallback (other RotationTimings) runs with 0. */
    private val effectiveStraightTicks: Int
        get() = if (resetMode == Mode.RANDOMIZED) 0 else straightTicks
    private val jumpTicksOpt by intRange("Jump", 0..0, 0..10, "ticks")
    internal val aimOnTower by boolean("AimOnTower", true)
    private var jumpTicks = jumpTicksOpt.random()

    /**
     * [Mode.RANDOMIZED] is in charge this tick. It drives only `RotationTiming = Normal` (the other timings aim from
     * `ModuleScaffold`'s tick handler, outside the per-tick rotation request this mode is built on) with a movement
     * correction its flight model holds for ([ScaffoldTellyRandomizer.supportsMovementCorrection]); anything else falls
     * back to [Mode.RESET].
     */
    val randomizes: Boolean
        get() = running && resetMode == Mode.RANDOMIZED &&
            ModuleScaffold.ScaffoldRotationValueGroup.rotationTiming ==
            ModuleScaffold.ScaffoldRotationValueGroup.RotationTimingMode.NORMAL &&
            ScaffoldTellyRandomizer.supportsMovementCorrection

    init {
        tree(ScaffoldTellyRandomizer)

        // Hooked here rather than on the declaration so the GUI order stays ResetMode first: by now every value the
        // hook touches exists. It also fires on a config load (`Value.deserializeFrom` ends in `set`).
        resetModeValue.onChanged { syncRandomizerVisibility(it) }
        // A change hook does not cover the initial state.
        syncRandomizerVisibility(resetMode)
    }

    private fun syncRandomizerVisibility(mode: Mode) {
        straightTicksValue.notAnOption(mode == Mode.RANDOMIZED)
        ScaffoldTellyRandomizer.notAnOption(mode != Mode.RANDOMIZED)
    }

    @Suppress("unused")
    private val gameHandler = handler<GameTickEvent> {
        if (player.onGround()) {
            ticksUntilJump++
        }
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (!player.moving || ModuleScaffold.blockCount <= 0 || !player.onGround()) {
            return@handler
        }

        val isStraight = RotationManager.currentRotation == null || effectiveStraightTicks == 0

        when {
            // The sprint boost of a jump follows the silent yaw, so wait until we face forward again.
            randomizes -> if (ScaffoldTellyRandomizer.facesForward && ticksUntilJump >= jumpTicks) event.jump = true
            resetMode == Mode.REVERSE -> event.jump = true
            else -> if (isStraight && ticksUntilJump >= jumpTicks) event.jump = true
        }
    }

    @Suppress("unused")
    private val afterJumpHandler = handler<PlayerAfterJumpEvent> {
        ticksUntilJump = 0
        jumpTicks = jumpTicksOpt.random()
    }

    enum class Mode(override val tag: String) : Tagged {
        REVERSE("Reverse"),
        RESET("Reset"),

        /** Per-jump randomised rhythm with derived bounds, see [ScaffoldTellyRandomizer]. */
        RANDOMIZED("Randomized")
    }

}
