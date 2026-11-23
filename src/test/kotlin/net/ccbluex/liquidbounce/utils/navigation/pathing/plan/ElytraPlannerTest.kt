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

package net.ccbluex.liquidbounce.utils.navigation.pathing.plan

import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElytraPlannerTest {

    /** Open sky everywhere: nothing is ever in the way above ground. */
    private val openSky = NavBlockSource { _, _, _ -> NavBlockClass.PACKED_AIR }

    /** Flat ground at sea level. */
    private val flatTerrain: (Int, Int) -> Int = { _, _ -> 64 }

    private fun plan(
        source: NavBlockSource = openSky,
        surface: (Int, Int) -> Int = flatTerrain,
        from: BlockPos = BlockPos(0, 70, 0),
        to: BlockPos = BlockPos(600, 70, 0),
    ) = ElytraPlanner.planOpenSky(source, from, to, surface)

    @Test
    fun `a long flight over open ground is planned`() {
        val route = plan()

        assertNotNull(route)
        assertTrue(route!!.waypoints.size > 2, "a 600-block flight needs intermediate points")
        assertTrue(route.reachedGoal)
    }

    @Test
    fun `a short hop is refused because walking beats taking off`() {
        assertNull(plan(to = BlockPos(20, 70, 0)), "taking off and landing costs more than walking it")
    }

    @Test
    fun `the route ends on the goal rather than sailing over it`() {
        val route = plan(to = BlockPos(600, 70, 0))!!

        val last = route.waypoints.last()
        assertTrue(last.x > 599.0 && last.x < 602.0, "did not finish at the goal column: $last")
        assertTrue(last.y < 80.0, "should descend onto the goal, not stay at cruise height")
    }

    @Test
    fun `cruise height clears the terrain by the requested margin`() {
        val route = plan(surface = { _, _ -> 200 })!!

        // Every point except the final descent has to sit above the ground it passes over.
        for (point in route.waypoints.dropLast(1)) {
            assertTrue(
                point.y >= 200 + ElytraPlanner.DEFAULT_CLEARANCE,
                "cruised at ${point.y} over ground at 200",
            )
        }
    }

    @Test
    fun `the climb starts before the mountain, not above it`() {
        // A glider trades height for speed and cannot climb on demand, so a profile that reacts to
        // the ground directly below reacts far too late to clear anything.
        val ridgeStart = 400
        val surface: (Int, Int) -> Int = { x, _ -> if (x in ridgeStart..(ridgeStart + 40)) 220 else 64 }

        val route = plan(surface = surface)!!

        val approaching = route.waypoints.filter { it.x > ridgeStart - 80 && it.x < ridgeStart.toDouble() }
        assertTrue(approaching.isNotEmpty(), "expected points on the approach")
        assertTrue(
            approaching.all { it.y >= 220 + ElytraPlanner.DEFAULT_CLEARANCE },
            "still low on the approach: ${approaching.map { it.y }}",
        )
    }

    @Test
    fun `a ceiling means the space is enclosed and the flight is refused`() {
        // The Nether below its roof, or a cave. Straight-line flight is wrong there and needs a real
        // three-dimensional search, so this planner declines rather than flying into rock.
        val roofed = NavBlockSource { _, y, _ ->
            if (y > 100) NavBlockClass.pack(NavBlockClass.SOLID_FULL, 4) else NavBlockClass.PACKED_AIR
        }

        assertNull(plan(source = roofed), "a roofed world is not open sky")
    }

    @Test
    fun `unloaded chunks are not mistaken for a ceiling`() {
        // Space we have not received yet is unknown, not blocked. Treating it as a roof would refuse
        // every flight that leaves the render distance — which is most of them.
        val unloaded = NavBlockSource { _, _, _ -> NavBlockClass.PACKED_UNKNOWN }

        assertNotNull(plan(source = unloaded), "unknown air should not block a flight")
    }

    /**
     * The case the elytra mode exists for: nearly the whole flight is over ground nobody has seen.
     *
     * The height map ends at the render distance, so a profile that falls back to its own floor out
     * there plans a straight line at sea level over terrain that is not at sea level. Holding the
     * last height that meant something is the only honest answer until the chunks arrive — and they
     * do arrive, well before the aircraft does, which is what the in-flight rebuild is for.
     */
    @Test
    fun `height is held across ground that has not been received`() {
        val knownUntil = 200
        val mountainous: (Int, Int) -> Int = { x, _ -> if (x <= knownUntil) 180 else Int.MIN_VALUE }

        val route = plan(surface = mountainous, to = BlockPos(4000, 70, 0))!!

        val blind = route.waypoints.dropLast(1).filter { it.x > knownUntil + ElytraPlanner.DEFAULT_CLEARANCE }
        assertTrue(blind.isNotEmpty(), "expected most of a 4000-block flight to be over unknown ground")
        assertTrue(
            blind.all { it.y >= 180 + ElytraPlanner.DEFAULT_CLEARANCE },
            "dropped to ${blind.minOf { it.y }} once the map ran out, below the last known ground at 180",
        )
    }

    @Test
    fun `a dimension with no sky to spare refuses the flight`() {
        // The Nether: its ground is a hundred blocks under its own roof, so there is no height at
        // which a blind straight line is safe. Planning one anyway flies into bedrock.
        val route = ElytraPlanner.planOpenSky(
            openSky, BlockPos(0, 70, 0), BlockPos(600, 70, 0),
            surfaceAt = { _, _ -> 110 },
            ceiling = 128,
        )

        assertNull(route, "cruise clearance does not fit under the roof")
    }

    @Test
    fun `flying above the nether roof counts as open sky`() {
        // The roof is solid, but a flight held above it has nothing overhead, which is exactly the
        // easy case and must not be refused.
        val netherRoof = NavBlockSource { _, y, _ ->
            if (y in 120..127) NavBlockClass.pack(NavBlockClass.SOLID_FULL, 4) else NavBlockClass.PACKED_AIR
        }

        val route = ElytraPlanner.planOpenSky(
            netherRoof, BlockPos(0, 140, 0), BlockPos(600, 140, 0),
            surfaceAt = { _, _ -> 127 },
        )

        assertNotNull(route, "above the roof there is nothing in the way")
        assertTrue(route!!.waypoints.dropLast(1).all { it.y > 127 }, "must stay above the roof")
    }
}
