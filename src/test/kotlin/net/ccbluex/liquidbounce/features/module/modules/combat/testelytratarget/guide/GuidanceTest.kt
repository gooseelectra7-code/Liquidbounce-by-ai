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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The guidance law, flown against a moving opponent.
 *
 * The centrepiece is the comparison against pure pursuit, because that is the entire justification
 * for the module being built the way it is. It is stated in both directions on purpose: a decisive
 * win where an attacker would otherwise overshoot, and no meaningful difference in a straight tail
 * chase, so the claim cannot be read as broader than it is. Everything else here guards a property
 * the law is supposed to have: it holds its radius, it pushes away when too close as well as pulling
 * in when too far, and it never jumps.
 *
 * The simulated aircraft is deliberately simple - constant speed with a capped turn rate - so that
 * what is being measured is the law and not the elytra model. The turn cap is the important half:
 * with instant turning, every guidance law looks equally good, and overshoot, the thing this module
 * exists to prevent, cannot happen at all.
 */
class GuidanceTest {

    private val settings = GuidanceSettings()

    private companion object {
        const val MIN_REACH = 0.0
        const val MAX_REACH = 4.0

        /** Radians per tick. About seven degrees, which is a plausible elytra turn rate. */
        const val MAX_TURN = 0.12
    }

    private data class Flight(val ticksInBand: Int, val headings: List<Vec3>, val ranges: List<Double>)

    /**
     * Flies [law] against a target moving in a straight line and reports how long the target spent
     * inside the attack band.
     */
    @Suppress("LongParameterList")
    private fun fly(
        law: (GuidanceInput) -> Vec3,
        ourStart: Vec3,
        ourHeading: Vec3,
        speed: Double,
        targetStart: Vec3,
        targetVelocity: Vec3,
        ticks: Int = 240,
    ): Flight {
        var position = ourStart
        var heading = ourHeading.normalize()
        var targetPosition = targetStart

        var inBand = 0
        val headings = ArrayList<Vec3>(ticks)
        val ranges = ArrayList<Double>(ticks)

        repeat(ticks) {
            val commanded = law(
                GuidanceInput(
                    eye = position,
                    velocity = heading.scale(speed),
                    targetPoint = targetPosition,
                    targetVelocity = targetVelocity,
                    minReach = MIN_REACH,
                    maxReach = MAX_REACH,
                )
            )

            heading = turnTowards(heading, commanded.normalize(), MAX_TURN)
            position = position.add(heading.scale(speed))
            targetPosition = targetPosition.add(targetVelocity)

            val range = position.distanceTo(targetPosition)
            ranges.add(range)
            headings.add(heading)
            if (range in MIN_REACH..MAX_REACH) {
                inBand++
            }
        }

        return Flight(inBand, headings, ranges)
    }

    private fun guided(input: GuidanceInput) = Guidance.solve(input, settings).direction

    /** The naive law: point at where the target is right now. */
    private fun pursuit(input: GuidanceInput) = input.targetPoint.subtract(input.eye).normalize()

    @Test
    fun `guidance keeps an outpaced target in reach far longer than flying straight at it`() {
        // The overshoot regime, and the reason the module exists: an attacker much faster than the
        // target, where a turn radius of ten-odd blocks means blowing past is unrecoverable for
        // several seconds. Pointing at the target flies through it and then spends the fight looping
        // back; cancelling the bearing rate arrives on a curve and stays.
        val ourStart = Vec3.ZERO
        val ourHeading = Vec3(0.0, 0.0, 1.0)
        val targetStart = Vec3(25.0, 0.0, 45.0)
        val stationary = Vec3.ZERO

        listOf(1.5, 0.9).forEach { speed ->
            val guided = fly(::guided, ourStart, ourHeading, speed, targetStart, stationary, ticks = 300)
            val naive = fly(::pursuit, ourStart, ourHeading, speed, targetStart, stationary, ticks = 300)

            assertTrue(
                guided.ticksInBand > naive.ticksInBand * 3,
                "at speed $speed guidance held reach for ${guided.ticksInBand} ticks against " +
                    "${naive.ticksInBand} for pure pursuit, which is not the decisive margin measured"
            )
        }
    }

    @Test
    fun `in a straight tail chase the two laws are comparable`() {
        // Stated so the claim above is not read as more than it is. With nothing to get wrong, there
        // is nothing to win: the advantage is specifically in the overshoot, not everywhere.
        val ourStart = Vec3.ZERO
        val ourHeading = Vec3(0.0, 0.0, 1.0)
        val targetStart = Vec3(0.0, 0.0, 45.0)
        val targetVelocity = Vec3(0.0, 0.0, 0.5)

        val guided = fly(::guided, ourStart, ourHeading, 1.5, targetStart, targetVelocity, ticks = 300)
        val naive = fly(::pursuit, ourStart, ourHeading, 1.5, targetStart, targetVelocity, ticks = 300)

        assertTrue(
            guided.ticksInBand >= naive.ticksInBand * 0.8,
            "guidance fell well behind pure pursuit in a plain tail chase: " +
                "${guided.ticksInBand} against ${naive.ticksInBand}"
        )
    }

