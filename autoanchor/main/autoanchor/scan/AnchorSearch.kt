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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.AnchorSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.OverrideShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.ShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.AnchorView
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.TargetEntry
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.chargeOf
import net.ccbluex.liquidbounce.utils.block.isInteractable
import net.ccbluex.liquidbounce.utils.block.searchBlocksInCuboid
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import java.util.PriorityQueue
import kotlin.math.sqrt

/** One interaction left: the anchor stands and already carries `CHARGE >= 1`, so only the detonation is missing. */
const val DETONATE_ONLY = 1

/** Two interactions left: the anchor stands at `CHARGE = 0` and still has to be charged before it will explode. */
const val CHARGE_AND_DETONATE = 2

/** Three interactions left: the cell is empty and the whole place / charge / detonate cycle has to run. */
const val FULL_CYCLE = 3

/** Whether a candidate cell already holds a respawn anchor or one would have to be placed there. */
enum class AnchorKind {

    /**
     * The cell already holds a `RespawnAnchorBlock`. No click face has to be found: the anchor's own visible faces
     * are the look target, and neither `useItemOn` nor `useWithoutItem` reads the hit direction
     * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:78-123`).
     */
    EXISTING,

    /**
     * The cell is air or replaceable, and an anchor has to be placed there: by clicking a solid NEIGHBOUR, or - for
     * a cell that holds a replaceable, pickable block - by clicking the cell itself, which vanilla's
     * `replaceClicked` branch replaces (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-61`).
     */
    NEW
}

/**
 * How a cell can be turned into an explosion, and what that costs in interactions.
 *
 * The three counts are not interchangeable and the difference is the whole economics of this module. All three
 * clicks are `UseItemOn` on a block, one packet class, and Grim's `PacketOrderI` allows ONE of those per tick - so
 * [remaining] is also the number of TICKS the cycle needs at best.
 *
 * - [DETONATE_ONLY]: `useWithoutItem` requires `CHARGE != 0` (`RespawnAnchorBlock.java:101`), which an anchor at
 *   `CHARGE >= 1` already satisfies. One click, no items spent, and the anchor is already standing - free.
 * - [CHARGE_AND_DETONATE]: `charge()` (`:176-181`) has to run first, which needs `Items.GLOWSTONE` in hand
 *   (`isRespawnFuel`, `:125-127`).
 * - [FULL_CYCLE]: a `BlockItem` placement (`Items.java:2193`) always lands at `CHARGE = 0` - the block overrides
 *   neither `getStateForPlacement` nor `canSurvive` - so a new cell costs an anchor, a glowstone and three ticks.
 */
data class AnchorEntry(val kind: AnchorKind, val charge: Int, val remaining: Int)

/**
 * Classification of one cell: [AnchorKind.EXISTING] when [charge] is non-null (i.e. `chargeOf` found an anchor),
 * [AnchorKind.NEW] when the cell is [replaceable] instead, `null` when neither holds and no anchor can ever
 * explode there.
 *
 * Pure, so the rule can be exercised without a world; the caller supplies the two readings. Note the asymmetry the
 * caller must preserve: [replaceable] is only consulted when [charge] is null, because an anchor standing in the
 * cell is never a placement target - clicking it charges or detonates it, it does not replace it.
 */
fun anchorEntry(charge: Int?, replaceable: Boolean): AnchorEntry? = when {
    charge != null -> AnchorEntry(
        kind = AnchorKind.EXISTING,
        charge = charge,
        remaining = if (charge >= 1) DETONATE_ONLY else CHARGE_AND_DETONATE,
    )
    replaceable -> AnchorEntry(kind = AnchorKind.NEW, charge = 0, remaining = FULL_CYCLE)
    else -> null
}

/**
 * Which entry phases the caller can actually pay for this tick.
 *
 * Acceptance has to know this, or the search keeps proposing cycles the sequencer then refuses and the module
 * starves - the same failure the doctrine records for the miner. A candidate whose [AnchorEntry.remaining] needs an
 * item we do not have is therefore refused IN the search, not later.
 *
 * - [canPlace]: a respawn anchor is reachable in the hotbar (`Items.RESPAWN_ANCHOR`).
 * - [canCharge]: glowstone is reachable in the hotbar (`Items.GLOWSTONE`, the only fuel -
 *   `RespawnAnchorBlock.isRespawnFuel`, `net/minecraft/world/level/block/RespawnAnchorBlock.java:125-127`) AND
 *   `AnchorSettings.recharge` is on. The setting belongs here rather than in a later gate: with charging switched
 *   off, a cell we would place an anchor on and then never charge is worthless, so it must not be proposed at all.
 *
 * The detonating click needs no item at all - the block takes the click before the item does
 * (`MultiPlayerGameMode.performUseItemOn`, `net/minecraft/client/multiplayer/MultiPlayerGameMode.java:347-354`) -
 * so [DETONATE_ONLY] is always affordable. Whether the currently held item would swallow the click (glowstone) is
 * a hand-selection question for the executor, not a search question: `DetonateHand` can always fix it.
 */
data class AnchorAmmo(val canPlace: Boolean, val canCharge: Boolean) {

    /** Whether a candidate needing [remaining] interactions can be paid for. */
    fun affords(remaining: Int): Boolean = when (remaining) {
        DETONATE_ONLY -> true
        CHARGE_AND_DETONATE -> canCharge
        else -> canPlace && canCharge
    }

    companion object {

        /** Everything affordable; the fixture for tests and for a caller that does its own item accounting. */
        val ALL = AnchorAmmo(canPlace = true, canCharge = true)
    }
}

/**
 * The floor a [AnchorKind.NEW] candidate must clear: `bestExisting * (1 + gainPercent / 100)`.
 *
 * A standing charged anchor is one tick and costs nothing; a new cell is three ticks, one respawn anchor and one
 * glowstone. So a new cell has to BEAT the best standing one by a margin rather than tie it - the shape of
 * `BasePlaceSearch.best(bestExisting)`. With no existing candidate at all the floor is `0` and every new cell is
 * judged on [AnchorSettings.minDamage] alone.
 */
