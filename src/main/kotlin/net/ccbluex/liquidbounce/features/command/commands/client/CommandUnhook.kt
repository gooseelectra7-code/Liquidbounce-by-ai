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
package net.ccbluex.liquidbounce.features.command.commands.client

import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleUnhook
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular

/**
 * Unhook Command
 *
 * Panic button: closes any open screen and disables every running module in one
 * keypress, so the client goes dormant and the screen looks vanilla. Shares its
 * logic with [ModuleUnhook] (which also gives it a bindable key). See that
 * module for the honesty note on what "unhook" can and cannot mean for a mixin
 * client (mixins cannot be removed at runtime; hooks are made inert instead).
 */
object CommandUnhook : Command.Factory {

    override fun createCommand(): Command {
        return CommandBuilder
            .begin("unhook")
            .handler {
                ModuleUnhook.panic()
                chat(regular(command.result("unhooked")), command)
            }
            .build()
    }

}
