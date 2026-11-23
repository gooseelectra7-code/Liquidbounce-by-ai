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
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavNeighborFunction
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.minecraft.core.BlockPos
import kotlin.math.max

/**
 * Generates every way a walking player can leave a block position.
 *
 * A node is the block the player's feet occupy. The planner is a [NavNeighborFunction], so the
 * search drives it directly; it allocates nothing and reads the world only through
 * [NavBlockSource], which is what lets it run off the main thread and be tested against synthetic
 * terrain.
 *
 * Which move kinds are generated follows from [caps]. In particular there is no separate
 * "walking pass" and "digging pass" as in the previous engine: dig and place edges are simply
 * priced at what they really cost, so a single search prefers walking whenever walking is faster,
 * and turning [NavCaps.allowBreak] off just stops those edges from being emitted.
 */
class GroundPlanner(
    private val source: NavBlockSource,
    private val caps: NavCaps,
) : NavNeighborFunction {

    override fun expand(node: Long, out: NavEdgeBuffer) {
        val x = BlockPos.getX(node)
        val y = BlockPos.getY(node)
        val z = BlockPos.getZ(node)

        val feetClass = source.navClass(x, y, z)

        // Swimming replaces ordinary ground movement rather than adding to it: a player in water is
        // not walking anywhere.
        if (caps.allowSwim && feetClass == NavBlockClass.WATER) {
            addSwimMoves(x, y, z, out)
            return
        }

        addCardinalMoves(x, y, z, out)
        addDiagonalMoves(x, y, z, out)
        addClimbMoves(x, y, z, feetClass, out)

        if (caps.allowParkour) addParkourMoves(x, y, z, out)
        if (caps.allowBreak) addDigMoves(x, y, z, out)
        if (caps.allowPlace) addPlaceMoves(x, y, z, out)
        if (caps.allowBreak && caps.allowPlace) addDigUpMove(x, y, z, out)
    }

    // -----------------------------------------------------------------------
    //  Predicates
    // -----------------------------------------------------------------------

    /** Can a two-block-tall body occupy the column whose feet are at this position? */
    private fun bodyFits(x: Int, y: Int, z: Int): Boolean =
        NavStandingSpot.bodyFits(source, x, y, z, caps)

    /**
     * Is one level of the body's footprint clear, ignoring everything above and below it?
     *
     * For sweeps that pass a body through a level rather than parking it there — the column a fall
     * drops down, the cells a jump crosses. A player is under a block wide and this is one read;
     * anything wider overlaps its neighbours and has to be asked about all of them.
     */
    private fun levelIsClear(x: Int, y: Int, z: Int): Boolean {
        val radius = caps.bodyColumnRadius
        if (radius == 0) return NavBlockClass.isPassable(source.navClass(x, y, z))

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (!NavBlockClass.isPassable(source.navClass(x + dx, y, z + dz))) return false
            }
        }
        return true
    }

    /**
     * Can the player hold this position, whether by standing, floating or hanging on?
     *
     * Shared with everything that resolves a user's target into a reachable goal, so the two can
     * never drift into disagreeing about what counts as a place to stand.
     */
    private fun canOccupy(x: Int, y: Int, z: Int): Boolean =
        NavStandingSpot.canOccupy(source, x, y, z, caps)

    /**
     * Will a block placed into this cell actually end up in it?
     *
     * Air and fluids give way; a ladder, a cobweb, a carpet or a berry bush do not, however freely
     * a body walks through them — the block lands against their face instead, one cell short of
     * where the route wanted it.
     */
    private fun isReplaceable(navClass: Int): Boolean = when (navClass) {
        NavBlockClass.AIR, NavBlockClass.WATER -> true
        else -> false
    }

    /**
     * Can a body standing in this cell jump at all?
     *
     * A cobweb, powder snow, a berry bush and water each hold the body in place or slow the launch
     * to nothing, so a jump from one covers a fraction of its usual distance.
     */
    private fun canTakeOffFrom(feetClass: Int): Boolean = when (feetClass) {
        NavBlockClass.COBWEB, NavBlockClass.HAZARD, NavBlockClass.WATER -> false
        else -> true
    }

    /** Cost of entering a position on foot, including terrain and hazards. */
    private fun enterCost(x: Int, y: Int, z: Int): Double = NavCostModel.stepCost(
        surfaceClass = source.navClass(x, y - 1, z),
        feetClass = source.navClass(x, y, z),
        headClass = source.navClass(x, y + 1, z),
        caps = caps,
    )

    // -----------------------------------------------------------------------
    //  Level and vertical steps
    // -----------------------------------------------------------------------

    private fun addCardinalMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        for (direction in NavMove.CARDINALS) {
            val nx = x + NavMove.OFFSET_X[direction]
            val nz = z + NavMove.OFFSET_Z[direction]

            if (canOccupy(nx, y, nz)) {
                out.add(pack(nx, y, nz), enterCost(nx, y, nz), NavMove.encode(NavMove.WALK, direction))
            }
            addAscend(x, y, z, nx, nz, direction, out)
            addDescendOrFall(x, y, z, nx, nz, direction, out)
        }
    }

    /**
     * Steps up onto a higher neighbour, by one block or by several.
     *
     * A player only ever manages one, but a horse clears far more, and that changes routing
     * substantially: terrain that forces a long detour on foot is a straight line on horseback. The
     * climb has to be clear the whole way, since the body passes through every level of the
     * departing column on the way up.
     */
    private fun addAscend(x: Int, y: Int, z: Int, nx: Int, nz: Int, direction: Int, out: NavEdgeBuffer) {
        for (rise in 1..caps.maxStepUp) {
            // Headroom over the departing column is what the jump actually needs; without it we
            // clip our head and never leave the ground. Measured from the top of the body rather
            // than from a hardcoded second block: a camel is three cells tall and would otherwise
            // have its head checked against a level it has already cleared.
            if (!NavBlockClass.isPassable(source.navClass(x, y + rise + caps.bodyHeightBlocks - 1, z))) return
            // Refuses a fence top by itself: BLOCKING_TALL is not standable, because its surface is
            // a block and a half up and a jump reaches a block and a quarter.
            if (!canOccupy(nx, y + rise, nz)) continue

            val cost = enterCost(nx, y + rise, nz) + NavCostModel.ascendPenalty() * rise
            out.add(pack(nx, y + rise, nz), cost, NavMove.encode(NavMove.ASCEND, direction))
        }
    }

    /**
     * Emits the single step down and every longer drop from the same edge.
     *
     * Both start by walking horizontally into the neighbouring column, so they share the same
     * clearance requirement and differ only in how far the player then falls.
     */
    private fun addDescendOrFall(x: Int, y: Int, z: Int, nx: Int, nz: Int, direction: Int, out: NavEdgeBuffer) {
        // The whole body passes through the neighbouring column at the current height before
        // dropping, so it needs the same room a step onto that column would need. Checking one
        // column and two levels here — while WALK asks bodyFits — let a camel, which is three cells
        // tall and overlaps its neighbours, step off a ledge into a gap it does not fit in.
        if (!bodyFits(nx, y, nz)) return

        val horizontal = NavCostModel.stepCost(
            surfaceClass = source.navClass(x, y - 1, z),
            feetClass = source.navClass(nx, y, nz),
            headClass = source.navClass(nx, y + 1, nz),
            caps = caps,
        )

        // Always scan the full column rather than stopping at the capability's drop limit: a pool of
        // water forty blocks down is a perfectly safe landing, and cutting the scan short at three
        // would hide it. Deciding what is survivable is the cost model's job, not the scan's.
        for (drop in 1..MAX_SEARCHED_DROP) {
            val destY = y - drop
            val supportClass = source.navClass(nx, destY - 1, nz)

            // The falling body sweeps the whole column, one level at a time, and it is as wide on
            // the way down as it is on the way in.
            if (!levelIsClear(nx, destY, nz)) break

            if (NavBlockClass.isStandable(supportClass)) {
                emitDrop(nx, destY, nz, drop, horizontal, direction, out)
                // Anything below this is behind solid ground.
                break
            }
            // Water and cobweb stop a fall without being standable; landing in them is a valid end.
            if (NavBlockClass.breaksFall(source.navClass(nx, destY, nz))) {
                emitDrop(nx, destY, nz, drop, horizontal, direction, out)
                break
            }
        }
    }

    private fun emitDrop(
        nx: Int,
        destY: Int,
        nz: Int,
        drop: Int,
        horizontal: Double,
        direction: Int,
        out: NavEdgeBuffer,
    ) {
        // Room for the body where it comes to rest, not merely for a player's head.
        if (!bodyFits(nx, destY, nz)) return

        val landingClass = source.navClass(nx, destY, nz)
        val fall = NavCostModel.fallCost(drop, landingClass, caps)
        if (!fall.isFinite()) return

        val kind = if (drop == 1) NavMove.DESCEND else NavMove.FALL
        val hazards = NavCostModel.hazardPenalty(landingClass, caps) +
            NavCostModel.surfaceHazardPenalty(source.navClass(nx, destY - 1, nz), caps)
        // The larger of the two, not their sum: a body walks off a ledge and keeps travelling while
        // it falls, so the step and the drop happen at the same time rather than one after the
        // other. Adding them charged a one-block step down at more than twice what it costs, which
        // is exactly the margin by which mining the floor with a good shovel looked cheaper than
        // stepping off it. On a long drop the fall dominates and nothing changes.
        val travel = max(horizontal, fall)
        out.add(pack(nx, destY, nz), travel + hazards, NavMove.encode(kind, direction))
    }

    private fun addDiagonalMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        for (direction in NavMove.DIAGONALS) {
            val dx = NavMove.OFFSET_X[direction]
            val dz = NavMove.OFFSET_Z[direction]
            val nx = x + dx
            val nz = z + dz

            if (!canOccupy(nx, y, nz)) continue
            // Anti-corner-cut: a player cannot squeeze diagonally between two solid blocks.
            if (!bodyFits(x + dx, y, z)) continue
            if (!bodyFits(x, y, z + dz)) continue

            val cost = enterCost(nx, y, nz) * NavTiming.DIAGONAL_MULTIPLIER
            out.add(pack(nx, y, nz), cost, NavMove.encode(NavMove.DIAGONAL, direction))
        }
    }

    /**
     * Up and down a ladder or a vine.
     *
     * Both directions have to end somewhere the body can actually hold itself, which for a climb
     * means either more of the ladder or a place to stand. A rung above the last one is mid-air: the
     * search would expand it as though it were ground and walk out of it sideways. A cell below the
     * bottom rung is worse — it is a step off the ladder into a shaft, and because no fall edge was
     * ever consulted it is priced at four ticks and ignores the drop limit entirely. Stepping out
     * one level up at the top is already covered by [addAscend] from the ladder cell itself.
     */
    private fun addClimbMoves(x: Int, y: Int, z: Int, feetClass: Int, out: NavEdgeBuffer) {
        if (feetClass != NavBlockClass.CLIMBABLE) return

        val above = source.navClass(x, y + 1, z)
        if (bodyFits(x, y + 1, z) && (above == NavBlockClass.CLIMBABLE || canOccupy(x, y + 1, z))) {
            out.add(
                pack(x, y + 1, z),
                NavTiming.CLIMB_UP_PER_BLOCK,
                NavMove.encode(NavMove.CLIMB, NavMove.VERTICAL),
            )
        }

        val below = source.navClass(x, y - 1, z)
        if (below == NavBlockClass.CLIMBABLE || canOccupy(x, y - 1, z)) {
            out.add(
                pack(x, y - 1, z),
                NavTiming.CLIMB_DOWN_PER_BLOCK,
                NavMove.encode(NavMove.CLIMB, NavMove.VERTICAL),
            )
        }
    }

    /**
     * Swimming, which happens on the surface and nowhere else.
     *
     * Vanilla starts the swimming pose with the eyes under water and keeps it while any part of the
     * body is in water, so a crossing dips once and is then swum along the top at full speed for no
     * air at all. That makes a submerged route strictly worse than a surface one — same distance,
     * same speed, and a drowning clock running — so the search is not offered it. Depth belongs to
     * the single dip the executor makes to get going, not to the plan.
     *
     * A swimmer who is already under, having fallen in or waded off a shelf, gets exactly one move:
     * upward. That is how they rejoin the surface, and it is the only reason to be down there.
     */
    private fun addSwimMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        if (!isSurfaceWater(x, y, z)) {
            if (bodyFits(x, y + 1, z)) {
                out.add(pack(x, y + 1, z), swimCost(x, y + 1, z), NavMove.encode(NavMove.SWIM, NavMove.VERTICAL))
            }
            return
        }

        for (direction in NavMove.CARDINALS) {
            addSurfaceSwim(x, y, z, direction, diagonal = false, out)
        }

        // Diagonals matter more in water than on land: a swimmer has no run of level steps for the
        // executor to smooth into a straight line, so a four-connected lattice is swum exactly as
        // planned — a staircase across the lake, at nine ticks a block.
        for (direction in NavMove.DIAGONALS) {
            val dx = NavMove.OFFSET_X[direction]
            val dz = NavMove.OFFSET_Z[direction]
            // The same corner rule as on foot: a body cannot squeeze between two blocks, and being
            // in water does not make it any thinner.
            if (!bodyFits(x + dx, y, z) || !bodyFits(x, y, z + dz)) continue
            addSurfaceSwim(x, y, z, direction, diagonal = true, out)
        }

        addWadeOutMoves(x, y, z, out)
    }

    /**
     * One swim edge in a direction, following the water level up or down a step if it changes.
     *
     * Rivers and shorelines are not flat: the surface drops a block over a weir and rises against a
     * bank, and a swimmer follows it without ever going under. Level first, then either neighbour.
     */
    private fun addSurfaceSwim(x: Int, y: Int, z: Int, direction: Int, diagonal: Boolean, out: NavEdgeBuffer) {
        val nx = x + NavMove.OFFSET_X[direction]
        val nz = z + NavMove.OFFSET_Z[direction]

        for (rise in SURFACE_STEPS) {
            val ny = y + rise
            if (!isSurfaceWater(nx, ny, nz) || !bodyFits(nx, ny, nz)) continue

            val cost = swimCost(nx, ny, nz) * if (diagonal) NavTiming.DIAGONAL_MULTIPLIER else 1.0
            out.add(pack(nx, ny, nz), cost, NavMove.encode(NavMove.SWIM, direction))
            return
        }
    }

    /**
     * Climbing out onto the bank.
     *
     * Without it a route can swim into a lake and never leave it: every other move from a water cell
     * leads to more water, and walking moves are not generated for a body that is swimming.
     */
    private fun addWadeOutMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        for (direction in NavMove.CARDINALS) {
            val nx = x + NavMove.OFFSET_X[direction]
            val nz = z + NavMove.OFFSET_Z[direction]

            // Down as well as level and up. A puddle on a ledge — one block of water with a drop off
            // its edge — offered no exit in that direction at all, though walking out of one is a
            // single step down; and walking *in* is always allowed, so it was a one-way trap.
            for (rise in WADE_STEPS) {
                val ny = y + rise
                if (source.navClass(nx, ny, nz) == NavBlockClass.WATER) continue
                if (!canOccupy(nx, ny, nz)) continue

                out.add(
                    pack(nx, ny, nz),
                    NavTiming.SWIM_PER_BLOCK + enterCost(nx, ny, nz),
                    NavMove.encode(NavMove.SWIM, direction),
                )
                break
            }
        }
    }

    /** Water with something other than more water above it — the layer a swimmer rides. */
    private fun isSurfaceWater(x: Int, y: Int, z: Int): Boolean =
        source.navClass(x, y, z) == NavBlockClass.WATER &&
            source.navClass(x, y + 1, z) != NavBlockClass.WATER

    /**
     * Cost of swimming into a cell.
     *
     * The only submerged cells a route ever contains are the ones on the way back up out of deep
     * water, and those are still priced for the air they cost.
     */
    private fun swimCost(x: Int, y: Int, z: Int): Double {
        val submerged = source.navClass(x, y + 1, z) == NavBlockClass.WATER
        val depth = if (submerged) NavCostModel.SUBMERGED_PENALTY else 0.0
        return NavTiming.SWIM_PER_BLOCK + depth + NavCostModel.hazardPenalty(source.navClass(x, y, z), caps)
    }

    // -----------------------------------------------------------------------
    //  Parkour
    // -----------------------------------------------------------------------

    /**
     * Jumps straight across a gap of two to [NavCaps.maxParkourGap] blocks.
     *
     * Only cardinal jumps are generated. Diagonal parkour is far more sensitive to the exact
     * take-off speed, and a route that needs it is one the executor would frequently fail to
     * follow — a plan the body cannot carry out is worse than a slightly longer one.
     */
    private fun addParkourMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        if (!NavBlockClass.isStandable(source.navClass(x, y - 1, z))) return
        if (!NavBlockClass.isPassable(source.navClass(x, y + 2, z))) return
        // You cannot sprint-jump out of a cobweb, out of powder snow, or from a cell that is holding
        // the body up in water. The executor's own jump simulation refuses these, so an edge from
        // one is a move that stalls to its deadline and replans into itself.
        if (!canTakeOffFrom(source.navClass(x, y, z))) return

        for (direction in NavMove.CARDINALS) {
            val dx = NavMove.OFFSET_X[direction]
            val dz = NavMove.OFFSET_Z[direction]

            // Distance is where the landing block is; the cap counts the gap, which is one less.
            for (distance in 2..caps.maxParkourGap + 1) {
                val nx = x + dx * distance
                val nz = z + dz * distance

                // Everything strictly between take-off and landing has to be open at body height,
                // and must genuinely be a gap — if it were standable we would simply walk.
                val gapX = x + dx * (distance - 1)
                val gapZ = z + dz * (distance - 1)
                if (!bodyFits(gapX, y, gapZ)) break
                if (!NavBlockClass.isPassable(source.navClass(gapX, y + 2, gapZ))) break
                if (NavBlockClass.isStandable(source.navClass(gapX, y - 1, gapZ))) break

                if (!canOccupy(nx, y, nz)) continue
                if (!NavBlockClass.isStandable(source.navClass(nx, y - 1, nz))) continue
                // The landing column needs the same jump clearance as the take-off and the gap: the
                // body is still near the top of its arc when it crosses into it, so a ceiling one
                // block above head height there is a ceiling the jump hits.
                if (!NavBlockClass.isPassable(source.navClass(nx, y + 2, nz))) continue

                val cost = enterCost(nx, y, nz) * distance + NavTiming.JUMP_PENALTY
                out.add(pack(nx, y, nz), cost, NavMove.encode(NavMove.PARKOUR, direction))
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Mining and building
    // -----------------------------------------------------------------------

    private fun addDigMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        for (direction in NavMove.CARDINALS) {
            val nx = x + NavMove.OFFSET_X[direction]
            val nz = z + NavMove.OFFSET_Z[direction]

            // Nothing to dig means an ordinary walk already covers this edge.
            if (bodyFits(nx, y, nz)) continue
            if (!NavBlockClass.isStandable(source.navClass(nx, y - 1, nz))) continue

            val digCost = clearingCost(nx, y, nz) + clearingCost(nx, y + 1, nz)
            if (!digCost.isFinite()) continue

            out.add(
                pack(nx, y, nz),
                digCost + caps.groundTicksPerBlock,
                NavMove.encode(NavMove.DIG, direction),
            )
        }

        addDigDownMove(x, y, z, out)
    }

    private fun addDigDownMove(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        val belowClass = source.navClass(x, y - 1, z)
        if (NavBlockClass.isPassable(belowClass)) return

        val digCost = clearingCost(x, y - 1, z)
        if (!digCost.isFinite()) return
        // Never mine out from under ourselves into a drop we cannot survive, or into lava.
        val landingClass = source.navClass(x, y - 2, z)
        if (landingClass == NavBlockClass.LAVA) return
        // The destination of this move is the cell being dug out, and the body has to be able to
        // hold it once it is empty. A soft landing two below stops a fall, but it does not hold a
        // body up one block above itself: dig the floor out over open water and the player ends up
        // in the water, a block below the waypoint the route insists they are standing on.
        if (!NavBlockClass.isStandable(landingClass)) return

        out.add(pack(x, y - 1, z), digCost, NavMove.encode(NavMove.DIG_DOWN, NavMove.VERTICAL))
    }

    private fun addDigUpMove(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        if (caps.placeableBlocks <= 0) return
        if (!NavBlockClass.isStandable(source.navClass(x, y - 1, z))) return

        val digCost = clearingCost(x, y + 2, z)
        if (!digCost.isFinite()) return

        val cost = digCost + NavCostModel.placeCost(caps) + NavTiming.JUMP_PENALTY
        out.add(pack(x, y + 1, z), cost, NavMove.encode(NavMove.DIG_UP, NavMove.VERTICAL))
    }

    private fun addPlaceMoves(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        if (caps.placeableBlocks <= 0) return

        addPillarMove(x, y, z, out)

        // Bridging: the destination is open but has nothing underneath, so we lay a block first.
        for (direction in NavMove.CARDINALS) {
            val nx = x + NavMove.OFFSET_X[direction]
            val nz = z + NavMove.OFFSET_Z[direction]

            if (!bodyFits(nx, y, nz)) continue
            val supportClass = source.navClass(nx, y - 1, nz)
            if (NavBlockClass.isStandable(supportClass)) continue
            // Passable is not the same as replaceable. A ladder, a vine, a cobweb and a carpet are
            // all walked through, and a block right-clicked at one of them lands on its face
            // instead of in its cell — so the support the route was promised never appears and the
            // move runs to its deadline.
            if (!isReplaceable(supportClass)) continue

            val cost = NavCostModel.placeCost(caps) + caps.groundTicksPerBlock
            if (!cost.isFinite()) continue
            out.add(pack(nx, y, nz), cost, NavMove.encode(NavMove.PLACE, direction))
        }
    }

    private fun addPillarMove(x: Int, y: Int, z: Int, out: NavEdgeBuffer) {
        if (!NavBlockClass.isStandable(source.navClass(x, y - 1, z))) return
        if (!bodyFits(x, y + 1, z)) return

        val cost = NavCostModel.placeCost(caps) + NavTiming.JUMP_PENALTY
        if (!cost.isFinite()) return
        out.add(pack(x, y + 1, z), cost, NavMove.encode(NavMove.PILLAR, NavMove.VERTICAL))
    }

    /**
     * Ticks to make a cell passable, which is zero when it already is and infinite when the block
     * there cannot be broken.
     */
    private fun clearingCost(x: Int, y: Int, z: Int): Double {
        val packed = source.packed(x, y, z)
        val navClass = NavBlockClass.navClassOf(packed)
        if (NavBlockClass.isPassable(navClass)) return 0.0
        // Digging into a fluid floods the tunnel, and unknown blocks are exactly what we refuse to
        // guess about.
        if (NavBlockClass.isFluid(navClass) || navClass == NavBlockClass.UNKNOWN) {
            return Double.POSITIVE_INFINITY
        }
        // Nor is it enough that the block itself is dry: a block is also a wall, and taking it out
        // opens whatever it was holding back. Lava behind a stone face does not announce itself
        // until the moment the pick goes through, and by then the tunnel — and the player standing
        // in it — is where the lava is going.
        if (touchesFluid(x, y, z)) return Double.POSITIVE_INFINITY

        return NavCostModel.breakCost(NavBlockClass.digBucketOf(packed), caps)
    }

    /**
     * Does anything wet or molten touch this block?
     *
     * All six sides, because a fluid flows down as readily as sideways and the one above is the
     * worst of them: break the floor of a pool and it comes down the hole.
     */
    private fun touchesFluid(x: Int, y: Int, z: Int): Boolean {
        for (side in FLUID_PROBE) {
            if (NavBlockClass.isFluid(source.navClass(x + side[0], y + side[1], z + side[2]))) return true
        }
        return false
    }

    private fun pack(x: Int, y: Int, z: Int): Long = BlockPos.asLong(x, y, z)

    private companion object {
        /**
         * How far down a column is scanned for a landing.
         *
         * The cost model refuses anything lethal, so this only bounds the work done before giving
         * up on a column. Deep enough to find the water at the bottom of a ravine.
         */
        const val MAX_SEARCHED_DROP = 32

        /**
         * Heights a surface swim edge will look for the water level at, in order of preference.
         *
         * Level first, then a step up or down — enough to follow a river over a weir or up against
         * a bank without ever putting the head under.
         */
        val SURFACE_STEPS = intArrayOf(0, 1, -1)

        /**
         * Heights a wade-out edge will look for the bank at, in order of preference.
         *
         * Nearest first: stepping straight out beats hauling up onto a ledge, and both beat
         * dropping off the edge of the water into whatever is below it.
         */
        val WADE_STEPS = intArrayOf(0, 1, -1)

        /** The six sides checked for fluid before a block is allowed to be broken. */
        val FLUID_PROBE = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        )
    }
}
