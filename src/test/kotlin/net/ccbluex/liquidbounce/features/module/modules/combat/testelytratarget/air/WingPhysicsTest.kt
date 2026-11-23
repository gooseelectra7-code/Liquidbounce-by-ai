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
import kotlin.math.abs

/**
 * Invariants of the elytra model.
 *
 * These are properties, not fixtures. There is no headless vanilla entity to compare against, so a
 * table of expected numbers would only be asserting that the code still equals itself; what can be
 * checked honestly is that the model behaves like a wing — it sinks when level, trades height for
 * speed when pointed down, gains speed from a boost, and does not care which compass direction any
 * of that happens in.
 */
class WingPhysicsTest {

    private fun look(yaw: Float, pitch: Float) = WingPhysics.look(yaw, pitch)

    @Test
    fun `level flight sinks and picks up speed`() {
        var velocity = Vec3(0.0, 0.0, 0.6)
        val look = look(0f, 0f)

        var altitude = 0.0
        var previousHorizontal = velocity.horizontalDistance()
        var gainedSpeed = false

        repeat(40) {
            velocity = WingPhysics.step(velocity, look, 0f)
            altitude += velocity.y

            val horizontal = velocity.horizontalDistance()
            if (horizontal > previousHorizontal) {
                gainedSpeed = true
            }
            previousHorizontal = horizontal
        }

        assertTrue(altitude < 0.0, "a level glide must lose height, got $altitude")
        assertTrue(gainedSpeed, "a level glide must convert that height into speed")
    }

    @Test
    fun `diving is faster than climbing`() {
        val start = Vec3(0.0, 0.0, 0.8)

        var diving = start
        var climbing = start
        repeat(20) {
            diving = WingPhysics.step(diving, look(0f, 40f), 40f)
            climbing = WingPhysics.step(climbing, look(0f, -40f), -40f)
        }

        assertTrue(
            diving.length() > climbing.length(),
            "a dive must end faster than a climb: dive=${diving.length()} climb=${climbing.length()}"
        )
    }

    @Test
    fun `a boost speeds us up along the look direction`() {
        val velocity = Vec3(0.0, 0.0, 0.5)
        val look = look(0f, 0f)

        val boosted = WingPhysics.boost(velocity, look)

        assertTrue(boosted.length() > velocity.length(), "a rocket must add speed")
        assertTrue(boosted.dot(look) > velocity.dot(look), "the added speed must be along the look")
    }

    @Test
    fun `repeated boosting converges rather than diverging`() {
        var velocity = Vec3.ZERO
        val look = look(0f, 0f)

        repeat(200) { velocity = WingPhysics.boost(velocity, look) }

        // The recurrence is v' = 0.5 v + 0.85 look, whose fixed point is 1.7 * look. Vanilla never
        // gets there because rockets expire and drag intervenes, but the model must not run away.
        assertEquals(1.7, velocity.length(), 1.0e-6)
    }

    @Test
    fun `the model is indifferent to compass heading`() {
        val speed = 0.7
        val pitch = 15f

        val results = listOf(0f, 90f, 180f, -37f).map { yaw ->
            var velocity = Vec3.directionFromRotation(0f, yaw).scale(speed)
            repeat(30) { velocity = WingPhysics.step(velocity, look(yaw, pitch), pitch) }
            velocity.length()
        }

        results.zipWithNext { a, b ->
            // Not exact: the look vector comes from float trigonometry, so each heading rounds
            // differently. What must hold is that no direction is privileged.
            assertTrue(abs(a - b) < 1.0e-6, "identical flights in different directions differ: $a vs $b")
        }
    }

    @Test
    fun `specific energy falls while gliding`() {
        var velocity = Vec3(0.0, 0.0, 1.0)
        var y = 100.0
        val look = look(0f, 0f)

        val before = WingPhysics.specificEnergy(velocity, y)
        repeat(40) {
            velocity = WingPhysics.step(velocity, look, 0f)
            y += velocity.y
        }
        val after = WingPhysics.specificEnergy(velocity, y)

        // Drag is the only thing that can change the total, and it can only remove.
        assertTrue(after < before, "gliding must bleed energy: $before -> $after")
    }
}
