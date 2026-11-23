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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.PlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.TargetEntry
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.utils.block.searchBlocksInCuboid
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue

/**
 * One accepted placement: the obsidian/bedrock [pos] to click, the explosion [center] of the crystal that would
 * stand on it (`ExplosionMath.crystalCenter`, the `(x + 0.5, y, z + 0.5)` of `EndCrystalItem.useOn`,
 * `net/minecraft/world/item/EndCrystalItem.java:42`), the [target] it was scored against, that target's [damage]
 * (evaluated at `TargetEntry.placeState`) and the local player's [selfDamage].
 */
data class PlaceCandidate(
    val pos: BlockPos,
    val center: Vec3,
    val target: TargetEntry,
    val damage: DamageResult,
    val selfDamage: DamageResult,
)

/**
 * Finds the obsidian/bedrock block to place a crystal on right now. One instance per tick (it holds the tick's
 * [WorldView]); main thread only.
 *
 * ### Candidate cells ([candidates])
 * The integer cube of radius `settings.range + 1` around [WorldView.eye] (`Vec3.searchBlocksInCuboid`,
 * BlockExtensions.kt:194-202, which is `BlockPos.betweenClosed`, `net/minecraft/core/BlockPos.java:405-427`; the
 * iterator reuses one `MutableBlockPos`, so accepted cells are copied with `immutable()`). Nothing is allocated for a
 * cell that fails one of these checks, in this order:
 * - the eye is closer than `settings.range` to `AABB(pos)` (`AABB.java:31-33`; [blockDistanceSq], squared). The range
 *   is the eye-to-hit-point distance of the later click; the box distance is its lower bound, the exact test is the
 *   look point search. The server's own check (`ServerGamePacketListenerImpl.java:1321` →
 *   `Player.isWithinBlockInteractionRange(pos, 1.0)`, `Player.java:1980`: eye to `AABB(pos)` below the block
 *   interaction range + 1.0) is looser than any configurable range.
 * - `EndCrystalItem.useOn` accepts only `Blocks.OBSIDIAN` / `BEDROCK` (`EndCrystalItem.java:27`) with an empty block
 *   above (`:30-32`). [collectCells] spells that out on the state it already holds ([isBase], then `above` air), which
 *   is [WorldView.isPlaceable] without re-reading the cell — two `getBlockState` calls per candidate instead of three.
 *   [evaluate], which starts from a bare position, still calls [WorldView.isPlaceable].
 * - no placement of ours is still pending for `pos.above()` ([CrystalLedger.isPending]).
 * - the crystal that would stand there is within `BreakSettings.Range` of the eye ([crystalDistanceSq] against
 *   [breakReachSq]) — a crystal we could never detonate is a wasted one that also blocks its own column.
 * - [WorldView.isColumnFree]: the entity box `(x, y, z, x + 1, y + 2, z + 1)` of `above` (`:34-39`) holds nothing but
 *   ignored entities. This is the only entity query per cell, so [ExplosionRanker] runs it only after a target's damage
 *   bound has passed.
 *
 * ### Damage
 * The crystal explodes at [PlaceCandidate.center] with power 6 and hurts a victim only when its feet are within 12
 * blocks (`net/minecraft/world/level/ServerExplosion.java:182-183`). [DamageCalculator.upperBound] (full exposure, no
 * rays) bounds [DamageCalculator.damage] from above (see its KDoc), which [ExplosionRanker] turns into a branch and
 * bound over all (cell, target) pairs.
 *
 * ### Acceptance and ordering (contract; [candidates] and [evaluate])
 * Target: `damage.effective >= settings.minDamage`, or `settings.lethalOverride` and [DamageResult.isLethalFor] the
 * target's `placeState`. Self: `selfDamage.effective <= settings.maxSelfDamage`, and with `settings.antiSuicide`
 * `selfDamage.healthLoss + selfDamage.absorbed < view.self.health + view.self.absorption`. Ordering: higher target
 * `damage.effective` first; ties → lower `selfDamage.effective`, then nearer to the eye (box distance).
 *
 * ### Deliberate deviations
 * - A candidate whose target damage is exactly `0` (victim outside the 12 blocks, skipped, or fully resisted) is never
 *   accepted, even with `MinDamage = 0`: vanilla would not hurt that victim at all.
 * - Self damage costs 45 rays, so at most [ExplosionRanker.MAX_FINALISTS] finalists are ever tried; when every one of
 *   them fails the self checks the result is empty although a later finalist might have passed.
 * - [candidates] is the whole accepted ranking and not just its head, so a caller that cannot use the best placement
 *   (`TickPlanner`: its own blast would waste it, or no rotation reaches it this tick) can demote instead of idling.
 *   Pulling the head costs what the old single-result search cost; each demotion costs one more 45-ray self-damage
 *   evaluation and the chain is bounded by [ExplosionRanker.MAX_FINALISTS].
 */
