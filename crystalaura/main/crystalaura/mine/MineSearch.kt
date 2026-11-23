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

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.BasePlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.MineSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoints
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base.isReplaceInPlace
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.MultiOverrideShapeSource
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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import java.util.PriorityQueue
import kotlin.math.sqrt

/** [clearMask]: nothing has to be broken — the cell is already a base cell, so it is `BasePlaceSearch`'s business. */
internal const val CLEAR_NONE = 0

/** [clearMask]: the base cell itself must be broken before the obsidian can replace it. */
internal const val CLEAR_BASE = 1

/** [clearMask]: the cell above the base must be broken, because the crystal needs TRUE AIR there. */
internal const val CLEAR_ABOVE = 2

/** [clearMask]: no sequence of breaks turns this cell into a base — undiggable, a fluid, or a falling column. */
internal const val CLEAR_IMPOSSIBLE = -1

/**
 * Finds the obsidian base that becomes available after breaking up to `settings.maxBlocks` cells, and only when it
 * is worth more than everything available right now. A sibling of `BasePlaceSearch`, one instance per tick (it holds
 * the tick's [WorldView]); main thread only.
 *
 * ### Which cells have to go
 * `EndCrystalItem.useOn` (`net/minecraft/world/item/EndCrystalItem.java:22-57`) has exactly three conditions: the
 * clicked block is `Blocks.OBSIDIAN` or `Blocks.BEDROCK` (`:27`), `level.isEmptyBlock(pos.above())` — and
 * `LevelReader.isEmptyBlock` is `getBlockState(pos).isAir()` (`net/minecraft/world/level/LevelReader.java:85-87`),
 * so ONE cell and only true air (`:30-32`) — and an empty `AABB(x, y, z, x + 1, y + 2, z + 1)` ENTITY volume above
 * (`:34-40`). There is no block test on `pos.above(2)`, none on the horizontal neighbours and none against the
 * crystal's own `sized(2.0F, 2.0F)` hitbox, so a fully enclosed 1x1x1 pocket is a legal crystal cell.
 *
 * With the obsidian placement in front of it, that leaves [clearMask]'s three cases and never more than two cells:
 * the enemy on flat ground (the ground cell is solid, the cell above already air — break 1, the obsidian drops into
 * the hole and the crystal ends at the victim's FOOT level, which is where `ServerExplosion.hurtEntities` measures
 * from, `net/minecraft/world/level/ServerExplosion.java:182-183`), the enclosed case (both solid — break 2) and the
 * ceiling case (the base cell is already air or replaceable, only the cell above is solid — break 1).
 *
 * ### What is refused, and why
 * - **Undiggable.** Hardness `-1` makes `getDestroyProgress` return exactly `0.0F`
 *   (`net/minecraft/world/level/block/state/BlockBehaviour.java:356-364`), so the dig never completes: bedrock
 *   (`Blocks.java:224-232`), barrier (`:3033-3044`), light (`:3045-3055`), moving piston (`:817-823`). Air has
 *   nothing to dig. Grim flags every one of them plus water and lava in one list
 *   (`checks/impl/breaking/AirLiquidBreak.java:59-69`, "the block does not have a hitbox"). See [isDiggable].
 * - **Fluids.** `MultiPlayerGameMode.destroyBlock` writes `fluidState.createLegacyBlock()`
 *   (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:117-147`) and `Level.removeBlock` does the same
 *   server-side (`net/minecraft/world/level/Level.java:271-275`), so breaking a WATERLOGGED block leaves water, not
 *   air. Water is `canBeReplaced() == true` (`BlockBehaviour.java:824-826`) but `isAir() == false` (`:581-583`), so
 *   the cell fails `isEmptyBlock` and the whole dig was wasted. [isDiggable] therefore refuses any cell whose
 *   `getFluidState()` (`BlockBehaviour.java:859-861`) is non-empty — which is also the water / lava / bubble-column
 *   half of Grim's list, since only an empty fluid legacy-blocks to air (`EmptyFluid.java:56-59`).
 * - **Falling blocks.** `FallingBlock.updateShape` schedules a tick `getDelayAfterPlace() = 2` ticks out and
 *   `FallingBlock.tick` spawns the entity as soon as `isFree(level.getBlockState(pos.below()))`
 *   (`net/minecraft/world/level/block/FallingBlock.java:33-65`; `isFree` = air, fire tag, liquid or replaceable).
 *   `tick` is `ServerLevel`-only, so the client never predicts it and the pocket silently refills two server ticks
 *   later. [clearMask] checks the single cell directly above the TOPMOST cleared cell ([fallsWhenUnsupported]) —
 *   walking further up buys nothing, because the boolean "does anything fall in" is decided by that one block, and
 *   a block two cells up is not even neighbour-updated by our break. `suspicious_sand` / `suspicious_gravel` are
 *   `BrushableBlock` (`BrushableBlock.java:28`), not `FallingBlock`, and do NOT fall.
 * - **The block under our feet.** `player.getOnPos()` (`net/minecraft/world/entity/Entity.java:1002-1023`, the
 *   `mainSupportingBlockPos` when there is one) is never cleared.
 * - **No way in for the obsidian.** The obsidian must be placed against a block Grim still believes is solid
 *   (`AirLiquidPlace`, cancel VL 0 — see [BaseClick]): an untouched neighbour in `BasePlace.Range`, or — when the
 *   plan leaves the base cell standing and replaceable — the base cell itself, which vanilla's `replaceClicked`
 *   branch puts the obsidian into. A plan with neither is dropped, not "fixed" later.
 *
 * ### Scoring and the gain rule
 * A survivor is scored with `placeSearch.evaluate(base, requirePlaceable = false, sourceOverride)` and a
 * [MultiOverrideShapeSource] that shows the rays the pocket as it WILL be: `Shapes.block()` at the base (the future
 * obsidian) and `Shapes.empty()` at every cell we are going to remove. The result must beat the floor
 * `bestExisting.damage.effective * (1 + settings.minGain / 100)`, the shape `BasePlaceSearch.best` uses — digging
 * costs whole ticks in which the aura can neither attack nor place (a tick carrying a dig packet carries nothing
 * else, `PacketOrderI.java:98-123`, `MultiActionsF.java:79-91`), so a mined plan must never displace something we
 * can do now.
 *
 * `PlaceSearch.evaluate` also enforces `BreakSettings.Range` on the crystal it would create, so a mined base whose
 * crystal we could never detonate is rejected for free; nothing here duplicates or bypasses that.
 *
 * ### Cost order
 * Cells of the cube of radius [cellReach] around the eye go through [collect], cheapest first, and nothing is
 * allocated for a cell that fails one of these:
 * - box distance to the eye ([blockDistanceSq]) below [cellReach];
 * - [reachesAnyTarget], the `dist > 1.0` cut-off of `ServerExplosion.hurtEntities` (`:182-183`) for at least one
 *   target — one squared distance and one `sqrt` per target, no allocation;
 * - [clearMask]: two `getBlockState` calls and the hazard tests, allocation-free through the shared scratch
 *   position; a mask of [CLEAR_NONE] (already a base, `BasePlaceSearch`'s job) or [CLEAR_IMPOSSIBLE] is out, and so
 *   is a mask with more cells than `settings.maxBlocks`;
 * - the damage bound ([PlaceSearch.upperBound], pure arithmetic; `0` or below the MinGain floor → out).
 *
 * Survivors are queued by bound and drained in descending bound by [refine], which stops as soon as the next bound
 * is below the BEST effective damage found so far — the bound is an upper bound, so no later cell can reach or tie
 * it. The test is non-strict, keeping exact ties alive for the [BY_RANK] tie-breaks; this is the cutoff of
 * `ExplosionRanker.refine` (PlaceSearch.kt:360-375) and of `BasePlaceSearch.refine` verbatim. Only a drained cell
 * pays [admit]: the click neighbour scan (up to 6 `getBlockState` plus a `BlockPlaceContext` each) and then the
 * rays of [score].
 *
 * ### Per-tick worst case
 * The cube is [cellReach] = `min(Place.Range, max(Mine.Range, BasePlace.Range) + 1)`, at most 11 cells per axis
 * with the defaults. Every cell pays one box distance, then — while any target is in range — one `sqrt` per target,
 * then at most two `getBlockState` plus one `BlockPlaceContext`, then the bound arithmetic. Unlike
 * `BasePlaceSearch`, whose `isBaseCell` prunes to the handful of existing obsidian cells, almost every solid cell
 * near the victim survives [clearMask], so the heap can hold hundreds of entries; what bounds the expensive part is
 * the [refine] cutoff, and since the heap is drained in descending bound the FIRST poll is usually admissible and
 * immediately raises the cutoff to the true optimum. The degenerate case — nothing admissible anywhere — drains the
 * whole heap, exactly as `BasePlaceSearch` does.
 *
 * ### Deliberate deviations
 * - Only the FIRST cell gets a look-point search ([aimable]), not the whole plan. A dig runs over many ticks and
 *   re-aims every one of them, so the aim of the later cells belongs to the job — and it could not be decided here
 *   anyway: the second cell of a two-block pocket is the one UNDER the first, hidden behind it until the first is
 *   gone, so testing it now would reject every ordinary underground plan. The first cell is different: it is dug
 *   immediately, and a plan whose first cell no rotation can reach is pure loss. Digging owns the whole tick — no
 *   attack, no placement (`PacketOrderI`) — so a job that locks onto an unreachable cell does not merely fail, it
 *   starves the aura for as long as it keeps trying, which is exactly what "it stopped placing crystals" looks
 *   like from the outside. `MineJob` re-traces the ray before every packet, so nothing illegal was ever sent; the
 *   cost was in ticks, and ticks are the scarce thing here.
 * - No entity-obstruction test for the future obsidian (`BasePlaceSearch.isUnobstructed`). It would be a snapshot
 *   of where entities stand now, and the placement happens ticks later; `BasePlaceSearch` re-checks it at the
 *   moment it actually places.
 * - No dig-duration estimate. `Mine.MaxTicks` is enforced by the job, which knows the tool it will actually hold.
 */
