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

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavTiming
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.NavSectionComponents.Companion.EDGE
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.NavSectionComponents.Companion.NONE
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavBlockSource
import net.minecraft.core.BlockPos
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * A map of the world at the scale of section-sized rooms, and the routes between them.
 *
 * The detailed search knows about single blocks and nothing else, which is why a long route makes it
 * expand a hundred thousand of them to discover what a glance at the terrain would have told it: the
 * mountain is on the left, go right. This layer is that glance. Its nodes are whole connected pockets
 * of standing room — one per [NavSectionComponents] component — so a search across it costs hundreds
 * of expansions where the detailed one costs hundreds of thousands, and what it finds becomes the
 * corridor the detailed search is steered along.
 *
 * ## What it is allowed to be wrong about
 *
 * Only the guidance. A corridor is never a wall: the detailed search may leave it whenever the
 * terrain says so, and pays for that in heuristic rather than being forbidden. That matters, because
 * the abstraction deliberately does not model jumps, falls or digging, and so sometimes says two
 * pockets are separate when the real planner could join them.
 *
 * ## Caching
 *
 * Components are built where a search actually looks, not for every chunk that loads — most sections
 * are never on anyone's route, and the ones that are get reused across every replan. A block change
 * drops its section's entry through [invalidate], and the next search rebuilds it.
 */
object NavSectionGraph {

    private val sections = java.util.concurrent.ConcurrentHashMap<Long, Entry>()

    /** Cached components for one section; [components] is null when nothing can stand in it. */
    private class Entry(val components: NavSectionComponents?)

    // -----------------------------------------------------------------------
    //  Cache
    // -----------------------------------------------------------------------

    /** Forgets the section holding this block, after a change to it. */
    fun invalidate(x: Int, y: Int, z: Int) {
        sections.remove(sectionKey(x shr 4, y shr 4, z shr 4))
    }

    /** Forgets every section of a chunk column, when that chunk goes away. */
    fun invalidateColumn(chunkX: Int, chunkZ: Int) {
        sections.keys.removeIf { sectionXOf(it) == chunkX && sectionZOf(it) == chunkZ }
    }

    /** Forgets everything, on a world change or when the cache is released. */
    fun clear() {
        sections.clear()
    }

    /** Sections currently held, for the debug overlay. */
    val cachedSections: Int get() = sections.size

    /**
     * What the cached pockets were flooded for.
     *
     * A pocket is "everywhere a *this* body can stand", so the same section has different pockets
     * for a player who may swim and one who may not, and different ones again for a horse that does
     * not fit where a player does. Keyed by position alone, a cache filled under one profile was
     * handed to the next: turning Swim on left every lake still marked impassable, and the corridor
     * kept routing round water the search was perfectly happy to cross.
     */
    @Volatile
    private var cachedFor: Int = 0

    /** The parts of a capability snapshot that change which cells count as standing room. */
    private fun fingerprintOf(caps: NavCaps): Int {
        var hash = if (caps.allowSwim) 1 else 0
        hash = 31 * hash + caps.bodyColumnRadius
        hash = 31 * hash + caps.bodyHeightBlocks
        return hash
    }

    private fun forgetIfBuiltForAnotherBody(caps: NavCaps) {
        val fingerprint = fingerprintOf(caps)
        if (fingerprint == cachedFor) return
        cachedFor = fingerprint
        sections.clear()
    }

    private fun componentsAt(source: NavBlockSource, sx: Int, sy: Int, sz: Int, caps: NavCaps): NavSectionComponents? =
        sections.computeIfAbsent(sectionKey(sx, sy, sz)) {
            Entry(NavSectionComponents.of(source, sx, sy, sz, caps))
        }.components

    // -----------------------------------------------------------------------
    //  Routing
    // -----------------------------------------------------------------------

