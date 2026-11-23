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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide

import net.minecraft.world.phys.Vec3
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure kinematics handed to [Guidance]. No entities, no world, no settings objects, so the law can
 * be reasoned about on paper and tested headless. Blocks and ticks throughout.
 *
 * @param eye our eye position, because reach is measured from the eyes
 * @param velocity our velocity in blocks per tick
 * @param targetPoint where we want to be pointing: the target's predicted aim point
 * @param targetVelocity the target's predicted velocity, used for the lead turn and the orbit side
 * @param minReach lower edge of the attack band
 * @param maxReach upper edge of the attack band
 */
data class GuidanceInput(
    val eye: Vec3,
    val velocity: Vec3,
    val targetPoint: Vec3,
    val targetVelocity: Vec3,
    val minReach: Double,
    val maxReach: Double,
)

/**
 * Tunables of the guidance law, mirrored from the module's Guidance settings group.
 *
 * The standoff default was measured rather than guessed. Flying the law against pure pursuit over
 * crossing, tail-chase and stationary targets at several speeds, a standoff near the near edge of the
 * band wins or ties almost everywhere, while a wide one throws away time in reach for nothing: the
 * lower edge of vanilla reach is usually zero, so there is no penalty for being close, only for being
 * unable to stay there.
 */
data class GuidanceSettings(
    val navConstant: Double = 3.5,
    val standoff: Double = 0.3,
    val mergeDistance: Double = 3.0,
    val passBehind: Boolean = true,
)

/**
 * What the law decided, plus the numbers worth drawing on screen when Debug is on.
 *
 * @param direction the unit vector we want our velocity to point along
 * @param range current distance to the aim point
 * @param closure closing speed; negative means the gap is opening
 * @param standoffRange the radius inside the attack band we are trying to hold
 * @param orbitWeight 0 while purely chasing, 1 while purely orbiting
 */
data class GuidanceSolution(
    val direction: Vec3,
    val range: Double,
    val closure: Double,
    val standoffRange: Double,
    val orbitWeight: Double,
)

/**
 * Where to point the wing.
 *
 * The problem this solves is not "fly at the target". That is the naive answer, and it is the reason
 * elytra chases degenerate into a series of wild overshoots: flying at where an opponent *is* means
 * forever turning to follow them, arriving from behind with an enormous closing speed, and blowing
 * past in two ticks. The fix is the guidance law every real interceptor uses.
 *
 * **Proportional navigation.** If the bearing to something holds steady while the range shrinks, the
 * two of you are on a collision course; that is the sailor's constant-bearing rule. So rather than
 * steering at the target, steer to cancel the *rotation* of the line of sight, turning at `N` times
 * the rate the bearing drifts. Against anything moving in a straight line this converges onto an
 * intercept with almost no lateral correction left at the end, and that leftover correction is
 * precisely what pure pursuit burns all its energy on.
 *
 * Three regimes, blended continuously by range so nothing can chatter at a boundary:
 *
 * - far out, proportional navigation, with pure pursuit faded in when the target is so far off our
 *   nose that the bearing rate has nothing useful to say yet;
 * - closing, a lead turn aimed at the *edge* of the attack band rather than at the target, so the
 *   pass is an arc through the band instead of a spike through the middle of it;
 * - inside, an orbit that holds the standoff radius.
 *
 * The module this replaces used discrete phases with hysteresis to stop them flapping. A continuous
 * blend cannot flap, so there are no phases here at all.
 */
object Guidance {

    /**
     * Beyond this bearing error the line-of-sight rate is not yet informative: we are not pointed
     * anywhere near the target, so pure pursuit is faded in to bring the nose round first.
     */
    private const val CAPTURE_ANGLE = Math.PI / 3.0

    /** How hard the orbit corrects a wrong radius, relative to its tangential motion. */
    private const val RADIAL_GAIN = 1.4

    @Suppress("MagicNumber")
    fun solve(input: GuidanceInput, settings: GuidanceSettings): GuidanceSolution {
        val toTarget = input.targetPoint.subtract(input.eye)
        val range = toTarget.length()

        val bearing = if (range < NEGLIGIBLE) Vec3(0.0, 0.0, 1.0) else toTarget.scale(1.0 / range)
        val speed = input.velocity.length()
        val heading = if (speed < NEGLIGIBLE) bearing else input.velocity.scale(1.0 / speed)

        val relative = input.targetVelocity.subtract(input.velocity)
        val closure = -relative.dot(bearing)

        val standoffRange = lerp(input.minReach, input.maxReach, settings.standoff.coerceIn(0.0, 1.0))
            .coerceAtLeast(NEGLIGIBLE)

        val tangent = orbitTangent(bearing, input.targetVelocity, heading, settings.passBehind)

        val pursuit = pursuitDirection(bearing, heading, relative, range, settings.navConstant)
        val merge = mergeDirection(bearing, tangent, standoffRange, range)
        val orbit = orbitDirection(bearing, tangent, standoffRange, range)

        // Orbit hands over to the lead turn across the outer half of the band, and the lead turn
        // hands over to pursuit between the band edge and the merge distance. Both edges are
        // smoothstepped, so the commanded direction is continuous in range and so is its derivative.
        val nearWeight = smoothstep(standoffRange * 0.9, input.maxReach * 1.05, range)
        val farWeight = smoothstep(input.maxReach, input.maxReach * settings.mergeDistance, range)

        val close = slerp(orbit, merge, nearWeight)
        val direction = slerp(close, pursuit, farWeight)

        return GuidanceSolution(
            direction = direction,
            range = range,
            closure = closure,
            standoffRange = standoffRange,
            orbitWeight = 1.0 - nearWeight,
        )
    }

