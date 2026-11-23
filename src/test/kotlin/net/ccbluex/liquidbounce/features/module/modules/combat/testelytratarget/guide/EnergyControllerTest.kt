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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * The energy controller, which owns how steeply we fly and nothing else.
 *
 * The behaviour worth pinning down is the one that makes overshoot handle itself: a surplus of energy
 * has to come out as a climb, because that is what converts the speed that would have carried us past
 * the target into height we can spend on the next pass.
 */
class EnergyControllerTest {

    private val level = Vec3(0.0, 0.0, 1.0)

    private fun input(
        ourSpeed: Double,
        ourY: Double,
        targetSpeed: Double,
        targetY: Double,
        direction: Vec3 = level,
        orbitWeight: Double = 0.0,
    ) = EnergyInput(
        velocity = Vec3(0.0, 0.0, ourSpeed),
        y = ourY,
        targetVelocity = Vec3(0.0, 0.0, targetSpeed),
        targetY = targetY,
        desiredDirection = direction,
        orbitWeight = orbitWeight,
    )

    @Test
    fun `surplus energy is converted into a climb`() {
        val decision = EnergyController.apply(
            input(ourSpeed = 3.0, ourY = 100.0, targetSpeed = 0.5, targetY = 100.0),
            EnergySettings(),
        )

        assertTrue(decision.deficit < 0.0, "the setup is meant to be energy-rich, got ${decision.deficit}")
        assertTrue(decision.direction.y > 0.0, "surplus energy must come out as a climb")
    }

    @Test
    fun `an energy deficit lowers the nose`() {
        val decision = EnergyController.apply(
            input(ourSpeed = 0.4, ourY = 100.0, targetSpeed = 2.5, targetY = 120.0),
            EnergySettings(),
        )

        assertTrue(decision.deficit > 0.0, "the setup is meant to be energy-poor, got ${decision.deficit}")
        assertTrue(decision.direction.y < 0.0, "a deficit must be answered by trading height for speed")
    }

    @Test
    fun `a rocket is only wanted when we are materially behind`() {
        val rich = EnergyController.apply(
            input(ourSpeed = 2.5, ourY = 120.0, targetSpeed = 0.5, targetY = 100.0),
            EnergySettings(),
        )
        val poor = EnergyController.apply(
            input(ourSpeed = 0.3, ourY = 90.0, targetSpeed = 2.5, targetY = 130.0),
            EnergySettings(),
        )

        assertFalse(rich.wantsBoost, "spending a rocket while already ahead on energy is waste")
        assertTrue(poor.wantsBoost, "a real deficit is exactly what a rocket is for")
    }

    @Test
    fun `the climb is capped`() {
        val settings = EnergySettings(maxClimbPitch = 20.0)
        val decision = EnergyController.apply(
            input(ourSpeed = 6.0, ourY = 100.0, targetSpeed = 0.1, targetY = 100.0),
            settings,
        )

        val maxSine = Math.sin(Math.toRadians(settings.maxClimbPitch))
        assertTrue(
            decision.direction.y <= maxSine + 1.0e-9,
            "climb exceeded the cap: ${decision.direction.y} against $maxSine"
        )
    }

    @Test
    fun `holding station suppresses the zoom climb when breaking away is not allowed`() {
        val orbiting = EnergyController.apply(
            input(ourSpeed = 3.0, ourY = 100.0, targetSpeed = 0.5, targetY = 100.0, orbitWeight = 1.0),
            EnergySettings(allowZoomClimb = false),
        )

        // Fully committed to the orbit, the energy bias is faded out entirely and the guidance
        // direction survives untouched. That is what keeps an HvH chase inside the band.
        assertEquals(0.0, orbiting.direction.y, 1.0e-9)
    }

    @Test
    fun `steering is never touched`() {
        val direction = Vec3(0.6, 0.3, -0.7).normalize()
        val decision = EnergyController.apply(
            input(ourSpeed = 3.0, ourY = 100.0, targetSpeed = 0.5, targetY = 100.0, direction = direction),
            EnergySettings(),
        )

        val before = Math.atan2(-direction.x, direction.z)
        val after = Math.atan2(-decision.direction.x, decision.direction.z)
        assertEquals(before, after, 1.0e-9, "the energy controller changed the compass heading")

        assertEquals(1.0, hypot(hypot(decision.direction.x, decision.direction.y), decision.direction.z), 1.0e-9)
    }

    @Test
    fun `switching it off returns the guidance direction verbatim`() {
        val direction = Vec3(0.2, 0.5, 0.84).normalize()
        val decision = EnergyController.apply(
            input(ourSpeed = 3.0, ourY = 100.0, targetSpeed = 0.5, targetY = 100.0, direction = direction),
            EnergySettings(enabled = false),
        )

        assertEquals(direction, decision.direction)
    }
}
