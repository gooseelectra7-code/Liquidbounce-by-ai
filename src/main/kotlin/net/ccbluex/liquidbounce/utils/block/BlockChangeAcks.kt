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

import net.minecraft.client.multiplayer.ClientLevel
import java.lang.ref.WeakReference

/**
 * The newest `ClientboundBlockChangedAckPacket` sequence the client has PROCESSED, and the level it belongs to.
 *
 * The server answers every sequenced block action - `STOP_DESTROY_BLOCK` among them - with an ack of the highest
 * sequence it has handled (`ServerGamePacketListenerImpl.java:1290-1291`), sent from its `tick()` (`:294-296`),
 * i.e. after the block updates that action caused. So once the ack covering a packet has been processed, the
 * client's world shows the server's answer to that packet.
 *
 * Fed from `MixinClientLevel` at the return of `ClientLevel.handleBlockChangedAck`, which runs on the MAIN thread:
 * `ClientPacketListener.handleBlockChangedAck` re-dispatches through `PacketUtils.ensureRunningOnSameThread`
 * (`ClientPacketListener.java:2433-2436`). An incoming `PacketEvent` cannot stand in for it - that fires on the
 * netty thread, before the packet (and the block updates queued ahead of it) has been applied.
 *
 * Main thread only. Sequence numbers are counted per `ClientLevel` - each has its own
 * `BlockStatePredictionHandler` (`ClientLevel.java:159`), starting from 0 - hence the level identity next to the
 * number.
 *
 * An ack that exceeds what the level has sent ([sentSoFar]) belongs to the PREVIOUS level and is dropped. A dimension
 * change builds a new `ClientLevel` (`ClientPacketListener.java:1263-1283`) the moment the respawn packet is handled,
 * while the server only sends acks from its connection tick (`ServerGamePacketListenerImpl.java:294-296`) - so the ack
 * for actions sent just before a portal lands on the new level. Kept, it would read as "everything up to N answered"
 * for a level whose counter restarted at 0, and every gate built on this would stay open there.
 */
object BlockChangeAcks {

    private var level: WeakReference<ClientLevel>? = null
    private var sequence = -1

    @JvmStatic
    fun onProcessed(level: ClientLevel, sequence: Int, sentSoFar: Int) {
        if (sequence > sentSoFar) {
            return
        }
        if (this.level?.get() !== level) {
            this.level = WeakReference(level)
            this.sequence = sequence
        } else if (sequence > this.sequence) {
            this.sequence = sequence
        }
    }

    /** Whether the server's answer to the action sent with [sequence] in [level] has been processed. */
    fun isProcessed(level: ClientLevel, sequence: Int): Boolean =
        this.level?.get() === level && this.sequence >= sequence

}
