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

import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCostModel
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavEdgeBuffer
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

class GroundPlannerTest {

    private val floorY = 8

    /** Terrain with a solid floor at [floorY] and open air above it. */
    private fun flatTerrain(size: Int = 16, height: Int = 20): NavTestTerrain =
        NavTestTerrain(size, height, size).apply { floor(floorY) }

    private fun expand(planner: GroundPlanner, x: Int, y: Int, z: Int): NavEdgeBuffer {
        val buffer = NavEdgeBuffer()
        planner.expand(BlockPos.asLong(x, y, z), buffer)
        return buffer
    }

    private fun edgesOfKind(buffer: NavEdgeBuffer, kind: Int): List<Int> =
        (0 until buffer.size).filter { NavMove.kindOf(buffer.moves[it]) == kind }

    private fun destinations(buffer: NavEdgeBuffer): Set<Long> =
        (0 until buffer.size).map { buffer.nodes[it] }.toSet()

    /** Cheapest edge to a destination, or null when nothing leads there. */
    private fun costOf(buffer: NavEdgeBuffer, node: Long): Double? =
        (0 until buffer.size).filter { buffer.nodes[it] == node }.minOfOrNull { buffer.costs[it] }

    // -----------------------------------------------------------------------
    //  Preferring the obvious thing
    // -----------------------------------------------------------------------

    @Test
    fun `stepping off a one-block ledge beats mining it away`() {
        // The terrain is a floor with the ground one block lower to the east — the commonest shape
        // there is, and the one a player crosses without thinking.
        val terrain = flatTerrain()
        for (z in 0 until 16) {
            for (x in 9 until 16) {
                terrain.set(x, floorY, z, NavBlockClass.AIR)
                terrain.set(x, floorY - 1, z, NavBlockClass.SOLID_FULL)
            }
        }

        // A miner, with a good tool and its own low aversion to digging — the profile that made this
        // go wrong. Priced on the clock alone, a shovel goes through a block of dirt in less time
        // than a footstep takes, so the cheapest way down a ledge was to mine the ledge.
        val caps = NavCaps(allowBreak = true, breakTicksPerHardness = 2.0, digAversion = 12.0)
        val buffer = expand(GroundPlanner(terrain, caps), 8, floorY + 1, 8)

        val descend = costOf(buffer, BlockPos.asLong(9, floorY, 8))
        val digDown = costOf(buffer, BlockPos.asLong(8, floorY, 8))

        assertTrue(descend != null, "there has to be a step down off the ledge at all")
        assertTrue(
            digDown == null || descend!! < digDown,
            "stepping down cost $descend, mining down cost $digDown — the step must win",
        )
    }

    @Test
    fun `a drop is not charged for the walking it does on the way`() {
        // Walking off a ledge and falling happen at the same time — the body keeps its speed all the
        // way down. Charging the step and the fall one after the other priced a one-block descent at
        // more than twice what it costs, and that margin is what made digging look reasonable.
        val terrain = flatTerrain(height = 40)
        for (z in 0 until 16) {
            for (x in 9 until 16) {
                terrain.set(x, floorY, z, NavBlockClass.AIR)
                terrain.set(x, floorY - 1, z, NavBlockClass.SOLID_FULL)
            }
        }

        val buffer = expand(GroundPlanner(terrain, NavCaps()), 8, floorY + 1, 8)
        val descend = costOf(buffer, BlockPos.asLong(9, floorY, 8))

        assertTrue(descend != null, "the step down has to exist")
        assertTrue(
            descend!! <= NavTiming.SPRINT_PER_BLOCK + NavCostModel.fallTicks(1),
            "a one-block descent cost $descend, more than the slower of its two halves",
        )
    }

    // -----------------------------------------------------------------------
    //  Edges the body cannot actually perform
    // -----------------------------------------------------------------------

