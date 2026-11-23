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
package net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove.ModuleInventoryMove.Behaviour
import net.ccbluex.liquidbounce.utils.input.InputTracker
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FINAL_DECISION
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.player.Input

/**
 * Packet side of [Behaviour.BYPASS] and [Behaviour.LEGIT] (ported from the "syntax" client's
 * GuiMove BYPASS/LEGIT modes).
 *
 * Movement itself uses the regular sanctioned path: for these behaviours
 * [ModuleInventoryMove.shouldHandleInputs] returns true exactly like [Behaviour.NORMAL], so
 * MixinKeyboardInput fakes the movement keys. This object ONLY delays the container packets that
 * would betray "clicking while moving" and replays them during a short, genuine stop.
 *
 * Flow: inventory clicks sent while moving are queued and cancelled. When the inventory close
 * packet follows (still moving), it is queued as well and a tick sequence starts:
 *
 * - BYPASS: t0 lock movement, t1 flush the whole queue silently (clicks then close, original
 *   order), t2 unlock.
 * - LEGIT:  t0 lock, t3 flush clicks, t5 flush close packets, t7 unlock - the slower spacing is
 *   for anticheats that check container packet timings.
 *
 * GRIM-SAFETY reasoning:
 * a) Movement is locked via [MovementInputEvent] (like [Behaviour.STOP_ON_ACTION]), so the client
 *    GENUINELY stops: outgoing position deltas decay to zero and the silently-sent empty
 *    [ServerboundPlayerInputPacket] matches them - no BadPackets from an input/movement mismatch.
 *    We never touch KeyMappings or player.input directly.
 * b) The delayed [ServerboundContainerClickPacket]s reach the server while the player is
 *    stationary, which beats Matrix/Grim-style "inventory click while moving" heuristics.
 * c) Cancelling a [ServerboundContainerClosePacket] for containerId 0 when NO click was queued is
 *    consistent: the survival inventory is opened purely client-side, so the server only learns
 *    it was open FROM click packets - it never saw one, hence there is nothing to close.
 * d) Swing/interact/use packets are suppressed while queued clicks await their flush, preventing
 *    attack-while-inventory-open flags (the server considers the inventory open between the
 *    flushed clicks and the flushed close).
 * No rotation changes and no packet reordering beyond the above are introduced.
 */
internal object InventoryMoveGuiBypass : EventListener, MinecraftShortcuts {

    override val running: Boolean
        get() = ModuleInventoryMove.running &&
            (ModuleInventoryMove.behavior === Behaviour.BYPASS || ModuleInventoryMove.behavior === Behaviour.LEGIT)

    /**
     * Queued (cancelled) container packets in original send order: clicks first, close last.
     */
    private val delayedPackets = mutableListOf<Packet<*>>()

    /**
     * Latched once something was queued while moving; keeps [moving] true (and therefore keeps
     * queueing follow-up packets) even if the movement keys are released before the flush.
     */
    private var movedInGui = false

    /**
     * True from the moment the close packet is queued until the flush sequence finished.
     */
    private var processingPackets = false

    /**
     * While set, [movementInputHandler] zeroes the client input, so the player really stops.
     */
    private var movementLocked = false

    /**
     * Connection the queued packets belong to. LB equivalent of the reference's null-player
     * cleanup: if the connection changed (relog), the queue is stale and flushing it would send
     * clicks with invalid container/slot state - a guaranteed flag - so it is dropped instead.
     */
    private var queuedConnection: ClientPacketListener? = null

    private val moving: Boolean
        get() = movedInGui || movementKeysDown()

    /**
     * Physical key state of forward/back/left/right/jump via [InputTracker] (respects rebinds).
     * Sneak/sprint are intentionally not part of this - they don't move the player horizontally
     * while an inventory screen is open.
     */
    private fun movementKeysDown(): Boolean = with(InputTracker) {
        mc.options.keyUp.isPressedOnAny || mc.options.keyDown.isPressedOnAny ||
            mc.options.keyLeft.isPressedOnAny || mc.options.keyRight.isPressedOnAny ||
            mc.options.keyJump.isPressedOnAny
    }

