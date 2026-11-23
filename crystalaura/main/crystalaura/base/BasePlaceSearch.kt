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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.BasePlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoint
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoints
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.RayTests
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.OverrideShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.blockDistanceSq
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.canBeReplacedWith
import net.ccbluex.liquidbounce.utils.block.isInteractable
import net.ccbluex.liquidbounce.utils.block.searchBlocksInCuboid
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * An obsidian base to place with [look]; [candidate] is the crystal placement on [block] that justifies it.
 *
 * Vanilla has TWO placement forms and this carries both, because `BlockPlaceContext` picks between them by itself:
 * `replaceClicked = level.getBlockState(hit.getBlockPos()).canBeReplaced(this)`
 * (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37`) decides whether `getClickedPos()` (`:54-57`)
 * answers the CLICKED position or `hit.getBlockPos().relative(hit.getDirection())`.
 *
 * - [face] non-null: the relative form. [clickPos] is the neighbour `block.relative(face.opposite)` and
 *   `clickPos.relative(face) == block`; the hit direction IS the placement and must come back from the ray.
 * - [face] null: the replace-in-place form. `clickPos == block`, the cell already holds a replaceable block, and
 *   vanilla ignores the hit direction entirely once `replaceClicked` is true - so ANY face the ray produces is
 *   correct and the executor must not constrain it.
 */
data class BasePlan(
    val block: BlockPos,
    val clickPos: BlockPos,
    val face: Direction?,
    val look: LookPoint,
    val candidate: PlaceCandidate,
) {

    /** The replace-in-place form: the click lands on [block] itself and the hit direction is free. */
    val replaceClicked: Boolean
        get() = face == null
}

/**
 * Whether the cell at [pos] may be clicked DIRECTLY so the obsidian replaces what stands there - the
 * `replaceClicked` branch of `BlockPlaceContext` (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37,
 * 54-61`), which is how a player puts a block into a snow layer or a fire.
 *
 * Four terms, and the first is the one `BlockPlaceContext` itself reads:
 *
 * - [replaceable]: `state.canBeReplaced(context)` for the item in hand, the client helper
 *   `BlockState.canBeReplacedWith(pos, stack)` (BlockExtensions.kt:464-478). This is the flag that decides WHICH
 *   branch vanilla takes; with obsidian in hand `SnowLayerBlock.canBeReplaced`
 *   (`net/minecraft/world/level/block/SnowLayerBlock.java:118-126`) answers `layers == 1`, so a deeper snow is
 *   refused here exactly as vanilla refuses it.
 * - not air. Air is replaceable but has no outline shape, so no ray can pick it - `ClipContext.Block.OUTLINE`
 *   (`net/minecraft/world/level/ClipContext.java:48-50, 58`) would return `Shapes.empty()` and the pick walks on.
 *   An air cell is placed into with the RELATIVE form instead.
 * - not interactable unless [sneaking]. Exactly the term the neighbour form applies: the clicked block consumes
 *   the click before the item is used at all (`ServerPlayerGameMode.useItemOn`,
 *   `net/minecraft/server/level/ServerPlayerGameMode.java:358-371`, mirrored in `MultiPlayerGameMode.java:339-350`)
 *   unless `player.isSecondaryUseActive() && haveSomethingInOurHands`, with the client's `isInteractable` list
 *   (BlockExtensions.kt:659-685) standing in for the `useWithoutItem` overrides. Neither `SnowLayerBlock` nor
 *   `BaseFireBlock` is on that list and neither overrides `useWithoutItem`, so neither needs the sneak.
 * - a non-empty outline shape at [pos], so the pick can land on it at all.
 *
 * Pure but for [level]: everything else is passed in, which is what makes the rule testable on a `FakeBlockGetter`.
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
 * Finds an obsidian base to place so that a crystal on it beats the best direct placement by `settings.minGain`
 * percent. One instance per tick (it holds the tick's [WorldView]); main thread only.
 *
 * ### Vanilla obsidian placement — TWO forms
 * `BlockItem.useOn` → `place(new BlockPlaceContext(context))` (`net/minecraft/world/item/BlockItem.java:41-60`).
 * `BlockPlaceContext` (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-57`): when the clicked
 * block `canBeReplaced(context)` the obsidian replaces it, otherwise it goes to
 * `hit.getBlockPos().relative(hit.getDirection())`. Both branches are searched here and [BasePlan] carries which
 * one was taken:
 * - the RELATIVE form — a base at `block` placed by clicking a NON-replaceable neighbour
 *   `clickPos = block.relative(dir)` on the face `dir.getOpposite()` (`Direction.java:175`), see [clickFaces];
 * - the REPLACE-IN-PLACE form — the base cell is clicked DIRECTLY and the obsidian takes its place, see
 *   [isReplaceInPlace]. `replaceClicked` makes `getClickedPos()` answer the clicked position and `canPlace()`
 *   (`:59-61`) short-circuit to true, and the hit direction is then read by nobody. This is the form a player uses
 *   on a snow layer or a fire, and it is the only one available when every neighbour of the cell is itself
 *   replaceable. It also matters for the aim: a full-footprint outline shape glued to the bottom of the base cell
 *   (a one-layer snow is 2/16 tall, `SnowLayerBlock.java:31, 53-55`; a fire 1/16, `BaseFireBlock.java:30`) sits
 *   flush against the top face of the DOWN neighbour, and that neighbour's UP face is the only clickable one left
 *   once [isBaseCell] has forced the cell above to be air.
 *
 * `canPlace()` (`:59-61`) requires the destination to be replaceable, which for the relative form is:
 * `BlockBehaviour.canBeReplaced(state, context)` (`net/minecraft/world/level/block/state/BlockBehaviour.java:270-272`)
 * is `state.canBeReplaced() && !itemInHand.is(block.asItem())` plus block overrides — mirrored by the client helper
 * `BlockState.canBeReplacedWith(pos, ItemStack(Items.OBSIDIAN))` (BlockExtensions.kt:464-478; air short-circuits it).
 * `getPlacementState` (`BlockItem.java:113-116`) yields `Block.getStateForPlacement` = `defaultBlockState()`
 * (`net/minecraft/world/level/block/Block.java:457-459`; obsidian is a plain `Block`, `Blocks.java:1187-1190`) and
 * `canPlace(context, state)` (`BlockItem.java:132-136`) = `canSurvive` (the `BlockBehaviour` default `true`,
 * `BlockBehaviour.java:318-320`, which obsidian does not override) and
 * `CollisionGetter.isUnobstructed(state, pos, CollisionContext.placementContext(player))`
 * (`net/minecraft/world/level/CollisionGetter.java:31-34`), see [isUnobstructed].
 *
 * Before the item is used at all, `ServerPlayerGameMode.useItemOn`
 * (`net/minecraft/server/level/ServerPlayerGameMode.java:358-371`; the client mirrors it in
 * `MultiPlayerGameMode.java:339-350`) lets the clicked block consume the click (`state.useItemOn` /
 * `useWithoutItem`) unless `player.isSecondaryUseActive() && haveSomethingInOurHands` — with obsidian in hand that is
 * sneaking (`Player.isSecondaryUseActive` = `isShiftKeyDown()`, `Player.java:288-290`). Interactable neighbours are
 * therefore skipped unless the player sneaks, with the client's `isInteractable` list (BlockExtensions.kt:659-685,
 * collected from the `useWithoutItem` overrides) standing in for the block behaviour.
 *
 * ### The crystal on the new base
 * `EndCrystalItem.useOn` needs `block.above()` empty (`EndCrystalItem.java:30-32`, checked here) and a free entity
 * column, the place range and the damage acceptance — [PlaceSearch.evaluate] with `requirePlaceable = false` and an
 * [OverrideShapeSource] that lets the exposure rays see the future obsidian as its collision shape, the full cube
 * `Shapes.block()` (`Shapes.java:40`). Rule: `bestExisting == null || candidate.damage.effective >=
 * bestExisting.damage.effective * (1 + settings.minGain / 100)`.
 *
 * ### Cost order
 * Cells of the cube of radius [cellReach] around the eye go through [collect], cheapest first; nothing is allocated
 * for a cell that fails one of these:
 * - box distance to the eye ([blockDistanceSq]) below [cellReach]. The `settings.range + 1` half of that radius is
 *   the reach bound (a neighbour within `settings.range` implies the cell within `settings.range + 1`, the box
 *   distance being 1-Lipschitz in the cell offset); the [PlaceSearch.range] half is exact too, because
 *   [PlaceSearch.evaluate] rejects a cell beyond its own range outright, so no farther cell can ever score.
 * - [reachesAnyTarget]: the `dist > 1.0` cut-off of `ServerExplosion.hurtEntities`
 *   (`net/minecraft/world/level/ServerExplosion.java:182-183`) for at least one target — one squared distance and
 *   one `sqrt` per target, no allocation and no pipeline.
 * - [isBaseCell]: air above (`EndCrystalItem.java:30-32`) first, then replaceable by obsidian, the order that keeps
 *   the `BlockPlaceContext` of `canBeReplacedWith` out of every cell whose ceiling is solid.
 * - the damage bound ([PlaceSearch.upperBound], pure arithmetic; `0` or below the MinGain floor → out).
 *
 * Survivors are queued by bound and drained in descending bound by [refine], which stops as soon as the next bound
 * is below the BEST effective damage found so far — no later cell can reach or tie it — exactly like
 * `ExplosionRanker.refine` (PlaceSearch.kt:360-375), a non-strict test keeping exact ties alive for the [BY_RANK]
 * tie-breaks. Only a drained cell pays the expensive admissions ([admit]), again cheapest first: click faces
 * (neighbour within `settings.range`, not replaceable, not interactable unless sneaking; a bit set of `Direction`
 * ordinals) and the replace-in-place test ([isSelfClickable]) → entity obstruction ([isUnobstructed]) →
 * [PlaceSearch.evaluate] (the rays). What survives is ordered like `PlaceSearch` (damage desc, self damage asc,
 * distance asc) and cut to the [LOOK_CANDIDATES] cells that get the ray budget of the look point search
 * ([LOOK_BUDGET] rays per search: `LookPoints.forFace` on the neighbours in `Direction` order, first hit wins, and
 * `LookPoints.forPlacement` on the cell itself for the replace-in-place form) — a selection made after refinement,
 * over the cells actually evaluated. When both forms are reachable the smaller turn wins ([aim]).
 *
 * ### Per-tick worst case
 * With the defaults (`BasePlace.Range` = `Place.Range` = 4.5) the cube is 10 or 11 cells per axis, at most 1331
 * (the `settings.range + 1` = 5.5 cube alone would be 1728–2197). Every cell pays one box distance and, while any
 * target is in range at all, one `sqrt` per target; only a cell that survives that pays up to two `getBlockState`
 * calls plus one `BlockPlaceContext`, and only then the bound arithmetic (one [PlaceSearch.upperBound] per target).
 * The ray cost is bounded by the refine cutoff rather than by the cell count: a cell reaches [admit] only while its
 * full-exposure bound still reaches the best effective damage already found, so it is the cells whose bound ties
 * or beats the true optimum — a handful in an ordinary scene, since the bound decays with the distance to the
 * victim. Each of those costs at most 6 `getBlockState` + 6 `BlockPlaceContext`, two `world.getEntities` queries
 * (the base cube in [isUnobstructed], the crystal column inside [PlaceSearch.evaluate]) and 45 exposure rays for
 * the target plus 45 for the local player; on top come at most [LOOK_CANDIDATES] × 6 × [LOOK_BUDGET] look-point
 * rays. The cutoff this replaces was the [LOOK_CANDIDATES]-th best damage, which — the bound assuming exposure 1.0
 * where real placements see 0.6–0.8 — let through essentially every cell within the explosion radius of the victim.
 */
@Suppress("TooManyFunctions")
class BasePlaceSearch(
    private val view: WorldView,
    private val placeSearch: PlaceSearch,
    private val settings: BasePlaceSettings,
    private val ignore: (Entity) -> Boolean,
    private val wire: Rotation,
    private val slackDeg: Float,
    private val throughBlocks: Boolean,
) {

    /** `player.isSecondaryUseActive()` (`Player.java:288-290`), read once per search. */
    private val sneaking = player.isSecondaryUseActive

    /** `CollisionContext.placementContext(player)` (`CollisionContext.java:37-42`), a per-search snapshot. */
    private val placementContext: CollisionContext = CollisionContext.placementContext(player)

    /** `CollisionContext.of(player)` (`ClipContext.java:28-30`): the context the PICK resolves outlines with. */
    private val pickContext: CollisionContext = CollisionContext.of(player)

    private val rangeSq: Double = settings.range.toDouble() * settings.range

    /**
     * Cube radius of [collect]: `min(settings.range + 1, PlaceSearch.range)`, both halves exact (class KDoc). A
     * base cell may sit up to one block beyond `settings.range` — the clicked NEIGHBOUR is what carries that range,
     * not the base — but never beyond the range [PlaceSearch.evaluate] enforces on the crystal cell itself.
     */
    private val cellReach: Float = minOf(settings.range + 1f, placeSearch.range)

    private val cellReachSq: Double = cellReach.toDouble() * cellReach

    private val above = BlockPos.MutableBlockPos()
    private val neighbour = BlockPos.MutableBlockPos()

    /** Best obsidian base to place (MinGain rule applied against [bestExisting]), or null. */
    fun best(bestExisting: PlaceCandidate?): BasePlan? {
        val floor = if (bestExisting == null) {
            0f
        } else {
            bestExisting.damage.effective * (1f + settings.minGain / PERCENT)
        }
        for (entry in refine(collect(floor), floor)) {
            val plan = aim(entry)
            if (plan != null) return plan
        }
        return null
    }

    /** The cheap stage: geometry, then state, then the bound. Everything expensive waits for [refine]. */
    private fun collect(floor: Float): PriorityQueue<Cell> {
        val heap = PriorityQueue<Cell>(BY_BOUND)
        val eye = view.eye
        for (cell in eye.searchBlocksInCuboid(cellReach)) {
            val distSq = blockDistanceSq(cell.x, cell.y, cell.z, eye)
            if (distSq >= cellReachSq || !reachesAnyTarget(cell) || !isBaseCell(cell)) continue
            val bound = placeSearch.upperBound(cell)
            if (bound > 0f && bound >= floor) heap.add(Cell(cell.immutable(), distSq, bound))
        }
        return heap
    }

    /**
     * `!(dist > 1.0)` of `ServerExplosion.hurtEntities` (`ServerExplosion.java:182-183`) for at least one target: a
     * crystal standing on [cell] explodes at [ExplosionMath.crystalCenter], and a victim whose feet are farther than
     * `radius * 2 = 12` from it is skipped by vanilla whatever the exposure, so such a cell can never score. Same
     * arithmetic as [ExplosionMath.distanceFraction] (`feet.distanceToSqr(center)` squares `center - feet`, hence
     * that subtraction order), without allocating the centre.
     */
    private fun reachesAnyTarget(cell: BlockPos): Boolean {
        val centerX = cell.x + 0.5
        val centerY = (cell.y + 1).toDouble()
        val centerZ = cell.z + 0.5
        for (target in view.targets) {
            val feet = target.placeState.feet
            val distSq = Mth.lengthSquared(centerX - feet.x, centerY - feet.y, centerZ - feet.z)
            if (sqrt(distSq) / ExplosionMath.DOUBLE_RADIUS.toDouble() <= 1.0) return true
        }
        return false
    }

    /**
     * State checks: the block above the cell is air (`EndCrystalItem.java:30-32`) and the cell itself is replaceable
     * by obsidian. The air test comes first because it never allocates, while [isReplaceable] builds a
     * `BlockPlaceContext` for every non-air cell.
     */
    private fun isBaseCell(cell: BlockPos): Boolean {
        above.set(cell.x, cell.y + 1, cell.z)
        if (!view.state(above).isAir) return false
        return isReplaceable(view.state(cell), cell)
    }

    /** Click targets, obstruction and the rays, cheapest first; the scored cell or null. */
    private fun admit(cell: Cell): ScoredCell? {
        val faces = clickFaces(cell.pos)
        val self = isSelfClickable(cell.pos)
        if ((faces == 0 && !self) || !isUnobstructed(cell.pos)) return null
        val candidate = score(cell.pos) ?: return null
        return ScoredCell(cell, faces, self, candidate)
    }

    /**
     * Whether the base cell itself may be clicked so the obsidian REPLACES what stands in it: inside
     * `settings.range` (the click lands on this cell, so it carries the range, not a neighbour) and
     * [isReplaceInPlace]. The replaceability term is re-read rather than inherited from [isBaseCell] so the rule
     * stays one self-contained statement; it costs one `BlockPlaceContext` for a cell that already paid six.
     */
    private fun isSelfClickable(pos: BlockPos): Boolean {
        if (blockDistanceSq(pos.x, pos.y, pos.z, view.eye) >= rangeSq) return false

        val state = view.state(pos)
        return isReplaceInPlace(world, pos, state, isReplaceable(state, pos), pickContext, sneaking)
    }

    /**
     * `BlockPlaceContext.canPlace()` for obsidian at [pos]: `state.canBeReplaced(context)` (`BlockPlaceContext.java:36,
     * 59-61`) through `canBeReplacedWith`; air (`replaceable()`, `Blocks.java:58`, and never the obsidian item) is
     * answered without building the context.
     */
    private fun isReplaceable(state: BlockState, pos: BlockPos): Boolean =
        state.isAir || state.canBeReplacedWith(pos, OBSIDIAN_STACK)

    /** Bit set (`1 shl Direction.ordinal`) of the neighbours of [block] that can be clicked to place the base. */
    private fun clickFaces(block: BlockPos): Int {
        var mask = 0
        for (dir in Direction.entries) {
            neighbour.setWithOffset(block, dir)
            if (isClickable(neighbour)) mask = mask or (1 shl dir.ordinal)
        }
        return mask
    }

    /**
     * A neighbour within `settings.range` (box distance, the eye-to-hit-point lower bound) that the obsidian will not
     * replace (`BlockPlaceContext.java:35-36`) and whose use will not consume the click
     * (`ServerPlayerGameMode.java:358-371`, see the class KDoc).
     */
    private fun isClickable(pos: BlockPos): Boolean {
        if (blockDistanceSq(pos.x, pos.y, pos.z, view.eye) >= rangeSq) return false
        val state = view.state(pos)
        return !isReplaceable(state, pos) && (!state.isInteractable || sneaking)
    }

    /**
     * `CollisionGetter.isUnobstructed(state, pos, context)` (`CollisionGetter.java:31-34`): `shape =
     * state.getCollisionShape(level, pos, context)` (`BlockBehaviour.java:678`), empty → free, else
     * `EntityGetter.isUnobstructed(null, shape.move(pos))` (`net/minecraft/world/level/EntityGetter.java:33-48`): over
     * `getEntities(null, shape.bounds())` — `EntitySelector.NO_SPECTATORS` (`EntityGetter.java:29-31`,
     * `EntitySelector.java:15`) — an entity obstructs when `!isRemoved() && blocksBuilding` (`Entity.java:218, 3851`;
     * the `isPassengerOfSameVehicle` term is void for a null source) `&& Shapes.joinIsNotEmpty(shape,
     * Shapes.create(entity.getBoundingBox()), BooleanOp.AND)` (`Shapes.java:88-90, 156-176`; `BooleanOp.java:12`).
     * Deviation: entities satisfying [ignore] are treated as absent, like [WorldView.isColumnFree] does (crystals we
     * attacked are gone on the server) - with one exception, see [obstructs].
     */
    private fun isUnobstructed(block: BlockPos): Boolean {
        val shape = OBSIDIAN_STATE.getCollisionShape(world, block, placementContext)
        if (shape.isEmpty) return true
        val moved = shape.move(block)
        for (entity in world.getEntities(null, moved.bounds())) {
            if (obstructs(entity, moved)) return false
        }
        return true
    }

    /**
     * An end crystal ALWAYS blocks the obsidian, even one this tick is about to detonate ([ignore] would otherwise
     * hide it, `EndCrystal.blocksBuilding = true`, `EndCrystal.java:32`). Server-side such a placement does succeed -
     * the attack precedes the use and the explosion runs first - but the obsidian would land in the very cell the
     * crystal stood in, i.e. directly on top of the block the aura is cycling on. `WorldView.isPlaceable` needs the
     * block above the obsidian to be AIR, so that turns a working spot into a dead one and walks the base upwards a
     * block per crystal. Everything else follows the caller's filter.
     */
    private fun obstructs(entity: Entity, shape: VoxelShape): Boolean {
        if (entity.isRemoved || !entity.blocksBuilding) return false
        if (entity !is EndCrystal && ignore(entity)) return false
        return Shapes.joinIsNotEmpty(shape, Shapes.create(entity.boundingBox), BooleanOp.AND)
    }

    /**
     * Drains [heap] in descending bound, admitting and scoring each cell with [admit] and keeping candidates at or
     * above [floor], until the next bound is below the BEST effective damage found so far: the bound is an upper
     * bound, so no later cell can reach or tie it. The test is non-strict, keeping exact ties alive for the [BY_RANK]
     * tie-breaks — the cutoff logic of `ExplosionRanker.refine` (PlaceSearch.kt:360-375) verbatim.
     *
     * Result: ordered by [BY_RANK] and cut to the [LOOK_CANDIDATES] cells that get the look point search, chosen
     * after the refinement, from the cells whose damage was actually evaluated. That group can hold fewer than
     * [LOOK_CANDIDATES] cells — a cell whose bound already lost to the best effective damage is never evaluated and
     * can therefore no longer serve as the fallback of a cell the look point search fails on, which is the one
     * behavioural difference to the old cutoff (the best plan itself is unaffected: the bound is an upper bound).
     */
    private fun refine(heap: PriorityQueue<Cell>, floor: Float): List<ScoredCell> {
        val scored = ArrayList<ScoredCell>()
        var bestEffective = 0f
        var next = heap.poll()
        while (next != null && next.bound >= bestEffective) {
            val entry = admit(next)
            if (entry != null && entry.candidate.damage.effective >= floor) {
                scored += entry
                bestEffective = maxOf(bestEffective, entry.candidate.damage.effective)
            }
            next = heap.poll()
        }
        scored.sortWith(BY_RANK)
        return if (scored.size > LOOK_CANDIDATES) scored.subList(0, LOOK_CANDIDATES) else scored
    }

    private fun score(pos: BlockPos): PlaceCandidate? =
        placeSearch.evaluate(pos, requirePlaceable = false) { source ->
            OverrideShapeSource(base = source, x = pos.x, y = pos.y, z = pos.z, shape = Shapes.block())
        }

    /**
     * The aim for one scored cell, over BOTH placement forms: the neighbours the cell reported (relative form,
     * `Direction` order) and, when the cell itself is clickable, the replace-in-place form. Whichever is reachable
     * wins; when both are, the one whose look point needs the smaller turn does ([LookPoint.angle] is the angle to
     * the rotation already on the wire, so that is the cheaper tick and the one `DuplicateRotPlace` prefers).
     */
    private fun aim(entry: ScoredCell): BasePlan? {
        val block = entry.cell.pos
        val relative = Direction.entries.firstNotNullOfOrNull { dir ->
            if (entry.hasFace(dir)) lookThrough(block, dir, entry.candidate) else null
        }
        val self = if (entry.selfClickable) lookSelf(block, entry.candidate) else null
        return when {
            relative == null -> self
            self == null -> relative
            self.look.angle < relative.look.angle -> self
            else -> relative
        }
    }

    /** `LookPoints.forFace` on the face of `block.relative(dir)` that points back at [block]. */
    private fun lookThrough(block: BlockPos, dir: Direction, candidate: PlaceCandidate): BasePlan? {
        val clickPos = block.relative(dir)
        val face = dir.opposite
        val look = LookPoints.forFace(
            eye = view.eye,
            wire = wire,
            pos = clickPos,
            face = face,
            range = settings.range.toDouble(),
            ignore = ignore,
            slackDeg = slackDeg,
            budget = LOOK_BUDGET,
            throughBlocks = throughBlocks,
        ) ?: return null
        return BasePlan(block = block, clickPos = clickPos, face = face, look = look, candidate = candidate)
    }

    /**
     * The replace-in-place aim: `LookPoints.forPlacement` on [block] ITSELF, which accepts ANY visible face -
     * `replaceClicked` makes `BlockPlaceContext` ignore the hit direction (`BlockPlaceContext.java:31-37, 54-61`),
     * so unlike [lookThrough] there is no face to constrain and `forFace` would be the wrong search.
     *
     * The candidates are sampled on `RayTests.pickBox(block)`, the OUTLINE bounds, and not on `AABB(block)`: the
     * blocks this form exists for do not fill their cell, so most of `AABB(block)` names rays that never terminate
     * on it. A null box means the shape is empty and nothing can be picked - [isSelfClickable] already refused
     * that, so this is belt and braces.
     */
    private fun lookSelf(block: BlockPos, candidate: PlaceCandidate): BasePlan? {
        val look = LookPoints.forPlacement(
            eye = view.eye,
            wire = wire,
            pos = block,
            placeRange = settings.range.toDouble(),
            attackCrystal = null,
            breakRange = settings.range.toDouble(),
            ignore = ignore,
            slackDeg = slackDeg,
            budget = LOOK_BUDGET,
            throughBlocks = throughBlocks,
            sampleBox = RayTests.pickBox(block) ?: return null,
        ) ?: return null
        return BasePlan(block = block, clickPos = block, face = null, look = look, candidate = candidate)
    }

    /** A cell that passed the geometry, state and bound checks of [collect]; the heap is ordered by [bound]. */
    private class Cell(val pos: BlockPos, val distSq: Double, val bound: Float)

    /**
     * A [Cell] that passed [admit]. [faces] is the bit set of [clickFaces] (the relative form), [selfClickable]
     * whether the cell itself may be clicked (the replace-in-place form) and [candidate] the placement it
     * justifies. At least one of the two forms is set, or [admit] would have refused the cell.
     */
    private class ScoredCell(
        val cell: Cell,
        private val faces: Int,
        val selfClickable: Boolean,
        val candidate: PlaceCandidate,
    ) {

        fun hasFace(dir: Direction): Boolean = (faces and (1 shl dir.ordinal)) != 0
    }

    private companion object {

        const val PERCENT = 100f

        /** Cells that get the look point search, best damage first. */
        const val LOOK_CANDIDATES = 4

        /** Ray budget of one `LookPoints.forFace` call. */
        const val LOOK_BUDGET = 32

        /** `new ItemStack(Items.OBSIDIAN)` (`ItemStack.java:245`, `Items.java:415`): the item in hand. */
        val OBSIDIAN_STACK: ItemStack by lazy { ItemStack(Items.OBSIDIAN) }

        /** The placement state of obsidian (`Block.java:457-459`). */
        val OBSIDIAN_STATE: BlockState by lazy { Blocks.OBSIDIAN.defaultBlockState() }

        val BY_BOUND: Comparator<Cell> = compareByDescending { it.bound }

        val BY_RANK: Comparator<ScoredCell> = compareByDescending<ScoredCell> { it.candidate.damage.effective }
            .thenBy { it.candidate.selfDamage.effective }
            .thenBy { it.cell.distSq }
    }
}
