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

package net.ccbluex.liquidbounce.utils.entity

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeadingPositionExtrapolationTest {

    private val linear = LinearPositionExtrapolation(
        basePosition = Vec3(0.0, 0.0, 0.0),
        velocity = Vec3(1.0, 0.0, 0.0)
    )

    @Test
    fun `shifts the query forward by the lead`() {
        val leading = LeadingPositionExtrapolation(linear, leadTicks = 2.0, maxTicks = 30.0)

        // The solver asks for the target at the arrow's flight time; with a lead of 2 the
        // answer must be the base position 2 ticks further along.
        assertEquals(Vec3(7.0, 0.0, 0.0), leading.getPositionInTicks(5.0))
    }

    @Test
    fun `clamps the total look-ahead at the cap`() {
        val leading = LeadingPositionExtrapolation(linear, leadTicks = 4.0, maxTicks = 10.0)

        assertEquals(Vec3(10.0, 0.0, 0.0), leading.getPositionInTicks(20.0))
    }

    @Test
    fun `clamps to zero when the total goes negative`() {
        // The bisect in the solvers starts at 0.0, but nothing guarantees a caller never
        // asks for a negative tick — the answer must be the base snapshot, not a lead
        // backwards through the wall the target came from.
        val leading = LeadingPositionExtrapolation(linear, leadTicks = 1.0, maxTicks = 30.0)

        assertEquals(Vec3(0.0, 0.0, 0.0), leading.getPositionInTicks(-2.0))
    }

    @Test
    fun `fractional leads are passed through`() {
        val leading = LeadingPositionExtrapolation(linear, leadTicks = 0.5, maxTicks = 30.0)

        assertEquals(Vec3(1.5, 0.0, 0.0), leading.getPositionInTicks(1.0))
    }

}
