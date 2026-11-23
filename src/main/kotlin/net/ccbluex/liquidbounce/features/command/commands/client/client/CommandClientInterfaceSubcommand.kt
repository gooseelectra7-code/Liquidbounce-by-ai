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
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.integration.screen.native.InterfaceMode
import net.ccbluex.liquidbounce.integration.screen.native.InterfaceSettings
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable

/**
 * `.client interface <native|vanilla>` — selects whether the client's native menus replace the
 * vanilla ones. Takes effect on the next screen change (the screen manager re-evaluates every tick
 * and on every screen event).
 */
object CommandClientInterfaceSubcommand {

    fun interfaceCommand() = CommandBuilder.begin("interface")
        .parameter(
            ParameterBuilder.begin<String>("mode")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR).required()
                .autocompletedFrom { InterfaceMode.entries.map { it.name.lowercase() } }
                .build()
        )
        .handler {
            val name = (args[0] as String).uppercase()
            val mode = InterfaceMode.entries.firstOrNull { it.name == name }
            if (mode == null) {
                chat(regular("Unknown mode. Use: native or vanilla."))
                return@handler
            }
            InterfaceSettings.mode = mode
            chat(regular("Interface mode set to "), variable(mode.name.lowercase()), regular("."))
        }
        .build()
}
