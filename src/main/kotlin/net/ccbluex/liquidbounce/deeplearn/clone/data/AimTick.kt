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
package net.ccbluex.liquidbounce.deeplearn.clone.data

import com.google.gson.annotations.SerializedName

/**
 * A single recorded game tick of the player's own aim.
 *
 * [dx]/[dy] are the *action*: how many mouse counts the player moved during this tick, as exact
 * integers. A real mouse can only ever produce integer multiples of the sensitivity GCD, so the
 * action space is discrete and storing it as an integer keeps that property intact through the
 * whole pipeline. Everything else is the *context* the player reacted to, measured at the start of
 * the tick, before the movement happened.
 *
 * Keys are single letters to keep the dataset files small — a busy hour of recording is ~72k ticks.
 */
@JvmRecord
data class AimTick(
    @SerializedName("y")
    val ownYaw: Float,
    @SerializedName("p")
    val ownPitch: Float,

    /** Mouse counts moved horizontally during this tick. */
    @SerializedName("dx")
    val dx: Int,
    /** Mouse counts moved vertically during this tick. */
    @SerializedName("dy")
    val dy: Int,

    /** Absolute yaw from the eyes to the target's hitbox center. Zero when [hasTarget] is false. */
    @SerializedName("ty")
    val targetYaw: Float,
    @SerializedName("tp")
    val targetPitch: Float,
    @SerializedName("d")
    val distance: Float,
    @SerializedName("h")
    val hurtTime: Int,

    /**
     * Target hitbox size in blocks. Recorded so the crosshair's offset from the box centre can be
     * expressed as a *fraction of the hitbox* rather than as an angle — which is the form an
     * anticheat sees it in, and the only form in which "where on the body does this player aim"
     * is comparable across distances.
     */
    @SerializedName("tw")
    val targetWidth: Float,
    @SerializedName("tht")
    val targetHeight: Float,

    /**
     * Runtime entity id of the tracked target.
     *
     * It is only meaningful inside one sequence and is deliberately not a UUID: the trainer only
     * needs to know whether two adjacent rows describe the same opponent. Without that bit a
     * switch turns the angle between two unrelated players into an impossible target velocity.
     */
    @SerializedName("tid")
    val targetId: Int,

    /**
     * Own horizontal movement in blocks per tick, resolved into the direction the player is
     * facing. Stored yaw-relative rather than as a world vector or a bare speed, because what
     * couples to aim is *how the player is moving relative to where they are looking* — strafing
     * around a target produces different mouse motion than walking straight at it, and a scalar
     * speed cannot tell those apart.
     */
    @SerializedName("sf")
    val moveForward: Float,
    @SerializedName("ss")
    val moveStrafe: Float,

    /** Whether the crosshair ray hits the target's hitbox. */
    @SerializedName("c")
    val onTarget: Boolean,
    /** Whether an attack packet was sent during this tick. */
    @SerializedName("a")
    val attacked: Boolean,
    /** Whether a target was tracked at all. When false the target fields hold sentinel zeros. */
    @SerializedName("e")
    val hasTarget: Boolean,

    /**
     * Attack cooldown recharge, 0 to 1.
     *
     * The single strongest driver of click timing in 1.9+ combat — a player waits for the weapon
     * to recharge and swings as it lands. Without it the attack head is learning the shadow of a
     * policy whose main input it cannot see.
     */
    @SerializedName("cd")
    val attackCooldown: Float,

    @SerializedName("sp")
    val sprinting: Boolean,
    @SerializedName("sn")
    val sneaking: Boolean,
    @SerializedName("og")
    val onGround: Boolean,

    /** Whether an item was in use — blocking, eating, drawing a bow. Aim differs under all three. */
    @SerializedName("ui")
    val usingItem: Boolean,

    /** Knockback or an explosion arrived this tick: a large, externally caused aim perturbation. */
    @SerializedName("kb")
    val receivedVelocity: Boolean,
    @SerializedName("dmg")
    val tookDamage: Boolean
)
