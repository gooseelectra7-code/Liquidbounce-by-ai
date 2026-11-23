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

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove.ModuleInventoryMove.Behaviour
import net.ccbluex.liquidbounce.integration.screen.NativeClientScreen
import net.ccbluex.liquidbounce.utils.input.InputTracker
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FINAL_DECISION
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ShulkerBoxBlock
import java.util.EnumSet

/**
 * Settings + packet engine of [Behaviour.SYSTEM] — ported from SystemDLC
 * (`system-source — копия`, client/module/movement/InvMove.java, machine-recovered names;
 * semantics traced through SneakState.java / KeyPressUtil.java / PacketEvent.java).
 *
 * What the original does (and this port reproduces):
 *
 * 1. Movement policy (InvMove.java:128-149): movement keys are handled ONLY in the survival
 *    inventory (gated by «Бегать в инвентаре» = [runInInventory], InvMove.java:50-53) and in the
 *    client's own GUI («Бегать в гуи» = [runInGui], InvMove.java:54-57) — never in other
 *    containers (a shulker screen is force-stopped, InvMove.java:132/146-148), chat, sign or
 *    anvil screens (InvMove.java:129-131).
 * 2. Click queue (InvMove.java:253-277, 307-323): a `ClickSlotC2S` sent from the survival
 *    inventory while moving — or within 4 ticks of moving (`value239`, InvMove.java:77-81) — is
 *    cancelled and queued, and the movement keys are force-released (`update14` →
 *    SneakState.update5 → KeyPressUtil.update3/update2, i.e. a genuine stop). While the stop is
 *    active, further clicks pass through untouched (`SneakState.isValueAsBoolean` gate,
 *    InvMove.java:311).
 * 3. Drip-feed flush (InvMove.java:90-102): queued clicks are re-sent silently one per 2 ticks
 *    (`value235` spacing), each dropped unless its syncId still matches the open menu.
 * 4. Close handling (InvMove.java:295-304): a `CloseHandledScreenC2S` sent while clicks are
 *    pending or while moving is cancelled and held; it goes out (`update11`, InvMove.java:153-162)
 *    once the queue has been empty for `value236` ticks (4 with pending clicks, 2 for a bare
 *    close), and movement resumes 1 tick later (`value238`, InvMove.java:83-89).
 * 5. Shulker-box PICKUP delay (InvMove.java:255-260, 233-247): a PICKUP click that touches a
 *    shulker-box item (slot or cursor) with even pickup parity (`flag`) is not queued but held
 *    alone and re-sent after exactly 2 ticks (`value237`), also under a movement stop.
 * 6. Enable/disable (InvMove.java:201-204, 344-347, 349-385): `update16` flushes everything
 *    (clicks syncId-guarded, close unconditionally) and clears all state.
 *
 * Mapping to this client's sanctioned input path (the AGENTS.md InventoryMove invariant —
 * movement in a GUI goes through `shouldHandleInputs`/MixinKeyboardInput, never by pressing
 * KeyMappings):
 * - The original polls physical GLFW keys and calls `KeyBinding.setPressed` every tick
 *   (InvMove.java:136-144). Here [ModuleInventoryMove.shouldHandleInputs] returns the SYSTEM
 *   screen policy ([allowsMovementOn]) and MixinKeyboardInput fakes the keys from
 *   [InputTracker] — same set of keys: forward/back/left/right/jump/sprint (`update15`,
 *   InvMove.java:331-342; sneak is NOT in the array, which matches our default
 *   `passthroughSneak = false`).
 * - The original's force-stop (`KeyPressUtil.update3/update2`: release all movement keys +
 *   `setSprinting(false)`, KeyPressUtil.java:27-56, re-applied each tick via
 *   `SneakState.update4`, InvMove.java:122-127) maps to zeroing [MovementInputEvent] at
 *   [FINAL_DECISION] while [movementSuppressed] — the pattern of [InventoryMoveGuiBypass] and
 *   STOP_ON_ACTION: the client genuinely decelerates, sprint drops with the input, and every
 *   outgoing packet stays truthful (no input/position mismatch for Grim to flag).
 * - The original re-sends packets through its silent hook (PacketEvent.onPacket2 with
 *   `ConnectionHooks.setFlag`, PacketEvent.java:44-53) → [sendPacketSilently] here.
 * - The original ticks at TAIL of `MinecraftClient.tick` (ClientTickMixin.java:20); this port
 *   uses [GameTickEvent] (HEAD of `Minecraft.tick`) — both run once per client tick, so all
 *   counter timings (2-tick click spacing, 4/2-tick close delay, 2-tick shulker delay, 1-tick
 *   resume, 4-tick recent-move latch) are preserved exactly.
 *
 * Counter/field mapping (original → here): `value235` → [clickFlushCooldown], `value236` →
 * [closeCountdown], `value237` → [shulkerResendDelay], `value238` → [resumeDelay], `value239` →
 * [recentMoveTicks], `flag` → [pickupParity], `flag2` (== `SneakState.value > 0` for this
 * module) → [movementSuppressed], `queue` → [queuedClicks], `clickSlotC2SPacket` →
 * [heldShulkerClick], `closeHandledScreenC2SPacket` → [pendingClose]. `flag3` is dead code in
 * the original (only ever read at InvMove.java:123 and cleared, never set) and is omitted.
 *
 * Deliberate deviations, each documented at its site:
 * - Stale state from a previous connection is DROPPED, not flushed ([queuedConnection] — the
 *   [InventoryMoveGuiBypass] rule: flushing clicks into a new connection's container state is a
 *   guaranteed flag; the original reaches the same end through its syncId guard, but sends the
 *   held close unconditionally).
 * - «Бегать в гуи» maps "the client's ClickGuiScreen" to this client's own screens
 *   ([NativeClientScreen] implementors) — this
 *   client has several first-party GUI screens where SystemDLC has one.
 * - The original stops movement while one of its own GUI widgets is focused
 *   (`WidgetState.isWidgetAsBoolean`, InvMove.java:132); the counterpart here is the existing
 *   global search-bar guard in [ModuleInventoryMove.shouldHandleInputs].
 */