fun newAnchorFloor(bestExisting: Float, gainPercent: Float): Float = bestExisting * (1f + gainPercent / PERCENT)

/** Percent divisor of [newAnchorFloor]. */
private const val PERCENT = 100f

/**
 * One accepted cycle: the [pos] whose anchor is to explode, the explosion [center] of that anchor
 * ([AnchorMath.anchorCenter], `pos.getCenter()` of `RespawnAnchorBlock.java:168`), the [entry] that says what is
 * still missing there, the [target] it was scored against, that target's [damage] and the local player's
 * [selfDamage].
 *
 * [clickFaces] is a bit set of `Direction.ordinal`: bit `dir` is set when `pos.relative(dir)` may be clicked on
 * face `dir.getOpposite()` to place the anchor at [pos] (`BlockPlaceContext`,
 * `net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-57`). [selfClick] is vanilla's OTHER
 * placement form: [pos] itself may be clicked and the anchor replaces what stands there (`replaceClicked`, `:36,
 * 54-61`), which is the only way in for a cell all of whose neighbours are themselves replaceable. Both are
 * meaningful only for [AnchorKind.NEW]; an [AnchorKind.EXISTING] candidate is clicked on the anchor block itself
 * and reports `0` / `false`.
 */
data class AnchorCandidate(
    val pos: BlockPos,
    val center: Vec3,
    val entry: AnchorEntry,
    val clickFaces: Int,
    val selfClick: Boolean,
    val target: TargetEntry,
    val damage: DamageResult,
    val selfDamage: DamageResult,
) {

    /** [AnchorEntry.kind] of [entry]. */
    val kind: AnchorKind get() = entry.kind

    /** [AnchorEntry.charge] of [entry]: the anchor's current charge, `0` for a cell that has none yet. */
    val charge: Int get() = entry.charge

    /** [AnchorEntry.remaining] of [entry]: interactions - and therefore ticks - still to spend. */
    val remaining: Int get() = entry.remaining

    /** Whether the neighbour `pos.relative(dir)` may be clicked to place the anchor; see [clickFaces]. */
    fun hasClickFace(dir: Direction): Boolean = (clickFaces and (1 shl dir.ordinal)) != 0
}

/**
 * Finds the cell whose anchor explosion is worth the interactions it costs. One instance per tick (it holds the
 * tick's [AnchorView]); main thread only.
 *
 * ### Two candidate kinds, one ranking
 * A cell that already holds a `RespawnAnchorBlock` and a cell an anchor could be placed in are ranked TOGETHER, and
 * the ranking carries [AnchorEntry.remaining] so the caller can see what it is buying. The economics are in
 * [newAnchorFloor]: a [AnchorKind.NEW] candidate has to beat the best [AnchorKind.EXISTING] one by
 * [AnchorSettings.newAnchorGain] percent, because one is free and the other costs three ticks and two items.
 *
 * ### The exposure override is the model, not an option
 * Vanilla removes the anchor BEFORE the blast: `level.removeBlock(pos, false)`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:151`) runs before `level.explode(...)` (`:169`), and
 * inside the explosion `hurtEntities` runs before `interactWithBlocks`
 * (`net/minecraft/world/level/ServerExplosion.java:236` before `:240`). So the anchor's own cell is AIR when
 * `getSeenPercent` marches its rays, and BOTH the target's and the local player's [ShapeSource] are wrapped in an
 * [OverrideShapeSource] reporting `Shapes.empty()` for the candidate cell. This is the exact mirror of
 * `BasePlaceSearch.score`, which overrides to `Shapes.block()` for an obsidian base that does not exist yet.
 *
 * Getting it backwards makes every ray from a victim that is not directly above hit the anchor at its own
 * destination and collapses the exposure to `0` - a mistake this repository has already made once, in the
 * `AutoAnchorFinder` of the old AutoAnchor module, and fixed there before that module was deleted. The
 * regression test is `AnchorRankingTest.overriding the anchor cell to empty is what makes a side placement
 * score`.
 *
 * ### Cost order, and why it is NOT `BasePlaceSearch`'s
 * The crystal aura's candidate set is "the handful of obsidian and bedrock cells in reach", so reading the block
 * state first costs nothing. The anchor's candidate set is "every replaceable cell in reach", which is the whole
 * cube - up to 1728 cells at the default range. Reading the state first would mean a `getBlockState` and, for every
 * non-air cell, a `BlockPlaceContext` allocation per cell. So the order here is
 *
 * 1. [blockDistanceSq] against the cube radius - pure arithmetic on the loop's own `MutableBlockPos`;
 * 2. [reachesAnyTarget] - the `dist > 1.0` cut-off of `ServerExplosion.hurtEntities`
 *    (`ServerExplosion.java:182-183`) with `AnchorMath.DOUBLE_RADIUS` = 10, one squared distance and one `sqrt`
 *    per target, no allocation and no pipeline;
 * 3. the damage bound ([ExplosionRanker.bound], full exposure, no rays);
 * 4. and only then [AnchorView.state], the `RespawnAnchorBlock` test ([chargeOf]), the `isAir` fast path and
 *    finally `canBeReplacedWith`, which is the one call that allocates a `BlockPlaceContext`;
 * 5. the ammo test ([AnchorAmmo.affords]).
 *
 * The cube of step 1 is `Range` for BOTH kinds (see [cellReach]): the charge and the detonation click the anchor
 * itself, so no candidate may sit outside `Range` however its placement would be reached.
 *
 * Everything expensive waits for the refine cutoff ([refine]): the click targets ([clickFaces], up to six
 * `getBlockState` plus six `BlockPlaceContext`, and [isSelfClickable], one more of each plus one `getShape`),
 * [AnchorView.isUnobstructed] (one entity query, `NEW` only) and then the 45 exposure rays for the target and 45
 * for the local player.
 *
 * ### Acceptance and ordering (contract)
 * Target: `damage.effective >= settings.minDamage`, or `settings.lethalOverride` and [DamageResult.isLethalFor]
 * the target's snapshot. Self: `selfDamage.effective <= settings.maxSelfDamage`, and with `settings.antiSuicide`
 * `selfDamage.healthLoss + selfDamage.absorbed < view.self.health + view.self.absorption`. A [AnchorKind.NEW]
 * candidate additionally clears [newAnchorFloor]. Ordering: target `damage.effective` desc, then
 * `selfDamage.effective` asc, then [AnchorEntry.remaining] asc (the cheaper cycle wins a tie), then nearer to the
 * eye.
 *
 * ### Deliberate deviations
 * - A candidate whose target damage is exactly `0` is never accepted, even with `MinDamage = 0`: vanilla would not
 *   hurt that victim at all.
 * - [newAnchorFloor] is computed from candidates that passed the TARGET acceptance, before any self damage is
 *   evaluated. A standing anchor that turns out to be self-lethal therefore still raises the bar for new cells in
 *   the same tick. Evaluating it the other way round would mean paying 45 self rays for every existing candidate
 *   before the floor is known, which is the cost this whole ordering exists to avoid.
 * - [MAX_REFINED] caps how many cells of one heap may be drained. The cutoff alone does not bound the work when
 *   NOTHING is accepted (a target behind a wall scores every cell at exposure `0`, so the cutoff never rises off
 *   `0` and the drain would run through every cell whose bound passed `MinDamage`, at 45 rays each). The crystal
 *   aura has the same hole and does not notice it, because its candidates are obsidian cells; here the cap is the
 *   difference between a bounded tick and a stall.
 * - [candidates] is the whole accepted ranking and not just its head, so a caller that cannot use the best cycle
 *   (no rotation reaches it this tick, or the sequencer is already busy elsewhere) can demote instead of idling.
 */
