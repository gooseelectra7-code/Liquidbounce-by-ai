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

package net.ccbluex.liquidbounce.utils.navigation.pathing.search

import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.NavGoal
import net.minecraft.core.BlockPos

/**
 * Expands the successors of a node into a reusable buffer.
 *
 * Implemented by each planner: the ground planner writes walk/jump/dig edges, the boat planner
 * writes water edges, and so on. The move byte is opaque to the search — only the planner that
 * wrote it has to understand it.
 */
fun interface NavNeighborFunction {

    /**
     * Writes every edge leaving [node] into [out], which arrives already cleared.
     *
     * Called on the search thread, once per expanded node. Must not allocate.
     */
    fun expand(node: Long, out: NavEdgeBuffer)
}

/** Why a search stopped. Surfaced in the debug overlay, and used to decide how to react. */
enum class NavSearchOutcome {
    /** The goal was reached. [NavSearchResult.path] is a complete route. */
    GOAL,

    /** Search ran out of budget; the path leads to the closest node found so far. */
    BEST_EFFORT,

    /** Everything reachable was explored and the goal was not among it. */
    EXHAUSTED,

    /** The start position has no usable neighbours at all. */
    NO_START,
}

/** Budget and tuning for one search. */
data class NavSearchLimits(
    /** Hard cap on node expansions. */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,

    /** Abandon branches costing more than this many ticks. */
    val maxCost: Double = Double.POSITIVE_INFINITY,

    /**
     * Weighted-A* multiplier on the heuristic, at least 1.
     *
     * Above 1 the search dives at the goal, trading a path up to this factor from optimal for a
     * large cut in expansions. That trade is what makes travelling past the render distance
     * practical, since the useful part of such a path ends at the chunk frontier anyway.
     */
    val heuristicWeight: Double = 1.0,

    /**
     * Factor applied to every goal estimate, to keep it admissible for the mode being planned.
     *
     * Goals are written against a player on foot. Anything faster — a horse, a boat — makes edges
     * cheaper than those estimates assume, which turns the heuristic into an overestimate and
     * quietly costs optimality. Scaling here keeps the goals themselves free of any notion of who
     * is doing the travelling. Must not exceed one.
     */
    val heuristicScale: Double = 1.0,

    /** Wall-clock budget in nanoseconds, or [Long.MAX_VALUE] for none. */
    val timeoutNanos: Long = Long.MAX_VALUE,

    /**
     * How much extra distance-to-goal is worth accepting to end a best-effort path on the frontier,
     * in ticks.
     */
    val frontierTolerance: Double = DEFAULT_FRONTIER_TOLERANCE,
) {
    init {
        require(maxIterations > 0) { "maxIterations must be positive" }
        require(heuristicWeight >= 1.0) { "heuristicWeight must be at least 1.0" }
        require(heuristicScale > 0.0 && heuristicScale <= 1.0) { "heuristicScale must be in (0, 1]" }
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 20_000

        /** Roughly one block of travel. */
        const val DEFAULT_FRONTIER_TOLERANCE = 4.0
    }
}

/** A route plus everything worth knowing about how it was found. */
class NavSearchResult(
    /** Packed positions from start to end, inclusive. Always holds at least the start. */
    val path: LongArray,

    /** Planner move ids; `moves[i]` leads from `path[i]` to `path[i + 1]`. */
    val moves: ByteArray,

    /** Cost of [path] in ticks. */
    val totalCost: Double,

    /** Why the search stopped. */
    val outcome: NavSearchOutcome,

    /** Nodes expanded. The headline performance number. */
    val expansions: Int,

    /** Nodes discovered, expanded or not. */
    val discovered: Int,

    /** Largest number of nodes queued at once. */
    val peakOpen: Int,

    /** Wall-clock time spent searching. */
    val elapsedNanos: Long,
) {
    /** Did we actually get where we were asked to go? */
    val reachedGoal: Boolean get() = outcome == NavSearchOutcome.GOAL

    /** True when the route is a single node, i.e. there is nowhere to walk. */
    val isEmpty: Boolean get() = path.size < 2

    /** Last position on the route. */
    fun destination(): Long = path[path.size - 1]

    override fun toString(): String =
        "NavSearchResult($outcome, ${path.size} nodes, cost=%.1f, %d expansions in %.2fms)".format(
            totalCost, expansions, elapsedNanos / 1_000_000.0,
        )
}

