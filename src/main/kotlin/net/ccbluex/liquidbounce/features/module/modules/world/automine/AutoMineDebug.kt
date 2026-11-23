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

package net.ccbluex.liquidbounce.features.module.modules.world.automine

import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.core.BlockPos

/**
 * A written account of what the miner decided and why, in the game log.
 *
 * Aimed at whoever is reading `logs/latest.log` after the fact rather than at the player: a miner
 * spends minutes at a time doing something slightly wrong, and "it walks around inside the region"
 * is not a description anyone can act on. What is missing from such a report is always the same
 * thing — which block it picked, out of how many, how far away, what the route said about it, and
 * what it was waiting for when nothing appeared to happen.
 *
 * ## Two kinds of line
 *
 * [event] is for things that happen once and matter: a target chosen, a block broken, a target given
 * up on. Every one is written.
 *
 * [state] is for answers to "what are you doing right now", which are asked every tick and are the
 * same tick after tick. Only a *change* of answer is written, so a manoeuvre that goes to plan is
 * three lines and one that hangs is one line plus silence — which is itself the diagnosis.
 *
 * Off unless asked for, and every line carries a tick number and the player's position, so lines
 * from different subsystems can be lined up against each other and against the route's own trace.
 */
object AutoMineDebug {

    /** Set from the module's Debug setting. */
    @Volatile
    var enabled = false

    /** Ticks since the module was switched on. Cheap ordering that survives log interleaving. */
    var tick: Long = 0
        private set

    private var lastState: String? = null

    fun onTick() {
        tick++
    }

    fun reset() {
        tick = 0
        lastState = null
    }

    /** Something happened. Always written. */
    fun event(message: String) {
        if (!enabled) return
        logger.info("[AutoMine] t={} {} | {}", tick, message, where())
    }

    /** What we are doing now. Written only when the answer is not what it was last time. */
    fun state(message: String) {
        if (!enabled || message == lastState) return
        lastState = message
        logger.info("[AutoMine] t={} {} | {}", tick, message, where())
    }

    /** Formats a block position the way every line here does, so grep and eyes both work. */
    fun pos(pos: BlockPos?): String = if (pos == null) "none" else "${pos.x},${pos.y},${pos.z}"

    private fun where(): String {
        val p = player
        return "at %.1f,%.1f,%.1f".format(p.x, p.y, p.z)
    }
}
