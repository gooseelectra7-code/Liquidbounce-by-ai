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
package net.ccbluex.liquidbounce.features.module.modules.combat.totem

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.entity.anyHorizontal
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FINAL_DECISION
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput

/**
 * Makes the totem features' container clicks survive the two anticheats that cancel or flag them.
 *
 * One instance PER MODULE, owned by the module: the settings and the movement stop belong to that
 * module alone, and [windowUp] is how the owner says when its own swap is in flight. It was a single
 * global object until 2026-09-16.
 *
 * This is the AutoTotem + InventoryMove coupling the user asked for on 2026-09-14. It is NOT a
 * heuristic - both anticheats were read from source this session and they want the same thing, so
 * this mirrors their predicates exactly rather than guessing at a tolerance.
 *
 * ## Grim (clone at HEAD `8eb5f28`, 2026-09-09) - this one CANCELS
 *
 * `checks/impl/multiactions/MultiActionsC.java:37-51`, name
 * `grim.multiactions.inventory_click_while_moving`. On any `CLICK_WINDOW` it evaluates three
 * conditions and, if any holds, calls `event.setCancelled(true)`:
 * - `isVerboseSprinting` (`:23-25`) - `player.isSprinting`, on EVERY client version;
 * - `isVerboseInput` (`:33-35`) - `knownInput.moving()`, i.e.
 *   `forward || backward || left || right || jump` (`utils/data/KnownInput.java:10-12`), on client
 *   and server 1.21.2+;
 * - `isVerboseSneaking` (`:28-30`) - `player.isSneaking`, on clients older than 1.15 OR
 *   **1.21.9 and newer**, which is us.
 *
 * It is NOT experimental and the shipped `punishments/en.yml` Misc group contains `"MultiActions"`,
 * so it is enabled and cancelling out of the box. Its only exemption is
 * `player.serverOpenedInventoryThisTick` (`:40`), which is armed solely by a server-to-client
 * `OPEN_WINDOW` (`events/packets/PreViaCheckManagerListener.java:55-57`) - and the player's own
 * inventory, container id 0, never produces one. So the exemption never applies to us.
 *
 * **Why a cancel is the ghost.** `CompensatedInventory` runs BEFORE the cancel in dispatch order
 * (`manager/CheckManager.java` builder order 121 vs 142) and guards on `!event.isCancelled()`
 * (`utils/latency/CompensatedInventory.java:261`), so it applies the click to Grim's model. The
 * client applied it too. The real server never saw it. Nothing in the cancel path sends a resync;
 * the only correction is `CorrectingPlayerInventoryStorage.handleClientClaimedSlotSet:66-68`, which
 * schedules one about 5 ticks later - and it early-returns entirely when
 * `isPacketInventoryActive` is false (`:108`), which is the case for menus Grim does not model.
 * That is the ghost totem, and it is why this gate refuses the click rather than risking it.
 *
 * ## TotemGuard 3.0 (branch `3.0`) - this one only flags, but it wants the same state
 *
 * `common/.../check/impl/inventory/InventoryB.java:42-48`, "Moving during inventory interaction":
 * a `CLICK_WINDOW` while `data.isSprinting() && !data.isSwimming()` fails as
 * `"click (sprinting)"`, and while `inputData.hasMovement(true)` as `"click (move)"`. The same
 * check also fails a `CLOSE_WINDOW` in that state (`:49-65`), so the close has to ride the quiet
 * window too - and `InventoryManager` emits one after every chain.
 *
 * ## Why the fork's own `Requires = NoMovement` is not enough
 *
 * `InventoryRequirements.NO_MOVEMENT` tests `player.input.moveVector.isLikelyZero && !player.jumping`
 * (`utils/inventory/InventoryValueGroups.kt:123`). That covers Grim's `knownInput.moving()` but
 * NOT sprinting and NOT sneaking, each of which trips `MultiActionsC` on its own. It is also off by
 * default (`Requires` defaults to an empty set), so out of the box nothing guards any of this.
 *
 * ## The stop
 *
 * Refusing to click is only half an answer: something has to make the quiet happen, or a sprinting
 * player never totems at all. [movementInputHandler] zeroes the sanctioned movement input for the
 * swap window, which is the same lever `ModuleInventoryMove`'s own SYSTEM engine pulls for its
 * queued clicks (`InventoryMoveSystemBypass.suppressMovement`) - so this works whether or not
 * InventoryMove is enabled, and the two do not fight because both only ever zero the input.
 * Sprint is deliberately NOT released by a packet of ours: vanilla drops it by itself once the
 * input is zero, and a hand-sent `STOP_SPRINTING` risks Grim's `BadPacketsF`
 * (`checks/impl/badpackets/BadPacketsF.java:42-51`), which CANCELS a redundant stop and would
 * leave Grim believing we are still sprinting - the exact state that gets our clicks cancelled.
 */
