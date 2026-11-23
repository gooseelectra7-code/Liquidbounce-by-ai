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
package net.ccbluex.liquidbounce.features.command.commands.bots

import kotlinx.coroutines.launch
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.eventListenerScope
import net.ccbluex.liquidbounce.features.bots.BotManager
import net.ccbluex.liquidbounce.features.bots.ipc.BotCommand
import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.CommandException
import net.ccbluex.liquidbounce.features.command.Parameter.Verificator.Result
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.utils.client.MessageMetadata
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.text.asText

/**
 * `.bot` family of chat commands for managing bot profiles.
 *
 * Phase 1 covers list / add / remove / start / stop / say. Phase 4 extends with goto / follow /
 * attack, and Phase 3 adds the proxy subgroup.
 */
object CommandBot : Command.Factory, EventListener {

    private val META = MessageMetadata(id = "CBot#info")

    override fun createCommand(): Command = CommandBuilder
        .begin("bot")
        .hub()
        .subcommand(listCommand())
        .subcommand(addCommand())
        .subcommand(removeCommand())
        .subcommand(startCommand())
        .subcommand(stopCommand())
        .subcommand(sayCommand())
        .build()

    private fun listCommand(): Command = CommandBuilder
        .begin("list")
        .handler {
            val profiles = BotManager.profiles()
            if (profiles.isEmpty()) {
                chat(regular("No bots configured."), metadata = META)
                return@handler
            }
            chat(regular("Bots (${profiles.size}):"), metadata = META)
            for (profile in profiles) {
                val state = BotManager.instance(profile.id)?.state?.value?.name ?: "DISCONNECTED"
                chat(
                    regular("- ${variable(profile.username).string} → ${profile.address}  [$state]"),
                    metadata = META,
                )
            }
        }
        .build()

    private fun addCommand(): Command = CommandBuilder
        .begin("add")
        .parameter(
            ParameterBuilder.begin<String>("username")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                .required()
                .build()
        )
        .parameter(
            ParameterBuilder.begin<String>("host")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                .required()
                .build()
        )
        .parameter(
            ParameterBuilder.begin<Int>("port")
                .verifiedBy { input ->
                    val v = input.toIntOrNull()
                    if (v != null && v in 1..65535) {
                        Result.Ok(v)
                    } else {
                        Result.Error("'$input' is not a valid port (1..65535)")
                    }
                }
                .optional()
                .build()
        )
        .handler {
            val username = args[0] as String
            val host = args[1] as String
            val port = (args.getOrNull(2) as Int?) ?: 25565

            if (BotManager.byUsername(username) != null) {
                throw CommandException("A bot named '$username' already exists.".asText())
            }

            val profile = BotManager.add(BotManager.newProfile(username, host, port))
            chat(
                regular("Added bot ${variable(profile.username).string} → ${profile.address}"),
                metadata = META,
            )
        }
        .build()

    private fun removeCommand(): Command = CommandBuilder
        .begin("remove")
        .parameter(usernameParameter())
        .handler {
            val username = args[0] as String
            val profile = BotManager.byUsername(username)
                ?: throw CommandException("No bot named '$username'.".asText())
            BotManager.remove(profile.id)
            chat(regular("Removed bot ${variable(username).string}."), metadata = META)
        }
        .build()

    private fun startCommand(): Command = CommandBuilder
        .begin("start")
        .parameter(usernameParameter())
        .handler {
            val username = args[0] as String
            val profile = BotManager.byUsername(username)
                ?: throw CommandException("No bot named '$username'.".asText())
            val started = BotManager.start(profile.id)
            if (started) {
                chat(regular("Starting ${variable(username).string}…"), metadata = META)
            } else {
                chat(markAsError("Bot is already running."), metadata = META)
            }
        }
        .build()

    private fun stopCommand(): Command = CommandBuilder
        .begin("stop")
        .parameter(usernameParameter())
        .handler {
            val username = args[0] as String
            val profile = BotManager.byUsername(username)
                ?: throw CommandException("No bot named '$username'.".asText())
            if (BotManager.stop(profile.id)) {
                chat(regular("Stopped ${variable(username).string}."), metadata = META)
            } else {
                chat(markAsError("Bot is not running."), metadata = META)
            }
        }
        .build()

    private fun sayCommand(): Command = CommandBuilder
        .begin("say")
        .parameter(usernameParameter())
        .parameter(
            ParameterBuilder.begin<String>("message")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                .vararg()
                .required()
                .build()
        )
        .handler {
            val username = args[0] as String
            val message = (args[1] as Array<*>).joinToString(" ") { it as String }
            val profile = BotManager.byUsername(username)
                ?: throw CommandException("No bot named '$username'.".asText())

            val instance = BotManager.instance(profile.id)
                ?: throw CommandException("Bot '$username' is not started.".asText())

            eventListenerScope.launch { instance.send(BotCommand.Chat(message)) }
            chat(regular("Sent to ${variable(username).string}: $message"), metadata = META)
        }
        .build()

    private fun usernameParameter() = ParameterBuilder
        .begin<String>("username")
        .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
        .autocompletedFrom { BotManager.profiles().map { it.username } }
        .required()
        .build()
}
