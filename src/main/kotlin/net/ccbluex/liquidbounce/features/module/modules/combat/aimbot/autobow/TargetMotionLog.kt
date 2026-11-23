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

package net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.autobow

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.ModuleAutoBow
import net.ccbluex.liquidbounce.injection.mixins.minecraft.network.MixinClientboundMoveEntityPacketAccessor
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap

/**
 * What the SERVER knows about a target's position and motion, read off its position packets
 * instead of the client's smoothed rendering of them.
 *
 * ## Why the rendered entity is the wrong thing to aim at (26.1.2)
 *
 * - A player's position is broadcast every SECOND server tick (`EntityType.java:1115`,
 *   `updateInterval(2)`; `ServerEntity.sendChanges`, `ServerEntity.java:121`), and only when it
 *   changed (`:140-142`, or every 60 ticks regardless). So what the client holds is between zero
 *   and two ticks stale, and a shot that leads from it without knowing how stale it is is short by
 *   that much - a tick of sprinting is 0.28 blocks, the whole half-width of a player.
 * - The client interpolates each packet over three ticks (`InterpolationHandler.java:11`, `:84-106`),
 *   so `position() - lastPos` is a smoothed, lagging velocity, and `position()` itself trails the
 *   packet by up to two ticks of motion. `InterpolationHandler.position()` (`:40-42`) is the packet.
 * - Remote players get no velocity from the server outside of knockback (`ServerEntity.java:169`:
 *   `needsSync` or `trackDelta`, and players do not track deltas), so `getDeltaMovement` says
 *   nothing about how they walk.
 *
 * ## What this keeps
 *
 * Per living entity near the player, the last few DISTINCT positions the client received - read
 * at the head of every tick from `InterpolationHandler.position()`, which moves only when a
 * packet does - each stamped with the netty-thread arrival time of the latest position packet
 * for that entity ([packetHandler]) and the client tick it was first seen on. From those:
 *
 * - **velocity** is the displacement between packets divided by the server's cadence. The server
 *   sends every `updateInterval` ticks while the entity moves, so consecutive samples are that many
 *   server ticks apart whatever the network did to their spacing; up to three consecutive samples
 *   are averaged (`(last - first) / ((count - 1) * interval)`) to ride out one-tick arrival jitter.
 *   A run that broke (no packet for longer than the interval) means the entity stood still: the
 *   server would have reported any movement within the interval, so the velocity is zero, not
 *   whatever the client is still interpolating towards.
 * - **sinceReceived** is how long ago the position was received, in ticks - the staleness a lead
 *   has to add on top of the round trip.
 *
 * Threading: [packetHandler] runs on the netty thread and touches only [arrivals]; everything else
 * is main thread. [parent] is [ModuleAutoBow], so nothing here runs while the module is off.
 */
object TargetMotionLog : EventListener {

    override fun parent(): EventListener = ModuleAutoBow

    /**
     * The server's last reported [position] of an entity, its per-tick [velocity] and how many
     * ticks ago that position arrived. [fromPackets] is false when the log has nothing on the
     * entity yet and the client's interpolated state was used instead.
     */
    class Motion(val position: Vec3, val velocity: Vec3, val sinceReceivedTicks: Double, val fromPackets: Boolean)

    private class Sample(val position: Vec3, val arrivalNanos: Long, val tick: Int)

    private class Log {
        val samples = ArrayDeque<Sample>()
        var lastTouchedTick = 0
    }

    /** Entity id -> `System.nanoTime()` of its latest position packet. Netty thread writes, main thread reads. */
    private val arrivals = ConcurrentHashMap<Int, Long>()

    /** Main thread only. */
    private val logs = Int2ObjectOpenHashMap<Log>()

