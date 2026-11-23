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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.drive

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * Station keeping in the holonomic mode.
 *
 * The behaviour under test is the one the first version did not have at all: the ability to slow
 * down. ElytraFly's Vanilla mode flies at a flat speed, and an aircraft that only knows one speed
 * can do nothing but fly past whatever it is chasing, so holding position has to come from
 * commanding a velocity rather than a direction.
 */
class HolonomicDriverTest {

    private val maxHorizontal = 1.0
    private val maxVertical = 0.5

    private fun velocityAt(
        range: Double,
        standoff: Double = 1.2,
        targetVelocity: Vec3 = Vec3.ZERO,
        direction: Vec3 = Vec3(0.0, 0.0, 1.0),
    ) = HolonomicDriver.stationKeepingVelocity(
        direction = direction,
        range = range,
        standoffRange = standoff,
        targetVelocity = targetVelocity,
        maxHorizontal = maxHorizontal,
        maxVertical = maxVertical,
        verticalGain = 1.0,
    )

    private fun horizontalSpeed(v: Vec3) = hypot(v.x, v.z)

    @Test
    fun `a distant target is chased flat out`() {
        val velocity = velocityAt(range = 60.0)
        assertEquals(maxHorizontal, horizontalSpeed(velocity), 1.0e-9)
    }

    @Test
    fun `speed falls away as the standoff is reached`() {
        val far = horizontalSpeed(velocityAt(range = 20.0))
        val closing = horizontalSpeed(velocityAt(range = 4.0))
        val arrived = horizontalSpeed(velocityAt(range = 1.2))

        assertTrue(far > closing, "speed must come off on the approach: $far then $closing")
        assertTrue(closing > arrived, "and keep coming off: $closing then $arrived")
        assertEquals(0.0, arrived, 1.0e-9, "at the standoff there is nowhere left to go")
    }

    @Test
    fun `holding station on a moving target means matching its velocity`() {
        val targetVelocity = Vec3(0.0, 0.0, 0.6)
        val velocity = velocityAt(range = 1.2, targetVelocity = targetVelocity)

        // Sitting exactly at the standoff, the correction is zero and what is left is the target's
        // own velocity: we fly alongside instead of past.
        assertEquals(targetVelocity.z, velocity.z, 1.0e-9)
        assertEquals(0.0, velocity.x, 1.0e-9)
    }

    @Test
    fun `the speed budget is never exceeded`() {
        val fast = Vec3(0.0, 0.0, 5.0)
        val velocity = velocityAt(range = 40.0, targetVelocity = fast)

        assertTrue(
            horizontalSpeed(velocity) <= maxHorizontal + 1.0e-9,
            "a fast target must not talk us past the speed ElytraFly is set to"
        )
    }

    @Test
    fun `the climb rate is clamped to the configured vertical speed`() {
        val climbing = velocityAt(range = 40.0, direction = Vec3(0.0, 1.0, 0.0), targetVelocity = Vec3(0.0, 3.0, 0.0))
        val diving = velocityAt(range = 40.0, direction = Vec3(0.0, -1.0, 0.0), targetVelocity = Vec3(0.0, -3.0, 0.0))

        assertEquals(maxVertical, climbing.y, 1.0e-9)
        assertEquals(-maxVertical, diving.y, 1.0e-9)
    }

    @Test
    fun `being too close is answered by backing off, not by stopping`() {
        // Inside the standoff the guidance direction points outwards, and the correction has to
        // carry us that way rather than merely zeroing the speed.
        val outward = Vec3(0.0, 0.0, -1.0)
        val velocity = velocityAt(range = 0.2, direction = outward)

        assertTrue(velocity.z < 0.0, "commanded velocity still heads towards the target while too close")
    }
}
