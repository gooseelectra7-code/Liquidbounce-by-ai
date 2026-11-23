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

package net.ccbluex.liquidbounce.utils.navigation.pathing.world

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.material.Fluids
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Navigation classification of a single block, stored as one nibble (0..15).
 *
 * The pathfinder never looks at [BlockState] or [net.minecraft.world.phys.shapes.VoxelShape] during
 * search — every navigation-relevant property is reduced to one of these classes exactly once, when
 * the block enters [NavWorld]. That reduction is what makes the search loop a sequence of array
 * reads instead of palette lookups and shape intersections.
 *
 * Classes describe *navigation semantics*, not raw geometry: a fence is [BLOCKING_TALL] because you
 * cannot step over it, not because of its exact bounding box.
 */
@Suppress("TooManyFunctions")
object NavBlockClass {

    /** No collision at all. Grass, torches, rails, buttons and pressure plates land here too. */
    const val AIR = 0

    /** Water source or flowing water. Swimmable, breaks fall damage. */
    const val WATER = 1

    /** Lava. Passable in the physical sense, lethal in every other sense. */
    const val LAVA = 2

    /** Ladder, vine, scaffolding — passable *and* grants vertical movement. */
    const val CLIMBABLE = 3

    /** Cobweb. Passable but crippling slow; also cancels fall damage. */
    const val COBWEB = 4

    /** Fire, sweet berry bush, wither rose, powder snow. You walk *into* these and get hurt. */
    const val HAZARD = 5

    /** Full 1x1x1 collision cube. The common walkable ground. */
    const val SOLID_FULL = 6

    /** Bottom slab and friends: full XZ footprint, top surface at y+0.5. Steppable without a jump. */
    const val SOLID_TOP_HALF = 7

    /** Carpet, snow layer, lily pad: full XZ footprint, top surface at or below y+0.25. */
    const val CARPET_LIKE = 8

    /** Fence, wall, fence gate. Occupies the cell and is 1.5 blocks tall — cannot be stepped over. */
    const val BLOCKING_TALL = 9

    /** Door or trapdoor. Blocking in its current state but openable, so worth a distinct cost. */
    const val DOOR_LIKE = 10

    /** Soul sand, soul soil, honey, slime, mud. Solid and standable, but slows movement. */
    const val SLOW = 11

    /** Ice, packed ice, blue ice. Solid, standable, faster and slippery. */
    const val FAST = 12

    /**
     * Magma and cactus: solid enough to stand on or be stopped by, and damaging on contact.
     *
     * Kept apart from [HAZARD] because conflating them is a routing bug in both directions — a
     * planner that thinks magma is passable walks through walls of it, and one that thinks cactus
     * is passable plans straight into it.
     */
    const val HAZARD_SOLID = 13

    /** Has collision but matches none of the shapes above (stairs, anvils, chests, ...). */
    const val OTHER_SOLID = 14

    /** Chunk or section is not loaded. Never treated as air — the search stops at the frontier. */
    const val UNKNOWN = 15

    /**
     * Dig bucket meaning "cannot be broken at all" — bedrock, barriers, and anything else with
     * negative hardness.
     *
     * Unbreakability lives here rather than in a nav class of its own: bedrock is an ordinary full
     * cube as far as walking is concerned, and giving it a separate class would have thrown away
     * its shape. "Can I stand on it" and "can I mine it" are independent questions.
     */
    const val DIG_UNBREAKABLE = 15

    /** Highest dig bucket that still represents a breakable block. */
    const val DIG_MAX_BREAKABLE = 14

    // -----------------------------------------------------------------------
    //  Nibble packing — high nibble is the nav class, low nibble the dig bucket
    // -----------------------------------------------------------------------

    /** Packs a nav class and a dig bucket into the single byte stored per block. */
    fun pack(navClass: Int, digBucket: Int): Byte = ((navClass shl 4) or (digBucket and 0xF)).toByte()

    /** Nav class held in the high nibble of a packed block byte. */
    fun navClassOf(packed: Byte): Int = (packed.toInt() shr 4) and 0xF

    /** Dig bucket held in the low nibble of a packed block byte. */
    fun digBucketOf(packed: Byte): Int = packed.toInt() and 0xF

    /** The value stored for blocks in sections we have never received. */
    @JvmField
    val PACKED_UNKNOWN: Byte = pack(UNKNOWN, DIG_UNBREAKABLE)

