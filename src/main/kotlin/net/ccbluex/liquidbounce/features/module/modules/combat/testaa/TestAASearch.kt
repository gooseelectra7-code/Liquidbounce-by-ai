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
package net.ccbluex.liquidbounce.features.module.modules.combat.testaa

import net.ccbluex.liquidbounce.features.module.modules.combat.testaa.TestAAMath.ANCHOR_POWER
import net.minecraft.core.BlockPos
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * The three searches of donut's "Anchor Aura" - existing anchors (`AnchorAura.IiIiiiiiIIiIi`,
 * `AnchorAura.java:628-930`), placement cells (`iIIIIiiiii.IiiiIiIIiiIiI`, `i^I^I^I^Iiiiii.java:444-746`) and cover
 * cells (`iIIIIiiiii.IiiiIiIIIiiII` and its two identical twins, `:134-259`) - ported 1:1, plus the reconstruction
 * of the one piece they all call that is native.
 */
@Suppress("MagicNumber")
internal object TestAASearch {

    /** Sort priority of the donut `Sort` setting (`AnchorAura$IiiiIiIiii`: 0 = Damage, 1 = Safety). */
    enum class SortMode { DAMAGE, SAFETY }

    /** What every search reads off the module, so the searches themselves stay stateless. */
    class Params(
        val minDamage: Float,
        val maxSelfDamage: Float,
        val blastThrough: Boolean,
        val sort: SortMode,
        val randomization: Float,
        /** `AnchorAura.iIIiIIIIIii`: the target's predicted offset, used by the anchor search. */
        val offset: Vec3,
        /** `SocialCommand.IIIiIIIiiIiIiI`: FRIENDS within 16 blocks - the list the self-safety check protects. */
        val friends: List<Player>,
        /** `AnchorAura.iIIIiiiiiii`: predicted boxes of nearby entities, which a placement cell must not touch. */
        val predictedBoxes: List<Pair<net.minecraft.world.entity.Entity, AABB>>,
    )

    /** `AnchorAura$IiiiIiIiiI`: one scored anchor. */
    private class AnchorCandidate(
        val pos: BlockPos,
        val needsCharge: Boolean,
        val slot: Int,
        val distanceSqr: Double,
        val damage: Float,
        val selfDamage: Float,
        val blastThrough: Boolean,
    )

    /**
     * `AnchorAura$IiiiIiIiIi`: the anchor to click. [firstSlot] is glowstone when it [needsCharge], the detonating
     * slot otherwise; [detonateSlot] is the detonating slot either way (`AnchorAura.java:927`).
     */
    class Interaction(
        val damage: Float,
        val pos: BlockPos,
        val needsCharge: Boolean,
        val firstSlot: Int,
        val detonateSlot: Int,
    )

    /** `iIIIIiiiii$iIIIiIIIii`: one scored placement cell. */
    private class PlaceCandidate(
        val pos: BlockPos,
        val damage: Float,
        val blastThrough: Boolean,
        val selfDamage: Float,
    )

    /** `iIIIIiiiii$iIIIiIIIiI`: where to place an anchor, and the click that places it. */
    class Placement(val hit: TestAAWorld.PlaceHit, val pos: BlockPos, val damage: Float)

    /** `iIIIIiiiii$iIIIiIIIII`: where to place a cover block, and the click that places it. */
    class Cover(val pos: BlockPos, val hit: TestAAWorld.PlaceHit)

    /**
     * The self-safety check `SocialCommand.IIIiiIiiIiIiIi` (`SocialCommand.java:65196`) is NATIVE and has no
     * reconstruction anywhere in the donut tree. Known from its six call sites: it takes the candidate cell, the local
     * player, the nearby-FRIENDS list, the anchor centre and the level, returns null to reject the candidate, and its
     * value is stored as the candidate's self damage (the second key of the Safety sort). `Max Self Damage` is read by
     * no Java code at all, so it is read here.
     *
     * GUESS, all of it: our own damage from that anchor (its cell as air, our box unpredicted) must not exceed
     * `Max Self Damage` nor kill us, and no friend in the list may take more than `Max Self Damage` either.
     */
    private fun selfDamage(level: Level, player: Player, pos: BlockPos, center: Vec3, params: Params): Float? {
        val self = TestAAMath.anchorDamage(level, pos, center, player, Vec3.ZERO, null)
        if (self > params.maxSelfDamage || self >= player.health + player.absorptionAmount) {
            return null
        }
        for (friend in params.friends) {
            if (TestAAMath.anchorDamage(level, pos, center, friend, Vec3.ZERO, null) > params.maxSelfDamage) {
                return null
            }
        }
        return self
    }

