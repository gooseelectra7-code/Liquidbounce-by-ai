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

package net.ccbluex.liquidbounce.utils.block

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientBlockPlaceEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import java.lang.ref.WeakReference

/**
 * Blocks our own client placed that the server has not answered yet - the ones Grim may not hold.
 *
 * The client draws a placed block the moment it predicts it; Grim does not. Its `AirLiquidPlace` (not experimental,
 * cancel VL 0) reads the CLICKED block out of Grim's own world on every `PLAYER_BLOCK_PLACEMENT`, at packet receipt
 * (`checks/impl/scaffolding/AirLiquidPlace.java:58-85`, `events/packets/CheckManagerListener.java:515`), and Grim
 * writes our placement into that world only when it drains it - and not at all when our box or a tracked entity's box
 * touches the block, when an entity the client already removed is still in Grim's world (it goes one transaction
 * after its destroy packet), or when a check cancelled the placement (`utils/anticheat/update/BlockPlace.java:546-596`,
 * `events/packets/PacketEntityReplication.java:393-401`, `CheckManagerListener.java:517-528`). In those cases the
 * block reaches Grim's world only with the server's block update, about one round trip later, and a click on it
 * before then - charging an anchor we just placed, say - is a click on air. None of those conditions can be read
 * from the client, so the only exact answer is to wait for the server's.
 *
 * That answer is the `ClientboundBlockChangedAckPacket` covering the placement's sequence ([BlockChangeAcks]). The
 * server sends it from its next connection tick (`ServerGamePacketListenerImpl.java:293-297`), after the block
 * updates it sent while handling the click (`:1361-1362`), and Grim applies such an update at our pong for the
 * transaction bound to it (`events/packets/worldreader/BasePacketWorldReader.java:155-166`). So once the ack is
 * processed, that pong has already left ahead of anything we send next. The chain assumes pongs flow: `PingSpoof`
 * (holds incoming pings) and Timer `SlowBoost` (holds outgoing pongs) break it.
 *
 * ### How a placement gets its sequence
 * [ClientBlockPlaceEvent] fires from `BlockItem.place` (`MixinBlockItem`), which runs inside
 * `MultiPlayerGameMode.startPrediction`'s `predict` - BEFORE that call sends the `ServerboundUseItemOnPacket` it
 * builds (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:289-295`, `:325-328`). The next outgoing `UseItemOn` is therefore that very packet, and its sequence is taken from it. Both
 * happen on the main thread in one call chain, and `EventManager.callEvent` runs every handler whether or not an
 * earlier one cancelled the event, so a packet another module holds back (Blink) is still seen - and its ack simply
 * comes later.
 *
 * Main thread only. Entries are counted per [ClientLevel], like [BlockChangeAcks]; [TIMEOUT_MS] is the backstop for
 * a sequence that was never seen and for a connection whose answers stall.
 */
object PendingPlacements : EventListener {

    override val running: Boolean
        get() = super.running && inGame

    /** A placement of ours: the `UseItemOn` [sequence] ([NO_SEQUENCE] until its packet is seen) and when it left. */
    private class Entry(var sequence: Int, val placedAtMs: Long)

    private val entries = HashMap<BlockPos, Entry>()

    /** The cell of the placement whose packet has not been seen yet; see the class KDoc. */
    private var unsequenced: BlockPos? = null

    private var level: WeakReference<ClientLevel>? = null

    @Suppress("unused")
    private val placeHandler = handler<ClientBlockPlaceEvent> { event ->
        syncLevel()
        entries.values.removeIf(::isSettled)
        entries[event.blockPos] = Entry(NO_SEQUENCE, System.currentTimeMillis())
        unsequenced = event.blockPos
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (event.origin != TransferOrigin.OUTGOING || packet !is ServerboundUseItemOnPacket) {
            return@handler
        }

        val pos = unsequenced ?: return@handler
        unsequenced = null
        entries[pos]?.sequence = packet.sequence
    }

    /**
     * Whether a block our client placed at [pos] is still waiting for the server's answer - i.e. whether a click on
     * it now could reach Grim before Grim holds the block (class KDoc). False for anything we did not place.
     */
    fun isAwaitingServer(pos: BlockPos): Boolean {
        syncLevel()
        val entry = entries[pos] ?: return false
        if (isSettled(entry)) {
            entries.remove(pos)
            return false
        }
        return true
    }

    private fun isSettled(entry: Entry): Boolean {
        val level = mc.level ?: return true
        val sequence = entry.sequence
        val answered = sequence != NO_SEQUENCE && BlockChangeAcks.isProcessed(level, sequence)
        return answered || System.currentTimeMillis() - entry.placedAtMs > TIMEOUT_MS
    }

    /** Sequences restart with every [ClientLevel], so the entries of another one mean nothing here. */
    private fun syncLevel() {
        val current = mc.level
        if (level?.get() !== current) {
            entries.clear()
            unsequenced = null
            level = current?.let(::WeakReference)
        }
    }

    /**
     * No sequence seen. Real ones start at 1: the counter starts at 0 and is incremented before use
     * (`net/minecraft/client/multiplayer/prediction/BlockStatePredictionHandler.java:55-56`).
     */
    private const val NO_SEQUENCE = -1

    /**
     * How long a placement may wait for its answer before it stops counting. An ack normally arrives within a round
     * trip plus one server tick, so reaching this means it is not coming (a sequence never seen) or the connection
     * is stalling.
     */
    private const val TIMEOUT_MS = 2000L
}
