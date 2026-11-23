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

package net.ccbluex.liquidbounce.features.module.modules.movement.fly.modes.polar

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly.modes
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.network.handlePacket
import net.minecraft.core.Holder
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.world.damagesource.DamageType
import java.util.concurrent.atomic.AtomicInteger

/**
 * @anticheat Hycraft (Polar)
 * @anticheat Version 15.05.2024
 * @testedOn mc.hycraft.us
 *
 * @note Tested in Bedwars, Skywars. Pretty much flagless
 *
 * How it works: on a damage event for us the incoming pings are held for a window, the knockback that follows
 * (`ClientboundSetEntityMotionPacket`) is applied at once and queued again, and at the end of the window everything
 * is flushed; then the module switches Fly off. One boost per toggle.
 *
 * 2026-09-26, reported as "does not always work when it should" (only the intended damage involved). The held pings
 * live in [BlinkManager]'s queue, which is SHARED, and `ModuleBacktrack` drains it on every frame: with no target it
 * flushes every queued incoming packet the moment there is one, with a target everything older than its 100-150 ms
 * delay (`ModuleBacktrack.kt:197-217`, `hasQueuedIncoming` at `:316-317` does not ask whose packets they are). So
 * with Backtrack on, the hold that this mode depends on was released within a frame or two - or not, depending on
 * Backtrack's state. [ownsIncomingBlinkQueue] is the same hand-off `VelocityReduce` already has
 * (`VelocityReduce.ownsIncomingBlinkQueue`): while a window is open Backtrack leaves the queue alone.
 * Other modules that flush the incoming queue directly (Velocity Grim2371 / Lag / Reduce, KnockbackDelay, PingSpoof,
 * Phase Blink) do not check it; they fight over the same packets by design.
 *
 * Also fixed: a window that closed without a motion packet left `damageTaken` set, so the next unrelated motion
 * packet was taken as the boost with no window left and Fly switched off at once. The counters are shared between
 * the netty thread (packets) and the game thread (ticks), so they are atomic / volatile now.
 */
internal object FlyHycraftDamage : Mode("HycraftDamage") {

    override val parent: ModeValueGroup<*>
        get() = modes

    /** Logs every stage of a window to `latest.log`: what armed it, what was captured, why it closed. */
    private val debug by boolean("Debug", false)

    /** Length of the hold, counted down by client ticks and by held pings (unchanged from the original). */
    private const val WINDOW = 40

    @Volatile
    private var damageTaken = false

    @Volatile
    private var release = false

    private val ticks = AtomicInteger(0)

    @Volatile
    private var heldPings = 0

    /**
     * True while a window is open: the incoming queue holds our pings and must not be drained by anyone else.
     * Read by `ModuleBacktrack`, next to `VelocityReduce.ownsIncomingBlinkQueue`.
     */
    val ownsIncomingBlinkQueue: Boolean
        get() = running && ticks.get() > 0

    override fun enable() {
        ticks.set(0)
        damageTaken = false
        release = false
        heldPings = 0
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        waitTicks(1)

        ticks.updateAndGet { if (it > 0) it - 1 else it }
    }

    /**
     * Used to works on different servers as well but now only Hycraft
     */
    @Suppress("unused")
    private val packetHandler = handler<BlinkPacketEvent> { event ->
        val packet = event.packet

        if (event.origin != TransferOrigin.INCOMING) {
            return@handler
        }

        // Fired on the netty thread; the player is gone during a world change.
        val player = mc.player ?: return@handler

        event.action = when (packet) {
            is ClientboundDamageEventPacket if packet.entityId == player.id -> {
                if (ticks.get() > 0) {
                    log("damage ${typeName(packet.sourceType)} ignored: window open (${ticks.get()} left)")
                    BlinkManager.Action.PASS
                } else {
                    damageTaken = true
                    heldPings = 0
                    ticks.set(WINDOW)
                    log("damage ${typeName(packet.sourceType)} armed the window")
                    handlePacket(packet)
                    BlinkManager.Action.QUEUE
                }
            }

            is ClientboundSetEntityMotionPacket if packet.id == player.id && damageTaken -> {
                damageTaken = false
                release = true
                log("knockback captured: ${packet.movement}, ${ticks.get()} left")
                handlePacket(packet)
                BlinkManager.Action.QUEUE
            }

            is ClientboundPingPacket -> {
                val left = ticks.getAndUpdate { if (it > 0) it - 1 else it }

                if (left <= 0) {
                    if (release) {
                        log("window over after $heldPings held pings, boost used -> Fly off")
                        ModuleFly.enabled = false
                    } else if (damageTaken) {
                        // Closed without a motion packet: the next one must not be taken as this damage's push.
                        damageTaken = false
                        log("window over after $heldPings held pings without knockback, re-armed")
                    }
                    return@handler
                }

                heldPings++
                BlinkManager.Action.QUEUE
            }

            // Prevent [PacketQueueManager] from flushing queued packets
            else -> BlinkManager.Action.PASS
        }

    }

    private fun typeName(type: Holder<DamageType>): String =
        type.unwrapKey().map { it.identifier().toString() }.orElse("?")

    private fun log(message: String) {
        if (debug) {
            logger.info("[Fly HycraftDamage] $message")
        }
    }

}
