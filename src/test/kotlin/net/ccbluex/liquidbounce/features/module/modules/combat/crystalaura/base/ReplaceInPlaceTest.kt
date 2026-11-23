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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.FakeBlockGetter
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * [isReplaceInPlace] — the rule that admits vanilla's SECOND placement form, the one `BasePlaceSearch` was missing.
 *
 * `BlockPlaceContext` (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-61`) sets
 * `replaceClicked` from `level.getBlockState(hit.getBlockPos()).canBeReplaced(this)` and then puts the block into
 * the CLICKED cell, ignoring the hit direction. Aiming straight at a snow layer and clicking is therefore how a
 * player puts obsidian there, and it is the only way in for a cell whose every neighbour is itself replaceable.
 *
 * The [replaceable] argument stands in for the live `BlockState.canBeReplacedWith(pos, ItemStack(Items.OBSIDIAN))`
 * (BlockExtensions.kt:464-478), which needs a `BlockPlaceContext` and hence a `Level` and a player. The values
 * passed here are the ones vanilla computes: `BlockBehaviour.canBeReplaced(state, context)`
 * (`net/minecraft/world/level/block/state/BlockBehaviour.java:270-272`) is `state.canBeReplaced() &&
 * !itemInHand.is(block.asItem())` unless the block overrides it, and `SnowLayerBlock` does
 * (`net/minecraft/world/level/block/SnowLayerBlock.java:118-126`): with anything but a snow item in hand it answers
 * `layers == 1`. [snowRule] restates that so the expectation is visible rather than assumed.
 */
class ReplaceInPlaceTest {

    companion object {

        private const val SIZE = 4

        private val POS = BlockPos(1, 1, 1)

        /** Eyes above the stone's top (y = 1.0) at every height and side: steep, shallow, and just over the slab. */
        private val EYES: List<Vec3> = buildList {
            for (x in doubleArrayOf(-1.5, 1.5, 4.5)) {
                for (z in doubleArrayOf(-1.5, 1.5, 4.5)) {
                    for (y in doubleArrayOf(1.3, 2.62, 4.5)) add(Vec3(x, y, z))
                }
            }
        }

        /** Points strictly inside the top face of the block below [POS] - its edges are a measure-zero case. */
        private val TOP_POINTS: List<Vec3> = buildList {
            for (u in doubleArrayOf(0.1, 0.5, 0.9)) {
                for (v in doubleArrayOf(0.1, 0.5, 0.9)) add(Vec3(POS.x + u, POS.y.toDouble(), POS.z + v))
            }
        }

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    private val level = FakeBlockGetter(SIZE, SIZE, SIZE)

    /** `SnowLayerBlock.canBeReplaced` with obsidian in hand (`SnowLayerBlock.java:118-126`): `layers == 1`. */
    private fun snowRule(layers: Int): Boolean = layers == 1

    private fun snow(layers: Int): BlockState =
        Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers)

    private fun admits(state: BlockState, replaceable: Boolean, sneaking: Boolean = false): Boolean {
        level.set(POS.x, POS.y, POS.z, state)
        return isReplaceInPlace(level, POS, state, replaceable, CollisionContext.empty(), sneaking)
    }

    /**
     * The reported bug: a one-layer snow is replaceable by obsidian and has a shape to pick, so the cell may be
     * clicked directly and the obsidian takes its place — no neighbour needed.
     */
    @Test
    fun `a one-layer snow admits the self-click`() {
        assertTrue(snowRule(1))
        assertTrue(admits(snow(1), replaceable = snowRule(1)))
    }

    /**
     * Two layers and deeper are refused, and the refusal is vanilla's own: `SnowLayerBlock.canBeReplaced` answers
     * `layers == 1` with obsidian in hand, so `canPlace()` fails and the click would do nothing. This must not
     * change — it is the one snow case the aura is right to skip.
     */
    @Test
    fun `snow deeper than one layer is still refused`() {
        for (layers in 2..8) {
            assertFalse(snowRule(layers), "vanilla replaces $layers-layer snow")
            assertFalse(admits(snow(layers), replaceable = snowRule(layers)), "$layers-layer snow admitted")
        }
    }

    /** Fire is `replaceable()` (`Blocks.java:1201-1213`) and carries a 1/16-tall outline, so it is admitted too. */
    @Test
    fun `fire admits the self-click`() {
        assertTrue(admits(Blocks.FIRE.defaultBlockState(), replaceable = true))
    }

    /**
     * Air is replaceable and is nevertheless refused: `ClipContext.Block.OUTLINE` resolves to `Shapes.empty()` for
     * it, so no ray can ever pick the cell. An air base cell keeps using the RELATIVE form — a click on a solid
     * neighbour — which is what the search has always done and what must keep working.
     */
    @Test
    fun `air is refused and stays on the neighbour form`() {
        assertFalse(admits(Blocks.AIR.defaultBlockState(), replaceable = true))
    }

    /** A block the obsidian would not replace is refused whatever its shape — this is `canPlace()` failing. */
    @Test
    fun `a non-replaceable block is refused`() {
        assertFalse(admits(Blocks.STONE.defaultBlockState(), replaceable = false))
    }

    /**
     * An interactable block consumes the click before the item is used at all
     * (`ServerPlayerGameMode.useItemOn`, `net/minecraft/server/level/ServerPlayerGameMode.java:358-371`) unless we
     * sneak — the same term the neighbour form applies, restated for the clicked cell itself. A lever stands in
     * for the `isInteractable` list; the blocks this form exists for (snow, fire) are not on it.
     */
    @Test
    fun `an interactable block needs the sneak`() {
        val lever = Blocks.LEVER.defaultBlockState()
        assertFalse(admits(lever, replaceable = true, sneaking = false))
        assertTrue(admits(lever, replaceable = true, sneaking = true))
    }

    /**
     * The shapes the whole fix turns on, straight from the block: a full 16x16 footprint glued to the BOTTOM of
     * the cell — 2/16 tall for a one-layer snow (`SnowLayerBlock.java:31, 53-55`), 1/16 for a fire
     * (`BaseFireBlock.java:30`). Sampling `AABB(pos)` for these aims mostly at empty space, which is why the aim
     * for a self-click samples `RayTests.pickBox` instead.
     */
    @Test
    fun `the shapes sit on the floor of their cell`() {
        val snowShape = snow(1).getShape(level, POS, CollisionContext.empty()).bounds()
        assertEquals(0.0, snowShape.minY)
        assertEquals(2.0 / 16.0, snowShape.maxY, 1.0e-9)
        assertEquals(1.0, snowShape.maxX - snowShape.minX, 1.0e-9)

        val fireShape = Blocks.FIRE.defaultBlockState().getShape(level, POS, CollisionContext.empty()).bounds()
        assertEquals(0.0, fireShape.minY)
        assertEquals(1.0 / 16.0, fireShape.maxY, 1.0e-9)
        assertEquals(1.0, fireShape.maxZ - fireShape.minZ, 1.0e-9)
    }

    /**
     * [coversFace] for the blocks the replace-in-place form exists for, with a stone on all six sides: a one-layer
     * snow and a floor fire cover the face of the block BENEATH them and no other - side and ceiling neighbours stay
     * clickable - and air covers nothing.
     */
    @Test
    fun `snow and fire cover only the face of the block beneath`() {
        for (dir in Direction.entries) {
            val side = POS.relative(dir)
            level.set(side.x, side.y, side.z, Blocks.STONE.defaultBlockState())
        }

        for (state in listOf(snow(1), Blocks.FIRE.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
            level.set(POS.x, POS.y, POS.z, state)
            for (dir in Direction.entries) {
                val covered = coversFace(level, POS, state, dir, CollisionContext.empty())
                assertEquals(dir == Direction.DOWN && !state.isAir, covered, "$state towards $dir")
            }
        }
    }

    /**
     * The NEIGHBOUR half of [coversFace]: the face is covered only where it lies on the shared plane. Under the snow
     * there is nothing to cover when the cell below is air, and a bottom slab's top is half a block below the plane -
     * a fire on it covers the plane but not the slab, whose top stays reachable from the side (vanilla's clip
     * reaches it, [raysEndingOnTopOf]). Found by a refutation audit of the first, cell-only version of the rule.
     */
    @Test
    fun `a face below the shared plane is not covered`() {
        val below = POS.below()
        level.set(POS.x, POS.y, POS.z, Blocks.FIRE.defaultBlockState())
        level.set(below.x, below.y, below.z, Blocks.AIR.defaultBlockState())
        assertFalse(coversFace(level, POS, Blocks.FIRE.defaultBlockState(), Direction.DOWN, CollisionContext.empty()))

        level.set(below.x, below.y, below.z, Blocks.OAK_SLAB.defaultBlockState())
        assertFalse(coversFace(level, POS, Blocks.FIRE.defaultBlockState(), Direction.DOWN, CollisionContext.empty()))

        val sideEye = Vec3(-1.5, 0.8, 1.5)
        val slabTop = Vec3(1.5, 0.5, 1.5)
        assertEquals(1, raysEndingOnTopOf(below, listOf(sideEye), listOf(slabTop)))
    }

    /**
     * The premise of [coversFace], with vanilla's own `BlockGetter.clip` (`ClipContext.Block.OUTLINE`, the pick's
     * shape getter) as the oracle: with a one-layer snow on a stone, NO ray ends on the stone's top face - from
     * straight above, from the side, from just over the slab, every one stops on the snow. That face is exactly the
     * one `clickFaces` used to offer for a snow cell on open ground. Without the snow the same rays all reach it,
     * so the first assertion is not vacuous.
     */
    @Test
    fun `no ray reaches the top face of a block under one snow layer`() {
        val below = POS.below()
        level.set(below.x, below.y, below.z, Blocks.STONE.defaultBlockState())

        level.set(POS.x, POS.y, POS.z, snow(1))
        assertEquals(0, raysEndingOnTopOf(below))

        level.set(POS.x, POS.y, POS.z, Blocks.AIR.defaultBlockState())
        assertEquals(EYES.size * TOP_POINTS.size, raysEndingOnTopOf(below))
    }

    /** How many eye-to-point rays of [eyes] x [points] vanilla's clip ends on the UP face of [block]. */
    private fun raysEndingOnTopOf(
        block: BlockPos,
        eyes: List<Vec3> = EYES,
        points: List<Vec3> = TOP_POINTS,
    ): Int {
        var count = 0
        for (eye in eyes) {
            for (point in points) {
                // Slightly past the point, so a ray that is not stopped earlier really reaches the face.
                val to = eye.add(point.subtract(eye).scale(1.01))
                val context = ClipContext(
                    eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()
                )
                val hit = level.clip(context)
                if (hit.type == HitResult.Type.BLOCK && hit.blockPos == block && hit.direction == Direction.UP) count++
            }
        }
        return count
    }
}