/**
 * Reusable scratch for searches.
 *
 * Allocate one per planner and hand it to every search that planner runs. Replanning happens
 * constantly while following a path, and reusing these arrays is what keeps a replan from
 * re-allocating megabytes.
 *
 * Not thread-safe: one workspace belongs to one thread at a time.
 */
class NavSearchWorkspace(initialCapacity: Int = DEFAULT_CAPACITY) {

    internal val arena = NavNodeArena(initialCapacity)
    internal val heap = NavHeap(initialCapacity)
    internal val edges = NavEdgeBuffer()

    internal fun reset() {
        arena.clear()
        heap.clear()
        edges.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 1 shl 14
    }
}

/**
 * Weighted A* over packed block positions, allocation-free in its inner loop.
 *
 * ## Best effort
 *
 * The search never comes back empty-handed. When the budget runs out before the goal is reached, it
 * returns the route to the explored node closest to the goal. Following that partial route and
 * replanning from the far end is what gives the engine unbounded range.
 *
 * ## Frontier preference
 *
 * With [isFrontier] supplied, a partial route ending at the edge of the loaded world is preferred
 * over one ending in explored interior, as long as it is nearly as close to the goal. Walking to the
 * frontier streams in the next chunks; walking into a dead end just wastes the trip.
 *
 * ## Interruption
 *
 * [NavSearchLimits.timeoutNanos] and [cancelled] are checked every few hundred expansions, so a
 * search that is no longer wanted stops promptly instead of burning its whole iteration budget.
 */
object NavSearch {