@Suppress("TooManyFunctions")
class MineSearch(
    private val view: WorldView,
    private val placeSearch: PlaceSearch,
    private val settings: MineSettings,
    private val baseSettings: BasePlaceSettings,
    private val wire: Rotation,
    private val rayIgnore: (Entity) -> Boolean,
    private val slackDeg: Float,
    private val throughBlocks: Boolean,
) {

    /** `player.isSecondaryUseActive()` (`net/minecraft/world/entity/player/Player.java:288-290`), read once. */
    private val sneaking = player.isSecondaryUseActive

    /** `CollisionContext.of(player)` (`ClipContext.java:28-30`): the context the PICK resolves outlines with. */
    private val pickContext: CollisionContext = CollisionContext.of(player)

    /** `Entity.getOnPos()` (`Entity.java:1002-1023`): the block we stand on, which no plan may take away. */
    private val standing: BlockPos = player.onPos

    /** `Mine.Range` squared: the eye-to-`AABB(pos)` bound every cell we intend to break must satisfy. */
    private val digRangeSq: Double = settings.range.toDouble() * settings.range

    /** `BasePlace.Range` squared: the bound the neighbour clicked to place the obsidian must satisfy. */
    private val clickRangeSq: Double = baseSettings.range.toDouble() * baseSettings.range

    /**
     * Cube radius of [collect]: `min(PlaceSearch.range, max(Mine.Range, BasePlace.Range) + 1)`.
     *
     * The `+ 1` half is a superset bound and not an exact one, because which of the two ranges binds depends on the
     * plan: a base cell we break ourselves is bounded by `Mine.Range` directly, a base whose CEILING we break sits
     * up to one block beyond `Mine.Range`, and a base we place obsidian into sits up to one block beyond
     * `BasePlace.Range` (the box distance is 1-Lipschitz in the cell offset, so a neighbour within `r` implies the
     * cell within `r + 1`). The [PlaceSearch.range] half is exact: [PlaceSearch.evaluate] rejects a cell beyond its
     * own range outright, so no farther cell can ever score.
     */
    private val cellReach: Float = minOf(placeSearch.range, maxOf(settings.range, baseSettings.range) + 1f)

    private val cellReachSq: Double = cellReach.toDouble() * cellReach

    /** Scratch position of [clearMask]; valid only inside one [maskFor] call. */
    private val scratch = BlockPos.MutableBlockPos()

    /** Scratch position of [clickFor]. */
    private val neighbour = BlockPos.MutableBlockPos()

    /**
     * The best mined base, or null.
     *
     * [bestExisting] is the best placement available WITHOUT digging — the head of `PlaceSearch.candidates()`, or,
     * when `BasePlace` is on and found a plan this tick, whichever of that plan's candidate and the direct one is
     * better. Passing it is what makes `Mine.MinGain` mean "better than anything we could do instead"; passing null
     * says nothing at all is available, and then any accepted plan wins.
     */
    fun best(bestExisting: PlaceCandidate?): MineTarget? {
        val floor = if (bestExisting == null) {
            0f
        } else {
            bestExisting.damage.effective * (1f + settings.minGain / PERCENT)
        }
        return refine(collect(floor), floor).firstOrNull()?.target
    }

    /** The cheap stage: geometry, then the block states, then the bound. Everything expensive waits for [refine]. */
    private fun collect(floor: Float): PriorityQueue<Cell> {
        val heap = PriorityQueue<Cell>(BY_BOUND)
        val eye = view.eye
        for (cell in eye.searchBlocksInCuboid(cellReach)) {
            val distSq = blockDistanceSq(cell.x, cell.y, cell.z, eye)
            if (distSq >= cellReachSq || !reachesAnyTarget(cell)) continue
            val mask = maskFor(cell)
            if (mask <= CLEAR_NONE || Integer.bitCount(mask) > settings.maxBlocks) continue
            val bound = placeSearch.upperBound(cell)
            if (bound > 0f && bound >= floor) heap.add(Cell(cell.immutable(), distSq, bound, mask))
        }
        return heap
    }

    /**
     * `!(dist > 1.0)` of `ServerExplosion.hurtEntities` (`ServerExplosion.java:182-183`) for at least one target,
     * the pre-cut of `BasePlaceSearch.reachesAnyTarget` verbatim: a crystal standing on [cell] explodes at
     * [ExplosionMath.crystalCenter], and a victim whose feet are farther than `radius * 2 = 12` from it is skipped
     * by vanilla whatever the exposure, so such a cell can never score.
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

    /** [clearMask] against the live client level, with this search's replaceability and clearing rules. */
    private fun maskFor(cell: BlockPos): Int = clearMask(
        level = world,
        base = cell,
        scratch = scratch,
        replaceable = ::isReplaceable,
        clearable = ::isClearable,
    )

    /** The click neighbour and then the rays; the finished plan or null. */
    /**
     * Whether some rotation reaches [cell] at all: any face the eye is outside of for which `LookPoints.forFace`
     * finds a point whose pick ray really lands on that block, exactly the test `MineJob` will re-run before every
     * dig packet (`aim/LookPoints.kt`, `aim/RayTests.kt`).
     *
     * A face the eye is not outside of costs nothing — `forFace` rejects it before tracing (`isOutside`) — so a
     * fully buried cell is refused after six trivial checks, and a visible one usually hits on its first sampled
     * point. That is what keeps this affordable in the degenerate case the class KDoc describes, where the refine
     * cutoff drains the whole heap: the gate sits before [score], so a cell nobody can look at never pays for the
     * 90 exposure rays a scoring would cost.
     */
    private fun aimable(cell: BlockPos): Boolean = Direction.entries.any { face ->
        LookPoints.forFace(
            eye = view.eye,
            wire = wire,
            pos = cell,
            face = face,
            range = settings.range.toDouble(),
            ignore = rayIgnore,
            slackDeg = slackDeg,
            budget = LOOK_BUDGET,
            throughBlocks = throughBlocks,
        ) != null
    }

    private fun admit(cell: Cell): MineTarget? {
        val click = if (isCrystalBase(view.state(cell.pos))) null else clickFor(cell.pos, cell.mask) ?: return null
        val cells = cellsOf(cell.pos, cell.mask)
        if (!aimable(cells.first())) return null
        val candidate = score(cell.pos, cells) ?: return null
        return MineTarget(base = cell.pos, cells = cells, click = click, candidate = candidate)
    }

    /**
     * Drains [heap] in descending bound, admitting and scoring each cell and keeping plans at or above [floor],
     * until the next bound is below the BEST effective damage found so far (see the class KDoc). The survivors are
     * returned in [BY_RANK] order.
     */
    private fun refine(heap: PriorityQueue<Cell>, floor: Float): List<Scored> {
        val scored = ArrayList<Scored>()
        var bestEffective = 0f
        var next = heap.poll()
        while (next != null && next.bound >= bestEffective) {
            val target = admit(next)
            if (target != null && target.candidate.damage.effective >= floor) {
                scored += Scored(next, target)
                bestEffective = maxOf(bestEffective, target.candidate.damage.effective)
            }
            next = heap.poll()
        }
        scored.sortWith(BY_RANK)
        return scored
    }

    /**
     * The cells of [mask] in the order they must be broken: TOP-DOWN, so that the block above a cell is already
     * gone when we aim at it and so that the falling-block test of [clearMask] stays valid throughout the dig.
     */
    private fun cellsOf(base: BlockPos, mask: Int): List<BlockPos> {
        val cells = ArrayList<BlockPos>(2)
        if ((mask and CLEAR_ABOVE) != 0) cells += base.above()
        if ((mask and CLEAR_BASE) != 0) cells += base
        return cells
    }

    /**
     * [PlaceSearch.evaluate] with `requirePlaceable = false` (the base does not exist yet) and the pocket as it
     * will be: `Shapes.block()` at [base] — the future obsidian, `Shapes.java:40-42` — and `Shapes.empty()`
     * (`:36-38`) at every cell of [cells]. [base] is written FIRST because a plan may both break its base cell and
     * put the obsidian there, and [MultiOverrideShapeSource] lets the first match win.
     */
    private fun score(base: BlockPos, cells: List<BlockPos>): PlaceCandidate? {
        val keys = LongArray(cells.size + 1)
        keys[0] = BlockPos.asLong(base.x, base.y, base.z)
        for (index in cells.indices) {
            val cell = cells[index]
            keys[index + 1] = BlockPos.asLong(cell.x, cell.y, cell.z)
        }
        val shapes = Array(keys.size) { if (it == 0) Shapes.block() else Shapes.empty() }
        return placeSearch.evaluate(base, requirePlaceable = false) { source ->
            MultiOverrideShapeSource(base = source, cells = keys, shapes = shapes)
        }
    }

    /**
     * How the obsidian gets into [base], in vanilla's two forms and in that order — a PROOF of feasibility, never
     * a click this module sends (see [BaseClick]).
     *
     * The relative form first: the first neighbour of [base] the obsidian can be clicked against that the plan does
     * NOT break — the `AirLiquidPlace` rule of [BaseClick]. Only `Direction.UP` can name a cell of the plan, [base]
     * not being its own neighbour; when the plan leaves that cell alone it is air and [isClickable] refuses it
     * anyway.
     *
     * Then the replace-in-place form: [base] itself, when the plan does not clear it (so it is still standing when
     * the obsidian goes in, and `clearMask` therefore already found it replaceable) and it can be clicked. Without
     * it a base cell whose every neighbour is replaceable — a snow layer or a fire on open ground — would be judged
     * unreachable and the whole dig plan dropped, although a player places obsidian into exactly such a cell by
     * aiming straight at it.
     */
    private fun clickFor(base: BlockPos, mask: Int): BaseClick? {
        for (dir in Direction.entries) {
            if (dir == Direction.UP && (mask and CLEAR_ABOVE) != 0) continue
            neighbour.setWithOffset(base, dir)
            if (isClickable(neighbour)) return BaseClick(pos = neighbour.immutable(), face = dir.opposite)
        }
        if ((mask and CLEAR_BASE) != 0 || !isSelfClickable(base)) return null
        return BaseClick(pos = base, face = null)
    }

    /**
     * `BasePlaceSearch.isSelfClickable` for the mined variant: inside `BasePlace.Range` (the click lands on this
     * cell) and [isReplaceInPlace], whose replaceability term is the same [isReplaceable] that [clearMask] read.
     */
    private fun isSelfClickable(pos: BlockPos): Boolean {
        if (blockDistanceSq(pos.x, pos.y, pos.z, view.eye) >= clickRangeSq) return false

        val state = view.state(pos)
        return isReplaceInPlace(world, pos, state, isReplaceable(pos, state), pickContext, sneaking)
    }

    /**
     * A neighbour within `BasePlace.Range` (box distance, the eye-to-hit-point lower bound) that the obsidian will
     * not replace (`BlockPlaceContext.java:35-36`) and whose use will not consume the click
     * (`ServerPlayerGameMode.java:358-371`, mirrored client-side in `MultiPlayerGameMode.java:339-350`) —
     * `BasePlaceSearch.isClickable` verbatim, on `BasePlace.Range` because that is the click this enables.
     */
    private fun isClickable(pos: BlockPos): Boolean {
        if (blockDistanceSq(pos.x, pos.y, pos.z, view.eye) >= clickRangeSq) return false
        val state = view.state(pos)
        return !isReplaceable(pos, state) && (!state.isInteractable || sneaking)
    }

    /**
     * `BlockPlaceContext.canPlace()` for obsidian at [pos] (`BlockPlaceContext.java:36, 59-61`) through
     * `canBeReplacedWith`; air (`replaceable()`, `Blocks.java:58`, and never the obsidian item) is answered without
     * building the context. `BasePlaceSearch.isReplaceable` with the arguments in the order [clearMask] wants.
     */
    private fun isReplaceable(pos: BlockPos, state: BlockState): Boolean =
        state.isAir || state.canBeReplacedWith(pos, OBSIDIAN_STACK)

    /** Whether we may break the cell [pos]: inside `Mine.Range`, not the block we stand on, and [isDiggable]. */
    private fun isClearable(pos: BlockPos, state: BlockState): Boolean {
        if (blockDistanceSq(pos.x, pos.y, pos.z, view.eye) >= digRangeSq) return false
        if (pos.x == standing.x && pos.y == standing.y && pos.z == standing.z) return false
        return isDiggable(world, pos, state)
    }

    /** A cell that passed the geometry, state and bound checks of [collect]; the heap is ordered by [bound]. */
    private class Cell(val pos: BlockPos, val distSq: Double, val bound: Float, val mask: Int)

    /** A [Cell] that passed [admit], with the plan it produced. */
    private class Scored(val cell: Cell, val target: MineTarget)

    private companion object {

        const val PERCENT = 100f

        /** Rays one `LookPoints.forFace` call may trace in [MineSearch.aimable]. */
        const val LOOK_BUDGET = 16

        /** `new ItemStack(Items.OBSIDIAN)` (`ItemStack.java:245`, `Items.java:415`): the item in hand. */
        val OBSIDIAN_STACK: ItemStack by lazy { ItemStack(Items.OBSIDIAN) }

        val BY_BOUND: Comparator<Cell> = compareByDescending { it.bound }

        /**
         * `BasePlaceSearch.BY_RANK` with one term added: at equal damage the plan that breaks FEWER cells wins,
         * because every extra cell is another dig plus the ~300 ms `FINISHED_DIGGING` to `START_DIGGING` pacing
         * Grim's `FastBreak` (`checks/impl/breaking/FastBreak.java:81-94`) requires, spent unable to act.
         */
        val BY_RANK: Comparator<Scored> = compareByDescending<Scored> { it.target.candidate.damage.effective }
            .thenBy { it.target.candidate.selfDamage.effective }
            .thenBy { it.target.cells.size }
            .thenBy { it.cell.distSq }
    }
}

