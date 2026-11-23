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

package net.ccbluex.liquidbounce.features.module.modules.world.automine.target

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import net.ccbluex.liquidbounce.features.module.modules.world.automine.AutoMineDebug
import net.ccbluex.liquidbounce.features.module.modules.world.automine.ModuleAutoMine
import net.ccbluex.liquidbounce.utils.block.isNotBreakable
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.core.BlockPos
import kotlin.math.sqrt

/**
 * Which of the indexed blocks to go and get.
 *
 * **The rule: the nearest one that is inside the bounds, is not being avoided, and — when asked — has
 * a face open to the air.** Nothing else is weighed. No ore is worth more than another, distance is
 * measured from the eyes, and ties do not matter.
 *
 * That flatness is on purpose. A scoring function with three terms is a thing nobody can predict from
 * outside, and "why did it go *there*?" then has no answer short of reading the code. One rule means
 * the log line naming the choice is enough to check it.
 *
 * ## Why the expensive tests only run on improvements
 *
 * The index can hold tens of thousands of positions — coal alone, across a full render distance, does
 * that. Distance is arithmetic on numbers already in hand; exposure is six reads of the navigation
 * cache and breakability is a chunk lookup. So the loop tests distance first and only asks the
 * expensive questions when a candidate is already closer than the best one so far, which in practice
 * happens a handful of times per pass.
 */
object TargetPicker {

    /**
     * Blocks not to choose again for a while, and until when.
     *
     * A block the job could not reach stays the nearest one, so without this it would be chosen again
     * on the very next tick, for ever. Timed rather than permanent because the reason is usually
     * temporary — something in the way that is itself about to be mined.
     */
    private val refused = Long2LongOpenHashMap()

    fun clear() {
        refused.clear()
    }

    fun refuse(pos: BlockPos, forMs: Long = REFUSAL_MS) {
        refused.put(pos.asLong(), System.currentTimeMillis() + forMs)
    }

    /**
     * The nearest block worth mining, or null when the index has nothing to offer.
     *
     * @param maxRange how far away a target may be, in blocks
     * @param exposedOnly refuse anything with no face open to the air — the "only what I can see" rule
     */
    fun best(maxRange: Double, exposedOnly: Boolean): BlockPos? {
        val startedAt = System.nanoTime()
        val eyes = player.eyePosition
        val now = System.currentTimeMillis()
        val limit = maxRange * maxRange

        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        var considered = 0

        // The tracker yields a reused mutable position under its own read lock, so this loop stays
        // short, copies what it keeps, and does nothing that could block.
        for (pos in OreTracker.allPositions()) {
            considered++

            val distance = pos.distToCenterSqr(eyes)
            if (distance < bestDistance && distance <= limit && acceptable(pos, now, exposedOnly)) {
                bestDistance = distance
                best = pos.immutable()
            }
        }

        // Timed, because "it thinks for ages" is a thing to measure rather than to believe: the index
        // holds tens of thousands of positions and one pass over all of them happens per target.
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        AutoMineDebug.state(
            "picked ${AutoMineDebug.pos(best)} out of $considered indexed" +
                (if (exposedOnly) ", exposed only" else "") +
                (if (best != null) " at %.1f".format(sqrt(bestDistance)) else "") +
                " in %.2fms".format(elapsedMs)
        )
        return best
    }

    /**
     * Everything about a candidate that is not its distance.
     *
     * Asked only of candidates that are already the nearest so far, which is what keeps a pass over
     * tens of thousands of indexed positions to arithmetic plus a handful of real questions.
     */
    private fun acceptable(pos: BlockPos, now: Long, exposedOnly: Boolean): Boolean {
        if (!ModuleAutoMine.bounds.contains(pos.x, pos.z) || isRefused(pos, now)) return false
        if (exposedOnly && !OreVisibility.isExposed(pos)) return false

        // The index is fed by packets and can name a block the world has since forgotten about.
        val state = pos.state ?: return false
        return !state.isAir && !state.isNotBreakable(pos)
    }

    private fun isRefused(pos: BlockPos, now: Long): Boolean {
        val until = refused.get(pos.asLong())
        return until != 0L && until > now
    }

    /** Ten seconds. Long enough to have moved on, short enough to come back once the way opens. */
    private const val REFUSAL_MS = 10_000L
}
