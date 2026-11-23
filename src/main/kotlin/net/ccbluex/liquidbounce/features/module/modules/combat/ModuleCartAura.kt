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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.player.Player

/**
 * CartAura — the [AutoCartFire] chain (rail → TNT minecart → flint & steel on the block behind the
 * rail → charged crossbow through it → explosion) fired at the popped target instead of
 * yourself.
 *
 * A target's totem pop is detected the way [net.ccbluex.liquidbounce.features.module.modules.render.ModuleTotemPopESP]
 * reads it — the `PROTECTED_FROM_DEATH` entity event arrives on the netty thread and is parked,
 * the victim is resolved on the tick. The chain then picks its spot with
 * [AutoCartFire.findSpot]'s scan — left, right or anywhere around the popped target — and runs
 * the same sequence as Mode FIRE. One chain per pop, spaced by [Cooldown].
 */
object ModuleCartAura : ClientModule("CartAura", ModuleCategories.COMBAT) {

    private val range by float("Range", 8f, 2f..12f, "m")
    private val cooldown by int("Cooldown", 60, 0..120, "ticks")

    private var lastStartMs = 0L

    @Volatile
    private var pendingPop: ClientboundEntityEventPacket? = null

    /** Somewhere a pop was seen; resolved on the tick once the victim is in the world. */
    @Suppress("unused")
    private val popHandler = handler<PacketEvent> { event ->
        val packet = event.packet as? ClientboundEntityEventPacket ?: return@handler
        if (packet.eventId != EntityEvent.PROTECTED_FROM_DEATH) return@handler
        pendingPop = packet
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val pop = pendingPop ?: return@tickHandler
        pendingPop = null

        val level = mc.level ?: return@tickHandler
        val victim = pop.getEntity(level) as? Player ?: return@tickHandler
        if (!victim.isAlive || !victim.shouldBeAttacked()) return@tickHandler
        if (victim.distanceToSqr(player) > range * range) return@tickHandler

        val now = System.currentTimeMillis()
        if (now - lastStartMs < cooldown.toLong()) return@tickHandler
        lastStartMs = now

        AutoCartFire.startAt(victim.position())
    }
}