class SwapQuiet(
    owner: EventListener,
    /**
     * Whether the OWNING module has a swap window up right now.
     *
     * Supplied by the module instead of read from a global, so this instance only ever reacts
     * to its own owner. [tickHandler] keeps the stop alive from it, which is what stops a
     * feature having to remember to release anything.
     */
    private val windowUp: () -> Boolean,
) : ToggleableValueGroup(owner, "SwapQuiet", true), MinecraftShortcuts {

    private val NOT_ARMED = Int.MIN_VALUE

    /**
     * Hard bound on ONE CONTINUOUS RUN of the stop, and the most load-bearing number here.
     *
     * Callers re-request every scheduling cycle for as long as they want to swap, so the per-tick
     * freshness of [requestedTick] can never expire on its own - without this budget the input
     * would stay zeroed for as long as the caller keeps wanting a swap. Normally the quiet arrives
     * in one or two ticks, because zeroing the input is exactly what makes vanilla drop sprint. But
     * if anything else pins the sprint on (a KeepSprint-style module, a mixin that re-adds input
     * after [FINAL_DECISION]) the quiet never arrives, and then a player frozen mid-fight is a far
     * worse outcome than a totem that did not swap. So the run is abandoned here: the input is
     * handed back, [reset] ends the run, and the next request starts a fresh one. That yields a
     * retry duty cycle instead of either a freeze or a permanent refusal.
     */
    private val MAX_STOP_TICKS = 20

    /**
     * Ticks a fresh run is refused for after one was abandoned on [MAX_STOP_TICKS].
     *
     * Without it that budget is not a duty cycle, it is a single free tick. `InventoryManager`'s
     * scheduler is a TICK HANDLER, so the callers re-request on every tick they still want a swap:
     * the tick [reset] ends an abandoned run, the next request starts another one and the player is
     * pinned again immediately. This cooldown is what actually hands the movement back - about half
     * the time stopped, half the time the player's, for as long as the quiet keeps not arriving.
     */
    private val RETRY_COOLDOWN_TICKS = 20

    /**
     * Zero the movement input for the swap window so the quiet actually arrives.
     *
     * Off makes this gate passive: clicks still wait for a genuine stop, but nothing produces one,
     * so a permanently sprinting player simply never swaps. Only turn it off if you would rather
     * lose the totem than lose the sprint.
     */
    private val stop = tree(StopMovementGroup(this))

    /** The movement stop and how long it outlasts the swap window. */
    class StopMovementGroup(owner: EventListener) : ToggleableValueGroup(owner, "StopMovement", true) {

        /**
         * Ticks the stop is held past the last tick a swap window was up.
         *
         * The click and the `CLOSE_WINDOW` that follows it are separate packets in separate ticks,
         * and TotemGuard fails the close for movement just like the click, so the quiet has to
         * outlast the chain rather than end with it.
         */
        val holdTicks by int("HoldTicks", 3, 0..10, "ticks")

    }

    /**
     * Refuse a hand swap while our own inventory is open server-side.
     *
     * TotemGuard 3.0 counts both halves of a hand-swap burst as separate violations when a
     * container is up: `HELD_ITEM_CHANGE` is InventoryA "change slot"
     * (`common/.../check/impl/inventory/InventoryA.java:111-116`) and `SWAP_ITEM_WITH_OFFHAND` is
     * InventoryA "swap offhand" off the PLAYER_DIGGING branch (`:140-147`). Vanilla can produce
     * neither with a screen open, so this is not a tolerance - it is a shape vanilla never has.
     *
     * Optional like every other protection here, but turning it off does not buy speed: it only
     * lets the burst go out into a window where it is certain to be flagged.
     */
    private val noSwapWithOpenInventory by boolean("NoSwapWithOpenInventory", true)

    /** Last tick a stop was requested or a swap window was seen up, or [NOT_ARMED]. */
    private var requestedTick = NOT_ARMED

    /** First tick of the current continuous run, for the [MAX_STOP_TICKS] budget. */
    private var runStartedTick = NOT_ARMED

    /** Tick a new run may be started again after one was abandoned, or [NOT_ARMED]. */
    private var cooldownUntilTick = NOT_ARMED

    override val running: Boolean
        get() = super.running && inGame

    /**
     * Whether a container click sent RIGHT NOW would survive both anticheats.
     *
     * Every movement key plus jump, which is Grim's `knownInput.moving()` exactly, plus sneak for
     * its `isVerboseSneaking`. The two state reads after them are the ones the input record cannot
     * answer, because Grim takes them from the entity-action packets rather than from the input:
     * `isSprinting` and `isShiftKeyDown`.
     *
     * When [enabled] is off this is always true, which restores the previous behaviour exactly.
     */
    val isQuiet: Boolean
        get() {
            if (!enabled) {
                return true
            }

            val player = mc.player ?: return false

            val keys = player.input.keyPresses

            // THE SPRINT KEY IS DELIBERATELY NOT READ HERE, and reading it was the freeze reported on
            // 2026-09-14 (the client stopping the player hard, at times nothing needed a swap).
            // This used to be `keyPresses == Input.EMPTY`, and `Input` is a SEVEN-field record whose
            // last field is `keySprint.isDown()` (`client/player/KeyboardInput.tick`,
            // `world/entity/player/Input.java:6`) - a field NOTHING here can clear. The stop runs
            // through `MovementInputEvent`, which carries direction, jump and sneak only, while that
            // field is decided by the separate `SprintEvent` this object does not handle. So for
            // anyone holding the sprint key - or with vanilla's Toggle Sprint on, where
            // `ToggleKeyMapping.isDown` stays true until it is pressed again - the comparison could
            // never be true. The callers then re-requested the stop every tick forever (see
            // [RETRY_COOLDOWN_TICKS]) and the player was pinned in place for a swap that could
            // never fire.
            //
            // Neither anticheat reads that bit. Grim's `knownInput.moving()`
            // (`utils/data/KnownInput.java:10-12`) is `forward || backward || left || right ||
            // jump`; the sprint STATE it does read is the entity flag, which is `player.isSprinting`
            // below - and vanilla drops that by itself once the impulse is zero
            // (`LocalPlayer.shouldStopRunSprinting`, `:919`), which is exactly what the stop causes.
            // A held sprint key with no movement is also what every vanilla player standing still
            // with Toggle Sprint on sends, so it cannot be what either check keys off.
            return !keys.anyHorizontal && !keys.jump && !keys.shift &&
                !player.isSprinting &&
                !player.isShiftKeyDown
        }

    /**
     * WHICH condition is holding [isQuiet] down, or null when nothing is.
     *
     * Diagnostic only. Exists because none of this reaches `latest.log` otherwise - `debugParameter`
     * feeds the Debug overlay, not the log - so a report of "it just does not swap" had nothing to
     * read. The order matches [isQuiet] so the name is the one that would fail first.
     */
    val quietBlocker: String?
        get() {
            if (!enabled) {
                return null
            }

            val player = mc.player ?: return "no player"
            val keys = player.input.keyPresses

            return when {
                keys.anyHorizontal -> "move key"
                keys.jump -> "jump key"
                keys.shift -> "sneak key"
                player.isSprinting -> "sprinting"
                player.isShiftKeyDown -> "sneaking"
                else -> null
            }
        }

    /**
     * The stop machine's state in one line, for the refusal log.
     *
     * `run` is how long the current continuous stop has lasted against [MAX_STOP_TICKS], and
     * `cooldown` is the ticks left of a [RETRY_COOLDOWN_TICKS] hand-back. A cooldown counting down
     * while a swap is wanted is the signature of a run that was abandoned without reaching quiet.
     */
    val stopDiagnostic: String
        get() {
            val tick = mc.player?.tickCount ?: 0
            val cooldownLeft = if (cooldownUntilTick == NOT_ARMED) -1 else cooldownUntilTick - tick
            return "stopping=$isStopping run=${runTicks ?: -1}/$MAX_STOP_TICKS cooldown=$cooldownLeft"
        }

    /**
     * Whether a hand swap - a `SetCarriedItem` or a `SWAP_ITEM_WITH_OFFHAND` - may be sent now.
     *
     * Separate from [isQuiet] because it answers a different anticheat rule and is separately
     * switchable. True when the protection is off, so the callers keep their pre-audit behaviour.
     */
    val allowsHandSwapNow: Boolean
        get() = !enabled || !noSwapWithOpenInventory || !InventoryManager.isInventoryOpenServerSide

    /** Ticks since the stop was last requested, or null when none is pending or it expired. */
    private val stopHeldTicks: Int?
        get() {
            val armed = requestedTick
            if (armed == NOT_ARMED) {
                return null
            }

            val elapsed = (mc.player?.tickCount ?: return null) - armed
            return elapsed.takeIf { it in 0..MAX_STOP_TICKS }
        }

    /** Ticks the current continuous run has lasted, or null when no run is active. */
    private val runTicks: Int?
        get() {
            val started = runStartedTick
            if (started == NOT_ARMED) {
                return null
            }

            return ((mc.player?.tickCount ?: return null) - started).takeIf { it >= 0 }
        }

    /** Whether the movement input is being held down for a swap right now. */
    val isStopping: Boolean
        get() {
            if (!running || !enabled || !stop.enabled) {
                return false
            }

            if (stopHeldTicks?.let { it <= stop.holdTicks } != true) {
                return false
            }

            // Budget blown - see [MAX_STOP_TICKS]. Hand the input back rather than freeze.
            return (runTicks ?: 0) <= MAX_STOP_TICKS
        }

    fun reset() {
        requestedTick = NOT_ARMED
        runStartedTick = NOT_ARMED
    }

    /**
     * Asks for the movement stop because a swap is WANTED, before anything has been scheduled.
     *
     * Load-bearing against a deadlock. A feature that refuses to click until [isQuiet] has, by
     * refusing, also not opened any swap window - so the derived request in [tickHandler] would
     * never fire, the stop would never happen, the quiet would never arrive and the totem would
     * never be swapped. Callers must therefore request on the same branch where they bail out.
     */
    fun requestStop() {
        val tick = mc.player?.tickCount ?: return

        // A run abandoned on the budget owes the player their movement back before we take it
        // again. The range test also clears a cooldown left behind by a `tickCount` that restarted
        // under us (a respawn, a dimension change), which would otherwise sit there unreachable.
        if (cooldownUntilTick != NOT_ARMED) {
            if (cooldownUntilTick - tick in 1..RETRY_COOLDOWN_TICKS) {
                return
            }

            cooldownUntilTick = NOT_ARMED
        }

        // A run that has gone quiet for longer than the hold is over; the next request starts a
        // fresh one, which is also what re-arms the budget after it was abandoned.
        if (stopHeldTicks?.let { it <= stop.holdTicks } != true) {
            runStartedTick = tick
        }

        requestedTick = tick
    }

    /**
     * Notices a swap window and keeps the stop alive for [holdTicks] past it.
     *
     * Derived from [windowUp] rather than from an explicit request, so a feature cannot forget to
     * release it. The owner decides what counts as its window.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        debugParameter("Swap Quiet") { isQuiet }
        debugParameter("Swap Stopping") { isStopping }
        debugParameter("Swap Stop Run") { runTicks ?: -1 }
        debugParameter("Swap Stop Cooldown") { cooldownUntilTick != NOT_ARMED }

        if (windowUp()) {
            requestStop()
        }

        // End an abandoned run so the next request can start a clean one; leaving it standing would
        // make the budget a one-shot for the rest of the session. The cooldown is what makes the
        // hand-back real against callers that ask again on the very next tick.
        if ((runTicks ?: 0) > MAX_STOP_TICKS) {
            mc.player?.let { cooldownUntilTick = it.tickCount + RETRY_COOLDOWN_TICKS }
            reset()
        }
    }

    /**
     * Zeroes the movement input for the swap window.
     *
     * [FINAL_DECISION] priority, the same `ModuleInventoryMove` uses, so this is the last word on
     * the input and no later handler re-adds movement underneath us.
     */
    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent>(FINAL_DECISION) { event ->
        if (!isStopping) {
            return@handler
        }

        event.directionalInput = DirectionalInput.NONE
        event.jump = false
        event.sneak = false
    }

}