    /** How often the clock and the cancellation flag are consulted, in expansions. */
    private const val INTERRUPT_CHECK_INTERVAL = 256

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun search(
        start: Long,
        goal: NavGoal,
        neighbors: NavNeighborFunction,
        workspace: NavSearchWorkspace,
        limits: NavSearchLimits = NavSearchLimits(),
        isFrontier: ((Long) -> Boolean)? = null,
        cancelled: (() -> Boolean)? = null,
    ): NavSearchResult {
        val startedAt = System.nanoTime()
        val deadline = if (limits.timeoutNanos == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            startedAt + limits.timeoutNanos
        }

        workspace.reset()
        val arena = workspace.arena
        val heap = workspace.heap
        val edges = workspace.edges

        val startId = arena.obtain(start)
        val startH = limits.heuristicScale *
            goal.heuristic(BlockPos.getX(start), BlockPos.getY(start), BlockPos.getZ(start))
        arena.gScore[startId] = 0.0
        arena.hScore[startId] = startH
        arena.state[startId] = NavNodeArena.OPEN
        heap.pushOrDecrease(startId, limits.heuristicWeight * startH)

        val best = BestEffortTracker(startId, startH)
        var expansions = 0
        var outcome = NavSearchOutcome.EXHAUSTED

        while (!heap.isEmpty) {
            if (expansions >= limits.maxIterations) {
                outcome = NavSearchOutcome.BEST_EFFORT
                break
            }
            if (expansions % INTERRUPT_CHECK_INTERVAL == 0 && expansions > 0 &&
                (System.nanoTime() >= deadline || cancelled?.invoke() == true)
            ) {
                outcome = NavSearchOutcome.BEST_EFFORT
                break
            }

            val currentId = heap.pop()
            val currentPos = arena.positions[currentId]
            val currentG = arena.gScore[currentId]
            arena.state[currentId] = NavNodeArena.CLOSED
            expansions++

            val x = BlockPos.getX(currentPos)
            val y = BlockPos.getY(currentPos)
            val z = BlockPos.getZ(currentPos)

            if (goal.isGoal(x, y, z)) {
                return finish(
                    arena, heap, currentId, NavSearchOutcome.GOAL,
                    expansions, startedAt,
                )
            }

            best.consider(currentId, arena.hScore[currentId], currentPos, isFrontier)

            edges.clear()
            neighbors.expand(currentPos, edges)

            for (edgeIndex in 0 until edges.size) {
                val neighborPos = edges.nodes[edgeIndex]
                val candidateG = currentG + edges.costs[edgeIndex]
                if (candidateG > limits.maxCost) continue

                val neighborId = arena.obtain(neighborPos)
                // Weighted A* keeps its closed set: a node's g is final once expanded, and the
                // resulting path stays within heuristicWeight of optimal. That bound is the whole
                // point of the weight.
                if (arena.state[neighborId] == NavNodeArena.CLOSED) continue
                if (candidateG >= arena.gScore[neighborId]) continue

                arena.gScore[neighborId] = candidateG
                arena.parent[neighborId] = currentId
                arena.incomingMove[neighborId] = edges.moves[edgeIndex]

                var neighborH = arena.hScore[neighborId]
                if (neighborH == Double.POSITIVE_INFINITY) {
                    neighborH = limits.heuristicScale * goal.heuristic(
                        BlockPos.getX(neighborPos),
                        BlockPos.getY(neighborPos),
                        BlockPos.getZ(neighborPos),
                    )
                    arena.hScore[neighborId] = neighborH
                }

                arena.state[neighborId] = NavNodeArena.OPEN
                heap.pushOrDecrease(neighborId, candidateG + limits.heuristicWeight * neighborH)
            }
        }

        val endId = best.select(limits.frontierTolerance)
        // Nothing beyond the start was ever discovered, so the start itself is walled in. A search
        // that did explore but found nowhere closer still reports its real outcome — the caller
        // reads NavSearchResult.isEmpty to see there is no route to walk.
        val finalOutcome = if (arena.size <= 1) NavSearchOutcome.NO_START else outcome
        return finish(arena, heap, endId, finalOutcome, expansions, startedAt)
    }

    private fun finish(
        arena: NavNodeArena,
        heap: NavHeap,
        endId: Int,
        outcome: NavSearchOutcome,
        expansions: Int,
        startedAt: Long,
    ): NavSearchResult = NavSearchResult(
        path = arena.reconstructPositions(endId),
        moves = arena.reconstructMoves(endId),
        totalCost = arena.gScore[endId],
        outcome = outcome,
        expansions = expansions,
        discovered = arena.size,
        peakOpen = heap.peakSize,
        elapsedNanos = System.nanoTime() - startedAt,
    )

    /**
     * Remembers where to stop when the goal is out of reach.
     *
     * Two candidates are tracked: the explored node closest to the goal, and the closest one that
     * also sits on the loaded-chunk frontier. The frontier node wins when it is nearly as good,
     * because stopping there is what lets the next chunks arrive.
     */
    private class BestEffortTracker(private val startId: Int, startH: Double) {

        private var bestId = startId
        private var bestH = startH
        private var frontierId = NavNodeArena.NOT_FOUND
        private var frontierH = Double.POSITIVE_INFINITY

        fun consider(id: Int, h: Double, pos: Long, isFrontier: ((Long) -> Boolean)?) {
            if (h < bestH) {
                bestH = h
                bestId = id
            }
            if (isFrontier != null && id != startId && h < frontierH && isFrontier(pos)) {
                frontierH = h
                frontierId = id
            }
        }

        fun select(frontierTolerance: Double): Int {
            if (frontierId != NavNodeArena.NOT_FOUND && frontierH <= bestH + frontierTolerance) {
                return frontierId
            }
            return bestId
        }
    }
}
