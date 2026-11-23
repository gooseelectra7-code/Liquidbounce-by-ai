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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.net

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.state.AnchorLedger
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.world.damagesource.DamageTypes
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Hand-over of the incoming packets the anchor aura consumes from the netty thread to the main thread.
 *
 * `PacketEvent` for incoming packets fires at the HEAD of `Connection.genericsFtw` on the netty thread
 * (`src/main/java/net/ccbluex/liquidbounce/injection/mixins/minecraft/network/MixinConnection.java:66-91`), before
 * vanilla's handler hops to the main thread via `PacketUtils.ensureRunningOnSameThread`
 * (`net/minecraft/client/multiplayer/ClientPacketListener.java:576, 788, 1232, 1363`). [AnchorLedger] is main
 * thread only, so [offer] parks the packets in a [ConcurrentLinkedQueue] (the pattern of
 * `features/module/modules/render/ModuleTotemPopESP.kt:84-118`) and [drainInto] replays them, in wire order, into
 * the ledger. Wire order matters here for the same reason it does for the crystal aura: the explosion packet and
 * the damage events it caused arrive back to back, and the ledger's grace memory is what makes a late damage event
 * still attributable (`state/AnchorLedger.kt`, class KDoc). The queue is FIFO and [offer] is called synchronously
 * from the single netty read path, so replaying it preserves that order exactly.
 *
 * Bundles need no handling: `MixinConnection.hookReceivingPacket` cancels the `ClientboundBundlePacket` itself and
 * re-enters `genericsFtw` once per sub-packet (`MixinConnection.java:68-84`), so every sub-packet produces its own
 * `PacketEvent` and the bundle never reaches [offer].
 *
 * ### The two packets, and why they are the anchor aura's whole incoming story
 * The client predicts the placement and the charge and can simply read both back off the blockstate. It predicts
 * NOTHING of the detonation - the client branch of `RespawnAnchorBlock.useWithoutItem` is
 * `return InteractionResult.CONSUME` (`net/minecraft/world/level/block/RespawnAnchorBlock.java:120-122`) and
 * `ClientLevel.explode` has an empty body (`net/minecraft/client/multiplayer/ClientLevel.java:1045-1060`). So the
 * only evidence that a detonation worked arrives over the wire:
 *
 * - `ClientboundExplodePacket`, a record whose FIRST component is `Vec3 center`
 *   (`net/minecraft/network/protocol/game/ClientboundExplodePacket.java:17-25`), sent verbatim to every player
 *   within 64 blocks (`distanceToSqr(center) < 4096.0`, `net/minecraft/server/level/ServerLevel.java:1220-1229`).
 *   Fed to [AnchorLedger.onExplosion], which matches it against our detonation centres by EXACT double
 *   comparison - legitimate because both sides are `pos.getCenter()` of the same block.
 * - `ClientboundDamageEventPacket`, the record
 *   `(int entityId, Holder<DamageType> sourceType, int sourceCauseId, int sourceDirectId,
 *   Optional<Vec3> sourcePosition)` (`ClientboundDamageEventPacket.java:16`). Fed to
 *   [AnchorLedger.onDamageEvent] as `(entityId, sourceType.is(BAD_RESPAWN_POINT), sourcePosition.orElse(null))`.
 *   The entity ids are useless for an anchor: `badRespawnPointExplosion(boomPos)` is
 *   `new DamageSource(type, boomPos)` with no causing and no direct entity
 *   (`net/minecraft/world/damagesource/DamageSources.java:278-280`), so both encode as `-1`
 *   (`ClientboundDamageEventPacket.java:26-27, 46-48`). The POSITION is the identifier instead, and it is exact:
 *   `sourcePositionRaw()` is the same `boomPos` the explosion centre is built from.
 *
 * The crystal aura's `ClientboundAddEntityPacket` / `ClientboundRemoveEntitiesPacket` handling is dropped
 * outright. It existed to track end crystal ENTITIES - their ids, their spawn positions and the chain removals of
 * one blast. An anchor is a BLOCK: ownership is decided by "our outgoing `UseItemOn` named the cell AND an anchor
 * later stands there", and its disappearance is read off the blockstate by the module hub
 * ([AnchorLedger.onAnchorGone]), not off an entity removal.
 *
 * Because the event precedes vanilla's own handling, a packet drained at the start of a tick describes a change
 * the client level has not applied yet. Neither of the two carries anything the ledger has to resolve against the
 * world, so that is harmless here. The caller's `PacketEvent` handler should forward `TransferOrigin.INCOMING`
 * packets only; an outgoing packet can never be one of the clientbound types above, so it would be dropped anyway.
 *
 * Threading: [offer] is called on the netty thread; [drainInto], [explosions] and [reset] belong to the main
 * thread.
 */
class PacketIntake {

    private val queue = ConcurrentLinkedQueue<Packet<*>>()

    /** Total `ClientboundExplodePacket`s drained since the last [reset]; main thread, stats only. */
    var explosions: Int = 0
        private set

    /** NETTY THREAD. Enqueues only the packet types [drainInto] consumes; touches nothing else. */
    fun offer(packet: Packet<*>) {
        if (packet is ClientboundExplodePacket || packet is ClientboundDamageEventPacket) {
            queue.add(packet)
        }
    }

    /**
     * MAIN THREAD. Replays every queued packet, in arrival order, into [ledger]. Call it once per tick right after
     * [AnchorLedger.onTickStart] so the ledger stamps the packets with the current tick.
     */
    fun drainInto(ledger: AnchorLedger) {
        while (true) {
            val packet = queue.poll() ?: break
            when (packet) {
                is ClientboundExplodePacket -> {
                    explosions++
                    ledger.onExplosion(packet.center(), packet.radius())
                }
                is ClientboundDamageEventPacket -> ledger.onDamageEvent(
                    packet.entityId(),
                    packet.sourceType().`is`(DamageTypes.BAD_RESPAWN_POINT),
                    packet.sourcePosition().orElse(null),
                )
                else -> {}
            }
        }
    }

    /** Drops everything still queued and zeroes [explosions]; call on world change and when the module is disabled. */
    fun reset() {
        queue.clear()
        explosions = 0
    }
}
