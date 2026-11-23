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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Chooses which keys to hold by trying them, rather than by working out which one points nearest to
 * where we want to go.
 *
 * ## Why aiming a key at the target is not steering
 *
 * A keyboard offers eight directions. Any line that is not one of them can only be walked by holding
 * a direction that is up to twenty-two degrees wrong, and a body with momentum turns that error into
 * a slide: it leaves the line, the error grows, the steering answers with the *next* key round, and
 * the body arrives at the corner sideways. Two more things make it worse and neither is visible from
 * inside a formula. Movement is computed from the rotation the client is *sending*, not from the
 * camera, so during a smoothed turn the keys are being resolved against a heading that is still
 * catching up. And under [MovementCorrection.SILENT] the keys are rotated once more and re-rounded
 * to the same eight directions before they reach the physics — a second quantisation of an already
 * quantised answer.
 *
 * ## What this does instead
 *
 * It runs the client's own physics forward a few ticks for each of the eight keys, through the same
 * correction the game will apply, and keeps the one that leaves the body nearest the line it is
 * supposed to be on. Momentum, friction, the wall it is about to graze, the ledge it is about to
 * step off and both quantisations are then not modelled at all — they simply happen in the
 * simulation, the same way they are about to happen for real.
 *
 * The result is closed-loop and looks like a person: on a line that no key matches it alternates
 * between the two that bracket it, and into a turn it starts leaning before the corner instead of
 * discovering it afterwards.
 */
object NavSteering {

    /**
     * The keys to hold this tick to stay on the line from [from] to [to].
     *
     * [previous] is what was chosen last tick, and exists only to settle ties: two keys are often
     * worth the same fraction of a block, and swapping between them every tick is a stutter that
     * costs speed for nothing.
     */
    fun bestInput(
        from: Vec3,
        to: Vec3,
        sprinting: Boolean,
        previous: DirectionalInput,
    ): DirectionalInput {
        var best = DirectionalInput.FORWARDS
        var bestError = Double.MAX_VALUE

        for (candidate in CANDIDATES) {
            var error = trackingError(predict(candidate, sprinting), from, to)
            if (candidate != previous) error += SWITCH_PENALTY

            if (error < bestError) {
                bestError = error
                best = candidate
            }
        }
        return best
    }

    /**
     * Where the body ends up after holding [input] for the length of the horizon.
     *
     * Seeded from the live player, so the velocity it starts with is the real one — which is the
     * whole point: the same key produces a different answer depending on what the body is already
     * doing.
     */
    private fun predict(input: DirectionalInput, sprinting: Boolean): Vec3 {
        val simulation = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput(
                asTheGameWillApplyIt(input),
                jumping = false,
                sprinting = sprinting,
                sneaking = false,
            ),
        )
        // Not the camera. See movementYaw: with a rotation in force the client strafes about the
        // rotation it is sending, and during a smoothed turn the two are degrees apart.
        simulation.yRot = movementYaw()

