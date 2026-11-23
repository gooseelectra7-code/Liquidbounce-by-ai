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
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.features.module.modules.world.speedmine.ModuleSpeedMine
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findBestToolToMineBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/** Where one [PacketMineJob] cell stands. */
enum class PacketMinePhase {
    /** No job. */
    IDLE,

    /** The cell has no dig in `ModuleSpeedMine` yet; the job wants a tick to aim and send the START. */
    START,

    /** `ModuleSpeedMine` is digging the cell; the job takes no tick and waits for the dig to fill. */
    DIG,

    /** The dig is full and every gate of the STOP is open; the job wants a tick to aim and send it. */
    FINISH,

    /** Our STOP is out; the job waits for the server's block update. */
    BREAKING,

    /** Every cell is gone. */
    DONE,

    /** Given up. */
    ABORTED,
}

/**
 * The pure part of [PacketMineJob]: which cell, which phase, and how many ticks the dig has TAKEN from the aura and how
 * long it has been waiting. No world, no packets, no `mc`; everything arrives through [tick] and the four
 * observations, which is what makes the arbitration testable headlessly (`PacketMineFlowTest`).
 *
 * ### Which ticks the dig takes
 * Only [MineMove.START] and [MineMove.FINISH] ticks. Everything in [PacketMinePhase.DIG] and
 * [PacketMinePhase.BREAKING] is the aura's - which is the whole point of this engine.
 *
 * ### Who goes first when both want the tick
 * A wanted START or FINISH lets an action the aura has ready THIS tick go first, at most [MAX_DEFER_TICKS] ticks in a
 * row, and then takes the tick. Never yielding would cost a crystal that was already available; always yielding
 * would starve the dig against an aura with no delays, which acts every tick it has a target. Two ticks cost the
 * pocket 100 ms and are longer than the gap between two actions of one aura cycle.
 *
 * ### Refusals
 * `ModuleSpeedMine` may still refuse after the aura spent the tick - a START inside its 300 ms pacing or against
 * someone else's claim, a STOP whose gates moved between PLAN and EXECUTE. [MAX_REFUSALS] in a row back the job off
 * for [BACKOFF_TICKS], so a refusal that does not clear by itself does not keep taking ticks.
 */
@Suppress("TooManyFunctions")
internal class PacketMineFlow {

    var phase: PacketMinePhase = PacketMinePhase.IDLE
        private set

    /** Index into `MineTarget.cells`. */
    var cell: Int = 0
        private set

    /** Ticks the current cell has taken from the aura (START and FINISH ticks, the aim included). */
    var ownedTicks: Int = 0
        private set

    /** Ticks the current cell has been worked on at all, taken or not. */
    var waitTicks: Int = 0
        private set

    private var cellCount = 0
    private var deferred = 0
    private var refusals = 0
    private var backoff = 0
    private var restarts = 0
    private var sinceStop = 0

    val active: Boolean
        get() = phase == PacketMinePhase.START || phase == PacketMinePhase.DIG ||
            phase == PacketMinePhase.FINISH || phase == PacketMinePhase.BREAKING

    /**
     * What the shell reads every PLAN. [engineDigs]: `ModuleSpeedMine` has an action on the cell. [finishable]: that
     * action may be finished now with the stack the job would hold (`ModuleSpeedMine.auraFinishBlocker`).
     * [pacingOpen]: 300 ms have passed since the last STOP of anybody's, so a START would not be refused for it.
     */
    class Reading(val engineDigs: Boolean, val finishable: Boolean, val pacingOpen: Boolean)

    /**
     * [maxOwnedTicks] is `Mine.MaxTicks`; [maxWaitTicks] bounds the whole cell, derived by the shell from the dig's
     * predicted time; [breakTicks] is how long a sent STOP may go unanswered before the dig is resumed.
     */
    class Limits(val maxOwnedTicks: Int, val maxWaitTicks: Int, val breakTicks: Int)

    fun begin(cells: Int) {
        reset()
        cellCount = cells
        phase = PacketMinePhase.START
    }

    fun reset() {
        phase = PacketMinePhase.IDLE
        cell = 0
        cellCount = 0
        resetCell()
    }

