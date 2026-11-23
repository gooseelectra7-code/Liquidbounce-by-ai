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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.math.samplePointOnSide
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * A rotation to request and the point that rotation hits, for one anchor interaction.
 *
 * - [point]: the hit location of the centre ray, i.e. the `location` of the `BlockHitResult` the executor puts into
 *   the hit it clicks with.
 * - [rotation]: the raw rotation that produced [point]; the wire carries `rotation.normalize()` (see [LookPoints]).
 * - [face]: the face as reported by the pick, and [blockPos] the block it belongs to. Both are always non-null here,
 *   unlike in the crystal aura's copy of this file where an entity look point carried neither.
 * - [angle]: `wire.angleTo(rotation)` in degrees (`Rotation.angleTo`, Rotation.kt:135-137).
 *
 * The crystal aura's `servesAttack` flag is gone with the attack it described: all three anchor interactions are
 * block clicks, so a look point never has to serve a second action of a different class.
 */
class LookPoint(
    val point: Vec3,
    val rotation: Rotation,
    val face: Direction,
    val blockPos: BlockPos,
    val angle: Float,
)

/**
 * Aim points for the anchor aura. For each interaction - place against a neighbour face, charge the anchor,
 * detonate the anchor - it finds the rotation that is cheapest to reach from the rotation already on the wire and
 * still performs the action after the rotation pipeline has quantised and dithered it. Pure client-side ray tests
 * through [RayTests]; no packets, no world mutation; every function traces at most `budget` rays.
 *
 * Copied from `crystalaura/aim/LookPoints.kt` minus `forCrystal`, `forPhantomBox` and the box-only helpers: this
 * module never aims at an entity (`RayTests`, class KDoc), so those legs would be dead code.
 *
 * Search structure (both functions): sample points on the visible faces of the block cube -> candidate rotations
 * (`Rotation.lookingAt(point, eye)`, Rotation.kt:66-68) with their angle to the wire (`Rotation.angleTo`,
 * Rotation.kt:135-137) -> sorted per ring -> [search] tests them lazily in that order and stops at the first ROBUST
 * one (centre and the four slack corners pass). Later rings are only built when the earlier ones failed and budget
 * remains.
 *
 * ### Ask ONCE per cycle, then hold the answer
 * Grim's `DuplicateRotPlace` (`checks/impl/scaffolding/DuplicateRotPlace.java:36-50`) arms only when the yaw delta
 * between the last two rotation updates EXCEEDS 2 degrees (`if (deltaX > 2)`) and flags only when that delta then
 * REPEATS across two consecutive placements (`Math.abs(deltaX - lastPlacedDeltaX) < 0.0001`); every other shape
 * calls `reward()`. Clicking one cell three ticks running is therefore the SAFE pattern: after the first aim the
 * delta is about zero, falls under the 2 degree gate, and each click rewards. The dangerous pattern is re-aiming
 * with an identical non-trivial yaw delta before every click.
 *
 * The rule for the sequencer follows directly: take the look point ONCE, when the cycle starts, and hold it for the
 * whole place / charge / detonate cycle. Never call back into this object for the charge or the detonate click
 * while the rotation already on the wire still lands on the cell - re-verify it with `RayTests.hitsBlock`, which
 * costs one ray, instead of re-searching, which costs a new rotation.
 *
 * Only the client pick is modelled (the passed range is the eye-to-hit-point distance); the server's own block
 * click check (eye to `AABB(pos)` < 5.5, `ServerGamePacketListenerImpl.java:1309-1370`) is looser than any range a
 * caller passes here.
 *
 * The public signatures are fixed by the module contract, hence the parameter-count suppression.
 */
@Suppress("LongParameterList")
object LookPoints {

    /** Face-relative sample proportions per ring (`AABB.samplePointOnSide`, BoxExtensions.kt:130-141). */
    private val CENTRE = doubleArrayOf(0.5)
    private val GRID_5 = DoubleArray(5) { 0.1 + 0.2 * it }
    private val GRID_9 = DoubleArray(9) { 0.06 + 0.11 * it }
    private val BLOCK_RINGS = arrayOf(CENTRE, GRID_5, GRID_9)

    private val BY_ANGLE: Comparator<Candidate> = compareBy { it.angle }