@Suppress("TooManyFunctions")
class AnchorSearch(
    private val view: AnchorView,
    private val calc: DamageCalculator,
    private val settings: AnchorSettings,
    private val ammo: AnchorAmmo,
    private val excluded: (BlockPos) -> Boolean = { false },
) {

    /**
     * `AnchorSettings.Range`, read once for the tick: the eye-to-`AABB(pos)` bound of the clicked block.
     *
     * The range is the eye-to-hit-point distance of the later click; the box distance is its lower bound and the
     * exact test is the look point search of a later stage. The server's own check
     * (`ServerGamePacketListenerImpl.java:1321` -> `Player.isWithinBlockInteractionRange(pos, 1.0)`,
     * `net/minecraft/world/entity/player/Player.java:1980`) is looser than any configurable range.
     */
    val range: Float = settings.range

    private val rangeSq: Double = range.toDouble() * range

    /**
     * Cube radius of [collect]: `Range` itself, for EVERY candidate kind.
     *
     * An earlier revision let a [AnchorKind.NEW] cell sit up to one block beyond `Range` on the grounds that the
     * clicked NEIGHBOUR is what carries the range of the PLACEMENT. That is true of the placement and false of the
     * cycle: the charge (`RespawnAnchorBlock.useItemOn`) and the detonation (`useWithoutItem`) both click the
     * ANCHOR ITSELF, so a cell out of `Range` produces a placement the sequencer then has to abandon two ticks
     * later. The cell is therefore bound by `Range` and the clicked neighbour by [isClickable], which applies the
     * same bound to its own position.
     */
    private val cellReach: Float = range

    private val cellReachSq: Double = rangeSq

    /** `player.isSecondaryUseActive()` (`Player.java:288-290`), read once per search; see [isClickable]. */
    private val sneaking = player.isSecondaryUseActive

    /** `CollisionContext.of(player)` (`ClipContext.java:28-30`): the context the PICK resolves outlines with. */
    private val pickContext: CollisionContext = CollisionContext.of(player)

    private val neighbour = BlockPos.MutableBlockPos()

    /** Bound-only ranker (its queue is never used) shared by every bound and acceptance test. */
    private val bounds: ExplosionRanker<Cell> by lazy { ranker(cell = null) }

    /**
     * Every cycle this tick's ranking accepts, best first, evaluated ON DEMAND.
     *
     * The scan itself ([collect] and [refine]) runs at the FIRST PULL, not when the sequence is built - it is one
     * self-contained pass with no state left behind, unlike `PlaceSearch`, whose ranker queue had to be filled
     * eagerly so that a second iteration could not find it drained. What stays lazy past that point is the SELF
     * damage: `rankFinalists` walks the finalists one tie group at a time, so the head of this sequence costs
     * exactly what [best] costs and every further element costs one more 45-ray evaluation, at most
     * [MAX_FINALISTS] of them in total.
     *
     * The sequence may be iterated ONCE (`kotlin.sequences.sequence` throws on a second iterator).
     */
    fun candidates(): Sequence<AnchorCandidate> = rankFinalists(
        finalists = ::finalists,
        selfDamage = { bound -> calc.damage(bound.center, view.self, emptied(view.selfSource, bound.key.pos)) },
        acceptsSelf = bounds::acceptsSelf,
        rank = BY_ANCHOR_RANK,
    ).map(::toCandidate)

    /** The best cycle available right now, or null. */
    fun best(): AnchorCandidate? = candidates().firstOrNull()

    /**
     * Evaluates one explicit [pos] with the acceptance rules of [best], for the per-tick re-validation a sequencer
     * owes its running cycle: the cell still holds what we expect, it is still in range and affordable, it is still
     * unobstructed, and the target is still worth it.
     *
     * The one rule NOT applied here is [newAnchorFloor]: it compares a candidate against the rest of the tick's
     * ranking, which a single-cell evaluation does not have. A cycle already under way is not competing for the
     * decision any more, so re-validating it against a margin it only had to clear at the start would abandon it
     * for no gain.
     */
    fun evaluate(pos: BlockPos): AnchorCandidate? {
        val distSq = blockDistanceSq(pos.x, pos.y, pos.z, view.eye)
        if (distSq >= cellReachSq) return null

        val cell = classify(pos, distSq, bounds.bound(AnchorMath.anchorCenter(pos))) ?: return null
        if (!admit(cell)) return null

        val ranker = ranker(cell.pos)
        ranker.offer(key = cell, center = cell.center, distSq = distSq)
        return ranker.best()?.let(::toCandidate)
    }

    /**
     * `LivingEntity.lastHurt` the detonation of an anchor at [pos] would leave on each tracked victim, by entity id
     * - the input `AnchorLedger.markDetonated` stamps so the NEXT explosion is priced with the right i-frame
     * residue.
     *
     * Vanilla stores the post-shield amount: `LivingEntity.hurtServer` sets `lastHurt` at
     * `net/minecraft/world/entity/LivingEntity.java:1203` (inside the invulnerability window) and `:1206`
     * (outside it), in both cases the value that reached `actuallyHurt`. Victims that take nothing
     * (`afterBlocking == 0`) and hits the window swallows are left out: for the latter vanilla returns before
     * touching `lastHurt` (`:1198-1199`), so a previous estimate stays valid and must not be overwritten.
     *
     * The cell is emptied for the rays exactly as [score] does - the anchor is removed before the blast
     * (`RespawnAnchorBlock.java:151` before `:169`) - so this is the same arithmetic the candidate was scored
     * with, not a second model. The local player is not tracked by the ledger and therefore not included.
     */
    fun predictedLastHurt(pos: BlockPos): Map<Int, Float> {
        val result = Int2FloatOpenHashMap()
        val center = AnchorMath.anchorCenter(pos)
        for (target in view.targets) {
            val damage = calc.damage(center, target.state, emptied(target.source, pos))
            if (damage.afterBlocking > 0f && !damage.iFrameGated) {
                result.put(target.entity.id, damage.afterBlocking)
            }
        }
        return result
    }

    /**
     * The finalists of the tick, sorted by [BY_TARGET] so that [rankFinalists]'s tie groups are contiguous.
     *
     * Two refinements, not one, and the order between them is the point: the [AnchorKind.EXISTING] cells decide
     * [newAnchorFloor], and only then are the [AnchorKind.NEW] cells drained with that floor in hand. Doing it in
     * one pass would have to score new cells before the floor is known and drop them afterwards, which prunes
     * existing cells against a cutoff that the gain rule then removes. Splitting is also the cheap direction:
     * standing anchors are rare, so their heap is tiny, while the new-cell heap is the one that needs a floor to
     * cut it down.
     */
    private fun finalists(): List<Scored<Cell>> {
        val cells = collect()
        val existing = refine(cells.existing, floor = 0f)
        val floor = newAnchorFloor(
            bestExisting = existing.maxOfOrNull { it.damage.effective } ?: 0f,
            gainPercent = settings.newAnchorGain,
        )
        val fresh = refine(cells.fresh, floor)

        val all = ArrayList<Scored<Cell>>(existing.size + fresh.size)
        all += existing
        all += fresh
        all.sortWith(BY_TARGET)
        return if (all.size > MAX_FINALISTS) all.subList(0, MAX_FINALISTS) else all
    }

    /** The cheap stage: geometry, then the bound, then the state; see the class KDoc for why that order. */
    private fun collect(): Cells {
        val existing = PriorityQueue<Cell>(BY_BOUND)
        val fresh = PriorityQueue<Cell>(BY_BOUND)
        val eye = view.eye
        for (pos in eye.searchBlocksInCuboid(cellReach)) {
            val distSq = blockDistanceSq(pos.x, pos.y, pos.z, eye)
            if (distSq >= cellReachSq || !reachesAnyTarget(pos)) continue
            val bound = bounds.bound(AnchorMath.anchorCenter(pos))
            if (bound <= 0f) continue
            val cell = classify(pos, distSq, bound) ?: continue
            if (cell.entry.kind == AnchorKind.EXISTING) existing.add(cell) else fresh.add(cell)
        }
        return Cells(existing, fresh)
    }

    /**
     * `!(dist > 1.0)` of `ServerExplosion.hurtEntities` (`ServerExplosion.java:182-183`) for at least one target: an
     * anchor in [pos] explodes at `(x + 0.5, y + 0.5, z + 0.5)` (`RespawnAnchorBlock.java:168`), and a victim whose
     * FEET are farther than `radius * 2 = 10` from it is skipped by vanilla whatever the exposure, so such a cell
     * can never score. Same arithmetic as [AnchorMath.distanceFraction] (`feet.distanceToSqr(center)` squares
     * `center - feet`, hence that subtraction order), without allocating the centre.
     */
    private fun reachesAnyTarget(pos: BlockPos): Boolean {
        val centerX = pos.x + 0.5
        val centerY = pos.y + 0.5
        val centerZ = pos.z + 0.5
        for (target in view.targets) {
            val feet = target.state.feet
            val distSq = Mth.lengthSquared(centerX - feet.x, centerY - feet.y, centerZ - feet.z)
            if (sqrt(distSq) / AnchorMath.DOUBLE_RADIUS.toDouble() <= 1.0) return true
        }
        return false
    }

    /**
     * Reads the cell state once and turns it into a [Cell], or rejects it.
     *
     * [excluded] runs first, on the loop's reused `MutableBlockPos` - it must not retain the position. It is the
     * hook for the detonation-in-flight set: after our detonate click the anchor is STILL VISIBLE locally (the
     * client branch of `useWithoutItem` returns `CONSUME` and `ClientLevel.explode` has an empty body), so without
     * it the search proposes the same anchor every tick until the server's removal arrives.
     */
    private fun classify(pos: BlockPos, distSq: Double, bound: Float): Cell? {
        if (excluded(pos)) return null

        val state = view.state(pos)
        val charge = chargeOf(state)
        val entry = anchorEntry(charge, charge == null && view.isReplaceable(state, pos))
        if (entry == null || !ammo.affords(entry.remaining)) {
            return null
        }
        return Cell(
            pos = pos.immutable(),
            center = AnchorMath.anchorCenter(pos),
            distSq = distSq,
            bound = bound,
            entry = entry,
        )
    }

    /**
     * Drains [heap] in descending bound, admitting and scoring each cell, until the next bound is below the best
     * effective damage found so far - the bound is an upper bound, so no later cell can reach or tie it. The test
     * is non-strict, keeping exact ties alive for the [BY_ANCHOR_RANK] tie-breaks; it is the cutoff of
     * `ExplosionRanker.refine` with [floor] as its starting value instead of `0`, which is exact because anything
     * below the floor is rejected anyway.
     *
     * [MAX_REFINED] caps the drain; see the class KDoc for the case the cutoff alone does not bound.
     */
    private fun refine(heap: PriorityQueue<Cell>, floor: Float): List<Scored<Cell>> {
        val scored = ArrayList<Scored<Cell>>()
        var cutoff = floor
        var drained = 0
        var next = heap.poll()
        while (next != null && next.bound >= cutoff && drained < MAX_REFINED) {
            drained++
            val entry = if (admit(next)) score(next) else null
            if (entry != null && entry.damage.effective >= floor) {
                scored += entry
                cutoff = maxOf(cutoff, entry.damage.effective)
            }
            next = heap.poll()
        }
        return scored
    }

    /**
     * The expensive admissions, cheapest first, and only for a cell the refine cutoff let through.
     *
     * An [AnchorKind.EXISTING] cell needs none of them: the anchor is already standing, so nothing has to be
     * placed, no click target has to exist (the block itself is the look target) and no entity can be in the way of
     * a block that is already there.
     *
     * A [AnchorKind.NEW] cell needs ONE of vanilla's two placement forms - a clickable neighbour ([clickFaces]) or
     * the cell itself ([isSelfClickable]) - and an unobstructed cell.
     */
    private fun admit(cell: Cell): Boolean {
        if (cell.entry.kind == AnchorKind.EXISTING) return true

        cell.faces = clickFaces(cell.pos)
        cell.selfClick = isSelfClickable(cell.pos)
        return (cell.faces != 0 || cell.selfClick) && view.isUnobstructed(cell.pos)
    }

    /**
     * Bit set (`1 shl Direction.ordinal`) of the neighbours of [block] that can be clicked to place the anchor.
     *
     * `BlockItem.useOn` -> `place(new BlockPlaceContext(context))`
     * (`net/minecraft/world/item/BlockItem.java:41-60`); `BlockPlaceContext`
     * (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-57`) puts the block at
     * `hit.getBlockPos().relative(hit.getDirection())` unless the clicked block itself `canBeReplaced(context)`, so
     * a cell at [block] is placed by clicking a NON-replaceable neighbour `block.relative(dir)` on the face
     * `dir.getOpposite()` (`net/minecraft/core/Direction.java:175`).
     */
    private fun clickFaces(block: BlockPos): Int {
        var mask = 0
        for (dir in Direction.entries) {
            neighbour.setWithOffset(block, dir)
            if (isClickable(neighbour)) mask = mask or (1 shl dir.ordinal)
        }
        return mask
    }

    /**
     * Whether the cell itself may be clicked so the anchor REPLACES what stands in it - the `replaceClicked`
     * branch of `BlockPlaceContext` (`BlockPlaceContext.java:31-37, 54-61`), the other half of the placement the
     * neighbour form of [clickFaces] covers.
     *
     * The replaceability term is the same one [classify] read - an [AnchorKind.NEW] cell only exists for a
     * `view.isReplaceable(state, pos)` - and is re-read here so the rule stays one self-contained statement. The
     * cell is inside `Range` by construction: [cellReach] IS `Range` for every kind.
     */
    private fun isSelfClickable(pos: BlockPos): Boolean {
        val state = view.state(pos)
        return isReplaceInPlace(world, pos, state, view.isReplaceable(state, pos), pickContext, sneaking)
    }

    /**
     * A neighbour within `Range` (box distance, the eye-to-hit-point lower bound) that the anchor will not replace
     * (`BlockPlaceContext.java:35-36`) and whose own use will not consume the click.
     *
     * Before the item is used at all, `ServerPlayerGameMode.useItemOn`
     * (`net/minecraft/server/level/ServerPlayerGameMode.java:358-371`; the client mirrors it in
     * `MultiPlayerGameMode.java:339-350`) lets the clicked block consume the click (`state.useItemOn` /
     * `useWithoutItem`) unless `player.isSecondaryUseActive() && haveSomethingInOurHands` - with an anchor in hand
     * that is sneaking (`Player.isSecondaryUseActive` = `isShiftKeyDown()`,
     * `net/minecraft/world/entity/player/Player.java:288-290`). Interactable neighbours are therefore skipped
     * unless the player sneaks, with the client's `isInteractable` list (`utils/block/BlockExtensions.kt:659-685`,
     * collected from the `useWithoutItem` overrides) standing in for the block behaviour.
     */
    private fun isClickable(pos: BlockPos): Boolean {
        if (blockDistanceSq(pos.x, pos.y, pos.z, view.eye) >= rangeSq) return false

        val state = view.state(pos)
        return !view.isReplaceable(state, pos) && (!state.isInteractable || sneaking)
    }

    /**
     * The rays: the best target whose damage passes the target acceptance for an explosion at [cell], or null.
     *
     * This is the per-target half of `ExplosionRanker.refine` for one centre. It is here and not in the ranker
     * because the anchor's expensive admissions ([admit]) have to sit between the refine cutoff and the rays, and
     * `ExplosionRanker` has no hook there - its own per-key gate runs at `offer` time, which for a candidate set of
     * this size would pay six `getBlockState` and an entity query for every cell whose bound passes `MinDamage`.
     */
    private fun score(cell: Cell): Scored<Cell>? {
        var best: Scored<Cell>? = null
        for (target in view.targets) {
            val victim = target.state
            val ub = calc.upperBound(cell.center, victim)
            if (!bounds.passesBound(ub, victim)) continue

            val damage = calc.damage(cell.center, victim, emptied(target.source, cell.pos))
            if (!bounds.acceptsTarget(damage, victim)) continue
            if (best == null || damage.effective > best.damage.effective) {
                val bound = Bound(key = cell, center = cell.center, distSq = cell.distSq, target = target, ub = ub)
                best = Scored(bound, damage)
            }
        }
        return best
    }

    /**
     * [base] with the cell at [pos] reporting `Shapes.empty()` (`net/minecraft/world/phys/shapes/Shapes.java:36-38`)
     * - the anchor is removed before the blast, see the class KDoc.
     */
    private fun emptied(base: ShapeSource, pos: BlockPos): ShapeSource =
        OverrideShapeSource(base = base, x = pos.x, y = pos.y, z = pos.z, shape = Shapes.empty())

    /** An [ExplosionRanker] whose rays see [cell] as air; `null` for the bound-only instance, which casts none. */
    private fun ranker(cell: BlockPos?): ExplosionRanker<Cell> = ExplosionRanker(
        view = view,
        calc = calc,
        settings = settings,
        sourceOf = { target -> if (cell == null) target.source else emptied(target.source, cell) },
        selfSource = if (cell == null) view.selfSource else emptied(view.selfSource, cell),
    )

    private fun toCandidate(ranked: Ranked<Cell>): AnchorCandidate = AnchorCandidate(
        pos = ranked.key.pos,
        center = ranked.center,
        entry = ranked.key.entry,
        clickFaces = ranked.key.faces,
        selfClick = ranked.key.selfClick,
        target = ranked.target,
        damage = ranked.damage,
        selfDamage = ranked.selfDamage,
    )

    /**
     * A cell that passed the cheap stage of [collect]; the heaps are ordered by [bound]. [faces] and [selfClick]
     * are filled by [admit] and stay `0` / `false` for an [AnchorKind.EXISTING] cell, which needs no click target.
     */
    private class Cell(
        val pos: BlockPos,
        val center: Vec3,
        val distSq: Double,
        val bound: Float,
        val entry: AnchorEntry,
    ) {

        var faces: Int = 0

        var selfClick: Boolean = false
    }

    /** The two heaps of [collect], kept apart so the gain floor can be computed before the new cells are drained. */
    private class Cells(val existing: PriorityQueue<Cell>, val fresh: PriorityQueue<Cell>)

    private companion object {

        /**
         * How many cells of one heap may be drained by [refine].
         *
         * The cutoff is the real pruning; this only bounds the case where nothing is ever accepted and the cutoff
         * therefore never rises off the floor - a target behind a wall, where every cell scores exposure `0`. Cells
         * are drained in descending bound, so the cap keeps the most promising ones; 64 is far above the handful
         * that survive the cutoff in an ordinary scene and far below the ~1728 the cube can hold.
         */
        const val MAX_REFINED = 64

        val BY_BOUND: Comparator<Cell> = compareByDescending { it.bound }

        /**
         * The contract order of two ACCEPTED candidates: target damage desc, self damage asc, [AnchorEntry.remaining]
         * asc, distance asc.
         *
         * The `remaining` step is the one the crystal aura has no analogue for. It only fires on an exact tie in
         * both damages - two cells that hurt the victim and us identically - and then prefers the cheaper cycle: a
         * standing charged anchor over one that still needs charging, and either over a cell that needs the whole
         * three-tick sequence.
         */
        val BY_ANCHOR_RANK: Comparator<Ranked<Cell>> = compareByDescending<Ranked<Cell>> { it.damage.effective }
            .thenBy { it.selfDamage.effective }
            .thenBy { it.key.entry.remaining }
            .thenBy { it.distSq }
    }
}

