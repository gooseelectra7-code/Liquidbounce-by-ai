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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air

import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.TerrainProbe
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Everything the guidance pipeline needs about the world, read once at the top of a tick.
 *
 * This is the only file in the chase that touches entities, and that is deliberate. Reading the
 * world repeatedly during planning invites the kind of bug where two halves of one decision see two
 * different positions; taking a single consistent snapshot and passing values downward makes the
 * whole pipeline a pure function of this struct, which is also what makes it testable.
 *
 * @param eye our eye position, the point reach is measured from
 * @param velocity our current velocity, blocks per tick
 * @param rotation the rotation the wing is actually flying: the silent rotation when we have one,
 *   since that is what vanilla fall-flying reads through the mixin, and the camera only otherwise
 * @param frames the target's predicted track, one entry per tick
 * @param minReach lower edge of the attack band, hitbox margin already folded in
 * @param maxReach upper edge of the same band
 */
data class AirState(
    val eye: Vec3,
    val velocity: Vec3,
    val rotation: Rotation,
    val target: TargetSnapshot,
    val frames: List<TargetFrame>,
    val minReach: Double,
    val maxReach: Double,
) {
    companion object {

        /**
         * How far down to look for ground. Past this the exact number stops mattering: anything with
         * this much air under it is not about to hit anything.
         */
        private const val PROBE_DEPTH = 64.0

        /**
         * Builds the snapshot, or null when the target is no longer worth planning against.
         */
        fun capture(
            target: LivingEntity,
            horizon: Int,
            assumeSprint: Boolean,
            lead: Boolean,
            assumeEnemyFlight: Int,
        ): AirState? {
            if (!target.isAlive || horizon <= 0) {
                return null
            }

            val snapshot = snapshotOf(target, assumeEnemyFlight)
            val frames = TargetMotion.predict(snapshot, horizon, assumeSprint, lead)
            if (frames.isEmpty()) {
                return null
            }

            // The band comes from the aura, never from a setting of our own. Holding a target inside
            // "our" range while the aura measures a different one would keep us in a position it
            // cannot shoot from, which is precisely the failure this module exists to avoid.
            val attackRange = ModuleKillAura.range.getAttackRange()
            val minReach = (attackRange.effectiveMinRange(player) - attackRange.hitboxMargin)
                .coerceAtLeast(0f)
                .toDouble()
            val maxReach = (attackRange.effectiveMaxRange(player) + attackRange.hitboxMargin).toDouble()

            return AirState(
                eye = player.eyePosition,
                velocity = player.deltaMovement,
                rotation = RotationManager.currentRotation ?: player.rotation,
                target = snapshot,
                frames = frames,
                minReach = minReach,
                maxReach = maxReach,
            )
        }

        private fun snapshotOf(target: LivingEntity, assumeEnemyFlight: Int) = TargetSnapshot(
            position = target.position(),
            // Displacement since last tick, not deltaMovement: the server does not send velocity for
            // other players every tick, so the field is stale or zero exactly when it matters most.
            velocity = target.position().subtract(target.lastPos),
            height = target.bbHeight.toDouble(),
            yaw = target.yRot,
            pitch = target.xRot,
            gliding = target.isFallFlying,
            onGround = target.onGround(),
            gravity = target.getAttribute(Attributes.GRAVITY)?.value ?: WingPhysics.DEFAULT_GRAVITY,
            boostTicks = RocketWatch.remainingBoostTicks(target, assumeEnemyFlight),
        )

        /**
         * Ground clearance by a straight downward cast. Cheap, and the only question the feasibility
         * filter asks of the world: a glider that keeps air beneath it does not crash, and a wall in
         * front is a problem the pull-up solves anyway.
         */
        val terrainProbe = TerrainProbe { point ->
            val below = point.subtract(0.0, PROBE_DEPTH, 0.0)
            val hit = world.clip(
                ClipContext(point, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            )

            if (hit.type == HitResult.Type.MISS) PROBE_DEPTH else point.y - hit.location.y
        }
    }
}