    /**
     * Look point that clicks ONLY [face] of the block at [pos] - the PLACEMENT aim, and the anchor module's primary
     * entry point.
     *
     * A respawn anchor is a plain `BlockItem` (`net/minecraft/world/item/Items.java:2193`) with no
     * `getStateForPlacement` and no `canSurvive` override, so the placement is the ordinary
     * `BlockPlaceContext` one: the block lands at `pos.relative(face)` when that cell is replaceable
     * (`BlockPlaceContext.getClickedPos`, `net/minecraft/world/item/context/BlockPlaceContext.java:36-42`, and
     * `BlockItem.place`). The cell the search picked is therefore the NEIGHBOUR of the block clicked here, and this
     * function is what makes the face - not just the block - part of the contract.
     *
     * Sampling and robustness as in [forBlock], ordered by angle to [wire]; the test is
     * `RayTests.hitsBlock(...)` with the extra condition `BlockHitResult.getDirection() == face`
     * (BlockHitResult.java:52-54). Null when [eye] is not strictly beyond that face (no ray from there can enter the
     * block through it), when nothing hits, or when the budget is spent first.
     */
    fun forNeighbourFace(
        eye: Vec3,
        wire: Rotation,
        pos: BlockPos,
        face: Direction,
        range: Double,
        ignore: (Entity) -> Boolean,
        slackDeg: Float,
        budget: Int,
        throughBlocks: Boolean = false,
    ): LookPoint? {
        val box = AABB(pos)
        if (!isOutside(box, face, eye)) return null

        val left = Budget(budget)
        val candidates = blockCandidates(box, listOf(face), Viewer(eye, wire), BY_ANGLE)
        val hitsFace = MemoTest(left) { rotation ->
            RayTests.hitsBlock(
                eye = eye, rotation = rotation, pos = pos,
                range = range, ignore = ignore, throughBlocks = throughBlocks,
            )?.takeIf { it.direction == face }
        }
        return search(candidates, left, slackDeg, hitsFace)?.toLookPoint(pos)
    }

    /**
     * Look point that hits the block at [pos] through ANY of its visible faces - the CHARGE and DETONATE aim.
     *
     * Both of those clicks land on the anchor itself and neither reads the hit direction:
     * `RespawnAnchorBlock.useItemOn` (`net/minecraft/world/level/block/RespawnAnchorBlock.java:78-97`) and
     * `useWithoutItem` (`:99-123`) consult only the blockstate, the held item and `canSetSpawn`. So every visible
     * face is equally good and the cheapest rotation wins outright.
     *
     * Candidates: the visible faces of `AABB(pos)` (AABB.java:31-33; a face is visible when [eye] lies strictly
     * beyond its plane, see [isOutside]), sampled ring by ring - centre, 5x5 grid inset to [0.1, 0.9], 9x9 grid
     * inset to [0.06, 0.94] - every ring ordered by angle to [wire]. Test:
     * `RayTests.hitsBlock(eye, rotation, pos, range, ignore)`.
     *
     * The crystal aura's copy of this function ordered every ring UP face first and carried a second search leg for
     * a rotation that also picked the crystal to attack. Both are dropped: there is no second action to serve, and
     * the UP preference existed only because the future crystal box sat on the top face.
     *
     * [sampleBox] overrides the box the candidates are sampled on; the ray test still names [pos] and the range is
     * still the eye-to-hit-point distance. It exists for the replace-in-place placement, whose target cell may hold
     * a block that does not fill it - `RayTests.pickBox` hands over the OUTLINE bounds, so the aim lands on the
     * shape that is really there. Null means `AABB(pos)`; a respawn anchor fills its cell, so the charge and the
     * detonation are unaffected either way.
     *
     * @return the look point - [LookPoint.point] and [LookPoint.face] are the `location` and `direction` of the
     * centre ray's `BlockHitResult` (BlockHitResult.java:52-54), `blockPos = pos` - or null when no face is visible,
     * no candidate hits the block, or the budget is spent before any does.
     */
    fun forBlock(
        eye: Vec3,
        wire: Rotation,
        pos: BlockPos,
        range: Double,
        ignore: (Entity) -> Boolean,
        slackDeg: Float,
        budget: Int,
        throughBlocks: Boolean = false,
        sampleBox: AABB? = null,
    ): LookPoint? {
        val box = sampleBox ?: AABB(pos)
        val faces = visibleFaces(box, eye)
        if (faces.isEmpty()) return null

        val left = Budget(budget)
        val candidates = blockCandidates(box, faces, Viewer(eye, wire), BY_ANGLE)
        val hitsBlock = MemoTest(left) { rotation ->
            RayTests.hitsBlock(
                eye = eye, rotation = rotation, pos = pos,
                range = range, ignore = ignore, throughBlocks = throughBlocks,
            )
        }
        return search(candidates, left, slackDeg, hitsBlock)?.toLookPoint(pos)
    }

