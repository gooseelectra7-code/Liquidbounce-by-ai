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

import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.network.isLocalPlayerVelocity
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.world.entity.LivingEntity

/**
 * KnockbackDelay module
 *
 * Receive-side blink: once a self-knockback packet arrives (entity motion for the local player
 * or an explosion with player knockback), every incoming packet is held back for a random,
 * ground/air dependent delay, so the knockback applies late while you keep fighting.
 *
 * Ported from the Echo client. Instead of re-handling packets by hand (which would run on the
 * netty thread), this port queues and flushes through [BlinkManager]: while holding, it answers
 * every incoming [BlinkPacketEvent] with [BlinkManager.Action.QUEUE]; to flush it simply stops
 * doing so, which makes the manager release the queue in order on the main client thread.
 * BlinkManager also natively implements echo's packet exceptions (chat is never touched;
 * position/disconnect/death flush the queue and are handled normally).
 */
object ModuleKnockbackDelay : ClientModule("KnockbackDelay", ModuleCategories.COMBAT) {

    private val chance by int("Chance", 100, 0..100, "%")
    private val airDelay by intRange("AirDelay", 100..250, 0..5000, "ms")
    private val groundDelay by intRange("GroundDelay", 50..150, 0..5000, "ms")

    @Volatile
    private var active = false

    @Volatile
    private var activeDelayMs = 0L

    @Volatile
    private var lastTargetId = -1

    override val tag: String
        get() = if (active) {
            "Holding"
        } else {
            val range = if (mc.player?.onGround() == false) airDelay else groundDelay
            "${range.first}-${range.last}ms"
        }

    override fun onEnabled() {
        resetState()
    }

    override fun onDisabled() {
        // Echo flushes its queue on disable; release everything we are currently holding.
        if (active) {
            BlinkManager.flush(TransferOrigin.INCOMING)
        }
        resetState()
    }

    private fun resetState() {
        deactivate()
        lastTargetId = -1
    }

    private fun deactivate() {
        active = false
        activeDelayMs = 0L
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        lastTargetId = event.entity.id
    }

    @Suppress("unused")
    private val blinkPacketHandler = handler<BlinkPacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || mc.hasSingleplayerServer()) {
            return@handler
        }

        when (val packet = event.packet) {
            // Null packet = BlinkManager's periodic flush poll, fired on the main thread
            // every frame before packet tasks are processed. This replaces echo's tick handler.
            null -> onFlushPoll(event)
            else -> onIncomingPacket(event, packet)
        }
    }

    /**
     * Echo's onTick: flush when the last attacked target became unavailable or the oldest
     * held packet exceeded the picked delay. Leaving [BlinkPacketEvent.action] at its
     * default ([BlinkManager.Action.FLUSH]) makes BlinkManager release the queue.
     */
    private fun onFlushPoll(event: BlinkPacketEvent) {
        if (!active) {
            return
        }

        if (isLastTargetUnavailable()) {
            deactivate()
            lastTargetId = -1
            return
        }

        val oldest = oldestHeldTimestamp()
        if (oldest == null) {
            // Nothing is held anymore (e.g. flushed externally) - just deactivate.
            deactivate()
            return
        }

        if (System.currentTimeMillis() - oldest >= activeDelayMs) {
            deactivate()
            return
        }

        event.action = BlinkManager.Action.QUEUE
    }

    @Suppress("ReturnCount")
    private fun onIncomingPacket(event: BlinkPacketEvent, packet: Packet<*>) {
        // Echo never touches chat packets (the serverbound checks are dead conditions on the
        // receive path, kept for fidelity). With QUEUE set, BlinkManager still passes chat
        // packets through without queueing them - so the hold is kept while chat flows.
        if (packet is ServerboundChatPacket ||
            packet is ClientboundSystemChatPacket ||
            packet is ServerboundChatCommandPacket
        ) {
            if (active) {
                event.action = BlinkManager.Action.QUEUE
            }
            return
        }

        // Forced flush conditions: BlinkManager itself flushes the queue on these packets and
        // handles them normally (echo: flushAll() + vanilla handling). We only reset our state.
        // Respawn/Login are BlinkManager extras beyond echo's list; deactivate on them too so
        // we never keep "holding" over a manager-forced flush.
        if (packet is ClientboundPlayerPositionPacket ||
            packet is ClientboundDisconnectPacket ||
            packet is ClientboundRespawnPacket ||
            packet is ClientboundLoginPacket ||
            (packet is ClientboundSetHealthPacket && packet.health <= 0f)
        ) {
            deactivate()
            return
        }

        // The last attacked target is being removed: forget it and, if holding, flush.
        var removingLastTarget = false
        if (packet is ClientboundRemoveEntitiesPacket && lastTargetId != -1 &&
            packet.entityIds.contains(lastTargetId)
        ) {
            lastTargetId = -1
            removingLastTarget = true
        }

        // Trigger: self knockback (set entity motion with our id / explosion player knockback).
        if (!active && packet.isLocalPlayerVelocity() && rollChance()) {
            active = true
            activeDelayMs = pickDelay()
        }

        if (!active) {
            return
        }

        if (removingLastTarget) {
            // Echo queues the removal packet and immediately flushes everything. Leaving the
            // action at FLUSH releases the held packets first and lets this packet through
            // right behind them - the same order queue-then-flushAll produces.
            deactivate()
            return
        }

        // Echo flushes as soon as the oldest held packet exceeds the delay.
        val oldest = oldestHeldTimestamp()
        if (oldest != null && System.currentTimeMillis() - oldest >= activeDelayMs) {
            deactivate()
            return
        }

        event.action = BlinkManager.Action.QUEUE
    }

    private fun oldestHeldTimestamp(): Long? =
        BlinkManager.packetQueue.firstOrNull { it.origin == TransferOrigin.INCOMING }?.timestamp

    private fun isLastTargetUnavailable(): Boolean {
        if (lastTargetId == -1) {
            return false
        }

        val level = mc.level ?: return true
        val entity = level.getEntity(lastTargetId) ?: return true
        if (!entity.isAlive || entity.isRemoved) {
            return true
        }

        return entity is LivingEntity && (entity.health <= 0f || !entity.shouldBeAttacked())
    }

    private fun rollChance(): Boolean = when {
        chance >= 100 -> true
        chance <= 0 -> false
        else -> Math.random() * 100.0 < chance
    }

    private fun pickDelay(): Long =
        (if (mc.player?.onGround() == false) airDelay else groundDelay).random().toLong()

}
