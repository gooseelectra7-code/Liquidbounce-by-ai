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
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Where a glide can go, and the height it should hold on the way. */
class ElytraRoute(
    /** Points to fly through, in order. */
    val waypoints: List<Vec3>,

    /** Whether the last point is the goal, or the flight has to be re-planned from there. */
    val reachedGoal: Boolean,

    /**
     * Whether this route was searched through walls rather than drawn across open sky.
     *
     * The two are flown differently, and the difference is not cosmetic. An open-sky route carries
     * an altitude profile built from a height map that a long flight can outrun, so the executor
     * watches for terrain and climbs over what the profile missed. A route searched through
     * enclosed space has no such gap to cover — and there, climbing at whatever appears ahead means
     * climbing into the ceiling.
     */
    val enclosed: Boolean = false,
) {
    val isEmpty: Boolean get() = waypoints.size < 2

    /** Straight-line length of the flight, in blocks. */
    val length: Double
        get() = waypoints.zipWithNext().sumOf { (from, to) -> from.distanceTo(to) }
}

/**
 * Routes a glide across open sky.
 *
 * Deliberately not a 3D search. Above open terrain the only obstacle is the ground itself, so the
 * route is the straight line to the goal with an altitude profile that clears whatever it passes
 * over — and that profile comes straight out of the surface height map [NavWorld] already maintains.
 * A full lattice search here would cost orders of magnitude more to arrive at the same line.
 *
 * Enclosed spaces — the Nether below its roof, or a cave system — are a genuinely different problem
 * that does need a 3D search, and this planner refuses them rather than pretending: see
 * [isOpenSkyBetween]. The Nether roof itself is open sky by this test, which is correct, since
 * flying above it is exactly the easy case.
 *
 * ## Climbing early
 *
 * Altitude is taken as a forward-looking maximum rather than the terrain directly below. A glider
 * cannot climb on demand, so learning about a ridge when already upon it is learning too late; the
 * profile starts rising well before one arrives.
 */
object ElytraPlanner {

    /**
     * Plans a glide, or returns null when the terrain is not open enough to fly blind through.
     *
     * @param clearance blocks to keep above the terrain
     * @param ceiling exclusive upper bound of buildable Y; a profile that needs more sky than the
     *   dimension has is refused rather than planned and then bounced off the roof
     */
    @Suppress("LongParameterList")
    fun planOpenSky(
        source: NavBlockSource,
        from: BlockPos,
        to: BlockPos,
        surfaceAt: (Int, Int) -> Int,
        clearance: Int = DEFAULT_CLEARANCE,
        ceiling: Int = Int.MAX_VALUE,
    ): ElytraRoute? {
        val dx = (to.x - from.x).toDouble()
        val dz = (to.z - from.z).toDouble()
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < MIN_WORTHWHILE_DISTANCE) {
            lastRefusal = "too short to fly (${distance.toInt()} blocks)"
            return null
        }

        val steps = ceil(distance / WAYPOINT_SPACING).toInt().coerceAtLeast(1)
        val waypoints = ArrayList<Vec3>(steps + 1)

        // Height carried into the unknown. Most of a long flight is planned over ground nobody has
        // seen yet, and that is the part the whole exercise is for.
        var held = MIN_CRUISE_ALTITUDE
        var blindFrom = -1

        for (step in 0..steps) {
            val t = step.toDouble() / steps
            val x = from.x + dx * t
            val z = from.z + dz * t

            val ground = highestGroundAhead(x, z, dx / distance, dz / distance, surfaceAt)
            val altitude = if (ground == Int.MIN_VALUE) {
                // Nothing known here. Hold what the last known ground asked for rather than
                // dropping to the floor of the range: the terrain does not stop at the render
                // distance, and a profile that does plans a straight line through the next hill.
                if (blindFrom < 0) blindFrom = step
                held
            } else {
                blindFrom = -1
                max(ground + clearance, MIN_CRUISE_ALTITUDE).also { held = it }
            }

            // A dimension whose ground is close to its roof — the Nether — cannot hold a cruise
            // above its terrain at all, and pretending otherwise plans a flight into bedrock.
            if (altitude > ceiling - HEADROOM) {
                lastRefusal = "needs y=$altitude, ceiling is $ceiling"
                return null
            }
            if (!isOpenSkyBetween(source, x, z, altitude)) {
                lastRefusal = "roof over ${x.toInt()} ${z.toInt()} at y=$altitude"
                return null
            }

            waypoints.add(Vec3(x + 0.5, altitude.toDouble(), z + 0.5))
        }