    /**
     * The lazy search shared by both look points: walks [candidates] in priority order and returns the first ROBUST
     * one, i.e. one whose centre rotation passes [test] and whose four slack corners (yaw +- [slackDeg], pitch +-
     * [slackDeg], see [corner]) pass it too. Every ray is charged to [budget] as it is traced; once the budget is
     * exhausted - or the candidates run out - the FIRST candidate whose centre passed is returned with
     * `robust = false`, or null when none passed. A candidate whose evaluation is cut short by the budget counts as
     * not robust.
     *
     * Why corners: `RotationManager.update()` (RotationManager.kt:176-187) sends `target.towards(...).normalize()`,
     * and `Rotation.normalize()` (Rotation.kt:107-128) rounds the delta from the current rotation onto the
     * sensitivity grid (`RotationUtil.gcd`, RotationUtil.kt:46-50), dithers it by up to 0.4 * gcd (`dither`,
     * Rotation.kt:54-63: amplitude `gcd * (0.05 + 0.35 * u)`, noise `(u1 - u2) * amplitude`) and clamps the pitch to
     * +-90. The rotation on the wire may therefore differ from the requested one by up to 0.9 * gcd per axis, and a
     * look point is only worth requesting when the whole slack square still performs the action; the four corners
     * are the cheap proxy for that square (`AuraRotation.slackDegrees` picks 0.9 * gcd for Dither and 0.5 * gcd for
     * Exact). A zero slack skips the corners.
     *
     * [test] returns the hit for a rotation or null. It charges [budget] itself (see [MemoTest]) and answers null
     * when it cannot; the loop re-checks the budget before every ray, so at most the current candidate is affected.
     */
    private fun <T : Any> search(
        candidates: Sequence<Candidate>,
        budget: Budget,
        slackDeg: Float,
        test: (Rotation) -> T?,
    ): Found<T>? {
        var fallback: Found<T>? = null
        for (candidate in candidates) {
            if (budget.isExhausted) break
            val hit = test(candidate.rotation) ?: continue
            if (isRobust(candidate.rotation, slackDeg, budget, test)) {
                return Found(candidate, hit, robust = true)
            }
            if (fallback == null) {
                fallback = Found(candidate, hit, robust = false)
            }
        }
        return fallback
    }

    /** All four [corner]s of [rotation] pass [test]; false as soon as one fails or the budget is exhausted. */
    private fun isRobust(rotation: Rotation, slackDeg: Float, budget: Budget, test: (Rotation) -> Any?): Boolean {
        if (slackDeg <= 0f) return true
        for (index in 0 until 4) {
            if (budget.isExhausted || test(corner(rotation, slackDeg, index)) == null) return false
        }
        return true
    }

    /**
     * Corner [index] (0..3) of the slack square around [rotation]: yaw +- [slackDeg], pitch +- [slackDeg] with the
     * pitch clamped to +-90 exactly like `Rotation.normalize()` (Rotation.kt:127) does on the wire.
     */
    private fun corner(rotation: Rotation, slackDeg: Float, index: Int): Rotation {
        val yawSign = if ((index and 1) == 0) -1f else 1f
        val pitchSign = if ((index and 2) == 0) -1f else 1f
        return Rotation(rotation.yaw + yawSign * slackDeg, (rotation.pitch + pitchSign * slackDeg).coerceIn(-90f, 90f))
    }

    /** The faces of [box] with [eye] strictly beyond their plane (at most three). */
    private fun visibleFaces(box: AABB, eye: Vec3): List<Direction> =
        Direction.entries.filter { isOutside(box, it, eye) }

