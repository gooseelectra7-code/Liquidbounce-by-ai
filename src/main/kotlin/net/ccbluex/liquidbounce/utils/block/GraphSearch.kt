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
package net.ccbluex.liquidbounce.utils.block

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import java.util.PriorityQueue
import java.util.function.Predicate
import java.util.function.ToDoubleFunction

@JvmRecord
data class WeightedEdge<T>(
    val node: T,
    val cost: Double,
)

@JvmRecord
data class ShortestPath<T>(
    val nodes: List<T>,
    val totalCost: Double,
)

@JvmRecord
private data class QueueEntry<T>(
    val node: T,
    val gScore: Double,
    val fScore: Double,
    /** Raw (unweighted) heuristic for this node — used for best-effort closeness tracking. */
    val hScore: Double = 0.0,
)

/**
 * Finds the shortest path using A* search.
 *
 * Set [heuristic] to zero to get Dijkstra behavior.
 */
fun <T> aStarShortestPath(
    start: T,
    isGoal: Predicate<T>,
    neighbors: (T) -> Iterable<WeightedEdge<T>>,
    heuristic: ToDoubleFunction<T>,
    maxIterations: Int = Int.MAX_VALUE,
    maxCost: Double = Double.POSITIVE_INFINITY,
): ShortestPath<T>? {
    require(maxIterations > 0) { "maxIterations must be positive." }

    val gScores = Object2DoubleOpenHashMap<T>().apply {
        defaultReturnValue(Double.POSITIVE_INFINITY)
        put(start, 0.0)
    }
    val previous = Object2ObjectOpenHashMap<T, T>()

    val queue = PriorityQueue(Comparator.comparingDouble(ToDoubleFunction(QueueEntry<T>::fScore)))
    queue.add(QueueEntry(start, 0.0, heuristic.applyAsDouble(start)))

    var iterations = 0
    while (queue.isNotEmpty()) {
        iterations++
        if (iterations > maxIterations) {
            break
        }

        val current = queue.poll()
        val bestCurrentG = gScores.getDouble(current.node)
        if (current.gScore > bestCurrentG || current.gScore > maxCost) {
            continue
        }

        if (isGoal.test(current.node)) {
            return ShortestPath(reconstructPath(start, current.node, previous), current.gScore)
        }

        for (edge in neighbors(current.node)) {
            require(edge.cost >= 0.0) { "Path search edge costs must be non-negative." }

            val candidateG = current.gScore + edge.cost
            if (candidateG > maxCost) {
                continue
            }

            val knownG = gScores.getDouble(edge.node)
            if (candidateG >= knownG) {
                continue
            }

            gScores.put(edge.node, candidateG)
            previous.put(edge.node, current.node)

            val fScore = candidateG + heuristic.applyAsDouble(edge.node)
            queue.add(QueueEntry(edge.node, candidateG, fScore))
        }
    }

    return null
}

@JvmRecord
data class BestEffortPath<T>(
    val nodes: List<T>,
    val totalCost: Double,
    val reachedGoal: Boolean,
)

/**
 * A* that never gives up empty-handed: if the goal isn't reached within [maxIterations]/[maxCost],
 * it returns the path to the explored node with the lowest heuristic (closest to the goal).
 *
 * This enables unbounded-distance navigation via segment-by-segment replanning: walk toward the
 * best-so-far node, then re-plan from the new position. [BestEffortPath.reachedGoal] tells the
 * caller whether this is a final path or an intermediate segment.
 *
 * [heuristicWeight] turns this into weighted A*: the priority queue orders by `g + W·h`. With
 * `W > 1` the search dives toward the goal far more greedily — it reaches the loaded-chunk
 * frontier in the goal's direction within a much smaller iteration budget (key for travelling
 * past the render distance) at the cost of slightly suboptimal paths. `g` itself stays the true
 * cost, so the reconstructed path is still valid.
 *
 * [isFrontier], when supplied, marks nodes that sit on the loaded-chunk boundary. If the goal
 * isn't reached, a frontier node is preferred as the segment endpoint when it is essentially as
 * close to the goal as the overall best node (within [frontierTolerance], in raw-heuristic units).
 * This makes segments end where fresh chunks will stream in, without dragging us toward the map
 * edge when the genuinely-closest reachable node is interior (e.g. walled-off goal in render).
 *
 * Returns null only if there is genuinely nowhere to go (start has no usable neighbors).
 */
