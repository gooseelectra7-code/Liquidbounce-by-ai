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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.FakeBlockGetter
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * [isReplaceInPlace] — the rule that admits vanilla's SECOND placement form for the respawn anchor.
 *
 * `BlockPlaceContext` (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-61`) sets
 * `replaceClicked` from `level.getBlockState(hit.getBlockPos()).canBeReplaced(this)` and then puts the block into
 * the CLICKED cell, ignoring the hit direction. `AnchorSearch` used to enumerate neighbours only, so a cell whose
 * every neighbour is itself replaceable — a snow layer or a fire on open ground — could never be placed into.
 *
 * A respawn anchor is a plain `BlockItem` (`net/minecraft/world/item/Items.java:2193`), so the replaceability
 * answers are the same ones obsidian gets: `SnowLayerBlock.canBeReplaced`
 * (`net/minecraft/world/level/block/SnowLayerBlock.java:118-126`) returns `layers == 1` for any non-snow item in
 * hand. [snowRule] restates that, standing in for the live `AnchorView.isReplaceable`, which needs a
 * `BlockPlaceContext` and hence a `Level` and a player.
 */
class ReplaceInPlaceTest {

    companion object {

        private const val SIZE = 4

        private val POS = BlockPos(1, 1, 1)

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    private val level = FakeBlockGetter(SIZE, SIZE, SIZE)

    /** `SnowLayerBlock.canBeReplaced` with a respawn anchor in hand (`SnowLayerBlock.java:118-126`). */
    private fun snowRule(layers: Int): Boolean = layers == 1

    private fun snow(layers: Int): BlockState =
        Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers)

    private fun admits(state: BlockState, replaceable: Boolean, sneaking: Boolean = false): Boolean {
        level.set(POS.x, POS.y, POS.z, state)
        return isReplaceInPlace(level, POS, state, replaceable, CollisionContext.empty(), sneaking)
    }

    /** A one-layer snow may be clicked directly; the anchor replaces it, no clickable neighbour needed. */
    @Test
    fun `a one-layer snow admits the self-click`() {
        assertTrue(admits(snow(1), replaceable = snowRule(1)))
    }

    /** Deeper snow stays refused, because vanilla's `canBeReplaced` refuses it and `canPlace()` would fail. */
    @Test
    fun `snow deeper than one layer is still refused`() {
        for (layers in 2..8) {
            assertFalse(admits(snow(layers), replaceable = snowRule(layers)), "$layers-layer snow admitted")
        }
    }

    /** Fire is `replaceable()` and carries a 1/16-tall outline, so it is admitted too. */
    @Test
    fun `fire admits the self-click`() {
        assertTrue(admits(Blocks.FIRE.defaultBlockState(), replaceable = true))
    }

    /**
     * Air is replaceable and still refused: its outline is `Shapes.empty()`, so no ray picks the cell. An air cell
     * keeps using the NEIGHBOUR form, which is what `clickFaces` has always found for it.
     */
    @Test
    fun `air is refused and stays on the neighbour form`() {
        assertFalse(admits(Blocks.AIR.defaultBlockState(), replaceable = true))
    }

    /** A block the anchor would not replace is refused whatever its shape. */
    @Test
    fun `a non-replaceable block is refused`() {
        assertFalse(admits(Blocks.STONE.defaultBlockState(), replaceable = false))
    }

    /**
     * An interactable block consumes the click before the item is used
     * (`ServerPlayerGameMode.java:358-371`) unless we sneak. `AnchorJob` passes `sneaking = false` because the
     * module hub stands down entirely while sneaking, so for the live cycle this term is always the strict one.
     */
    @Test
    fun `an interactable block needs the sneak`() {
        val lever = Blocks.LEVER.defaultBlockState()
        assertFalse(admits(lever, replaceable = true, sneaking = false))
        assertTrue(admits(lever, replaceable = true, sneaking = true))
    }

    /**
     * The shape the aim has to sample: a full footprint glued to the BOTTOM of the cell, 2/16 tall for one snow
     * layer (`SnowLayerBlock.java:31, 53-55`). A respawn anchor fills its cell, so `RayTests.pickBox` changes
     * nothing for the charge and the detonation and everything for a replace-in-place placement.
     */
    @Test
    fun `a one-layer snow sits on the floor of its cell`() {
        val bounds = snow(1).getShape(level, POS, CollisionContext.empty()).bounds()
        assertEquals(0.0, bounds.minY)
        assertEquals(2.0 / 16.0, bounds.maxY, 1.0e-9)
        assertEquals(1.0, bounds.maxX - bounds.minX, 1.0e-9)

        val anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState().getShape(level, POS, CollisionContext.empty())
        assertEquals(1.0, anchor.bounds().maxY - anchor.bounds().minY, 1.0e-9)
    }
}