/**
 * Whether the cell at [pos] may be clicked DIRECTLY so the placed block replaces what stands there - the
 * `replaceClicked` branch of `BlockPlaceContext` (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37,
 * 54-61`), which is how a player puts a block into a snow layer or a fire.
 *
 * Four terms, and the first is the one `BlockPlaceContext` itself reads:
 *
 * - [replaceable]: `state.canBeReplaced(context)` for the item in hand, which is what [AnchorView.isReplaceable]
 *   answers through `canBeReplacedWith(pos, ANCHOR_STACK)`. This is the flag that decides WHICH branch vanilla
 *   takes; a respawn anchor is a plain `BlockItem` (`Items.java:2193`), so `SnowLayerBlock.canBeReplaced`
 *   (`net/minecraft/world/level/block/SnowLayerBlock.java:118-126`) answers `layers == 1` for it exactly as it
 *   does for obsidian, and a deeper snow is refused here exactly as vanilla refuses it.
 * - not air. Air is replaceable but has no outline shape, so no ray can pick it - `ClipContext.Block.OUTLINE`
 *   (`net/minecraft/world/level/ClipContext.java:48-50, 58`) would return `Shapes.empty()` and the pick walks on.
 * - not interactable unless [sneaking]. Exactly the term the neighbour form applies: the clicked block consumes
 *   the click before the item is used at all (`ServerPlayerGameMode.useItemOn`,
 *   `net/minecraft/server/level/ServerPlayerGameMode.java:358-371`, mirrored in `MultiPlayerGameMode.java:339-350`)
 *   unless `player.isSecondaryUseActive() && haveSomethingInOurHands`, with the client's `isInteractable` list
 *   (`utils/block/BlockExtensions.kt:659-685`) standing in for the `useWithoutItem` overrides. Neither
 *   `SnowLayerBlock` nor `BaseFireBlock` is on that list and neither overrides `useWithoutItem`.
 * - a non-empty outline shape at [pos], so the pick can land on it at all.
 *
 * The crystal aura carries the same rule as `crystalaura/base/BasePlaceSearch.kt`'s `isReplaceInPlace`; the two
 * modules are deliberately independent, as `blockDistanceSq` and the whole `aim` package already are.
 */
