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

package net.ccbluex.liquidbounce.utils.inventory

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack

/**
 * Tracks how far the client's own view of the OFFHAND slot can be trusted — the "ghost totem"
 * problem: a totem rendered in the offhand that the server does not have, and therefore will not
 * pop.
 *
 * Container clicks are predicted locally. `MultiPlayerGameMode.handleContainerInput` runs
 * `containerMenu.clicked(...)` (`MultiPlayerGameMode.java:452`) BEFORE it sends the packet
 * (`:464-469`), so the client shows the result of a click the server has not seen yet. What the
 * server does with that click is deeply asymmetric:
 *
 * - **Agreement is silent.** `handleContainerClick` forces the server's `remoteSlots` to the
 *   `changedSlots` we claimed (`ServerGamePacketListenerImpl.java:1909-1913`) and only then calls
 *   `broadcastChanges` (`:1918`), which emits a slot exclusively when it diverges from
 *   `remoteSlots` (`AbstractContainerMenu.java:198-203`, `:253-256`). There is NO "click accepted"
 *   packet: success can only ever be inferred from silence, never confirmed. Neither is there a
 *   side channel — a player is excluded from its own entity tracker (`ChunkMap.java:1379`,
 *   `if (player != this.entity)`), so no `SetEquipment` about our own offhand ever arrives, and
 *   `ServerPlayer.doCloseContainer` (`:1408-1412`) does not resync either.
 * - **Disagreement arrives late.** A correction is one `ClientboundContainerSetSlotPacket`, or a
 *   full `ClientboundContainerSetContentPacket` when the state id did not match (`:1915-1916`),
 *   i.e. roughly one round trip after the click.
 * - **Three branches drop the click and answer NOTHING**: a container id that does not match the
 *   server's own menu (`:1893`), `!stillValid` (`:1896`) and an out-of-range slot index (`:1900`).
 *   The local prediction then stands forever.
 *
 * So this exposes two booleans instead of a fictional confirmation: [isUnconfirmed] (the offhand
 * may not exist server-side yet) and [isSuspectedDrop] (the click most likely hit one of the three
 * silent branches, so no correction is coming and re-clicking is the only cure).
 *
 * On the container id: a SWAP with button 40 writes straight into the player's inventory
 * (`AbstractContainerMenu.java:477-478`, `inventory.getItem(buttonNum)`) while `broadcastChanges`
 * only walks the OPEN menu's slot list — and [InventoryMenu.SHIELD_SLOT] exists only in
 * [InventoryMenu]. An offhand swap sent while a foreign container is open can therefore be neither
 * confirmed nor corrected while that container stays open, and stays reported as unconfirmed.
 *
 * Threading: incoming [PacketEvent]s are fired from the netty thread at the head of
 * `Connection.genericsFtw` (`MixinConnection.java:66-90`), before the packet reaches the main
 * thread. Hence the volatile fields, and hence the comparison being packet-against-stored-copy
 * rather than packet-against-live-inventory.
 */
object OffhandSyncTracker : EventListener {

    override val running: Boolean
        get() = super.running && inGame

    /** Floor of the confirmation window — one server tick, for a singleplayer/0-ping connection. */
    private const val MIN_CONFIRM_MS = 50L

    /** Ceiling of the confirmation window; past it a silent click is written off as accepted. */
    private const val MAX_CONFIRM_MS = 1000L

    /** Head room on top of the measured ping for the server's own tick granularity. */
    private const val CONFIRM_MARGIN_MS = 100L

    /** How long a fresh piece of server truth keeps [hadRecentServerTruth] up. */
    private const val TRUTH_REACT_MS = 150L

    private val sinceClick = Chronometer(0L)
    private val sinceTruth = Chronometer(0L)

    @Volatile
    private var pending = false

    @Volatile
    private var pendingContainerId = InventoryMenu.CONTAINER_ID

    /** The client's offhand right after the prediction of the pending click. */
    @Volatile
    private var predicted: ItemStack = ItemStack.EMPTY

    /**
     * The pending click most likely hit one of the three silent-drop branches of
     * `handleContainerClick`, so the desync will never be corrected on its own.
     */
    @Volatile
    var isSuspectedDrop = false
        private set

    /** How often the server's truth contradicted our prediction — a diagnostic counter only. */
    @Volatile
    var contradictions = 0
        private set

    /** Whether the client's offhand may not exist server-side right now. */
    val isUnconfirmed: Boolean
        get() = when {
            isSuspectedDrop -> true
            !pending -> false
            // No correction can reach us for the offhand while a foreign menu is open (see KDoc).
            pendingContainerId != InventoryMenu.CONTAINER_ID -> true
            else -> sinceClick.elapsed < confirmWindow()
        }