    /** The value stored for plain air, i.e. the overwhelming majority of a loaded world. */
    @JvmField
    val PACKED_AIR: Byte = pack(AIR, 0)

    // -----------------------------------------------------------------------
    //  Predicates — the vocabulary the planners actually speak
    // -----------------------------------------------------------------------

    /**
     * Can a player body occupy this cell without being pushed out?
     *
     * [UNKNOWN] is deliberately *not* passable: unloaded space must never be mistaken for open air,
     * otherwise the search happily plans routes through terrain it has never seen.
     */
    fun isPassable(navClass: Int): Boolean = when (navClass) {
        AIR, WATER, CLIMBABLE, COBWEB, HAZARD, CARPET_LIKE -> true
        else -> false
    }

    /** Passable *and* harmless. Used when a safe route is preferred over a short one. */
    fun isSafePassable(navClass: Int): Boolean = when (navClass) {
        AIR, WATER, CLIMBABLE, CARPET_LIKE -> true
        else -> false
    }

    /**
     * Does this block support a player standing on top of it?
     *
     * [BLOCKING_TALL] deliberately does not, even though a fence is solid enough to hold weight.
     * Its top is a block and a half up, and a player's jump reaches a block and a quarter — so the
     * only way onto a fence is from something taller, and a lattice that treats every node as one
     * block above its support cannot express that. Calling it standable made the planner lay routes
     * along the tops of fences and walls, which the executor then ground against for the length of
     * a stuck timer before replanning the identical route.
     */
    fun isStandable(navClass: Int): Boolean = when (navClass) {
        SOLID_FULL, SOLID_TOP_HALF, SLOW, FAST, HAZARD_SOLID, OTHER_SOLID -> true
        else -> false
    }

    /** Height of the walking surface above the block's own base, in blocks. */
    fun surfaceHeight(navClass: Int): Double = when (navClass) {
        SOLID_TOP_HALF -> 0.5
        CARPET_LIKE -> 0.0
        BLOCKING_TALL -> 1.5
        else -> 1.0
    }

    /** Water or lava. */
    fun isFluid(navClass: Int): Boolean = navClass == WATER || navClass == LAVA

    /** Would standing in, on, or against this block hurt? */
    fun isDangerous(navClass: Int): Boolean = when (navClass) {
        LAVA, HAZARD, HAZARD_SOLID -> true
        else -> false
    }

    /**
     * Does landing in this block cancel fall damage?
     *
     * Only the classes where every member qualifies. [SLOW] is excluded even though slime and honey
     * belong there, because soul sand and mud share the class and break nothing — and the cost of
     * being wrong here is a lethal drop, not a slower route.
     */
    fun breaksFall(navClass: Int): Boolean = navClass == WATER || navClass == COBWEB

    /** Can the player climb vertically here without jumping or placing? */
    fun isClimbable(navClass: Int): Boolean = navClass == CLIMBABLE

    /** Is this cell known, i.e. backed by a chunk we have actually received? */
    fun isKnown(navClass: Int): Boolean = navClass != UNKNOWN

    /** Readable name, for diagnostics. */
    @Suppress("CyclomaticComplexMethod")
    fun nameOf(navClass: Int): String = when (navClass) {
        AIR -> "air"
        WATER -> "water"
        LAVA -> "lava"
        CLIMBABLE -> "climbable"
        COBWEB -> "cobweb"
        HAZARD -> "hazard"
        SOLID_FULL -> "solid"
        SOLID_TOP_HALF -> "half-slab"
        CARPET_LIKE -> "carpet"
        BLOCKING_TALL -> "fence"
        DOOR_LIKE -> "door"
        SLOW -> "slow"
        FAST -> "ice"
        HAZARD_SOLID -> "solid-hazard"
        OTHER_SOLID -> "other-solid"
        UNKNOWN -> "UNLOADED"
        else -> "?$navClass"
    }

    // -----------------------------------------------------------------------
    //  Classification
    // -----------------------------------------------------------------------

    /**
     * Memoised [classify], keyed by block state.
     *
     * Block states are canonical singletons and a whole world only ever contains a few thousand
     * distinct ones, while a single chunk scan asks about a quarter of a million blocks. Classifying
     * each distinct state once turns tag lookups and shape intersections into a hash hit.
     */
    fun classifyCached(state: BlockState): Byte =
        classificationCache.computeIfAbsent(state, ::classify)

    /** Drops the classification cache. Only useful when tags are reloaded (resource/datapack swap). */
    fun invalidateClassificationCache() {
        classificationCache.clear()
    }

