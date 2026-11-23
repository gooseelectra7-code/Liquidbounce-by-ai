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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The three regimes a target can be in, and the one switch that turns leading off. */
class TargetMotionTest {

    private fun snapshot(
        velocity: Vec3,
        gliding: Boolean = false,
        onGround: Boolean = false,
        boostTicks: Int = 0,
        yaw: Float = 0f,
        pitch: Float = 0f,
    ) = TargetSnapshot(
        position = Vec3.ZERO,
        velocity = velocity,
        height = 1.8,
        yaw = yaw,
        pitch = pitch,
        gliding = gliding,
        onGround = onGround,
        boostTicks = boostTicks,
    )

    @Test
    fun `a running target keeps running when sprint is assumed`() {
        val frames = TargetMotion.predict(
            snapshot(Vec3(0.0, 0.0, 0.28), onGround = true),
            ticks = 20,
            assumeSprint = true,
        )

        assertEquals(0.28, frames.last().velocity.z, 1.0e-9)
        assertEquals(0.28 * 20, frames.last().position.z, 1.0e-9)
    }

    @Test
    fun `a target left to coast comes to a stop`() {
        val frames = TargetMotion.predict(
            snapshot(Vec3(0.0, 0.0, 0.28), onGround = true),
            ticks = 40,
            assumeSprint = false,
        )

        assertEquals(0.0, frames.last().velocity.length(), 1.0e-9)
    }

    @Test
    fun `an airborne target follows the vanilla air rule`() {
        val start = Vec3(0.3, 0.4, 0.0)
        val frames = TargetMotion.predict(snapshot(start), ticks = 1)

        val expectedY = (start.y - WingPhysics.DEFAULT_GRAVITY) * 0.98
        assertEquals(expectedY, frames[0].velocity.y, 1.0e-12)
        assertEquals(start.x * 0.91, frames[0].velocity.x, 1.0e-12)
    }

    @Test
    fun `a boosting glider outruns the same glider without a rocket`() {
        val start = Vec3(0.0, 0.0, 1.0)

        val plain = TargetMotion.predict(snapshot(start, gliding = true), ticks = 20)
        val boosted = TargetMotion.predict(snapshot(start, gliding = true, boostTicks = 10), ticks = 20)

        assertTrue(
            boosted.last().position.length() > plain.last().position.length(),
            "a rocket must move the prediction further out"
        )
    }

    @Test
    fun `the boost stops when the rocket is spent`() {
        val frames = TargetMotion.predict(
            snapshot(Vec3(0.0, 0.0, 1.0), gliding = true, boostTicks = 5),
            ticks = 20,
        )

        val duringBoost = frames[4].velocity.length() - frames[3].velocity.length()
        val afterBoost = frames[10].velocity.length() - frames[9].velocity.length()

        assertTrue(duringBoost > 0.0, "speed must still be climbing while the rocket burns")
        assertTrue(afterBoost < duringBoost, "the push must stop once the rocket is out")
    }

    @Test
    fun `turning leading off stops the aim point running ahead`() {
        val moving = snapshot(Vec3(0.0, 0.0, 1.0), gliding = true)

        val led = TargetMotion.predict(moving, ticks = 10, lead = true)
        val frozen = TargetMotion.predict(moving, ticks = 10, lead = false)

        // The switch zeroes the velocity rather than widening any tolerance, so the rest of the
        // pipeline behaves identically and only the aim point changes. A glider still sinks and
        // converts that into a little forward speed, which is vanilla, not leading.
        assertTrue(
            frozen.last().position.z < led.last().position.z * 0.25,
            "unled prediction still ran to ${frozen.last().position.z} against ${led.last().position.z}"
        )
    }
}
