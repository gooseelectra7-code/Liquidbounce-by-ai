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

import net.ccbluex.liquidbounce.features.module.modules.world.automine.ModuleAutoMine
import net.ccbluex.liquidbounce.utils.block.AbstractBlockLocationTracker
import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Every block worth mining that the client currently knows about.
 *
 * This is what turns the module from "dig this box" into a miner: instead of asking the world what is
 * next to it, it keeps an index and asks the index. [ChunkScanner.subscribe] replays every chunk that
 * is already loaded, so the index is complete moments after switching on, and from then on it is fed
 * one block at a time as the server sends updates.
 *
 * Keyed by [Block] rather than by position, which costs nothing and buys grouping: block instances are
 * canonical singletons and there are a few dozen of them here, so the store is one set of packed
 * positions per ore type.
 *
 * ## Threading
 *
 * `getStateFor` runs on the scanner's worker pool, several threads at once, and must therefore touch
 * nothing that belongs to the game: no world reads, no player, no settings that could be edited under
 * it. That is why the module hands it a **snapshot** of what to look for
 * ([ModuleAutoMine.targetSnapshot]) rather than the live setting.
 */
object OreTracker : AbstractBlockLocationTracker.State2BlockPos<Block>() {

    /**
     * Whether a position is worth remembering.
     *
     * Bounds are applied here as well as at selection time, and deliberately: it is the only thing
     * keeping the index from growing for as long as the session lasts. Everything else about a
     * candidate — whether it is exposed, reachable, refused a moment ago — changes far too often to be
     * baked into an index.
     */
    override fun getStateFor(pos: BlockPos, state: BlockState): Block? {
        val block = state.block
        if (!ModuleAutoMine.wants(block)) return null
        if (!ModuleAutoMine.bounds.contains(pos.x, pos.z)) return null

        return block
    }

    /** Positions currently indexed, for the log. Counted rather than exposed, to keep the lock short. */
    fun size(): Int = allPositions().count()

    private var subscribed = false

    /**
     * Starts indexing, if it is not already.
     *
     * Guarded because [ChunkScanner.subscribe] *errors* on a second subscribe, and there are two ways
     * in: switching the module on, and changing the block list while it is on.
     */
    fun start() {
        if (subscribed) return
        ChunkScanner.subscribe(this)
        subscribed = true
    }

    /** Stops indexing and drops everything; the scanner clears our store for us. */
    fun stop() {
        if (!subscribed) return
        ChunkScanner.unsubscribe(this)
        subscribed = false
    }

    /** Rebuilds the index from scratch, for when what counts as a target has changed. */
    fun restart() {
        if (!subscribed) return
        stop()
        start()
    }
}
