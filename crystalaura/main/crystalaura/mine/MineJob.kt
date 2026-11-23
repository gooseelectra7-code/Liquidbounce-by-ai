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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.MineSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.PlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.SwitchMode
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoint
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoints
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.RayTests
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.blockDistanceSq
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.MiningClaim
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doBreak
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findBestToolToMineBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult

/** Where one [MineJob] stands; the machine is `IDLE -> AIM -> DIG -> SETTLE -> DIG -> DONE | ABORTED`. */
enum class MinePhase {
    /** No target. */
    IDLE,

    /** A cell is targeted, the aim is being brought onto it, no `START_DESTROY_BLOCK` has gone out for it yet. */
    AIM,

    /** A `START_DESTROY_BLOCK` for the current cell is outstanding; an `ABORT` is owed if we stop now. */
    DIG,

    /** The cell fell; the pacing window before the next cell's `START` (see [MineFlow.settle]). */
    SETTLE,

    /** Every cell of the target is gone. */
    DONE,

    /** Given up, interrupted or invalidated. */
    ABORTED,
}

/** What [MineFlow.tick] decided this tick belongs to. */
enum class MineMove {
    /** The job does not own this tick. */
    NONE,

    /** The job owns the tick and calls `doBreak` on the current cell. */
    DIG,

    /** The job owns the tick, aims, and sends nothing (the pacing floor of [MineFlow.settle]). */
    HOLD,

    /** The job owns the tick and ends it with `interaction.stopDestroyBlock()`. */
    ABORT,
}

/**
 * What [MineJob.plan] decided for this tick, consumed once by [MineJob.execute].
 *
 * [look] is the rotation to request (null: none was reachable this tick, the caller holds the current one) and
 * [slot] the mining tool to bring into the hand at the tick HEAD - both are filed by the hub, exactly as the
 * aura's own `TickPlan.look` / `TickPlan.slot` are, so that `gameMode.tick()` emits the `SetCarriedItem` before
 * anything is clicked (`MultiPlayerGameMode.tick`, `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:
 * 297-304` -> `ensureHasSentCarriedItem`, `:311-317`).
 *
 * [entity] is the victim the mined base was scored against, passed to `AuraRotation.request` as its
 * reset-detection entity so that a `SMOOTH` step towards the cell is not restarted every tick.
 */
class MinePlan(
    val move: MineMove,
    val pos: BlockPos?,
    val face: Direction?,
    val look: LookPoint?,
    val slot: HotbarItemSlot?,
    val entity: Entity?,
)

/**
 * The pure part of [MineJob]: which phase the dig is in, which cell it is on, how many ticks that cell has cost and
 * when the last break completed. No world, no packets, no `mc` - everything it knows arrives through [tick] and the
 * three observations [onStarted] / [onFinished] / [onSwallowed], which is what makes the pacing and the give-up
 * paths testable headlessly.
 *
 * ### Pacing: vanilla's own delay plus a wall-clock floor
 * `MultiPlayerGameMode.continueDestroyBlock` sets `destroyDelay = 5` on the tick a break completes
 * (`MultiPlayerGameMode.java:278`) and every later call consumes one of those five and sends NOTHING
 * (`:225-227`, it merely returns true so the caller still swings). The sixth call is the one that reaches
 * `startDestroyBlock` and emits `START_DESTROY_BLOCK`, i.e. vanilla itself paces two breaks six client ticks =
 * 300 ms apart, which is exactly what Grim's `FastBreak` wants: it adds `300 - gapMs` to a balance for every gap
 * below 275 ms and flags once that balance passes 1000 ms
 * (`checks/impl/breaking/FastBreak.java:81-96`), so a 300 ms gap is neutral and decays the balance instead.
 *
 * Two holes make the wall-clock floor necessary on top of it. An INSTANT break never sets `destroyDelay` - the
 * non-creative instant path of `startDestroyBlock` (`:188-190`) calls `destroyBlock` and returns without touching
 * it (the only writes are `:166`, `:229` and `:278`, all creative or completion) - so nothing would separate two
 * instant breaks. And ticks are not always 50 ms apart: after a stall the client runs several ticks in one frame,
 * which would compress six ticks into far less than 300 ms. [settle] therefore lets vanilla swallow the calls it
 * is going to swallow anyway ([expectedSwallow], 5 after a real break and 0 after an instant one) and only then
 * gates the call that would BECOME the `START` on [MineJob.PACING_MS] of real time. Skipping a call is always
 * safe: the delay only ever counts down through calls we make, so a skipped tick can never produce an early START.
 *
 * ### Ticks are counted per cell, aim included
 * `Mine.MaxTicks` is charged from the moment a cell becomes the current one, not from its `START`. A cell whose
 * face no rotation can reach costs the aura exactly as much as a slow one - the job owns its ticks either way -
 * and only a deadline that covers the aim can end that.
 */