/**
 * Which cells have to be broken to turn [base] into a cell a crystal can stand on: a bit set of [CLEAR_BASE] and
 * [CLEAR_ABOVE], [CLEAR_NONE] when nothing has to go, or [CLEAR_IMPOSSIBLE].
 *
 * Pure, and the reason the rules of [MineSearch] are testable: everything it reads comes from [level] and the two
 * predicates. [replaceable] answers `BlockPlaceContext.canPlace()` for the obsidian at a cell (the live search
 * passes a `canBeReplacedWith` with the real placement context), [clearable] answers "may we break this" (range,
 * the block under our feet, [isDiggable]). [scratch] is written twice and holds nothing afterwards.
 *
 * The two cells follow `EndCrystalItem.useOn` (`net/minecraft/world/item/EndCrystalItem.java:22-57`):
 * `base.above()` must end as TRUE AIR (`:30-32`), [base] itself must end as obsidian or bedrock (`:27`), which it
 * does either by already being one or by taking the obsidian we place into it. The final test is the falling-block
 * hazard of `FallingBlock.tick` (`net/minecraft/world/level/block/FallingBlock.java:48-54`) on the single cell
 * above the topmost cell we clear.
 */
internal fun clearMask(
    level: BlockGetter,
    base: BlockPos,
    scratch: BlockPos.MutableBlockPos,
    replaceable: (BlockPos, BlockState) -> Boolean,
    clearable: (BlockPos, BlockState) -> Boolean,
): Int {
    scratch.set(base.x, base.y + 1, base.z)
    val aboveState = level.getBlockState(scratch)
    var mask = CLEAR_NONE
    if (!aboveState.isAir) {
        if (!clearable(scratch, aboveState)) return CLEAR_IMPOSSIBLE
        mask = CLEAR_ABOVE
    }
    val baseState = level.getBlockState(base)
    if (!isCrystalBase(baseState) && !replaceable(base, baseState)) {
        if (!clearable(base, baseState)) return CLEAR_IMPOSSIBLE
        mask = mask or CLEAR_BASE
    }
    if (mask == CLEAR_NONE) return CLEAR_NONE
    scratch.set(base.x, base.y + if ((mask and CLEAR_ABOVE) != 0) 2 else 1, base.z)
    return if (fallsWhenUnsupported(level.getBlockState(scratch))) CLEAR_IMPOSSIBLE else mask
}

