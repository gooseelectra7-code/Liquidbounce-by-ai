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
package net.ccbluex.liquidbounce.features.module.modules.combat.placement

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY

/**
 * What kind of interaction has already gone out this tick, shared by everything that sends one.
 *
 * Grim's `MultiActionsF` and `PacketOrderJ` both look for a block interaction and an entity
 * interaction inside a single tick window, and `PacketOrderE` looks for a held-item change landing
 * after either. None of that is about which MODULE sent what — the server sees one packet stream —
 * so a guard kept privately inside one module cannot work. AutoCrystal attacking and AutoBasePlace
 * placing in the same tick produces exactly the pattern with neither of them doing anything wrong on
 * its own.
 *
 * Ticks are counted rather than flagged because the crystal-spawn reaction is deferred through the
 * tick-loop executor, which drains BEFORE the tick handlers. A boolean cleared at the top of a
 * handler is cleared after that reaction already read it; a tick number is the same answer whatever
 * the order.
 */
object CrystalTickGuard : EventListener {

    @Volatile
    private var tick = 0L

    @Volatile
    private var attackedAt = -1L

    @Volatile
    private var placedAt = -1L

    /**
     * Advanced at the movement packet, not at the top of the tick.
     *
     * Everything that marks does so from a tick handler, and the tick handlers run BEFORE the
     * movement packet. Incrementing at the top of the tick raced them: whether a mark landed on this
     * tick or the previous one depended on handler registration order, which is not something a
     * correctness guard may depend on. Advancing after them makes every mark in a tick share one
     * number, including the crystal-spawn reaction that runs at the tick-loop head.
     */
    @Suppress("unused")
    private val tickHandler = handler<PlayerNetworkMovementTickEvent>(priority = FIRST_PRIORITY) { event ->
        if (event.state == EventState.PRE) {
            tick++
        }
    }

    /**
     * Whether pairing a block interaction with an entity interaction is refused outright.
     *
     * Set from `OneTickAction`, so the trade is the user's and it is the same switch that governs it
     * inside AutoCrystal. Refusing unconditionally, as this did at first, killed AutoBasePlace
     * entirely: the aura attacks on most ticks of a fight, so its placements were being dropped
     * almost every time.
     */
    @Volatile
    var strict = false

    /**
     * Whether an attack has gone out this tick, regardless of [strict].
     *
     * A held-item change landing after one is flagged by `PacketOrderE` whatever the user thinks
     * about pairing actions, so the slot guard reads this and not the strict view.
     */
    val attackedRaw: Boolean
        get() = attackedAt == tick

    val attackedThisTick: Boolean
        get() = strict && attackedRaw

    val placedThisTick: Boolean
        get() = strict && placedAt == tick

    /**
     * Whether any interaction — a place or an attack — has already gone out this tick, while [strict].
     *
     * This is the whole budget under [strict]: exactly one interaction per tick, across every module
     * that shares this guard. A second place is `MultiPlace`, a second attack is `MultiInteractA`, and
     * one of each is `PacketOrderI`/`PacketOrderJ`/`MultiActionsF` — so once anything has acted, nothing
     * else may until the next tick. Off [strict] it is always false and pairing is unrestricted.
     */
    val actedThisTick: Boolean
        get() = attackedThisTick || placedThisTick

    fun markAttacked() {
        attackedAt = tick
    }

    fun markPlaced() {
        placedAt = tick
    }

}
