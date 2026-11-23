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

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.AnchorSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.CycleSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.DetonateHand
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.SwitchMode
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.LookPoint
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.LookPoints
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.RayTests
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.exec.AnchorExecReport
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.exec.AnchorExecutor
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.exec.consumesUse
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.AnchorCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.blockDistanceSq
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.isReplaceInPlace
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.state.AnchorLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.AnchorView
import net.ccbluex.liquidbounce.utils.block.isInteractable
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.shapes.CollisionContext

/**
 * What [AnchorJob.plan] decided for this tick, consumed once by [AnchorJob.execute].
 *
 * @param move the interaction to perform, [AnchorMove.NONE] when the job owns the tick but sends nothing (it is
 *   waiting for a confirmation, throttled by `Anchor.Delay`, or still bringing the aim onto the cell).
 * @param cell the cell whose anchor is to explode - the position the ledger, the search and the render all name.
 * @param clickPos the block the `UseItemOn` addresses: [cell] itself for the charge and the detonation, and for
 *   [AnchorMove.PLACE] either the NEIGHBOUR (the relative form) or [cell] again (the replace-in-place form).
 * @param face the face of [clickPos] that must come back from the ray, or null when any visible face will do.
 *   Non-null only for a RELATIVE [AnchorMove.PLACE], because `BlockPlaceContext` then puts the block at
 *   `hit.getBlockPos().relative(hit.getDirection())`
 *   (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-57`) - the face IS the placement. It is
 *   null for the replace-in-place form, where `replaceClicked` (`:36, 54-61`) makes vanilla ignore the direction
 *   and put the block into the clicked cell, and null for the charge and the detonation, which read no direction
 *   at all.
 * @param look the rotation to request at the tick head, or null when the rotation already on the wire lands on
 *   [clickPos] (then the hub holds it - see the aim rule in [AnchorJob]).
 * @param slot the hotbar slot to bring into the hand at the tick HEAD so that `gameMode.tick()` emits the single
 *   `SetCarriedItem` before the click (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:297-304` ->
 *   `ensureHasSentCarriedItem`, `:311-317`). Null means "use whatever is held", which is legal only for the
 *   detonation (`DetonateHand.HELD`, or a `SwitchMode.NONE` hand that already qualifies).
 * @param entity the victim the cell was scored against, passed to `AuraRotation.request` as its reset-detection
 *   entity so a `SMOOTH` step towards the cell is not restarted every tick.
 */
class AnchorPlan(
    val move: AnchorMove,
    val cell: BlockPos,
    val clickPos: BlockPos,
    val face: Direction?,
    val look: LookPoint?,
    val slot: HotbarItemSlot?,
    val entity: Entity?,
)

