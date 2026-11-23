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

import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.TargetFrame
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The veto.
 *
 * A guidance law that knows only about the target will fly a flawless intercept into a hillside, so
 * the point of these tests is that the filter actually stops it, and that it stays out of the way
 * when there is nothing to stop.
 */
class FeasibilityTest {

    /** A floor at a fixed height, which is all the terrain any of this needs. */
    private fun floorAt(y: Double) = TerrainProbe { point -> point.y - y }

    private val openSky = TerrainProbe { Double.MAX_VALUE }

    private fun frames(position: Vec3, velocity: Vec3 = Vec3.ZERO, count: Int = 40) =
        (1..count).map { TargetFrame(position.add(velocity.scale(it.toDouble())), velocity) }

    private fun input(
        eye: Vec3,
        velocity: Vec3,
        target: Vec3,
        targetVelocity: Vec3 = Vec3.ZERO,
    ) = FeasibilityInput(
        eye = eye,
        velocity = velocity,
        targetFrames = frames(target, targetVelocity),
        targetAimHeight = 1.8,
        minReach = 0.0,
        maxReach = 4.0,
    )

    private val settings = FeasibilitySettings(horizon = 40, minAltitude = 4.0)

    @Test
    fun `a clear path is left alone`() {
        val commanded = Rotation(0f, 0f)
        val result = Feasibility.resolve(
            commanded,
            from = commanded,
            input = input(Vec3(0.0, 100.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 100.0, 30.0)),
            settings = settings,
            probe = openSky,
        )

        assertTrue(result.safe)
        assertEquals(commanded, result.rotation, "a safe command must be passed through untouched")
    }

    @Test
    fun `a dive into the floor is refused and replaced`() {
        val commanded = Rotation(0f, 70f)
        val eye = Vec3(0.0, 20.0, 0.0)

        val direct = Feasibility.rollout(
            commanded,
            from = commanded,
            input = input(eye, Vec3(0.0, -0.5, 1.0), Vec3(0.0, 1.0, 25.0)),
            settings = settings,
            probe = floorAt(0.0),
        )
        assertFalse(direct.safe, "a steep dive from twenty blocks up must not pass the filter")

        val resolved = Feasibility.resolve(
            commanded,
            from = commanded,
            input = input(eye, Vec3(0.0, -0.5, 1.0), Vec3(0.0, 1.0, 25.0)),
            settings = settings,
            probe = floorAt(0.0),
        )

        assertTrue(resolved.safe, "the filter found no way out of a recoverable dive")
        assertTrue(
            resolved.rotation.pitch < commanded.pitch,
            "the recovery must pull the nose up, went from ${commanded.pitch} to ${resolved.rotation.pitch}"
        )
    }

    @Test
    fun `the void counts as terrain`() {
        val commanded = Rotation(0f, 60f)
        val result = Feasibility.rollout(
            commanded,
            from = commanded,
            input = input(Vec3(0.0, -50.0, 0.0), Vec3(0.0, -1.5, 0.5), Vec3(0.0, -60.0, 20.0)),
            settings = settings,
            probe = openSky,
        )

        assertFalse(result.safe, "falling out of the world must be rejected like any other crash")
    }

    @Test
    fun `time in the band is counted, not just arrival`() {
        // Parked right beside a stationary target: every rolled tick is a tick in reach.
        val commanded = Rotation(0f, 0f)
        val result = Feasibility.rollout(
            commanded,
            from = commanded,
            input = input(Vec3(0.0, 100.0, 0.0), Vec3.ZERO, Vec3(0.0, 99.0, 2.0)),
            settings = settings,
            probe = openSky,
        )

        assertTrue(result.ticksInBand > 0, "a target sitting inside reach must register as in reach")
        assertEquals(0, result.ticksToBand, "and it must register from the very first tick")
    }

    @Test
    fun `a rocket is only endorsed when it shortens the intercept`() {
        val commanded = Rotation(0f, 0f)
        val far = input(Vec3(0.0, 100.0, 0.0), Vec3(0.0, 0.0, 0.4), Vec3(0.0, 100.0, 34.0))

        val helps = Feasibility.boostPaysOff(
            commanded, commanded, far, settings, openSky, boostTicks = 15, gainTicks = 4,
        )
        val pointless = Feasibility.boostPaysOff(
            commanded, commanded, far, settings, openSky, boostTicks = 0, gainTicks = 4,
        )

        assertTrue(helps, "closing a long gap faster is exactly what a rocket should be spent on")
        assertFalse(pointless, "a boost of no ticks cannot pay for itself")
    }

    @Test
    fun `a rocket is endorsed on a chase that would never arrive at all`() {
        // The case that actually matters, and the one the first version got wrong: past a certain
        // range neither future reaches the band inside the horizon, so demanding an earlier arrival
        // refuses every rocket at exactly the distance rockets exist for. Progress is the test
        // instead.
        val commanded = Rotation(0f, 0f)
        val distant = input(Vec3(0.0, 100.0, 0.0), Vec3(0.0, 0.0, 0.5), Vec3(0.0, 100.0, 300.0))

        val plain = Feasibility.rollout(commanded, commanded, distant, settings, openSky)
        val boosted = Feasibility.rollout(commanded, commanded, distant, settings, openSky, boostTicks = 15)

        assertEquals(Rollout.NEVER, plain.ticksToBand, "the setup is meant to be out of reach entirely")
        assertEquals(Rollout.NEVER, boosted.ticksToBand, "and to stay out of reach even with a rocket")
        assertTrue(
            boosted.closestApproach < plain.closestApproach,
            "a rocket must at least close the gap: ${boosted.closestApproach} against ${plain.closestApproach}"
        )
        assertTrue(
            Feasibility.boostPaysOff(commanded, commanded, distant, settings, openSky, 15, 4),
            "closing a gap this large is precisely what a rocket should be spent on"
        )
    }

    @Test
    fun `a rocket is refused when it closes nothing`() {
        // Flying away from the target: the boost makes the miss worse, not better.
        val commanded = Rotation(0f, 0f)
        val behind = input(Vec3(0.0, 100.0, 0.0), Vec3(0.0, 0.0, 0.5), Vec3(0.0, 100.0, -300.0))

        assertFalse(
            Feasibility.boostPaysOff(commanded, commanded, behind, settings, openSky, 15, 4),
            "a boost pointing away from the target cannot pay for itself"
        )
    }
}
