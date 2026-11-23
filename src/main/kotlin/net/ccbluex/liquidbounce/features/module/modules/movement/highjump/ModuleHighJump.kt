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
package net.ccbluex.liquidbounce.features.module.modules.movement.highjump

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.sequenceHandler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories

/**
 * HighJump module
 *
 * Allows you to jump higher.
 */
object ModuleHighJump : ClientModule("HighJump", ModuleCategories.MOVEMENT) {

    private val modes = choices(
        "Mode", Vanilla, arrayOf(
            Vanilla, Vulcan, Grim
        )
    ).apply { tagBy(this) }
    private val motion by float("Motion", 0.8f, 0.2f..10f)

    private object Vanilla : Mode("Vanilla") {

        override val parent: ModeValueGroup<Mode>
            get() = modes

        @Suppress("unused")
        val jumpEvent = sequenceHandler<PlayerJumpEvent> {
            it.motion = motion
        }
    }

    /**
     * @anticheat Vulcan
     * @anticheatVersion 2.7.5
     * @testedOn eu.loyisa.cn; eu.anticheat-test.com
     * @note this still flags a bit
     */
    private object Vulcan : Mode("Vulcan") {

        override val parent: ModeValueGroup<Mode>
            get() = modes

        var glide by boolean("Glide", false)

        var shouldGlide = false

        @Suppress("unused")
        val repeatable = tickHandler {
            if (glide && shouldGlide) { // if the variable is true, then glide
                if (player.onGround()) {
                    shouldGlide = false
                    return@tickHandler
                }
                if (player.fallDistance > 0) {
                    if (player.tickCount % 2 == 0) {
                        player.deltaMovement.y = -0.155
                    }
                } else {
                    player.deltaMovement.y = -0.1
                }
            }
        }

        @Suppress("unused")
        val jumpEvent = sequenceHandler<PlayerJumpEvent> {
            it.motion = motion
            waitTicks(100)
            player.deltaMovement.y = 0.0
            shouldGlide = true
        }
    }

    /**
     * For a Grim server whose immediate-setback-threshold is disabled - the only vertical limit is
     * then the accumulated max-advantage, so a single big jump tick goes through.
     *
     * A jump is one upward impulse; Grim predicts ~0.42 for a jump, so the offset it scores is
     * `Power - 0.42`. Keeping [power] under about 2.2 stays below a ~2-block budget. This is the same
     * mechanism [net.ccbluex.liquidbounce.features.module.modules.exploit.ModuleGrimProbe]'s HIGHJUMP
     * test measures: on the user's HvH server (2026-08-22) a single 1.35-offset tick did not set
     * back, and the flag only came once the running advantage crossed ~2 blocks.
     *
     * Do not spam it - each jump adds its offset to the same budget, which only decays ~0.1 %/tick,
     * so several highjumps in a row will cross the ceiling. One jump, then let it recover.
     *
     * @anticheat Grim
     * @note Only works where immediate-setback-threshold is off or high. Probe the server with
     *       GrimProbe (HIGHJUMP) before trusting a value.
     */
    private object Grim : Mode("Grim") {

        override val parent: ModeValueGroup<Mode>
            get() = modes

        private val power by float("Power", 1.8f, 0.42f..2.4f)

        @Suppress("unused")
        val jumpEvent = sequenceHandler<PlayerJumpEvent> {
            it.motion = power
        }
    }
}
