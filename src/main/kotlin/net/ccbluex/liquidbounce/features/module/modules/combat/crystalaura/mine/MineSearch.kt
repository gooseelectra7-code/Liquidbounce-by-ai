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
import net.ccbluex.liquidbounce.features.module.modules.world.ModuleSurround
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
 * - **Fluid NEIGHBOURS**, which is a different rule and was missing until 2026-09-20 (reported in game: the
 *   miner digs in water where no crystal can go). A DRY cell next to water passes [isDiggable] and is genuinely
 *   air the instant it breaks - and water, five fluid ticks later. Only the cell that must hold the crystal cares,
 *   so the test lives in [clearMask] rather than in [isDiggable]: see [fluidWouldFlowInto].
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
 * ### Blocks Surround has just placed
 * A plan whose cells include one `ModuleSurround` sent a placement into within the last [SURROUND_WINDOW_MS] is taken
 * only when no other plan is worth the same ([pick], 2026-10-17, the user's ask: "do not pick blocks Surround places
 * when there are equal ones by damage that it did not place, within the last second"). "The same" is target damage
 * ([PlaceCandidate.damage], NOT [rankOf]) at most [SURROUND_MARGIN] HP below - the user's choice over exact equality,
 * which two different cells practically never reach. A Surround plan more than that above every other plan is
 * still dug. Only the cells the plan BREAKS count: a crystal standing on Surround's obsidian takes nothing from it.
 * All cells of one search are judged against ONE instant ([surroundSince]), so two plans digging the same block
 * cannot be classified differently because the second ran out mid-search.
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
 * is below the BEST RANK found so far ([rankOf]: the damage, discounted when the plan still needs obsidian) — rank
 * <= damage <= bound, so no later cell can reach or tie it. (While that best plan digs a block Surround has just
 * placed the stop is lower still, see [refine].) The test is non-strict, keeping exact ties alive for the
 * [BY_RANK] tie-breaks; with no plan that needs obsidian it is the cutoff of `ExplosionRanker.refine`
 * (PlaceSearch.kt:360-375) and of `BasePlaceSearch.refine`. Since the rank can sit below the damage, the cutoff is
 * lower than it used to be and [MAX_REFINED] binds a little more often. Only a drained cell
 * pays [admit]: the click neighbour scan (up to 6 `getBlockState` plus a `BlockPlaceContext` each) and then the
 * rays of [score].
 *
 * ### Per-tick worst case
 * The cube is [cellReach] = `min(Place.Range, max(Mine.Range, BasePlace.Range) + 1)`, at most 11 cells per axis
 * with the defaults. Every cell pays one box distance, then — while any target is in range — one `sqrt` per target,
 * then at most two `getBlockState` plus one `BlockPlaceContext`, then the bound arithmetic. Almost every solid
 * cell near the victim survives [clearMask], so the heap can hold hundreds of entries; what bounds the expensive
 * part is the [refine] cutoff, and since the heap is drained in descending bound the FIRST poll is usually
 * admissible and immediately raises the cutoff to the true optimum.
 *
 * This paragraph used to add "unlike `BasePlaceSearch`, whose `isBaseCell` prunes to the handful of existing
 * obsidian cells". That was wrong and it mattered, because it was the sentence a reader went to when pricing the
 * other search. `isBaseCell` is "the block above is air AND the cell is replaceable", and `isReplaceable` starts
 * with `state.isAir` - so every air cell with air above qualifies, which in the open is most of the cube. The
 * claim describes `PlaceSearch`, which looks for pedestals to place ON; `BasePlaceSearch` looks for cells to put
 * obsidian INTO. Both heaps are large.
 *
 * The degenerate case - nothing admissible anywhere - used to drain the whole heap in both searches. Fixed on
 * 2026-09-20: the cutoff now starts at the acceptance floor rather than at zero, with [MAX_REFINED] behind it.
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
     * [existingDamage] is what the aura can do WITHOUT digging (the hub's `withoutMining`: the placement ranking's
     * head, a `BasePlace` plan, the crystal about to be hit, our placements in flight, a pocket we just opened).
     * Passing it is what makes `Mine.MinGain` mean "better than anything we could do instead"; 0 says nothing at all
     * is available, and then any accepted plan wins - so 0 must be the truth, not a gap in the reference
     * (2026-10-03: a reference that saw only the direct ranking dropped to 0 for every tick our own crystal was in
     * flight, and AutoMine dug pockets worth no gain at all).
     */
    fun best(existingDamage: Float): MineTarget? {
        val floor = mineGainFloor(existingDamage, settings.minGain)
        return pick(refine(collect(floor), floor))?.target
    }

    /**
     * For the `Debug` line: what [pick] did about a Surround cell on this search - `avoided` (the head digs a block
     * Surround has just placed and another plan was worth the same) or `kept` (no such plan among the ones [refine]
     * drained - [MAX_REFINED] can end the drain before one is reached), with that cell; null when the head digs no
     * such block or nothing was found.
     */
    var surroundNote: String? = null
        private set

    /** `now - SURROUND_WINDOW_MS`, read ONCE: every cell of this search is judged against the same instant. */
    private val surroundSince: Long = System.currentTimeMillis() - SURROUND_WINDOW_MS

    /**
     * The head of [scored] (already in [BY_RANK] order), unless it digs a block Surround has just placed and a plan
     * that does not deals the same damage within [SURROUND_MARGIN] - see the class KDoc and [pickAvoiding]. Among such
     * plans the best in [BY_RANK] order wins.
     */
    private fun pick(scored: List<Scored>): Scored? {
        val picked = pickAvoiding(
            ranked = scored,
            damage = { it.target.candidate.damage.effective },
            avoided = { it.surroundCell != null },
            margin = SURROUND_MARGIN,
        ) ?: return null
        scored.first().surroundCell?.let { cell ->
            surroundNote = (if (picked.surroundCell == null) "avoided " else "kept ") + cell.toShortString()
        }
        return picked
    }

    /** The first of [cells] `ModuleSurround` sent a placement into since [surroundSince], or null. */
    private fun surroundCellOf(cells: List<BlockPos>): BlockPos? =
        cells.firstOrNull { ModuleSurround.placedSince(it, surroundSince) }

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
     * until the next bound is below the BEST rank found so far (see the class KDoc). The survivors are
     * returned in [BY_RANK] order.
     *
     * Two things bound the drain, and the first is what makes the second almost never bind.
     *
     * The cutoff STARTS at [floor], not at zero. `DamageCalculator.upperBound` dominates the effective damage of
     * the same centre (its KDoc proves each stage monotone, with one named data-pack exception), so a cell whose
     * bound is already below the floor can never satisfy `effective >= floor` and can never be accepted. Cutting
     * it changes no result - it was never going to enter `scored`, so it could not have served as a look-point
     * fallback either. Starting at zero meant the whole heap was drained on every tick where nothing cleared the
     * floor, at ~45 exposure rays a cell, which is the COMMON case here rather than a pathological one.
     *
     * [MAX_REFINED] then caps what is left: cells whose bound clears the floor but which are refused by `admit`
     * or score below it after the rays. Found by audit 2026-09-20; `scan/PlaceSearch.refine` had the cap already
     * and this copy did not, and the class KDoc of `mine/MineSearch` had described the defect for both.
     *
     * While the head (the best plan so far) digs a block Surround has just placed, the cutoff is
     * `min(head.rank, head.damage - SURROUND_MARGIN)` (never below the floor), so the plans [pick] may take instead
     * are drained too: such a plan's damage is at least `head.damage - SURROUND_MARGIN` and its bound at least its
     * damage, while a cell below `head.rank` can still not become the head. The cutoff can therefore go DOWN when such
     * a head appears; that drops nothing, because cells are only ever dropped at the loop's exit.
     */
    private fun refine(heap: PriorityQueue<Cell>, floor: Float): List<Scored> {
        val scored = ArrayList<Scored>()
        // The cutoff is the best RANK so far (and never below the floor every plan has to reach). A later cell can only
        // win with a higher rank, its rank is at most its damage and its damage at most its bound - so a bound below
        // the cutoff still ends the drain exactly, and with no plan that needs obsidian it is the old damage cutoff.
        // A head that digs a fresh Surround block lowers it to keep the plans `pick` may prefer (see above).
        var cutoff = floor
        var head: Scored? = null
        var drained = 0
        var next = heap.poll()
        while (next != null && next.bound >= cutoff && drained < MAX_REFINED) {
            drained++
            val target = admit(next)
            if (target != null && target.candidate.damage.effective >= floor) {
                val entry = Scored(next, target, rankOf(target), surroundCellOf(target.cells))
                scored += entry
                if (head == null || BY_RANK.compare(entry, head) < 0) head = entry
                val headCutoff = if (head.surroundCell == null) {
                    head.rank
                } else {
                    minOf(head.rank, head.target.candidate.damage.effective - SURROUND_MARGIN)
                }
                cutoff = maxOf(floor, headCutoff)
            }
            next = heap.poll()
        }
        scored.sortWith(BY_RANK)
        return scored
    }

    /**
     * What a plan is worth in the ranking: its damage, discounted by `BasePlace.MinGain` when the obsidian still has to
     * go in ([MineTarget.click] non-null; null means the base already IS obsidian or bedrock, see [admit]).
     *
     * A pocket on standing obsidian is the dig and nothing else; any other pocket is the dig PLUS a base placement -
     * the same extra step `BasePlaceSearch` prices against a direct placement with exactly this setting (a base must
     * beat it by `MinGain` %). Two pockets differ by that step and nothing more, so the one that needs it has to beat
     * the one that does not by the same margin. Before 2026-10-03 the ranking compared raw damage, with "breaks fewer
     * cells" only as a tie-break after self damage: a block sitting on obsidian - one dig, then the crystal straight
     * on - lost to an equal pocket elsewhere that needed the obsidian too (the user's report).
     */
    private fun rankOf(target: MineTarget): Float {
        val damage = target.candidate.damage.effective
        return if (target.click == null) damage else damage / (1f + baseSettings.minGain / PERCENT_OF_ONE)
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

    /**
     * A [Cell] that passed [admit], with the plan it produced, its [rankOf] and the first of its cells Surround has
     * just placed ([surroundCellOf], null for none).
     */
    private class Scored(val cell: Cell, val target: MineTarget, val rank: Float, val surroundCell: BlockPos?)

    private companion object {

        /** Rays one `LookPoints.forFace` call may trace in [MineSearch.aimable]. */
        const val LOOK_BUDGET = 16

        /**
         * Cells one [refine] may price before it gives up, after the floor cutoff has done its work. A backstop,
         * not the primary bound - see [refine].
         */
        const val MAX_REFINED = 64


        /** `new ItemStack(Items.OBSIDIAN)` (`ItemStack.java:245`, `Items.java:415`): the item in hand. */
        val OBSIDIAN_STACK: ItemStack by lazy { ItemStack(Items.OBSIDIAN) }

        val BY_BOUND: Comparator<Cell> = compareByDescending { it.bound }

        /**
         * By [rankOf] (damage, with the obsidian a pocket still needs priced in), then the plan that needs NO
         * obsidian, then `BasePlaceSearch.BY_RANK`'s self damage, then the plan that breaks FEWER cells - every extra
         * cell is another dig plus the ~300 ms `FINISHED_DIGGING` to `START_DIGGING` pacing Grim's `FastBreak`
         * (`checks/impl/breaking/FastBreak.java:81-94`) requires, spent unable to act - then distance.
         */
        val BY_RANK: Comparator<Scored> = compareByDescending<Scored> { it.rank }
            .thenBy { it.target.click != null }
            .thenBy { it.target.candidate.selfDamage.effective }
            .thenBy { it.target.cells.size }
            .thenBy { it.cell.distSq }

        /** `MinGain` settings are percentages. */
        const val PERCENT_OF_ONE = 100f

        /** How recent a Surround placement must be for [pick] to avoid digging it - "the last second", the user's. */
        const val SURROUND_WINDOW_MS = 1_000L

        /** HP within which [pick] calls two plans equal; the user chose a margin in HP (2026-10-17). */
        const val SURROUND_MARGIN = 0.5f
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
 * does either by already being one or by taking the obsidian we place into it. "Ends as air" is asked of the
 * above-cell as a lasting property, not a momentary one - a cleared cell that water reaches is worth nothing, so
 * [fluidWouldFlowInto] refuses it. The final test is the falling-block hazard of `FallingBlock.tick`
 * (`net/minecraft/world/level/block/FallingBlock.java:48-54`) on the single cell above the topmost cell we clear.
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
        // Two refusals with one verdict: we cannot break it, or breaking it buys nothing because becoming air is
        // not enough for THIS cell - it has to STAY air until the crystal goes in. `clearable` runs first, so the
        // short circuit also keeps it from seeing a `scratch` the neighbour walk has moved.
        if (!clearable(scratch, aboveState) ||
            fluidWouldFlowInto(level, base.x, base.y + 1, base.z, scratch)
        ) {
            return CLEAR_IMPOSSIBLE
        }
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

/**
 * Whether vanilla's fluid spread would fill the cell at ([x], [y], [z]) once we have cleared it to air.
 *
 * [isDiggable] only refuses a block that carries a fluid ITSELF - the waterlogging case, where the break leaves
 * `fluidState.createLegacyBlock()` instead of air. It says nothing about a DRY block standing next to water, and
 * that is the one the crystal aura actually meets: water poured on the ground is the standard answer to a crystal
 * player. Breaking such a cell leaves air for a moment and water a few fluid ticks later
 * (`WaterFluid.getTickDelay` = 5, `WaterFluid.java:113-115`), and `EndCrystalItem.useOn` needs `pos.above()` to be
 * TRUE AIR (`:30-32`). The dig was spent on a base that can never take a crystal, and `MineJob.stillValid` only
 * notices afterwards, when it finds an already-broken cell that is no longer air.
 *
 * The rule is `FlowingFluid.getNewLiquid` (`FlowingFluid.java:164-198`) evaluated for an air cell:
 * - a non-empty fluid of the same type DIRECTLY ABOVE returns `getFlowing(8, true)` unconditionally (`:193-194`),
 *   so any fluid overhead fills us;
 * - otherwise the amount is `max(horizontal neighbour amounts) - getDropOff` (`:196-197`), and water's drop-off is
 *   1 (`WaterFluid.java:108-110`). So a horizontal neighbour of [SPREADING_AMOUNT] or more reaches us and one of
 *   amount 1 does not.
 *
 * Two deliberate simplifications, both of which can only ever refuse MORE cells, never admit a wrong one:
 * `canPassThroughWall` (`FlowingFluid.java:201`) is not modelled, so a fluid blocked by geometry is assumed to
 * reach us; and the drop-off is taken as water's 1 even for lava, whose overworld drop-off is 2. The
 * two-sources-make-a-source branch (`:182-189`) needs no separate term - two sources means a horizontal
 * neighbour of amount 8.
 *
 * Applied ONLY to the cell that must end as air. The base cell below it takes the obsidian, and water there is
 * harmless: it is replaceable, so the placement removes it. And it is deliberately NOT applied to an above-cell
 * that is ALREADY air - a steady-state world would have flooded that cell long ago, so the check there would
 * refuse sound bases over a `canPassThroughWall` we do not model.
 */
@Suppress("LongParameterList")
internal fun fluidWouldFlowInto(
    level: BlockGetter,
    x: Int,
    y: Int,
    z: Int,
    scratch: BlockPos.MutableBlockPos,
): Boolean {
    if (!level.getBlockState(scratch.set(x, y + 1, z)).fluidState.isEmpty) return true

    return level.getBlockState(scratch.set(x - 1, y, z)).fluidState.amount >= SPREADING_AMOUNT ||
        level.getBlockState(scratch.set(x + 1, y, z)).fluidState.amount >= SPREADING_AMOUNT ||
        level.getBlockState(scratch.set(x, y, z - 1)).fluidState.amount >= SPREADING_AMOUNT ||
        level.getBlockState(scratch.set(x, y, z + 1)).fluidState.amount >= SPREADING_AMOUNT
}

/** Smallest horizontal neighbour amount that survives water's drop-off of 1 - see [fluidWouldFlowInto]. */
internal const val SPREADING_AMOUNT = 2

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

/**
 * The head of [ranked] (already in ranking order, best first), unless it is [avoided] and some plan that is not has
 * [damage] at most [margin] below the head's - then the first such plan in [ranked]'s order. The rule of
 * `MineSearch.pick` (blocks Surround has just placed), pure so it can be tested. The head is kept when no plan
 * qualifies, i.e. when it beats every plan that is not avoided by more than [margin].
 */
internal fun <T> pickAvoiding(
    ranked: List<T>,
    damage: (T) -> Float,
    avoided: (T) -> Boolean,
    margin: Float,
): T? {
    val head = ranked.firstOrNull() ?: return null
    if (!avoided(head)) return head

    val minDamage = damage(head) - margin
    return ranked.firstOrNull { !avoided(it) && damage(it) >= minDamage } ?: head
}

/**
 * The damage a dug placement must reach: [existingDamage] raised by [minGainPercent] (`Mine.MinGain`), 0 when nothing
 * exists. The one formula both the search ([MineSearch.best]) and the running jobs (`AutoMineEngine.plan`) use.
 */
internal fun mineGainFloor(existingDamage: Float, minGainPercent: Float): Float =
    if (existingDamage <= 0f) 0f else existingDamage * (1f + minGainPercent / PERCENT_SCALE)

/** `Mine.MinGain` is a percentage. */
private const val PERCENT_SCALE = 100f