    /** This tick's move; [MineMove.NONE] when the tick stays the aura's. */
    fun tick(reading: Reading, auraOffers: Boolean, limits: Limits): MineMove {
        if (!active) return MineMove.NONE

        waitTicks++
        if (waitTicks > limits.maxWaitTicks) return giveUp()

        return when (phase) {
            PacketMinePhase.START -> startPhase(reading, auraOffers, limits)
            PacketMinePhase.DIG, PacketMinePhase.FINISH -> digPhase(reading, auraOffers, limits)
            PacketMinePhase.BREAKING -> breakingPhase(limits)
            else -> MineMove.NONE
        }
    }

    private fun startPhase(reading: Reading, auraOffers: Boolean, limits: Limits): MineMove {
        // Already dug - by an earlier START of ours, or the player clicked the same block first: adopt it.
        if (reading.engineDigs) {
            phase = PacketMinePhase.DIG
            return digPhase(reading, auraOffers, limits)
        }
        if (backoff > 0) {
            backoff--
            return idle()
        }
        if (!reading.pacingOpen) return idle()

        return want(MineMove.START, auraOffers, limits)
    }

    @Suppress("ReturnCount")
    private fun digPhase(reading: Reading, auraOffers: Boolean, limits: Limits): MineMove {
        if (!reading.engineDigs) {
            // The engine dropped the dig: evicted by another START, out of its reach, reset by a slot switch.
            restarts++
            if (restarts > MAX_RESTARTS) return giveUp()
            phase = PacketMinePhase.START
            return idle()
        }
        if (backoff > 0) {
            backoff--
            phase = PacketMinePhase.DIG
            return idle()
        }
        if (!reading.finishable) {
            phase = PacketMinePhase.DIG
            return idle()
        }

        phase = PacketMinePhase.FINISH
        return want(MineMove.FINISH, auraOffers, limits)
    }

    private fun breakingPhase(limits: Limits): MineMove {
        sinceStop++
        // The STOP did not take (lost, or the server armed its delayed destroy): back to the dig, whose own
        // progress and gates decide when the next one may go.
        if (sinceStop > limits.breakTicks) phase = PacketMinePhase.DIG
        return idle()
    }

    /**
     * A tick the job does not want at all. It ends a run of deferrals, so [MAX_DEFER_TICKS] counts consecutive
     * ticks in which the job WANTED the tick and gave it to the aura - not deferrals scattered over a long dig.
     */
    private fun idle(): MineMove {
        deferred = 0
        return MineMove.NONE
    }

    private fun want(move: MineMove, auraOffers: Boolean, limits: Limits): MineMove {
        if (auraOffers && deferred < MAX_DEFER_TICKS) {
            deferred++
            return MineMove.NONE
        }
        if (ownedTicks >= limits.maxOwnedTicks) return giveUp()

        deferred = 0
        ownedTicks++
        return move
    }

    /** `ModuleSpeedMine` now digs the cell (started or adopted). */
    fun onStarted() {
        phase = PacketMinePhase.DIG
        refusals = 0
    }

    /** `ModuleSpeedMine` refused the START. */
    fun onStartRefused() {
        refused()
    }

    /** Our STOP went out. */
    fun onFinishSent() {
        phase = PacketMinePhase.BREAKING
        sinceStop = 0
        refusals = 0
    }

    /** `ModuleSpeedMine` held the STOP after all. */
    fun onFinishRefused() {
        phase = PacketMinePhase.DIG
        refused()
    }

    /** The current cell is air, whoever broke it: the next cell, or done. */
    fun onCellGone() {
        if (!active) return
        cell++
        resetCell()
        phase = if (cell >= cellCount) PacketMinePhase.DONE else PacketMinePhase.START
    }

    private fun refused() {
        refusals++
        if (refusals >= MAX_REFUSALS) {
            refusals = 0
            backoff = BACKOFF_TICKS
        }
    }

    private fun giveUp(): MineMove {
        phase = PacketMinePhase.ABORTED
        return MineMove.NONE
    }

    private fun resetCell() {
        ownedTicks = 0
        waitTicks = 0
        deferred = 0
        refusals = 0
        backoff = 0
        restarts = 0
        sinceStop = 0
    }

