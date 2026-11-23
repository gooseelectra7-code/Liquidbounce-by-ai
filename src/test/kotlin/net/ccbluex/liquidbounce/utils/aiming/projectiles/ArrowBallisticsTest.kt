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

package net.ccbluex.liquidbounce.utils.aiming.projectiles

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The solver is checked against a straight re-statement of vanilla's `AbstractArrow.tick` step
 * (move, then inertia, then gravity) — not against its own closed form.
 */
class ArrowBallisticsTest {

    private val fullBow = ArrowBallistics.Physics(speed = 3.0, gravity = 0.05, drag = 0.99)

    /**
     * Steps the arrow the vanilla way and returns how close its path (sampled finely inside each
     * tick's straight segment) comes to [target], compared at the same moment in time.
     */
    private fun closestApproach(
        physics: ArrowBallistics.Physics,
        start: Vec3,
        velocity: Vec3,
        ticks: Int = 150,
        target: (Double) -> Vec3,
    ): Double {
        var position = start
        var motion = velocity
        var best = Double.MAX_VALUE

        repeat(ticks) { tick ->
            val next = position.add(motion)

            for (sample in 0..SAMPLES_PER_TICK) {
                val fraction = sample.toDouble() / SAMPLES_PER_TICK
                val point = position.lerp(next, fraction)
                best = minOf(best, point.distanceTo(target(tick + fraction)))
            }

            position = next
            motion = motion.scale(physics.drag).subtract(0.0, physics.gravity, 0.0)
        }

        return best
    }

    @Test
    fun `direct shots pass through static points`() {
        val source = Vec3(0.0, 1.52, 0.0)

        for (speed in doubleArrayOf(3.0, 2.0625, 3.15, 2.5)) {
            // Inside the reach of the slowest speed (a 15-tick bow reaches ~62 blocks on flat ground).
            for (distance in doubleArrayOf(1.0, 4.0, 10.0, 25.0, 40.0)) {
                for (height in doubleArrayOf(-10.0, 0.0, 8.0)) {
                    val physics = fullBow.copy(speed = speed)
                    val aim = Vec3(distance, source.y + height, distance * 0.3)

                    val solution = ArrowBallistics.solveDirect(physics, source, Vec3.ZERO, 120.0) { aim }
                    assertNotNull(solution, "no solution at speed $speed, distance $distance, height $height")

                    val miss = closestApproach(physics, source, solution!!.direction.scale(speed)) { aim }
                    assertTrue(miss < TOLERANCE, "missed by $miss at speed $speed, distance $distance, height $height")
                }
            }
        }
    }

