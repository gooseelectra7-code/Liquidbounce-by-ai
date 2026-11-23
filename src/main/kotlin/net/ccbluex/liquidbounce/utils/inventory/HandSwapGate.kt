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

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.InventoryMenu

/**
 * Stands the whole client down for the packet window a HAND SWAP occupies: while one is in flight nothing acts -
 * no second inventory click, no use, no attack, no dig.
 *
 * ROTATIONS ARE DELIBERATELY LEFT ALONE. They are not actions: a rotation rides the movement packet every tick
 * regardless, and taking the aim away for the swap window would make the auras snap back and re-acquire - visible,
 * expensive, and pointless, since the aim is not what a swap puts in doubt. So the auras keep looking at their
 * target through the hold and only their CLICK stands down.
 *
 * ### What counts as a hand swap
 * Every click that can change what a hand HOLDS:
 * - a `SWAP` whose hotbar button is [Inventory.SLOT_OFFHAND] or the server's selected slot - button 40 and the
 *   selection are written straight through `inventory.getItem(buttonNum)`
 *   (`AbstractContainerMenu.java:477-478`), from any open menu;
 * - a click on [InventoryMenu.SHIELD_SLOT] or on the selected hotbar slot of the player's own menu;
 * - vanilla's own offhand swap key, `ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND`
 *   (`Minecraft.java:2015-2019`).
 *
 * ### Why everything else has to wait
 * - **The hands are in doubt for a round trip.** A container click is predicted locally -
 *   `MultiPlayerGameMode.handleContainerInput` runs `containerMenu.clicked(...)` (`MultiPlayerGameMode.java:452`)
 *   BEFORE it sends (`:464-469`) - and agreement is answered with SILENCE, so the prediction can be a lie for as
 *   long as it takes a correction to arrive (see [OffhandSyncTracker]). Vanilla's own swap key is worse: it
 *   predicts NOTHING, the server alone performs the swap (`ServerGamePacketListenerImpl.java:1263-1271`), so until
 *   its `SetSlot` lands the client renders the OLD hands while the server already holds the new ones. A use sent
 *   into that window consumes whatever the server thinks is in the hand, not what we aimed with.
 * - **Vanilla never uses an item while its own menu is open.** [InventoryManager] brackets its clicks with an open
 *   and a `ServerboundContainerClosePacket`; a use inside those brackets is a packet shape no client produces.
 *   Hence [isSwapping] stays up while the server still believes the inventory is open.
 * - **Predictions stack.** A second swap piled onto an unconfirmed one is computed against the first one's
 *   PREDICTED state; if the first hit one of the three answerless branches of `handleContainerClick`
 *   ([OffhandSyncTracker]), every swap behind it is wrong too, and nothing will ever correct them.
 *
 * ### The window
 * The swap's own tick, plus [holdTicks] more and plus as long as the server still holds the menu open. The swap's
 * tick is the part that is never optional: a use inside it shares a Grim tick window with the click and its close
 * packet. Everything past it is a judgement call - TCP delivers a use sent in a LATER tick behind the swap
 * anyway, so what extra ticks buy is only the chance to hear a correction first, at the price of stalling the
 * auras. Hence the default 0, and the knob for whoever wants the round trip covered.
 *
 * It is counted in `LocalPlayer.tickCount` - the only clock every consumer agrees on inside one tick: it advances
 * in `level.tickEntities()`, i.e. AFTER the head hook that drives `CoroutineTicker` and `GameTickEvent` and AFTER
 * `handleKeybinds()` (`Minecraft.java`, tick body), so the inventory scheduler at the head of the tick and an aura
 * at `InputHandleEvent` read the same number - which a handler-order-dependent counter of our own would not give.
 * [MAX_HOLD_TICKS] caps it as a dead man's switch: a gate that blocks everything must never be able to stick.
 *
 * ### What this is NOT
 * It does not track confirmation - that is [OffhandSyncTracker]'s job and `AutoTotem.AntiGhost` acts on it.
 *
 * The attack and dig holds are not a packet-order argument: container traffic sits outside vanilla's click loops
 * entirely (`Minecraft.java:2015-2025`), so no packet-order check reads an attack against a container click. They
 * exist because the module logic behind them is what has to pause - an aura that keeps swinging through a swap
 * spends its click budget, its hit delay and its ledger marks on a hand whose contents are in doubt.
 *
 * Attacks and digs have no chokepoint at all, so [holdsAttacks] / [holdsDigs] are read by the modules that own
 * them - `ModuleKillAura`, `ModuleCrystalAura`, `ModuleAnchorOptimizer` - at the point where they decide, before
 * any of the attack event's listeners get to put their sprint packets and slot switches on the wire. Whatever
 * attacks outside those (`CatleanPlaceModule`'s raw attack packet, `ModuleAutoClicker`, which simulates the
 * player's own attack key) is not held.
 *
 * It reaches only the sanctioned paths - the [InventoryManager] schedule and `utils/entity/InteractionUtils` - while
 * every swap ARMS it, including those it cannot hold back. A click that skips the schedule is therefore not held:
 * a direct `InventoryAction.Click.performAction()` (`ModuleElytraSwap`, `player/offhand/Totem`,
 * `packetmine/tool/SwitchMethod`) or a raw `interaction.*` call
 * (`CatleanBlockPlacer`'s ALTERNATIVE switch, `ModuleFastUse`'s crossbow burst, `ModuleAutoShop`,
 * `ModuleContainerHelper`). Those still hold everyone ELSE down while they work.
 *
 * Callers that need to know whether a packet actually LEFT must read [holdsUses] themselves before acting:
 * `useItem` / `interactBlock` / `interactEntity` answer "did not handle" while held, but
 * `crystalaura.exec.ActionExecutor` and `autoanchor.exec.AnchorExecutor` count a `useItemOn` as spent whatever it
 * returns (the packet normally goes out inside `startPrediction` regardless), which is why their hubs gate on
 * [holdsUses] at the EXECUTE step instead of relying on the return value.
 */