    override fun toString(): String = "$phase cell=$cell/$cellCount own=$ownedTicks t=$waitTicks"

    companion object {

        /** Consecutive ticks a wanted START or FINISH lets a ready aura action go first (class KDoc). */
        const val MAX_DEFER_TICKS = 2

        /** Refusals in a row before the job backs off (class KDoc). */
        const val MAX_REFUSALS = 3

        /** Ticks the job asks for nothing after [MAX_REFUSALS]. */
        const val BACKOFF_TICKS = 5

        /** Times the engine may drop the dig of one cell before the job gives the cell up. */
        const val MAX_RESTARTS = 2
    }
}

/**
 * The SpeedMine engine of AutoMine (`Mine.Engine = SpeedMine`, 2026-10-02, the user's ask: "while the packet
 * SpeedMine works you can place crystals, blocks and so on"). It breaks the one or two cells of a [MineTarget] through
 * `ModuleSpeedMine`'s packet mining and takes from the aura ONLY the ticks that carry a dig packet of ours.
 *
 * ### Why the aura can act between the START and the STOP
 * A packet dig is two packets. The server starts timing at the START (`destroyProgressStart`), keeps `destroyPos`
 * through the ABORT `StartMode` sends a tick later, and on the STOP tests `getDestroyProgress(held item) *
 * (ticksSpentDestroying + 1) >= 0.7` (`ServerPlayerGameMode.handleBlockBreakAction`, START and STOP branches) - the
 * item that counts is the one in the hand AT THE STOP. Nothing in between is a dig packet, so the ticks in between
 * carry none of the dig/click combinations `PacketOrderI` and `MultiActionsF` look for, and the aura places, attacks
 * and switches to its crystals as if nothing were being dug. Grim's `FastBreak` (LIVE) times the dig from START to
 * STOP and credits the best tool held at any point; `ModuleSpeedMine` mirrors it before every STOP.
 *
 * ### Why the aura sends the START and the STOP itself
 * `ModuleSpeedMine` aims for its own STOP with a rotation request at `IMPORTANT_FOR_USAGE_2` (30) and finishes the
 * dig in that request's `whenReached`, which `RotationManager.update` only runs for the TOP request
 * (`RotationManager.kt:225-230`) - and the aura holds its aim at 35 whenever a crystal is in range. Its tool switch
 * goes through `ensureHasSentCarriedItem`, which announces the aura's silent slot instead while the aura holds one
 * (AGENTS.md, "Slot changes"), and its STOP gate then refuses the crystal it finds in the hand. Left alone, the STOP
 * would starve exactly while the aura works.
 *
 * So on the two ticks that matter the aura does what it does for its own clicks: PLAN files the aim at the cell (and,
 * for the STOP, the tool as its silent slot, announced by `gameMode.tick()` before any click), EXECUTE traces the
 * OUTGOING rotation onto the cell and only then calls `ModuleSpeedMine.startForAura` / `finishForAura` with the face
 * that ray produced - the rotation `RotationBreak` (experimental) checks, and a face the eye really sees
 * (`PositionBreakA`, LIVE). The aura plans no attack and no use on those ticks. Every other rule stays in
 * `ModuleSpeedMine`: the claim, the 300 ms pacing, the ABORT after the START, the FastBreak mirror, the STOP's
 * server-side test, `WrongBreak`'s "is the server still on our cell".
 *
 * Whether the STOP may go is asked BEFORE the tick is taken (`auraFinishBlocker`, the STOP's own gates run for the
 * tool the aura is about to hold), so a STOP that would be held never costs the aura a tick.
 *
 * ### What it does to `ModuleSpeedMine`
 * - The dig of the current cell is marked `auraOwned` every tick it is read (`markForAura`) - also one the player
 *   started first and the job merely adopted. While marked, the module sends no STOP of its own for it and no blind
 *   `RebreakStops` follow-ups after ours, each of which would take a tick from the aura exactly when the pocket
 *   opened; the scheduled STOP of `StartMode = Stop` is skipped too. `Rebreak = NCPAir` sends dig packets the aura
 *   cannot account for, so the engine is not used with it (`ModuleSpeedMine.auraCompatible`). DoubleMine is (since
 *   2026-10-03): its START + ABORT + STOP leaves inside the job's own START tick, and a cell of ours the server ends up
 *   finishing by itself - the player STARTed another block after ours - gets no STOP (`auraFinishBlocker` refuses it)
 *   and is finished by SpeedMine's tool hold, so the job just waits for it like for any dig.
 * - The BASE cell of a pocket is released once it is gone (`releaseForAura`): with `Rebreak = Grim` the module keeps
 *   breaking whatever reappears there, and the obsidian `BasePlace` puts in next is exactly that. Every other cell is
 *   handed back (`handBackFromAura`) and keeps its rebreak - re-breaking a block put back into the crystal's cell
 *   keeps the pocket open - until the module's next START evicts that dig (a two-cell pocket's second cell does).
 *
 * ### Budgets
 * `Mine.MaxTicks` counts the ticks a cell TAKES (aim and START, aim and STOP). How long the block itself takes is
 * bounded by its predicted time (`ModuleSpeedMine.ticksToFinish` for the tool the STOP will be sent with, as if the
 * whole dig were spent in the air) plus ping and slack; a cell predicted beyond [MAX_DIG_TICKS] standing is not
 * started at all, and a job whose victim is no longer a target ends - while it runs, no new pocket is searched for.
 *
 * ### Who sends the STOP
 * Only the aura, for as long as it holds the dig: `ModuleSpeedMine` files none of its own for an `auraOwned` dig
 * (see `MineAction.step`), because the rotation request it would file can fire long after the aura finished the cell
 * - a second STOP into the air Grim predicts until the ack (`AirLiquidBreak`, LIVE). Every way out of a job
 * therefore hands the dig back - a broken cell is handed back, [giveUp] and [reset] release a standing one, and a
 * stand-down ([stop]) only pauses a job that resumes with the module. A dig another START moved the server off is
 * dropped by the module itself, so the job sees it gone and starts over.
 *
 * Readiness is judged for the aura's tool (`auraFinishBlocker`: the module's own pace with `Factor`, the FastBreak
 * mirror and the server's 0.7 test), never by the module's `progress`, which it accumulates for the tool IT would pick
 * - or for the aura's crystal when `SwitchTool` is off.
 */
