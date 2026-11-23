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
package net.ccbluex.liquidbounce.integration.screen

import net.ccbluex.liquidbounce.features.account.AccountManager
import net.ccbluex.liquidbounce.utils.client.ServerObserver
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.randomUsername

/**
 * Reconnect actions shared by the disconnected-screen buttons (`MixinDisconnectedScreen`)
 * and the `.client reconnect` command. [ServerObserver] keeps the last server across the
 * disconnect, so these work from the kick screen.
 */
object ReconnectActions {

    @JvmStatic
    fun canReconnect(): Boolean = ServerObserver.serverInfo != null

    @JvmStatic
    fun reconnect() {
        runCatching { ServerObserver.reconnect() }
            .onFailure { logger.error("Reconnect failed", it) }
    }

    /** Logs into a fresh random cracked account, then reconnects to the last server. */
    @JvmStatic
    fun reconnectWithRandomAlt() {
        Thread({
            runCatching {
                AccountManager.loginCrackedAccount(randomUsername())
                ServerObserver.reconnect()
            }.onFailure { logger.error("Reconnect with a random alt failed", it) }
        }, "hellhit-reconnect-alt").apply { isDaemon = true }.start()
    }
}