class PlaceSearch(
    private val view: WorldView,
    private val calc: DamageCalculator,
    private val settings: PlaceSettings,
    private val ledger: CrystalLedger,
    private val ignore: (Entity) -> Boolean,
    breakRange: Float,
) {

    /**
     * `PlaceSettings.Range`, read once for the tick: the eye-to-`AABB(pos)` bound [best] and [evaluate] apply. Read
     * by `BasePlaceSearch`, whose base cells are worthless beyond it — [evaluate] would reject them anyway.
     */
    internal val range: Float = settings.range

    /** [range] squared, the form both distance tests use ([blockDistanceSq] is squared as well). */
    private val rangeSq: Double = range.toDouble() * range

    /**
     * `BreakSettings.Range` squared: a cell whose crystal would stand outside it is refused outright, because we
     * could never detonate that crystal.
     *
     * `Place.Range` (4.5) reaches farther than `Break.Range` (3.0) and nothing linked the two, so the band between
     * them produced crystals that no `RayTests.hitsCrystal(range = breakRange)` can ever reach: the attack is
     * dropped for good (`TickPlanner.resolve` drops an attack with no reachable aim), `wastedByOwnBlast` explicitly
     * skips out-of-reach own crystals, and the crystal keeps occupying its column - the aura pays for a crystal that
     * can do nothing and loses the block under it. Whether a cell qualifies is re-decided every tick, so stepping
     * closer brings it straight back.
     */
    private val breakReachSq: Double = breakRange.toDouble() * breakRange

    /** Bound-only ranker (its queue is never used) shared by every [upperBound] call. */
    private val bounds by lazy { ranker(sourceOverride = null) }

    /**
     * Every placement this tick's ranker accepts, best first, evaluated ON DEMAND ([ExplosionRanker.ranked]).
     *
     * [collectCells] runs EAGERLY here, before anything is pulled. [ExplosionRanker] is single use - the cell loop
     * fills its queue and the first pull drains it - so filling the queue from inside the lazy body would make the
     * scan depend on whether the caller pulls at all, and a second iteration would find an empty queue instead of
     * the same list. Filling first and keeping only the ranking lazy gives the sequence one honest shape: it may be
     * iterated ONCE (`kotlin.sequences.sequence` throws `IllegalStateException` on a second iterator), its head
     * costs exactly what [best] costs, and every further element costs one more 45-ray self-damage evaluation, at
     * most [ExplosionRanker.MAX_FINALISTS] of them in total.
     *
     * `TickPlanner.decide` walks it to demote past a candidate that its own blast would waste or that no rotation
     * can reach this tick; [best] stays the entry point for everything that means "the best placement available"
     * (the MinGain floor of `BasePlaceSearch`).
     */
    fun candidates(): Sequence<PlaceCandidate> {
        val ranker = ranker(sourceOverride = null)
        collectCells(ranker)
        return ranker.ranked().map { it.toCandidate() }
    }

    /** Best obsidian/bedrock position to place a crystal on right now (uses `TargetEntry.placeState`), or null. */
    fun best(): PlaceCandidate? = candidates().firstOrNull()

    /**
     * Evaluates one explicit [pos] with the acceptance rules of [best]: range, [WorldView.isPlaceable] (only when
     * [requirePlaceable] — `false` for an obsidian base that does not exist yet, the caller has checked the cell
     * itself), no pending placement and a free column. [sourceOverride] wraps both the targets' and the local player's
     * [ShapeSource] (`BasePlaceSearch` passes an `OverrideShapeSource` that adds the future obsidian).
     */
    fun evaluate(
        pos: BlockPos,
        requirePlaceable: Boolean = true,
        sourceOverride: ((ShapeSource) -> ShapeSource)? = null,
    ): PlaceCandidate? {
        val distSq = blockDistanceSq(pos.x, pos.y, pos.z, view.eye)
        if (distSq >= rangeSq || !admits(pos, requirePlaceable)) return null
        val ranker = ranker(sourceOverride)
        ranker.offer(pos.immutable(), ExplosionMath.crystalCenter(pos), distSq)
        return ranker.best()?.toCandidate()
    }

    /**
     * Largest [DamageCalculator.upperBound] over the targets that could pass the target acceptance for a crystal on
     * [pos], `0` when none could. Pure arithmetic, no placeability check — the pruning key of `BasePlaceSearch`.
     */
    internal fun upperBound(pos: BlockPos): Float = bounds.bound(ExplosionMath.crystalCenter(pos))

    /**
     * The cell loop of [best]. The cell state is read once and reused for [isBase] and for the `above` air test, so
     * an obsidian / bedrock cell costs two `getBlockState` calls instead of the three of `isBase` +
     * [WorldView.isPlaceable]; the reused [BlockPos.MutableBlockPos] also spares the `pos.above()` allocation of
     * [admits]. Accept / reject is unchanged: `isBase(state(cell)) && state(above).isAir` is [WorldView.isPlaceable]
     * verbatim.
     */
    private fun collectCells(ranker: ExplosionRanker<BlockPos>) {
        val eye = view.eye
        val above = BlockPos.MutableBlockPos()
        for (cell in eye.searchBlocksInCuboid(range + 1f)) {
            val distSq = blockDistanceSq(cell.x, cell.y, cell.z, eye)
            if (distSq >= rangeSq || !isBase(view.state(cell))) continue
            above.set(cell.x, cell.y + 1, cell.z)
            if (!view.state(above).isAir || ledger.isPending(above)) continue
            if (crystalDistanceSq(cell.x, cell.y, cell.z, eye) >= breakReachSq) continue
            ranker.offer(cell.immutable(), ExplosionMath.crystalCenter(cell), distSq)
        }
    }

    /** The placeability rules except the range and the column (see the class KDoc); used by [evaluate]. */
    private fun admits(pos: BlockPos, requirePlaceable: Boolean): Boolean {
        if (requirePlaceable && !view.isPlaceable(pos)) return false
        if (crystalDistanceSq(pos.x, pos.y, pos.z, view.eye) >= breakReachSq) return false
        return !ledger.isPending(pos.above())
    }

    /** `blockState.is(Blocks.OBSIDIAN) || blockState.is(Blocks.BEDROCK)` (`EndCrystalItem.java:27`). */
    private fun isBase(state: BlockState): Boolean = state.`is`(Blocks.OBSIDIAN) || state.`is`(Blocks.BEDROCK)

    private fun ranker(sourceOverride: ((ShapeSource) -> ShapeSource)?): ExplosionRanker<BlockPos> {
        val sourceOf: (TargetEntry) -> ShapeSource = { target ->
            val source = target.source
            sourceOverride?.invoke(source) ?: source
        }
        return ExplosionRanker(
            view = view,
            calc = calc,
            settings = settings,
            victimOf = { it.placeState },
            sourceOf = sourceOf,
            selfSource = sourceOverride?.invoke(view.selfSource) ?: view.selfSource,
            gate = { pos -> view.isColumnFree(pos.above(), ignore) },
        )
    }

    private fun Ranked<BlockPos>.toCandidate(): PlaceCandidate = PlaceCandidate(
        pos = key,
        center = center,
        target = target,
        damage = damage,
        selfDamage = selfDamage,
    )
}