    /**
     * Finds a coarse route from one position to another, or null when the abstraction cannot see one.
     *
     * Null is not "no route exists" — it is "this layer has nothing useful to say", which happens
     * when either end has no standing room, when the two ends are in pockets the abstraction cannot
     * join, or when the search runs out of budget. Every one of those simply leaves the detailed
     * search to work unguided, exactly as it did before this layer existed.
     */
    @Suppress("LongParameterList", "ReturnCount", "NestedBlockDepth", "CyclomaticComplexMethod")
    fun route(
        source: NavBlockSource,
        from: BlockPos,
        to: BlockPos,
        caps: NavCaps,
        maxExpansions: Int = DEFAULT_MAX_EXPANSIONS,
        timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS,
    ): NavCorridor? {
        forgetIfBuiltForAnotherBody(caps)

        val deadline = System.nanoTime() + timeoutNanos
        val start = nodeAt(source, from, caps) ?: return null
        val goal = nodeAt(source, to, caps) ?: return null
        if (start == goal) return null

        val goalCentre = representativeOf(source, goal, caps) ?: return null

        val gScore = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.POSITIVE_INFINITY) }
        val cameFrom = Long2LongOpenHashMap()
        val closed = LongOpenHashSet()
        val open = PriorityQueue<Candidate>(compareBy { it.f })

        gScore.put(start, 0.0)
        open.add(Candidate(start, heuristic(source, start, goalCentre, caps)))

        var expansions = 0
        while (open.isNotEmpty()) {
            if (expansions >= maxExpansions) return null
            // Building a pocket means flooding a whole section, so a search over unfamiliar ground
            // can be slow in a way the expansion count does not show. The detailed search has a
            // clock for the same reason; guidance that arrives late is worse than none.
            if (expansions and TIME_CHECK_MASK == 0 && System.nanoTime() > deadline) return null

            val current = open.poll()
            if (!closed.add(current.node)) continue
            expansions++

            if (current.node == goal) return corridorOf(source, cameFrom, goal, from, to, caps)

            val g = gScore.get(current.node)
            forEachNeighbour(source, current.node, caps) { neighbour, step ->
                if (!closed.contains(neighbour)) {
                    val tentative = g + step
                    if (tentative < gScore.get(neighbour)) {
                        gScore.put(neighbour, tentative)
                        cameFrom.put(neighbour, current.node)
                        open.add(Candidate(neighbour, tentative + heuristic(source, neighbour, goalCentre, caps)))
                    }
                }
            }
        }
        return null
    }

    private class Candidate(val node: Long, val f: Double)

    /**
     * Walks the pockets one step out from this one.
     *
     * A step is any single block move across the section boundary that the fill would have accepted
     * inside a section: a level step or one block up or down, plus the purely vertical step that
     * only a ladder or a water column can offer. Every destination is looked up in whichever section
     * actually contains it, so a step that crosses two faces at once — over a section corner — is
     * still found.
     */
    private inline fun forEachNeighbour(
        source: NavBlockSource,
        node: Long,
        caps: NavCaps,
        visit: (Long, Double) -> Unit,
    ) {
        val sx = sectionXOf(node)
        val sy = sectionYOf(node)
        val sz = sectionZOf(node)
        val component = componentOf(node)
        val own = componentsAt(source, sx, sy, sz, caps) ?: return

        val originX = sx * EDGE
        val originY = sy * EDGE
        val originZ = sz * EDGE

        val found = LongOpenHashSet()
        for (side in SIDES) {
            val faceX = side[0]
            val faceZ = side[1]

            // Cells of this section that touch the face, and the cells they could step into.
            for (along in 0 until EDGE) {
                for (level in 0 until EDGE) {
                    val localX = if (faceX != 0) (if (faceX > 0) EDGE - 1 else 0) else along
                    val localZ = if (faceZ != 0) (if (faceZ > 0) EDGE - 1 else 0) else along
                    if (own.localComponent(localX, level, localZ) != component) continue

                    val worldX = originX + localX + faceX
                    val worldZ = originZ + localZ + faceZ
                    for (rise in -1..1) {
                        val worldY = originY + level + rise
                        val other = componentAtWorld(source, worldX, worldY, worldZ, caps) ?: continue
                        found.add(other)
                    }
                }
            }
        }

        // Straight up and down, which only a ladder or a column of water ever offers.
        for (localX in 0 until EDGE) {
            for (localZ in 0 until EDGE) {
                if (own.localComponent(localX, EDGE - 1, localZ) == component) {
                    componentAtWorld(source, originX + localX, originY + EDGE, originZ + localZ, caps)
                        ?.let(found::add)
                }
                if (own.localComponent(localX, 0, localZ) == component) {
                    componentAtWorld(source, originX + localX, originY - 1, originZ + localZ, caps)
                        ?.let(found::add)
                }
            }
        }

        val centre = representativeOf(source, node, caps) ?: return
        val iterator = found.iterator()
        while (iterator.hasNext()) {
            val neighbour = iterator.nextLong()
            if (neighbour == node) continue
            val other = representativeOf(source, neighbour, caps) ?: continue
            visit(neighbour, distance(centre, other) * caps.minCostPerBlock)
        }
    }

    /** The node covering a world position, or null when nothing stands there. */
    private fun componentAtWorld(source: NavBlockSource, x: Int, y: Int, z: Int, caps: NavCaps): Long? {
        val components = componentsAt(source, x shr 4, y shr 4, z shr 4, caps) ?: return null
        val component = components.componentAt(x, y, z)
        if (component == NONE) return null
        return packNode(x shr 4, y shr 4, z shr 4, component)
    }

    /**
     * The node a position belongs to, looking a little way down when it does not stand on anything.
     *
     * A goal picked from the air — under a crosshair, in the middle of a jump — is common enough
     * that refusing to guide those routes would give up most of the benefit.
     */
    private fun nodeAt(source: NavBlockSource, pos: BlockPos, caps: NavCaps): Long? {
        for (drop in 0..MAX_GROUND_SEARCH) {
            componentAtWorld(source, pos.x, pos.y - drop, pos.z, caps)?.let { return it }
        }
        return null
    }

    private fun representativeOf(source: NavBlockSource, node: Long, caps: NavCaps): Long? {
        val components = componentsAt(source, sectionXOf(node), sectionYOf(node), sectionZOf(node), caps) ?: return null
        val component = componentOf(node)
        if (component >= components.representatives.size) return null
        return components.representatives[component]
    }

    private fun heuristic(source: NavBlockSource, node: Long, goalCentre: Long, caps: NavCaps): Double {
        val centre = representativeOf(source, node, caps) ?: return 0.0
        return distance(centre, goalCentre) * caps.minCostPerBlock
    }

    private fun corridorOf(
        source: NavBlockSource,
        cameFrom: Long2LongOpenHashMap,
        goal: Long,
        from: BlockPos,
        to: BlockPos,
        caps: NavCaps,
    ): NavCorridor {
        val nodes = ArrayList<Long>()
        var current = goal
        nodes.add(current)
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current)
            nodes.add(current)
        }
        nodes.reverse()

        val points = ArrayList<BlockPos>(nodes.size + 2)
        points.add(from)
        for (node in nodes) {
            val representative = representativeOf(source, node, caps) ?: continue
            points.add(
                BlockPos(
                    BlockPos.getX(representative),
                    BlockPos.getY(representative),
                    BlockPos.getZ(representative),
                ),
            )
        }
        points.add(to)

        // Priced in walking ticks, deliberately, and not in the ticks of whatever is carrying the
        // player. GuidedGoal compares this against the wrapped goal's own estimate, which is always
        // in walking ticks, and NavSearch then scales the result once for the mount. Building the
        // corridor in mount ticks meant that scale was applied to it twice, so the guidance faded
        // out exactly where it is worth most — on the long routes a horse is ridden for.
        return NavCorridor(points, NavTiming.MIN_COST_PER_BLOCK)
    }

    private fun distance(a: Long, b: Long): Double {
        val dx = (BlockPos.getX(a) - BlockPos.getX(b)).toDouble()
        val dy = (BlockPos.getY(a) - BlockPos.getY(b)).toDouble()
        val dz = (BlockPos.getZ(a) - BlockPos.getZ(b)).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // -----------------------------------------------------------------------
    //  Node packing
    // -----------------------------------------------------------------------

    private fun sectionKey(sx: Int, sy: Int, sz: Int): Long = packNode(sx, sy, sz, 0)

    private fun packNode(sx: Int, sy: Int, sz: Int, component: Int): Long =
        (sx.toLong() and SECTION_MASK shl SHIFT_X) or
            (sz.toLong() and SECTION_MASK shl SHIFT_Z) or
            (sy.toLong() and HEIGHT_MASK shl SHIFT_Y) or
            component.toLong()

    private fun sectionXOf(node: Long): Int = (node shr SHIFT_X).toInt()

    private fun sectionZOf(node: Long): Int = (node shl BITS_X shr (SHIFT_Z + BITS_X)).toInt()

    private fun sectionYOf(node: Long): Int = (node shl (BITS_X + BITS_Z) shr (SHIFT_Y + BITS_X + BITS_Z)).toInt()

    private fun componentOf(node: Long): Int = (node and COMPONENT_MASK).toInt()

    private const val BITS_X = 22
    private const val BITS_Z = 22
    private const val BITS_Y = 13
    private const val BITS_COMPONENT = 7

    private const val SHIFT_X = BITS_Z + BITS_Y + BITS_COMPONENT
    private const val SHIFT_Z = BITS_Y + BITS_COMPONENT
    private const val SHIFT_Y = BITS_COMPONENT

    private const val SECTION_MASK = (1L shl BITS_X) - 1
    private const val HEIGHT_MASK = (1L shl BITS_Y) - 1
    private const val COMPONENT_MASK = (1L shl BITS_COMPONENT) - 1

    /** The four section faces a level step can cross. */
    private val SIDES = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0),
        intArrayOf(0, 1), intArrayOf(0, -1),
    )

    /** Blocks to look down from a position that is not itself standable, to find the ground under it. */
    private const val MAX_GROUND_SEARCH = 8

    /**
     * Pockets the coarse search may expand.
     *
     * Each is a room-sized piece of world, so this is a very long way indeed — far enough that
     * running out means the terrain is a maze, and a maze is not something a corridor helps with.
     */
    const val DEFAULT_MAX_EXPANSIONS = 4_000

    /**
     * Wall-clock budget for building the coarse route.
     *
     * This runs before the detailed search and adds to it, so it needs a ceiling — but not a tight
     * one, for two reasons. Building a pocket the first time is far slower than every time after
     * it, cold code being what it is, and the caller reuses a corridor for as long as it still
     * describes where we are, so this is paid once a journey rather than once a replan.
     *
     * Running out is not a failure: the route is then planned exactly as it was before this layer
     * existed, only without the head start.
     */
    const val DEFAULT_TIMEOUT_NANOS = 120_000_000L

    /** Check the clock every so many expansions, since reading it is not free either. */
    private const val TIME_CHECK_MASK = 63
}