    @Test
    fun `nothing is planned onto the top of a fence`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY + 1, 8, NavBlockClass.BLOCKING_TALL)

        val buffer = expand(GroundPlanner(terrain, NavCaps()), 8, floorY + 1, 8)

        // A fence top is a block and a half up and a jump reaches a block and a quarter, so the
        // step exists only in the lattice. The executor answers such an edge by pressing into the
        // fence until its deadline and replanning into the identical route.
        assertFalse(
            BlockPos.asLong(9, floorY + 2, 8) in destinations(buffer),
            "a fence top is out of reach and must never be a destination",
        )
    }

    @Test
    fun `a ladder is not climbed past its last rung`() {
        val terrain = flatTerrain()
        // Two rungs against a wall, and nothing above them.
        terrain.set(8, floorY + 1, 8, NavBlockClass.CLIMBABLE)
        terrain.set(8, floorY + 2, 8, NavBlockClass.CLIMBABLE)
        terrain.set(7, floorY + 1, 8, NavBlockClass.SOLID_FULL)
        terrain.set(7, floorY + 2, 8, NavBlockClass.SOLID_FULL)
        terrain.set(7, floorY + 3, 8, NavBlockClass.SOLID_FULL)

        val buffer = expand(GroundPlanner(terrain, NavCaps()), 8, floorY + 2, 8)

        // The cell above the top rung is mid-air: a body cannot hold it, and expanding it as if it
        // were ground produces a route that walks sideways out of thin air.
        assertFalse(
            BlockPos.asLong(8, floorY + 3, 8) in destinations(buffer),
            "climbing above the last rung leaves the body with nothing to hold",
        )
    }

    @Test
    fun `stepping off the bottom of a ladder is a fall, not a climb`() {
        val terrain = NavTestTerrain(16, 40, 16)
        terrain.floor(4)
        // A ladder that starts high up a wall, with a long drop underneath it.
        for (y in 20..24) {
            terrain.set(8, y, 8, NavBlockClass.CLIMBABLE)
            terrain.set(7, y, 8, NavBlockClass.SOLID_FULL)
        }

        val buffer = expand(GroundPlanner(terrain, NavCaps()), 8, 20, 8)

        // Priced as a four-tick climb, the step off the bottom rung bypassed the fall rules
        // entirely — sixteen blocks of drop that neither maxFallBlocks nor the health check ever
        // saw, because no fall edge was consulted.
        assertFalse(
            BlockPos.asLong(8, 19, 8) in destinations(buffer),
            "there is nothing below the bottom rung to climb onto",
        )
    }

    @Test
    fun `a wide body is not dropped into a gap it does not fit`() {
        val terrain = NavTestTerrain(16, 20, 16)
        terrain.floor(floorY)
        // One block lower to the east, and a wall one column further on.
        for (z in 0 until 16) {
            terrain.set(9, floorY, z, NavBlockClass.AIR)
            terrain.set(9, floorY - 1, z, NavBlockClass.SOLID_FULL)
            terrain.set(10, floorY + 1, z, NavBlockClass.SOLID_FULL)
        }
        // A camel is 1.7 wide and 2.375 tall: it overlaps three columns and needs three levels.
        val caps = NavCaps(bodyWidth = 1.7, bodyHeight = 2.375, maxStepUp = 1, allowParkour = false)

        val buffer = expand(GroundPlanner(terrain, caps), 8, floorY + 1, 8)

        // The step down is into a column whose neighbour is solid, which WALK would have refused —
        // the drop used to ask only about a player-sized body and one column.
        assertFalse(
            BlockPos.asLong(9, floorY, 8) in destinations(buffer),
            "a drop must ask for the same room a step onto the same block would",
        )
    }

    @Test
    fun `a puddle on a ledge can be stepped out of`() {
        val terrain = NavTestTerrain(16, 20, 16)
        terrain.floor(floorY)
        terrain.set(8, floorY + 1, 8, NavBlockClass.WATER)
        // East of the puddle the ground falls away by one block.
        terrain.set(9, floorY, 8, NavBlockClass.AIR)
        terrain.set(9, floorY - 1, 8, NavBlockClass.SOLID_FULL)

        val buffer = expand(GroundPlanner(terrain, NavCaps(allowSwim = true)), 8, floorY + 1, 8)

        // Walking into water is always allowed, so an exit that only looks level and upward makes
        // any one-block puddle beside a drop a place a route can enter and never leave.
        assertTrue(
            BlockPos.asLong(9, floorY, 8) in destinations(buffer),
            "wading out has to include the step down off the water's edge",
        )
    }

    @Test
    fun `no jump is planned out of a cobweb`() {
        val terrain = flatTerrain()
        terrain.set(8, floorY + 1, 8, NavBlockClass.COBWEB)
        // A two-block gap to the east with a landing beyond it.
        terrain.set(9, floorY, 8, NavBlockClass.AIR)
        terrain.set(10, floorY, 8, NavBlockClass.AIR)

        val buffer = expand(GroundPlanner(terrain, NavCaps(allowParkour = true)), 8, floorY + 1, 8)

        assertTrue(
            edgesOfKind(buffer, NavMove.PARKOUR).isEmpty(),
            "a body held by a cobweb cannot sprint-jump anywhere",
        )
    }

    // -----------------------------------------------------------------------
    //  The admissibility invariant
    // -----------------------------------------------------------------------

    @Test
    fun `no edge is ever cheaper than the heuristic floor`() {
        // This is the invariant every goal heuristic is built on: estimates are distance times
        // NavTiming.MIN_COST_PER_BLOCK, so an edge below that floor would let the heuristic
        // overestimate and A* would silently start returning suboptimal paths. Checked exhaustively
        // over random terrain and the most permissive capabilities, since those emit the most kinds
        // of edge and the cheapest ones.
        val random = Random(seed = 20260731)
        val caps = NavCaps(
            canSprint = true,
            allowParkour = true,
            allowBreak = true,
            allowPlace = true,
            placeableBlocks = 64,
            allowFallDamage = true,
            allowSwim = true,
        )

        repeat(12) { round ->
            val terrain = randomTerrain(random, round)
            val planner = GroundPlanner(terrain, caps)
            val buffer = NavEdgeBuffer()

            for (x in 0 until TERRAIN_SIZE) {
                for (y in 0 until TERRAIN_HEIGHT) {
                    for (z in 0 until TERRAIN_SIZE) {
                        buffer.clear()
                        planner.expand(BlockPos.asLong(x, y, z), buffer)

                        for (edge in 0 until buffer.size) {
                            val destination = buffer.nodes[edge]
                            val cost = buffer.costs[edge]
                            val dx = BlockPos.getX(destination) - x
                            val dy = BlockPos.getY(destination) - y
                            val dz = BlockPos.getZ(destination) - z

                            val horizontal = sqrt((dx * dx + dz * dz).toDouble()) * NavTiming.MIN_COST_PER_BLOCK
                            val vertical = if (dy > 0) dy * NavTiming.MIN_COST_PER_ASCENT else 0.0
                            val floor = max(horizontal, vertical)

                            assertTrue(
                                cost >= floor - TOLERANCE,
                                "round $round: edge ($x,$y,$z) -> (${BlockPos.getX(destination)}," +
                                    "${BlockPos.getY(destination)},${BlockPos.getZ(destination)}) " +
                                    "kind ${NavMove.nameOf(NavMove.kindOf(buffer.moves[edge]))} " +
                                    "costs $cost, below the floor of $floor",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `edges never lead into unloaded space`() {
        // Planning through unknown terrain is how a pathfinder walks confidently into a wall it has
        // never seen. Every cell outside the test box reads as unknown.
        val terrain = flatTerrain()
        val planner = GroundPlanner(terrain, NavCaps(allowBreak = true, allowPlace = true, placeableBlocks = 64))
        val buffer = NavEdgeBuffer()

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                buffer.clear()
                planner.expand(BlockPos.asLong(x, floorY + 1, z), buffer)
                for (edge in 0 until buffer.size) {
                    val destination = buffer.nodes[edge]
                    val feet = NavBlockClass.navClassOf(
                        terrain.packed(
                            BlockPos.getX(destination),
                            BlockPos.getY(destination),
                            BlockPos.getZ(destination),
                        ),
                    )
                    assertFalse(
                        feet == NavBlockClass.UNKNOWN,
                        "edge from ($x, $z) leads into unloaded space",
                    )
                }
            }
        }
    }

    @Test
    fun `every emitted edge has a finite, non-negative cost`() {
        val random = Random(seed = 4242)
        val caps = NavCaps(allowParkour = true, allowBreak = true, allowPlace = true, placeableBlocks = 64)

        repeat(6) { round ->
            val terrain = randomTerrain(random, round)
            val planner = GroundPlanner(terrain, caps)
            val buffer = NavEdgeBuffer()

            for (x in 0 until TERRAIN_SIZE) {
                for (y in 0 until TERRAIN_HEIGHT) {
                    for (z in 0 until TERRAIN_SIZE) {
                        buffer.clear()
                        planner.expand(BlockPos.asLong(x, y, z), buffer)
                        for (edge in 0 until buffer.size) {
                            assertTrue(buffer.costs[edge].isFinite(), "infinite cost survived into the buffer")
                            assertTrue(buffer.costs[edge] >= 0.0, "negative cost would break A* outright")
                        }
                        assertFalse(buffer.overflowed, "the edge buffer overflowed at ($x, $y, $z)")
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Level movement
    // -----------------------------------------------------------------------

    @Test
    fun `open ground offers four cardinal walks and four diagonals`() {
        val terrain = flatTerrain()
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(edgesOfKind(buffer, NavMove.WALK).size == 4, "expected four cardinal walks")
        assertTrue(edgesOfKind(buffer, NavMove.DIAGONAL).size == 4, "expected four diagonals")
    }

    @Test
    fun `a diagonal is priced at exactly root two straight steps`() {
        // The goal heuristics use octile distance, which is only admissible while this holds.
        val terrain = flatTerrain()
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)
        val walk = edgesOfKind(buffer, NavMove.WALK).first()
        val diagonal = edgesOfKind(buffer, NavMove.DIAGONAL).first()

        assertTrue(
            abs(buffer.costs[diagonal] - buffer.costs[walk] * NavTiming.DIAGONAL_MULTIPLIER) < TOLERANCE,
            "diagonal cost ${buffer.costs[diagonal]} is not root two times ${buffer.costs[walk]}",
        )
    }

    @Test
    fun `a diagonal squeeze between two blocks is refused`() {
        val terrain = flatTerrain()
        // Wall off both orthogonal neighbours of the north-east diagonal from (8, 8).
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)
        terrain.box(8, floorY + 1, 7, 8, floorY + 2, 7, NavBlockClass.SOLID_FULL)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertFalse(
            BlockPos.asLong(9, floorY + 1, 7) in destinations(buffer),
            "corner-cutting between two solid blocks must not be allowed",
        )
    }

    @Test
    fun `terrain changes the price of a step`() {
        // The flat constant table of the previous engine priced ice, stone and soul sand
        // identically, which is why it could never decide between them.
        val terrain = flatTerrain()
        terrain.set(9, floorY, 8, NavBlockClass.FAST)
        terrain.set(7, floorY, 8, NavBlockClass.SLOW)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)
        fun costOntoColumn(x: Int, z: Int): Double {
            val edge = (0 until buffer.size).first { buffer.nodes[it] == BlockPos.asLong(x, floorY + 1, z) }
            return buffer.costs[edge]
        }

        val ice = costOntoColumn(9, 8)
        val plain = costOntoColumn(8, 9)
        val soulSand = costOntoColumn(7, 8)

        assertTrue(ice < plain, "ice ($ice) should be quicker than plain ground ($plain)")
        assertTrue(soulSand > plain, "soul sand ($soulSand) should be slower than plain ground ($plain)")
        assertTrue(
            ice >= NavTiming.MIN_COST_PER_BLOCK - TOLERANCE,
            "ice at $ice fell below the admissibility floor of ${NavTiming.MIN_COST_PER_BLOCK}",
        )
    }

    // -----------------------------------------------------------------------
    //  Vertical movement
    // -----------------------------------------------------------------------

    @Test
    fun `a one-block step up produces an ascend`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY + 1, 8, NavBlockClass.SOLID_FULL)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(
            BlockPos.asLong(9, floorY + 2, 8) in destinations(buffer),
            "should be able to step up onto the raised block",
        )
        assertTrue(edgesOfKind(buffer, NavMove.ASCEND).isNotEmpty())
    }

    @Test
    fun `a step up is refused without headroom to jump`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY + 1, 8, NavBlockClass.SOLID_FULL)
        // Ceiling directly above the player: no room to jump.
        terrain.set(8, floorY + 3, 8, NavBlockClass.SOLID_FULL)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertFalse(
            BlockPos.asLong(9, floorY + 2, 8) in destinations(buffer),
            "jumping needs two blocks of headroom",
        )
    }

    @Test
    fun `stepping off a ledge produces a descend`() {
        val terrain = NavTestTerrain(16, 20, 16)
        // Floor only on half the map, so x = 9 is a one-block drop.
        for (x in 0..8) {
            for (z in 0 until 16) {
                terrain.set(x, floorY, z, NavBlockClass.SOLID_FULL)
            }
        }
        for (x in 9 until 16) {
            for (z in 0 until 16) {
                terrain.set(x, floorY - 1, z, NavBlockClass.SOLID_FULL)
            }
        }
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(
            BlockPos.asLong(9, floorY, 8) in destinations(buffer),
            "should be able to step down one block",
        )
        assertTrue(edgesOfKind(buffer, NavMove.DESCEND).isNotEmpty())
    }

    @Test
    fun `a drop longer than the allowance is refused without fall damage permission`() {
        val terrain = NavTestTerrain(16, 40, 16)
        for (x in 0..8) {
            for (z in 0 until 16) {
                terrain.set(x, 30, z, NavBlockClass.SOLID_FULL)
            }
        }
        // The next column bottoms out ten blocks lower.
        for (x in 9 until 16) {
            for (z in 0 until 16) {
                terrain.set(x, 20, z, NavBlockClass.SOLID_FULL)
            }
        }
        val planner = GroundPlanner(terrain, NavCaps(allowFallDamage = false, maxFallBlocks = 3))

        val buffer = expand(planner, 8, 31, 8)

        assertFalse(
            BlockPos.asLong(9, 21, 8) in destinations(buffer),
            "a ten-block drop must be refused when fall damage is not allowed",
        )
    }

    @Test
    fun `the same drop is allowed once fall damage is permitted and the limit is raised`() {
        val terrain = NavTestTerrain(16, 40, 16)
        for (x in 0..8) {
            for (z in 0 until 16) {
                terrain.set(x, 30, z, NavBlockClass.SOLID_FULL)
            }
        }
        for (x in 9 until 16) {
            for (z in 0 until 16) {
                terrain.set(x, 20, z, NavBlockClass.SOLID_FULL)
            }
        }
        // Both are needed: damage has to be allowed *and* the drop has to be within the limit. The
        // limit is a hard one — a profile that allows damage does not thereby allow any height the
        // health bar happens to survive.
        val planner = GroundPlanner(terrain, NavCaps(allowFallDamage = true, maxFallBlocks = 12, health = 20.0))

        val buffer = expand(planner, 8, 31, 8)

        assertTrue(
            BlockPos.asLong(9, 21, 8) in destinations(buffer),
            "a survivable drop should be available when damage is permitted",
        )
    }

    @Test
    fun `the drop limit binds even when fall damage is permitted`() {
        val terrain = NavTestTerrain(16, 40, 16)
        for (x in 0..8) {
            for (z in 0 until 16) {
                terrain.set(x, 30, z, NavBlockClass.SOLID_FULL)
            }
        }
        for (x in 9 until 16) {
            for (z in 0 until 16) {
                terrain.set(x, 20, z, NavBlockClass.SOLID_FULL)
            }
        }
        // Ten blocks is seven half-hearts, comfortably survivable — and still further than the
        // route was told it may fall.
        val planner = GroundPlanner(terrain, NavCaps(allowFallDamage = true, maxFallBlocks = 4, health = 20.0))

        val buffer = expand(planner, 8, 31, 8)

        assertFalse(
            BlockPos.asLong(9, 21, 8) in destinations(buffer),
            "a survivable drop past the limit must still be refused",
        )
    }

    @Test
    fun `landing in water cancels the fall damage restriction`() {
        val terrain = NavTestTerrain(16, 40, 16)
        for (x in 0..8) {
            for (z in 0 until 16) {
                terrain.set(x, 30, z, NavBlockClass.SOLID_FULL)
            }
        }
        terrain.box(9, 20, 0, 15, 20, 15, NavBlockClass.WATER)
        val planner = GroundPlanner(terrain, NavCaps(allowFallDamage = false, maxFallBlocks = 3))

        val buffer = expand(planner, 8, 31, 8)

        assertTrue(
            BlockPos.asLong(9, 20, 8) in destinations(buffer),
            "water breaks the fall, so the drop is safe regardless of its length",
        )
    }

    // -----------------------------------------------------------------------
    //  Climbing and swimming
    // -----------------------------------------------------------------------

    @Test
    fun `a ladder can be climbed in both directions`() {
        val terrain = flatTerrain()
        terrain.box(8, floorY + 1, 8, 8, floorY + 5, 8, NavBlockClass.CLIMBABLE)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 3, 8)

        assertTrue(BlockPos.asLong(8, floorY + 4, 8) in destinations(buffer), "should climb up")
        assertTrue(BlockPos.asLong(8, floorY + 2, 8) in destinations(buffer), "should climb down")
        assertTrue(edgesOfKind(buffer, NavMove.CLIMB).size == 2)
    }

    /** A pool sixteen wide with its surface at [surfaceY]. */
    private fun pool(surfaceY: Int): NavTestTerrain = NavTestTerrain(16, 20, 16).apply {
        floor(floorY)
        box(0, floorY + 1, 0, 15, surfaceY, 15, NavBlockClass.WATER)
    }

    @Test
    fun `swimming replaces walking and reaches every direction along the surface`() {
        val surfaceY = floorY + 6
        val planner = GroundPlanner(pool(surfaceY), NavCaps(allowSwim = true))

        val buffer = expand(planner, 8, surfaceY, 8)

        // Eight compass directions and nothing else: no diving, and no climbing out into open air.
        // The diagonals matter more here than on land, because a swimmer has no run of level steps
        // for the executor to smooth — a four-connected lattice is swum exactly as planned.
        assertTrue(
            edgesOfKind(buffer, NavMove.SWIM).size == 8,
            "expected the eight compass directions, got ${edgesOfKind(buffer, NavMove.SWIM).size}",
        )
        assertTrue(edgesOfKind(buffer, NavMove.WALK).isEmpty(), "a swimming player is not walking")
    }

    @Test
    fun `a submerged swimmer is only ever offered the way up`() {
        // Being under water is not a place to travel through, it is a place to leave. Surface
        // swimming is the same speed and costs no air, so there is never a reason to plan a route
        // through the depths — only a way back out of them.
        val surfaceY = floorY + 6
        val planner = GroundPlanner(pool(surfaceY), NavCaps(allowSwim = true))

        val buffer = expand(planner, 8, floorY + 3, 8)
        val swims = edgesOfKind(buffer, NavMove.SWIM)

        assertTrue(swims.size == 1, "expected one move, upward, got ${swims.size}")
        assertTrue(BlockPos.asLong(8, floorY + 4, 8) in destinations(buffer), "the one move should rise")
    }

    @Test
    fun `a swimmer can climb out onto the bank`() {
        // Every other move from water leads to more water, and a body that is swimming gets no
        // walking moves — so without this a route can enter a lake and never leave it.
        val surfaceY = floorY + 6
        val terrain = pool(surfaceY)
        // A shore along one edge, its top level with the water.
        terrain.box(15, floorY + 1, 0, 15, surfaceY, 15, NavBlockClass.SOLID_FULL)

        val planner = GroundPlanner(terrain, NavCaps(allowSwim = true))
        val buffer = expand(planner, 14, surfaceY, 8)

        assertTrue(
            BlockPos.asLong(15, surfaceY + 1, 8) in destinations(buffer),
            "should be able to climb out onto the bank",
        )
    }

    // -----------------------------------------------------------------------
    //  Hazards
    // -----------------------------------------------------------------------

    @Test
    fun `lava is a wall, not an expensive shortcut`() {
        // Walking into lava is death rather than a cost, so it is impassable outright. The old
        // engine could not tell lava from water at all and would happily route straight through it.
        val terrain = flatTerrain()
        terrain.set(9, floorY + 1, 8, NavBlockClass.LAVA)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertFalse(
            BlockPos.asLong(9, floorY + 1, 8) in destinations(buffer),
            "no edge may lead into lava",
        )
        assertTrue(
            BlockPos.asLong(7, floorY + 1, 8) in destinations(buffer),
            "the other directions are still open",
        )
    }

    @Test
    fun `a fall into lava is never planned`() {
        val terrain = NavTestTerrain(16, 40, 16)
        for (x in 0..8) {
            for (z in 0 until 16) {
                terrain.set(x, 30, z, NavBlockClass.SOLID_FULL)
            }
        }
        terrain.box(9, 20, 0, 15, 20, 15, NavBlockClass.LAVA)
        val planner = GroundPlanner(terrain, NavCaps(allowFallDamage = true, health = 20.0))

        val buffer = expand(planner, 8, 31, 8)

        assertFalse(
            BlockPos.asLong(9, 20, 8) in destinations(buffer),
            "lava neither stops a fall nor is a place to land",
        )
    }

    @Test
    fun `walking through fire is possible but heavily penalised`() {
        // Unlike lava, a passable hazard is priced rather than banned — sometimes the only way on is
        // through the fire.
        val terrain = flatTerrain()
        terrain.set(9, floorY + 1, 8, NavBlockClass.HAZARD)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)
        val intoFire = (0 until buffer.size).firstOrNull { buffer.nodes[it] == BlockPos.asLong(9, floorY + 1, 8) }
        val ontoStone = (0 until buffer.size).first { buffer.nodes[it] == BlockPos.asLong(7, floorY + 1, 8) }

        assertTrue(intoFire != null, "a passable hazard should stay traversable as a last resort")
        assertTrue(
            buffer.costs[intoFire!!] > buffer.costs[ontoStone] * 5,
            "fire at ${buffer.costs[intoFire]} is not penalised enough against ${buffer.costs[ontoStone]}",
        )
    }

    @Test
    fun `standing on magma is penalised but possible`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY, 8, NavBlockClass.HAZARD_SOLID)
        val planner = GroundPlanner(terrain, NavCaps.SAFE)

        val buffer = expand(planner, 8, floorY + 1, 8)
        val ontoMagma = (0 until buffer.size).first { buffer.nodes[it] == BlockPos.asLong(9, floorY + 1, 8) }
        val ontoStone = (0 until buffer.size).first { buffer.nodes[it] == BlockPos.asLong(7, floorY + 1, 8) }

        assertTrue(
            buffer.costs[ontoMagma] > buffer.costs[ontoStone],
            "magma should cost more than plain stone",
        )
    }

    // -----------------------------------------------------------------------
    //  Capability gating
    // -----------------------------------------------------------------------

    @Test
    fun `no dig or place edges appear without the capability`() {
        val terrain = flatTerrain()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)
        val planner = GroundPlanner(terrain, NavCaps(allowBreak = false, allowPlace = false))

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(edgesOfKind(buffer, NavMove.DIG).isEmpty())
        assertTrue(edgesOfKind(buffer, NavMove.DIG_DOWN).isEmpty())
        assertTrue(edgesOfKind(buffer, NavMove.PILLAR).isEmpty())
        assertTrue(edgesOfKind(buffer, NavMove.PLACE).isEmpty())
    }

    @Test
    fun `a wall can be dug through once breaking is allowed`() {
        val terrain = flatTerrain()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)
        val planner = GroundPlanner(terrain, NavCaps(allowBreak = true))

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(
            BlockPos.asLong(9, floorY + 1, 8) in destinations(buffer),
            "the blocked cell should become reachable by digging",
        )
        assertTrue(edgesOfKind(buffer, NavMove.DIG).isNotEmpty())
    }

    @Test
    fun `bedrock cannot be dug through`() {
        val terrain = flatTerrain()
        terrain.box(
            9, floorY + 1, 8, 9, floorY + 2, 8,
            NavBlockClass.SOLID_FULL, NavBlockClass.DIG_UNBREAKABLE,
        )
        val planner = GroundPlanner(terrain, NavCaps(allowBreak = true))

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertFalse(
            BlockPos.asLong(9, floorY + 1, 8) in destinations(buffer),
            "unbreakable blocks must stay impassable even with digging enabled",
        )
    }

    @Test
    fun `digging costs more than walking the same distance`() {
        // This is what lets one search replace the old two-pass strategy: with honest prices, going
        // around simply wins on cost whenever it is faster.
        val terrain = flatTerrain()
        terrain.box(9, floorY + 1, 8, 9, floorY + 2, 8, NavBlockClass.SOLID_FULL)
        val planner = GroundPlanner(terrain, NavCaps(allowBreak = true))

        val buffer = expand(planner, 8, floorY + 1, 8)
        val dig = edgesOfKind(buffer, NavMove.DIG).first()
        val walk = edgesOfKind(buffer, NavMove.WALK).first()

        assertTrue(
            buffer.costs[dig] > buffer.costs[walk],
            "digging (${buffer.costs[dig]}) must cost more than walking (${buffer.costs[walk]})",
        )
    }

    @Test
    fun `pillaring requires blocks to place`() {
        val terrain = flatTerrain()

        val withoutBlocks = expand(
            GroundPlanner(terrain, NavCaps(allowPlace = true, placeableBlocks = 0)), 8, floorY + 1, 8,
        )
        val withBlocks = expand(
            GroundPlanner(terrain, NavCaps(allowPlace = true, placeableBlocks = 64)), 8, floorY + 1, 8,
        )

        assertTrue(edgesOfKind(withoutBlocks, NavMove.PILLAR).isEmpty(), "no blocks means no pillaring")
        assertTrue(edgesOfKind(withBlocks, NavMove.PILLAR).isNotEmpty())
    }

    @Test
    fun `a gap can be bridged once placing is allowed`() {
        val terrain = flatTerrain()
        // Remove the floor under the eastern neighbour, leaving a hole to bridge.
        terrain.set(9, floorY, 8, NavBlockClass.AIR)
        val planner = GroundPlanner(terrain, NavCaps(allowPlace = true, placeableBlocks = 64))

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(edgesOfKind(buffer, NavMove.PLACE).isNotEmpty(), "the hole should be bridgeable")
    }

    // -----------------------------------------------------------------------
    //  Parkour
    // -----------------------------------------------------------------------

    @Test
    fun `a two-block gap can be jumped`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY, 8, NavBlockClass.AIR)
        val planner = GroundPlanner(terrain, NavCaps(allowParkour = true, maxParkourGap = 3))

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(
            BlockPos.asLong(10, floorY + 1, 8) in destinations(buffer),
            "should jump the one-block hole to land at x = 10",
        )
        assertTrue(edgesOfKind(buffer, NavMove.PARKOUR).isNotEmpty())
    }

    @Test
    fun `parkour is not generated when the capability is off`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY, 8, NavBlockClass.AIR)
        val planner = GroundPlanner(terrain, NavCaps(allowParkour = false))

        val buffer = expand(planner, 8, floorY + 1, 8)

        assertTrue(edgesOfKind(buffer, NavMove.PARKOUR).isEmpty())
    }

    @Test
    fun `parkour will not jump onto ground it cannot land on`() {
        val terrain = flatTerrain()
        terrain.set(9, floorY, 8, NavBlockClass.AIR)
        terrain.set(10, floorY, 8, NavBlockClass.AIR)
        terrain.set(11, floorY, 8, NavBlockClass.AIR)
        val planner = GroundPlanner(terrain, NavCaps(allowParkour = true, maxParkourGap = 3))

        val buffer = expand(planner, 8, floorY + 1, 8)
        val landings = edgesOfKind(buffer, NavMove.PARKOUR).map { buffer.nodes[it] }

        for (landing in landings) {
            val supportClass = NavBlockClass.navClassOf(
                terrain.packed(BlockPos.getX(landing), BlockPos.getY(landing) - 1, BlockPos.getZ(landing)),
            )
            assertTrue(NavBlockClass.isStandable(supportClass), "parkour landed on nothing")
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private fun randomTerrain(random: Random, round: Int): NavTestTerrain {
        val terrain = NavTestTerrain(TERRAIN_SIZE, TERRAIN_HEIGHT, TERRAIN_SIZE)
        val classes = intArrayOf(
            NavBlockClass.AIR,
            NavBlockClass.AIR,
            NavBlockClass.SOLID_FULL,
            NavBlockClass.SOLID_FULL,
            NavBlockClass.WATER,
            NavBlockClass.LAVA,
            NavBlockClass.CLIMBABLE,
            NavBlockClass.COBWEB,
            NavBlockClass.HAZARD,
            NavBlockClass.HAZARD_SOLID,
            NavBlockClass.SLOW,
            NavBlockClass.FAST,
            NavBlockClass.SOLID_TOP_HALF,
            NavBlockClass.CARPET_LIKE,
            NavBlockClass.BLOCKING_TALL,
            NavBlockClass.OTHER_SOLID,
        )

        for (x in 0 until TERRAIN_SIZE) {
            for (y in 0 until TERRAIN_HEIGHT) {
                for (z in 0 until TERRAIN_SIZE) {
                    val navClass = classes[random.nextInt(classes.size)]
                    val digBucket = random.nextInt(0, NavBlockClass.DIG_UNBREAKABLE + 1)
                    terrain.set(x, y, z, navClass, digBucket)
                }
            }
        }
        // Keep a deterministic marker so failures name a reproducible round.
        terrain.set(0, 0, 0, NavBlockClass.SOLID_FULL, round % NavBlockClass.DIG_UNBREAKABLE)
        return terrain
    }

    private companion object {
        const val TERRAIN_SIZE = 10
        const val TERRAIN_HEIGHT = 12
        const val TOLERANCE = 1.0E-9
    }
}