fun <T> aStarBestEffort(
    start: T,
    isGoal: Predicate<T>,
    neighbors: (T) -> Iterable<WeightedEdge<T>>,
    heuristic: ToDoubleFunction<T>,
    maxIterations: Int = Int.MAX_VALUE,
    maxCost: Double = Double.POSITIVE_INFINITY,
    heuristicWeight: Double = 1.0,
    isFrontier: ((T) -> Boolean)? = null,
    frontierTolerance: Double = 0.0,
): BestEffortPath<T>? {
    require(maxIterations > 0) { "maxIterations must be positive." }

    val gScores = Object2DoubleOpenHashMap<T>().apply {
        defaultReturnValue(Double.POSITIVE_INFINITY)
        put(start, 0.0)
    }
    val previous = Object2ObjectOpenHashMap<T, T>()

    val queue = PriorityQueue(Comparator.comparingDouble(ToDoubleFunction(QueueEntry<T>::fScore)))
    val startH = heuristic.applyAsDouble(start)
    queue.add(QueueEntry(start, 0.0, heuristicWeight * startH, startH))

    var bestNode = start
    var bestHeuristic = startH

    // Closest-to-goal node that also sits on the loaded-chunk frontier (if tracking is enabled).
    var bestFrontierNode: T? = null
    var bestFrontierHeuristic = Double.POSITIVE_INFINITY

    var iterations = 0
    while (queue.isNotEmpty()) {
        iterations++
        if (iterations > maxIterations) {
            break
        }

        val current = queue.poll()
        val bestCurrentG = gScores.getDouble(current.node)
        if (current.gScore > bestCurrentG || current.gScore > maxCost) {
            continue
        }

        if (isGoal.test(current.node)) {
            return BestEffortPath(reconstructPath(start, current.node, previous), current.gScore, true)
        }

        val h = current.hScore
        if (h < bestHeuristic) {
            bestHeuristic = h
            bestNode = current.node
        }
        if (isFrontier != null && current.node != start && h < bestFrontierHeuristic && isFrontier(current.node)) {
            bestFrontierHeuristic = h
            bestFrontierNode = current.node
        }

        for (edge in neighbors(current.node)) {
            require(edge.cost >= 0.0) { "Path search edge costs must be non-negative." }

            val candidateG = current.gScore + edge.cost
            if (candidateG > maxCost) {
                continue
            }

            val knownG = gScores.getDouble(edge.node)
            if (candidateG >= knownG) {
                continue
            }

            gScores.put(edge.node, candidateG)
            previous.put(edge.node, current.node)

            val hRaw = heuristic.applyAsDouble(edge.node)
            val fScore = candidateG + heuristicWeight * hRaw
            queue.add(QueueEntry(edge.node, candidateG, fScore, hRaw))
        }
    }

    // Prefer ending the segment at the loaded boundary when it's about as close to the goal.
    val frontierNode = bestFrontierNode
    if (frontierNode != null && frontierNode != start && bestFrontierHeuristic <= bestHeuristic + frontierTolerance) {
        return BestEffortPath(reconstructPath(start, frontierNode, previous), gScores.getDouble(frontierNode), false)
    }

    if (bestNode == start) return null
    return BestEffortPath(reconstructPath(start, bestNode, previous), gScores.getDouble(bestNode), false)
}

private fun <T> reconstructPath(
    start: T,
    goal: T,
    previous: Object2ObjectOpenHashMap<T, T>,
): List<T> {
    val reversedPath = mutableListOf<T>()

    var current = goal
    reversedPath.add(current)

    while (current != start) {
        val parent = previous[current]
            ?: return emptyList()
        current = parent
        reversedPath.add(current)
    }

    reversedPath.reverse()
    return reversedPath
}

// ---------------------------------------------------------------------------
//  Packed-long variant (no boxed keys) — used by the pathfinder hot path.
// ---------------------------------------------------------------------------

@JvmRecord
private data class QueueLongEntry(
    val node: Long,
    val gScore: Double,
    val fScore: Double,
    val hScore: Double = 0.0,
)

/**
 * Long-keyed A* best-effort search. Identical semantics to [aStarBestEffort], but the node space
 * is packed block positions (e.g. [net.minecraft.core.BlockPos.asLong]) so the open/closed maps
 * can use primitive `long` keys. This removes almost all per-iteration boxing in the pathfinder
 * hot path and is the key to fast route rebuilding.
 */
fun aStarBestEffortLong(
    start: Long,
    isGoal: (Long) -> Boolean,
    neighbors: (Long) -> Iterable<WeightedLongEdge>,
    heuristic: (Long) -> Double,
    maxIterations: Int = Int.MAX_VALUE,
    maxCost: Double = Double.POSITIVE_INFINITY,
    heuristicWeight: Double = 1.0,
    isFrontier: ((Long) -> Boolean)? = null,
    frontierTolerance: Double = 0.0,
): BestEffortPath<Long>? {
    require(maxIterations > 0) { "maxIterations must be positive." }
    return LongAStarSearch(
        start, isGoal, neighbors, heuristic, maxIterations, maxCost, heuristicWeight, isFrontier, frontierTolerance,
    ).run()
}

