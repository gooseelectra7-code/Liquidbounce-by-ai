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

package net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.core.Direction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Hit-and-run cycle: strike, break away for the length of the attack cooldown, come back as it
 * expires, strike again.
 *
 * Sitting on a target between hits is what an elytra is worst at — the wing cannot hover, so
 * holding position turns into a tight orbit that bleeds height and never lines up another pass.
 * The cooldown is dead time anyway, so it is spent opening distance and regaining altitude, which
 * is exactly what the next dive needs.
 *
 * ```
 * ATTACK ──── hit lands, or we close inside PassDistance ────► RETREAT
 *    ▲                                                            │
 *    └──── gap open AND cooldown back above ReturnAt ─────────────┘
 * ```
 *
 * [rotationFor] returns `null` during [Phase.ATTACK] — that is the signal for the caller to aim
 * straight at the target, so the attack run keeps the module's own prediction and aim point.
 *
 * ## Why the retreat point is latched
 *
 * The point is snapshotted in **world space** when the retreat starts and flown until the phase
 * ends. Recomputing it every tick — from the target's live position, or by re-running the direction
 * scan — makes the aim jump between neighbouring ticks and the wing never settles on a heading. The
 * result is a spin at roughly the retreat radius that the player cannot break out of. Same failure
 * mode as an unlatched landing decision: an orbit, not an approach.
 */
internal object ElytraTargetPeek : ToggleableValueGroup(ModuleElytraTarget, "Peek", true) {

    /**
     * Run the cycle against gliding targets too. Off by default: two wings closing head-on already
     * separate on their own after a pass, and tail-chasing a glider is the better answer there.
     */
    internal val appliesToGliders by boolean("GlidingTargets", false)

    /**
     * How far the break-away runs before the point counts as reached.
     */
    private val retreatDistance by float("RetreatDistance", 16f, 5f..48f, "blocks")

    /**
     * Height added to the retreat point. A wing only buys height with speed, and the return leg is
     * a dive — without this the cycle sinks a little further on every pass until it is on the floor.
     */
    private val retreatClimb by float("RetreatClimb", 6f, 0f..24f, "blocks")

    /**
     * Attack-cooldown fraction (0 = just swung, 1 = fully charged) at which we turn back. Below 1
     * so the turn and the run in happen *during* the last of the cooldown and we arrive charged
     * rather than arriving early and loitering.
     */
    private val returnAt by float("ReturnAt", 0.6f, 0f..1f)

    /**
     * Distance to the target that ends an attack run even if no hit landed — at this range the pass
     * is spent either way and continuing straight just flies into whatever is behind the target.
     */
    private val passDistance by float("PassDistance", 3.5f, 1f..12f, "blocks")

    /**
     * Gap that must be open before the return leg may start, so a miss cannot collapse the cycle
     * into a tight orbit around the target.
     */
    private val returnDistance by float("ReturnDistance", 12f, 4f..48f, "blocks")

    private val minRetreatTicks by int("MinRetreatTicks", 6, 0..40, "ticks")

    /**
     * Safety cap. Without a KillAura the cooldown signal never moves, and a retreat point that
     * turns out to be unflyable would otherwise hold the aim indefinitely.
     */
    private val maxRetreatTicks by int("MaxRetreatTicks", 60, 10..200, "ticks")

    /**
     * Pitch held when there is no clear air to break away into — nose up, trade speed for height
     * and come back around.
     */
    private val climbPitch by float("ClimbPitch", -88f, -90f..0f, "°")

    private val horizontal = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)

    /**
     * A swing resets `attackStrengthTicker` to zero, so the cooldown fraction drops by a large step
     * in one tick. Anything smaller is the normal per-tick recharge.
     */
    private const val HIT_DROP = 0.15f

    private const val REACHED_RADIUS = 3.0

    private enum class Phase { ATTACK, RETREAT }

    private var phase = Phase.ATTACK
    private var retreatPoint: Vec3? = null
    private var retreatTicks = 0
    private var previousCooldown = 1f
    private var trackedTargetId = -1

    fun reset() {
        phase = Phase.ATTACK
        retreatPoint = null
        retreatTicks = 0
        previousCooldown = 1f
        trackedTargetId = -1
    }

    /**
     * Rotation to fly this tick, or `null` while on the attack run — then the caller aims at the
     * target itself.
     */
    fun rotationFor(target: LivingEntity): Rotation? {
        if (!running) {
            reset()
            return null
        }

        if (target.id != trackedTargetId) {
            reset()
            trackedTargetId = target.id
        }

        val cooldown = player.getAttackStrengthScale(0.5f)
        val landedHit = cooldown < previousCooldown - HIT_DROP
        previousCooldown = cooldown

        val from = player.position()
        val gap = from.distanceTo(target.position())

        when (phase) {
            Phase.ATTACK -> if (landedHit || gap <= passDistance) {
                beginRetreat(target, from)
            }

            Phase.RETREAT -> {
                retreatTicks++
                if (shouldReturn(cooldown, gap, from)) {
                    phase = Phase.ATTACK
                    retreatPoint = null
                }
            }
        }

        if (phase == Phase.ATTACK) {
            return null
        }

        val point = retreatPoint ?: return Rotation(player.yRot, climbPitch)
        return Rotation.lookingAt(point, from)
    }

    private fun shouldReturn(cooldown: Float, gap: Double, from: Vec3): Boolean {
        if (retreatTicks >= maxRetreatTicks) {
            return true
        }

        if (retreatTicks < minRetreatTicks) {
            return false
        }

        val reached = retreatPoint?.let { from.distanceToSqr(it) <= REACHED_RADIUS * REACHED_RADIUS } != false
        val gapOpen = gap >= returnDistance || reached

        return gapOpen && cooldown >= returnAt
    }

    /**
     * Picks where to break away to and latches it.
     *
     * Candidates are scored on how far they point *away* from the target first and how little they
     * turn us second — carrying the speed we already have out of the pass is worth more than the
     * ideal bearing, because that speed is what buys the height for the next dive.
     */
    private fun beginRetreat(target: LivingEntity, from: Vec3) {
        phase = Phase.RETREAT
        retreatTicks = 0
        retreatPoint = null

        val away = flatten(from.subtract(target.position()))
        val heading = flatten(player.deltaMovement)
        val reach = retreatDistance.toDouble()
        val climb = retreatClimb.toDouble()
        val eye = player.eyePosition

        var bestScore = -Double.MAX_VALUE

        for (direction in candidates(heading, away)) {
            val point = from.add(direction.scale(reach)).add(0.0, climb, 0.0)

            if (!isClear(eye, point)) {
                continue
            }

            val score = (away?.dot(direction) ?: 0.0) * AWAY_WEIGHT + (heading?.dot(direction) ?: 0.0)
            if (score > bestScore) {
                bestScore = score
                retreatPoint = point
            }
        }
    }

    private fun candidates(heading: Vec3?, away: Vec3?): List<Vec3> = buildList {
        // Straight on and straight out come first: both are already close to where the pass left
        // us pointing, so they cost the least speed to take.
        heading?.let(::add)
        away?.let(::add)
        horizontal.mapTo(this) { it.unitVec3 }
    }

    private fun flatten(vector: Vec3): Vec3? {
        val flat = Vec3(vector.x, 0.0, vector.z)
        return if (flat.lengthSqr() > 1.0E-4) flat.normalize() else null
    }

    private fun isClear(from: Vec3, to: Vec3): Boolean {
        val context = ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        return player.level().clip(context).type == HitResult.Type.MISS
    }

    private const val AWAY_WEIGHT = 2.0
}
