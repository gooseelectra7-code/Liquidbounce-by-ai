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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.math.getNearestPoint
import net.ccbluex.liquidbounce.utils.math.samplePointOnSide
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * A rotation to request and the point that rotation hits, for one crystal-aura action.
 *
 * - [point]: the hit location of the centre ray - for a block look point the `location` of the `BlockHitResult`
 *   (the location the executor puts into the `BlockHitResult` it clicks with), for a crystal the
 *   `EntityHitResult.location`, for a phantom box the geometric entry point.
 * - [rotation]: the raw rotation that produced [point]; the wire carries `rotation.normalize()` (see [LookPoints]).
 * - [face] / [blockPos]: non-null for block look points only - the face as reported by the pick and the block itself.
 * - [angle]: `wire.angleTo(rotation)` in degrees (`Rotation.angleTo`, Rotation.kt:135-137).
 * - [servesAttack]: the same rotation also picks the crystal to attack ([LookPoints.forPlacement]); always true for
 *   crystal and phantom look points, always false for [LookPoints.forFace].
 */
class LookPoint(
    val point: Vec3,
    val rotation: Rotation,
    val face: Direction?,
    val blockPos: BlockPos?,
    val angle: Float,
    val servesAttack: Boolean,
)

/**
 * Aim points for the crystal aura. For each action - place on a block face, attack a crystal, attack a predicted
 * (IdPredict) crystal box - it finds the rotation that is cheapest to reach from the rotation already on the wire and
 * still performs the action after the rotation pipeline has quantised and dithered it. Pure client-side ray tests
 * through [RayTests]; no packets, no world mutation; every function traces at most `budget` rays.
 *
 * Search structure (all functions): sample points on the visible faces of a box -> candidate rotations
 * (`Rotation.lookingAt(point, eye)`, Rotation.kt:66-68) with their angle to the wire (`Rotation.angleTo`,
 * Rotation.kt:135-137) -> sorted per ring -> [search] tests them lazily in that order and stops at the first ROBUST one
 * (centre and the four slack corners pass). Later rings are only built when the earlier ones failed and budget remains.
 *
 * Only the client pick is modelled (the passed ranges are the eye-to-hit-point distances); the server's own checks
 * (block click: eye to `AABB(pos)` < 5.5, attack: eye to box <= 6.0, ServerGamePacketListenerImpl.java:1309-1370 and
 * :1758-1785) are looser than any range a caller passes here.
 *
 * The public signatures are fixed by the module contract, hence the parameter-count suppression.
 */
@Suppress("TooManyFunctions", "LongParameterList")
object LookPoints {

    /** Face-relative sample proportions per ring (`AABB.samplePointOnSide`, BoxExtensions.kt:130-141). */
    private val CENTRE = doubleArrayOf(0.5)
    private val GRID_5 = DoubleArray(5) { 0.1 + 0.2 * it }
    private val GRID_9 = DoubleArray(9) { 0.06 + 0.11 * it }
    private val BLOCK_RINGS = arrayOf(CENTRE, GRID_5, GRID_9)

    private val BY_ANGLE: Comparator<Candidate> = compareBy { it.angle }
    private val UP_FIRST_BY_ANGLE: Comparator<Candidate> =
        compareBy<Candidate>({ it.face != Direction.UP }, { it.angle })