    fun cleanup() {
        delayedPackets.clear()
        movedInGui = false
        processingPackets = false
        movementLocked = false
        queuedConnection = null
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(FIRST_PRIORITY) { event ->
        if (event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }

        when (val packet = event.packet) {
            is ServerboundContainerClickPacket ->
                // Only the survival inventory screen (reference checks InventoryScreen); the
                // slot-count check is kept faithful to the reference (>= 27 on the open menu).
                if (mc.screen is InventoryScreen && moving && player.containerMenu.slots.size >= 27) {
                    delayedPackets += packet
                    movedInGui = true
                    queuedConnection = mc.connection
                    event.cancelEvent()
                }

            is ServerboundContainerClosePacket ->
                // The close packet is sent from LocalPlayer.closeContainer() BEFORE the screen is
                // cleared (verified against 26.1.2 bytecode), so mc.screen is still the
                // InventoryScreen here. The screen check keeps programmatic containerId-0 closes
                // from other features (no GUI open) out of this logic.
                if (packet.containerId == 0 && mc.screen is InventoryScreen && moving && !processingPackets) {
                    if (delayedPackets.isEmpty()) {
                        // GRIM-SAFETY (c): no click was let through, the server never learned the
                        // inventory was open - hiding the close keeps both sides consistent.
                        event.cancelEvent()
                    } else {
                        delayedPackets += packet
                        movedInGui = true
                        queuedConnection = mc.connection
                        event.cancelEvent()
                        processingPackets = true
                    }
                }

            is ServerboundPlayerInputPacket ->
                if (processingPackets) {
                    // GRIM-SAFETY (a): the movement lock has genuinely stopped the player, so an
                    // all-false input is what the server must see; send it silently ourselves so
                    // no other input state can leak out mid-sequence.
                    event.cancelEvent()
                    sendPacketSilently(ServerboundPlayerInputPacket(Input.EMPTY))
                }

            // Reference suppresses HandSwing/InteractEntity/InteractItem/InteractBlock while
            // flushing. In 26.x the old InteractEntity packet is split into Interact + Attack,
            // so both are covered here.
            is ServerboundSwingPacket, is ServerboundInteractPacket, is ServerboundAttackPacket,
            is ServerboundUseItemPacket, is ServerboundUseItemOnPacket ->
                if (processingPackets && delayedPackets.isNotEmpty()) {
                    // GRIM-SAFETY (d): no attacks/interactions while the server-side inventory is
                    // about to be opened by the flushed clicks.
                    event.cancelEvent()
                }

            else -> {}
        }
    }

    /**
     * Same pattern as [Behaviour.STOP_ON_ACTION]'s movement handler and ModuleKnockbackDisplace:
     * the ONLY way we stop the player is by zeroing the sanctioned input event - the client then
     * decelerates naturally and every outgoing packet stays truthful (GRIM-SAFETY (a)).
     */
    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent>(FINAL_DECISION) { event ->
        if (!movementLocked) {
            return@handler
        }

        event.directionalInput = DirectionalInput.NONE
        event.jump = false
        event.sneak = false
    }

    /**
     * Tick-based flush sequence. The body runs at the start of every client tick (GameTickEvent);
     * [waitTicks] advances exactly one tick per step, so the tick counts match the reference.
     *
     * If [running] turns false mid-sequence (module disabled, behaviour switched, world left),
     * the coroutine is cancelled at its next resume and [cleanup] unlocks the movement.
     */
    @Suppress("unused")
    private val processingSequence = tickHandler(onCancellation = Runnable { cleanup() }) {
        // Stale queue from a previous connection must never be flushed (see [queuedConnection]).
        if (queuedConnection != null && queuedConnection !== mc.connection) {
            cleanup()
        }

        // Reference: movedInGui resets once no GUI is open and nothing is queued or processing.
        if (movedInGui && mc.screen == null && delayedPackets.isEmpty() && !processingPackets) {
            movedInGui = false
        }

        if (!processingPackets) {
            return@tickHandler
        }

        val legitTiming = ModuleInventoryMove.behavior === Behaviour.LEGIT

        // t0: lock movement - zero client input so the player really stops server-side.
        movementLocked = true
        waitTicks(1)

        if (!legitTiming) {
            // BYPASS - t1: send ALL queued packets silently (clicks then the close, original
            // order) while the player is stationary (GRIM-SAFETY (b)).
            flushQueuedPackets { true }
            processingPackets = false
            movedInGui = false
            waitTicks(1)
            // t2: unlock movement.
            movementLocked = false
        } else {
            // LEGIT - for anticheats that check container packet timings.
            // t1, t2: idle.
            waitTicks(2)
            // t3: send all non-close queued packets silently (GRIM-SAFETY (b)).
            flushQueuedPackets { it !is ServerboundContainerClosePacket }
            // t4: idle.
            waitTicks(2)
            // t5: send the queued close packets silently, emptying the queue.
            flushQueuedPackets { true }
            // t6: idle.
            waitTicks(2)
            // t7: unlock movement, done.
            movementLocked = false
            processingPackets = false
            movedInGui = false
        }
    }

    /**
     * Sends and removes every queued packet matching [filter], in original queue order.
     */
    private inline fun flushQueuedPackets(filter: (Packet<*>) -> Boolean) {
        val iterator = delayedPackets.iterator()
        while (iterator.hasNext()) {
            val packet = iterator.next()
            if (filter(packet)) {
                iterator.remove()
                sendPacketSilently(packet)
            }
        }
    }

    /**
     * Dimension switches invalidate the queue (this does not fire on disconnect - [running] is
     * already false then - which is what [queuedConnection] covers).
     */
    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        cleanup()
    }

}