/**
 * Squared distance from [point] to the unit cube `AABB(BlockPos(x, y, z))` (`AABB.java:31-33`): the arithmetic of
 * `AABB.distanceToSqr(x, y, z)` (BoxExtensions.kt:118-123) without allocating the box.
 */
internal fun blockDistanceSq(x: Int, y: Int, z: Int, point: Vec3): Double {
    val dx = maxOf(x - point.x, point.x - (x + 1), 0.0)
    val dy = maxOf(y - point.y, point.y - (y + 1), 0.0)
    val dz = maxOf(z - point.z, point.z - (z + 1), 0.0)
    return Mth.lengthSquared(dx, dy, dz)
}

/**
 * Squared distance from [point] to the hitbox of a crystal standing on the block ([x], [y], [z]) —
 * `[x - 0.5, y + 1, z - 0.5] .. [x + 1.5, y + 3, z + 1.5]` ([ExplosionMath.crystalBox], `EndCrystal` is
 * `sized(2.0F, 2.0F)`) — with the arithmetic of [blockDistanceSq] and without allocating the box.
 */
internal fun crystalDistanceSq(x: Int, y: Int, z: Int, point: Vec3): Double {
    val dx = maxOf(x - 0.5 - point.x, point.x - (x + 1.5), 0.0)
    val dy = maxOf(y + 1.0 - point.y, point.y - (y + 3.0), 0.0)
    val dz = maxOf(z - 0.5 - point.z, point.z - (z + 1.5), 0.0)
    return Mth.lengthSquared(dx, dy, dz)
}