internal fun isReplaceInPlace(
    level: BlockGetter,
    pos: BlockPos,
    state: BlockState,
    replaceable: Boolean,
    context: CollisionContext,
    sneaking: Boolean,
): Boolean {
    if (!replaceable || state.isAir) return false
    if (state.isInteractable && !sneaking) return false
    return !state.getShape(level, pos, context).isEmpty
}

/**
 * Squared distance from [point] to the unit cube `AABB(BlockPos(x, y, z))` (`AABB.java:31-33`): the arithmetic of
 * `AABB.distanceToSqr(x, y, z)` (`utils/math/BoxExtensions.kt:118-123`) without allocating the box.
 */
internal fun blockDistanceSq(x: Int, y: Int, z: Int, point: Vec3): Double {
    val dx = maxOf(x - point.x, point.x - (x + 1), 0.0)
    val dy = maxOf(y - point.y, point.y - (y + 1), 0.0)
    val dz = maxOf(z - point.z, point.z - (z + 1), 0.0)
    return Mth.lengthSquared(dx, dy, dz)
}

/** An explosion centre paired with one target and its exposure-free damage bound; [key] names the source. */
internal class Bound<K>(val key: K, val center: Vec3, val distSq: Double, val target: TargetEntry, val ub: Float)

/** A [Bound] refined with rays whose target [damage] passed the target acceptance. */
internal class Scored<K>(val bound: Bound<K>, val damage: DamageResult)