internal object InventoryMoveSystemBypass : ValueGroup("System"), EventListener, MinecraftShortcuts {

    override val running: Boolean
        get() = ModuleInventoryMove.running

    /**
     * «Бегать в инвентаре» — "allows moving with the inventory open", default true
     * (InvMove.java:50-53).
     */
    private val runInInventory by boolean("RunInInventory", true)

    /**
     * «Бегать в гуи» — "allows moving with the client GUI open", default true
     * (InvMove.java:54-57).
     */
    private val runInGui by boolean("RunInGui", true)

    /**
     * Click types the original queues (InvMove.java:262-266). CLONE and PICKUP_ALL pass
     * through untouched, exactly like in the original.
     */
    private val queueableClickTypes: EnumSet<ContainerInput> = EnumSet.of(
        ContainerInput.PICKUP,
        ContainerInput.QUICK_MOVE,
        ContainerInput.SWAP,
        ContainerInput.THROW,
        ContainerInput.QUICK_CRAFT,
    )

    // - State (see the counter/field mapping table in the KDoc) -

    private val queuedClicks = ArrayDeque<ServerboundContainerClickPacket>()
    private var heldShulkerClick: ServerboundContainerClickPacket? = null
    private var pendingClose: ServerboundContainerClosePacket? = null
    private var pickupParity = false
    private var movementSuppressed = false
    private var clickFlushCooldown = 0
    private var closeCountdown = 0
    private var shulkerResendDelay = 0
    private var resumeDelay = 0
    private var recentMoveTicks = 0

    /**
     * Connection the queued packets belong to (LB-specific staleness guard, same rationale as
     * [InventoryMoveGuiBypass.queuedConnection]): a queue from a previous connection is dropped,
     * never flushed.
     */
    private var queuedConnection: ClientPacketListener? = null

