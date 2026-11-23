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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.events.ChatReceiveEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories

/**
 * AutoAccept module
 *
 * Automatically accepts incoming teleport (/tpa) requests by sending the configured
 * command after a configurable tick delay.
 *
 * Ported from SystemDLC (`system-source — копия`,
 * `src/main/java/client/module/player/AutoAccept.java`). The original listened to the raw
 * system-chat packet (non-overlay only), matched the request text against fixed
 * English/Russian keyword pairs, optionally required the message to contain a friend's
 * name, then armed a tick countdown and sent the command from the tick loop. A new
 * matching message while waiting re-arms the countdown — exactly reproduced here.
 * Setting names translated from the Russian originals ("Только друзья" -> OnlyFriends,
 * "Задержка" -> Delay, "Команда" -> Command); defaults and ranges kept exactly.
 *
 * Grim-relevant decisions: the only packet this module ever emits is one chat command
 * (`ClientPacketListener.sendCommand`) from the client tick — no movement, rotation or
 * interaction packets, so no Grim check surface. The chat trigger fires on the main
 * thread (ChatListener), and the send happens in the tick handler, mirroring the
 * original's packet-thread-sets-flag / tick-thread-sends split.
 */
object ModuleAutoAccept : ClientModule("AutoAccept", ModuleCategories.MISC) {

    /** Original: "Только друзья" — accept teleport requests from friends only (default true). */
    private val onlyFriends by boolean("OnlyFriends", true)

    /** Original: "Задержка" — delay before accepting, 0..40 ticks, default 0. */
    private val delay by int("Delay", 0, 0..40, "ticks")

    /** Original: "Команда" — command used to accept the teleport (sent without leading slash). */
    private val command by text("Command", "tpaccept")

    private var pending = false
    private var ticksLeft = 0

    override fun onEnabled() {
        pending = false
        ticksLeft = 0
    }

    override fun onDisabled() {
        pending = false
        ticksLeft = 0
    }

    /**
     * The original matched `GameMessageS2CPacket` with `overlay == false`; in this repo that is
     * exactly [ChatReceiveEvent.ChatType.GAME_MESSAGE] (MixinChatListener maps overlay system
     * messages to DISGUISED_CHAT_MESSAGE). Player chat is intentionally NOT matched.
     */
    @Suppress("unused")
    private val chatHandler = handler<ChatReceiveEvent> { event ->
        if (event.type != ChatReceiveEvent.ChatType.GAME_MESSAGE) {
            return@handler
        }

        val message = event.message.lowercase()
        if (!isTeleportRequest(message)) {
            return@handler
        }

        if (onlyFriends && !containsFriendName(message)) {
            return@handler
        }

        // Arm (or re-arm) the countdown — a second matching message resets the timer,
        // exactly like the original (value235 = delay; flag = true).
        ticksLeft = delay
        pending = true
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!pending) {
            return@handler
        }

        if (ticksLeft > 0) {
            ticksLeft--
        } else {
            val acceptCommand = command.trim()
            if (acceptCommand.isNotEmpty()) {
                network.sendCommand(acceptCommand)
            }

            pending = false
        }
    }

    /**
     * Keyword pairs kept EXACTLY as in the original (AutoAccept.java `isString2`):
     * a teleport word AND a request word, English or Russian.
     */
    private fun isTeleportRequest(message: String): Boolean {
        val hasTeleportWord = message.contains("teleport") || message.contains("телепорт") ||
            message.contains("tpa") || message.contains("tpask")
        val hasRequestWord = message.contains("request") || message.contains("просит") ||
            message.contains("запрос") || message.contains("has requested") ||
            message.contains("wants to")
        return hasTeleportWord && hasRequestWord
    }

    /**
     * The original checked whether the lowercased message contains any (lowercased) friend
     * name from its friend list; blank names never occurred there, so they are skipped.
     */
    private fun containsFriendName(message: String): Boolean =
        FriendManager.friends.any { friend ->
            friend.name.isNotBlank() && message.contains(friend.name.lowercase())
        }
}
