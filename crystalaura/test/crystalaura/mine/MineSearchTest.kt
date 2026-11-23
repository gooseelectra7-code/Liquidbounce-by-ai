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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.FakeBlockGetter
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The state rules of `MineSearch`, exercised on a [FakeBlockGetter] through the pure [clearMask], [isDiggable] and
 * [fallsWhenUnsupported] — everything the live search adds on top of them is range, entities and rays.
 *
 * [replaceable] stands in for the search's `canBeReplacedWith`, which needs a real `BlockPlaceContext` and hence a
 * player: `BlockBehaviour.canBeReplaced(state, context)`
 * (`net/minecraft/world/level/block/state/BlockBehaviour.java:270-272`) is `state.canBeReplaced() &&
 * !itemInHand.is(block.asItem())` plus block overrides, and with obsidian in hand only the first term can differ
 * for any block used here. [clearable] drops the range and the standing-block terms, leaving [isDiggable].
 */
class MineSearchTest {

    companion object {

        private const val SIZE = 16

        /** The base cell of every scene; the two cells above it stay inside the region. */
        private val BASE = BlockPos(8, 8, 8)

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    private val air: BlockState = Blocks.AIR.defaultBlockState()
    private val stone: BlockState = Blocks.STONE.defaultBlockState()
    private val obsidian: BlockState = Blocks.OBSIDIAN.defaultBlockState()

    /** A world that is air everywhere except the cells given as `dy to state`, relative to [BASE]. */
    private fun scene(vararg cells: Pair<Int, BlockState>): FakeBlockGetter {
        val fake = FakeBlockGetter(SIZE, SIZE, SIZE)
        for ((dy, state) in cells) {
            fake.set(BASE.x, BASE.y + dy, BASE.z, state)
        }
        return fake
    }

    private fun mask(fake: FakeBlockGetter): Int = clearMask(
        level = fake,
        base = BASE,
        scratch = BlockPos.MutableBlockPos(),
        replaceable = { _, state -> state.canBeReplaced() },
        clearable = { pos, state -> isDiggable(fake, pos, state) },
    )

    private fun waterlogged(): BlockState = Blocks.OAK_SLAB.defaultBlockState()
        .setValue(SlabBlock.TYPE, SlabType.TOP)
        .setValue(BlockStateProperties.WATERLOGGED, true)

    /**
     * Row 1 of the cell-count table: the enemy stands on flat ground, the ground cell is solid and the cell above
     * it is already air, so exactly the ground cell goes and the obsidian drops into the hole.
     */
    @Test
    fun `flat ground clears the base cell only`() {
        val mask = mask(scene(0 to stone, 1 to air))
        assertEquals(CLEAR_BASE, mask)
        assertEquals(1, Integer.bitCount(mask))
    }

    /** Row 2: underground, both the base cell and the cell above it are solid, so both go. */
    @Test
    fun `enclosed pocket clears both cells`() {
        val mask = mask(scene(0 to stone, 1 to stone))
        assertEquals(CLEAR_BASE or CLEAR_ABOVE, mask)
        assertEquals(2, Integer.bitCount(mask))
    }

    /** Row 3: the base cell is already replaceable, only the ceiling above it is in the way. */
    @Test
    fun `solid ceiling over a free base clears the ceiling only`() {
        val mask = mask(scene(0 to air, 1 to stone))
        assertEquals(CLEAR_ABOVE, mask)
        assertEquals(1, Integer.bitCount(mask))
    }

    /**
     * The replace-in-place case: a one-layer snow under a solid ceiling. The snow is replaceable
     * (`SnowLayerBlock.canBeReplaced` answers `layers == 1` with obsidian in hand,
     * `net/minecraft/world/level/block/SnowLayerBlock.java:118-126`), so [clearMask] leaves the base cell alone
     * and only the ceiling goes — which is exactly the plan whose obsidian has to go in by clicking the snow
     * ITSELF when no neighbour is clickable (`MineSearch.clickFor`, `BaseClick` with a null face).
     */
    @Test
    fun `a snow layer under a solid cell clears the ceiling only`() {
        val snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1)
        val mask = mask(scene(0 to snow, 1 to stone))
        assertEquals(CLEAR_ABOVE, mask)
        assertEquals(0, mask and CLEAR_BASE, "the base cell must survive, or nothing is left to click")
    }

    /**
     * Nothing to mine: an air cell with air above it is what `BasePlaceSearch` places obsidian into, and an
     * obsidian cell with air above it is what `PlaceSearch` puts a crystal on. Both are [CLEAR_NONE], so
     * `MineSearch` never competes with them.
     */
    @Test
    fun `a cell that is already usable needs no dig`() {
        assertEquals(CLEAR_NONE, mask(scene(0 to air, 1 to air)))
        assertEquals(CLEAR_NONE, mask(scene(0 to obsidian, 1 to air)))
    }

    /**
     * An existing obsidian or bedrock base under a solid cell is the cheapest target there is: one dig, and no
     * obsidian placement at all (`MineTarget.click` is null for it).
     */
    @Test
    fun `an existing base under a solid cell clears that cell only`() {
        assertEquals(CLEAR_ABOVE, mask(scene(0 to obsidian, 1 to stone)))
        assertEquals(CLEAR_ABOVE, mask(scene(0 to Blocks.BEDROCK.defaultBlockState(), 1 to stone)))
    }

