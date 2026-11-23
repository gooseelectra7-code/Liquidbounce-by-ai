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

package net.ccbluex.liquidbounce.utils.navigation.pathing

import net.ccbluex.liquidbounce.utils.client.chat

/**
 * A running commentary from the executor, for when watching it is not enough to tell what it thinks
 * it is doing.
 *
 * Two manoeuvres in a row have now been diagnosed twice from a description of what they looked like
 * from outside, and the second diagnosis was right about the cause and wrong about the test. This
 * exists so the next one is not a guess: what the executor is waiting for is a thing it knows and
 * nobody else can see.
 *
 * Off unless asked for, and deduplicated — a line appears when the answer *changes*, so a manoeuvre
 * that goes to plan is three lines and one that does not repeats nothing.
 */
object NavDebug {

    /** Set from the navigation module's Trace setting. */
    @Volatile
    var trace = false

    private var last: String? = null

    fun say(message: String) {
        if (!trace || message == last) return
        last = message
        chat("§7[nav] $message")
    }

    /** Forgets the last line, so the next identical one is printed again. */
    fun reset() {
        last = null
    }
}