    /**
     * The SYSTEM screen policy for [ModuleInventoryMove.shouldHandleInputs]: movement only in
     * the survival inventory (`check3`, InvMove.java:164-166 — the creative screen is a
     * different class on 26.1.2, so the extra exclusion is implicit) and in the client's own
     * GUI (InvMove.java:135-136). Everything else — other containers (shulker force-stop,
     * InvMove.java:132), chat/sign/anvil (InvMove.java:129-131), pause menu — gets no movement,
     * which on our side simply means MixinKeyboardInput leaves the vanilla (released) key state
     * alone.
     */
    fun allowsMovementOn(screen: Screen): Boolean = when {
        screen is InventoryScreen -> runInInventory
        screen is NativeClientScreen -> runInGui
        else -> false
    }

    private val hasPendingState: Boolean
        get() = queuedClicks.isNotEmpty() || heldShulkerClick != null || pendingClose != null ||
            movementSuppressed || pickupParity || clickFlushCooldown > 0 || closeCountdown > 0 ||
            shulkerResendDelay > 0 || resumeDelay > 0 || recentMoveTicks > 0

    /** `getInt()` (InvMove.java:229-231). */
    private val currentContainerId: Int
        get() = mc.player?.containerMenu?.containerId ?: -1

    /**
     * `check4` (InvMove.java:168-190): client movement input non-zero, or sprinting, or any of
     * the physical forward/back/left/right/jump keys held. The original polls raw GLFW key
     * codes; [InputTracker] is this client's equivalent and additionally respects rebinds.
     * During a suppression the event-zeroed input reads zero here while the physically held
     * keys keep this true — exactly like the original's released KeyBindings vs held GLFW keys.
     */
    private fun isMovingOrKeysHeld(): Boolean {
        val player = mc.player ?: return false
        val move = player.input.moveVector
        if (move.x != 0.0f || move.y != 0.0f) {
            return true
        }
        if (player.isSprinting) {
            return true
        }
        return with(InputTracker) {
            mc.options.keyUp.isPressedOnAny || mc.options.keyDown.isPressedOnAny ||
                mc.options.keyLeft.isPressedOnAny || mc.options.keyRight.isPressedOnAny ||
                mc.options.keyJump.isPressedOnAny
        }
    }

    /** `check6` (InvMove.java:279-286): movement input non-zero, or sprinting (no key poll). */
    private fun isMovingByInput(): Boolean {
        val player = mc.player ?: return false
        val move = player.input.moveVector
        return if (move.x == 0.0f && move.y == 0.0f) player.isSprinting else true
    }

    /** `check5` (InvMove.java:206-208). */
    private fun isMovingOrRecentlyMoved(): Boolean = recentMoveTicks > 0 || isMovingOrKeysHeld()

    /** `isInt` + `isItemStack` (InvMove.java:233-247): slot stack or cursor stack is a shulker box. */
    private fun affectsShulkerBox(slotId: Int): Boolean {
        val menu = mc.player?.containerMenu ?: return false
        if (slotId < 0 || slotId >= menu.slots.size) {
            return false
        }
        return menu.slots[slotId].item.isShulkerBoxItem() || menu.carried.isShulkerBoxItem()
    }

    private fun ItemStack.isShulkerBoxItem(): Boolean {
        val item = this.item
        return item is BlockItem && item.block is ShulkerBoxBlock
    }

    /** `setClickSlotC2SPacket` (InvMove.java:325-329): PICKUP clicks toggle the parity. */
    private fun trackPickupParity(packet: ServerboundContainerClickPacket) {
        if (packet.containerInput == ContainerInput.PICKUP) {
            pickupParity = !pickupParity
        }
    }