/** An explosion centre paired with one target and its exposure-free damage bound; [key] names the source. */
internal class Bound<K>(val key: K, val center: Vec3, val distSq: Double, val target: TargetEntry, val ub: Float)

/** A [Bound] refined with rays whose target [damage] passed the target acceptance. */
internal class Scored<K>(val bound: Bound<K>, val damage: DamageResult)

/** A [Scored] whose [selfDamage] passed the self checks — the result of [ExplosionRanker.best]. */
internal class Ranked<K>(private val scored: Scored<K>, val selfDamage: DamageResult) {

    val key: K get() = scored.bound.key
    val center: Vec3 get() = scored.bound.center
    val distSq: Double get() = scored.bound.distSq
    val target: TargetEntry get() = scored.bound.target
    val damage: DamageResult get() = scored.damage
}

/**
 * Branch-and-bound ranking of explosion centres against the targets of a [WorldView], shared by [PlaceSearch]
 * (centres keyed by the base block) and [BreakSearch] (keyed by the crystal). Single use: [offer] queues, the first
 * pull of [ranked] (or of [best], its head) consumes the queue. [victimOf] selects the victim snapshot
 * (`placeState` / `breakState`), [sourceOf] the shape source of a target's exposure rays, [selfSource] the local
 * player's, and [gate] is the expensive per-key admission test (entity column) that runs at most once per key and
 * only after a bound has passed.
 *
 * 1. [offer]: for every target `ub = calc.upperBound(center, victim)`; queued when `ub > 0` and `ub >= MinDamage` or
 *    (LethalOverride and `ub >= health + absorption` — the bound form of `DamageResult.isLethalFor`, because
 *    `healthLoss = max(effective - absorption, 0)`).
 * 2. [refine]: the queue is drained in descending `ub`; each entry is evaluated with rays
 *    ([DamageCalculator.damage]) and kept when it passes the target acceptance. The drain stops as soon as the next
 *    `ub` is below the best `effective` found: no later entry can reach or tie it. (A strict test keeps exact ties
 *    alive so that the self-damage and distance tie-breaks of the contract still apply.)
 * 3. [ranked]: self damage for the finalists in order (`effective` desc, then distance), at most [MAX_FINALISTS] of
 *    them, one tie group at a time and only as far as the consumer pulls. A finalist that fails the self checks
 *    never ends the search — the next one is tried. Yielded: every accepted finalist, ordered by `effective` desc,
 *    `selfDamage.effective` asc, distance asc; [best] is the first of them and pays for nothing beyond it.
 */