    /**
     * Look point for placing a crystal on the obsidian/bedrock at [pos] - any face may be clicked
     * (`EndCrystalItem.useOn`, EndCrystalItem.java:22-57) - preferring one that also picks [attackCrystal], so the
     * executor can attack and place in the same tick from a single rotation.
     *
     * Candidates: the visible faces of `AABB(pos)` (AABB.java:31-33; a face is visible when [eye] lies strictly beyond
     * its plane, see [isOutside]), sampled ring by ring - centre, 5x5 grid inset to [0.1, 0.9], 9x9 grid inset to
     * [0.06, 0.94] - every ring ordered UP face first, then by angle to [wire]. Block test:
     * `RayTests.hitsBlock(eye, rotation, pos, placeRange, ignore)`; the caller's [ignore] is used as is (it already
     * lists the crystal to attack when LookThrough allows aiming through it).
     *
     * Ranking, best first: (A) robust and serving the attack, (B) robust, (C) centre-only and serving the attack,
     * (D) centre-only; within a tier the first candidate in ring order. With an [attackCrystal] the same candidates are
     * searched twice: block-only first (yields B or D), then block-and-crystal with the remaining budget (yields A or
     * C). Block rays are memoised per rotation, so the second pass only pays for the crystal rays
     * (`RayTests.hitsCrystal(eye, rotation, attackCrystal, breakRange, ignore')`, where `ignore'` never ignores
     * [attackCrystal] itself because the caller's filter may list it for the block test); a serving candidate is robust
     * only when its corners pass both tests. The result is A, else B, else C, else D.
     *
     * Without an [attackCrystal], `servesAttack` is `face == UP`: the future crystal box
     * `ExplosionMath.crystalBox(pos)` spans x and z in [pos - 0.5, pos + 1.5] and y in [pos.y + 1, pos.y + 3], so
     * the entire top face (x, z in [pos, pos + 1] at y = pos.y + 1) lies on the bottom plane of that box, and a ray
     * that reaches the top face from an eye above it (UP visibility) is inside the box just before the face point -
     * once the crystal exists the same rotation picks it (entity hits win when strictly closer,
     * LocalPlayer.java:1270), subject to the break range and to occluders, which the executor re-checks with
     * [forCrystal] next tick.
     *
     * [sampleBox] overrides the box the candidates are sampled on; the ray test still names [pos] and the range is
     * still the eye-to-hit-point distance. It exists for a block that does not fill its cell - `RayTests.pickBox`
     * hands over the OUTLINE bounds, so a click on a one-layer snow or a fire aims at the thin slab that is really
     * there rather than at the empty part of the cell above it. Null means `AABB(pos)`, which is what every full
     * block wants and what every existing caller passes.
     *
     * @return the look point - [LookPoint.point] and [LookPoint.face] are the `location` and `direction` of the centre
     * ray's `BlockHitResult` (BlockHitResult.java:52-54), `blockPos = pos` - or null when no face is visible, no
     * candidate hits the block, or the budget is spent before any does.
     */
    fun forPlacement(
        eye: Vec3,
        wire: Rotation,
        pos: BlockPos,
        placeRange: Double,
        attackCrystal: Entity?,
        breakRange: Double,
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
        val candidates = blockCandidates(box, faces, Viewer(eye, wire), UP_FIRST_BY_ANGLE)
        val hitsBlock = MemoTest(left) { rotation ->
            RayTests.hitsBlock(
                eye = eye, rotation = rotation, pos = pos,
                range = placeRange, ignore = ignore, throughBlocks = throughBlocks,
            )
        }
        val plain = search(candidates, left, slackDeg, hitsBlock) ?: return null
        if (attackCrystal == null) {
            return plain.toLookPoint(pos, servesAttack = plain.hit.direction == Direction.UP)
        }

        val pickable = ignoreExcept(attackCrystal, ignore)
        val hitsCrystal = MemoTest(left) { rotation ->
            RayTests.hitsCrystal(
                eye = eye, rotation = rotation, crystal = attackCrystal, range = breakRange, ignore = pickable,
                throughBlocks = throughBlocks,
            )
        }
        val serving = search(candidates, left, slackDeg) { rotation ->
            hitsBlock(rotation)?.takeIf { hitsCrystal(rotation) != null }
        }
        return if (serving != null && (serving.robust || !plain.robust)) {
            serving.toLookPoint(pos, servesAttack = true)
        } else {
            plain.toLookPoint(pos, servesAttack = false)
        }
    }

    /**
     * Look point that clicks ONLY [face] of the block at [pos] - used to place an obsidian base, which then goes to the
     * neighbour `pos.relative(face)`. Same sampling and robustness as [forPlacement], ordered by angle; the test is
     * `RayTests.hitsBlock(...)` with the extra condition `BlockHitResult.getDirection() == face`
     * (BlockHitResult.java:52-54). Null when [eye] is not strictly beyond that face (no ray from there can enter the
     * block through it), when nothing hits, or when the budget is spent first. `servesAttack` is always false.
     */
    fun forFace(
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
        return search(candidates, left, slackDeg, hitsFace)?.toLookPoint(pos, servesAttack = false)
    }