    @Test
    fun `the orbit holds station indefinitely once the turn radius allows it`() {
        // An aircraft can only circle something at a radius its turn rate can sustain: below
        // speed / turn-rate it simply cannot be done, whatever the law asks for. At a speed that
        // does allow it, holding station has to be total rather than approximate.
        val flight = fly(
            law = ::guided,
            ourStart = Vec3(3.0, 0.0, 0.0),
            ourHeading = Vec3(0.0, 0.0, 1.0),
            speed = 0.3,
            targetStart = Vec3.ZERO,
            targetVelocity = Vec3.ZERO,
            ticks = 200,
        )

        assertTrue(
            flight.ticksInBand == 200,
            "the orbit let the target out of reach on ${200 - flight.ticksInBand} of 200 ticks"
        )
        assertTrue(
            flight.ranges.all { it <= MAX_REACH },
            "the orbit drifted out to ${flight.ranges.max()} with the band ending at $MAX_REACH"
        )
    }

    @Test
    fun `sitting on top of the target pushes us back out`() {
        // Reach has a floor as well as a ceiling, so being too close has to be corrected in the
        // outward direction rather than treated as ideal.
        val eye = Vec3.ZERO
        val target = Vec3(0.0, 0.0, 0.8)
        val bearing = target.subtract(eye).normalize()

        val direction = Guidance.solve(
            GuidanceInput(
                eye = eye,
                velocity = Vec3(0.0, 0.0, 0.5),
                targetPoint = target,
                targetVelocity = Vec3.ZERO,
                minReach = 2.0,
                maxReach = 4.0,
            ),
            settings,
        ).direction

        assertTrue(
            direction.dot(bearing) < 0.0,
            "commanded direction still points at the target while inside the near edge of the band"
        )
    }

    @Test
    fun `the commanded direction never jumps as the range changes`() {
        // Continuity cannot be checked by asking whether the direction changes slowly, because a
        // steep but smooth stretch would fail that and a small genuine step would pass it. What
        // separates the two is how the change behaves as the sample spacing shrinks: a smooth curve
        // scales with the step, a discontinuity does not shrink at all.
        val coarse = worstStepOverRange(0.04)
        val fine = worstStepOverRange(0.01)

        assertTrue(
            fine < coarse * 0.4,
            "sampling four times finer barely reduced the largest change ($coarse to $fine), " +
                "which is what a step in the commanded direction looks like"
        )
    }

    /** Largest angle between the commands at two adjacent ranges, sweeping across every regime. */
    private fun worstStepOverRange(step: Double): Double {
        val target = Vec3.ZERO
        val targetVelocity = Vec3(0.4, 0.0, 0.0)

        var previous: Vec3? = null
        var worst = 0.0
        var range = 0.5

        while (range <= 25.0) {
            val direction = Guidance.solve(
                GuidanceInput(
                    eye = Vec3(0.0, 0.0, -range),
                    velocity = Vec3(0.0, 0.0, 1.2),
                    targetPoint = target,
                    targetVelocity = targetVelocity,
                    minReach = MIN_REACH,
                    maxReach = MAX_REACH,
                ),
                settings,
            ).direction

            previous?.let { worst = maxOf(worst, angleBetween(it, direction)) }
            previous = direction
            range += step
        }

        return worst
    }

    @Test
    fun `a tail chase does not oscillate`() {
        val flight = fly(
            law = ::guided,
            ourStart = Vec3(0.0, 0.0, -20.0),
            ourHeading = Vec3(0.0, 0.0, 1.0),
            speed = 0.9,
            targetStart = Vec3.ZERO,
            targetVelocity = Vec3(0.0, 0.0, 0.85),
            ticks = 160,
        )

        val turns = flight.headings.zipWithNext { a, b -> angleBetween(a, b) }
        val late = turns.drop(60)

        assertTrue(
            late.all { it <= MAX_TURN + 1.0e-9 },
            "turn rate exceeded the aircraft limit, which means the harness is wrong"
        )
        assertTrue(
            late.average() < MAX_TURN * 0.75,
            "the law is sawing at the stick in a steady chase: mean turn ${late.average()}"
        )
    }

    private fun angleBetween(a: Vec3, b: Vec3): Double =
        acos(a.normalize().dot(b.normalize()).coerceIn(-1.0, 1.0))

    /** Rotates [from] towards [to] by at most [limit] radians. */
    private fun turnTowards(from: Vec3, to: Vec3, limit: Double): Vec3 {
        val angle = angleBetween(from, to)
        if (angle <= limit || angle < 1.0e-9) {
            return to
        }

        val axis = from.cross(to)
        if (axis.lengthSqr() < 1.0e-18) {
            return from
        }

        val unit = axis.normalize()
        val c = cos(limit)
        val s = sin(limit)
        return from.scale(c)
            .add(unit.cross(from).scale(s))
            .add(unit.scale(unit.dot(from) * (1.0 - c)))
            .normalize()
    }
}
