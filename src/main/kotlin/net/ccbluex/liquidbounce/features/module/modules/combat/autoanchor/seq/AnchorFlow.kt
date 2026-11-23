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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.CHARGE_AND_DETONATE
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.DETONATE_ONLY
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.FULL_CYCLE

/**
 * Where one anchor cycle stands. The machine is `IDLE -> (PLACE ->) (CHARGE ->) DETONATE -> DONE`, entered at
 * whichever step the world says is the first MISSING one, and leavable at any point through [AnchorPhase.ABORTED].
 */
enum class AnchorPhase {

    /** No cell targeted. */
    IDLE,

    /** The cell is empty and an anchor has to be placed into it; three interactions left. */
    PLACE,

    /** An anchor stands at `CHARGE = 0` and has to be charged with glowstone; two interactions left. */
    CHARGE,

    /** An anchor stands at `CHARGE >= 1` and only the detonating click is missing; one interaction left. */
    DETONATE,

    /** The detonating click left the client. The blast itself is the ledger's problem, not the flow's. */
    DONE,

    /** Given up: the tick budget ran out, or the shell invalidated the cycle. */
    ABORTED,
}

/**
 * What [AnchorFlow.tick] decided this tick belongs to. There is no `ABORT` move on purpose: a use has NO abort
 * packet (unlike a dig, which owes an `ABORT_DESTROY_BLOCK`), so giving up costs nothing and emits nothing.
 */
enum class AnchorMove {

    /** The flow sends nothing this tick - it is idle, waiting for a confirmation, or throttled by the delay. */
    NONE,

    /** Click the neighbour face so `BlockItem.place` pushes a respawn anchor into the cell. */
    PLACE,

    /** Click the anchor with glowstone in hand (`RespawnAnchorBlock.useItemOn`, `:78-97`). */
    CHARGE,

    /** Click the anchor with anything that is not glowstone (`RespawnAnchorBlock.useWithoutItem`, `:99-123`). */
    DETONATE,
}

/**
 * Everything the flow is allowed to know about the target cell this tick. The shell reads it from the world
 * (`AnchorView.anchorCharge`, `isReplaceable`, `isUnobstructed`) and hands it in; the flow itself never touches
 * `mc`, which is what makes the whole state machine testable headlessly.
 *
 * @param isAnchor the cell holds a `RespawnAnchorBlock` (`AnchorView.anchorCharge(pos) != null`).
 * @param charge `state.getValue(RespawnAnchorBlock.CHARGE)`, 0..4
 *   (`net/minecraft/world/level/block/RespawnAnchorBlock.java:48-50`); pass 0 when [isAnchor] is false.
 * @param replaceable the cell would accept a `BlockItem` placement (`BlockPlaceContext.canPlace`).
 * @param unobstructed no `blocksBuilding` entity intersects the anchor's collision shape in the cell
 *   (`EntityGetter.isUnobstructed`, `net/minecraft/world/level/EntityGetter.java:33-48`).
 * @param placementConfirmed the server's answer to OUR placement of this anchor has been processed, so the anchor
 *   is in Grim's world as well as on our screen. True for a cell we did not place into. See "Our anchor is not
 *   Grim's until..." in [AnchorFlow].
 */
class CellReading(
    val isAnchor: Boolean,
    val charge: Int,
    val replaceable: Boolean,
    val unobstructed: Boolean,
    val placementConfirmed: Boolean = true,
)