    /**
     * The server told us the truth about the offhand just now. Worth outranking a caller's own
     * pacing: the correction that just landed may be exactly the ghost it was waiting for, and a
     * legitimate totem pop looks the same from here.
     */
    val hadRecentServerTruth: Boolean
        get() = sinceTruth.elapsed < TRUTH_REACT_MS

    private fun confirmWindow(): Long =
        ((mc.player?.ping ?: 0).toLong() + CONFIRM_MARGIN_MS).coerceIn(MIN_CONFIRM_MS, MAX_CONFIRM_MS)

    fun reset() {
        pending = false
        pendingContainerId = InventoryMenu.CONTAINER_ID
        predicted = ItemStack.EMPTY
        isSuspectedDrop = false
        sinceClick.reset(0L)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(priority = EventPriorityConvention.READ_FINAL_STATE) { event ->
        if (event.isCancelled) {
            return@handler
        }

        when (val packet = event.packet) {
            is ServerboundContainerClickPacket -> {
                if (event.origin != TransferOrigin.OUTGOING || !packet.touchesOffhand()) {
                    return@handler
                }

                // The prediction is already applied at send time, so this IS what we now believe.
                predicted = mc.player?.offhandItem?.copy() ?: ItemStack.EMPTY
                pendingContainerId = packet.containerId
                pending = true
                isSuspectedDrop = false
                sinceClick.reset()
            }

            is ClientboundContainerSetSlotPacket -> {
                if (event.origin != TransferOrigin.INCOMING ||
                    packet.containerId != InventoryMenu.CONTAINER_ID ||
                    packet.slot != InventoryMenu.SHIELD_SLOT
                ) {
                    return@handler
                }

                acceptServerTruth(packet.item)
            }

            is ClientboundContainerSetContentPacket -> {
                if (event.origin != TransferOrigin.INCOMING ||
                    packet.containerId != InventoryMenu.CONTAINER_ID
                ) {
                    return@handler
                }

                acceptServerTruth(packet.items.getOrNull(InventoryMenu.SHIELD_SLOT) ?: ItemStack.EMPTY)
            }

            is ClientboundOpenScreenPacket -> {
                // The server's own containerMenu is now this id. Anything we sent for a different
                // one within the last round trip hit the silent mismatch branch
                // (`ServerGamePacketListenerImpl.java:1893`) — no answer is coming for it.
                if (event.origin == TransferOrigin.INCOMING && pending &&
                    pendingContainerId != packet.containerId
                ) {
                    isSuspectedDrop = true
                }
            }

            is ClientboundContainerClosePacket -> {
                // Same mismatch, the other way round: the server is back on its inventory menu.
                if (event.origin == TransferOrigin.INCOMING && pending &&
                    pendingContainerId != InventoryMenu.CONTAINER_ID
                ) {
                    isSuspectedDrop = true
                }
            }

            else -> Unit
        }
    }

    private fun acceptServerTruth(actual: ItemStack) {
        if (pending && !ItemStack.matches(predicted, actual)) {
            contradictions++
        }

        pending = false
        predicted = ItemStack.EMPTY
        isSuspectedDrop = false
        sinceTruth.reset()
    }

    /**
     * Whether a click can move the offhand: it either addresses the offhand slot of the player's
     * own menu, or it is a SWAP whose hotbar button is [Inventory.SLOT_OFFHAND] — accepted from any
     * menu and written straight through `inventory.getItem(buttonNum)`
     * (`AbstractContainerMenu.java:477-478`).
     */
    private fun ServerboundContainerClickPacket.touchesOffhand(): Boolean {
        if (containerInput == ContainerInput.SWAP && buttonNum.toInt() == Inventory.SLOT_OFFHAND) {
            return true
        }

        return containerId == InventoryMenu.CONTAINER_ID && slotNum.toInt() == InventoryMenu.SHIELD_SLOT
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // Silence is the only success signal that exists (see KDoc), so a click that outlived its
        // window counts as accepted. A suspected drop is deliberately NOT cleared here: it stands
        // until server truth arrives or a fresh click replaces it, which bounds the repair to one
        // extra click per suspicion.
        // A foreign-menu click stays pending until that menu is gone, because no correction for
        // the offhand can be produced while it is open — otherwise agreement there would leave
        // this stuck as unconfirmed forever.
        val menuSettled = pendingContainerId == InventoryMenu.CONTAINER_ID ||
            mc.player?.containerMenu?.containerId == InventoryMenu.CONTAINER_ID

        if (pending && menuSettled && sinceClick.elapsed >= confirmWindow()) {
            pending = false
            pendingContainerId = InventoryMenu.CONTAINER_ID
            predicted = ItemStack.EMPTY
        }

        debugParameter("Offhand Unconfirmed") { isUnconfirmed }
        debugParameter("Offhand Suspected Drop") { isSuspectedDrop }
        debugParameter("Offhand Contradictions") { contradictions }
        debugParameter("Offhand Confirm Window") { "${confirmWindow()} ms" }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { reset() }

}