internal class MineFlow(private val clock: () -> Long = System::currentTimeMillis) {

    var phase: MinePhase = MinePhase.IDLE
        private set

    /** Index into `MineTarget.cells`; the list is ordered top-down and holds one or two entries. */
    var cell: Int = 0
        private set

    /** Ticks this job has owned for [cell], aim ticks included. */
    var cellTicks: Int = 0
        private set

    /**
     * The face the `START` went out with, frozen for the rest of the cell.
     *
     * `MultiBreak` accepts one `(position, face)` pair per tick across the non-ABORT dig packets
     * (`checks/impl/breaking/MultiBreak.java:41`) and `PositionBreakB` remembers the face of the last ABORT and
     * flags the next dig packet that names a different one (`checks/impl/breaking/PositionBreakB.java:26-44`), so
     * the `STOP` that ends a dig must carry the face its `START` carried.
     */
    var face: Direction? = null
        private set

    /** True while a `START_DESTROY_BLOCK` is outstanding, i.e. an `ABORT` is owed before the job may stop. */
    var started: Boolean = false
        private set

    private var cellCount = 0
    private var finishedAt = 0L
    private var expectedSwallow = 0
    private var swallowed = 0

    /** The job owns its ticks in exactly these three phases. */
    val active: Boolean
        get() = phase == MinePhase.AIM || phase == MinePhase.DIG || phase == MinePhase.SETTLE

    /** Starts a job over [cells] cells (`MineTarget.cells.size`, 1 or 2). */
    fun begin(cells: Int) {
        phase = MinePhase.AIM
        cell = 0
        cellTicks = 0
        cellCount = cells
        face = null
        started = false
        finishedAt = 0L
        expectedSwallow = 0
        swallowed = 0
    }

    /** Forgets everything; the caller has already released the claim. */
    fun reset() {
        phase = MinePhase.IDLE
        cell = 0
        cellTicks = 0
        cellCount = 0
        face = null
        started = false
    }

    /**
     * This tick's move. [maxTicks] and [pacingMs] are read live, so a setting changed mid-dig takes effect at once.
     * A cell that outlived [maxTicks] ends the job through [MineMove.ABORT] - the shell still owes the `ABORT`
     * packet when [started].
     */
    fun tick(maxTicks: Int, pacingMs: Long): MineMove {
        if (!active) return MineMove.NONE

        cellTicks++
        if (cellTicks > maxTicks) {
            phase = MinePhase.ABORTED
            return MineMove.ABORT
        }
        return if (phase == MinePhase.SETTLE) settle(pacingMs) else MineMove.DIG
    }

    /**
     * The pacing window (class KDoc): the calls vanilla is going to swallow are made straight away, because they
     * carry no packet and running them down in parallel with the clock is what makes the next `START` land at the
     * vanilla 300 ms instead of at 600. The call that would become the `START` waits for [pacingMs] of real time.
     */
    private fun settle(pacingMs: Long): MineMove {
        if (swallowed < expectedSwallow) return MineMove.DIG

        return if (clock() - finishedAt >= pacingMs) MineMove.DIG else MineMove.HOLD
    }

    /** A `START_DESTROY_BLOCK` went out with [digFace]; the cell is now being destroyed. */
    fun onStarted(digFace: Direction) {
        phase = MinePhase.DIG
        face = digFace
        started = true
    }

