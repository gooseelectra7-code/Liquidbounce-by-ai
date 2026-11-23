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

package net.ccbluex.liquidbounce.features.module.modules.movement.longjump.modes

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.movement.longjump.ModuleLongJump
import net.ccbluex.liquidbounce.utils.entity.withStrafe

/**
 * A long jump that lives inside Grim's offset budget instead of a version-specific desync. It jumps
 * normally, then for a few airborne ticks forces horizontal speed above what Grim simulates - the
 * excess is scored as offset, and as long as the running total stays under max-advantage there is no
 * setback.
 *
 * Two limits, both measured on the user's HvH server with
 * [net.ccbluex.liquidbounce.features.module.modules.exploit.ModuleGrimProbe] (2026-08-22):
 * - Grim's horizontal cap is ~1.1 b/t (shared with the NCP layer), so [speed] stays under it.
 * - max-advantage is ~2 blocks; each boosted tick costs roughly `speed - 0.34` of it, so
 *   `speed 0.9 x 3 ticks` spends ~1.7 and clears. Push [boostTicks] or [speed] and it will setback.
 *
 * Unlike the anti-cheat-specific modes here this carries no teleport tricks - it is just controlled
 * speed within the budget - so it degrades gracefully: on a stricter server it simply gets a shorter
 * leap, not a ban.
 *
 * @anticheat Grim
 * @note Tune against GrimProbe: keep speed under the horizontal cap and boostTicks*(speed-0.34)
 *       under the vertical budget it reports.
 */
internal object GrimLongJump : Mode("Grim") {

    override val parent: ModeValueGroup<*>
        get() = ModuleLongJump.mode

    private val speed by float("Speed", 0.9f, 0.3f..1.1f)
    private val boostTicks by int("BoostTicks", 3, 1..10, "ticks")

    private var boostLeft = 0

    override fun enable() {
        boostLeft = 0
        ModuleLongJump.jumped = false
        ModuleLongJump.boosted = false
    }

    @Suppress("unused")
    private val jumpEvent = handler<PlayerJumpEvent> {
        boostLeft = boostTicks
    }

    @Suppress("unused")
    private val repeatable = tickHandler {
        if (boostLeft <= 0 || player.onGround()) {
            return@tickHandler
        }

        player.deltaMovement = player.deltaMovement.withStrafe(speed = speed.toDouble())
        boostLeft--
        ModuleLongJump.boosted = true

        if (boostLeft == 0 && ModuleLongJump.autoDisable) {
            ModuleLongJump.enabled = false
        }
    }
}