/** A [Scored] whose [selfDamage] passed the self checks - the result of [ExplosionRanker.best]. */
internal class Ranked<K>(private val scored: Scored<K>, val selfDamage: DamageResult) {

    val key: K get() = scored.bound.key
    val center: Vec3 get() = scored.bound.center
    val distSq: Double get() = scored.bound.distSq
    val target: TargetEntry get() = scored.bound.target
    val damage: DamageResult get() = scored.damage
}

/**
 * Branch-and-bound ranking of explosion centres against the targets of an [AnchorView]. Single use: [offer] queues,
 * the first pull of [ranked] (or of [best], its head) consumes the queue. [sourceOf] selects the shape source of a
 * target's exposure rays and [selfSource] the local player's; both are wrapped by the caller so the candidate cell
 * reports `Shapes.empty()`.
 *
 * 1. [offer]: for every target `ub = calc.upperBound(center, victim)`; queued when `ub > 0` and `ub >= MinDamage`
 *    or (LethalOverride and `ub >= health + absorption` - the bound form of [DamageResult.isLethalFor], because
 *    `healthLoss = max(effective - absorption, 0)`).
 * 2. [refine]: the queue is drained in descending `ub`; each entry is evaluated with rays
 *    ([DamageCalculator.damage]) and kept when it passes the target acceptance. The drain stops as soon as the next
 *    `ub` is below the best `effective` found: no later entry can reach or tie it. (A non-strict test keeps exact
 *    ties alive so that the self-damage and distance tie-breaks of the contract still apply.)
 * 3. [ranked]: self damage for the finalists in order, at most [MAX_FINALISTS] of them, one tie group at a time and
 *    only as far as the consumer pulls. A finalist that fails the self checks never ends the search - the next one
 *    is tried.
 *
 * Two things the crystal aura's copy has are gone. `victimOf` selected between the `placeState` and `breakState`
 * snapshots and is now constant - a [TargetEntry] carries one snapshot, because an anchor has one moment that
 * matters. `gate` was the expensive per-key admission (the crystal's entity column) and ran at `offer` time; the
 * anchor's admissions are far more expensive and its candidate set far larger, so they belong AFTER the refine
 * cutoff, where [AnchorSearch.refine] runs them. That is also why [AnchorSearch] drains its own heap instead of
 * calling [refine] here: this class is used for the bounds, the acceptance rules and the single-cell
 * [AnchorSearch.evaluate].
 */