/**
 * The pure part of the anchor sequencer: which step of the place / charge / detonate cycle is next, how many ticks
 * the cycle has cost and when the last interaction went out. No world, no packets, no `mc` - everything it knows
 * arrives through [begin], [tick] and [onSent], and the clock is injected, which is what makes it unit-testable
 * headlessly. Modelled on `crystalaura/mine/MineJob.kt`'s `MineFlow`.
 *
 * ### One interaction class, one per tick
 * All three steps are `ServerboundUseItemOnPacket` on a block - `RespawnAnchorBlock.useItemOn`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:78-97`) for the charge, `useWithoutItem` (`:99-123`)
 * for the detonation, and the plain `BlockItem` path (`net/minecraft/world/item/Items.java:2193`) for the
 * placement. Grim's `PacketOrderI` allows ONE block interaction per tick, so a cycle is three ticks at best and
 * cannot be compressed by batching. That single fact is why this is a state machine rather than a script.
 *
 * ### There is no resume path
 * [begin] reads the world and picks the entry phase from it: replaceable and empty -> [AnchorPhase.PLACE],
 * an anchor at `CHARGE == 0` -> [AnchorPhase.CHARGE], an anchor at `CHARGE >= 1` -> [AnchorPhase.DETONATE].
 * Entering mid-cycle is the COMMON case in a fight (someone else's anchor, or one of ours from an abandoned
 * cycle), so "what is the next missing step for this cell" is the only rule; there is deliberately no separate
 * "resume an interrupted cycle" branch that could disagree with the world.
 *
 * ### The prediction asymmetry, which is the whole reason [onSent] and [pending] exist
 * The client predicts two of the three steps and none of the third:
 * - PLACE is predicted. `MultiPlayerGameMode.performUseItemOn` runs the interaction inside `startPrediction`, and
 *   `BlockItem.place` writes the block through `ClientLevel.setBlock`'s prediction path
 *   (`net/minecraft/client/multiplayer/ClientLevel.java:208-221`). So [CellReading.isAnchor] is normally true on
 *   the very next tick, and that is what advances the flow to [AnchorPhase.CHARGE].
 * - CHARGE is predicted. `RespawnAnchorBlock.charge` does `level.setBlock(pos, newState, 3)` (`:176-181`) inside
 *   the same prediction window, so [CellReading.charge] increments locally. A
 *   `ClientboundBlockChangedAckPacket` rollback puts it back to 0, which correctly makes the flow retry the
 *   charge instead of detonating a block the server still sees at `CHARGE == 0`.
 * - DETONATE is predicted by NOTHING. The client branch of `useWithoutItem` is `return InteractionResult.CONSUME`
 *   (`:120-122`) and `ClientLevel.explode` has an empty body (`ClientLevel.java:1045-1060`), so the anchor stays
 *   standing on screen and no local reading can ever confirm the blast. The flow therefore advances to
 *   [AnchorPhase.DONE] the moment the packet leaves and hands the wait to `AnchorLedger`.
 *
 * ### Our anchor is not Grim's until Grim says so
 * The client's prediction is enough for the SERVER, but not for Grim. Every `UseItemOn` on a block - the charge
 * and the detonation included - runs `AirLiquidPlace` at packet receipt (`CheckManagerListener.java:515`), which
 * reads the clicked block out of Grim's OWN world and cancels a click on air at violation level 0
 * (`scaffolding/AirLiquidPlace.java:61-89`). Grim writes our placement into that world only when it drains the
 * placement (`handleQueuedPlaces`, `CheckManagerListener.java:88-129`), and there are several ways it does not:
 * - `BlockPlace.set` (`utils/anticheat/update/BlockPlace.java:546-596`) skips the write when the new block touches
 *   our own box, or the box of any entity Grim tracks - the union of its interpolation ranges while it stands
 *   still, its box at the latest server position while it moves (`:567-587`). An entity the client has already
 *   removed still counts until the transaction AFTER its destroy packet is answered
 *   (`events/packets/PacketEntityReplication.java:393-401`) - a crystal popped this tick, for one.
 * - A live check cancelled the placement itself (`CheckManagerListener.java:517-528`), which the client cannot see
 *   either: `RotationPlace` for instance cancels on its pre-flying leg only while a buffer the client has no view
 *   of is up.
 * The server may still have accepted the block; Grim's world then gets it only from the server's block update, at
 * our pong for the transaction bound to it (`events/packets/worldreader/BasePacketWorldReader.java:155-166`) -
 * about one round trip after the click. A charge in between is a click on air.
 *
 * None of those conditions can be mirrored exactly from the client, so the gate does not try: a resolved
 * placement of ours enters [AnchorPhase.CHARGE] with [awaitingServer] set - and so does a cycle that [begin]s on an
 * anchor our client placed some other way, by hand or in an abandoned cycle - and no click leaves until the shell
 * reports [CellReading.placementConfirmed]. The shell reports it on the `ClientboundBlockChangedAckPacket` covering
 * the placement's sequence: the server sends that ack from its next connection tick
 * (`ServerGamePacketListenerImpl.java:293-297`), AFTER the block updates it sent while handling the click
 * (`:1361-1362`), so once the ack is processed the pong that made Grim apply the anchor is already on the wire
 * ahead of our charge. A placement Grim cancelled is answered by Grim's own ack at once, the client rolls its
 * prediction back on it, and the charge's re-validation aborts the cycle instead of clicking air. COST: the charge
 * after our own placement is about one round trip later than the client prediction alone would allow.
 *
 * ### The blast wait is NOT in here
 * Because DONE is reached at send time, the next cycle may start on a DIFFERENT cell while the previous blast is
 * still in flight. The old cell is hidden from the search by the ledger's pending-detonation entry, not by this
 * flow holding the module hostage.
 *
 * ### Aborting is free, and there is no Interrupt setting
 * A use has no ABORT packet, so [abort] just ends the job and the next tick may start a new cycle. That is why
 * this class exposes no interrupt policy: abandoning after the charge burns one glowstone and the whole cycle is
 * three ticks, and what it leaves behind is a STANDING CHARGED ANCHOR - worth more than it cost, and a
 * [AnchorPhase.DETONATE] entry (one tick, no items) for whoever gets there next. That is the exact opposite of an
 * abandoned crystal cycle, which leaves a crystal the enemy can pop.
 *
 * ### Hold the aim, do not re-aim per step
 * Grim's `DuplicateRotPlace` (`checks/impl/scaffolding/DuplicateRotPlace.java:36-50`) arms only when the yaw delta
 * between two rotation updates exceeds 2 degrees (`if (deltaX > 2)`) AND that delta repeats within 1e-4 across two
 * consecutive placements; everything else rewards. Clicking one cell three ticks running with the aim HELD is
 * therefore the SAFE shape - the delta is about zero and falls under the gate. The shell must take the look point
 * once, when the cycle begins, and never re-request one for [AnchorMove.CHARGE] or [AnchorMove.DETONATE] while the
 * rotation already on the wire still lands on the cell (see `aim/LookPoints.kt`).
 */