        repeat(HORIZON_TICKS) { simulation.tick() }
        return simulation.pos
    }

    /**
     * How badly a predicted position tracks the leg.
     *
     * Two terms, and the ordering between them is the whole behaviour. Distance to the line keeps
     * the body *on* the route, which is what stops the slide; distance still to run keeps it moving
     * along it, which is what stops the body sitting on the line doing nothing. Sagging below the
     * line is priced too, because a key that walks off a ledge scores beautifully on both of the
     * others right up until the landing.
     */
    private fun trackingError(predicted: Vec3, from: Vec3, to: Vec3): Double {
        val lateral = distanceToSegment(predicted, from, to)
        val remaining = horizontalDistance(predicted, to)
        val sag = max(0.0, to.y - predicted.y)

        return remaining + lateral * LATERAL_WEIGHT + sag * SAG_WEIGHT
    }

    /** Horizontal distance from a point to a segment, which is the line the route asked for. */
    private fun distanceToSegment(point: Vec3, from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val lengthSq = dx * dx + dz * dz
        if (lengthSq < MIN_SEGMENT_SQ) return horizontalDistance(point, to)

        val along = (((point.x - from.x) * dx + (point.z - from.z) * dz) / lengthSq).coerceIn(0.0, 1.0)
        val offX = point.x - (from.x + dx * along)
        val offZ = point.z - (from.z + dz * along)
        return sqrt(offX * offX + offZ * offZ)
    }

    private fun horizontalDistance(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return sqrt(dx * dx + dz * dz)
    }

    // -----------------------------------------------------------------------
    //  The client's movement pipeline, as the simulation has to see it
    // -----------------------------------------------------------------------

    /**
     * The heading the body will actually strafe about this tick.
     *
     * With any rotation in force other than [MovementCorrection.OFF],
     * [RotationManager]'s velocity handler recomputes the movement vector from the rotation being
     * *sent*, not from the camera — that is what keeps a silent rotation consistent with the
     * movement the server sees. Predicting against the camera instead is wrong by the whole width of
     * a smoothed turn, which is precisely when steering matters most.
     */
    private fun movementYaw(): Float {
        val correction = RotationManager.activeRotationTarget?.movementCorrection ?: return player.yRot
        if (correction == MovementCorrection.OFF) return player.yRot

        return RotationManager.currentRotation?.yaw ?: player.yRot
    }

    /**
     * The keys after the correction the game is about to apply to them.
     *
     * Mirrors `MixinKeyboardInput#transformDirection`, deliberately down to the rounding: under
     * silent correction the impulse is rotated by the gap between camera and sent rotation and then
     * snapped back onto the eight directions, so the keys that leave here are not always the keys
     * the physics receives. Reproducing the snap is what lets the search pick a key for its *effect*
     * rather than for its name.
     */
    private fun asTheGameWillApplyIt(input: DirectionalInput): DirectionalInput {
        val target = RotationManager.activeRotationTarget ?: return input
        if (target.movementCorrection != MovementCorrection.SILENT) return input
        val rotation = RotationManager.currentRotation ?: return input

        val forward = impulse(input.forwards, input.backwards)
        val sideways = impulse(input.left, input.right)

        val deltaYaw = ((player.yRot - rotation.yaw) * DEG_TO_RAD).toDouble()
        val cos = Mth.cos(deltaYaw)
        val sin = Mth.sin(deltaYaw)

        return DirectionalInput(
            movementForward = (forward * cos + sideways * sin).roundToInt().toFloat(),
            movementSideways = (sideways * cos - forward * sin).roundToInt().toFloat(),
        )
    }

    /** Vanilla's `KeyboardInput.calculateImpulse`: opposing keys cancel. */
    private fun impulse(positive: Boolean, negative: Boolean): Float = when {
        positive == negative -> 0f
        positive -> 1f
        else -> -1f
    }

    // -----------------------------------------------------------------------
    //  Tuning
    // -----------------------------------------------------------------------

    /**
     * Every way of moving worth considering. Standing still is not among them: stopping is decided
     * by the executor, which knows what it is stopping *for*, and letting the search discover it
     * would have the body brake at every waypoint it cannot quite reach in three ticks.
     */
    private val CANDIDATES = arrayOf(
        DirectionalInput.FORWARDS,
        DirectionalInput.FORWARDS_LEFT,
        DirectionalInput.FORWARDS_RIGHT,
        DirectionalInput.LEFT,
        DirectionalInput.RIGHT,
        DirectionalInput.BACKWARDS_LEFT,
        DirectionalInput.BACKWARDS_RIGHT,
        DirectionalInput.BACKWARDS,
    )

    /**
     * Ticks of physics rolled forward per candidate.
     *
     * Short on purpose. The decision is remade every tick, so the horizon only has to be long enough
     * for momentum to show itself — about a block at a sprint. Longer would anticipate more and cost
     * proportionally more, and would start answering a question the next tick will answer better.
     */
    private const val HORIZON_TICKS = 3

    /**
     * What a block off the line is worth against a block of progress along it.
     *
     * Above one, because a route is a promise that the ground along it has been checked and the
     * ground beside it has not.
     */
    private const val LATERAL_WEIGHT = 2.5

    /** What a block of unplanned descent is worth. Falling is rarely a shortcut. */
    private const val SAG_WEIGHT = 3.0

    /** Blocks of advantage a different key must show before the keys are changed. */
    private const val SWITCH_PENALTY = 0.02

    private const val MIN_SEGMENT_SQ = 1.0E-6

    private const val DEG_TO_RAD = 0.017453292f
}
