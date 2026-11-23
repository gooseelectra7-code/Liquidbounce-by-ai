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

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.ModuleAutoBow
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.ping
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.TridentItem
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures how long a shot of ours takes to happen on the server, from our own shots.
 *
 * ## What is measured
 *
 * The time from the packet that fires (a `ServerboundUseItemPacket` on a charged crossbow, a
 * `RELEASE_USE_ITEM` on a drawn bow or trident) to the `ClientboundAddEntityPacket` of the
 * projectile it spawned - the one whose owner (`packet.data`, `Projectile.getAddEntityPacket`,
 * `Projectile.java:350-352`) is us. The server spawns the projectile while it processes our
 * packet and sends the spawn right there (`ServerEntity.addPairing`, `ServerEntity.java:255-258`),
 * so the interval is the uplink latency, the wait until the server gets to our packet, and the
 * downlink latency: the whole delay between our decision and the arrow existing, with the server's
 * own lag included.
 *
 * ## Why it is the right number for the lead
 *
 * The target's position we aim from was sent by the server at some instant and reached us over the
 * same downlink. So `(now - arrival of the target's packet) + (this delay)` is, on the server's
 * clock, the time from the target's position being read to our arrow being spawned - the downlink
 * cancels out of the difference, and no half-round-trip assumption is needed. See
 * [AutoBowAimbotFeature.predictionFor].
 *
 * Until a shot has been measured, [delayTicks] is null and callers fall back to the tab-list
 * latency, which the server only broadcasts every 600 ticks (`PlayerList.java:470-471`) and which
 * misses the processing wait.
 *
 * Threading: our shot packets are sent from the main thread, the spawn arrives on netty; the
 * sample ring is guarded by [lock]. [parent] is [ModuleAutoBow].
 */
object ShotDelayMeter : EventListener {

    override fun parent(): EventListener = ModuleAutoBow

    /** `System.nanoTime()` of each shot packet not yet matched to a spawn, oldest first. */
    private val pendingShots = ConcurrentLinkedQueue<Long>()

    private val lock = Any()

    /** Measured delays in ticks, newest last. Guarded by [lock]. */
    private val samples = ArrayDeque<Double>()

    @Volatile
    private var lastMatchNanos = 0L

    /**
     * Forces the object's initialisation - and with it the registration of its handlers - from the
     * owning module.
     */
    fun register() {}

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet

        if (event.origin == TransferOrigin.OUTGOING) {
            // Not gated on isCancelled: FakeLag cancels the event to hold the packet and sends it
            // later without one, and that hold IS part of the delay being measured.
            if (isShot(packet)) {
                pendingShots += System.nanoTime()
            }
            return@handler
        }

        if (packet !is ClientboundAddEntityPacket || !isProjectileType(packet.type)) {
            return@handler
        }

        val owner = mc.player?.id ?: return@handler
        if (packet.data != owner) {
            return@handler
        }

        val now = System.nanoTime()

        // A multishot crossbow spawns three projectiles in one bundle; only the first pairs with a shot.
        if (now - lastMatchNanos < MULTISHOT_WINDOW_NANOS) {
            return@handler
        }

        var shot: Long? = null
        while (true) {
            val oldest = pendingShots.poll() ?: break
            if (now - oldest <= MAX_PENDING_NANOS) {
                shot = oldest
                break
            }
        }

        val fired = shot ?: return@handler
        lastMatchNanos = now

        synchronized(lock) {
            samples.addLast((now - fired) / NANOS_PER_TICK)
            while (samples.size > MAX_SAMPLES) {
                samples.removeFirst()
            }
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        pendingShots.clear()
        synchronized(lock) { samples.clear() }
    }

    /**
     * Whether [packet], about to leave, fires a projectile of ours. Main thread (our own sends).
     */
    private fun isShot(packet: Any): Boolean = when (packet) {
        is ServerboundUseItemPacket -> player.getItemInHand(packet.hand).let {
            it.item is CrossbowItem && CrossbowItem.isCharged(it)
        }

        // The hook runs inside send(), before MultiPlayerGameMode.releaseUsingItem stops the use.
        is ServerboundPlayerActionPacket -> packet.action == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM &&
            player.isUsingItem &&
            when (player.useItem.item) {
                // BowItem.releaseUsing shoots from a power of 0.1, i.e. from the third tick.
                is BowItem -> player.ticksUsingItem >= MIN_BOW_TICKS
                // TridentItem.releaseUsing throws from the tenth.
                is TridentItem -> player.ticksUsingItem >= MIN_TRIDENT_TICKS
                else -> false
            }

        else -> false
    }

    private fun isProjectileType(type: EntityType<*>): Boolean =
        type === EntityType.ARROW || type === EntityType.SPECTRAL_ARROW ||
            type === EntityType.TRIDENT || type === EntityType.FIREWORK_ROCKET

    /**
     * The measured shot delay in ticks (mean of the last shots), or null before the first one.
     */
    fun delayTicks(): Double? = synchronized(lock) {
        if (samples.isEmpty()) null else samples.average()
    }

    /**
     * How much the measured delay wanders, in ticks: the standard deviation of the last shots,
     * floored at [MIN_JITTER_TICKS] - the server processes a packet somewhere inside a tick even on
     * a perfect connection.
     */
    fun jitterTicks(): Double = synchronized(lock) {
        if (samples.size < 2) {
            return MIN_JITTER_TICKS
        }

        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / (samples.size - 1)
        max(MIN_JITTER_TICKS, sqrt(variance))
    }

    /**
     * The delay to assume before any shot was measured: the tab-list round trip plus the mean
     * wait until the server reaches a packet inside its tick.
     */
    fun fallbackDelayTicks(): Double = player.ping / MILLISECONDS_PER_TICK + FALLBACK_PROCESSING_TICKS

    /**
     * One line for the debug channel.
     */
    fun describe(): String = synchronized(lock) {
        if (samples.isEmpty()) {
            "shot delay: none measured, assuming %.1f ticks from ping".format(fallbackDelayTicks())
        } else {
            "shot delay: %.1f ticks (+-%.1f, %d shots)".format(samples.average(), jitterTicks(), samples.size)
        }
    }

    private const val MAX_SAMPLES = 8
    private const val NANOS_PER_TICK = 50_000_000.0
    private const val MILLISECONDS_PER_TICK = 50.0
    private const val MAX_PENDING_NANOS = 3_000_000_000L
    private const val MULTISHOT_WINDOW_NANOS = 20_000_000L
    private const val MIN_BOW_TICKS = 3
    private const val MIN_TRIDENT_TICKS = 10
    private const val MIN_JITTER_TICKS = 0.5
    private const val FALLBACK_PROCESSING_TICKS = 0.5

}