    /**
     * The cell fell: either the `STOP_DESTROY_BLOCK` of `continueDestroyBlock` (`MultiPlayerGameMode.java:267-278`)
     * or, with [instant], the `START`-only instant break of `startDestroyBlock` (`:188-190`). Advances to the next
     * cell or to [MinePhase.DONE] and opens the pacing window.
     */
    fun onFinished(instant: Boolean) {
        started = false
        face = null
        finishedAt = clock()
        expectedSwallow = if (instant) 0 else VANILLA_DESTROY_DELAY
        swallowed = 0
        cell++
        cellTicks = 0
        phase = if (cell >= cellCount) MinePhase.DONE else MinePhase.SETTLE
    }

    /** A `doBreak` call that `destroyDelay` consumed without sending anything (`MultiPlayerGameMode.java:225-227`). */
    fun onSwallowed() {
        swallowed++
    }

    /** The `ABORT_DESTROY_BLOCK` went out (or nothing was owed); the job is over. */
    fun onAborted() {
        started = false
        face = null
        phase = MinePhase.ABORTED
    }

    override fun toString(): String = "$phase cell=$cell/$cellCount t=$cellTicks"

    companion object {

        /**
         * `destroyDelay = 5` after a completed break (`MultiPlayerGameMode.java:278`); each further
         * `continueDestroyBlock` consumes one and sends nothing (`:225-227`), so the sixth call is the `START`.
         */
        const val VANILLA_DESTROY_DELAY = 5
    }
}