    /**
     * `update14` (InvMove.java:288-293). The original force-releases the keys and un-sprints
     * immediately; here the [movementInputHandler] zeroes the sanctioned input from the next
     * input tick on and the client decelerates truthfully (see the KDoc mapping).
     */
    private fun suppressMovement() {
        movementSuppressed = true
    }

    /** `update12` (InvMove.java:192-199): the original re-syncs keys from physical state; ours resume via MixinKeyboardInput. */
    private fun releaseMovement() {
        movementSuppressed = false
    }

    /**
     * `update11` (InvMove.java:153-162): send the held close (no syncId guard in the original),
     * then wait `value238 = 1` tick before releasing the movement.
     */
    private fun flushPendingClose() {
        val close = pendingClose
        if (close != null) {
            pendingClose = null
            sendPacketSilently(close)
            resumeDelay = 1
        } else {
            releaseMovement()
        }
    }

    /**
     * `update16` (InvMove.java:349-385): flush the queue and the held click (each dropped
     * unless its containerId still matches the open menu), then the close, then clear all
     * state. [flushPending] false = the drop-only path used for stale connections and world
     * changes (deviation, see the KDoc).
     */
    fun reset(flushPending: Boolean) {
        if (flushPending && queuedConnection != null && queuedConnection === mc.connection) {
            val containerId = currentContainerId
            while (queuedClicks.isNotEmpty()) {
                val click = queuedClicks.removeFirst()
                if (click.containerId == containerId) {
                    sendPacketSilently(click)
                }
            }
            heldShulkerClick?.let { click ->
                if (click.containerId == containerId) {
                    sendPacketSilently(click)
                }
            }
            pendingClose?.let(::sendPacketSilently)
        }
        queuedClicks.clear()
        heldShulkerClick = null
        pendingClose = null
        pickupParity = false
        movementSuppressed = false
        clickFlushCooldown = 0
        closeCountdown = 0
        shulkerResendDelay = 0
        resumeDelay = 0
        recentMoveTicks = 0
        queuedConnection = null
    }

