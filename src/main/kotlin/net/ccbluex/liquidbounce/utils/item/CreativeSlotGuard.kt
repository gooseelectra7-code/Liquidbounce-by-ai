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
package net.ccbluex.liquidbounce.utils.item

import com.mojang.authlib.GameProfile
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket
import net.minecraft.util.StringUtil
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile

/**
 * Keeps a creative slot update from killing the connection.
 *
 * A player head carries its skin in the `minecraft:profile` component, and that component's codec
 * VALIDATES the profile's player name when it is DECODED ([StringUtil.isValidPlayerName]). Heads
 * whose profile is named like a decoration - `Candy Bowl`, `Packed Ice Block` - therefore encode
 * fine on our side and then blow up on the receiving one:
 *
 * ```
 * io.netty.handler.codec.DecoderException: Failed to decode packet 'serverbound/minecraft:set_creative_mode_slot'
 * Caused by: ... Player name contained disallowed characters: 'Candy Bowl'
 *     at net.minecraft.world.item.ItemStack$3.decode(ItemStack.java:203)
 * ```
 *
 * The server drops the connection on that (observed 2026-10-26 in singleplayer: picking such a head
 * up in creative kicked the player out of the world, twice). It is not ours - vanilla's own creative
 * screen sends that packet - but every module that moves items in creative goes through the same
 * packet ([net.ccbluex.liquidbounce.utils.inventory.InventoryAction.Creative] and
 * [setInventoryItemCreative]), so the guard sits on the packet rather than on any one caller.
 *
 * The repair keeps the item usable instead of dropping the update: the profile is rebuilt with the
 * same id and the same properties (the `textures` property is what actually draws the head) under a
 * sanitized name, so the head still looks like itself.
 *
 * KNOWN LIMITS:
 * - [ResolvableProfile]'s only public factories are `createResolved` / `createUnresolved`, so a
 *   profile's skin PATCH is not carried over; a head whose texture lives in the patch rather than in
 *   the profile's properties comes back plain.
 * - A profile with no id is rebuilt as an unresolved one under the sanitized name, which is a
 *   different player for the server to resolve - cosmetic, and the alternative is the kick.
 */
object CreativeSlotGuard : EventListener {

    /** Used when a name has no usable characters at all. */
    private const val FALLBACK_NAME = "Head"

    /** Vanilla accepts at most 16 characters, and only these. */
    private const val MAX_NAME_LENGTH = 16
    private val DISALLOWED = Regex("[^A-Za-z0-9_]")

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }

        val packet = event.packet as? ServerboundSetCreativeModeSlotPacket ?: return@handler
        val repaired = repair(packet.itemStack) ?: return@handler

        // The original would be refused by the receiver's decoder, so it is replaced rather than
        // merely dropped - a dropped update would leave the slot out of sync instead.
        event.cancelEvent()
        sendPacketSilently(ServerboundSetCreativeModeSlotPacket(packet.slotNum, repaired))
    }

    /**
     * A copy of [stack] the receiving side can decode, or null when [stack] is already fine - which
     * is every stack without a profile, and every profile whose name vanilla would accept.
     */
    fun repair(stack: ItemStack): ItemStack? {
        val profile = stack.get(DataComponents.PROFILE) ?: return null
        val name = profile.name().orElse(null) ?: return null

        if (StringUtil.isValidPlayerName(name)) {
            return null
        }

        val partial = profile.partialProfile()
        val sanitized = name.replace(DISALLOWED, "").take(MAX_NAME_LENGTH).ifEmpty { FALLBACK_NAME }
        val id = partial.id()

        return stack.copy().apply {
            set(
                DataComponents.PROFILE,
                if (id != null) {
                    ResolvableProfile.createResolved(GameProfile(id, sanitized, partial.properties()))
                } else {
                    ResolvableProfile.createUnresolved(sanitized)
                }
            )
        }
    }
}
