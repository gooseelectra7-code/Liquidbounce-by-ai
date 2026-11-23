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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.net

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.world.entity.EntityType
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hand-over of the incoming packets the crystal aura consumes from the netty thread to the main thread.
 *
 * `PacketEvent` for incoming packets fires at the HEAD of `Connection.genericsFtw` on the netty thread
 * (`src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/network/MixinConnection.java:66-91`), before
 * vanilla's handler hops to the main thread via `PacketUtils.ensureRunningOnSameThread`
 * (`net/minecraft/client/multiplayer/ClientPacketListener.java:576, 788, 1232, 1363`). The [CrystalLedger] is main
 * thread only, so [offer] parks the packets in a [ConcurrentLinkedQueue] (the pattern of
 * `features/module/modules/render/ModuleTotemPopESP.kt:84-118`) and [drainInto] replays them, in wire order, into the
 * ledger. Wire order matters: the ledger relies on a hit crystal's removal arriving before the damage events that
 * name it (`state/CrystalLedger.kt`, class KDoc), and the queue is FIFO while [offer] is called synchronously from the
 * single netty read path.
 *
 * Bundles need no handling here: `MixinConnection.hookReceivingPacket` cancels the `ClientboundBundlePacket` itself
 * and re-enters `genericsFtw` once per sub-packet (`MixinConnection.java:68-84`), so every sub-packet produces its
 * own `PacketEvent` and the bundle never reaches [offer].
 *
 * Packets consumed (all others are dropped by [offer] without touching the queue):
 * - `ClientboundAddEntityPacket` (`net/minecraft/network/protocol/game/ClientboundAddEntityPacket.java:132-154`:
 *   `getId()`, `getType()`, `getX()`, `getY()`, `getZ()`; the position is decoded as raw doubles, `:99-101`, so a
 *   placed crystal arrives at exactly `(above.x + 0.5, above.y, above.z + 0.5)`, `EndCrystalItem.java:42`) →
 *   [CrystalLedger.onSpawn] with `isCrystal = type == EntityType.END_CRYSTAL` (`EntityType.java:416-424`).
 * - `ClientboundRemoveEntitiesPacket` (`ClientboundRemoveEntitiesPacket.java:41-43`: `getEntityIds(): IntList`) →
 *   [CrystalLedger.onRemoved] per id.
 * - `ClientboundDamageEventPacket` (record `(int entityId, Holder<DamageType> sourceType, int sourceCauseId,
 *   int sourceDirectId, Optional<Vec3> sourcePosition)`, `ClientboundDamageEventPacket.java:16`; an absent entity is
 *   encoded as `-1`, `:26-27, 46-48`) → [CrystalLedger.onDamageEvent].
 * - `ClientboundExplodePacket` (`ClientboundExplodePacket.java:17-25`), sent to every player within 64 blocks of the
 *   center (`ServerLevel.java:1225-1229`, `distanceToSqr(center) < 4096.0`) → only counted in [explosions].
 *
 * Because the event precedes vanilla's own handling, a spawn drained at the start of a tick may describe an entity
 * that `level.getEntity(id)` does not return yet; the ledger is id/position bookkeeping and does not care, callers
 * that resolve entities must. The caller's `PacketEvent` handler should forward `TransferOrigin.INCOMING` packets
 * only; an outgoing packet can never be one of the clientbound types above, so it would be dropped anyway.
 *
 * Threading: [offer] is called on the netty thread; [drainInto], [explosions] and [reset] belong to the main thread.
 */
class PacketIntake {

    private val queue = ConcurrentLinkedQueue<Packet<*>>()

    /** Total `ClientboundExplodePacket`s drained since the last [reset]; main thread, stats only. */
    var explosions: Int = 0
        private set

    /** NETTY THREAD. Enqueues only the packet types [drainInto] consumes; touches nothing else. */
    fun offer(packet: Packet<*>) {
        if (isConsumed(packet)) {
            queue.add(packet)
        }
    }

    /**
     * MAIN THREAD. Replays every queued packet, in arrival order, into [ledger]; [selfId] is the local player's
     * entity id compared against `sourceCauseId` by [CrystalLedger.onDamageEvent]. Call it once per tick right after
     * [CrystalLedger.onTickStart] so the ledger stamps the packets with the current tick.
     */
    fun drainInto(ledger: CrystalLedger, selfId: Int) {
        while (true) {
            val packet = queue.poll() ?: break
            when (packet) {
                is ClientboundAddEntityPacket -> ledger.onSpawn(
                    packet.id,
                    packet.type == EntityType.END_CRYSTAL,
                    packet.x,
                    packet.y,
                    packet.z,
                )
                is ClientboundRemoveEntitiesPacket -> removeAll(ledger, packet)
                is ClientboundDamageEventPacket -> ledger.onDamageEvent(
                    packet.entityId(),
                    packet.sourceCauseId(),
                    packet.sourceDirectId(),
                    selfId,
                )
                is ClientboundExplodePacket -> explosions++
            }
        }
    }

    /** Drops everything still queued and zeroes [explosions]; call on world change and when the module is disabled. */
    fun reset() {
        queue.clear()
        explosions = 0
    }

    /** `getEntityIds()` is a fastutil `IntList`; indexed `getInt` avoids boxing an iterator per id. */
    private fun removeAll(ledger: CrystalLedger, packet: ClientboundRemoveEntitiesPacket) {
        val ids = packet.entityIds
        for (index in 0 until ids.size) {
            ledger.onRemoved(ids.getInt(index))
        }
    }

    private fun isConsumed(packet: Packet<*>): Boolean =
        packet is ClientboundAddEntityPacket ||
            packet is ClientboundRemoveEntitiesPacket ||
            packet is ClientboundDamageEventPacket ||
            packet is ClientboundExplodePacket
}