    /**
     * Proportional navigation, expressed as a direction rather than an acceleration.
     *
     * The line-of-sight rate is the vector `omega = (r x vr) / (r . r)`: its magnitude is how fast
     * the bearing drifts in radians per tick, its direction is the axis that drift turns about.
     * Commanding our velocity to rotate about that same axis at `N` times that rate is true
     * proportional navigation. Because the controller re-plans every tick, a rate command is all
     * that is needed and there is no lookahead constant to invent.
     *
     * When the target sits far off our nose, the bearing rate is dominated by our own heading error
     * rather than by the target's motion, so pure pursuit is blended in to swing the nose round.
     * That is the acquisition phase of any real seeker.
     */
    private fun pursuitDirection(
        bearing: Vec3,
        heading: Vec3,
        relative: Vec3,
        range: Double,
        navConstant: Double,
    ): Vec3 {
        if (range < NEGLIGIBLE) {
            return bearing
        }

        val toTarget = bearing.scale(range)
        val losRate = toTarget.cross(relative).scale(1.0 / (range * range))
        val commanded = losRate.scale(navConstant)
        val rate = commanded.length()

        val navigation = if (rate < NEGLIGIBLE) {
            heading
        } else {
            rotateAbout(heading, commanded.scale(1.0 / rate), rate)
        }

        val bearingError = angleBetween(heading, bearing)
        val acquisition = smoothstep(CAPTURE_ANGLE, Math.PI, bearingError)
        return slerp(navigation, bearing, acquisition)
    }

    /**
     * The lead turn: aim at the point where the line of sight is tangent to the standoff sphere,
     * not at the target itself. Arriving tangentially is what turns a two-tick fly-through into a
     * sustained pass, because the speed that would have carried us past the target is spent curving
     * around it instead.
     */
    @Suppress("MagicNumber")
    private fun mergeDirection(bearing: Vec3, tangent: Vec3, standoffRange: Double, range: Double): Vec3 {
        if (range < NEGLIGIBLE) {
            return tangent
        }
        val offset = asin((standoffRange / range).coerceIn(0.0, 1.0))
        return bearing.scale(cos(offset)).add(tangent.scale(sin(offset))).normalizeOrElse(bearing)
    }

    /**
     * Holding station inside the attack band.
     *
     * The radial term is deliberately two-sided. Reach is a band with a floor as well as a ceiling,
     * vanilla's own check being `distance >= minReach && distance <= maxReach`, so sitting on top of
     * an opponent misses just as surely as sitting too far away and the correction has to be able to
     * push outwards.
     */
    private fun orbitDirection(bearing: Vec3, tangent: Vec3, standoffRange: Double, range: Double): Vec3 {
        val radialError = ((range - standoffRange) / standoffRange).coerceIn(-1.0, 1.0)
        return tangent.add(bearing.scale(RADIAL_GAIN * radialError)).normalizeOrElse(tangent)
    }

    /**
     * Which way round the target to go.
     *
     * Horizontal, because a vertical orbit trades away the altitude that is also our energy reserve.
     * The side is chosen to carry us into the target's rear hemisphere when `passBehind` is on: an
     * opponent cannot bring their nose round as fast as we can slide across it, so their tail is the
     * one piece of sky they are slowest to defend. Against a stationary target there is no tail to
     * speak of, and the side that costs us the least turning wins instead.
     */
    private fun orbitTangent(bearing: Vec3, targetVelocity: Vec3, heading: Vec3, passBehind: Boolean): Vec3 {
        val raw = bearing.cross(UP)
        val tangent = raw.normalizeOrElse(bearing.cross(heading).normalizeOrElse(anyPerpendicular(bearing)))

        val targetSpeed = targetVelocity.length()
        val preferred = if (passBehind && targetSpeed > NEGLIGIBLE) {
            // Moving opposite to the target's travel walks our relative position towards their tail.
            -tangent.dot(targetVelocity.scale(1.0 / targetSpeed))
        } else {
            tangent.dot(heading)
        }

        return if (preferred < 0.0) tangent.scale(-1.0) else tangent
    }
}
