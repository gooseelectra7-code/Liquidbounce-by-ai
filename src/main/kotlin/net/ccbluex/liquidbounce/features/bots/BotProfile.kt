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
package net.ccbluex.liquidbounce.features.bots

/**
 * Persisted record describing how a bot should be launched.
 *
 * Per Phase 1 of the BotManager plan the bot is driven by a mock subprocess; from Phase 2 onwards
 * the same record feeds the patched Botcraft binary as CLI arguments.
 */
data class BotProfile(
    val id: String,
    var username: String,
    var server: String,
    var port: Int = 25565,
    var proxyId: String? = null,
    var behavior: BotBehavior = BotBehavior.IDLE,
    /** Microsoft refresh token, null for offline / cracked mode. */
    var refreshToken: String? = null,
) {
    val address: String
        get() = "$server:$port"
}

enum class BotBehavior {
    /** Connect and stand still. */
    IDLE,

    /** Follow the host player around. */
    FOLLOW_HOST,

    /** Combat preset: attack nearby hostile mobs / configured targets. */
    COMBAT,

    /** Farming preset (placeholder — implemented in Phase 4). */
    FARM,
}