/**
 * Breaks the one or two cells of a [MineTarget], one cell at a time, by driving vanilla's own progressive breaking.
 * One instance per module; main thread only, and every packet it sends leaves from the module's EXECUTE step.
 *
 * ### Packets: drive `MultiPlayerGameMode`, never forge
 * The dig is `doBreak(hit, swingMode = DO_NOT_HIDE)` (`utils/block/BlockExtensions.kt:576`, vanilla path
 * `:608-611`) once per owned tick, which is `interaction.continueDestroyBlock(pos, direction)` plus the swing and
 * the breaking particles - the same three things `Minecraft.continueAttack` does while the attack key is held
 * (`net/minecraft/client/Minecraft.java:1648-1671`, the swing at `:1661-1664` only when `continueDestroyBlock`
 * returned true). `continueDestroyBlock` (`MultiPlayerGameMode.java:223-287`) then does everything by itself:
 * `ensureHasSentCarriedItem()` first (`:224`), the `START` through `startPrediction` on the first tick (`:199`,
 * i.e. with a correct monotonic sequence), nothing at all on the middle ticks, the `STOP` through `startPrediction`
 * when the progress reaches 1.0 (`:274`), `START`-only for an instant break (`:188-190`), and no packet whatsoever
 * when the target turned to air under us (`:243-245`).
 *
 * `doBreak(immediate = true)` is never used: it sends `START` and `STOP` from one call
 * (`BlockExtensions.kt:591-605`), which is two dig packets in one tick.
 *
 * ### Aborting, and why the next cell waits for a later tick
 * Only `interaction.stopDestroyBlock()` (`MultiPlayerGameMode.java:207-221`) may end a dig. It sends
 * `ABORT_DESTROY_BLOCK` at `destroyBlockPos` with a hardcoded `Direction.DOWN` through the three-argument
 * `ServerboundPlayerActionPacket` constructor, i.e. with sequence 0
 * (`net/minecraft/network/protocol/game/ServerboundPlayerActionPacket.java:26-28`) - which is what
 * `BadPacketsH` requires of a cancelled dig (`checks/impl/badpackets/BadPacketsH.java:59-63`: a non-zero sequence
 * on `CANCELLED_DIGGING` is cancelled) and what `PositionBreakB` reads as "no face"
 * (`checks/impl/breaking/PositionBreakB.java:39-43`: `lastFace = faceId == 0 ? null : face`).
 *
 * Retargeting by calling `continueDestroyBlock` / `startDestroyBlock` on a new position while the client is still
 * destroying is therefore never done. Vanilla's own retarget path sends the `ABORT` for the OLD position with the
 * NEW direction (`MultiPlayerGameMode.java:173`), which `PositionBreakB` stores as `lastFace` and holds against
 * the next dig packet that names a different face - and our next cell is re-aimed, so its face regularly differs.
 * It also puts two dig packets into one tick for no gain. The job aborts in one tick and starts the next cell in a
 * later one.
 *
 * ### The tick budget: digging is a THIRD mutually exclusive action class
 * `PacketOrderI` flags an attack, an entity interact or a release-use that follows ANY dig packet - `ABORT`
 * included, through `PacketOrderProcessor.isDigging()` - inside one tick
 * (`checks/impl/packetorder/PacketOrderI.java:81-96`, `:195-210`, `:134-149`), and a block placement or
 * `USE_ITEM` after a `FINISHED_DIGGING`, a `CANCELLED_DIGGING` or a non-instant `START_DIGGING` (`:98-123`).
 * `MultiActionsF` sets its block channel on `START_DIGGING` / `FINISHED_DIGGING`
 * (`checks/impl/multiactions/MultiActionsF.java:78-92`), so the reverse order flags symmetrically.
 *
 * A tick this job owns therefore carries no aura attack and no aura use, and the middle ticks of a dig - which
 * send no `PLAYER_ACTION` at all, only the swing - are owned as well, because two swings in one tick is not
 * something a vanilla client produces. The hub enforces that by planning nothing else on a tick [plan] answers.
 *
 * ### Rotation
 * The aim is held on the cell for the whole dig: `RotationBreak` raytraces the full block cube from the movement
 * rotation of the tick the dig packet arrives in (`checks/impl/breaking/RotationBreak.java:56-75` post-flying,
 * `didRayTraceHit` `:77-126`). [plan] asks `LookPoints` for a reachable face (any visible one before the first
 * `START`, the frozen [MineFlow.face] afterwards) and [execute] re-traces the ray with `RayTests.hitsBlock` against
 * `AuraRotation.outgoing` and, failing that, `AuraRotation.wire` - the two exact rotations `RotationBreak` itself
 * tries (`:99-112` uses `(lastYaw, pitch)`, `(yaw, pitch)` and, for 1.9+ clients, `(lastYaw, lastPitch)`) and the
 * same discipline `ActionExecutor.byAim` applies to the aura's own clicks. No tolerance is widened: a tick whose
 * exact ray misses sends nothing and the next one retries.
 *
 * The face put on the wire is the `direction` of that very raytrace, so it is a face the eye is genuinely outside
 * of (`checks/impl/breaking/PositionBreakA.java:51-59`) and trivially inside `0..5`
 * (`checks/impl/breaking/InvalidBreak.java:27`).
 *
 * ### Range, tool and arbitration
 * The cell must stay inside `Mine.Range` measured eye to `AABB(pos)` - the lower bound of the eye-to-hit-point
 * distance and the same quantity `FarBreak` clamps the eye into (`checks/impl/breaking/FarBreak.java:23-49`,
 * full block cube against `BLOCK_INTERACTION_RANGE`) and the server checks with
 * `Player.isWithinBlockInteractionRange(pos, 1.0)` (`net/minecraft/server/level/ServerPlayerGameMode.java:155`,
 * `net/minecraft/world/entity/player/Player.java:1980-1983`).
 *
 * The tool is `Slots.Hotbar.findBestToolToMineBlock(state)` (`utils/inventory/InventoryUtils.kt:95`, null in
 * creative, `:105`), chosen BEFORE the first `START` of a cell and never changed until that cell is gone:
 * `sameDestroyTarget` compares the held stack (`MultiPlayerGameMode.java:306-309`), so a mid-dig switch would
 * drive `continueDestroyBlock` into the retarget path described above. `Player.getDestroySpeed` reads
 * `Inventory.getSelectedItem()` (`net/minecraft/world/entity/player/Player.java:585`), which LiquidBounce's
 * `MixinInventory` redirects to `SilentHotbar.serversideSlot`, so a silently selected tool is the one the client
 * predicts with as well as the one the server sees.
 *
 * `MiningClaim` is consulted before any dig packet and released when the job ends: a `false` claim means send
 * nothing (`utils/block/MiningClaim.kt:104-105`). The claim is also what [cancelsBreaking] answers, because
 * vanilla's `continueAttack` tears an unheld dig down once per tick (`Minecraft.java:1666-1668`, reached from the
 * last statement of `handleKeybinds`, `:2073`) - the same shape `ModuleAutoMine.kt:306-308` uses.
 *
 * ### What is NOT here
 * The obsidian that follows the dig is placed by the existing `BasePlaceSearch` path on a LATER tick (this job
 * owns every tick that carries a dig packet, so the placement cannot share one). It can never be clicked against
 * a cell we broke: `BasePlaceSearch.isClickable` refuses a replaceable neighbour and a broken cell is air, and its
 * replace-in-place form refuses air too (`isReplaceInPlace` wants a pickable shape) - which is exactly the block
 * `AirLiquidPlace` resyncs a placement against at cancel VL 0
 * (`checks/impl/scaffolding/AirLiquidPlace.java:57-85`, `getDefaultCancelVL` `:87-90`; its only escape hatch,
 * `:67-78`, wants a modification younger than two ticks caused by `START_DIGGING` or a netty sync, i.e. an instant
 * break). `MineTarget.click` is the search's proof that such a neighbour will still exist; it is not re-clicked
 * from here.
 */