    private val classificationCache = ConcurrentHashMap<BlockState, Byte>()

    /**
     * Reduces a [BlockState] to its packed navigation byte.
     *
     * Runs once per distinct state on the [net.ccbluex.liquidbounce.utils.block.ChunkScanner] worker
     * threads, never during search. Shape queries go against [EmptyBlockGetter] so that no live
     * world access happens off the main thread; blocks whose real shape depends on neighbours
     * (fences, walls, panes) are recognised by tag before we ever look at geometry.
     */
    fun classify(state: BlockState): Byte = pack(classifyNav(state), digBucket(state))

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun classifyNav(state: BlockState): Int {
        if (state.isAir) return AIR

        val fluid = state.fluidState
        // Only a state that is *nothing but* fluid is water or lava to navigation. A waterlogged
        // slab, stair, fence or pane carries a full water fluid state while keeping every bit of
        // its collision, and answering WATER for it made solid geometry passable, unstandable and —
        // worst of the three — fall-breaking: a waterlogged slab at the bottom of a shaft was
        // planned as a free dive, since a landing that "breaks the fall" is exempt from the drop
        // limit and costs no health at all.
        if (!fluid.isEmpty && !hasCollision(state)) {
            // Compared against the fluid instances rather than FluidTags: identity needs no bound
            // tag data, which keeps classification correct before the server sends its tag set.
            return if (fluid.`is`(Fluids.LAVA) || fluid.`is`(Fluids.FLOWING_LAVA)) LAVA else WATER
        }

        // Tag- and identity-based classes first: these must win over geometry, either because the
        // real shape depends on neighbouring blocks or because the navigation meaning is not
        // derivable from the bounding box at all.
        if (isClimbable(state)) return CLIMBABLE
        if (state.`is`(Blocks.COBWEB)) return COBWEB
        if (isSolidHazard(state)) return HAZARD_SOLID
        if (isPassableHazard(state)) return HAZARD

        val block = state.block
        if (block is FenceBlock || block is WallBlock) return BLOCKING_TALL
        // A gate is a fence that opens, and an open one has no collision at all. Matching it with
        // the fences left an open gate as a wall, which seals a fenced enclosure to the planner
        // exactly as a closed door used to seal a building.
        if (block is FenceGateBlock) {
            return if (state.getValueOrElse(BlockStateProperties.OPEN, false)) AIR else BLOCKING_TALL
        }
        if (block is DoorBlock) {
            // An open door is a doorway, not a wall. Classifying every door as blocking regardless
            // of its state seals the player inside any building they happen to be standing in, and
            // the search can only report that by exhausting the entire interior first.
            return if (state.getValueOrElse(BlockStateProperties.OPEN, false)) AIR else DOOR_LIKE
        }
        if (block is TrapDoorBlock) {
            if (state.getValueOrElse(BlockStateProperties.OPEN, false)) return AIR
            // A closed trapdoor is not a closed door. Its bottom half is a three-inch platform laid
            // on the floor, walked over exactly like a carpet; its top half is a lid, walked on from
            // above. Calling both DOOR_LIKE — neither passable nor standable — turned every
            // trapdoor floor into a hole the search could neither cross nor stand on.
            return if (state.getValueOrElse(BlockStateProperties.HALF, Half.TOP) == Half.BOTTOM) {
                CARPET_LIKE
            } else {
                OTHER_SOLID
            }
        }

        if (isSlow(state)) return SLOW
        if (isFast(state)) return FAST

        return classifyGeometry(state)
    }

    /**
     * Ladders, vines, scaffolding and the rest.
     *
     * Recognised by block type first and only then by tag. Tags arrive from the server and are
     * unbound until they do, so leaning on them alone means every block is misclassified during the
     * window right after joining — and misclassified silently, since a tag lookup on unbound data
     * simply answers "no".
     */
    private fun isClimbable(state: BlockState): Boolean {
        val block = state.block
        return block is LadderBlock ||
            block is VineBlock ||
            block is ScaffoldingBlock ||
            state.`is`(BlockTags.CLIMBABLE)
    }

    /** Damaging blocks you stand on or bump into rather than walk through. */
    private fun isSolidHazard(state: BlockState): Boolean =
        state.`is`(Blocks.MAGMA_BLOCK) || state.`is`(Blocks.CACTUS)