    /**
     * Look point for attacking [crystal]: candidates on `crystal.boundingBox` ([boxCandidates]) ordered by angle,
     * tested with `RayTests.hitsCrystal(eye, rotation, crystal, breakRange, ignore')` where `ignore'` never ignores
     * [crystal] itself. [LookPoint.point] is the `EntityHitResult.location` of the centre ray; `face` and `blockPos`
     * are null, `servesAttack` is true. [throughBlocks] is `RayTests.hitsCrystal`'s.
     */
    fun forCrystal(
        eye: Vec3,
        wire: Rotation,
        crystal: Entity,
        breakRange: Double,
        ignore: (Entity) -> Boolean,
        slackDeg: Float,
        budget: Int,
        throughBlocks: Boolean = false,
    ): LookPoint? {
        val left = Budget(budget)
        val pickable = ignoreExcept(crystal, ignore)
        val hitsCrystal = MemoTest(left) { rotation ->
            RayTests.hitsCrystal(
                eye = eye, rotation = rotation, crystal = crystal, range = breakRange, ignore = pickable,
                throughBlocks = throughBlocks,
            )
        }
        val found = search(boxCandidates(crystal.boundingBox, Viewer(eye, wire)), left, slackDeg, hitsCrystal)
        return found?.let { boxLookPoint(it, it.hit.location) }
    }

    /**
     * [forCrystal] for a crystal that does not exist yet (IdPredict): candidates on [box], tested with
     * `RayTests.hitsPhantomBox(eye, rotation, box, breakRange, ignore)`; [LookPoint.point] is the geometric entry point
     * that test returns. [throughBlocks] is `RayTests.hitsPhantomBox`'s.
     */
    fun forPhantomBox(
        eye: Vec3,
        wire: Rotation,
        box: AABB,
        breakRange: Double,
        ignore: (Entity) -> Boolean,
        slackDeg: Float,
        budget: Int,
        throughBlocks: Boolean = false,
    ): LookPoint? {
        val left = Budget(budget)
        val hitsBox = MemoTest(left) { rotation ->
            RayTests.hitsPhantomBox(
                eye = eye, rotation = rotation, box = box, range = breakRange, ignore = ignore,
                throughBlocks = throughBlocks,
            )
        }
        val found = search(boxCandidates(box, Viewer(eye, wire)), left, slackDeg, hitsBox)
        return found?.let { boxLookPoint(it, it.hit) }
    }

    /**
     * The lazy search shared by all look points: walks [candidates] in priority order and returns the first ROBUST one,
     * i.e. one whose centre rotation passes [test] and whose four slack corners (yaw +- [slackDeg], pitch +-
     * [slackDeg], see [corner]) pass it too. Every ray is charged to [budget] as it is traced; once the budget is
     * exhausted - or the candidates run out - the FIRST candidate whose centre passed is returned with
     * `robust = false`, or null when none passed. A candidate whose evaluation is cut short by the budget counts as
     * not robust.
     *
     * Why corners: `RotationManager.update()` (RotationManager.kt:176-187) sends `target.towards(...).normalize()`, and
     * `Rotation.normalize()` (Rotation.kt:107-128) rounds the delta from the current rotation onto the sensitivity grid
     * (`RotationUtil.gcd`, RotationUtil.kt:46-50), dithers it by up to 0.4 * gcd (`dither`, Rotation.kt:54-63:
     * amplitude `gcd * (0.05 + 0.35 * u)`, noise `(u1 - u2) * amplitude`) and clamps the pitch to +-90. The rotation
     * on the wire may therefore differ from the requested one by up to 0.9 * gcd per axis, and a look point is only
     * worth requesting when the whole slack square still performs the action; the four corners are the cheap proxy
     * for that square (`AuraRotation.slackDegrees` picks 0.9 * gcd for Dither and 0.5 * gcd for Exact). A zero slack
     * skips the corners.
     *
     * [test] returns the hit for a rotation or null. It charges [budget] itself (see [MemoTest]) and answers null when
     * it cannot; the loop re-checks the budget before every ray, so at most the current candidate is affected.
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
     * True when [eye] lies strictly beyond the plane of [face]: `eye.y > maxY` for UP, `eye.y < minY` for DOWN, and the
     * same for EAST/WEST on x and SOUTH/NORTH on z - `Direction.getAxis` / `getAxisDirection` (Direction.java:275,
     * :163) with `Vec3.get(Axis)` (Vec3.java:281) and `AABB.min/max(Axis)` (AABB.java:82-89). From such an eye the
     * segment to any point of that face stays beyond the plane until the point itself, so the sampled point is the
     * first surface point of the box the ray meets.
     */
    private fun isOutside(box: AABB, face: Direction, eye: Vec3): Boolean {
        val coordinate = eye.get(face.axis)
        return if (face.axisDirection == Direction.AxisDirection.POSITIVE) {
            coordinate > box.max(face.axis)
        } else {
            coordinate < box.min(face.axis)
        }
    }

