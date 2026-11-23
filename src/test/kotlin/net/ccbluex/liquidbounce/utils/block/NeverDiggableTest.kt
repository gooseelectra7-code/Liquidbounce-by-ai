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

import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * [BlockState.isNeverDiggable], the gamemode-independent half of [BlockState.isBreakable].
 *
 * Every block-breaking module in the client routes through that predicate, and until 2026-09-11 it answered on
 * hardness alone - which says water and lava are breakable, because they are registered with `.strength(100.0F)`.
 * A dig on either is futile and is Grim's `AirLiquidBreak` on the first packet. These tests pin both halves of
 * the rule: the liquid BLOCK is refused, a waterlogged block is not.
 */
class NeverDiggableTest {

    private companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    @Test
    fun `liquids are never diggable however breakable their hardness looks`() {
        // The trap: this is exactly what the old predicate read, and it passes its `>= 0` test comfortably.
        val hardness = Blocks.WATER.defaultBlockState().getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        assertEquals(100.0f, hardness, "water's hardness")

        assertTrue(Blocks.WATER.defaultBlockState().isNeverDiggable(), "water")
        assertTrue(Blocks.LAVA.defaultBlockState().isNeverDiggable(), "lava")
        assertTrue(Blocks.BUBBLE_COLUMN.defaultBlockState().isNeverDiggable(), "bubble column")
    }

    @Test
    fun `hitboxless blocks are never diggable, so creative cannot dig them either`() {
        assertTrue(Blocks.AIR.defaultBlockState().isNeverDiggable(), "air")
        assertTrue(Blocks.LIGHT.defaultBlockState().isNeverDiggable(), "light")
        assertTrue(Blocks.MOVING_PISTON.defaultBlockState().isNeverDiggable(), "moving piston")
    }

    /**
     * The distinction the crystal aura's own `isDiggable` deliberately draws differently: it refuses anything
     * CARRYING a fluid, because a break there leaves water where it needs a pedestal. The shared predicate must
     * not, or every module in the client would stop breaking waterlogged stairs and slabs.
     */
    @Test
    fun `a waterlogged block is still diggable`() {
        val slab = Blocks.OAK_SLAB.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)

        assertFalse(slab.fluidState.isEmpty, "the fixture must actually carry water")
        assertFalse(slab.isNeverDiggable(), "a waterlogged slab is ordinary terrain")
    }

    @Test
    fun `unbreakable and ordinary blocks are left to the hardness test`() {
        // `isNeverDiggable` is only the shape half: bedrock is refused by hardness, and in creative vanilla
        // really does remove it, which is why it must NOT be refused here.
        assertFalse(Blocks.BEDROCK.defaultBlockState().isNeverDiggable(), "bedrock")
        assertFalse(Blocks.BARRIER.defaultBlockState().isNeverDiggable(), "barrier")

        assertFalse(Blocks.STONE.defaultBlockState().isNeverDiggable(), "stone")
        assertFalse(Blocks.OBSIDIAN.defaultBlockState().isNeverDiggable(), "obsidian")
        assertFalse(Blocks.TORCH.defaultBlockState().isNeverDiggable(), "a torch has no collision but does break")
    }
}
