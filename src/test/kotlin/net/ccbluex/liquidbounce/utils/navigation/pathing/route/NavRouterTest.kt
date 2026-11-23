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

package net.ccbluex.liquidbounce.utils.navigation.pathing.route

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavRouterTest {

    private val equipped = NavCaps(allowElytra = true, fireworks = 32)

    @Test
    fun `a long journey is flown`() {
        assertTrue(NavRouter.preferFlight(2000.0, equipped))
    }

    @Test
    fun `a short hop is walked, whatever the clock says`() {
        // Flying wins on time well before this, but the clock cannot price the rockets it spends.
        assertFalse(NavRouter.preferFlight(80.0, equipped))
        assertTrue(
            NavRouter.estimateFlightTicks(80.0) < NavRouter.estimateWalkTicks(80.0, equipped),
            "the point of the distance floor is that it overrides a comparison flying already wins",
        )
    }

    @Test
    fun `no wing means no flight`() {
        assertFalse(NavRouter.preferFlight(2000.0, NavCaps(allowElytra = false, fireworks = 32)))
    }

    @Test
    fun `one rocket is not enough to leave the ground`() {
        assertFalse(NavRouter.preferFlight(2000.0, NavCaps(allowElytra = true, fireworks = 1)))
    }

    @Test
    fun `riding rules flying out`() {
        // The animal is not coming along, and abandoning it is not the router's call to make.
        val mounted = NavCaps(allowElytra = true, fireworks = 32, travelTicksPerBlock = 2.2)

        assertFalse(NavRouter.preferFlight(2000.0, mounted))
    }

    @Test
    fun `the walking estimate is the optimistic one`() {
        // Straight line at full sprint: no route ever does better, so a strategy that only wins
        // against this one wins against the real thing too.
        val walking = NavRouter.estimateWalkTicks(100.0, NavCaps())
        val notSprinting = NavRouter.estimateWalkTicks(100.0, NavCaps(canSprint = false))

        assertTrue(walking < notSprinting)
    }
}
