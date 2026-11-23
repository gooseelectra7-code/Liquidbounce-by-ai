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
package net.ccbluex.liquidbounce.utils.combat.autoanchor

import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

/**
 * Picks an aim point on a respawn anchor that the player can actually click.
 *
 * Faithful port of echo's `AnchorAimSearch`: the naive approach — always aim at
 * the eye-side corner of the eye-facing face — fails when an adjacent block
 * (the safety block we just placed, obsidian, etc.) occludes that corner: the
 * raycast lands on the neighbor and the interaction is rejected. So we walk
 * every visible face in most-facing-first order, and for each face try a series
 * of sample points (random near-corner -> inward toward face center -> opposite
 * quadrants), returning the first one whose ray from the eye actually hits the
 * anchor.
 */
object AnchorAimSearch {

    /** Find an aim point on [anchorPos] that raycasts cleanly from the player's eye. */
    fun findClearAimPoint(anchorPos: BlockPos, partialTick: Float): Vec3 =
        findClearAimPointOrNull(anchorPos, partialTick) ?: Vec3.atCenterOf(anchorPos)

    /** [findClearAimPoint], but null when no sample point on any visible face raycasts cleanly. */
    fun findClearAimPointOrNull(anchorPos: BlockPos, partialTick: Float): Vec3? {
        val player = mc.player ?: return null
        if (mc.level == null) return null

        val eye = player.getEyePosition(partialTick)
        val anchorCenter = Vec3.atCenterOf(anchorPos)
        val toEye = eye.subtract(anchorCenter)

        // Most-facing-first: DESCENDING dot product with the direction to the eye.
        val sorted = Direction.entries.sortedByDescending { dotEye(it, toEye) }

        for (faceDir in sorted) {
            if (dotEye(faceDir, toEye) <= 0.0) continue // back face -- can't hit it from here
            val candidate = searchFace(eye, anchorPos, anchorCenter, faceDir)
            if (candidate != null) return candidate
        }
        return null
    }

    /** True if [aimPoint] raycasts from [eye] and lands on [anchorPos] (not an adjacent block). */
    fun canHitAnchor(eye: Vec3, aimPoint: Vec3, anchorPos: BlockPos): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false

        val dir = aimPoint.subtract(eye)
        if (dir.lengthSqr() < 1.0E-8) return true
        val end = eye.add(dir.normalize().scale(player.blockInteractionRange()))
        val hit = level.clip(
            ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        )
        return hit.type == HitResult.Type.BLOCK && hit.blockPos == anchorPos
    }

    private fun dotEye(dir: Direction, toEye: Vec3): Double =
        dir.stepX * toEye.x + dir.stepY * toEye.y + dir.stepZ * toEye.z

    private fun searchFace(eye: Vec3, anchorPos: BlockPos, anchorCenter: Vec3, faceDir: Direction): Vec3? {
        val fcx = anchorCenter.x + faceDir.stepX * 0.5
        val fcy = anchorCenter.y + faceDir.stepY * 0.5
        val fcz = anchorCenter.z + faceDir.stepZ * 0.5
        val axis = faceDir.axis

        val signA: Double
        val signB: Double
        when (axis) {
            Direction.Axis.X -> {
                signA = if (eye.y >= anchorCenter.y) 1.0 else -1.0
                signB = if (eye.z >= anchorCenter.z) 1.0 else -1.0
            }
            Direction.Axis.Y -> {
                signA = if (eye.x >= anchorCenter.x) 1.0 else -1.0
                signB = if (eye.z >= anchorCenter.z) 1.0 else -1.0
            }
            else -> {
                signA = if (eye.x >= anchorCenter.x) 1.0 else -1.0
                signB = if (eye.y >= anchorCenter.y) 1.0 else -1.0
            }
        }

        // Random near-corner so consecutive sequences don't pick identical yaws
        // (helps avoid Grim DuplicateRotPlace lining up across cycles).
        val startOff = 0.30 + ThreadLocalRandom.current().nextDouble() * 0.10 // [0.30, 0.40]

        val sampleOffsets = arrayOf(
            doubleArrayOf(signA * startOff, signB * startOff),
            doubleArrayOf(signA * 0.25, signB * 0.25),
            doubleArrayOf(signA * 0.20, signB * 0.20),
            doubleArrayOf(signA * 0.15, signB * 0.15),
            doubleArrayOf(signA * 0.10, signB * 0.10),
            doubleArrayOf(signA * 0.30, 0.0),
            doubleArrayOf(0.0, signB * 0.30),
            doubleArrayOf(0.0, 0.0),
            doubleArrayOf(-signA * 0.30, signB * 0.30),
            doubleArrayOf(signA * 0.30, -signB * 0.30),
            doubleArrayOf(-signA * 0.30, -signB * 0.30),
            doubleArrayOf(-signA * 0.30, 0.0),
            doubleArrayOf(0.0, -signB * 0.30),
        )

        for (off in sampleOffsets) {
            val candidate = faceSamplePoint(axis, fcx, fcy, fcz, off[0], off[1])
            if (canHitAnchor(eye, candidate, anchorPos)) return candidate
        }
        return null
    }

    private fun faceSamplePoint(
        axis: Direction.Axis,
        fcx: Double,
        fcy: Double,
        fcz: Double,
        offA: Double,
        offB: Double
    ): Vec3 = when (axis) {
        Direction.Axis.X -> Vec3(fcx, fcy + offA, fcz + offB)
        Direction.Axis.Y -> Vec3(fcx + offA, fcy, fcz + offB)
        Direction.Axis.Z -> Vec3(fcx + offA, fcy + offB, fcz)
    }
}