    /**
     * Breaking a waterlogged block leaves `fluidState.createLegacyBlock()`, i.e. WATER, which is replaceable but
     * not air — the pocket would never satisfy `isEmptyBlock`, so the whole plan is refused.
     */
    @Test
    fun `a waterlogged cell is never mined`() {
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to waterlogged(), 1 to air)))
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to stone, 1 to waterlogged())))
    }

    /** Water itself: replaceable as a base (nothing to dig), impossible as the cell above (it is not air). */
    @Test
    fun `a water cell is replaceable below and impossible above`() {
        val water = Blocks.WATER.defaultBlockState()
        assertEquals(CLEAR_NONE, mask(scene(0 to water, 1 to air)))
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to stone, 1 to water)))
    }

    /**
     * A falling block resting on the topmost cell we would clear refills the pocket about two server ticks later,
     * and the client never predicts it, so the plan is refused before it starts.
     */
    @Test
    fun `a falling block over the pocket kills the plan`() {
        val gravel = Blocks.GRAVEL.defaultBlockState()
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to stone, 1 to stone, 2 to gravel)))
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to air, 1 to stone, 2 to Blocks.SAND.defaultBlockState())))
    }

    /** `suspicious_gravel` is a `BrushableBlock`, not a `FallingBlock`, and does not refill anything. */
    @Test
    fun `suspicious gravel over the pocket is harmless`() {
        val suspicious = Blocks.SUSPICIOUS_GRAVEL.defaultBlockState()
        assertEquals(CLEAR_BASE or CLEAR_ABOVE, mask(scene(0 to stone, 1 to stone, 2 to suspicious)))
    }

    /**
     * With only the base cell cleared, the cell checked for a falling block is the AIR cell above it — a block one
     * further up keeps its support and is not even neighbour-updated by our break, so it is not our problem.
     */
    @Test
    fun `a falling block two cells up is not a hazard`() {
        val gravel = Blocks.GRAVEL.defaultBlockState()
        assertEquals(CLEAR_BASE, mask(scene(0 to stone, 1 to air, 2 to gravel)))
    }

    /** Hardness `-1` never finishes a dig, so such a cell can be neither the base nor the ceiling. */
    @Test
    fun `undiggable cells are refused by clearMask`() {
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to Blocks.BARRIER.defaultBlockState(), 1 to air)))
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to stone, 1 to Blocks.BEDROCK.defaultBlockState())))
        assertEquals(CLEAR_IMPOSSIBLE, mask(scene(0 to stone, 1 to Blocks.BARRIER.defaultBlockState())))
    }

    /** The undiggable set itself: air, anything carrying a fluid, and every hardness `-1` block. */
    @Test
    fun `isDiggable refuses air fluids and unbreakable blocks`() {
        val fake = FakeBlockGetter(SIZE, SIZE, SIZE)
        val undiggable = listOf(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.WATER, Blocks.LAVA, Blocks.BUBBLE_COLUMN,
            Blocks.BEDROCK, Blocks.BARRIER, Blocks.LIGHT, Blocks.MOVING_PISTON,
        )
        for (block in undiggable) {
            val state = block.defaultBlockState()
            assertFalse(isDiggable(fake, BASE, state), "$block must not be diggable")
        }
        assertFalse(isDiggable(fake, BASE, waterlogged()), "a waterlogged block must not be diggable")
    }

    /** The other side of it: ordinary terrain stays diggable, wrong tool or not. */
    @Test
    fun `isDiggable accepts ordinary terrain`() {
        val fake = FakeBlockGetter(SIZE, SIZE, SIZE)
        val diggable = listOf(
            Blocks.STONE, Blocks.DIRT, Blocks.OBSIDIAN, Blocks.GRAVEL, Blocks.SHORT_GRASS, Blocks.OAK_SLAB,
        )
        for (block in diggable) {
            assertTrue(isDiggable(fake, BASE, block.defaultBlockState()), "$block must be diggable")
        }
    }

    /** The `FallingBlock` set of 26.1.2, block by block. */
    @Test
    fun `fallsWhenUnsupported knows the falling blocks`() {
        val falling = listOf(
            Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.DRAGON_EGG,
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
            Blocks.WHITE_CONCRETE_POWDER, Blocks.BLACK_CONCRETE_POWDER,
        )
        for (block in falling) {
            assertTrue(fallsWhenUnsupported(block.defaultBlockState()), "$block must fall")
        }
        val standing = listOf(
            Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.SOUL_SAND,
            Blocks.POINTED_DRIPSTONE, Blocks.STONE, Blocks.OBSIDIAN,
        )
        for (block in standing) {
            assertFalse(fallsWhenUnsupported(block.defaultBlockState()), "$block must not fall")
        }
    }

    /**
     * The whole registry: 3 anvils, gravel, sand and red sand, the 16 concrete powders and the dragon egg — 23
     * blocks, and nothing else in 26.1.2 falls when its support goes.
     */
    @Test
    fun `exactly 23 registered blocks fall`() {
        val count = BuiltInRegistries.BLOCK.count { fallsWhenUnsupported(it.defaultBlockState()) }
        assertEquals(23, count)
    }
}