object HandSwapGate : EventListener, ValueGroup("SwapGate") {

    /** Nothing may ever be held longer than this, whatever the settings or the server say. */
    private const val MAX_HOLD_TICKS = 20

    /**
     * Ceiling for the "server still has our inventory open" extension. A chain with a slow `CloseDelay` keeps the
     * menu open for a few ticks; an unrelated cleaner click that re-opens it right after a swap must not be able to
     * ride that into a hold of its own.
     */
    private const val MAX_OPEN_EXTENSION_TICKS = 5

    private const val NOT_ARMED = Int.MIN_VALUE

    /** Master switch; off means no module is ever held back. */
    private val enabled by boolean("Enabled", true)

    /** Hold item uses (right clicks: place, eat, detonate, interact) for the window. */
    private val holdUses by boolean("HoldUses", true)

    /** Hold every further inventory action - swaps included - for the window. */
    private val holdClicks by boolean("HoldClicks", true)

    /**
     * Hold attacks. Read by the auras at their own decision point, NOT enforced by vetoing
     * `AttackEntityEvent`: `EventManager.callEvent` runs every handler regardless of cancellation
     * (`EventManager.kt:346-369`), and the handlers on that event do not check `isCancelled` - `ModuleCriticals`
     * (`:87-90`) and `ModuleSuperKnockback` (`:99-109`) put sprint packets on the wire and `ModuleAutoWeapon`
     * (`:130-142`) switches the slot. A veto there would leave all of that standing with no attack behind it,
     * which is a worse wire shape than the attack itself.
     */
    private val holdAttacks by boolean("HoldAttacks", true)

    /** Hold block breaking (the aura's AutoMine step). */
    private val holdDigs by boolean("HoldDigs", true)

    /**
     * Ticks held ON TOP of the tick that carried the swap. The default 0 is the swap's own tick alone, which is
     * what every sequence in this client already respects ("swap now, use next tick") - raising it delays the
     * fixed timelines that were built that way, `ModuleElytraSwap`'s firework path first of all.
     */
    private val holdTicks by int("HoldTicks", 0, 0..10, "ticks")

    override val running: Boolean
        get() = super.running && inGame

    @Volatile
    private var armedTick = NOT_ARMED

    /** What armed the gate, for the debug read-out only. */
    @Volatile
    private var armedBy: String? = null

    /** Inside [admitted]; main thread only, and restored by its own `finally`. */
    private var admitting = false

    /** Ticks since the swap left, or null when nothing is in flight. */
    private val heldTicks: Int?
        get() {
            val armed = armedTick
            if (armed == NOT_ARMED) {
                return null
            }

            val elapsed = (mc.player?.tickCount ?: return null) - armed
            return elapsed.takeIf { it in 0..MAX_HOLD_TICKS }
        }