    /**
     * The [BLOCK_RINGS] over [faces] of [box], each ring sorted by [order] and built on first use only (the sequence
     * may be walked twice by [forPlacement]; the `lazy` rings are then shared).
     */
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
     * Candidates on an entity box: ring 0 = the centres of the visible faces plus the point of the box nearest to the
     * eye (`AABB.getNearestPoint`, BoxExtensions.kt:105-111), ring 1 = the 5x5 grids of the visible faces; both sorted
     * by angle. An eye inside the box has no visible face and its nearest point is the eye itself, which [candidate]
     * maps to the wire rotation - correct, because a ray starting inside the box hits it at distance zero for every
     * rotation (`ProjectileUtil.getEntityHitResult`, ProjectileUtil.java:121-126; `AABB.contains`, AABB.java:253-259).
     */
    private fun boxCandidates(box: AABB, viewer: Viewer): Sequence<Candidate> = sequence {
        val faces = visibleFaces(box, viewer.eye)
        val nearest = candidate(box.getNearestPoint(viewer.eye), null, viewer)
        yieldAll((faceRing(box, faces, CENTRE, viewer) + nearest).distinctBy { it.rotation }.sortedWith(BY_ANGLE))
        yieldAll(faceRing(box, faces, GRID_5, viewer).sortedWith(BY_ANGLE))
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
                ring += candidate(box.samplePointOnSide(face, grid[i], grid[j]), face, viewer)
            }
        }
        return ring
    }

    /**
     * `Rotation.lookingAt(point, eye)` (Rotation.kt:66-68) and its angle to the wire; the eye itself (the nearest point
     * of a box that contains the eye) maps to the wire rotation.
     */
    private fun candidate(point: Vec3, face: Direction?, viewer: Viewer): Candidate {
        val eye = viewer.eye
        val rotation = if (point.distanceToSqr(eye) == 0.0) viewer.wire else Rotation.lookingAt(point, eye)
        return Candidate(rotation, face, viewer.wire.angleTo(rotation))
    }

    /** [ignore] with [entity] itself always pickable - a ray meant to hit it must be allowed to. */
    private fun ignoreExcept(entity: Entity, ignore: (Entity) -> Boolean): (Entity) -> Boolean =
        { other -> other !== entity && ignore(other) }

    private fun Found<BlockHitResult>.toLookPoint(pos: BlockPos, servesAttack: Boolean): LookPoint = LookPoint(
        point = hit.location,
        rotation = candidate.rotation,
        face = hit.direction,
        blockPos = pos,
        angle = candidate.angle,
        servesAttack = servesAttack,
    )

    private fun boxLookPoint(found: Found<*>, point: Vec3): LookPoint = LookPoint(
        point = point,
        rotation = found.candidate.rotation,
        face = null,
        blockPos = null,
        angle = found.candidate.angle,
        servesAttack = true,
    )
}

/** The eye position and the rotation currently on the wire, shared by every candidate of one search. */
private class Viewer(val eye: Vec3, val wire: Rotation)

/** One sample: the rotation that looks at it, the face it was sampled on (null for the nearest point) and its angle. */
private class Candidate(val rotation: Rotation, val face: Direction?, val angle: Float)

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
