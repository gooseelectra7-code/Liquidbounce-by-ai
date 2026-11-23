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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchResult
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * A route the executor can follow: positions to visit and how to get between them.
 *
 * A thin, immutable view over what the search produced. Position `i + 1` is reached from position
 * `i` by move `i`, so there is always exactly one fewer move than position.
 */
class NavPath(
    /** Packed block positions, from where the search started to where it ended. */
    val positions: LongArray,

    /** Encoded moves; `moves[i]` leads from `positions[i]` to `positions[i + 1]`. */
    val moves: ByteArray,

    /** Predicted time to walk the whole route, in ticks. */
    val totalCost: Double,

    /** Whether this route actually arrives, or is a best-effort segment toward the goal. */
    val reachedGoal: Boolean,
) {

    init {
        require(moves.size == positions.size - 1 || positions.isEmpty()) {
            "a path needs exactly one move between each pair of positions"
        }
    }

    /** Number of positions on the route. */
    val size: Int get() = positions.size

    /** Number of moves to execute. */
    val moveCount: Int get() = moves.size

    /** True when there is nowhere to walk. */
    val isEmpty: Boolean get() = positions.size < 2

    fun xAt(index: Int): Int = BlockPos.getX(positions[index])

    fun yAt(index: Int): Int = BlockPos.getY(positions[index])

    fun zAt(index: Int): Int = BlockPos.getZ(positions[index])

    fun blockAt(index: Int): BlockPos = BlockPos(xAt(index), yAt(index), zAt(index))

    /** Centre of the block's footprint at floor level — where the executor actually steers. */
    fun centerAt(index: Int): Vec3 = Vec3(xAt(index) + 0.5, yAt(index).toDouble(), zAt(index) + 0.5)

    /** Like [centerAt], but null past the end, for lookahead that may run off the route. */
    fun centerAtOrNull(index: Int): Vec3? = if (index in positions.indices) centerAt(index) else null

    /** Kind of move `index`, one of the [NavMove] constants. */
    fun kindAt(index: Int): Int = NavMove.kindOf(moves[index])

    /** Direction of move `index`. */
    fun directionAt(index: Int): Int = NavMove.directionOf(moves[index])

    /** How many moves of each kind the route contains, for the debug overlay and chat summaries. */
    fun countByKind(): IntArray {
        val counts = IntArray(NavMove.KIND_COUNT)
        for (move in moves) {
            val kind = NavMove.kindOf(move)
            if (kind < counts.size) counts[kind]++
        }
        return counts
    }

    /** Human-readable breakdown, e.g. "walk 42, ascend 3, dig 7". */
    fun describeComposition(): String {
        val counts = countByKind()
        val parts = counts.indices
            .filter { counts[it] > 0 }
            .sortedByDescending { counts[it] }
            .joinToString(", ") { "${NavMove.nameOf(it)} ${counts[it]}" }
        return parts.ifEmpty { "empty" }
    }

    companion object {
        /** Wraps a finished search. */
        fun of(result: NavSearchResult): NavPath = NavPath(
            positions = result.path,
            moves = result.moves,
            totalCost = result.totalCost,
            reachedGoal = result.reachedGoal,
        )
    }
}