/**
 * The client shell around [AnchorFlow]: it owns the cell, reads the world into a [CellReading] every tick,
 * re-validates the running cycle, picks the click target, the look point and the hotbar slot for THIS tick's
 * phase, and calls the [AnchorExecutor]. One instance per module; main thread only, and every packet it sends
 * leaves from the module's EXECUTE step.
 *
 * Modelled on `crystalaura/mine/MineJob.kt`: the pure state machine ([AnchorFlow]) is separately testable and this
 * class is the part that cannot be - it touches `mc`, the level and the hotbar.
 *
 * ### One block interaction per tick, and never two
 * All three steps are `ServerboundUseItemOnPacket` on a block and Grim's `PacketOrderI` allows ONE of those per
 * tick. [plan] therefore produces at most one move, [execute] performs at most one [AnchorExecutor.execute], and
 * [AnchorFlow] refuses to emit a second click for a phase whose first one has not been read back yet. This is the
 * module's hardest invariant; nothing here may be "optimised" into a batch.
 *
 * ### Re-validation, and the one thing it deliberately does NOT do
 * Before every interaction: the cell is still in `Anchor.Range` (measured to the block that is CLICKED - the
 * neighbour for a relative placement, the cell itself for a replace-in-place one and for the anchor's own two
 * clicks), the cell is still in the state the move expects, the click target is still clickable (in whichever of
 * the two placement forms it uses), the phase's slot is still there, and the ledger has not excluded the cell
 * (a detonation of ours in flight, or a dud).
 *
 * The cell's WORTH is NOT re-scored. That is the same rule `MineJob.stillValid` states: the ranking prices a
 * candidate against every other candidate of the tick, and re-running it mid-cycle would abandon a cycle the
 * moment a better cell appears - after two of its three interactions were already paid for. "This is taking too
 * long" is `Cycle.MaxTicks`' job, and there is deliberately no `Interrupt` setting, because abandoning after the
 * charge burns a glowstone and the whole cycle is only three ticks.
 *
 * ### Aborting is free
 * A use has NO abort packet (unlike a dig, which owes an `ABORT_DESTROY_BLOCK`), so [abort] sends nothing and the
 * next tick may start a new cycle on another cell. What an abandoned cycle leaves behind is a standing charged
 * anchor - worth MORE than it cost, and a one-interaction entry for whoever gets there next. That is the exact
 * opposite of an abandoned crystal cycle, which leaves a crystal the enemy can pop.
 *
 * ### Take the aim once, then hold it
 * Grim's `DuplicateRotPlace` (`checks/impl/scaffolding/DuplicateRotPlace.java:36-50`) arms only when the yaw delta
 * between two rotation updates exceeds 2 degrees (`if (deltaX > 2)`) AND that delta then REPEATS within 1e-4
 * across two consecutive placements; everything else calls `reward()`. Clicking one cell three ticks running with
 * the aim HELD is therefore the SAFE shape. So [lookFor] tests the rotation already on the wire FIRST - one ray
 * through `RayTests.hitsBlock` - and only searches for a new look point when that misses. A cycle normally aims
 * once, on its first tick, and reports `look = null` for the rest.
 *
 * The transition from the placement aim to the charge aim needs no re-aim at all, and that falls out of the
 * geometry: the placement ray passes THROUGH the (still empty) cell to reach the neighbour's inward face, so once
 * the anchor stands in that cell the very same rotation terminates on the anchor.
 */
