/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.point.features

import net.ccbluex.liquidbounce.utils.aiming.point.PointInsideBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class KalmanPerlinPointMotionTest {
    private val center = PointProportions(0.5, 0.5, 0.5)

    @Test
    fun `kalman reduces noisy point observation error without filtering box translation`() {
        val motion = KalmanPerlinPointMotion(42)
        val identity = Any()
        val settings = KalmanPerlinPointSettings(perlin = false, processVariance = 0.01, measurementVariance = 25.0)
        val random = Random(17)
        var rawError = 0.0
        var filteredError = 0.0
        for (tick in 1..500) {
            val observation = center.copy(x = 0.5 + random.nextDouble(-0.08, 0.08))
            val result = motion.advance(tick, identity, observation, settings)
            if (tick > 30) {
                rawError += (observation.x - 0.5) * (observation.x - 0.5)
                filteredError += (result.x - 0.5) * (result.x - 0.5)
            }
        }
        assertTrue(filteredError < rawError * 0.6, "Filtered MSE $filteredError vs raw $rawError")
    }

    @Test
    fun `perlin wanders continuously within amplitude and starts without a noise jump`() {
        val motion = KalmanPerlinPointMotion(12)
        val identity = Any()
        val settings = KalmanPerlinPointSettings(kalman = false)
        assertEquals(center, motion.advance(1, identity, center, settings))
        var previous = center
        var largest = 0.0
        for (tick in 2..2000) {
            val point = motion.advance(tick, identity, center, settings)
            assertTrue(abs(point.x - 0.5) <= settings.horizontalAmplitude + 1e-12)
            assertTrue(abs(point.y - 0.5) <= settings.verticalAmplitude + 1e-12)
            assertTrue(abs(point.z - 0.5) <= settings.horizontalAmplitude + 1e-12)
            assertTrue(abs(point.x - previous.x) < 0.02)
            assertTrue(abs(point.y - previous.y) < 0.02)
            assertTrue(abs(point.z - previous.z) < 0.02)
            largest = maxOf(largest, abs(point.x - 0.5))
            previous = point
        }
        assertTrue(largest > 0.02)
    }

    @Test
    fun `point stays inside the margin through large jumps and maximum configured noise`() {
        val motion = KalmanPerlinPointMotion(7)
        val identity = Any()
        val settings = KalmanPerlinPointSettings(horizontalAmplitude = 0.3, verticalAmplitude = 0.3, rate = 0.25)
        val random = Random(71)
        repeat(3000) { tick ->
            val raw = PointProportions(random.nextDouble(), random.nextDouble(), random.nextDouble())
            val result = motion.advance(tick, identity, raw, settings)
            assertTrue(result.x in 0.05..0.95)
            assertTrue(result.y in 0.05..0.95)
            assertTrue(result.z in 0.05..0.95)
        }
    }

    @Test
    fun `current box translation and resize reconstruct the held proportions without world lag`() {
        val original = AABB(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
        val initial = PointInsideBox(Vec3(0.15, 1.35, 0.3), original)
        val proportions = initial.proportions()
        assertEquals(PointProportions(0.25, 0.75, 0.5), proportions)
        val moved = AABB(20.0, -3.0, 10.0, 21.2, -2.0, 10.6)
        val rebuilt = moved.pointAt(proportions)
        assertSame(moved, rebuilt.box)
        assertEquals(20.3, rebuilt.pos.x, 1e-12)
        assertEquals(-2.25, rebuilt.pos.y, 1e-12)
        assertEquals(10.3, rebuilt.pos.z, 1e-12)
        val flat = AABB(2.0, 3.0, 4.0, 2.0, 3.0, 4.0)
        assertEquals(center, PointInsideBox(Vec3(2.0, 3.0, 4.0), flat).proportions())
        assertEquals(Vec3(2.0, 3.0, 4.0), flat.pointAt(center).pos)
    }

    @Test
    fun `other target probes and repeated calls do not consume the selected target state`() {
        val motion = KalmanPerlinPointMotion(42)
        val baseline = KalmanPerlinPointMotion(42)
        val selected = Any()
        val other = Any()
        val settings = KalmanPerlinPointSettings()
        for (tick in 1..100) {
            val observation = center.copy(x = 0.5 + tick * 0.001)
            val expected = baseline.advance(tick, selected, observation, settings)
            val actual = motion.advance(tick, selected, observation, settings)
            assertEquals(expected, actual)
            baseline.advance(tick, other, center, settings)
            repeat(5) { motion.advance(tick, other, center.copy(x = 0.9), settings) }
            assertEquals(actual, motion.advance(tick, selected, center.copy(x = 0.1), settings))
        }
    }

    @Test
    fun `equal objects with different identities never inherit another target velocity`() {
        class Target {
            override fun equals(other: Any?) = other is Target
            override fun hashCode() = 1
        }
        val motion = KalmanPerlinPointMotion(9)
        val first = Target()
        val second = Target()
        val settings = KalmanPerlinPointSettings(perlin = false)
        for (tick in 1..15) motion.advance(tick, first, center.copy(x = 0.3 + tick * 0.02), settings)
        val newPoint = center.copy(x = 0.8)
        assertEquals(newPoint, motion.advance(16, second, newPoint, settings))
    }

    @Test
    fun `skipped ticks backwards clocks inactivity and explicit reset discard history`() {
        val settings = KalmanPerlinPointSettings(perlin = false)
        for (nextTick in listOf(30, 5)) {
            val motion = KalmanPerlinPointMotion(8)
            val identity = Any()
            for (tick in 1..20) motion.advance(tick, identity, center.copy(x = 0.3 + tick * 0.01), settings)
            assertEquals(center, motion.advance(nextTick, identity, center, settings))
            motion.reset()
            assertEquals(center, motion.advance(nextTick, identity, center, settings))
        }
        val motion = KalmanPerlinPointMotion(8)
        val identity = Any()
        for (tick in 1..20) motion.advance(tick, identity, center.copy(x = 0.3 + tick * 0.01), settings)
        motion.advance(21, Any(), center, settings)
        assertEquals(center, motion.advance(22, identity, center, settings))
    }

    @Test
    fun `both subfeatures off preserve the exact observation including edges and reset history`() {
        val motion = KalmanPerlinPointMotion(18)
        val identity = Any()
        val settings = KalmanPerlinPointSettings(perlin = false)
        for (tick in 1..10) motion.advance(tick, identity, center.copy(x = 0.2 + tick * 0.03), settings)
        val edge = PointProportions(0.0, 1.0, 0.0)
        assertSame(edge, motion.advance(11, identity, edge, settings.copy(kalman = false)))
        assertEquals(center, motion.advance(11, identity, center, settings))
    }

    @Test
    fun `changing settings restarts filtering even in the same tick`() {
        val motion = KalmanPerlinPointMotion(18)
        val identity = Any()
        val on = KalmanPerlinPointSettings(perlin = false)
        for (tick in 1..10) motion.advance(tick, identity, center.copy(x = 0.2 + tick * 0.03), on)
        val observation = center.copy(x = 0.6)
        assertEquals(observation, motion.advance(10, identity, observation, on.copy(kalman = false)))
        assertEquals(observation, motion.advance(10, identity, observation, on))
    }

    @Test
    fun `kalman lag relative to the incoming point is bounded`() {
        val motion = KalmanPerlinPointMotion(42)
        val identity = Any()
        val settings = KalmanPerlinPointSettings(perlin = false, maxLag = 0.03, measurementVariance = 25.0)
        for (tick in 1..1000) {
            val raw = center.copy(x = if (tick % 2 == 0) 0.3 else 0.7)
            val result = motion.advance(tick, identity, raw, settings)
            assertTrue(abs(result.x - raw.x) <= settings.maxLag + 1e-12)
        }
    }

    @Test
    fun `more than 64 candidate probes never evict a result already resolved in this tick`() {
        val motion = KalmanPerlinPointMotion(12)
        val baseline = KalmanPerlinPointMotion(12)
        val identity = Any()
        val settings = KalmanPerlinPointSettings()
        for (tick in 1..10) {
            assertEquals(baseline.advance(tick, identity, center, settings),
                motion.advance(tick, identity, center, settings))
        }
        val cached = motion.advance(10, identity, center, settings)
        repeat(100) { motion.advance(10, Any(), center, settings) }
        assertEquals(cached, motion.advance(10, identity, center.copy(x = 0.9), settings))
        assertEquals(baseline.advance(11, identity, center, settings),
            motion.advance(11, identity, center, settings))
    }

    @Test
    fun `invalid scripted settings cannot throw or poison the point with nonfinite coordinates`() {
        val motion = KalmanPerlinPointMotion(19)
        val identity = Any()
        val invalid = KalmanPerlinPointSettings(
            processVariance = Double.NaN, measurementVariance = -1.0, maxLag = -0.2,
            horizontalAmplitude = Double.POSITIVE_INFINITY, verticalAmplitude = -2.0,
            rate = Double.NaN, edgeMargin = Double.NEGATIVE_INFINITY,
        )
        repeat(100) { tick ->
            val point = motion.advance(tick, identity, center.copy(x = if (tick % 2 == 0) 0.2 else 0.8), invalid)
            assertTrue(point.x in 0.05..0.95 && point.y in 0.05..0.95 && point.z in 0.05..0.95)
        }
    }

    @Test
    fun `face wandering stays strictly inside margin and avoids clipped plateaus`() {
        for (margin in listOf(0.05, 0.49)) {
        val motion = KalmanPerlinPointMotion(12)
        val identity = Any()
        val settings = KalmanPerlinPointSettings(kalman = false, edgeMargin = margin)
        val face = PointProportions(0.0, 1.0, 0.0)
        var previous = motion.advance(1, identity, face, settings)
        var same = 0
        val values = mutableSetOf<Double>()
        for (tick in 2..2000) {
            val point = motion.advance(tick, identity, face, settings)
            assertTrue(point.x > settings.edgeMargin && point.x < 1.0 - settings.edgeMargin)
            assertTrue(point.y > settings.edgeMargin && point.y < 1.0 - settings.edgeMargin)
            if (point.x == previous.x) same++
            values += point.x
            previous = point
        }
        assertTrue(same < 5, "Clipped plateaus: $same")
        assertTrue(values.size > 1900)
        }
    }
}
