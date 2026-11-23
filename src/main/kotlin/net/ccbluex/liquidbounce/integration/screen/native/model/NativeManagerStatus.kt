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
package net.ccbluex.liquidbounce.integration.screen.native.model

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.AccountManagerAdditionResultEvent
import net.ccbluex.liquidbounce.event.events.AccountManagerLoginResultEvent
import net.ccbluex.liquidbounce.event.events.AccountManagerRemovalResultEvent
import net.ccbluex.liquidbounce.event.events.ProxyCheckResultEvent
import net.ccbluex.liquidbounce.event.handler

/** One line of manager feedback for the native screens' status area. */
internal class StatusLine(val text: String, val error: Boolean) {
    val at: Long = System.currentTimeMillis()
}

/**
 * Event sink for the native alt manager: the account manager reports results through
 * events (possibly from worker threads); the screen polls [last] every frame. The object
 * registers its handlers on first use and stays alive for the game run.
 */
internal object AltManagerStatus : EventListener {

    @Volatile
    var last: StatusLine? = null
        private set

    /** Time of the last successful account ADDITION — the add modal auto-closes on it. */
    @Volatile
    var lastAdditionOkAt: Long = 0L
        private set

    fun note(text: String) {
        last = StatusLine(text, error = false)
    }

    private fun push(username: String?, error: String?, okPrefix: String) {
        last = when {
            error != null -> StatusLine(error, error = true)
            username != null -> StatusLine("$okPrefix $username", error = false)
            else -> return
        }
    }

    @Suppress("unused")
    private val onAddition = handler<AccountManagerAdditionResultEvent> { event ->
        push(event.username, event.error, "Added")
        if (event.error == null && event.username != null) {
            lastAdditionOkAt = System.currentTimeMillis()
        }
    }

    @Suppress("unused")
    private val onLogin = handler<AccountManagerLoginResultEvent> { event ->
        push(event.username, event.error, "Logged in as")
    }

    @Suppress("unused")
    private val onRemoval = handler<AccountManagerRemovalResultEvent> { event ->
        push(event.username, null, "Removed")
    }
}

/** Event sink for the native proxy manager; see [AltManagerStatus] for the pattern. */
internal object ProxyStatus : EventListener {

    @Volatile
    var last: StatusLine? = null
        private set

    /** Time of the last successful proxy check — the add modal auto-closes on it. */
    @Volatile
    var lastOkAt: Long = 0L
        private set

    fun note(text: String) {
        last = StatusLine(text, error = false)
    }

    fun fail(text: String) {
        last = StatusLine(text, error = true)
    }

    @Suppress("unused")
    private val onCheck = handler<ProxyCheckResultEvent> { event ->
        val error = event.error
        last = if (error != null) {
            StatusLine(error, error = true)
        } else {
            val proxy = event.proxy
            lastOkAt = System.currentTimeMillis()
            StatusLine("Proxy ${proxy?.host}:${proxy?.port} works", error = false)
        }
    }
}