@Suppress("TooManyFunctions")
class AnchorJob(
    private val settings: AnchorSettings,
    private val cycle: CycleSettings,
    private val executor: AnchorExecutor,
) : MinecraftShortcuts {

    /** The pure state machine; everything headlessly testable lives there and is exercised by `AnchorFlowTest`. */
    private val flow = AnchorFlow()

    /** Which of the three interactions the cycle is on; `IDLE` when there is no cycle. For the render only. */
    val phase: AnchorPhase get() = flow.phase

    /** Ticks the CURRENT phase has been waiting, against `Cycle.MaxTicks`. For the render only. */
    val phaseTicks: Int get() = flow.phaseTicks

    /** The cell of the running cycle, null while idle. */
    var cell: BlockPos? = null
        private set

    /** Click faces of the candidate the cycle started on ([AnchorCandidate.clickFaces]); `0` for a standing anchor. */
    private var faces = 0

    /**
     * Whether the candidate the cycle started on reported the replace-in-place form
     * ([AnchorCandidate.selfClick]): the cell itself may be clicked and the anchor takes the place of what stands
     * in it. `false` for a standing anchor, which is not placed at all.
     */
    private var selfClick = false

    /** The neighbour direction the placement clicks, frozen once found so the aim can be held. */
    private var clickDir: Direction? = null

    /** The victim the cell was scored against; only used as the rotation pipeline's reset-detection entity. */
    private var entity: LivingEntity? = null

    /** The job owns this tick: the hub starts no new cycle and files no other rotation while this holds. */
    val active: Boolean
        get() = flow.active

    /**
     * The running cycle has already sent at least one interaction, so the aim it was sent with must be kept for
     * the remaining clicks ([AnchorFlow.engaged]).
     *
     * This is the ONLY condition under which the module may pin the view. `AuraRotation.hold` re-requests the
     * rotation already on the wire, which freezes the camera where it stands and fights the player's own mouse;
     * doing that for a cell we have not committed to yet turns an unreachable candidate into a permanent view
     * lock, because the cycle just restarts on it every `Cycle.MaxTicks`.
     */
    val engaged: Boolean
        get() = flow.active && flow.engaged

    /**
     * Interactions - and, at one per tick, TICKS - still to spend before the blast; `0` while idle.
     *
     * The hub feeds this into the target prediction: a cycle entered at [AnchorPhase.PLACE] detonates up to three
     * ticks after the snapshot it was scored against, so the victim has to be extrapolated that much further.
     */
    val remaining: Int
        get() = flow.remaining

    /** One-liner for the module's `Debug` tracing. */
    fun describe(): String {
        val pos = cell ?: return "idle"
        return "$flow (${pos.x} ${pos.y} ${pos.z})" + (clickDir?.let { " ${it.name}" } ?: "")
    }

    /**
     * Decides this tick, at the module's PLAN step. [best] is the search's head and is only looked at when nothing
     * is running. Returns null when the job owns nothing this tick - then the hub lets the rotation return.
     *
     * [slack] is `AuraRotation.slackDegrees`, the per-axis deviation the rotation pipeline may still add on the
     * wire; [rayIgnore] the LookThrough filter.
     */
    fun plan(
        view: AnchorView,
        ledger: AnchorLedger,
        best: AnchorCandidate?,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
    ): AnchorPlan? {
        if (!flow.active) {
            clearCycle()
            val candidate = best ?: return null
            if (!flow.begin(readCell(view, candidate.pos))) return null

            cell = candidate.pos
            faces = candidate.clickFaces
            selfClick = candidate.selfClick
            entity = candidate.target.entity
        }
        return tickActive(view, ledger, slack, rayIgnore)
    }

    /**
     * Performs [plan] at the module's EXECUTE step and advances the flow when - and only when - the packet
     * actually left ([AnchorExecReport.sent]). A rejected tick leaves the phase untouched and is retried on the
     * next one, priced by `Cycle.MaxTicks`.
     */
    fun execute(
        plan: AnchorPlan,
        view: AnchorView,
        rayIgnore: (Entity) -> Boolean,
        allowUse: Boolean,
    ): AnchorExecReport {
        if (plan.move == AnchorMove.NONE) {
            return AnchorExecReport(AnchorMove.NONE, sent = false, reason = null)
        }

        val report = executor.execute(plan, view, rayIgnore, allowUse)
        if (report.sent) {
            flow.onSent(plan.move)
        }
        return report
    }

    /**
     * Abandons the running cycle but KEEPS [AnchorFlow]'s wall clock of the last send, so `Anchor.Delay` keeps
     * throttling across cycles. This is what every stand-down and every idle tick calls: abandoning is free (a use
     * owes no abort packet) and what it leaves behind - a standing charged anchor - is worth more than it cost.
     */
    fun stop() {
        if (flow.active) {
            flow.abort()
        }
        clearCycle()
    }

    /**
     * Forgets everything INCLUDING the send clock; only for the module's own state reset (disable, world change).
     * Between cycles use [stop] instead, or the delay would restart on every retarget.
     */
    fun reset() {
        flow.reset()
        clearCycle()
    }

    /** The per-cycle bookkeeping, which [AnchorFlow.begin] re-establishes; the flow's clock is not touched. */
    private fun clearCycle() {
        cell = null
        faces = 0
        selfClick = false
        clickDir = null
        entity = null
    }

    /** One tick of a running cycle: re-validate, ask [AnchorFlow] for the move, dress it with the aim and slot. */
    private fun tickActive(
        view: AnchorView,
        ledger: AnchorLedger,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
    ): AnchorPlan? {
        val pos = cell ?: return null
        if (ledger.isExcluded(pos)) return abort()

        val reading = readCell(view, pos)
        val move = flow.tick(reading, cycle.maxTicks, settings.delay.toLong())
        return dress(view, ledger, pos, move, reading, slack, rayIgnore) ?: abort()
    }

    /**
     * Turns the flow's [move] into a plan: the click target, the aim and the hotbar slot. Null means the cycle
     * cannot be continued and the caller aborts it.
     *
     * The phase may have ADVANCED inside `AnchorFlow.tick` - a resolved placement falls straight through into the
     * charge, which is what makes the best case three ticks rather than six - so everything here is derived from
     * the phase as it stands NOW ([phaseMove]) and never from the one the tick was entered with. That also covers
     * the throttled tick: with `move == NONE` the job still owns the tick and still holds the aim on the target
     * its CURRENT phase will click.
     */
    private fun dress(
        view: AnchorView,
        ledger: AnchorLedger,
        pos: BlockPos,
        move: AnchorMove,
        reading: CellReading,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
    ): AnchorPlan? {
        val aim = phaseMove()
        val target = if (usable(aim, move, reading)) clickTarget(view, ledger, aim, pos) else null
        val slot = slotFor(aim)
        // A null slot is legal only for the detonation, which may click with whatever is held (`AnchorPlan.slot`).
        if (target == null || (slot == null && aim != AnchorMove.DETONATE)) return null

        return AnchorPlan(
            move = move,
            cell = pos,
            clickPos = target.block,
            face = target.face,
            look = lookFor(view, target, slack, rayIgnore),
            slot = slot,
            entity = entity,
        )
    }

    /** The cycle is on a real phase and, when it wants to click this tick, the world still agrees with it. */
    private fun usable(aim: AnchorMove, move: AnchorMove, reading: CellReading): Boolean =
        aim != AnchorMove.NONE && (move == AnchorMove.NONE || matches(move, reading))

    /** Gives up on the cycle and hands the tick back; nothing is owed on the way out (class KDoc). */
    private fun abort(): AnchorPlan? {
        stop()
        return null
    }

    /**
     * The live state of [pos] as [AnchorFlow] sees it.
     *
     * `isUnobstructed` costs an entity query, so it is only asked for a cell that could actually be placed into -
     * for a cell that already holds an anchor the flow never consults the flag, and no entity can obstruct a block
     * that is already standing.
     */
    private fun readCell(view: AnchorView, pos: BlockPos): CellReading {
        val charge = view.anchorCharge(pos)
        val replaceable = charge == null && view.isReplaceable(pos)
        return CellReading(
            isAnchor = charge != null,
            charge = charge ?: 0,
            replaceable = replaceable,
            unobstructed = !replaceable || view.isUnobstructed(pos),
        )
    }

    /** The world still agrees with the move the flow wants to make; see [AnchorExecutor] for the same three tests. */
    private fun matches(move: AnchorMove, reading: CellReading): Boolean = when (move) {
        AnchorMove.PLACE -> !reading.isAnchor && reading.replaceable && reading.unobstructed
        AnchorMove.CHARGE -> reading.isAnchor && reading.charge == 0
        AnchorMove.DETONATE -> reading.isAnchor && reading.charge >= 1
        AnchorMove.NONE -> true
    }

    /** The move the CURRENT phase would emit, whatever the delay and the confirmation wait decided this tick. */
    private fun phaseMove(): AnchorMove = when (flow.phase) {
        AnchorPhase.PLACE -> AnchorMove.PLACE
        AnchorPhase.CHARGE -> AnchorMove.CHARGE
        AnchorPhase.DETONATE -> AnchorMove.DETONATE
        else -> AnchorMove.NONE
    }

    /**
     * Which block this tick clicks, and on which face.
     *
     * The charge and the detonation click the anchor ITSELF and read no direction - `useItemOn`
     * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:78-97`) and `useWithoutItem` (`:99-123`) consult
     * only the blockstate, the held item and `canSetSpawn` - so any visible face will do and the cell only has to
     * be inside `Anchor.Range`.
     *
     * The placement has vanilla's two forms and takes whichever is available, the NEIGHBOUR one first.
     *
     * Relative form: click a neighbour on the face pointing at the cell, and that face IS the placement - a
     * `BlockPlaceContext` puts the block at `hit.getBlockPos().relative(hit.getDirection())`
     * (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-57`). The direction is frozen in
     * [clickDir] once chosen, so the aim can be held for the rest of the cycle, and re-tested for clickability
     * every tick - a neighbour someone mined away invalidates it and the next clickable one is taken.
     *
     * Replace-in-place form: click the CELL, whose current block the anchor replaces (`replaceClicked`, `:36,
     * 54-61`). The face is then null, because vanilla reads no direction once that flag is set - the same
     * `ClickTarget` shape the charge and the detonation use, and [AnchorExecutor] already leaves a null face
     * unconstrained. It is the only way into a cell all of whose neighbours are themselves replaceable, and it is
     * re-tested every tick just like the frozen direction is.
     */
    private fun clickTarget(view: AnchorView, ledger: AnchorLedger, move: AnchorMove, pos: BlockPos): ClickTarget? {
        if (move != AnchorMove.PLACE) {
            // The charge and the detonation click the anchor itself, and the anchor is inside its own blast - so
            // this is also the gate that stops a second click on a cell whose anchor the server has already blown
            // up while the client still draws it.
            return if (inRange(view, pos) && !ledger.isBlastShadowed(pos)) ClickTarget(pos, null) else null
        }

        val frozen = clickDir?.takeIf { isClickable(view, ledger, pos.relative(it)) }
        val dir = frozen
            ?: Direction.entries.firstOrNull { hasFace(it) && isClickable(view, ledger, pos.relative(it)) }
        clickDir = dir
        if (dir != null) return ClickTarget(pos.relative(dir), dir.opposite)

        return if (selfClick && isSelfClickable(view, ledger, pos)) ClickTarget(pos, null) else null
    }

    /**
     * The aim for this tick, or null when the rotation ALREADY on the wire lands on the click target - which is
     * the normal case from the second tick of a cycle on, and the shape `DuplicateRotPlace` rewards (class KDoc).
     *
     * A search that finds nothing also answers null: the tick is still the job's, the hub holds the current
     * rotation and `Cycle.MaxTicks` prices the wait.
     */
    private fun lookFor(
        view: AnchorView,
        target: ClickTarget,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
    ): LookPoint? {
        val wire = AuraRotation.wire
        val throughBlocks = settings.throughBlocks
        val range = settings.range.toDouble()
        val onWire = RayTests.hitsBlock(view.eye, wire, target.block, range, rayIgnore, throughBlocks)
        if (onWire != null && (target.face == null || onWire.direction == target.face)) return null

        return if (target.face == null) {
            // Sampled on the shape actually standing there: an anchor fills its cell, but the block a
            // replace-in-place placement clicks need not, and `AABB(pos)` would then aim at empty air.
            LookPoints.forBlock(
                view.eye, wire, target.block, range, rayIgnore, slack, LOOK_BUDGET, throughBlocks,
                sampleBox = RayTests.pickBox(target.block),
            )
        } else {
            LookPoints.forNeighbourFace(
                view.eye, wire, target.block, target.face, range, rayIgnore, slack, LOOK_BUDGET, throughBlocks
            )
        }
    }

    /**
     * The hotbar slot the phase needs, or null when none is reachable - which aborts the cycle for the placement
     * and the charge and is merely "use what is held" for the detonation (see [AnchorPlan.slot]).
     *
     * The detonating hand may hold almost anything: the block takes the click before the item does
     * (`MultiPlayerGameMode.performUseItemOn`, `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:
     * 333-357`) and for a non-fuel item `RespawnAnchorBlock.useItemOn` returns `TRY_WITH_EMPTY_HAND` (`:93-95`),
     * which routes straight into `useWithoutItem` (`:352-354`). [DetonateHand.SAFE] still picks an item that
     * cannot consume the click ([consumesUse] inverted), so that in the desync window - the server still seeing
     * `CHARGE == 0`, `useWithoutItem` returning `PASS` - the fall-through to `itemStack.useOn` is a no-op instead
     * of a stray respawn anchor placed on top of the geometry that was just scored.
     */
    private fun slotFor(move: AnchorMove): HotbarItemSlot? {
        val mode = settings.switchMode
        return when (move) {
            AnchorMove.PLACE -> hotbarSlotFor(Items.RESPAWN_ANCHOR, mode)
            AnchorMove.CHARGE -> hotbarSlotFor(Items.GLOWSTONE, mode)
            AnchorMove.DETONATE -> when (settings.detonateHand) {
                DetonateHand.ANCHOR -> hotbarSlotFor(Items.RESPAWN_ANCHOR, mode)
                DetonateHand.HELD -> null
                DetonateHand.SAFE -> restrictSlot(safeSlot(), mode)
            }
            AnchorMove.NONE -> null
        }
    }

    /**
     * The [DetonateHand.SAFE] slot: a TOTEM that cannot consume the click if the hotbar has one, otherwise the
     * slot the server already holds when its item qualifies, otherwise the first hotbar slot that does.
     *
     * The totem comes first because the detonating hand is the one hand we get to choose, and holding a totem in
     * it is free life insurance against our own blast: `LivingEntity.checkTotemDeathProtection`
     * (`net/minecraft/world/entity/LivingEntity.java:1364-1394`) walks `InteractionHand.values()` - MAIN_HAND
     * FIRST - and takes the first stack carrying `DataComponents.DEATH_PROTECTION`, and `bad_respawn_point` is in
     * none of the `bypasses_*` tags, `#bypasses_invulnerability` included, so the check is reached. A totem is
     * also never `consumesUse` (a plain `Item` with no consumable, equippable, blocks-attacks or kinetic
     * component), so it satisfies the SAFE rule on its own terms rather than as an exception to it - the
     * [consumesUse] filter is still applied to it, so a resource pack's or a server's custom death-protecting
     * item that DOES consume a use is passed over rather than trusted.
     *
     * Preferring the HELD slot after that is not a micro-optimisation but a packet one: every switch is one more
     * `SetCarriedItem` on the wire, and a cycle that already emits up to three of them should not add a fourth for
     * an item it does not need to change. The totem outranks it because one packet is a cheap price for a life;
     * when the held item is itself a totem both rules agree and nothing is sent at all. Null when NO hotbar slot
     * qualifies - the executor then falls back to the held item and only refuses glowstone, which is the same
     * behaviour [DetonateHand.HELD] has.
     */
    private fun safeSlot(): HotbarItemSlot? {
        val held = Slots.Hotbar.getOrNull(SilentHotbar.serversideSlot)?.takeIf { !consumesUse(it.itemStack) }
        if (held != null && protectsFromDeath(held.itemStack)) return held

        Slots.Hotbar.findSlot { protectsFromDeath(it) && !consumesUse(it) }?.let { return it }

        return held ?: Slots.Hotbar.findSlot { !consumesUse(it) }
    }

    /**
     * Whether [stack] would save us from a killing blow - the exact test vanilla runs, the
     * `DataComponents.DEATH_PROTECTION` component (`LivingEntity.java:1373`), not an id comparison against
     * `Items.TOTEM_OF_UNDYING`. A data pack or a server may hang the component on anything, and `ModuleOffhand`'s
     * own totem predicate (`modules/player/offhand/ModuleOffhand.kt:248`) already reads it this way.
     */
    private fun protectsFromDeath(stack: ItemStack): Boolean = stack.has(DataComponents.DEATH_PROTECTION)

    /** Whether the candidate reported [dir] as a placement face; see [AnchorCandidate.clickFaces]. */
    private fun hasFace(dir: Direction): Boolean = (faces and (1 shl dir.ordinal)) != 0

    /**
     * `AnchorSearch.isClickable` for the running cycle: a neighbour inside `Anchor.Range` that the anchor will not
     * replace (`BlockPlaceContext.java:35-36`) and whose own use will not consume the click
     * (`utils/block/BlockExtensions.kt:659-685`).
     *
     * The search's `|| sneaking` term has no counterpart here on purpose: the module hub stands down entirely
     * while `player.isSecondaryUseActive`, because `performUseItemOn` sets `suppressUsingBlock` and skips the
     * block (`MultiPlayerGameMode.java:339-341`), so a sneaking tick never reaches this method.
     */
    private fun isClickable(view: AnchorView, ledger: AnchorLedger, pos: BlockPos): Boolean {
        if (!inRange(view, pos) || ledger.isBlastShadowed(pos)) return false

        val state = view.state(pos)
        return !view.isReplaceable(state, pos) && !state.isInteractable
    }

    /**
     * `AnchorSearch.isSelfClickable` for the running cycle: the cell is in `Anchor.Range`, still replaceable by
     * the anchor (`BlockPlaceContext`'s `replaceClicked` term) and still clickable ([isReplaceInPlace]).
     *
     * Sneaking is passed as `false` for the same reason [isClickable] drops the term: the module hub stands down
     * entirely while `player.isSecondaryUseActive`, so a sneaking tick never reaches this method.
     */
    private fun isSelfClickable(view: AnchorView, ledger: AnchorLedger, pos: BlockPos): Boolean {
        if (!inRange(view, pos) || ledger.isBlastShadowed(pos)) return false

        val state = view.state(pos)
        val replaceable = view.isReplaceable(state, pos)
        return isReplaceInPlace(world, pos, state, replaceable, CollisionContext.of(player), sneaking = false)
    }

    /** Eye to `AABB(pos)` inside `Anchor.Range`: the lower bound of the eye-to-hit-point distance of the click. */
    private fun inRange(view: AnchorView, pos: BlockPos): Boolean {
        val range = settings.range.toDouble()
        return blockDistanceSq(pos.x, pos.y, pos.z, view.eye) < range * range
    }

    /** The block one interaction addresses and, for a placement, the face that decides where the anchor lands. */
    private class ClickTarget(val block: BlockPos, val face: Direction?)

    private companion object {

        /** Rays one look point search may trace; the budget `MineJob` gives its own searches. */
        const val LOOK_BUDGET = 96
    }
}