class AnchorFlow(private val clock: () -> Long = System::currentTimeMillis) {

    /** The step the cycle is on. */
    var phase: AnchorPhase = AnchorPhase.IDLE
        private set

    /**
     * Ticks this cycle has owned since [begin], AIM ticks included - a cell no rotation can reach costs the module
     * exactly as much as a slow one, and only a deadline that covers the aim can end that. This is what
     * the cycle budget (`ModuleAutoAnchor.cycleBudget`, passed in as `maxTicks`) is charged against.
     */
    var ticks: Int = 0
        private set

    /** Ticks spent in the current [phase]; reset whenever the phase advances. Diagnostic only. */
    var phaseTicks: Int = 0
        private set

    /**
     * An interaction for the current [phase] went out and its effect has not been read back yet. While set, [tick]
     * spends the tick resolving it and returns [AnchorMove.NONE], so ONE phase can never produce two clicks in
     * consecutive ticks. A resolution that fails clears the flag, which lets the NEXT tick retry the same phase -
     * that is the `ClientboundBlockChangedAckPacket` rollback path for the charge.
     */
    var pending: Boolean = false
        private set

    /**
     * Our placement is on screen but the server has not answered it yet, so every click on it is held back (class
     * KDoc, "Our anchor is not Grim's until..."). Set when a placement resolves, or a cycle [begin]s on an anchor,
     * while [CellReading.placementConfirmed] is false.
     */
    var awaitingServer: Boolean = false
        private set

    /**
     * A glowstone has been spent on this cell: the charge click was confirmed by the world.
     *
     * The only thing this may relax later is the DAMAGE thresholds - finishing a cycle that already cost an item
     * is worth slightly less damage than starting one. It must NEVER relax a hard gate (range, ray, self-damage,
     * anti-suicide): those are correctness, not economics.
     */
    var committed: Boolean = false
        private set

