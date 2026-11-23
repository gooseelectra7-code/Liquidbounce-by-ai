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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Collision shape of the block at a cell, exactly as `state.getCollisionShape(level, pos, context)` returns it.
 *
 * This is the shape `ClipContext.Block.COLLIDER` resolves for every cell of `BlockGetter.clip`
 * (`net/minecraft/world/level/BlockGetter.java:67-71` → `net/minecraft/world/level/ClipContext.java:48-50, 57` →
 * `BlockBehaviour.BlockStateBase.getCollisionShape(BlockGetter, BlockPos, CollisionContext)`,
 * `net/minecraft/world/level/block/state/BlockBehaviour.java:678-680`). Fluids are deliberately not represented:
 * the crystal pipeline always clips with `ClipContext.Fluid.NONE`, whose fluid shape is `Shapes.empty()`
 * (`ClipContext.java:52-54, 97`), so a fluid can never produce a hit.
 */
fun interface ShapeSource {

    fun collisionShape(x: Int, y: Int, z: Int): VoxelShape
}

/**
 * [ShapeSource] reading a live [BlockGetter] through a per-instance memo keyed by [BlockPos.asLong].
 *
 * A cell is resolved at most once with `level.getBlockState(pos).getCollisionShape(level, pos, context)`
 * (`BlockGetter.java:67, 71` + `ClipContext.java:57`), afterwards it is served from the memo, so the 45 exposure
 * rays of one victim share every cell lookup. Intended lifetime: one instance per (tick, victim) — the memo never
 * observes world changes by itself; call [clear] or drop the instance whenever the world may have changed.
 * Not thread-safe (shared memo and scratch position).
 */
class LevelShapeSource(private val level: BlockGetter, private val context: CollisionContext) : ShapeSource {

    private val memo = Long2ObjectOpenHashMap<VoxelShape>()
    private val pos = BlockPos.MutableBlockPos()

    override fun collisionShape(x: Int, y: Int, z: Int): VoxelShape {
        val key = BlockPos.asLong(x, y, z)
        val cached = memo[key]
        if (cached != null) {
            return cached
        }

        pos.set(x, y, z)
        val shape = level.getBlockState(pos).getCollisionShape(level, pos, context)
        memo.put(key, shape)
        return shape
    }

    /** Forgets every memoized shape; the next query of any cell reads [level] again. */
    fun clear() {
        memo.clear()
    }
}

/**
 * Same as [base] except that the cell ([x], [y], [z]) reports [shape].
 *
 * Used to score a placement whose obsidian base does not exist yet: overriding the base position with
 * `Shapes.block()` makes the exposure rays see the future base exactly like a real full cube.
 */
class OverrideShapeSource(
    private val base: ShapeSource,
    private val x: Int,
    private val y: Int,
    private val z: Int,
    private val shape: VoxelShape,
) : ShapeSource {

    override fun collisionShape(x: Int, y: Int, z: Int): VoxelShape =
        if (x == this.x && y == this.y && z == this.z) shape else base.collisionShape(x, y, z)
}

/**
 * Same as [base] except that the cell `cells[i]` reports `shapes[i]`, the FIRST match winning.
 *
 * The mined variant of [OverrideShapeSource]: `MineSearch` scores a base that does not exist yet AND whose pocket is
 * not dug yet, so the exposure rays have to see the world as it WILL be — the future obsidian at the base cell as
 * the full cube `Shapes.block()` (`net/minecraft/world/phys/shapes/Shapes.java:40-42`) and every cell the dig
 * removes as `Shapes.empty()` (`Shapes.java:36-38`). Air is what a finished dig really leaves:
 * `MultiPlayerGameMode.destroyBlock` writes `fluidState.createLegacyBlock()`
 * (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:117-147`), server-side `Level.removeBlock` does the
 * same (`net/minecraft/world/level/Level.java:271-275`), and for an empty fluid that block is
 * `Blocks.AIR.defaultBlockState()` (`net/minecraft/world/level/material/EmptyFluid.java:56-59`) — `MineSearch`
 * refuses to dig any cell whose fluid is non-empty, so the "empty" override is never a lie.
 *
 * The base cell is expected FIRST, because a plan may both dig its base cell and put the obsidian there; first
 * match wins, so the cube beats the emptiness.
 *
 * Cells are keyed by [BlockPos.asLong] and scanned linearly. With the two or three cells of one plan that is a
 * handful of `long` compares and NO allocation per query, which is what matters: the rays ask for a shape thousands
 * of times per tick. A map would allocate a boxed key or hash on every one of them.
 */
class MultiOverrideShapeSource(
    private val base: ShapeSource,
    private val cells: LongArray,
    private val shapes: Array<VoxelShape>,
) : ShapeSource {

    init {
        require(cells.size == shapes.size) {
            "cells (${cells.size}) and shapes (${shapes.size}) must have the same size"
        }
    }

    override fun collisionShape(x: Int, y: Int, z: Int): VoxelShape {
        val key = BlockPos.asLong(x, y, z)
        for (index in cells.indices) {
            if (cells[index] == key) return shapes[index]
        }
        return base.collisionShape(x, y, z)
    }
}
