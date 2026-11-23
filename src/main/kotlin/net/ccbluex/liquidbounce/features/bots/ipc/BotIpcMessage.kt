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
package net.ccbluex.liquidbounce.features.bots.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Newline-JSON wire protocol between the Kotlin host and a bot subprocess.
 *
 * Sealed hierarchies are encoded with a `"type"` discriminator field. Each subtype is hand-mapped
 * to / from JSON so the project doesn't need the kotlinx.serialization Gradle plugin.
 */

/** Outgoing message — Kotlin → bot subprocess. */
sealed interface BotCommand {
    fun toJson(): String

    data class Chat(val text: String) : BotCommand {
        override fun toJson() = obj("chat") { addProperty("text", text) }
    }

    data class Goto(val x: Int, val y: Int, val z: Int, val speed: Double = 1.0) : BotCommand {
        override fun toJson() = obj("goto") {
            addProperty("x", x); addProperty("y", y); addProperty("z", z)
            addProperty("speed", speed)
        }
    }

    data class Follow(val player: String) : BotCommand {
        override fun toJson() = obj("follow") { addProperty("player", player) }
    }

    data class Attack(val entityId: Int) : BotCommand {
        override fun toJson() = obj("attack") { addProperty("entityId", entityId) }
    }

    data class Dig(val x: Int, val y: Int, val z: Int) : BotCommand {
        override fun toJson() = obj("dig") {
            addProperty("x", x); addProperty("y", y); addProperty("z", z)
        }
    }

    data object Disconnect : BotCommand {
        override fun toJson() = obj("disconnect") {}
    }

    data class CaptchaAnswer(val text: String) : BotCommand {
        override fun toJson() = obj("captcha_answer") { addProperty("text", text) }
    }
}

/** Incoming message — bot subprocess → Kotlin. */
sealed interface BotEvent {
    data object Joined : BotEvent
    data class Left(val reason: String = "") : BotEvent
    data class Chat(val sender: String = "", val message: String) : BotEvent
    data class Health(val hp: Double, val food: Int = 20) : BotEvent
    data class Position(val x: Double, val y: Double, val z: Double) : BotEvent
    data class Captcha(val pngBase64: String) : BotEvent
    data class Log(val level: String, val message: String) : BotEvent
    data class Error(val message: String) : BotEvent

    companion object {
        /**
         * Parse a single JSON line emitted by the bot's stdout. Lines that don't decode are
         * wrapped in [Log] so the operator still sees something — never returns null.
         */
        fun fromJson(line: String): BotEvent = runCatching {
            val obj = JsonParser.parseString(line) as? JsonObject ?: return Log("INFO", line)
            when (obj.get("type")?.asString) {
                "joined" -> Joined
                "left" -> Left(obj.getOptString("reason"))
                "chat" -> Chat(
                    sender = obj.getOptString("sender"),
                    message = obj.getOptString("message"),
                )
                "health" -> Health(
                    hp = obj.get("hp")?.asDouble ?: 0.0,
                    food = obj.get("food")?.asInt ?: 20,
                )
                "position" -> Position(
                    x = obj.get("x")?.asDouble ?: 0.0,
                    y = obj.get("y")?.asDouble ?: 0.0,
                    z = obj.get("z")?.asDouble ?: 0.0,
                )
                "captcha" -> Captcha(obj.getOptString("png"))
                "log" -> Log(
                    level = obj.getOptString("level").ifBlank { "INFO" },
                    message = obj.getOptString("message"),
                )
                "error" -> Error(obj.getOptString("message"))
                else -> Log("INFO", line)
            }
        }.getOrElse { Log("INFO", line) }

        private fun JsonObject.getOptString(name: String): String =
            get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""
    }
}

private inline fun obj(type: String, build: JsonObject.() -> Unit): String {
    val json = JsonObject()
    json.addProperty("type", type)
    json.build()
    return json.toString()
}