/** The hotbar slot holding [item], subject to [restrictSlot]; null when the phase cannot be paid for. */
internal fun hotbarSlotFor(item: Item, switchMode: SwitchMode): HotbarItemSlot? =
    restrictSlot(Slots.Hotbar.findSlot(item), switchMode)

/**
 * With [SwitchMode.NONE] no `SetCarriedItem` may go out, so only the slot the server already holds counts; every
 * other mode may switch to any hotbar slot.
 *
 * The server-side slot is `SilentHotbar.serversideSlot`, not `Inventory.selectedSlot`: a silent selection of ours
 * (or of another module) is what `ensureHasSentCarriedItem` announces
 * (`MixinMultiPlayerGameMode.hookCustomSelectedSlot`), and it is also what `Inventory.getSelectedItem` returns
 * through `MixinInventory`, so it is the slot both sides actually resolve the click with.
 *
 * Top-level because the module hub needs the same answer one step earlier: `AnchorAmmo` folds "which entry phases
 * are affordable right now" into the search's acceptance, and it has to agree with the sequencer exactly, or the
 * search proposes cycles the job then refuses and the module starves.
 */
internal fun restrictSlot(slot: HotbarItemSlot?, switchMode: SwitchMode): HotbarItemSlot? =
    if (switchMode != SwitchMode.NONE) slot else slot?.takeIf { it.hotbarIndex == SilentHotbar.serversideSlot }
