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

import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Everything the guidance law knows about the aircraft and where it is headed. */
class ElytraSituation(
    val position: Vec3,
    val velocity: Vec3,

    /** The point being flown at. */
    val target: Vec3,

    /** Height the route wants held here. Ignored while [landing]. */
    val cruiseY: Double,

    /** Ground under the aircraft, which is what the landing flare is measured against. */
    val groundY: Double,

    /** Something solid within the look-ahead along the current heading. */
    val blockedAhead: Boolean,

    /** A rocket is carried and could be lit. */
    val hasFirework: Boolean,

    /** One of our rockets is already burning. */
    val boosted: Boolean,

    /** Final descent onto the destination. */
    val landing: Boolean,

    /**
     * The destination is no longer something to steer at — we are putting it down here.
     *
     * Latched by the executor rather than derived from distance, because distance alone unlatches
     * the moment the aircraft flies past, and turning back at that point is how a wide orbit
     * replaces a tight one.
     */
    val touchdown: Boolean = false,
)

/** Where to point, and whether to light a rocket. */
class ElytraCommand(
    val yaw: Float,
    val pitch: Float,
    val useFirework: Boolean,
)

/**
 * Turns "where the route wants us" into "where to point the nose".
 *
 * Kept free of game state on purpose: everything a glide decides comes out of this function, so the
 * decisions can be checked against known situations without a running client. The executor's job is
 * only to measure the situation and apply the answer.
 *
 * ## Why this is not just "look at the waypoint"
 *
 * A glider has no throttle. Pointing straight at a waypoint above you trades away the speed that is
 * holding you up, and a wing that runs out of speed stops flying and starts falling — so altitude is
 * flown as a *damped* error rather than aimed at, and a climb commanded below flying speed is
 * refused outright until either speed or a rocket is available to pay for it.
 */
object ElytraGuidance {

    fun steer(situation: ElytraSituation): ElytraCommand {
        val toTarget = situation.target.subtract(situation.position)
        val yaw = Math.toDegrees(atan2(-toTarget.x, toTarget.z)).toFloat()

        if (situation.landing) return landingCommand(situation, toTarget, yaw)

        val speed = hypot(situation.velocity.x, situation.velocity.z)
        val climb = situation.cruiseY - situation.position.y

        // A wall ahead is not an altitude problem to be damped — it is the one case worth spending
        // everything on immediately. Vanilla prices a glider's collision with a wall at ten times
        // the speed it lost, minus three, which at cruise is most of a health bar.
        var pitch = if (situation.blockedAhead) {
            CLIMB_PITCH
        } else {
            (-climb * PITCH_PER_BLOCK).toFloat().coerceIn(CLIMB_PITCH, DIVE_PITCH)
        }

        val wantsThrust = situation.blockedAhead || climb > CLIMB_THRUST_HEIGHT || speed < CRUISE_SPEED
        val useFirework = situation.hasFirework && !situation.boosted && wantsThrust

        // Stall guard. Climbing costs speed, and below flying speed a climb buys nothing but a
        // stall — level off, build speed back, and climb once there is something to climb on.
        //
        // "Something to climb on" includes the rocket being lit this very tick, which is the whole
        // of taking off: a wing cast from a standing jump has no speed at all, and levelling out
        // there would fly the rocket straight into the ground it just left. Nor does the guard
        // apply with a wall ahead, where levelling out is what hits it.
        val thrusting = situation.boosted || useFirework
        if (!situation.blockedAhead && !thrusting && pitch < GLIDE_PITCH && speed < STALL_SPEED) {
            pitch = GLIDE_PITCH
        }

        return ElytraCommand(yaw, pitch, useFirework)
    }

    /**
     * The final descent, in three stages: come down the slope, spiral off what is left, then flare.
     *
     * The thing that makes landing a glider awkward is that it cannot come down on the spot. Height
     * is only lost by travelling, so a wing that arrives over its destination still high has two
     * options: fly on until the height is gone — which is how an autopilot sails past its target
     * and keeps going — or turn, and spend that height going round instead of going away. Turning
     * is the answer, and it is what a person does.
     *
     * Chasing the destination is not an option once it is beneath us: steering at a point below the
     * aircraft is a circle around it that never closes, because the aircraft keeps overflying the
     * point it is turning towards. The spiral is deliberately flown on heading and turn rate rather
     * than on the destination, so it comes down beside the target rather than orbiting it.
     *
     * The flare at the end is about arriving slowly. Vanilla prices a glider's collision at ten
     * times the speed it loses, minus three, so touching down at cruise is worth most of a health
     * bar; a few dozen ticks of nose-up first costs nothing.
     */
    private fun landingCommand(situation: ElytraSituation, toTarget: Vec3, yawToTarget: Float): ElytraCommand {
        val horizontal = hypot(toTarget.x, toTarget.z)
        val height = situation.position.y - situation.groundY
        val heading = headingYaw(situation, yawToTarget)
        val speed = hypot(situation.velocity.x, situation.velocity.z)

        // Low enough to put it down. Bleed off what speed is left, then settle onto the ground.
        if (height <= FLARE_HEIGHT) {
            val pitch = if (speed > TOUCHDOWN_SPEED) FLARE_PITCH else SINK_PITCH
            return ElytraCommand(heading, pitch, useFirework = false)
        }

        // Over the destination and still too high: turn down onto it rather than flying on.
        if (situation.touchdown || horizontal < TOUCHDOWN_RADIUS) {
            return ElytraCommand(heading + SPIRAL_TURN, leadPitch(situation, SPIRAL_PITCH), useFirework = false)
        }

        val slope = Math.toDegrees(-atan2(toTarget.y, max(horizontal, MIN_HORIZONTAL))).toFloat()
        val wanted = slope.coerceIn(SINK_PITCH, APPROACH_MAX_DIVE)
        return ElytraCommand(yawToTarget, leadPitch(situation, wanted), useFirework = false)
    }