    /**
     * `onPacketEvent` (InvMove.java:307-323). FIRST_PRIORITY so the cancellation is visible to
     * InventoryManager's READ_FINAL_STATE tracker (it skips cancelled events). Outgoing
     * container packets originate on the render thread here (same thread as the tick handler),
     * matching the original's single-threaded dispatch.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(FIRST_PRIORITY) { event ->
        if (event.origin != TransferOrigin.OUTGOING || ModuleInventoryMove.behavior !== Behaviour.SYSTEM) {
            return@handler
        }

        when (val packet = event.packet) {
            // InvMove.java:295-304 — the close is intercepted for ANY container, matching the
            // original (no screen or containerId filter).
            is ServerboundContainerClosePacket -> {
                val hasPendingClicks = queuedClicks.isNotEmpty() || heldShulkerClick != null
                if (hasPendingClicks || isMovingByInput()) {
                    event.cancelEvent()
                    pendingClose = packet
                    queuedConnection = mc.connection
                    resumeDelay = 0
                    suppressMovement()
                    closeCountdown = maxOf(closeCountdown, if (hasPendingClicks) 4 else 2)
                }
            }

            is ServerboundContainerClickPacket -> {
                // SneakState.isValueAsBoolean gate (InvMove.java:311): while the stop is
                // active the player is already (being brought) stationary, so clicks pass.
                if (movementSuppressed) {
                    return@handler
                }
                // check3 (InvMove.java:313): survival inventory only.
                if (mc.screen !is InventoryScreen) {
                    return@handler
                }
                if (!isMovingOrRecentlyMoved()) {
                    // InvMove.java:314-315: stationary clicks only keep the parity in sync.
                    trackPickupParity(packet)
                    return@handler
                }

                if (packet.containerInput == ContainerInput.PICKUP && !pickupParity &&
                    affectsShulkerBox(packet.slotNum.toInt())
                ) {
                    // InvMove.java:255-260: the shulker PICKUP is held alone for 2 ticks
                    // (parity NOT toggled on this branch, exactly like the original).
                    event.cancelEvent()
                    heldShulkerClick = packet
                    queuedConnection = mc.connection
                    shulkerResendDelay = 2
                    suppressMovement()
                    closeCountdown = 4
                } else if (packet.containerInput in queueableClickTypes) {
                    // InvMove.java:262-275.
                    event.cancelEvent()
                    queuedClicks.addLast(packet)
                    trackPickupParity(packet)
                    queuedConnection = mc.connection
                    suppressMovement()
                    closeCountdown = 4
                    if (clickFlushCooldown == 0) {
                        clickFlushCooldown = 1
                    }
                }
            }

            else -> {}
        }
    }

    /**
     * `onTick` (InvMove.java:73-151), steps in the original order. Runs whenever the module is
     * on (not just in SYSTEM) so a sequence left behind by a behaviour switch is drained the
     * way the original's `onDisable` → `update16` drains it.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // Stale queue from a previous connection is dropped (deviation, see KDoc).
        if (queuedConnection != null && queuedConnection !== mc.connection) {
            reset(flushPending = false)
        }

        if (ModuleInventoryMove.behavior !== Behaviour.SYSTEM) {
            if (hasPendingState) {
                reset(flushPending = true)
            }
            return@handler
        }

        // 1) Recent-movement latch (InvMove.java:77-81).
        if (isMovingOrKeysHeld()) {
            recentMoveTicks = 4
        } else if (recentMoveTicks > 0) {
            recentMoveTicks--
        }

        // 2) Post-close resume delay (InvMove.java:83-89).
        if (resumeDelay > 0) {
            resumeDelay--
            if (resumeDelay == 0 && pendingClose == null) {
                releaseMovement()
            }
        }

        // 3) Click drip-feed, one click per 2 ticks (InvMove.java:90-102).
        if (clickFlushCooldown > 0) {
            clickFlushCooldown--
        } else if (queuedClicks.isNotEmpty()) {
            val click = queuedClicks.removeFirst()
            if (click.containerId == currentContainerId) {
                sendPacketSilently(click)
            }
            clickFlushCooldown = 1
            if (queuedClicks.isNotEmpty()) {
                closeCountdown = maxOf(closeCountdown, 4)
            }
        }

        // 4) Close countdown (InvMove.java:104-109).
        if (closeCountdown > 0) {
            closeCountdown--
            if (closeCountdown == 0 && queuedClicks.isEmpty()) {
                flushPendingClose()
            }
        }

        // 5) Delayed shulker click re-send (InvMove.java:111-120).
        if (shulkerResendDelay > 0) {
            shulkerResendDelay--
            if (shulkerResendDelay == 0) {
                heldShulkerClick?.let { click ->
                    if (click.containerId == currentContainerId) {
                        sendPacketSilently(click)
                    }
                }
                heldShulkerClick = null
            }
        }

        // 6) The original's movement branch (InvMove.java:122-149) — key pressing and the
        //    per-tick force-release — lives in MixinKeyboardInput ([allowsMovementOn]) and
        //    [movementInputHandler] respectively.
    }

    /**
     * The stop itself: same sanctioned pattern as [InventoryMoveGuiBypass] and STOP_ON_ACTION —
     * zero the input event, the client decelerates genuinely, every outgoing packet stays
     * truthful. Replaces `KeyPressUtil.update3/update2` + the per-tick `SneakState.update4`
     * re-release (KeyPressUtil.java:27-56, InvMove.java:122-127).
     */
    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent>(FINAL_DECISION) { event ->
        if (!movementSuppressed) {
            return@handler
        }

        event.directionalInput = DirectionalInput.NONE
        event.jump = false
        event.sneak = false
    }

    /**
     * World/dimension change invalidates the queue — drop, never flush (same as
     * [InventoryMoveGuiBypass]; the original reaches its equivalent through
     * `player() == null` → `update16`, InvMove.java:74-75).
     */
    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        reset(flushPending = false)
    }

}