@JvmRecord
data class WeightedLongEdge(
    val node: Long,
    val cost: Double,
)

private class LongAStarSearch(
    private val start: Long,
    private val isGoal: (Long) -> Boolean,
    private val neighbors: (Long) -> Iterable<WeightedLongEdge>,
    private val heuristic: (Long) -> Double,
    private val maxIterations: Int,
    private val maxCost: Double,
    private val heuristicWeight: Double,
    private val isFrontier: ((Long) -> Boolean)?,
    private val frontierTolerance: Double,
) {
    private val gScores = Long2DoubleOpenHashMap().apply {
        defaultReturnValue(Double.POSITIVE_INFINITY)
        put(start, 0.0)
    }
    private val previous = Long2LongOpenHashMap().apply {
        defaultReturnValue(Long.MIN_VALUE)
    }
    private val queue = PriorityQueue<QueueLongEntry>(compareBy { it.fScore })
    private val tracker: LongSearchTracker

    init {
        val startH = heuristic(start)
        queue.add(QueueLongEntry(start, 0.0, heuristicWeight * startH, startH))
        tracker = LongSearchTracker(start, startH)
    }

    fun run(): BestEffortPath<Long>? {
        var iterations = 0
        while (queue.isNotEmpty()) {
            iterations++
            if (iterations > maxIterations) break

            val current = queue.poll()
            val bestCurrentG = gScores.get(current.node)
            if (current.gScore > bestCurrentG || current.gScore > maxCost) continue
            if (isGoal(current.node)) {
                return tracker.buildPath(gScores, previous, current.node, true)
            }

            tracker.update(current.node, current.hScore, isFrontier)
            relaxEdges(current)
        }
        return tracker.bestPath(gScores, previous, frontierTolerance)
    }

    private fun relaxEdges(current: QueueLongEntry) {
        for (edge in neighbors(current.node)) {
            require(edge.cost >= 0.0) { "Path search edge costs must be non-negative." }
            val candidateG = current.gScore + edge.cost
            if (candidateG > maxCost) continue
            val knownG = gScores.get(edge.node)
            if (candidateG >= knownG) continue
            gScores.put(edge.node, candidateG)
            previous.put(edge.node, current.node)
            val hRaw = heuristic(edge.node)
            val fScore = candidateG + heuristicWeight * hRaw
            queue.add(QueueLongEntry(edge.node, candidateG, fScore, hRaw))
        }
    }
}

private class LongSearchTracker(
    private val start: Long,
    startH: Double,
) {
    var bestNode = start
        private set
    var bestHeuristic = startH
        private set
    var bestFrontierNode: Long? = null
        private set
    var bestFrontierHeuristic = Double.POSITIVE_INFINITY
        private set

    fun update(node: Long, h: Double, isFrontier: ((Long) -> Boolean)?) {
        if (h < bestHeuristic) {
            bestHeuristic = h
            bestNode = node
        }
        if (isFrontier != null && node != start && h < bestFrontierHeuristic && isFrontier(node)) {
            bestFrontierHeuristic = h
            bestFrontierNode = node
        }
    }

    fun buildPath(
        gScores: Long2DoubleOpenHashMap,
        previous: Long2LongOpenHashMap,
        node: Long,
        reachedGoal: Boolean,
    ): BestEffortPath<Long> = BestEffortPath(
        reconstructPathLong(start, node, previous),
        gScores.get(node),
        reachedGoal,
    )

    fun bestPath(
        gScores: Long2DoubleOpenHashMap,
        previous: Long2LongOpenHashMap,
        frontierTolerance: Double,
    ): BestEffortPath<Long>? {
        val frontierNode = bestFrontierNode
        if (frontierNode != null && frontierNode != start &&
            bestFrontierHeuristic <= bestHeuristic + frontierTolerance
        ) {
            return buildPath(gScores, previous, frontierNode, false)
        }
        if (bestNode == start) return null
        return buildPath(gScores, previous, bestNode, false)
    }
}

private fun reconstructPathLong(
    start: Long,
    goal: Long,
    previous: Long2LongOpenHashMap,
): List<Long> {
    val reversedPath = LongArrayList()

    var current = goal
    reversedPath.add(current)

    while (current != start) {
        val parent = previous.get(current)
        if (parent == Long.MIN_VALUE) {
            return emptyList()
        }
        current = parent
        reversedPath.add(current)
    }

    reversedPath.reverse()
    return reversedPath
}
