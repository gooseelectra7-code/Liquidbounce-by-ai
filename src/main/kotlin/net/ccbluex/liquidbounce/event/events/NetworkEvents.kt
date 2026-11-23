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

package net.ccbluex.liquidbounce.event.events

import io.netty.channel.ChannelPipeline
import net.ccbluex.liquidbounce.annotations.Tag
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.CancellableEvent
import net.ccbluex.liquidbounce.event.Event
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet

@Tag("pipeline")
class PipelineEvent(val channelPipeline: ChannelPipeline, val local: Boolean) : Event()

@Tag("packet")
class PacketEvent @JvmOverloads constructor(
    val origin: TransferOrigin,
    val packet: Packet<*>,
    val original: Boolean = true,
    /**
     * The listener this packet was dispatched to/from. For [TransferOrigin.INCOMING] packets, this is the
     * listener currently active on the connection, which may be the configuration-phase listener rather than
     * [net.minecraft.client.Minecraft.getConnection] (only set once the play phase has started). Handlers that
     * need to send a response for a packet received before the play phase must use this listener instead of
     * relying on the game connection, which is null at that point.
     */
    val listener: PacketListener? = null,
) : CancellableEvent()

@Tag("queuePacket")
class BlinkPacketEvent(
    val packet: Packet<*>?,
    val origin: TransferOrigin
) : Event() {

    var action: BlinkManager.Action = BlinkManager.Action.FLUSH
        set(value) {
            if (field == value || field.priority >= value.priority) {
                return
            }

            field = value
        }

}

enum class TransferOrigin(override val tag: String) : Tagged {
    INCOMING("Incoming"),
    OUTGOING("Outgoing");
}