internal class ExplosionRanker<K : Any>(
    private val view: AnchorView,
    private val calc: DamageCalculator,
    private val settings: AnchorSettings,
    private val sourceOf: (TargetEntry) -> ShapeSource,
    private val selfSource: ShapeSource,
) {

    private val heap by lazy { PriorityQueue<Bound<K>>(BY_BOUND) }

    /** Largest passing bound at [center] (step 1 without queueing), `0` when no target could pass. */
    fun bound(center: Vec3): Float {
        var best = 0f
        for (target in view.targets) {
            val victim = target.state
            val bound = calc.upperBound(center, victim)
            if (bound > best && passesBound(bound, victim)) best = bound
        }
        return best
    }

    /** Queues [center] for every target whose bound passes. Returns whether anything was queued. */
    fun offer(key: K, center: Vec3, distSq: Double): Boolean {
        var open = false
        for (target in view.targets) {
            val victim = target.state
            val bound = calc.upperBound(center, victim)
            if (!passesBound(bound, victim)) continue
            open = true
            heap.add(Bound(key = key, center = center, distSq = distSq, target = target, ub = bound))
        }
        return open
    }

    /**
     * Steps 2 and 3, evaluated ON DEMAND: the accepted finalists in rank order, empty when nothing queued passes
     * both acceptances. [refine] itself runs at the first pull and drains the queue, so the ranker is spent from
     * then on and the sequence may be iterated only once.
     */
    fun ranked(): Sequence<Ranked<K>> = rankFinalists(
        finalists = ::refine,
        selfDamage = { bound -> calc.damage(bound.center, view.self, selfSource) },
        acceptsSelf = ::acceptsSelf,
    )

    /** Steps 2 and 3 for one result: the first element of [ranked], null when nothing passes both acceptances. */
    fun best(): Ranked<K>? = ranked().firstOrNull()

    private fun refine(): List<Scored<K>> {
        val finalists = ArrayList<Scored<K>>()
        var bestEffective = 0f
        var next = heap.poll()
        while (next != null && next.ub >= bestEffective) {
            val victim = next.target.state
            val damage = calc.damage(next.center, victim, sourceOf(next.target))
            if (acceptsTarget(damage, victim)) {
                finalists += Scored(next, damage)
                bestEffective = maxOf(bestEffective, damage.effective)
            }
            next = heap.poll()
        }
        finalists.sortWith(BY_TARGET)
        return if (finalists.size > MAX_FINALISTS) finalists.subList(0, MAX_FINALISTS) else finalists
    }

    /** Step 1's acceptance on a full-exposure bound; `internal` so [AnchorSearch] can prune with the same rule. */
    fun passesBound(bound: Float, victim: VictimState): Boolean {
        if (bound <= 0f) return false
        return bound >= settings.minDamage ||
            (settings.lethalOverride && bound >= victim.health + victim.absorption)
    }

    /** The target acceptance; `internal` so [AnchorSearch.score] applies exactly this rule. */
    fun acceptsTarget(damage: DamageResult, victim: VictimState): Boolean {
        if (damage.effective <= 0f) return false
        return damage.effective >= settings.minDamage || (settings.lethalOverride && damage.isLethalFor(victim))
    }

    /** The self acceptance; `internal` so [AnchorSearch.candidates] applies exactly this rule. */
    fun acceptsSelf(selfDamage: DamageResult): Boolean {
        if (selfDamage.effective > settings.maxSelfDamage) return false
        return !settings.antiSuicide ||
            selfDamage.healthLoss + selfDamage.absorbed < view.self.health + view.self.absorption
    }
}

