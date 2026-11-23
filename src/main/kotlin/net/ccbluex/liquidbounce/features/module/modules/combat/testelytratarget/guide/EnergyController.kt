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

import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.WingPhysics
import net.minecraft.world.phys.Vec3
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What the energy controller needs to know. Pure values, blocks and ticks. */
data class EnergyInput(
    val velocity: Vec3,
    val y: Double,
    val targetVelocity: Vec3,
    val targetY: Double,
    val desiredDirection: Vec3,
    val orbitWeight: Double,
    val gravity: Double = WingPhysics.DEFAULT_GRAVITY,
)

/**
 * Tunables of the energy controller.
 *
 * @param margin how much specific energy we try to hold over the target
 * @param convertOvershoot scales how hard surplus energy is traded back into altitude
 * @param maxClimbPitch hard ceiling on the commanded climb, in degrees
 * @param allowZoomClimb whether a climb may carry us out of the attack band. False in the HvH modes,
 *   where the brief says we do not break away, so the orbit keeps the vertical instead
 */
data class EnergySettings(
    val enabled: Boolean = true,
    val margin: Double = 0.6,
    val convertOvershoot: Boolean = true,
    val maxClimbPitch: Double = 40.0,
    val allowZoomClimb: Boolean = true,
)

/**
 * @param direction the guidance direction with the flight-path angle corrected for energy
 * @param energy our specific energy
 * @param targetEnergy the target's specific energy
 * @param deficit how far below the setpoint we are; positive means we are the low-energy aircraft
 */
data class EnergyDecision(
    val direction: Vec3,
    val energy: Double,
    val targetEnergy: Double,
    val deficit: Double,
) {
    /** True when we are short enough of energy that a rocket is worth considering. */
    val wantsBoost: Boolean get() = deficit > BOOST_THRESHOLD

    companion object {
        /**
         * A deficit worth a rocket. Roughly the energy of a ten-block altitude disadvantage, or of
         * being about one block per tick slower: below that we can out-fly the gap by diving.
         */
        const val BOOST_THRESHOLD = 0.8
    }
}

/**
 * Decides how steeply to fly.
 *
 * Guidance owns the azimuth, this owns the flight-path angle, and the split is not arbitrary: which
 * way to turn is a geometry problem, but how steeply to fly is an energy problem, and mixing them is
 * what makes naive chasers bleed speed in the turn and then need a rocket to recover.
 *
 * Specific energy is speed and height added together in one number, `v^2 / 2 + g * h`. A dive spends
 * height to buy speed, a climb spends speed to buy height, and neither changes the total; in the air
 * the only thing that adds to it is a firework. So the honest question is never "am I faster than
 * them" but "do I have more energy than them", and the controller simply flies to hold a margin over
 * the target's figure: short of energy, lower the nose; holding a surplus, raise it.
 *
 * Overshoot falls out of that rule rather than needing a rule of its own. Blowing past an opponent
 * *is* a surplus of kinetic energy at the merge, so the same feedback that says "you have more
 * energy than you need" commands the climb that converts it into height, and the height comes back
 * as speed on the next pass. That is the boomerang, and it costs no special case.
 */
object EnergyController {

    /** Radians of flight-path angle commanded per unit of energy error. */
    private const val PITCH_GAIN = 0.5

    /** Steepest dive we will command. Past this the wing spends more speed on drag than it gains. */
    private const val MAX_DIVE_DEGREES = 70.0

    private const val MIN_HORIZONTAL = 1.0e-6

    fun apply(input: EnergyInput, settings: EnergySettings): EnergyDecision {
        val energy = WingPhysics.specificEnergy(input.velocity, input.y, input.gravity)
        val targetEnergy = WingPhysics.specificEnergy(input.targetVelocity, input.targetY, input.gravity)
        val deficit = targetEnergy + settings.margin - energy

        if (!settings.enabled) {
            return EnergyDecision(input.desiredDirection, energy, targetEnergy, deficit)
        }

        val commandedAngle = commandedAngle(input, settings, deficit)
        return EnergyDecision(
            direction = reAngle(input.desiredDirection, commandedAngle),
            energy = energy,
            targetEnergy = targetEnergy,
            deficit = deficit,
        )
    }

    /**
     * The flight-path angle to fly, in radians, positive being a climb.
     *
     * Starts from whatever slope guidance asked for and biases it by the energy error. Surplus
     * energy is only converted back into height while `convertOvershoot` is on, and, when zoom
     * climbs are disallowed, the bias is faded out as the orbit takes over so that a climb can never
     * carry us out of the band we are supposed to be holding.
     */
    private fun commandedAngle(input: EnergyInput, settings: EnergySettings, deficit: Double): Double {
        val guidanceAngle = asin(input.desiredDirection.normalize().y.coerceIn(-1.0, 1.0))

        val surplus = deficit < 0.0
        val gate = when {
            surplus && !settings.convertOvershoot -> 0.0
            settings.allowZoomClimb -> 1.0
            else -> 1.0 - input.orbitWeight.coerceIn(0.0, 1.0)
        }

        val bias = -PITCH_GAIN * deficit * gate
        val climbLimit = Math.toRadians(settings.maxClimbPitch)
        val diveLimit = Math.toRadians(MAX_DIVE_DEGREES)

        return (guidanceAngle + bias).coerceIn(-diveLimit, climbLimit)
    }

    /**
     * Rebuilds a unit direction with the same compass heading as [direction] but the flight-path
     * angle [angle]. Keeping the azimuth untouched is the whole point of the split: the energy
     * controller must never be able to steer.
     */
    private fun reAngle(direction: Vec3, angle: Double): Vec3 {
        val horizontal = hypot(direction.x, direction.z)
        if (horizontal < MIN_HORIZONTAL) {
            return direction
        }

        val scale = cos(angle) / horizontal
        return Vec3(direction.x * scale, sin(angle), direction.z * scale)
    }
}