    @Test
    fun `moving targets and inherited shooter motion are led exactly`() {
        val random = Random(1)
        val source = Vec3(0.0, 1.52, 0.0)

        repeat(60) {
            val base = Vec3(random.nextDouble(-50.0, 50.0), random.nextDouble(-8.0, 8.0), random.nextDouble(-50.0, 50.0))
            val targetVelocity = Vec3(random.nextDouble(-0.3, 0.3), 0.0, random.nextDouble(-0.3, 0.3))
            val inherited = Vec3(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.4, 0.4), random.nextDouble(-0.3, 0.3))
            val target = { ticks: Double -> base.add(targetVelocity.scale(ticks)) }

            val solution = ArrowBallistics.solveDirect(fullBow, source, inherited, 120.0, target) ?: return@repeat
            val velocity = solution.direction.scale(fullBow.speed).add(inherited)

            val miss = closestApproach(fullBow, source, velocity, target = target)
            assertTrue(miss < TOLERANCE, "missed a moving target by $miss")
        }
    }

    @Test
    fun `no drag and no gravity is a straight line`() {
        val firework = ArrowBallistics.Physics(speed = 1.6, gravity = 0.0, drag = 1.0)
        val source = Vec3(0.0, 1.5, 0.0)
        val aim = Vec3(20.0, 3.0, 5.0)

        val solution = ArrowBallistics.solveDirect(firework, source, Vec3.ZERO, 120.0) { aim }
        assertNotNull(solution)

        val expected = aim.subtract(source).normalize()
        assertTrue(solution!!.direction.distanceTo(expected) < 1E-6)
        assertEquals(aim.distanceTo(source) / firework.speed, solution.flightTicks, 1E-6)
    }

    @Test
    fun `out of range is unsolved`() {
        val weak = fullBow.copy(speed = 0.5)

        assertNull(ArrowBallistics.solveDirect(weak, Vec3.ZERO, Vec3.ZERO, 120.0) { Vec3(200.0, 0.0, 0.0) })
    }

    @Test
    fun `rotation round trip matches the solved direction`() {
        val solution = ArrowBallistics.solveDirect(fullBow, Vec3.ZERO, Vec3.ZERO, 120.0) {
            Vec3(-17.0, 4.0, 23.0)
        }
        assertNotNull(solution)

        // Rotation holds floats and Vec3.directionFromRotation uses Mth's 65536-entry sine table,
        // exactly like the server's shootFromRotation: agreement to ~1E-4 per unit is the floor.
        val velocity = ArrowBallistics.launchVelocity(solution!!.rotation, fullBow.speed, Vec3.ZERO)
        val error = velocity.distanceTo(solution.direction.scale(fullBow.speed))
        assertTrue(error < 1E-3, "round trip off by $error (${solution.rotation}, ${solution.direction})")
    }

    @Test
    fun `entity margin grows from the third tick`() {
        assertEquals(0.0, ArrowBallistics.entityMargin(1))
        assertEquals(0.0, ArrowBallistics.entityMargin(2))
        assertEquals(0.05, ArrowBallistics.entityMargin(3), 1E-9)
        assertEquals(0.3, ArrowBallistics.entityMargin(8), 1E-9)
        assertEquals(0.3, ArrowBallistics.entityMargin(40), 1E-9)
    }

    /**
     * [ArrowBallistics.hitChance] against a single static box, no blocks, no timing shift.
     */
    private fun chanceAgainst(box: AABB, direction: Vec3, timingJitter: Double = 0.0): Double =
        ArrowBallistics.hitChance(
            fullBow,
            Vec3(0.0, 1.52, 0.0),
            Vec3.ZERO,
            direction.normalize(),
            inaccuracy = 1.0,
            timingJitter = timingJitter,
            maxTicks = 60,
        ) { _, from, to, _ ->
            if (box.clip(from, to).isPresent) ArrowBallistics.Outcome.TARGET else ArrowBallistics.Outcome.NONE
        }

    @Test
    fun `hit chance is one for a box no draw of the spread can miss`() {
        // A wall two blocks deep, twenty wide and high, ten blocks out.
        val wall = AABB(10.0, -10.0, -10.0, 12.0, 10.0, 10.0)
        assertEquals(1.0, chanceAgainst(wall, Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun `hit chance is zero when every draw misses`() {
        // A player box ten blocks out, aimed a full ten degrees to the side of it.
        val box = AABB(9.7, 0.0, -0.3, 10.3, 1.8, 0.3)
        assertEquals(0.0, chanceAgainst(box, Vec3(1.0, 0.0, 0.176)))
    }

    @Test
    fun `hit chance at a box edge is about a half`() {
        // A tall, deep box whose near face's edge sits exactly on the aim line: draws turned to one
        // side hit, draws turned to the other miss, and the spread is symmetric.
        val box = AABB(9.0, -10.0, 0.0, 11.0, 10.0, 10.0)
        val chance = chanceAgainst(box, Vec3(1.0, 0.0, 0.0))
        assertTrue(chance in 0.3..0.7, "edge chance $chance")
    }

    @Test
    fun `hit chance covers the timing jitter evenly`() {
        val shifts = ArrayList<Double>()

        ArrowBallistics.hitChance(fullBow, Vec3.ZERO, Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 1.0, 2.0, 1) { _, _, _, shift ->
            shifts += shift
            ArrowBallistics.Outcome.BLOCKED
        }

        assertEquals(ArrowBallistics.SPREAD_SAMPLE_COUNT, shifts.size)
        assertEquals(0.0, shifts.average(), 1E-9)
        assertTrue(shifts.min() < -1.8 && shifts.max() > 1.8, "shifts ${shifts.min()}..${shifts.max()}")
        assertTrue(shifts.zipWithNext().all { (a, b) -> b > a }, "shifts are not increasing")
    }

    @Test
    fun `spread draws stay inside vanilla's triangle`() {
        val samples = ArrowBallistics.spreadSamples
        assertEquals(ArrowBallistics.SPREAD_SAMPLE_COUNT, samples.size)
        assertTrue(samples.all { abs(it.x) <= ArrowBallistics.SPREAD && abs(it.y) <= ArrowBallistics.SPREAD && abs(it.z) <= ArrowBallistics.SPREAD })
        // The same list every time: the estimate must not flicker between ticks.
        assertTrue(samples === ArrowBallistics.spreadSamples)
    }

    @Test
    fun `simulate steps like vanilla`() {
        val positions = ArrayList<Vec3>()
        ArrowBallistics.simulate(fullBow, Vec3.ZERO, Vec3(3.0, 0.0, 0.0), 3) { _, _, to ->
            positions += to
            true
        }

        // move by v, then v = v * 0.99 - (0, 0.05, 0)
        assertEquals(3, positions.size)
        assertTrue(positions[0].distanceTo(Vec3(3.0, 0.0, 0.0)) < 1E-12)
        assertTrue(positions[1].distanceTo(Vec3(5.97, -0.05, 0.0)) < 1E-12)
        assertTrue(positions[2].distanceTo(Vec3(8.9103, -0.1495, 0.0)) < 1E-12)
    }

    private companion object {
        /**
         * The formula bends between ticks where vanilla's path is a straight chord: under g / 8.
         */
        const val TOLERANCE = 0.01
        const val SAMPLES_PER_TICK = 400
    }

}