/** `blockState.is(Blocks.OBSIDIAN) || blockState.is(Blocks.BEDROCK)` (`EndCrystalItem.java:27`). */
internal fun isCrystalBase(state: BlockState): Boolean = state.`is`(Blocks.OBSIDIAN) || state.`is`(Blocks.BEDROCK)

/**
 * Whether the block at [pos] can be mined away into AIR.
 *
 * Three refusals, all of them load-bearing:
 * - air — nothing to dig, and Grim's `AirLiquidBreak` flags a dig packet naming an air block
 *   (`checks/impl/breaking/AirLiquidBreak.java:59-69`);
 * - a non-empty `getFluidState()` (`BlockBehaviour.java:859-861`) — the break would leave
 *   `fluidState.createLegacyBlock()`, i.e. water instead of air (`MultiPlayerGameMode.java:117-147`,
 *   `Level.java:271-275`), and only `EmptyFluid` legacy-blocks to air (`EmptyFluid.java:56-59`). This is the
 *   waterlogging hazard and, at the same time, the water / lava / bubble-column half of Grim's list, since those
 *   blocks carry their own fluid;
 * - hardness `-1` (`BlockStateBase.getDestroySpeed`, `BlockBehaviour.java:638-640`) — `getDestroyProgress` returns
 *   exactly `0.0F` and the dig never completes (`:356-364`): bedrock, barrier, light, moving piston. Grim treats a
 *   `FINISHED_DIGGING` on one of them as invalid (`AirLiquidBreak.java:67`).
 *
 * The wrong tool is deliberately NOT a refusal: it is a 3.33x penalty (the `30` vs `100` divisor of
 * `getDestroyProgress`), which `Mine.MaxTicks` prices instead. Creative mode is not modelled either — the aura
 * exists for survival play, where a hardness `-1` block is simply out of reach of the dig loop.
 */
internal fun isDiggable(level: BlockGetter, pos: BlockPos, state: BlockState): Boolean {
    if (state.isAir || !state.fluidState.isEmpty) return false
    return state.getDestroySpeed(level, pos) >= 0f
}

/**
 * Whether [state] falls once the block below it is gone: `state.getBlock() instanceof FallingBlock`, the class
 * whose `tick` spawns a `FallingBlockEntity` as soon as `isFree(level.getBlockState(pos.below()))`
 * (`net/minecraft/world/level/block/FallingBlock.java:48-54`, scheduled two ticks out by `updateShape`, `:33-46`).
 *
 * In 26.1.2 that is `AnvilBlock`, `ColoredFallingBlock` (gravel), `SandBlock` (sand and red sand), all sixteen
 * `ConcretePowderBlock`s and `DragonEggBlock`. `suspicious_sand` and `suspicious_gravel` are `BrushableBlock`
 * (`BrushableBlock.java:28`: `extends BaseEntityBlock implements Fallable`) and do NOT fall.
 */
internal fun fallsWhenUnsupported(state: BlockState): Boolean = state.block is FallingBlock
