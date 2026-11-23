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

package net.ccbluex.liquidbounce.utils.navigation.pathing.hier

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavTestTerrain
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavSectionComponentsTest {

    private val caps = NavCaps.TRAVEL

    /** One section's worth of world, floored at [floorY]. */
    private fun section(floorY: Int = 0): NavTestTerrain =
        NavTestTerrain(16, 16, 16).apply { floor(floorY) }

    private fun componentsOf(terrain: NavTestTerrain) = NavSectionComponents.of(terrain, 0, 0, 0, caps)

    @Test
    fun `an open floor is one pocket`() {
        val components = componentsOf(section())

        assertNotNull(components)
        assertEquals(1, components!!.count)
        assertEquals(0, components.componentAt(3, 1, 3))
        assertEquals(0, components.componentAt(12, 1, 12))
    }

    @Test
    fun `solid rock holds nothing`() {
        val solid = NavTestTerrain(16, 16, 16, fill = NavBlockClass.SOLID_FULL)

        assertNull(componentsOf(solid), "nothing can stand inside stone")
    }

    @Test
    fun `a wall through the middle splits the pocket in two`() {
        // The case a "does this section have floor in it" abstraction gets wrong: floor on both
        // sides, and no way between them without leaving the section.
        val terrain = section()
        terrain.box(8, 1, 0, 8, 15, 15, NavBlockClass.SOLID_FULL)

        val components = componentsOf(terrain)!!

        assertEquals(2, components.count)
        assertNotEquals(
            components.componentAt(3, 1, 8),
            components.componentAt(12, 1, 8),
            "the two sides of a wall are not the same pocket",
        )
    }

    @Test
    fun `a doorway in the wall makes it one pocket again`() {
        val terrain = section()
        terrain.box(8, 1, 0, 8, 15, 15, NavBlockClass.SOLID_FULL)
        terrain.box(8, 1, 7, 8, 2, 8, NavBlockClass.AIR)

        val components = componentsOf(terrain)!!

        assertEquals(1, components.count)
        assertEquals(components.componentAt(3, 1, 8), components.componentAt(12, 1, 8))
    }

    @Test
    fun `a step joins what a wall would separate`() {
        // Single steps up and down are ordinary walking, so terraces are one pocket.
        val terrain = section()
        terrain.box(8, 1, 0, 15, 1, 15, NavBlockClass.SOLID_FULL)

        val components = componentsOf(terrain)!!

        assertEquals(1, components.count, "a one-block terrace is walkable, so it is one pocket")
        assertEquals(components.componentAt(3, 1, 8), components.componentAt(12, 2, 8))
    }

    @Test
    fun `a ladder joins the cells it runs through`() {
        // Standing cells stacked directly on top of each other can only happen on a ladder or in
        // water, and there the vertical connection is real.
        val terrain = section()
        for (y in 1..10) {
            terrain.set(4, y, 4, NavBlockClass.CLIMBABLE)
        }

        val components = componentsOf(terrain)!!

        assertEquals(
            components.componentAt(4, 1, 4),
            components.componentAt(4, 9, 4),
            "a ladder is one pocket from bottom to top",
        )
    }

    @Test
    fun `each pocket is represented by a cell inside it`() {
        val terrain = section()
        terrain.box(8, 1, 0, 8, 15, 15, NavBlockClass.SOLID_FULL)

        val components = componentsOf(terrain)!!

        for (component in 0 until components.count) {
            val representative = components.representatives[component]
            assertEquals(
                component,
                components.componentAt(
                    net.minecraft.core.BlockPos.getX(representative),
                    net.minecraft.core.BlockPos.getY(representative),
                    net.minecraft.core.BlockPos.getZ(representative),
                ),
                "a pocket's representative has to be in it",
            )
        }
    }

    @Test
    fun `the floor below a section still holds a body up`() {
        // The bottom row of a section stands on a block that belongs to the section beneath it, and
        // a fill that would not look there would report a floor as empty air.
        val terrain = NavTestTerrain(16, 32, 16).apply { floor(15) }

        val components = NavSectionComponents.of(terrain, 0, 1, 0, caps)

        assertNotNull(components, "the row above the floor is standable")
        assertTrue(components!!.componentAt(8, 16, 8) >= 0)
    }
}