@Suppress("TooManyFunctions")
class MineJob(
    private val settings: MineSettings,
    private val place: PlaceSettings,
) : MinecraftShortcuts {

    /** The pure state machine; everything headlessly testable lives there and is exercised by `MineFlowTest`. */
    private val flow = MineFlow()

    private var target: MineTarget? = null

    /** The tool frozen for the current cell ([SwitchMode.NONE]: always null, dig with whatever is held). */
    private var tool: HotbarItemSlot? = null

    /** Ticks left in which no new job may start ([INTERRUPT_COOLDOWN_TICKS]). */
    private var cooldown = 0

    /** True only while [sendAbort] runs, so our own `stopDestroyBlock` is not cancelled by [cancelsBreaking]. */
    private var aborting = false

    /** The job owns this tick; the hub plans no attack and no use while this holds. */
    val active: Boolean
        get() = flow.active

    /**
     * Whether `CancelBlockBreakingEvent` must be cancelled right now: while the dig is ours, and never while we
     * are the ones calling `stopDestroyBlock`. The event is fired at the HEAD of that method
     * (`MixinMultiPlayerGameMode.java:85-93`), so without the second half our own abort would cancel itself.
     */
    val cancelsBreaking: Boolean
        get() = !aborting && MiningClaim.isHeldBy(this)

    /** One-liner for the module's `Debug` tracing. */
    fun describe(): String {
        val current = target ?: return "mine idle" + if (cooldown > 0) " cd=$cooldown" else ""
        val cell = current.cells.getOrNull(flow.cell)
        val where = if (cell == null) "-" else "(${cell.x} ${cell.y} ${cell.z})"
        return "mine $flow $where" + (flow.face?.let { " ${it.name}" } ?: "")
    }

    /**
     * Decides this tick, at the module's PLAN step. Returns null when the job does not own the tick - then the
     * aura plans normally.
     *
     * [target] is a freshly searched plan and is only looked at when nothing is running; [auraOffers] is
     * `TickPlan.attempting`, i.e. whether the aura has an action ready NOW. A job never starts against an offered
     * action (digging must not displace something we can do this tick) and, with `Mine.Interrupt`, a running one
     * gives up the moment such an action appears - paying the `ABORT` its own tick and then refusing to start
     * again for [INTERRUPT_COOLDOWN_TICKS].
     */
    fun plan(
        view: WorldView,
        target: MineTarget?,
        auraOffers: Boolean,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
    ): MinePlan? {
        if (cooldown > 0) cooldown--
        if (flow.started && !interaction.isDestroying) surrender()
        if (!flow.active) {
            endJob()
            if (target == null || !canStart(target, auraOffers)) return null

            this.target = target
            flow.begin(target.cells.size)
        }
        return tickActive(view, auraOffers, slack, rayIgnore)
    }

    /**
     * One tick of a running job: re-validate, renew the claim, ask [MineFlow] for the move and dress it with the
     * aim and the tool. Every way out is [abort], which decides by itself whether the `ABORT` packet is owed.
     */
    private fun tickActive(
        view: WorldView,
        auraOffers: Boolean,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
    ): MinePlan? {
        val cell = currentCell()
        if (cell == null || !canDig() || !stillValid(view, cell) || !MiningClaim.claim(this, cell, CLAIM_TICKS)) {
            return abort(0)
        }
        if (settings.interrupt && auraOffers) return abort(INTERRUPT_COOLDOWN_TICKS)

        val move = flow.tick(settings.maxTicks, PACING_MS)
        if (move != MineMove.DIG && move != MineMove.HOLD) return abort(INTERRUPT_COOLDOWN_TICKS)

        if (flow.face == null) tool = toolFor(cell)
        val look = lookFor(view, cell, slack, rayIgnore)
        return MinePlan(move, cell, flow.face, look, tool, target?.candidate?.target?.entity)
    }

    /**
     * Performs [plan] at the module's EXECUTE step (the RETURN of `Minecraft.handleKeybinds`, after vanilla's own
     * click loops and its `continueAttack`). [allowDig] is the hub's foreign-click gate: a dig packet may not join
     * a tick in which somebody else already clicked, in either order (`PacketOrderI`, `MultiActionsF`).
     *
     * Returns a one-line report for the module's `Debug` log; null is never returned so the trace always says why
     * a tick sent nothing.
     */
    fun execute(
        plan: MinePlan,
        view: WorldView,
        rayIgnore: (Entity) -> Boolean,
        allowDig: Boolean,
        foreignDig: Boolean,
    ): String {
        if (foreignDig) return surrender()
        if (plan.move == MineMove.ABORT) return sendAbort()

        return if (plan.move == MineMove.HOLD) "pacing" else dig(plan, view, rayIgnore, allowDig)
    }

    /**
     * Ends the job WITHOUT sending anything, because the client-side dig is no longer ours to abort.
     *
     * Two ways that happens, and `MiningClaim` stops neither: the player's own mouse, whose
     * `continueDestroyBlock` on another position takes the retarget path and has already sent the `ABORT` for our
     * cell (`MultiPlayerGameMode.java:167-175`) - so `destroyBlockPos` is theirs and a `stopDestroyBlock()` of
     * ours would abort THEIR dig - and anything that simply cleared `isDestroying` under us (the block turning to
     * air, `:243-245`, or a `stopDestroyBlock` we did not cancel). Releasing the claim also hands vanilla's own
     * per-tick teardown back, which is the correct `ABORT` for whatever is actually in progress.
     */
    private fun surrender(): String {
        flow.onAborted()
        endJob()
        return "dig no longer ours"
    }

    /**
     * The one `doBreak` of an owned tick. The hit is the one traced HERE against the outgoing rotation - never the
     * one the look point promised - and its `direction` is what goes on the wire, so the face is always one this
     * client's own pick produced.
     */
    private fun dig(plan: MinePlan, view: WorldView, rayIgnore: (Entity) -> Boolean, allowDig: Boolean): String {
        if (!allowDig) return "dig blocked this tick"

        val cell = plan.pos
        if (cell == null || !canDig() || !MiningClaim.isHeldBy(this)) return "dig gate closed"

        val hit = byAim(AuraRotation.outgoing) { rotation ->
            RayTests.hitsBlock(view.eye, rotation, cell, digRange, rayIgnore, throughBlocks)
                ?.takeIf { plan.face == null || it.direction == plan.face }
        } ?: return "dig ray misses"

        val destroying = interaction.isDestroying
        doBreak(hit, swingMode = SwingMode.DO_NOT_HIDE)
        return observe(hit, destroying)
    }

    /** Drops the job and its claim; called from the module's own reset (disable, world change, level mismatch). */
    fun reset() {
        endJob()
        cooldown = 0
    }

    /** Gives the claim back and forgets the target; the flow is already in a terminal phase when this runs. */
    private fun endJob() {
        MiningClaim.release(this)
        flow.reset()
        target = null
        tool = null
    }

    /**
     * Reads back what `doBreak` did, from the two things it changes observably: `isDestroying`
     * (`MultiPlayerGameMode.java:191`, `:216`, `:244`, `:267`) and the client-side block, which the `STOP` and the
     * instant break both clear through `destroyBlock` (`:117-147`).
     *
     * - not destroying -> destroying: the `START` went out, the dig is live.
     * - destroying -> not destroying: the `STOP` went out and the cell is gone. The one other way out of
     *   `isDestroying` is the target having turned to air under us (`:243-245`), which sends nothing - the cell is
     *   gone either way and the pacing floor costs 300 ms we did not have to pay, never the other way round.
     * - neither, but the cell is now air: the instant break of `startDestroyBlock` (`:188-190`), a `START` with no
     *   `STOP` and, crucially, without vanilla's `destroyDelay` - see [MineFlow].
     * - neither, and the cell still stands: `destroyDelay` swallowed the call (`:225-227`); only the swing went out.
     */
    private fun observe(hit: BlockHitResult, destroyingBefore: Boolean): String {
        val destroyingNow = interaction.isDestroying
        val gone = world.getBlockState(hit.blockPos).isAir
        return when {
            destroyingBefore && !destroyingNow -> {
                flow.onFinished(instant = false)
                "finished ${hit.blockPos.toShortString()}"
            }
            destroyingNow -> {
                if (!destroyingBefore) flow.onStarted(hit.direction)
                "digging ${hit.blockPos.toShortString()} ${hit.direction.name}"
            }
            gone -> {
                flow.onStarted(hit.direction)
                flow.onFinished(instant = true)
                "instant ${hit.blockPos.toShortString()}"
            }
            else -> {
                flow.onSwallowed()
                "delay"
            }
        }
    }

    /**
     * `interaction.stopDestroyBlock()` (`MultiPlayerGameMode.java:207-221`), which sends the `ABORT` only while
     * `isDestroying`, with [aborting] set so [cancelsBreaking] lets our own call through. Then the job is over.
     */
    private fun sendAbort(): String {
        val owed = interaction.isDestroying
        aborting = true
        try {
            interaction.stopDestroyBlock()
        } finally {
            aborting = false
        }
        flow.onAborted()
        endJob()
        return if (owed) "aborted" else "abort not owed"
    }

    /**
     * Ends the job. When a `START` is outstanding the `ABORT` owes its own tick - it may not share one with the
     * action that displaced us (`PacketOrderI`) - so the tick is claimed with [MineMove.ABORT]; otherwise nothing
     * is owed, the job stops here and the aura still gets this tick.
     */
    private fun abort(cooldownTicks: Int): MinePlan? {
        cooldown = maxOf(cooldown, cooldownTicks)
        val owed = flow.started
        flow.onAborted()
        if (!owed) {
            endJob()
            return null
        }
        return MinePlan(MineMove.ABORT, null, null, null, null, null)
    }

    /** A new job may start: a plan, nothing offered by the aura, no cooldown, the gates open, the dig is ours. */
    private fun canStart(target: MineTarget, auraOffers: Boolean): Boolean {
        if (target.cells.isEmpty() || auraOffers || cooldown > 0) return false

        return canDig() && MiningClaim.claim(this, target.cells[0], CLAIM_TICKS)
    }

    /**
     * The gates a vanilla client applies before it may continue an attack on a block: alive, not a spectator, no
     * item in use and no `PIERCING_WEAPON` in the main hand - `Minecraft.continueAttack` runs only while
     * `missTime <= 0 && !player.isUsingItem()` (`Minecraft.java:1653`) and only for a hand without
     * `DataComponents.PIERCING_WEAPON` (`:1655`), and Grim mirrors both (`MultiActionsB.java:19` for digging
     * while using an item, `AirLiquidBreak.java:69` for the piercing weapon on 1.21.11+ clients).
     *
     * The main-hand item is read AFTER the tool switch of the previous tick took effect, because
     * `Inventory.getSelectedItem` follows `SilentHotbar.serversideSlot` (`MixinInventory.java:46-53`).
     */
    private fun canDig(): Boolean {
        if (!player.isAlive || player.isSpectator || player.isUsingItem || player.isHandsBusy) return false
        if (mc.missTime > 0) return false

        return !player.getItemInHand(InteractionHand.MAIN_HAND).has(DataComponents.PIERCING_WEAPON)
    }

    /**
     * Re-validation of the running plan against the live world: the cell is still inside `Mine.Range` of the eye,
     * it is still something that can be mined away into air ([isDiggable] - which refuses air, any non-empty fluid
     * and hardness `-1`), and every cell this job already cleared is still air.
     *
     * The plan's WORTH is deliberately not re-scored here. `MineSearch` prices a mined base against the best
     * placement available right now, and re-running it every tick while the aura is barred from placing would
     * abort the job on the first candidate the search itself made unreachable. "Something better is available" is
     * `Mine.Interrupt`'s job, and "this is taking too long" is `Mine.MaxTicks`'.
     */
    private fun stillValid(view: WorldView, cell: BlockPos): Boolean {
        if (blockDistanceSq(cell.x, cell.y, cell.z, view.eye) >= digRangeSq) return false
        if (!isDiggable(world, cell, view.state(cell))) return false

        val cells = target?.cells ?: return false
        return (0 until flow.cell).all { view.state(cells[it]).isAir }
    }

    /**
     * The aim for this tick: any visible face while no `START` has gone out yet ([LookPoints.forPlacement] with no
     * crystal to serve, which samples every face the eye lies beyond), and the frozen face afterwards
     * ([LookPoints.forFace]). Null when no sampled ray reaches the cell - the tick is still ours, the hub holds
     * the current rotation and `Mine.MaxTicks` prices the wait.
     */
    private fun lookFor(view: WorldView, cell: BlockPos, slack: Float, rayIgnore: (Entity) -> Boolean): LookPoint? {
        val wire = AuraRotation.wire
        val frozen = flow.face
        return if (frozen == null) {
            LookPoints.forPlacement(
                view.eye, wire, cell, digRange, null, digRange, rayIgnore, slack, LOOK_BUDGET, throughBlocks
            )
        } else {
            LookPoints.forFace(
                view.eye, wire, cell, frozen, digRange, rayIgnore, slack, LOOK_BUDGET, throughBlocks
            )
        }
    }

    /**
     * The best hotbar tool for [cell], or null with [SwitchMode.NONE] and in creative
     * (`findBestToolToMineBlock` filters on `!player.isCreative`, `utils/inventory/InventoryUtils.kt:105`).
     * Resolved once per cell, before its first `START` (class KDoc).
     */
    private fun toolFor(cell: BlockPos): HotbarItemSlot? {
        if (settings.tool == SwitchMode.NONE) return null

        return Slots.Hotbar.findBestToolToMineBlock(world.getBlockState(cell))
    }

    private fun currentCell(): BlockPos? = target?.cells?.getOrNull(flow.cell)

    /** `PlaceSettings.ThroughBlocks`: dig a cell the eye cannot see, see that setting for why it is legal. */
    private val throughBlocks: Boolean
        get() = place.throughBlocks

    private val digRange: Double
        get() = settings.range.toDouble()

    private val digRangeSq: Double
        get() = digRange * digRange

    /**
     * `ActionExecutor.byAim` for the dig: the rotation this tick's movement packet will carry first, the one
     * already on the wire second. Both are exact and both are raytrace candidates of the post-flying check that
     * decides (`checks/impl/breaking/RotationBreak.java:99-112`), so nothing is loosened - an action that reaches
     * from neither is refused.
     */
    private inline fun <T : Any> byAim(aim: Rotation, test: (Rotation) -> T?): T? {
        test(aim)?.let { return it }
        val wire = AuraRotation.wire
        return if (wire == aim) null else test(wire)
    }

    companion object {

        /**
         * Wall-clock floor between a completed break and the next `START_DESTROY_BLOCK`, in milliseconds.
         *
         * `FastBreak` adds `300 - gapMs` to a balance for every gap below 275 ms and flags at a balance above
         * 1000 ms (`checks/impl/breaking/FastBreak.java:81-96`), so 300 is the value at which the balance decays
         * instead of growing - and it is also what vanilla's own six-tick `destroyDelay` produces (see [MineFlow]).
         */
        const val PACING_MS = 300L

        /**
         * Ticks after an interrupt-abort or a give-up in which no new job may start.
         *
         * One second. The case it exists for is an aura that produces an action every few ticks: without it the
         * job would start on the first quiet tick, spend a tick aiming, and abort again on the next offer, so the
         * only packets it would ever emit are `START` / `ABORT` pairs. A second is longer than the re-aim plus
         * placement cycle the aura runs at its default delays, so a job that does start has a real chance of
         * reaching its first `STOP`, and it is short enough to be invisible when the aura genuinely goes quiet.
         */
        const val INTERRUPT_COOLDOWN_TICKS = 20

        /**
         * Ticks a claim is taken for. `MiningClaim.DEFAULT_TICKS` is 3 and the claim is renewed in every PLAN, so
         * the value only decides how long the block stays ours after the job stops asking.
         */
        const val CLAIM_TICKS = MiningClaim.DEFAULT_TICKS

        /** Rays one look point search may trace, the budget `TickPlanner` gives its own searches. */
        const val LOOK_BUDGET = 96
    }
}
