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

import net.minecraft.world.phys.Vec3

/**
 * Everything the chase knows about the target at the instant a plan is made.
 *
 * Deliberately a plain value: no entity reference survives into the guidance layer, so a plan can be
 * rolled forward, scored and unit-tested without a world. [AirState] builds it from the live entity.
 *
 * @param position feet position
 * @param velocity blocks per tick
 * @param height the target's bounding-box height, used to place the aim point
 * @param yaw the direction the target is flying, which for a glider also drives their physics
 * @param pitch the target's nose angle, same
 * @param gliding true when the target is on an elytra
 * @param onGround true when the target is standing on something
 * @param gravity the target's effective gravity
 * @param boostTicks how many more ticks a firework of theirs is expected to keep pushing them
 */
data class TargetSnapshot(
    val position: Vec3,
    val velocity: Vec3,
    val height: Double,
    val yaw: Float,
    val pitch: Float,
    val gliding: Boolean,
    val onGround: Boolean,
    val gravity: Double = WingPhysics.DEFAULT_GRAVITY,
    val boostTicks: Int = 0,
) {
    /**
     * Where to aim on this body. Mid-height rather than the eyes: an elytra pass approaches from
     * arbitrary angles, and the centre of the box is the point that stays reachable longest as the
     * geometry swings around.
     */
    val aimPoint: Vec3 get() = position.add(0.0, height * 0.5, 0.0)
}

/** One predicted tick of the target's flight. */
data class TargetFrame(val position: Vec3, val velocity: Vec3) {
    fun aimPoint(snapshot: TargetSnapshot): Vec3 = position.add(0.0, snapshot.height * 0.5, 0.0)
}

/**
 * Forward-simulates the target so the guidance law can aim where they are going to be rather than
 * where they are.
 *
 * Three regimes, because a player in the air behaves nothing like a player on the ground:
 *
 * - **Gliding** — the full elytra model of [WingPhysics.step] driven by the rotation they are
 *   currently holding, plus [WingPhysics.boost] for as long as one of their rockets is expected to
 *   burn. This is the case that matters: a boosting glider covers ground fast enough that aiming at
 *   their present position misses by tens of blocks.
 * - **Airborne but not gliding** — a jump, a knockback, a fall. Zero-input vanilla air movement:
 *   horizontal decays by the 0.91 air friction, vertical by `(v - gravity) * 0.98`.
 * - **On the ground** — either they keep running at their current speed (`assumeSprint`, the honest
 *   default against a live opponent) or they coast to a halt under the 0.546 ground friction.
 *
 * Every branch holds the target's current heading. Predicting that an opponent will also *turn* is
 * a guess dressed up as data, and a wrong guess here is worse than no guess: it aims the intercept
 * at a place they were never going.
 */
object TargetMotion {

    /** Vanilla air friction for a player who is not touching the ground. */
    private const val AIR_FRICTION = 0.91

    /** Vanilla vertical drag, applied after gravity. */
    private const val VERTICAL_DRAG = 0.98

    /** Default block friction 0.6 times the 0.91 movement factor. */
    private const val GROUND_FRICTION = 0.546

    /** Below this the remaining speed is not worth carrying through the rest of the rollout. */
    private const val REST_SPEED = 0.003

    /**
     * Rolls [snapshot] forward for [ticks] ticks.
     *
     * @param lead when false the target is treated as stationary — the "do not lead" switch. It
     *   zeroes the velocity rather than widening any tolerance, so the rest of the pipeline behaves
     *   identically and only the aim point changes.
     * @return one frame per tick, index 0 being the state one tick from now
     */
    fun predict(
        snapshot: TargetSnapshot,
        ticks: Int,
        assumeSprint: Boolean = true,
        lead: Boolean = true,
    ): List<TargetFrame> {
        if (ticks <= 0) {
            return emptyList()
        }

        var position = snapshot.position
        var velocity = if (lead) snapshot.velocity else Vec3.ZERO
        val look = WingPhysics.look(snapshot.yaw, snapshot.pitch)
        val frames = ArrayList<TargetFrame>(ticks)

        repeat(ticks) { tick ->
            velocity = when {
                snapshot.gliding -> {
                    val boosted = if (tick < snapshot.boostTicks) {
                        WingPhysics.boost(velocity, look)
                    } else {
                        velocity
                    }
                    WingPhysics.step(boosted, look, snapshot.pitch, snapshot.gravity)
                }

                !snapshot.onGround -> Vec3(
                    velocity.x * AIR_FRICTION,
                    (velocity.y - snapshot.gravity) * VERTICAL_DRAG,
                    velocity.z * AIR_FRICTION,
                )

                assumeSprint -> velocity

                else -> Vec3(velocity.x * GROUND_FRICTION, 0.0, velocity.z * GROUND_FRICTION)
            }

            if (velocity.lengthSqr() < REST_SPEED * REST_SPEED) {
                velocity = Vec3.ZERO
            }

            position = position.add(velocity)
            frames.add(TargetFrame(position, velocity))
        }

        return frames
    }
}