    /**
     * True when [eye] lies strictly beyond the plane of [face]: `eye.y > maxY` for UP, `eye.y < minY` for DOWN, and
     * the same for EAST/WEST on x and SOUTH/NORTH on z - `Direction.getAxis` / `getAxisDirection`
     * (Direction.java:275, :163) with `Vec3.get(Axis)` (Vec3.java:281) and `AABB.min/max(Axis)` (AABB.java:82-89).
     * From such an eye the segment to any point of that face stays beyond the plane until the point itself, so the
     * sampled point is the first surface point of the box the ray meets.
     */
    private fun isOutside(box: AABB, face: Direction, eye: Vec3): Boolean {
        val coordinate = eye.get(face.axis)
        return if (face.axisDirection == Direction.AxisDirection.POSITIVE) {
            coordinate > box.max(face.axis)
        } else {
            coordinate < box.min(face.axis)
        }
    }

    /** The [BLOCK_RINGS] over [faces] of [box], each ring sorted by [order] and built on first use only. */
    private fun blockCandidates(
        box: AABB,
        faces: List<Direction>,
        viewer: Viewer,
        order: Comparator<Candidate>,
    ): Sequence<Candidate> {
        val rings = BLOCK_RINGS.map { grid -> lazy { faceRing(box, faces, grid, viewer).sortedWith(order) } }
        return sequence { for (ring in rings) yieldAll(ring.value) }
    }

    /**
     * One ring: every pair of [grid] proportions on every face of [faces] (`AABB.samplePointOnSide`,
     * BoxExtensions.kt:130-141), minus the middle cell of grids larger than the centre ring - it is the centre again.
     */
    private fun faceRing(box: AABB, faces: List<Direction>, grid: DoubleArray, viewer: Viewer): List<Candidate> {
        val size = grid.size
        val middle = size / 2
        val ring = ArrayList<Candidate>(faces.size * size * size)
        for (face in faces) {
            for (cell in 0 until size * size) {
                val i = cell / size
                val j = cell % size
                if (size > 1 && i == middle && j == middle) continue
                ring += candidate(box.samplePointOnSide(face, grid[i], grid[j]), viewer)
            }
        }
        return ring
    }

    /**
     * `Rotation.lookingAt(point, eye)` (Rotation.kt:66-68) and its angle to the wire; a point that IS the eye (the
     * eye sits on the sampled face) maps to the wire rotation, since every rotation hits it at distance zero.
     */
    private fun candidate(point: Vec3, viewer: Viewer): Candidate {
        val eye = viewer.eye
        val rotation = if (point.distanceToSqr(eye) == 0.0) viewer.wire else Rotation.lookingAt(point, eye)
        return Candidate(rotation, viewer.wire.angleTo(rotation))
    }

    private fun Found<BlockHitResult>.toLookPoint(pos: BlockPos): LookPoint = LookPoint(
        point = hit.location,
        rotation = candidate.rotation,
        face = hit.direction,
        blockPos = pos,
        angle = candidate.angle,
    )
}

/** The eye position and the rotation currently on the wire, shared by every candidate of one search. */
private class Viewer(val eye: Vec3, val wire: Rotation)

/** One sample: the rotation that looks at it and its angle to the wire. */
private class Candidate(val rotation: Rotation, val angle: Float)

/** A candidate whose centre ray hit, with the hit and whether all four slack corners hit as well. */
private class Found<T>(val candidate: Candidate, val hit: T, val robust: Boolean)

/** Ray tests still allowed in one search; each `RayTests` call charges one unit. */
private class Budget(private var remaining: Int) {

    val isExhausted: Boolean
        get() = remaining <= 0

    /** Charges one ray test; false (and nothing charged) when the budget is exhausted. */
    fun charge(): Boolean {
        if (remaining <= 0) return false
        remaining--
        return true
    }
}

/**
 * A ray test memoised per rotation for one search: each distinct [Rotation] (a value type, so equal yaw/pitch pairs
 * share the entry) is charged to [budget] and traced at most once, a cached outcome is free. A test that cannot be
 * charged answers null (not hit) without caching; the search loop stops on an exhausted budget anyway.
 */
private class MemoTest<T : Any>(
    private val budget: Budget,
    private val trace: (Rotation) -> T?,
) : (Rotation) -> T? {

    private val cache = HashMap<Rotation, T?>()

    override fun invoke(rotation: Rotation): T? {
        if (cache.containsKey(rotation)) return cache[rotation]
        if (!budget.charge()) return null
        return trace(rotation).also { cache[rotation] = it }
    }
}