    /** Damaging blocks with no meaningful collision — you walk in and take the hit. */
    private fun isPassableHazard(state: BlockState): Boolean =
        state.`is`(Blocks.SWEET_BERRY_BUSH) ||
            state.`is`(Blocks.WITHER_ROSE) ||
            state.`is`(Blocks.POWDER_SNOW) ||
            // Covers both fire and soul fire without depending on BlockTags.FIRE being bound.
            state.block is BaseFireBlock

    private fun isSlow(state: BlockState): Boolean =
        state.`is`(Blocks.SOUL_SAND) ||
            state.`is`(Blocks.SOUL_SOIL) ||
            state.`is`(Blocks.HONEY_BLOCK) ||
            state.`is`(Blocks.SLIME_BLOCK) ||
            state.`is`(Blocks.MUD)

    private fun isFast(state: BlockState): Boolean =
        state.`is`(Blocks.ICE) || state.`is`(Blocks.PACKED_ICE) || state.`is`(Blocks.BLUE_ICE)

    /**
     * Does this state collide with a body at all?
     *
     * Asked of waterlogged states, where the fluid says "swim through me" and the block says
     * otherwise. Anything that cannot be measured is treated as solid, which is the safe direction:
     * a phantom wall costs a detour, a phantom opening costs a route through rock.
     */
    @Suppress("SwallowedException")
    private fun hasCollision(state: BlockState): Boolean {
        val shape = try {
            state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        } catch (_: Exception) {
            return true
        }
        return !shape.isEmpty
    }

    /**
     * Derives a class from the collision box. Only reached for blocks whose shape is
     * self-contained, so [EmptyBlockGetter] gives the same answer the real world would.
     */
    @Suppress("ReturnCount", "SwallowedException")
    private fun classifyGeometry(state: BlockState): Int {
        val shape = try {
            state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        } catch (_: Exception) {
            // A handful of blocks reach for a BlockEntity while building their shape. Treating them
            // as opaque obstacles is the safe direction to be wrong in.
            return OTHER_SOLID
        }

        if (shape.isEmpty) return AIR

        val bounds = shape.bounds()
        val fullFootprint = bounds.minX <= EPSILON && bounds.minZ <= EPSILON &&
            bounds.maxX >= 1.0 - EPSILON && bounds.maxZ >= 1.0 - EPSILON
        val restsOnFloor = bounds.minY <= EPSILON

        return when {
            fullFootprint && restsOnFloor && bounds.maxY >= 1.0 - EPSILON -> SOLID_FULL
            bounds.maxY > 1.0 + EPSILON -> BLOCKING_TALL
            fullFootprint && restsOnFloor && bounds.maxY in HALF_MIN..HALF_MAX -> SOLID_TOP_HALF
            restsOnFloor && bounds.maxY <= CARPET_MAX -> CARPET_LIKE
            else -> OTHER_SOLID
        }
    }

    /**
     * Buckets block hardness onto a logarithmic 0..14 scale (15 means unbreakable).
     *
     * Break time is proportional to hardness, and hardness spans two orders of magnitude, so a
     * linear bucket would spend its whole range on obsidian. At two buckets per octave the scale
     * reaches a hardness of 127 — comfortably past obsidian and netherite at 50 — while adjacent
     * buckets stay a factor of √2 apart, so the worst-case error is under 20%. That is far tighter
     * than anything else in the cost model.
     *
     * @see bucketToHardness for the inverse used by the cost model.
     */
    @Suppress("SwallowedException")
    fun digBucket(state: BlockState): Int {
        val hardness = try {
            state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        } catch (_: Exception) {
            return DIG_UNBREAKABLE
        }
        if (hardness < 0f) return DIG_UNBREAKABLE
        if (hardness <= 0f) return 0
        val bucket = (ln(hardness + 1.0) / LN_2 * BUCKETS_PER_OCTAVE).roundToInt()
        return bucket.coerceIn(0, DIG_MAX_BREAKABLE)
    }

    /** Inverse of [digBucket]: approximate hardness represented by a bucket. */
    fun bucketToHardness(bucket: Int): Double {
        if (bucket <= 0) return 0.0
        if (bucket >= DIG_UNBREAKABLE) return Double.POSITIVE_INFINITY
        return Math.pow(2.0, bucket / BUCKETS_PER_OCTAVE) - 1.0
    }

    private const val EPSILON = 1.0E-3
    private const val HALF_MIN = 0.4
    private const val HALF_MAX = 0.6
    private const val CARPET_MAX = 0.25
    private const val BUCKETS_PER_OCTAVE = 2.0
    private val LN_2 = ln(2.0)
}
