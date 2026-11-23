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
package net.ccbluex.liquidbounce.features.module.modules.combat.totem

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.logger

/**
 * A module's own text-only debug channel.
 *
 * The `Debug` module was the wrong switch for this. It is an all-or-nothing render feature - turning
 * it on to find out why one swap was refused also paints every registered `debugParameter` across
 * the screen, from every other module that registers one. This is the narrow alternative the user
 * asked for on 2026-09-16: one module, text only, off by default.
 *
 * It exists at all because the totem swap path is otherwise INVISIBLE in `latest.log`. Every gate in
 * it reports through `ModuleDebug.debugParameter`, which feeds the overlay and never writes a line,
 * so a report of "the jitter finished and it still did not swap" had nothing to read back. This is
 * what makes that report checkable.
 *
 * One instance per module, like [SwapQuiet] and [SwapJitter], and a nested `ToggleableValueGroup` so
 * both ClickGUIs collapse [toLog], [toChat] and [minInterval] away while it is off - see the
 * settings-style rule in AGENTS.md.
 */
class SwapDebug(
    private val owner: ClientModule,
    /**
     * Default of [minInterval]. A caller that only logs when its reason CHANGES passes 0: pacing
     * would then drop exactly the transition it exists to show.
     */
    defaultMinInterval: Int = 1000,
) : ToggleableValueGroup(owner, "Debug", false) {

    /** Write to `latest.log`. The one to leave on: it survives the session and can be sent over. */
    private val toLog by boolean("Log", true)

    /**
     * Also print in chat, tagged with the module.
     *
     * Off by default because the interesting case - a gate refusing every tick - is exactly the one
     * that would bury the chat, and [minInterval] is the only thing standing between it and a flood.
     */
    private val toChat by boolean("Chat", false)

    /**
     * Minimum gap between two lines, in ms.
     *
     * A refusal is typically re-evaluated every tick, so without this one blocked swap is 20 lines a
     * second. 0 disables the pacing and prints every call, which is only sensible for a one-shot.
     */
    private val minInterval by int("MinInterval", defaultMinInterval, 0..5000, "ms")

    private val pace = Chronometer()

    /**
     * Emits one diagnostic line, subject to [minInterval].
     *
     * Cheap to call unconditionally from a hot path: the [enabled] check comes first, so a disabled
     * channel costs one boolean read and never builds the string - as long as callers pass an
     * already-built [message] and not something expensive to compute. For an expensive one, guard
     * the call site with [enabled] yourself.
     */
    fun log(message: String) {
        if (!enabled) {
            return
        }

        if (minInterval > 0 && !pace.hasAtLeastElapsed(minInterval.toLong())) {
            return
        }

        pace.reset()

        if (toLog) {
            logger.info("[${owner.name}] $message")
        }

        if (toChat) {
            chat("§8[§b${owner.name}§8] §7$message", owner)
        }
    }

}