        // Come down onto the goal at the end rather than sailing over it at cruise height.
        waypoints.add(Vec3(to.x + 0.5, to.y.toDouble(), to.z + 0.5))

        lastRefusal = null
        lastBlindFrom = if (blindFrom < 0) -1 else blindFrom
        return ElytraRoute(waypoints, reachedGoal = true)
    }

    /**
     * Highest ground within the look-ahead window, or [Int.MIN_VALUE] when none of it is known.
     *
     * The window only looks forward: a glider trades height for speed and cannot climb on command,
     * so the profile has to start rising long before the obstacle rather than when it is underneath.
     */
    private fun highestGroundAhead(
        x: Double,
        z: Double,
        dirX: Double,
        dirZ: Double,
        surfaceAt: (Int, Int) -> Int,
    ): Int {
        var highest = Int.MIN_VALUE
        var ahead = 0.0
        while (ahead <= TERRAIN_LOOKAHEAD) {
            val sampleX = (x + dirX * ahead).toInt()
            val sampleZ = (z + dirZ * ahead).toInt()
            val surface = surfaceAt(sampleX, sampleZ)
            if (surface != Int.MIN_VALUE) highest = max(highest, surface)
            ahead += TERRAIN_SAMPLE_STEP
        }
        return highest
    }

    /**
     * Why the last call refused, or null when it did not.
     *
     * For `.nav fly`, which exists because "the route was not planned" and "the route was planned
     * and the take-off failed" look identical from the cockpit.
     */
    @Volatile
    var lastRefusal: String? = null
        private set

    /** Waypoint index from which the last plan was flying blind, or -1 if it never was. */
    @Volatile
    var lastBlindFrom: Int = -1
        private set

    /**
     * Is there genuinely open air at the cruise height here?
     *
     * This is the test that separates the two kinds of elytra travel. Open terrain has nothing above
     * the flight level, so a straight line is safe. A ceiling means the space is enclosed and a
     * glide needs real three-dimensional planning around walls and roofs — which this planner does
     * not do, and so declines rather than flying into them.
     */
    private fun isOpenSkyBetween(source: NavBlockSource, x: Double, z: Double, altitude: Int): Boolean {
        val blockX = x.toInt()
        val blockZ = z.toInt()

        for (level in 0 until CEILING_PROBE_HEIGHT) {
            val navClass = source.navClass(blockX, altitude + level, blockZ)
            // Unloaded space is not evidence of a ceiling; it is simply not known yet, and the
            // route will be re-planned as chunks arrive.
            if (navClass == NavBlockClass.UNKNOWN) continue
            if (!NavBlockClass.isPassable(navClass)) return false
        }
        return true
    }

    /** Blocks of air to keep between the hull and the ground. */
    const val DEFAULT_CLEARANCE = 24

    /** Below this, walking is simply better than taking off and landing again. */
    private const val MIN_WORTHWHILE_DISTANCE = 64.0

    /** Spacing of route points along the ground track. */
    private const val WAYPOINT_SPACING = 24.0

    /** How far ahead the altitude profile looks for rising ground. */
    private const val TERRAIN_LOOKAHEAD = 96.0

    private const val TERRAIN_SAMPLE_STEP = 8.0

    /** Never cruise below this, so a flight over a valley does not skim its floor. */
    private const val MIN_CRUISE_ALTITUDE = 96

    /** How much clear air above the cruise height counts as open sky. */
    private const val CEILING_PROBE_HEIGHT = 12

    /** Air to leave between the cruise height and the top of the world. */
    private const val HEADROOM = 4
}