@Suppress("TooManyFunctions")
class PacketMineJob(
    private val settings: MineSettings,
    private val place: PlaceSettings,
) : MinecraftShortcuts, AutoMineEngine {

    private val flow = PacketMineFlow()

    private var target: MineTarget? = null

    /** The face the START of the current cell went out with; the STOP aims at it first while it stays visible. */
    private var startFace: Direction? = null

    /** [PacketMineFlow.Limits.maxWaitTicks] of the current cell, fixed when the cell begins. */
    private var waitBudget = 0

    /** Ticks left in which no new job may start, after one was given up. */
    private var cooldown = 0

    private val scratch = BlockPos.MutableBlockPos()

    override val active: Boolean
        get() = flow.active

    override val plannedDamage: Float?
        get() = target?.candidate?.damage?.effective

    /** See [takeFinished]; set by [onCellBroken] when the last cell went. */
    private var finished: MineTarget? = null

    /** `ModuleSpeedMine` is there to dig for the aura (`ModuleSpeedMine.auraCompatible`). */
    val available: Boolean
        get() = ModuleSpeedMine.auraCompatible

    override fun describe(): String {
        val current = target ?: return "packet-mine idle" + if (cooldown > 0) " cd=$cooldown" else ""
        val cell = current.cells.getOrNull(flow.cell)
        val where = if (cell == null) "-" else "(${cell.x} ${cell.y} ${cell.z})"
        return "packet-mine $flow $where"
    }

    override fun plan(
        view: WorldView,
        target: MineTarget?,
        auraOffers: Boolean,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
        keepAbove: Float,
    ): MinePlan? {
        if (cooldown > 0) cooldown--
        if (!flow.active) {
            endJob()
            if (target == null || !canStart(target)) return null

            this.target = target
            flow.begin(target.cells.size)
            beginCell(target.cells[0])
        }
        return tickActive(view, auraOffers, slack, rayIgnore, keepAbove)
    }

    override fun takeFinished(): MineTarget? = finished.also { finished = null }

    @Suppress("ReturnCount")
    private fun tickActive(
        view: WorldView,
        auraOffers: Boolean,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
        keepAbove: Float,
    ): MinePlan? {
        val plan = target ?: return giveUp(null)
        val cell = plan.cells.getOrNull(flow.cell) ?: return giveUp(null)
        if (!available) return giveUp(cell)

        // Gone, whoever broke it: our STOP, the engine's own (its rotation path when nothing outranks it), the
        // server's delayed destroy, an instant START.
        if (view.state(cell).isAir) {
            onCellBroken(plan, cell)
            return null
        }
        if (!plan.stillHolds(view, cell, flow.cell, digRangeSq, scratch)) return giveUp(cell)
        // The vanilla engine is bounded by the ticks it takes; this one takes almost none and could dig on long after
        // the fight moved - and no new pocket is searched for while it runs. A victim that is no longer a target
        // (dead, gone, out of `Target.Range`) ends it.
        if (view.targets.none { it.entity === plan.candidate.target.entity }) return giveUp(cell)
        // The pocket is now worth less than what the aura does meanwhile ([keepAbove], see
        // `AutoMineEngine.plan`) - this engine leaves the aura every tick between its two packets, so the aura can
        // well have found something as good while the block was still standing. Not once our STOP is out: the server
        // breaks the block whatever we do now, and giving up would only lose the pocket from the bookkeeping.
        if (flow.phase != PacketMinePhase.BREAKING && plan.candidate.damage.effective < keepAbove) return giveUp(cell)
        // The hand gates of a vanilla dig apply to a packet one as well (`MineJob.canDig`). While they are shut the
        // job neither takes a tick nor spends its budgets - the dig itself goes on in the engine, under its own
        // `OnEating` rule.
        if (!canDig()) return null

        // Marked every tick, not only at our START: a dig the player started first is adopted without one, and an
        // unmarked dig would get its own STOP from the engine as well as ours (`MineAction.step`).
        val engineDigs = ModuleSpeedMine.markForAura(cell)
        val reading = PacketMineFlow.Reading(
            engineDigs = engineDigs,
            finishable = engineDigs && ModuleSpeedMine.auraFinishBlocker(cell, finishStack(cell)) == null,
            // Shut as well while the player (or anything else) digs another cell through SpeedMine: our START would
            // evict that dig and their held click would evict ours back (`digsElsewhereForOthers`).
            pacingOpen = System.currentTimeMillis() - ModuleSpeedMine.lastStopMs >= MineJob.PACING_MS &&
                !ModuleSpeedMine.digsElsewhereForOthers(cell),
        )
        val move = flow.tick(reading, auraOffers, limits())
        if (flow.phase == PacketMinePhase.ABORTED) return giveUp(cell)
        if (move != MineMove.START && move != MineMove.FINISH) return null

        val slot = if (move == MineMove.FINISH) toolFor(cell) else null
        val look = lookFor(view, cell, slack, rayIgnore)
        return MinePlan(move, cell, null, look, slot, plan.candidate.target.entity)
    }

    override fun execute(
        plan: MinePlan,
        view: WorldView,
        rayIgnore: (Entity) -> Boolean,
        allowDig: Boolean,
        foreignDig: Boolean,
    ): String {
        val cell = plan.pos ?: return "no cell"
        // A foreign click or dig already used this tick (`PacketOrderI`, `MultiActionsF`): nothing of ours joins it,
        // and the flow asks again next tick. The tick still counts against `MaxTicks`.
        if (!allowDig) return "dig blocked this tick"
        if (!available) return "SpeedMine is off"

        val hit = byAim(AuraRotation.outgoing) { rotation ->
            RayTests.hitsBlock(view.eye, rotation, cell, digRange, rayIgnore, throughBlocks)
        } ?: return "dig ray misses"

        return when (plan.move) {
            MineMove.START -> if (ModuleSpeedMine.startForAura(cell, hit.direction)) {
                flow.onStarted()
                startFace = hit.direction
                "START ${cell.toShortString()} ${hit.direction.name}"
            } else {
                flow.onStartRefused()
                "SpeedMine did not start (claim, pacing or reach)"
            }

            MineMove.FINISH -> {
                val held = ModuleSpeedMine.finishForAura(cell, hit.direction, plan.slot)
                if (held == null) {
                    flow.onFinishSent()
                    "STOP ${cell.toShortString()} ${hit.direction.name}"
                } else {
                    flow.onFinishRefused()
                    "STOP held: $held"
                }
            }

            else -> "nothing to do"
        }
    }

    /**
     * A stand-down (a screen, a totem swap) PAUSES this engine and ends nothing: the dig lives in `ModuleSpeedMine`,
     * which keeps timing it, and this job holds no claim, no slot and no aim between its two ticks. When the module
     * comes back the job reads the world again - a cell that went in the meantime is simply gone.
     */
    override fun stop() = Unit

    override fun reset() {
        val plan = target
        val cell = plan?.cells?.getOrNull(flow.cell)
        if (flow.active && cell != null) release(cell)
        flow.reset()
        endJob()
        cooldown = 0
        finished = null
    }

    private fun onCellBroken(plan: MineTarget, cell: BlockPos) {
        // The base cell is about to be refilled with obsidian - which the engine's rebreak would break again. Any
        // other cell is handed back: rebreaking what reappears there keeps the pocket open.
        if (cell == plan.base) ModuleSpeedMine.releaseForAura(cell) else ModuleSpeedMine.handBackFromAura(cell)
        flow.onCellGone()
        startFace = null
        if (flow.phase == PacketMinePhase.DONE) finished = plan
        val next = plan.cells.getOrNull(flow.cell)
        if (flow.active && next != null) beginCell(next) else endJob()
    }

    /** Ends the job, hands the cell back to the player, and keeps a new one from starting for a second. */
    private fun giveUp(cell: BlockPos?): MinePlan? {
        if (cell != null) release(cell)
        flow.reset()
        endJob()
        cooldown = GIVE_UP_COOLDOWN_TICKS
        return null
    }

    /**
     * Drops the engine's dig of [cell] when the block still stands, or just gives it back when it is gone - nothing is
     * owed for a broken block, and its rebreak may run again. No level (the reset of a disconnect): nothing to do,
     * `ModuleSpeedMine` drops its actions on the world change itself.
     */
    private fun release(cell: BlockPos) {
        val level = mc.level ?: return
        if (level.getBlockState(cell).isAir) {
            ModuleSpeedMine.handBackFromAura(cell)
        } else {
            ModuleSpeedMine.releaseForAura(cell)
        }
    }

    private fun endJob() {
        target = null
        startFace = null
    }

    /** A new job may start: the engine is there, no cooldown, and the first cell is a dig worth waiting for. */
    private fun canStart(target: MineTarget): Boolean {
        if (target.cells.isEmpty() || cooldown > 0 || !available || !canDig()) return false

        val first = target.cells[0]
        val predicted = ModuleSpeedMine.ticksToFinish(first, finishStack(first), onGround = true) ?: return false
        return predicted <= MAX_DIG_TICKS
    }

    /**
     * Fixes the wait budget of [cell]: its predicted dig as if the player spent ALL of it in the air - five times the
     * standing time, the slowest the same dig can be (`ModuleSpeedMine.ticksToFinish`), because a crystal fight is
     * jumped through and a budget for a standing player would end every dig of a jumping one - plus the START
     * pacing, two round trips and some slack. It is a stall guard only; the job takes no tick while it waits.
     */
    private fun beginCell(cell: BlockPos) {
        val worst = ModuleSpeedMine.ticksToFinish(cell, finishStack(cell), onGround = false) ?: MAX_WAIT_DIG_TICKS
        waitBudget = worst.coerceAtMost(MAX_WAIT_DIG_TICKS) + PACING_TICKS + 2 * pingTicks() + WAIT_SLACK_TICKS
    }

    private fun limits() = PacketMineFlow.Limits(
        maxOwnedTicks = settings.maxTicks,
        maxWaitTicks = waitBudget,
        breakTicks = pingTicks() + BREAK_SLACK_TICKS,
    )

    /**
     * The aim for a tick the job takes: none while the rotation on the wire already lands on the cell (the hub then
     * holds it, and nothing turns), otherwise the START face while it is still visible, otherwise any visible face.
     */
    private fun lookFor(view: WorldView, cell: BlockPos, slack: Float, rayIgnore: (Entity) -> Boolean): LookPoint? {
        val wire = AuraRotation.wire
        if (RayTests.hitsBlock(view.eye, wire, cell, digRange, rayIgnore, throughBlocks) != null) return null

        startFace?.let { face ->
            LookPoints.forFace(view.eye, wire, cell, face, digRange, rayIgnore, slack, LOOK_BUDGET, throughBlocks)
                ?.let { return it }
        }
        return LookPoints.forPlacement(
            view.eye, wire, cell, digRange, null, digRange, rayIgnore, slack, LOOK_BUDGET, throughBlocks
        )
    }

    /**
     * The tool the STOP goes out with: the best hotbar tool, which the hub holds as the aura's own slot for that
     * tick (`Mine.Tool`), or none with [SwitchMode.NONE] - then the STOP has to make do with whatever the server
     * holds, which `auraFinishBlocker` will usually refuse.
     */
    private fun toolFor(cell: BlockPos): HotbarItemSlot? {
        if (settings.tool == SwitchMode.NONE) return null
        return Slots.Hotbar.findBestToolToMineBlock(world.getBlockState(cell))
    }

    /** The stack [toolFor] will put in the hand for the STOP, or the hand the server has when there is none. */
    private fun finishStack(cell: BlockPos): ItemStack = toolFor(cell)?.itemStack ?: player.mainHandItem

    /**
     * `MineJob.canDig` minus `missTime`, which is a client input lockout no server reads and the vanilla engine only
     * needs because it drives `continueDestroyBlock`.
     */
    private fun canDig(): Boolean {
        if (!player.isAlive || player.isSpectator || player.isUsingItem || player.isHandsBusy) return false
        return !player.getItemInHand(InteractionHand.MAIN_HAND).has(DataComponents.PIERCING_WEAPON)
    }

    private fun pingTicks(): Int = (player.ping / MS_PER_TICK).roundToInt()

    /** `PlaceSettings.ThroughBlocks`, as for the vanilla engine. */
    private val throughBlocks: Boolean
        get() = place.throughBlocks

    private val digRange: Double
        get() = settings.range.toDouble()

    private val digRangeSq: Double
        get() = digRange * digRange

    /** `MineJob.byAim`: the outgoing rotation first, the one on the wire second; both exact. */
    private inline fun <T : Any> byAim(aim: Rotation, test: (Rotation) -> T?): T? {
        test(aim)?.let { return it }
        val wire = AuraRotation.wire
        return if (wire == aim) null else test(wire)
    }

    companion object {

        /**
         * A cell whose dig is predicted longer than this is not started: ten seconds is longer than the fight that
         * proposed it will keep the same geometry, and it is where obsidian without a pickaxe (about 250 s) and a
         * pickaxe without Efficiency (about 9 s) part ways.
         */
        const val MAX_DIG_TICKS = 200

        /** The wait budget's cap: [MAX_DIG_TICKS] spent entirely in the air, five times slower (see [beginCell]). */
        private const val MAX_WAIT_DIG_TICKS = 5 * MAX_DIG_TICKS

        /** `MineJob.PACING_MS` in ticks: the wait before a second cell's START. */
        private const val PACING_TICKS = 6

        /** Slack on top of a cell's predicted dig for the aim, the deferrals and a refusal or two. */
        private const val WAIT_SLACK_TICKS = 20

        /** How long a sent STOP may stay unanswered on top of the ping before the dig is resumed. */
        private const val BREAK_SLACK_TICKS = 10

        /** Ticks after a give-up in which no new job starts, as `MineJob.INTERRUPT_COOLDOWN_TICKS`. */
        private const val GIVE_UP_COOLDOWN_TICKS = 20

        /** Rays one look point search may trace, as for the vanilla engine. */
        private const val LOOK_BUDGET = 96

        private const val MS_PER_TICK = 50.0
    }
}