internal class ExplosionRanker<K : Any>(
    private val view: WorldView,
    private val calc: DamageCalculator,
    private val settings: PlaceSettings,
    private val victimOf: (TargetEntry) -> VictimState,
    private val sourceOf: (TargetEntry) -> ShapeSource,
    private val selfSource: ShapeSource,
    private val gate: (K) -> Boolean,
) {

    private val heap by lazy { PriorityQueue<Bound<K>>(BY_BOUND) }

    /** Largest passing bound at [center] (step 1 without queueing), `0` when no target could pass. */
    fun bound(center: Vec3): Float {
        var best = 0f
        for (target in view.targets) {
            val victim = victimOf(target)
            val bound = calc.upperBound(center, victim)
            if (bound > best && passesBound(bound, victim)) best = bound
        }
        return best
    }

    /**
     * Queues [center] for every target whose bound passes; [gate] is consulted before the first entry is queued and a
     * closed gate queues nothing. Returns whether anything was queued.
     */
    fun offer(key: K, center: Vec3, distSq: Double): Boolean {
        var open = false
        for (target in view.targets) {
            val victim = victimOf(target)
            val bound = calc.upperBound(center, victim)
            if (!passesBound(bound, victim)) continue
            if (!open && !gate(key)) return false
            open = true
            heap.add(Bound(key, center, distSq, target, bound))
        }
        return open
    }

    /**
     * Steps 2 and 3, evaluated ON DEMAND: the accepted finalists in rank order, empty when nothing queued passes
     * both acceptances. [refine] itself runs at the first pull and drains the queue, so the ranker is spent from
     * then on and the sequence may be iterated only once.
     *
     * The unit of work is the TIE GROUP, the run of finalists with equal `damage.effective` - contiguous, because
     * [BY_TARGET] sorts by that value first. Pulling an element evaluates the self damage of every finalist in its
     * group, and of every fully rejected group before it, keeps those passing [acceptsSelf] and yields them in
     * [BY_RANK] order; nothing beyond that group is touched until the consumer asks for more. That is precisely the
     * walk the old single-result [best] made: it evaluated finalists until the first accepted one and then on to
     * the end of THAT one's tie group, because [BY_RANK] may still re-order a tie by self damage and distance. So
     * the head of this sequence is the value [best] returned, for the same `calc.damage` calls in the same order;
     * see [rankFinalists].
     */
    fun ranked(): Sequence<Ranked<K>> = rankFinalists(
        finalists = ::refine,
        selfDamage = { center -> calc.damage(center, view.self, selfSource) },
        acceptsSelf = ::acceptsSelf,
    )

    /** Steps 2 and 3 for one result: the first element of [ranked], null when nothing passes both acceptances. */
    fun best(): Ranked<K>? = ranked().firstOrNull()

    private fun refine(): List<Scored<K>> {
        val finalists = ArrayList<Scored<K>>()
        var bestEffective = 0f
        var next = heap.poll()
        while (next != null && next.ub >= bestEffective) {
            val victim = victimOf(next.target)
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

    private fun passesBound(bound: Float, victim: VictimState): Boolean {
        if (bound <= 0f) return false
        return bound >= settings.minDamage ||
            (settings.lethalOverride && bound >= victim.health + victim.absorption)
    }

    private fun acceptsTarget(damage: DamageResult, victim: VictimState): Boolean {
        if (damage.effective <= 0f) return false
        return damage.effective >= settings.minDamage || (settings.lethalOverride && damage.isLethalFor(victim))
    }

    private fun acceptsSelf(selfDamage: DamageResult): Boolean {
        if (selfDamage.effective > settings.maxSelfDamage) return false
        return !settings.antiSuicide ||
            selfDamage.healthLoss + selfDamage.absorbed < view.self.health + view.self.absorption
    }

    private companion object {

        /**
         * How many finalists may get the self-damage evaluation. [best] walks them in rank order and stops at the
         * first that passes the self checks, so the cap is only reached when every one of them is unsafe (typically
         * `MaxSelfDamage` / `AntiSuicide` against a victim standing right next to us). The trade-off: one more
         * finalist is one more 45-ray `calc.damage` for the local player in that worst case, while a cap that is too
         * small silently returns null although a self-safe placement exists — the previous 3 was reachable with three
         * near-tied cells on the far side of the victim, hence 8.
         */
        const val MAX_FINALISTS = 8

        val BY_BOUND: Comparator<Bound<*>> = compareByDescending { it.ub }

        val BY_TARGET: Comparator<Scored<*>> =
            compareByDescending<Scored<*>> { it.damage.effective }.thenBy { it.bound.distSq }
    }
}

/**
 * The rank-ordered walk over the finalists of an [ExplosionRanker], lazy one tie group at a time - all of
 * [ExplosionRanker.ranked] except where the finalists and the self damage come from, so that the ordering contract
 * can be exercised on a constructed ranking.
 *
 * [finalists] is a supplier and not a list because producing it drains the ranker's queue: it must run when the
 * first element is pulled, not when the sequence is built (`PlaceSearch.candidates` builds the sequence one
 * statement after filling that queue and hands it on unpulled). It must be sorted by [ExplosionRanker.BY_TARGET],
 * `damage.effective` descending, which is what makes a tie group contiguous.
 *
 * Per group: [selfDamage] for every member, [acceptsSelf] as the filter, [BY_RANK] as the order. A group whose
 * members all fail contributes nothing and the walk goes on - one unsafe candidate never ends the search, the rule
 * the old `best()` implemented through its `best == null` guard and its `effective <` break.
 */
internal fun <K> rankFinalists(
    finalists: () -> List<Scored<K>>,
    selfDamage: (Vec3) -> DamageResult,
    acceptsSelf: (DamageResult) -> Boolean,
): Sequence<Ranked<K>> = sequence {
    val sorted = finalists()
    var from = 0
    while (from < sorted.size) {
        val to = tieEnd(sorted, from)
        yieldAll(acceptedTie(sorted, from, to, selfDamage, acceptsSelf))
        from = to
    }
}

/**
 * End (exclusive) of the run of equal `damage.effective` starting at [from]; never below `from + 1`. Exact float
 * equality is the right test here and reproduces the `effective <` break of the old `best()` on a descending list:
 * a finalist always has `effective > 0f` ([ExplosionRanker] accepts nothing else), so no NaN can reach this.
 */
private fun <K> tieEnd(finalists: List<Scored<K>>, from: Int): Int {
    val effective = finalists[from].damage.effective
    var to = from + 1
    while (to < finalists.size && finalists[to].damage.effective == effective) {
        to++
    }
    return to
}

/** Self damage for `finalists[from, to)`, the ones passing [acceptsSelf] in [BY_RANK] order (possibly empty). */
private fun <K> acceptedTie(
    finalists: List<Scored<K>>,
    from: Int,
    to: Int,
    selfDamage: (Vec3) -> DamageResult,
    acceptsSelf: (DamageResult) -> Boolean,
): List<Ranked<K>> {
    val accepted = ArrayList<Ranked<K>>(to - from)
    for (index in from until to) {
        val scored = finalists[index]
        val self = selfDamage(scored.bound.center)
        if (acceptsSelf(self)) {
            accepted += Ranked(scored, self)
        }
    }
    accepted.sortWith(BY_RANK)
    return accepted
}

/** The contract order of two ACCEPTED candidates: target damage desc, then self damage asc, then distance asc. */
private val BY_RANK: Comparator<Ranked<*>> = compareByDescending<Ranked<*>> { it.damage.effective }
    .thenBy { it.selfDamage.effective }
    .thenBy { it.distSq }