    /** A hand swap is on the wire and its window has not passed yet. */
    val isSwapping: Boolean
        get() {
            if (!enabled) {
                return false
            }

            val held = heldTicks ?: return false
            // The window outlives [holdTicks] while the server still has our inventory open - the swap is not over
            // until the close packet went out, and a use may not sit between the two.
            return held <= holdTicks ||
                (held <= MAX_OPEN_EXTENSION_TICKS && InventoryManager.isInventoryOpenServerSide)
        }

    /**
     * Whether item uses have to stand down right now.
     *
     * Always false inside [admitted]: an action the gate let through owns the window it then creates itself.
     */
    val holdsUses: Boolean
        get() = !admitting && holdUses && isSwapping

    /** Whether further inventory actions have to stand down right now. */
    val holdsClicks: Boolean
        get() = holdClicks && isSwapping

    /** Whether attacks have to stand down right now - read by the auras before they spend their click. */
    val holdsAttacks: Boolean
        get() = holdAttacks && isSwapping

    /** Whether block breaking has to stand down right now. */
    val holdsDigs: Boolean
        get() = holdDigs && isSwapping

    /**
     * Whether [InventoryManager] may run [chain] now.
     *
     * A chain that only closes a screen always may: closing is what ENDS the swap window, so holding it back would
     * deadlock the gate against its own inventory-open extension.
     */
    fun allows(chain: InventoryAction.Chain): Boolean =
        !holdsClicks || chain.actions.all { it is InventoryAction.CloseScreen }

    /**
     * Runs [block] with the use hold lifted, for an action the gate ADMITTED itself.
     *
     * A scheduled chain is atomic and is gated at admission ([allows] / [InventoryAction.UseItem.canPerformAction]),
     * not per packet: once it starts, `[swap, use, swap]` - what `ElytraFlyModeFirework` and `NavFireworks`
     * schedule - has to finish, and with `ClickDelay` at 0 all three of its actions share the swap's own tick. The
     * scope is one call, never a time window, so nothing else slips through with it.
     */
    fun <T> admitted(block: () -> T): T {
        val previous = admitting
        admitting = true
        try {
            return block()
        } finally {
            admitting = previous
        }
    }

    fun reset() {
        armedTick = NOT_ARMED
        armedBy = null
    }

    private fun arm(reason: String) {
        armedTick = mc.player?.tickCount ?: return
        armedBy = reason
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(priority = EventPriorityConvention.READ_FINAL_STATE) { event ->
        if (event.isCancelled || event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }

        val reason = when (val packet = event.packet) {
            is ServerboundContainerClickPacket -> packet.handSwapReason()
            is ServerboundPlayerActionPacket -> "vanilla offhand swap".takeIf {
                packet.action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
            }
            else -> null
        } ?: return@handler

        arm(reason)
    }

    /**
     * Names the hand this click can change, or null when it touches neither.
     *
     * The selection read is [SilentHotbar.serversideSlot] - the slot the SERVER holds, which is the one a
     * button-number swap actually lands in, and which a silent switch can have moved away from
     * `Inventory.selectedSlot`.
     */
    private fun ServerboundContainerClickPacket.handSwapReason(): String? {
        val held = SilentHotbar.serversideSlot

        if (containerInput == ContainerInput.SWAP) {
            return when (buttonNum.toInt()) {
                Inventory.SLOT_OFFHAND -> "offhand swap"
                held -> "held-slot swap"
                else -> null
            }
        }

        if (containerId != InventoryMenu.CONTAINER_ID) {
            return null
        }

        return when (slotNum.toInt()) {
            InventoryMenu.SHIELD_SLOT -> "offhand click"
            InventoryMenu.USE_ROW_SLOT_START + held -> "held-slot click"
            else -> null
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // heldTicks reports null past [MAX_HOLD_TICKS] and on a backwards tickCount (respawn); drop the arming with
        // it, so the gate cannot be re-opened by a later coincidence.
        if (armedTick != NOT_ARMED && heldTicks == null) {
            reset()
        }

        debugParameter("Swap Gate") { armedBy?.let { "$it (${heldTicks ?: "-"}t)" } ?: "idle" }
        debugParameter("Swap Gate Holds Uses") { holdsUses }
        debugParameter("Swap Gate Holds Clicks") { holdsClicks }
        debugParameter("Swap Gate Holds Attacks") { holdsAttacks }
        debugParameter("Swap Gate Holds Digs") { holdsDigs }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { reset() }

}
