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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.ccbluex.liquidbounce.features.bots.ipc.BotCommand
import net.ccbluex.liquidbounce.features.bots.ipc.BotEvent
import net.ccbluex.liquidbounce.features.bots.ipc.BotIpcChannel

/**
 * Runtime state of a single bot. Bound to a [BotProfile] until removed.
 */
class BotInstance(val profile: BotProfile) {

    enum class State { DISCONNECTED, CONNECTING, IN_GAME, CAPTCHA_PENDING, DEAD }

    private val ipc = BotIpcChannel(profile.id)

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    /** Last position reported by the bot, if any. */
    @Volatile var lastPosition: Triple<Double, Double, Double>? = null
        private set

    /** Last health reading from the bot, if any. */
    @Volatile var lastHealth: Double? = null
        private set

    fun start(scope: CoroutineScope) {
        if (_state.value != State.DISCONNECTED && _state.value != State.DEAD) {
            return
        }
        _state.value = State.CONNECTING

        // Phase 1: mock subprocess. Phase 2 swaps this for the real Botcraft binary.
        ipc.start(BotIpcChannel.mockCommandFor(profile.username), scope)

        ipc.events.onEach { event ->
            when (event) {
                is BotEvent.Joined -> _state.value = State.IN_GAME
                is BotEvent.Left -> _state.value = State.DEAD
                is BotEvent.Position -> lastPosition = Triple(event.x, event.y, event.z)
                is BotEvent.Health -> lastHealth = event.hp
                is BotEvent.Captcha -> _state.value = State.CAPTCHA_PENDING
                else -> Unit
            }
        }.launchIn(scope)
    }

    fun stop() {
        ipc.close()
        _state.value = State.DISCONNECTED
    }

    suspend fun send(command: BotCommand) {
        if (ipc.running) {
            ipc.send(command)
        }
    }

    fun sendAsync(command: BotCommand, scope: CoroutineScope) {
        scope.launch { send(command) }
    }
}