    private fun anchorsWorkAt(level: Level, pos: BlockPos): Boolean =
        level.environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, pos)

    private fun cube(center: BlockPos, radius: Int): Iterable<BlockPos> =
        BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))

    /**
     * `AnchorAura.IiIiiiiiIIiIi` (`AnchorAura.java:628-930`): the best anchor already standing within block reach.
     *
     * Every anchor in the reach cube is scored against [target] at the predicted offset. One whose real damage misses
     * `Min Damage` only because of cover goes onto a second list when `Blast Through` is on; the best eight of those
     * are re-scored with the cells the blast itself would clear treated as air. The survivors sort by
     * "not Blast Through" first, then `Sort`. The anchor the SERVER rotation already looks at wins ties within 1.0
     * damage (half a heart). The first candidate a rotation can reach is returned.
     */
    @Suppress("CognitiveComplexMethod", "LongMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
    fun findInteraction(level: Level, player: Player, target: Player, serverYaw: Float, serverPitch: Float,
                        params: Params): Interaction? {
        val eye = player.eyePosition
        val range = player.blockInteractionRange()
        val rangeSqr = range * range
        val eyeCell = BlockPos.containing(eye)
        val radius = ceil(range).toInt()

        val glowstone = TestAAWorld.glowstoneSlot(player)
        val detonate = TestAAWorld.detonateSlot(player)
        if (glowstone == -1 && detonate == -1) {
            return null
        }

        val candidates = ArrayList<AnchorCandidate>()
        val blocked = ArrayList<AnchorCandidate>()

        for (cursor in cube(eyeCell, radius)) {
            val state = level.getBlockState(cursor)
            if (!state.`is`(Blocks.RESPAWN_ANCHOR) || anchorsWorkAt(level, cursor)) {
                continue
            }

            val needsCharge = state.getValue(RespawnAnchorBlock.CHARGE) < 1
            val slot = if (needsCharge) glowstone else detonate
            if (slot == -1) {
                continue
            }

            val center = Vec3.atCenterOf(cursor)
            val distanceSqr = center.distanceToSqr(eye)
            if (distanceSqr > rangeSqr) {
                continue
            }

            val upper = TestAAMath.damage(params.offset, 1.0f, target, ANCHOR_POWER, center)
            if (upper <= 0.0f || upper < params.minDamage) {
                continue
            }

            val pos = cursor.immutable()
            val real = TestAAMath.anchorDamage(level, pos, center, target, params.offset, null)
            if (real <= 0.0f || real < params.minDamage) {
                if (!params.blastThrough || real >= upper) {
                    continue
                }
                blocked += AnchorCandidate(pos, needsCharge, slot, distanceSqr, upper, 0.0f, true)
                continue
            }

            val self = selfDamage(level, player, pos, center, params) ?: continue
            candidates += AnchorCandidate(pos, needsCharge, slot, distanceSqr, real, self, false)
        }

        blocked.sortByDescending { it.damage }
        for (candidate in blocked.take(8)) {
            val center = Vec3.atCenterOf(candidate.pos)
            val damage = TestAAMath.blastThroughDamage(level, candidate.pos, center, target, params.offset)
            if (damage < params.minDamage) {
                continue
            }
            val self = selfDamage(level, player, candidate.pos, center, params) ?: continue
            candidates += AnchorCandidate(
                candidate.pos, candidate.needsCharge, candidate.slot, candidate.distanceSqr, damage, self, true
            )
        }

        val order = compareBy<AnchorCandidate> { it.blastThrough }.then(
            if (params.sort == SortMode.SAFETY) {
                compareBy<AnchorCandidate> { it.selfDamage }.thenByDescending { it.damage }
            } else {
                compareByDescending<AnchorCandidate> { it.damage }.thenBy { it.distanceSqr }
            }
        )
        candidates.sortWith(order)
        val best = candidates.firstOrNull() ?: return null

        val looked = TestAAWorld.raycast(level, player, eye, serverYaw, serverPitch, range)
        if (looked.type == HitResult.Type.BLOCK) {
            val index = candidates.indexOfFirst { it.pos == looked.blockPos && it.damage >= best.damage - 1.0f }
            if (index > 0) {
                candidates.add(0, candidates.removeAt(index))
            }
        }

        for (candidate in candidates) {
            val aim = closestPoint(eye, AABB(candidate.pos))
            // `RaycastUtil.IIiIIiiiiiIii(.., mask 112)`: default step 1, origin = eye, jitter 2 - NOT Randomization.
            TestAAWorld.findInteractHit(player, candidate.pos, aim, range, 1.0f, eye, 2.0f) ?: continue
            return Interaction(candidate.damage, candidate.pos, candidate.needsCharge, candidate.slot, detonate)
        }
        return null
    }

    /**
     * `iIIIIiiiii.IiiiIiIIiiIiI` (`i^I^I^I^Iiiiii.java:444-746`): the best free cell to place an anchor into within
     * [range]. Same scoring as [findInteraction], but the target is predicted [predictTicks] ticks ahead (the
     * caller's `Predict + 1 + remaining Place Delay`), a cell must be replaceable, supported, not inside a predicted
     * entity box and safe to place into while we fall, and the tie-break in `Damage` sort is self damage, not
     * distance. The sticky cell is the one our OWN crosshair would place into.
     */
    @Suppress(
        "CognitiveComplexMethod", "LongMethod", "LongParameterList", "ReturnCount", "LoopWithTooManyJumpStatements"
    )
    fun findPlacement(
        level: Level,
        player: Player,
        target: Player,
        range: Double,
        stack: ItemStack,
        offset: Vec3,
        params: Params,
    ): Placement? {
        val anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState()
        val eyeCell = BlockPos.containing(player.eyePosition)
        val radius = ceil(range).toInt()

        val candidates = ArrayList<PlaceCandidate>()
        val blocked = ArrayList<PlaceCandidate>()

        for (cursor in cube(eyeCell, radius)) {
            if (!level.getBlockState(cursor).canBeReplaced() || !TestAAWorld.hasSupport(level, cursor)) {
                continue
            }

            val center = Vec3.atCenterOf(cursor)
            val upper = TestAAMath.damage(offset, 1.0f, target, ANCHOR_POWER, center)
            if (upper <= 0.0f || upper < params.minDamage ||
                !TestAAWorld.canPlaceSafely(level, cursor, anchor, player) ||
                touchesPredictedEntity(AABB(cursor), params) ||
                anchorsWorkAt(level, cursor)
            ) {
                continue
            }

            val pos = cursor.immutable()
            val real = TestAAMath.anchorDamage(level, pos, center, target, offset, null)
            if (real <= 0.0f || real < params.minDamage) {
                if (!params.blastThrough || real >= upper) {
                    continue
                }
                blocked += PlaceCandidate(pos, upper, true, 0.0f)
                continue
            }

            val self = selfDamage(level, player, pos, center, params) ?: continue
            candidates += PlaceCandidate(pos, real, false, self)
        }

        blocked.sortByDescending { it.damage }
        for (candidate in blocked.take(8)) {
            val center = Vec3.atCenterOf(candidate.pos)
            val damage = TestAAMath.blastThroughDamage(level, candidate.pos, center, target, offset)
            if (damage < params.minDamage) {
                continue
            }
            val self = selfDamage(level, player, candidate.pos, center, params) ?: continue
            candidates += PlaceCandidate(candidate.pos, damage, true, self)
        }

        if (candidates.isEmpty()) {
            return null
        }

        val order = compareBy<PlaceCandidate> { it.blastThrough }.then(
            if (params.sort == SortMode.SAFETY) {
                compareBy<PlaceCandidate> { it.selfDamage }.thenByDescending { it.damage }
            } else {
                compareByDescending<PlaceCandidate> { it.damage }.thenBy { it.selfDamage }
            }
        )
        candidates.sortWith(order)
        val best = candidates.first()

        // `IiIiIiiIIi.IIiIiIiIiiiiI`: OUR crosshair (player rotation, not the server's) and the cell it would fill.
        val crosshair = TestAAWorld.raycast(level, player, player.eyePosition, player.yRot, player.xRot, range)
        val crosshairCell = TestAAWorld.placementPos(player, crosshair, stack)
        val index = candidates.indexOfFirst { it.pos == crosshairCell && it.damage >= best.damage - 1.0f }
        if (index > 0) {
            candidates.add(0, candidates.removeAt(index))
        }

        for (candidate in candidates) {
            val hit = TestAAWorld.findPlaceHit(player, candidate.pos, range, stack, null, null, params.randomization)
                ?: continue
            return Placement(hit, candidate.pos, candidate.damage)
        }
        return null
    }

    /**
     * `iIIIIiiiii.IiiiIiIIIiiII` (`i^I^I^I^Iiiiii.java:134-259`; `IiiiIiIIiIIIi` and `IiiiIiIiIIiiI` are identical):
     * a cell for the block in hotbar [slot] that cuts our damage from the anchor at [anchorPos] to at least 0.5
     * below [currentSelfDamage]. Candidates lie within 3 blocks of the segment from our box centre to the
     * anchor centre; our exposure is recomputed with the anchor's cell as air and the candidate as a full cube.
     */
    @Suppress("LongParameterList", "ReturnCount", "LoopWithTooManyJumpStatements")
    fun findCover(
        level: Level,
        player: Player,
        range: Double,
        anchorPos: BlockPos,
        currentSelfDamage: Float,
        slot: Int,
        randomization: Float,
    ): Cover? {
        val stack = player.inventory.getItem(slot)
        val block = (stack.item as? BlockItem ?: return null).block.defaultBlockState()
        val anchorCenter = Vec3.atCenterOf(anchorPos)
        val bodyCenter = player.boundingBox.center
        val eyeCell = BlockPos.containing(player.eyePosition)
        val radius = ceil(range).toInt()

        val candidates = ArrayList<Pair<Float, BlockPos>>()
        for (cursor in cube(eyeCell, radius)) {
            if (!level.getBlockState(cursor).canBeReplaced()) {
                continue
            }
            val center = Vec3.atCenterOf(cursor)
            if (TestAAMath.distanceToSegmentSqr(center, bodyCenter, anchorCenter) > 9.0 ||
                !block.canSurvive(level, cursor)
            ) {
                continue
            }
            if (!TestAAWorld.hasSupport(level, cursor) || !TestAAWorld.canPlaceSafely(level, cursor, block, player)) {
                continue
            }

            val pos = cursor.immutable()
            val exposure = TestAAMath.exposure(level, anchorCenter, player, anchorPos, pos)
            val self = TestAAMath.damage(Vec3.ZERO, exposure, player, ANCHOR_POWER, anchorCenter)
            if (self > currentSelfDamage - 0.5f) {
                continue
            }
            candidates += self to pos
        }

        candidates.sortBy { it.first }
        for ((_, pos) in candidates) {
            val hit = TestAAWorld.findPlaceHit(player, pos, range, stack, null, null, randomization) ?: continue
            return Cover(pos, hit)
        }
        return null
    }

    /** Our damage from the anchor at [pos], as [findCover] measures it before any cover (anchor cell as air). */
    fun ownDamage(level: Level, player: Player, pos: BlockPos): Float =
        TestAAMath.anchorDamage(level, pos, Vec3.atCenterOf(pos), player, Vec3.ZERO, null)

    /** `SocialCommand.IIIiIiiiiiiiii` (`SocialCommand.java:60778-60806`). */
    private fun touchesPredictedEntity(cell: AABB, params: Params): Boolean =
        params.predictedBoxes.any { (entity, box) -> box.intersects(cell) && !entity.isRemoved }

    /** `SocialCommand.IIIiIiIiIIIiII` (`SocialCommand.java:56839-56873`): [point] clamped into [box]. */
    fun closestPoint(point: Vec3, box: AABB): Vec3 = Vec3(
        point.x.coerceIn(box.minX, box.maxX),
        point.y.coerceIn(box.minY, box.maxY),
        point.z.coerceIn(box.minZ, box.maxZ)
    )
}
