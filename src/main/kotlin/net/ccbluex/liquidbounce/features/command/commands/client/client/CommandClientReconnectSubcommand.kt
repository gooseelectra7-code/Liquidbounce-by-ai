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
package net.ccbluex.liquidbounce.features.command.commands.client.client

import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.integration.screen.ReconnectActions
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular

/**
 * `.client reconnect` — reconnects to the last server ([ReconnectActions]); works from
 * the disconnected screen and from the menus after a kick.
 */
object CommandClientReconnectSubcommand {

    fun reconnectCommand() = CommandBuilder.begin("reconnect")
        .handler {
            if (!ReconnectActions.canReconnect()) {
                chat(regular("No known last server to reconnect to."))
                return@handler
            }
            chat(regular("Reconnecting..."))
            ReconnectActions.reconnect()
        }
        .build()
}