    /**
     * At least one interaction of THIS cycle has actually gone out.
     *
     * This is what separates "we are mid-cycle and must keep the aim so the remaining clicks still land" from
     * "we merely picked a cell and have sent nothing yet". Only the first case may pin the view: `AuraRotation.hold`
     * re-requests the rotation ALREADY on the wire, so holding it freezes the camera wherever it happens to be and
     * overrides the player's own mouse. Before the first click there is nothing to preserve - if the aim drifts the
     * next tick simply re-aims - so holding then is pure cost, and on a cell the cycle can never finish it is a
     * permanent view lock (begin -> hold -> fail -> MaxTicks -> begin again).
     *
     * Cleared by [begin] and [reset]; deliberately NOT cleared by [abort], which is read after the fact.
     */
    var engaged: Boolean = false
        private set

    /** Wall clock of the last confirmed send, kept ACROSS cycles so `AnchorSettings.delay` throttles globally. */
    private var lastSentMs: Long = 0L

    /** The flow owns its ticks in exactly the three interaction phases. */
    val active: Boolean
        get() = phase == AnchorPhase.PLACE || phase == AnchorPhase.CHARGE || phase == AnchorPhase.DETONATE

    /**
     * Interactions - and therefore, at one per tick, TICKS - still needed before the blast: 3 / 2 / 1 for
     * [AnchorPhase.PLACE] / [AnchorPhase.CHARGE] / [AnchorPhase.DETONATE], 0 otherwise. The same three counts
     * `AnchorEntry.remaining` uses, so a candidate and a running cycle are directly comparable.
     *
     * Public because target prediction needs it: a cycle entered at [AnchorPhase.PLACE] detonates up to three
     * ticks after the snapshot it was scored against, so the victim has to be extrapolated that much further.
     */
    val remaining: Int
        get() = when (phase) {
            AnchorPhase.PLACE -> FULL_CYCLE
            AnchorPhase.CHARGE -> CHARGE_AND_DETONATE
            AnchorPhase.DETONATE -> DETONATE_ONLY
            else -> 0
        }

    /**
     * Starts a cycle on a cell whose current state is [reading], entering at the first MISSING step (class KDoc).
     *
     * @return true when a cycle started, false when the cell can never explode - it holds neither an anchor nor
     * anything replaceable, or a placement into it is blocked by a `blocksBuilding` entity. The flow is left
     * [AnchorPhase.IDLE] in that case.
     */
    fun begin(reading: CellReading): Boolean {
        ticks = 0
        phaseTicks = 0
        pending = false
        // An anchor our client placed outside this cycle - by hand, or in an abandoned cycle - is waited for too.
        awaitingServer = reading.isAnchor && !reading.placementConfirmed
        committed = false
        engaged = false
        phase = entryPhase(reading) ?: AnchorPhase.IDLE
        return active
    }

    /**
     * This tick's move. [maxTicks] and [delayMs] are read live, so a setting changed mid-cycle takes effect at once.
     *
     * Order, and why it is this order:
     * 1. An inactive flow owns nothing.
     * 2. The tick counter is charged FIRST, aim ticks and confirmation-wait ticks included, and a cycle past
     *    [maxTicks] is abandoned on the spot. Nothing is owed on the way out (class KDoc).
     * 3. A [pending] interaction is resolved BEFORE anything is emitted, and an unresolved one ends the tick with
     *    [AnchorMove.NONE] - that is the rule that stops one phase from clicking twice in a row. A resolution that
     *    SUCCEEDS falls through, so the next phase may click in this very tick: that is what makes the best case
     *    three ticks rather than six.
     * 4. A placement Grim may not hold yet ([awaitingServer]) keeps every click back until the reading confirms
     *    it. Checked AFTER the resolution, because it is the resolution that sets it, so an unconfirmed placement
     *    never falls through into its charge.
     * 5. The delay gate is wall clock since the last confirmed send, not ticks - after a stall several client
     *    ticks run inside one frame, and a tick counter would let the throttle collapse.
     */
    fun tick(reading: CellReading, maxTicks: Int, delayMs: Long): AnchorMove {
        if (!active) return AnchorMove.NONE

        ticks++
        phaseTicks++
        if (ticks > maxTicks) {
            phase = AnchorPhase.ABORTED
            return AnchorMove.NONE
        }

        if (pending) {
            pending = false
            if (!resolve(reading)) return AnchorMove.NONE
        }

        if (!serverCaughtUp(reading) || clock() - lastSentMs < delayMs) return AnchorMove.NONE

        return moveOf(phase)
    }

