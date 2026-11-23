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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.material.FluidState
import java.util.Random

/**
 * Minimal [BlockGetter] over a dense `BlockState` array covering `[0, sizeX) x [0, sizeY) x [0, sizeZ)`; every
 * cell outside that box is air.
 *
 * Implements the abstract members of `BlockGetter` (`net/minecraft/world/level/BlockGetter.java:26-36`:
 * `getBlockEntity`, `getBlockState`, `getFluidState`) and of `LevelHeightAccessor`
 * (`net/minecraft/world/level/LevelHeightAccessor.java:7-9`: `getHeight`, `getMinY`); the Fabric-injected
 * `FabricBlockGetter` (fabric-block-getter-api-v2 2.0.7) only declares default methods. Because `BlockGetter.clip`
 * is a default method that runs the real `traverseBlocks` (`BlockGetter.java:65-82, 115-172`),
 * `fake.clip(ClipContext(from, to, COLLIDER, NONE, CollisionContext.empty()))` is the vanilla oracle of the tests.
 * Requires `MinecraftBootstrap.ensureInitialized()` before construction (`Blocks` is registry-backed).
 */
class FakeBlockGetter(val sizeX: Int, val sizeY: Int, val sizeZ: Int) : BlockGetter {

    private val air: BlockState = Blocks.AIR.defaultBlockState()
    private val states = Array(sizeX * sizeY * sizeZ) { air }

    fun contains(x: Int, y: Int, z: Int): Boolean = x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ

    fun get(x: Int, y: Int, z: Int): BlockState = if (contains(x, y, z)) states[index(x, y, z)] else air

    fun set(x: Int, y: Int, z: Int, state: BlockState) {
        require(contains(x, y, z)) { "($x, $y, $z) is outside the ${sizeX}x${sizeY}x$sizeZ region" }
        states[index(x, y, z)] = state
    }

    /** Sets every cell of the closed box [min]..[max], clamped to the region, to [state]. */
    fun fill(min: BlockPos, max: BlockPos, state: BlockState) {
        for (x in maxOf(min.x, 0)..minOf(max.x, sizeX - 1)) {
            for (y in maxOf(min.y, 0)..minOf(max.y, sizeY - 1)) {
                for (z in maxOf(min.z, 0)..minOf(max.z, sizeZ - 1)) {
                    states[index(x, y, z)] = state
                }
            }
        }
    }

    private fun index(x: Int, y: Int, z: Int): Int = (y * sizeZ + z) * sizeX + x

    override fun getBlockState(pos: BlockPos): BlockState = get(pos.x, pos.y, pos.z)

    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null

    override fun getHeight(): Int = sizeY

    override fun getMinY(): Int = 0

    companion object {

        /** Edge length of the terrains built by [randomTerrain]. */
        const val SIZE = 24

        /**
         * Block palette of [randomTerrain]: full cubes, half slabs (both halves), stairs, a 1.5-high fence, a thin
         * pane, a fluid (empty collision shape under `CollisionContext.empty()`, `LiquidBlock.java:86-99`), a
         * no-collision plant (`Blocks.java:635-647`, `noCollision()`) and air.
         */
        fun palette(): List<BlockState> = listOf(
            Blocks.STONE.defaultBlockState(),
            Blocks.OBSIDIAN.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
            Blocks.OAK_STAIRS.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState(),
            Blocks.GLASS_PANE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.SHORT_GRASS.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
        )

        /**
         * A [SIZE]^3 terrain: solid stone floor (y = 0) and stone walls at x = 0 and z = 0, then every remaining
         * cell is set with probability [density] to a uniformly drawn [palette] entry.
         */
        fun randomTerrain(random: Random, density: Double = 0.12): FakeBlockGetter {
            val fake = FakeBlockGetter(SIZE, SIZE, SIZE)
            val stone = Blocks.STONE.defaultBlockState()
            val last = SIZE - 1
            fake.fill(BlockPos(0, 0, 0), BlockPos(last, 0, last), stone)
            fake.fill(BlockPos(0, 0, 0), BlockPos(0, last, last), stone)
            fake.fill(BlockPos(0, 0, 0), BlockPos(last, last, 0), stone)
            val palette = palette()
            for (i in 0 until SIZE * SIZE * SIZE) {
                if (random.nextDouble() >= density) continue
                val x = i % SIZE
                val z = (i / SIZE) % SIZE
                val y = i / (SIZE * SIZE)
                if (x == 0 || y == 0 || z == 0) continue
                fake.set(x, y, z, palette[random.nextInt(palette.size)])
            }
            return fake
        }
    }
}