/**
 * How many finalists may get the self-damage evaluation. The walk stops at the first that passes the self checks,
 * so the cap is only reached when every one of them is unsafe (typically `MaxSelfDamage` / `AntiSuicide` against a
 * victim standing right next to us, which at anchor range is the common case rather than the exotic one). The
 * trade-off: one more finalist is one more 45-ray evaluation for the local player in that worst case, while a cap
 * that is too small silently returns nothing although a self-safe cycle exists.
 */
internal const val MAX_FINALISTS = 8

internal val BY_BOUND: Comparator<Bound<*>> = compareByDescending { it.ub }

/** `damage.effective` descending, then distance - the order that makes a tie group contiguous. */
internal val BY_TARGET: Comparator<Scored<*>> =
    compareByDescending<Scored<*>> { it.damage.effective }.thenBy { it.bound.distSq }

/** The contract order of two ACCEPTED candidates: target damage desc, then self damage asc, then distance asc. */
internal val BY_RANK: Comparator<Ranked<*>> = compareByDescending<Ranked<*>> { it.damage.effective }
    .thenBy { it.selfDamage.effective }
    .thenBy { it.distSq }

/**
 * The rank-ordered walk over the finalists of an [ExplosionRanker], lazy one tie group at a time - all of
 * [ExplosionRanker.ranked] except where the finalists and the self damage come from, so that the ordering contract
 * can be exercised on a constructed ranking.
 *
 * [finalists] is a supplier and not a list because producing it drains a queue (and, for [AnchorSearch], runs the
 * whole cell scan): it must run when the first element is pulled, not when the sequence is built. It must be sorted
 * by [BY_TARGET], `damage.effective` descending, which is what makes a tie group contiguous.
 *
 * Per group: [selfDamage] for every member, [acceptsSelf] as the filter, [rank] as the order. A group whose members
 * all fail contributes nothing and the walk goes on - one unsafe candidate never ends the search.
 *
 * Two parameters differ from the crystal aura's copy. [selfDamage] takes the whole [Bound] rather than only its
 * centre, because each anchor candidate needs its OWN cell overridden to `Shapes.empty()` in the local player's
 * shape source, and the cell is the [Bound.key]. [rank] is a parameter because the anchor's contract order carries
 * a fourth key, `AnchorEntry.remaining`, between the self damage and the distance; [BY_RANK] is the crystal's own
 * order and the default.
 */
internal fun <K> rankFinalists(
    finalists: () -> List<Scored<K>>,
    selfDamage: (Bound<K>) -> DamageResult,
    acceptsSelf: (DamageResult) -> Boolean,
    rank: Comparator<in Ranked<K>> = BY_RANK,
): Sequence<Ranked<K>> = sequence {
    val sorted = finalists()
    var from = 0
    while (from < sorted.size) {
        val to = tieEnd(sorted, from)
        yieldAll(acceptedTie(sorted, from, to, selfDamage, acceptsSelf, rank))
        from = to
    }
}

/**
 * End (exclusive) of the run of equal `damage.effective` starting at [from]; never below `from + 1`. Exact float
 * equality is the right test here on a descending list: a finalist always has `effective > 0f`, so no NaN can reach
 * this.
 */
private fun <K> tieEnd(finalists: List<Scored<K>>, from: Int): Int {
    val effective = finalists[from].damage.effective
    var to = from + 1
    while (to < finalists.size && finalists[to].damage.effective == effective) {
        to++
    }
    return to
}

/** Self damage for `finalists[from, to)`, the ones passing [acceptsSelf] in [rank] order (possibly empty). */
private fun <K> acceptedTie(
    finalists: List<Scored<K>>,
    from: Int,
    to: Int,
    selfDamage: (Bound<K>) -> DamageResult,
    acceptsSelf: (DamageResult) -> Boolean,
    rank: Comparator<in Ranked<K>>,
): List<Ranked<K>> {
    val accepted = ArrayList<Ranked<K>>(to - from)
    for (index in from until to) {
        val scored = finalists[index]
        val self = selfDamage(scored.bound)
        if (acceptsSelf(self)) {
            accepted += Ranked(scored, self)
        }
    }
    accepted.sortWith(rank)
    return accepted
}