    /** Clears [awaitingServer] once [reading] confirms our placement; false while the click is still held back. */
    private fun serverCaughtUp(reading: CellReading): Boolean {
        if (awaitingServer && reading.placementConfirmed) {
            awaitingServer = false
        }
        return !awaitingServer
    }

    /**
     * The interaction [move] actually left the client - the shell calls this only after the packet was sent, never
     * for a tick whose re-validation failed. A move that does not belong to the current [phase] is ignored, so a
     * stale plan can never advance the machine.
     *
     * [AnchorMove.DETONATE] goes straight to [AnchorPhase.DONE]: there is nothing to resolve, because the client
     * predicts no part of the explosion (class KDoc). The other two arm [pending].
     */
    fun onSent(move: AnchorMove) {
        if (!active || move != moveOf(phase)) return

        lastSentMs = clock()
        engaged = true
        if (phase == AnchorPhase.DETONATE) {
            pending = false
            phase = AnchorPhase.DONE
        } else {
            pending = true
        }
    }

    /**
     * Gives up on the current cycle. Free and instantaneous: a use has no abort packet, so nothing is owed and the
     * next tick may begin a new cycle on another cell. [committed] is deliberately left standing - it records that
     * a glowstone was spent, which stays true after the abort.
     */
    fun abort() {
        pending = false
        awaitingServer = false
        phase = AnchorPhase.ABORTED
    }

    /** Forgets everything, the send clock included; for the module's own state reset (disable, world change). */
    fun reset() {
        phase = AnchorPhase.IDLE
        ticks = 0
        phaseTicks = 0
        pending = false
        awaitingServer = false
        committed = false
        engaged = false
        lastSentMs = 0L
    }

    override fun toString(): String =
        "$phase t=$ticks/$phaseTicks pending=$pending awaitingServer=$awaitingServer committed=$committed " +
            "engaged=$engaged"

    /**
     * The first missing step for a cell in state [reading], or null when the cell can never hold an exploding
     * anchor. The asymmetry matters: [CellReading.replaceable] is only consulted when the cell is NOT an anchor,
     * because an anchor standing there is never a placement target - clicking it charges or detonates it.
     */
    private fun entryPhase(reading: CellReading): AnchorPhase? = when {
        reading.isAnchor && reading.charge >= 1 -> AnchorPhase.DETONATE
        reading.isAnchor -> AnchorPhase.CHARGE
        reading.replaceable && reading.unobstructed -> AnchorPhase.PLACE
        else -> null
    }

    /**
     * Reads the world back after a [pending] interaction and advances the phase when it landed.
     *
     * @return true when the phase advanced (the caller may emit the NEW phase's move this same tick), false when
     * the interaction is not visible yet - the phase stays put and the next tick retries it, priced by
     * the cycle budget.
     */
    private fun resolve(reading: CellReading): Boolean = when (phase) {
        AnchorPhase.PLACE -> reading.isAnchor.also {
            if (it) {
                awaitingServer = !reading.placementConfirmed
                enter(AnchorPhase.CHARGE)
            }
        }
        AnchorPhase.CHARGE -> (reading.isAnchor && reading.charge >= 1).also {
            if (it) {
                committed = true
                enter(AnchorPhase.DETONATE)
            }
        }
        // DETONATE never arms `pending` (class KDoc), so this branch is unreachable in practice.
        else -> true
    }

    private fun enter(next: AnchorPhase) {
        phase = next
        phaseTicks = 0
    }

    private fun moveOf(current: AnchorPhase): AnchorMove = when (current) {
        AnchorPhase.PLACE -> AnchorMove.PLACE
        AnchorPhase.CHARGE -> AnchorMove.CHARGE
        AnchorPhase.DETONATE -> AnchorMove.DETONATE
        else -> AnchorMove.NONE
    }
}
