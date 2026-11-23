/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class Test2AimMathTest {
    @Test
    fun `kalman estimates constant angular velocity through noisy observations`() {
        val filter = Test2KalmanAxis(circular = false)
        filter.reset(0.0, 1.0)
        val random = Random(12)
        var rawError = 0.0
        var filteredError = 0.0
        for (tick in 1..500) {
            val truth = tick * 0.4
            val measurement = truth + random.nextDouble(-2.0, 2.0)
            filter.update(measurement, processVariance = 0.01, measurementVariance = 1.0)
            if (tick > 30) {
                rawError += (measurement - truth) * (measurement - truth)
                filteredError += (filter.angle - truth) * (filter.angle - truth)
            }
        }
        assertTrue(filteredError < rawError * 0.8, "Filter MSE $filteredError vs raw $rawError")
        assertEquals(0.4, filter.velocity, 0.3)
    }

    @Test
    fun `kalman yaw crosses the wrap continuously while pitch keeps the pole direction`() {
        val yaw = Test2KalmanAxis(circular = true)
        yaw.reset(179.0, 0.25)
        yaw.update(-179.0, 0.5, 0.25)
        assertTrue(yaw.angle in 179.0..181.0)
        assertTrue(yaw.velocity > 0.0)

        val pitch = Test2KalmanAxis(circular = false)
        pitch.reset(-90.0, 0.25)
        pitch.update(90.0, 0.5, 0.25)
        assertEquals(90.0, pitch.angle, 1e-9)
        assertEquals(0.0, pitch.velocity, 1e-9)
    }

    @Test
    fun `kalman remains finite over a long noisy tracking run and resets on a jump`() {
        val filter = Test2KalmanAxis(circular = true)
        filter.reset(0.0, 4.0)
        val random = Random(91)
        repeat(20_000) { tick ->
            filter.update(test2YawError(tick * 0.2 + random.nextDouble(-1.0, 1.0)), 0.01, 4.0)
            assertTrue(filter.angle.isFinite() && filter.velocity.isFinite())
        }
        val jumped = filter.angle + 100.0
        filter.update(test2YawError(jumped), 0.01, 4.0)
        assertEquals(jumped, filter.angle, 1e-6)
        assertEquals(0.0, filter.velocity, 1e-9)
    }

    @Test
    fun `perlin is seeded bounded nonconstant and continuous across negative and positive cells`() {
        val noise = Test2PerlinNoise(42)
        val same = Test2PerlinNoise(42)
        var largest = 0.0
        for (i in -1000..1000) {
            val x = i * 0.0137
            val value = noise.sample(x)
            assertEquals(value, same.sample(x), 0.0)
            assertTrue(value in -1.0..1.0)
            largest = maxOf(largest, abs(value))
        }
        assertTrue(largest > 0.1)
        for (cell in -10..10) {
            assertEquals(0.0, noise.sample(cell.toDouble()), 1e-12)
            val left = noise.sample(cell - 1e-5)
            val right = noise.sample(cell + 1e-5)
            assertTrue(abs(left - right) < 0.0001)
            // Gradient noise has a nonzero, matching derivative at a lattice point.
            assertEquals(-left / 1e-5, right / 1e-5, 1e-4)
            assertTrue(abs(right / 1e-5) > 1.0)
        }
    }

    @Test
    fun `controller smoothly accelerates and brakes momentum on reversal`() {
        val controller = Test2AxisController()
        var previous = 0.0
        val changes = mutableListOf<Double>()
        repeat(20) {
            val step = controller.step(100.0, 20.0, 5.0, 0.5, 0.15)
            assertTrue(step in 0.0..20.0)
            assertTrue(abs(step - previous) <= 5.0 + 1e-9)
            changes += step - previous
            previous = step
        }
        assertTrue(changes.distinct().size > 5, "Acceleration must vary rather than remain a linear ramp")
        val reversed = controller.step(-100.0, 20.0, 5.0, 0.5, 0.15)
        assertTrue(reversed > 0.0, "Reversal must brake existing momentum")
        assertTrue(abs(reversed - previous) <= 5.0 + 1e-9)
        repeat(30) { previous = controller.step(-100.0, 20.0, 5.0, 0.5, 0.15) }
        assertTrue(previous < 0.0)
    }

    @Test
    fun `motion does not consume another filter or noise step twice in the same tick`() {
        val motion = Test2AimMotion(42)
        val identity = Any()
        val current = Test2Angles(0.0, 0.0)
        val target = Test2Angles(10.0, 5.0)
        val settings = Test2Settings()
        val first = motion.advance(10, identity, current, target, settings)
        assertEquals(first, motion.advance(10, identity, first, Test2Angles(20.0, 8.0), settings))
        val baseline = Test2AimMotion(42)
        baseline.advance(10, identity, current, target, settings)
        assertEquals(
            baseline.advance(11, identity, first, target, settings),
            motion.advance(11, identity, first, target, settings)
        )
    }

    @Test
    fun `tick gaps and backwards clocks discard the old engagement`() {
        val settings = Test2Settings(perlin = false)
        for (nextTick in listOf(30, 5)) {
            val motion = Test2AimMotion(8)
            val identity = Any()
            var current = Test2Angles(0.0, 0.0)
            for (tick in 1..20) {
                current = motion.advance(tick, identity, current, Test2Angles(tick * 3.0, 10.0), settings)
            }
            val newIdentity = identity
            val target = Test2Angles(-100.0, -60.0)
            assertEquals(
                Test2AimMotion(8).advance(nextTick, newIdentity, current, target, settings),
                motion.advance(nextTick, newIdentity, current, target, settings)
            )
        }
    }

    @Test
    fun `kalman can be switched off and back on without inheriting stale velocity`() {
        val motion = Test2AimMotion(8)
        val baseline = Test2AimMotion(8)
        val identity = Any()
        var current = Test2Angles(0.0, 0.0)
        val on = Test2Settings(perlin = false)
        for (tick in 1..20) {
            baseline.advance(tick, identity, current, Test2Angles(tick * 2.0, 0.0), on)
            current = motion.advance(tick, identity, current, Test2Angles(tick * 2.0, 0.0), on)
        }
        val off = on.copy(kalman = false)
        for (tick in 21..30) {
            baseline.advance(tick, identity, current, Test2Angles(40.0, 0.0), off)
            current = motion.advance(tick, identity, current, Test2Angles(40.0, 0.0), off)
        }
        val enabled = motion.advance(31, identity, current, Test2Angles(40.0, 0.0), on)
        assertEquals(baseline.advance(31, identity, current, Test2Angles(40.0, 0.0), off), enabled)
    }

    @Test
    fun `motion keeps yaw continuous and pitch legal for moving and extreme targets`() {
        val motion = Test2AimMotion(71)
        val identity = Any()
        val settings = Test2Settings(yawSpeed = 120.0, pitchSpeed = 90.0, yawNoise = 2.0, pitchNoise = 1.0)
        val random = Random(17)
        var current = Test2Angles(179.0, -90.0)
        for (tick in 1..2000) {
            val target = Test2Angles(test2YawError(180.0 + tick * 0.8), random.nextDouble(-120.0, 120.0))
            val next = motion.advance(tick, identity, current, target, settings)
            assertTrue(next.yaw.isFinite() && next.pitch.isFinite())
            assertTrue(abs(next.yaw - current.yaw) <= settings.yawSpeed + 1e-9)
            assertTrue(abs(next.pitch - current.pitch) <= settings.pitchSpeed + 1e-9)
            assertTrue(next.pitch in -90.0..90.0)
            current = next
        }
        assertTrue(current.yaw > 360.0, "Output yaw must not be wrapped to +/-180")
    }

    @Test
    fun `hand back converges through poles and yaw wrap within configured count and speed bounds`() {
        for ((start, goal) in listOf(
            Test2Angles(179.0, -90.0) to Test2Angles(-179.0, 90.0),
            Test2Angles(-179.0, 90.0) to Test2Angles(179.0, -90.0),
            Test2Angles(900.0, 0.1) to Test2Angles(180.001, 0.1001),
        )) {
            var current = start
            val motion = Test2AimMotion(12)
            val settings = Test2Settings(yawSpeed = 7.0, pitchSpeed = 4.0, acceleration = 1.0)
            repeat(1000) { tick ->
                val next = motion.advanceReturn(tick, current, goal, settings, 0.15)
                assertTrue(abs(next.yaw - current.yaw) <= settings.yawSpeed + 1e-9)
                assertTrue(abs(next.pitch - current.pitch) <= settings.pitchSpeed + 1e-9)
                for (delta in listOf(next.yaw - current.yaw, next.pitch - current.pitch)) {
                    // Exact endpoints and physical pitch boundaries can leave a partial count.
                    if (abs(delta) > 0.075 && abs(next.pitch) != 90.0) {
                        assertEquals(kotlin.math.round(delta / 0.15), delta / 0.15, 1e-8)
                    }
                }
                assertTrue(next.pitch in -90.0..90.0)
                current = next
            }
            assertEquals(0.0, test2YawError(goal.yaw - current.yaw), 1e-9)
            assertEquals(goal.pitch, current.pitch, 1e-9)
        }
    }

    @Test
    fun `target switch and engagement return preserve bounded emitted acceleration`() {
        for (returning in listOf(false, true)) {
            val motion = Test2AimMotion(19)
            val settings = Test2Settings(kalman = false, perlin = false, acceleration = 1.0)
            val identity = Any()
            var current = Test2Angles(0.0, 0.0)
            var previous = Test2Angles(0.0, 0.0)
            for (tick in 1..10) {
                previous = current
                current = motion.advance(tick, identity, current, Test2Angles(150.0, 70.0), settings, 0.15)
            }
            val lastYaw = current.yaw - previous.yaw
            val lastPitch = current.pitch - previous.pitch
            val goal = Test2Angles(-60.0, -60.0)
            val next = if (returning) motion.advanceReturn(11, current, goal, settings, 0.15)
                else motion.advance(11, Any(), current, goal, settings, 0.15)
            assertTrue(abs(next.yaw - current.yaw - lastYaw) <= 1.0 + 1e-9)
            assertTrue(abs(next.pitch - current.pitch - lastPitch) <= 1.0 + 1e-9)
            assertTrue(next.yaw > current.yaw, "Existing momentum should survive the switch")
        }
    }

    @Test
    fun `low acceleration on coarse grid never emits an empty count interval`() {
        val controller = Test2AxisController()
        var previous = 0.0
        repeat(2000) { tick ->
            val step = controller.step(if (tick % 60 < 30) 100.0 else -100.0, 1.0, 0.5, 1.0, 0.6144)
            assertTrue(abs(step) <= 1.0)
            assertTrue(abs(step - previous) <= 0.6144 + 1e-9)
            assertEquals(kotlin.math.round(step / 0.6144), step / 0.6144, 1e-9)
            previous = step
        }
    }

    @Test
    fun `scripted invalid settings are sanitized and return remains finite`() {
        val motion = Test2AimMotion(12)
        val settings = Test2Settings(yawSpeed = -1.0, pitchSpeed = Double.NaN,
            acceleration = Double.POSITIVE_INFINITY, response = -3.0, noiseRate = Double.NaN)
        var current = Test2Angles(0.0, 0.0)
        repeat(100) { tick ->
            current = motion.advanceReturn(tick, current, Test2Angles(50.0, 30.0), settings, 0.15)
            assertTrue(current.yaw.isFinite() && current.pitch.isFinite())
        }
    }

    @Test
    fun `return releases exactly with float emission across settings and sensitivity extremes`() {
        for (speed in listOf(1.0, 45.0, 120.0)) {
            for (acceleration in listOf(0.5, 10.0, 30.0)) {
                for (response in listOf(0.1, 0.45, 1.0)) {
                    for (quantum in listOf(0.0096, 0.15, 0.6144)) {
                        val settings = Test2Settings(yawSpeed = speed, pitchSpeed = minOf(speed, 90.0),
                            acceleration = acceleration, response = response)
                        val motion = Test2AimMotion(12)
                        val target = Test2Angles(-179.123f.toDouble(), 85.789f.toDouble())
                        var current = Test2Angles(179.0, -88.0)
                        repeat(4000) { tick ->
                            val step = motion.advanceReturn(tick, current, target, settings, quantum)
                            current = Test2Angles(step.yaw.toFloat().toDouble(), step.pitch.toFloat().toDouble())
                        }
                        val rotation = net.ccbluex.liquidbounce.utils.aiming.data.Rotation(
                            current.yaw.toFloat(), current.pitch.toFloat())
                        val goal = net.ccbluex.liquidbounce.utils.aiming.data.Rotation(
                            target.yaw.toFloat(), target.pitch.toFloat())
                        assertEquals(0f, rotation.angleTo(goal), "Return stuck: $settings, quantum=$quantum, $current")
                    }
                }
            }
        }
    }

    @Test
    fun `large accumulated float yaw still returns with fine sensitivity`() {
        val motion = Test2AimMotion(12)
        val settings = Test2Settings(response = 0.1, acceleration = 0.5)
        val target = Test2Angles(0.0, 0.0)
        var current = Test2Angles(262439.96875, 0.0)
        repeat(4000) { tick ->
            val step = motion.advanceReturn(tick, current, target, settings, 0.0096)
            current = Test2Angles(step.yaw.toFloat().toDouble(), step.pitch.toFloat().toDouble())
        }
        assertEquals(0.0, test2YawError(target.yaw - current.yaw), 1e-9)
    }

    @Test
    fun `resolution coarsening retains an available count below the speed limit`() {
        val motion = Test2AimMotion(12)
        val settings = Test2Settings(yawSpeed = 1.0)
        val target = Test2Angles(0.0, 0.0)
        var current = Test2Angles(8388608.0, 0.0)
        repeat(4000) { tick ->
            val step = motion.advanceReturn(tick, current, target, settings, 0.6144)
            val emitted = step.yaw.toFloat().toDouble()
            assertTrue(abs(emitted - current.yaw) <= 1.0)
            current = Test2Angles(emitted, step.pitch.toFloat().toDouble())
        }
        assertEquals(0.0, test2YawError(target.yaw - current.yaw), 1e-9)
    }

    @Test
    fun `already arrived angles take one tick including equivalent yaw representations`() {
        val current = Test2Angles(179.0, -90.0)
        assertEquals(1, Test2AimMotion.estimateTicks(current, current, Test2Settings()))
        assertEquals(1, Test2AimMotion.estimateTicks(current, Test2Angles(-181.0, -90.0), Test2Settings()))
        assertEquals(1, Test2AimMotion.estimateTicks(current, Test2Angles(179.1, -89.9), Test2Settings()))
    }

    @Test
    fun `timing estimates cannot consume the live engagement state`() {
        val motion = Test2AimMotion(12)
        val baseline = Test2AimMotion(12)
        val identity = Any()
        val target = Test2Angles(80.0, 40.0)
        val settings = Test2Settings()
        var current = Test2Angles(0.0, 0.0)
        for (tick in 1..50) {
            repeat(5) {
                assertTrue(Test2AimMotion.estimateTicks(current, target, settings) in 1..200)
            }
            val next = motion.advance(tick, identity, current, target, settings)
            assertEquals(baseline.advance(tick, identity, current, target, settings), next)
            current = next
        }
    }
}