    /**
     * The attitude to hold in order to actually travel at [wanted], rather than merely to point at it.
     *
     * A wing does not go where it looks; its velocity is dragged toward the look direction by about a
     * tenth of the difference each tick. Commanding the descent angle we want therefore produces a
     * much shallower one — which is exactly how a landing that looks correct on paper arrives over
     * its destination still hundreds of blocks up, and then sails past it looking for somewhere to
     * put the height.
     *
     * So the command is aimed past the target angle by the error still outstanding, and eases back
     * onto it as the flight path catches up.
     */
    private fun leadPitch(situation: ElytraSituation, wanted: Float): Float {
        val velocity = situation.velocity
        val horizontal = hypot(velocity.x, velocity.z)
        if (horizontal < MIN_HEADING_SPEED) return wanted

        val actual = Math.toDegrees(-atan2(velocity.y, horizontal)).toFloat()
        return (wanted + (wanted - actual) * PATH_GAIN).coerceIn(CLIMB_PITCH, MAX_COMMANDED_DIVE)
    }

    /** Where the aircraft is actually travelling, for when there is no longer anything to aim at. */
    private fun headingYaw(situation: ElytraSituation, fallback: Float): Float {
        val velocity = situation.velocity
        if (hypot(velocity.x, velocity.z) < MIN_HEADING_SPEED) return fallback
        return Math.toDegrees(atan2(-velocity.x, velocity.z)).toFloat()
    }

    /** Degrees of pitch commanded per block of altitude error. */
    private const val PITCH_PER_BLOCK = 1.5

    /** Steepest climb. Beyond this a wing sheds speed faster than a rocket can restore it. */
    const val CLIMB_PITCH = -35f

    /** Steepest dive while cruising, which is how spare altitude is turned back into speed. */
    const val DIVE_PITCH = 25f

    /** Attitude that trades least: what a stalled wing levels out to while it recovers. */
    const val GLIDE_PITCH = 5f

    /** Below this horizontal speed, in blocks per tick, the wing is no longer really flying. */
    const val STALL_SPEED = 0.6

    /** Below this speed a rocket is worth lighting even when the altitude is right. */
    const val CRUISE_SPEED = 1.0

    /** Altitude deficit that justifies a rocket on its own. */
    const val CLIMB_THRUST_HEIGHT = 6.0

    /**
     * Gentlest descent the landing will command.
     *
     * Nose-down, not level: a wing that stops descending stops landing, and the aircraft floats
     * over its destination indefinitely. Shallow enough that the touchdown is soft.
     */
    const val SINK_PITCH = 8f

    /** Height above the ground at which the descent becomes a flare. */
    const val FLARE_HEIGHT = 6.0

    /** Nose-up attitude that trades speed for time, so the touchdown is slow rather than a crash. */
    const val FLARE_PITCH = -15f

    /** Below this, in blocks per tick, the wing is slow enough to simply be put down. */
    const val TOUCHDOWN_SPEED = 0.55

    /** Horizontal distance to the destination at which steering at it becomes a circle around it. */
    const val TOUCHDOWN_RADIUS = 12.0

    /**
     * Descent attitude while spiralling down over the destination.
     *
     * Steeper than the approach: the whole point of the turn is to get rid of height quickly, and
     * the speed it builds is bled off again in the flare.
     */
    const val SPIRAL_PITCH = 25f

    /**
     * How far off its own heading the aircraft looks while spiralling down.
     *
     * Not a turn rate — an offset, and a large one, because of how a wing turns. The velocity chases
     * the look direction by roughly a tenth of the gap each tick, so looking ten degrees off the
     * heading turns the aircraft about one degree per tick: a circle a hundred blocks across, which
     * is not a spiral over the destination but a departure from it. Looking sixty degrees off turns
     * it six or seven times faster, and bleeds the speed that has to go before touchdown anyway.
     */
    const val SPIRAL_TURN = 60f

    /** Steepest flight path the approach will aim to travel along. */
    const val APPROACH_MAX_DIVE = 25f

    /**
     * Steepest attitude the lead is allowed to command.
     *
     * Well past [APPROACH_MAX_DIVE], because pointing steeply is how a shallow descent is achieved;
     * bounded so that a wing behind on its descent does not answer by standing on its nose.
     */
    const val MAX_COMMANDED_DIVE = 45f

    /**
     * How hard the outstanding error is leaned on, as a multiple of itself.
     *
     * One means "aim as far past the wanted angle as we currently are short of it", which converges
     * without the overshoot a heavier hand would give on a wing this slow to answer.
     */
    private const val PATH_GAIN = 1.0f

    /** Below this the velocity direction is noise rather than a heading. */
    private const val MIN_HEADING_SPEED = 0.05

    /** Guards the slope calculation directly above the touchdown point. */
    private const val MIN_HORIZONTAL = 1.0E-3
}