    /**
     * Forces the object's initialisation - and with it the registration of its handlers - from the
     * owning module.
     */
    fun register() {}

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) {
            return@handler
        }

        val id = when (val packet = event.packet) {
            is ClientboundMoveEntityPacket -> if (packet.hasPosition()) {
                (packet as MixinClientboundMoveEntityPacketAccessor).`liquid_bounce$getEntityId`()
            } else {
                return@handler
            }

            is ClientboundEntityPositionSyncPacket -> packet.id()
            else -> return@handler
        }

        arrivals[id] = System.nanoTime()
    }

    /**
     * Runs ahead of RotationManager's FIRST_PRIORITY tick handler (which fires the aimbot's
     * planning), so the aimbot reads this tick's packets.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent>(priority = OBSERVE_PRIORITY) {
        val now = System.nanoTime()
        val tick = player.tickCount
        val eye = player.eyePosition

        for (entity in world.entitiesForRendering()) {
            if (entity !is LivingEntity || entity === player || entity.distanceToSqr(eye) > RANGE_SQ) {
                continue
            }

            val position = entity.interpolation?.position() ?: entity.position()
            val log = logs.getOrPut(entity.id) { Log() }
            log.lastTouchedTick = tick

            val last = log.samples.lastOrNull()
            if (last != null && last.position == position) {
                continue
            }

            // A position that moved without a packet (a local push, a vehicle) carries no arrival:
            // treat it as fresh rather than reuse the stamp of the packet before it.
            val arrived = arrivals[entity.id]?.takeIf { it > (last?.arrivalNanos ?: Long.MIN_VALUE) } ?: now
            log.samples.addLast(Sample(position, arrived, tick))
            while (log.samples.size > MAX_SAMPLES) {
                log.samples.removeFirst()
            }
        }

        if (tick % PRUNE_INTERVAL_TICKS == 0) {
            logs.values.removeIf { tick - it.lastTouchedTick > PRUNE_AGE_TICKS }
            arrivals.keys.removeIf { !logs.containsKey(it) }
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        logs.clear()
        arrivals.clear()
    }

    /**
     * The server-side motion of [entity] as of now (see the class KDoc).
     */
    fun motion(entity: Entity): Motion {
        val samples = logs[entity.id]?.samples
        val latest = samples?.lastOrNull()
            ?: return Motion(
                entity.interpolation?.position() ?: entity.position(),
                entity.position().subtract(entity.lastPos),
                UNKNOWN_SINCE_TICKS,
                fromPackets = false
            )

        val tick = player.tickCount
        val interval = entity.type.updateInterval().coerceAtLeast(1)
        val idleTicks = tick - latest.tick

        val velocity = if (idleTicks > interval + 1) {
            // A move would have been reported by now: the entity stands still.
            Vec3.ZERO
        } else {
            // Walk back over consecutive packets (each at most one interval plus a tick of jitter
            // apart) and average over the server's own cadence, not the arrival spacing.
            var first = latest
            var count = 1
            var index = samples.size - 2
            while (index >= 0 && count < VELOCITY_WINDOW) {
                val earlier = samples[index]
                if (first.tick - earlier.tick > interval + 1) {
                    break
                }
                first = earlier
                count++
                index--
            }

            if (count < 2) {
                entity.position().subtract(entity.lastPos)
            } else {
                latest.position.subtract(first.position).scale(1.0 / ((count - 1) * interval))
            }
        }

        val since = ((System.nanoTime() - latest.arrivalNanos) / NANOS_PER_TICK).coerceIn(0.0, MAX_SINCE_TICKS)
        return Motion(latest.position, velocity, since, fromPackets = true)
    }

    /** Above RotationManager's FIRST_PRIORITY (1000) and SilentHotbar (1001). */
    private const val OBSERVE_PRIORITY: Short = 1010

    private const val MAX_SAMPLES = 4
    private const val VELOCITY_WINDOW = 3
    private const val RANGE_SQ = 96.0 * 96.0
    private const val PRUNE_INTERVAL_TICKS = 40
    private const val PRUNE_AGE_TICKS = 100
    private const val NANOS_PER_TICK = 50_000_000.0

    /** The staleness assumed for an entity the log has not seen a packet of: half an interval. */
    private const val UNKNOWN_SINCE_TICKS = 0.5

    /** A position older than this is a standing entity; the number stops mattering. */
    private const val MAX_SINCE_TICKS = 10.0

}
