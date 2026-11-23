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

package net.ccbluex.liquidbounce.utils.navigation.pathing.world

import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class NavBlockClassTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    private fun navClassOf(state: BlockState): Int =
        NavBlockClass.navClassOf(NavBlockClass.classify(state))

    private fun digBucketOf(state: BlockState): Int =
        NavBlockClass.digBucketOf(NavBlockClass.classify(state))

    // -----------------------------------------------------------------------
    //  Nibble packing
    // -----------------------------------------------------------------------

    @Test
    fun `packing round-trips every nav class and dig bucket combination`() {
        for (navClass in 0..15) {
            for (digBucket in 0..15) {
                val packed = NavBlockClass.pack(navClass, digBucket)
                assertEquals(navClass, NavBlockClass.navClassOf(packed), "nav class lost for $navClass/$digBucket")
                assertEquals(digBucket, NavBlockClass.digBucketOf(packed), "dig bucket lost for $navClass/$digBucket")
            }
        }
    }

    @Test
    fun `sentinel bytes decode to their intended classes`() {
        assertEquals(NavBlockClass.UNKNOWN, NavBlockClass.navClassOf(NavBlockClass.PACKED_UNKNOWN))
        assertEquals(NavBlockClass.DIG_UNBREAKABLE, NavBlockClass.digBucketOf(NavBlockClass.PACKED_UNKNOWN))
        assertEquals(NavBlockClass.AIR, NavBlockClass.navClassOf(NavBlockClass.PACKED_AIR))
        assertEquals(0, NavBlockClass.digBucketOf(NavBlockClass.PACKED_AIR))
    }

    // -----------------------------------------------------------------------
    //  Predicates
    // -----------------------------------------------------------------------

    @Test
    fun `unloaded space is neither passable nor standable`() {
        // The single most important predicate invariant: mistaking unknown for air makes the search
        // plan confident routes through terrain it has never seen.
        assertFalse(NavBlockClass.isPassable(NavBlockClass.UNKNOWN))
        assertFalse(NavBlockClass.isStandable(NavBlockClass.UNKNOWN))
        assertFalse(NavBlockClass.isKnown(NavBlockClass.UNKNOWN))
    }

    @Test
    fun `no class is both passable and standable except the low ones you walk over`() {
        for (navClass in 0..15) {
            if (!NavBlockClass.isPassable(navClass) || !NavBlockClass.isStandable(navClass)) continue
            assertEquals(
                NavBlockClass.CARPET_LIKE,
                navClass,
                "only carpet-like blocks may be walked both through and on",
            )
        }
    }

    @Test
    fun `solid hazards are standable and passable hazards are not`() {
        assertTrue(NavBlockClass.isStandable(NavBlockClass.HAZARD_SOLID))
        assertFalse(NavBlockClass.isPassable(NavBlockClass.HAZARD_SOLID))
        assertTrue(NavBlockClass.isPassable(NavBlockClass.HAZARD))
        assertFalse(NavBlockClass.isStandable(NavBlockClass.HAZARD))
        assertTrue(NavBlockClass.isDangerous(NavBlockClass.HAZARD))
        assertTrue(NavBlockClass.isDangerous(NavBlockClass.HAZARD_SOLID))
        assertTrue(NavBlockClass.isDangerous(NavBlockClass.LAVA))
    }

    @Test
    fun `dangerous classes are never considered safe to walk through`() {
        for (navClass in 0..15) {
            if (NavBlockClass.isDangerous(navClass)) {
                assertFalse(NavBlockClass.isSafePassable(navClass), "class $navClass is dangerous yet marked safe")
            }
        }
    }

    @Test
    fun `walking surface heights match the shapes they describe`() {
        assertEquals(1.0, NavBlockClass.surfaceHeight(NavBlockClass.SOLID_FULL))
        assertEquals(0.5, NavBlockClass.surfaceHeight(NavBlockClass.SOLID_TOP_HALF))
        assertEquals(0.0, NavBlockClass.surfaceHeight(NavBlockClass.CARPET_LIKE))
        assertEquals(1.5, NavBlockClass.surfaceHeight(NavBlockClass.BLOCKING_TALL))
    }

    // -----------------------------------------------------------------------
    //  Classification of real block states
    // -----------------------------------------------------------------------

    @Test
    fun `air and full cubes classify by geometry`() {
        assertEquals(NavBlockClass.AIR, navClassOf(Blocks.AIR.defaultBlockState()))
        assertEquals(NavBlockClass.AIR, navClassOf(Blocks.CAVE_AIR.defaultBlockState()))
        assertEquals(NavBlockClass.SOLID_FULL, navClassOf(Blocks.STONE.defaultBlockState()))
        assertEquals(NavBlockClass.SOLID_FULL, navClassOf(Blocks.DIRT.defaultBlockState()))
        assertEquals(NavBlockClass.SOLID_FULL, navClassOf(Blocks.OBSIDIAN.defaultBlockState()))
    }

    @Test
    fun `blocks without collision are treated as open space`() {
        assertEquals(NavBlockClass.AIR, navClassOf(Blocks.SHORT_GRASS.defaultBlockState()))
        assertEquals(NavBlockClass.AIR, navClassOf(Blocks.TORCH.defaultBlockState()))
        assertEquals(NavBlockClass.AIR, navClassOf(Blocks.RAIL.defaultBlockState()))
    }

    @Test
    fun `fluids are told apart without bound tag data`() {
        // Classification runs on chunk-scanner threads that may see blocks before the server's tag
        // set arrives, so water and lava are recognised by fluid identity rather than FluidTags.
        assertEquals(NavBlockClass.WATER, navClassOf(Blocks.WATER.defaultBlockState()))
        assertEquals(NavBlockClass.LAVA, navClassOf(Blocks.LAVA.defaultBlockState()))
    }

    @Test
    fun `cobweb keeps its own class`() {
        assertEquals(NavBlockClass.COBWEB, navClassOf(Blocks.COBWEB.defaultBlockState()))
    }

    @Test
    fun `hazards split into the ones you stand on and the ones you fall into`() {
        assertEquals(NavBlockClass.HAZARD_SOLID, navClassOf(Blocks.MAGMA_BLOCK.defaultBlockState()))
        assertEquals(NavBlockClass.HAZARD_SOLID, navClassOf(Blocks.CACTUS.defaultBlockState()))
        assertEquals(NavBlockClass.HAZARD, navClassOf(Blocks.FIRE.defaultBlockState()))
        assertEquals(NavBlockClass.HAZARD, navClassOf(Blocks.SOUL_FIRE.defaultBlockState()))
        assertEquals(NavBlockClass.HAZARD, navClassOf(Blocks.POWDER_SNOW.defaultBlockState()))
        assertEquals(NavBlockClass.HAZARD, navClassOf(Blocks.SWEET_BERRY_BUSH.defaultBlockState()))
        assertEquals(NavBlockClass.HAZARD, navClassOf(Blocks.WITHER_ROSE.defaultBlockState()))
    }

    @Test
    fun `an open door is a doorway and a closed one is a wall`() {
        // Treating every door as blocking regardless of state seals the player inside any building
        // they are standing in, and a search can only discover that by exhausting the interior.
        val door = Blocks.OAK_DOOR.defaultBlockState()
        val closed = door.setValue(BlockStateProperties.OPEN, false)
        val open = door.setValue(BlockStateProperties.OPEN, true)

        assertEquals(NavBlockClass.DOOR_LIKE, navClassOf(closed))
        assertFalse(NavBlockClass.isPassable(navClassOf(closed)))
        assertTrue(NavBlockClass.isPassable(navClassOf(open)), "an open door must be walkable through")
    }

    @Test
    fun `a closed trapdoor is a floor, not a wall`() {
        val trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false)
        val bottom = trapdoor.setValue(BlockStateProperties.HALF, Half.BOTTOM)
        val top = trapdoor.setValue(BlockStateProperties.HALF, Half.TOP)

        // The bottom half is three inches of platform lying on the floor: you walk over it exactly
        // as you walk over a carpet. Calling it door-like made every trapdoor floor a hole the
        // search could neither cross nor stand on.
        assertEquals(NavBlockClass.CARPET_LIKE, navClassOf(bottom))
        assertTrue(NavBlockClass.isPassable(navClassOf(bottom)))

        // The top half is a lid hung at the ceiling of its own cell — walked on from above.
        assertFalse(NavBlockClass.isPassable(navClassOf(top)))
        assertTrue(NavBlockClass.isStandable(navClassOf(top)))

        assertTrue(NavBlockClass.isPassable(navClassOf(trapdoor.setValue(BlockStateProperties.OPEN, true))))
    }

    @Test
    fun `fences and walls block movement without being steppable over`() {
        assertEquals(NavBlockClass.BLOCKING_TALL, navClassOf(Blocks.OAK_FENCE.defaultBlockState()))
        assertEquals(NavBlockClass.BLOCKING_TALL, navClassOf(Blocks.COBBLESTONE_WALL.defaultBlockState()))
        assertEquals(NavBlockClass.BLOCKING_TALL, navClassOf(Blocks.OAK_FENCE_GATE.defaultBlockState()))
        assertFalse(NavBlockClass.isPassable(NavBlockClass.BLOCKING_TALL))

        // And nothing may be planned on top of one: the surface is a block and a half up, while a
        // jump reaches a block and a quarter. A lattice node sits one block above its support, so
        // "standable" here means proposing a step no body can make.
        assertFalse(
            NavBlockClass.isStandable(NavBlockClass.BLOCKING_TALL),
            "a fence top is out of jumping reach and must not be a place to stand",
        )
    }

    @Test
    fun `an open fence gate is a doorway`() {
        val gate = Blocks.OAK_FENCE_GATE.defaultBlockState()

        assertEquals(NavBlockClass.BLOCKING_TALL, navClassOf(gate.setValue(BlockStateProperties.OPEN, false)))
        assertTrue(
            NavBlockClass.isPassable(navClassOf(gate.setValue(BlockStateProperties.OPEN, true))),
            "an open gate has no collision at all; treating it as a wall seals every fenced field",
        )
    }

    @Test
    fun `waterlogged blocks keep the shape they have`() {
        val slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)
        val fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)

        // A waterlogged state carries a full water fluid state while keeping every bit of its
        // collision. Answering WATER for it made solid geometry passable and — worse — fall-breaking,
        // so a waterlogged slab at the bottom of a shaft was planned as a free dive.
        assertEquals(NavBlockClass.SOLID_TOP_HALF, navClassOf(slab))
        assertFalse(NavBlockClass.breaksFall(navClassOf(slab)))
        assertEquals(NavBlockClass.BLOCKING_TALL, navClassOf(fence))

        // Water itself is of course still water.
        assertEquals(NavBlockClass.WATER, navClassOf(Blocks.WATER.defaultBlockState()))
    }

    @Test
    fun `ladders and vines are climbable without depending on bound tags`() {
        // Recognised by block type, so classification is right even in the window after joining a
        // server when tag data has not arrived yet.
        assertEquals(NavBlockClass.CLIMBABLE, navClassOf(Blocks.LADDER.defaultBlockState()))
        assertEquals(NavBlockClass.CLIMBABLE, navClassOf(Blocks.VINE.defaultBlockState()))
        assertEquals(NavBlockClass.CLIMBABLE, navClassOf(Blocks.SCAFFOLDING.defaultBlockState()))
    }

    @Test
    fun `terrain speed modifiers get their own classes`() {
        assertEquals(NavBlockClass.SLOW, navClassOf(Blocks.SOUL_SAND.defaultBlockState()))
        assertEquals(NavBlockClass.SLOW, navClassOf(Blocks.HONEY_BLOCK.defaultBlockState()))
        assertEquals(NavBlockClass.SLOW, navClassOf(Blocks.SLIME_BLOCK.defaultBlockState()))
        assertEquals(NavBlockClass.FAST, navClassOf(Blocks.ICE.defaultBlockState()))
        assertEquals(NavBlockClass.FAST, navClassOf(Blocks.BLUE_ICE.defaultBlockState()))
    }

    @Test
    fun `partial shapes land in the height bucket they belong to`() {
        assertEquals(NavBlockClass.SOLID_TOP_HALF, navClassOf(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()))
        assertEquals(NavBlockClass.CARPET_LIKE, navClassOf(Blocks.WHITE_CARPET.defaultBlockState()))
    }

    // -----------------------------------------------------------------------
    //  Dig buckets
    // -----------------------------------------------------------------------

    @Test
    fun `unbreakable blocks report the unbreakable bucket while staying ordinary cubes`() {
        // Bedrock walks exactly like stone; only mining treats it differently. Keeping the two
        // questions separate is why unbreakability lives in the dig nibble.
        val bedrock = Blocks.BEDROCK.defaultBlockState()
        assertEquals(NavBlockClass.SOLID_FULL, navClassOf(bedrock))
        assertEquals(NavBlockClass.DIG_UNBREAKABLE, digBucketOf(bedrock))
        assertTrue(NavBlockClass.isStandable(navClassOf(bedrock)))
    }

    @Test
    fun `instantly breakable blocks sit in bucket zero`() {
        assertEquals(0, digBucketOf(Blocks.SHORT_GRASS.defaultBlockState()))
        assertEquals(0, digBucketOf(Blocks.AIR.defaultBlockState()))
    }

    @Test
    fun `dig buckets increase monotonically with hardness`() {
        val dirt = digBucketOf(Blocks.DIRT.defaultBlockState())          // 0.5
        val stone = digBucketOf(Blocks.STONE.defaultBlockState())        // 1.5
        val ironBlock = digBucketOf(Blocks.IRON_BLOCK.defaultBlockState()) // 5.0
        val obsidian = digBucketOf(Blocks.OBSIDIAN.defaultBlockState())  // 50.0

        assertTrue(dirt < stone, "dirt ($dirt) should be softer than stone ($stone)")
        assertTrue(stone < ironBlock, "stone ($stone) should be softer than iron ($ironBlock)")
        assertTrue(ironBlock < obsidian, "iron ($ironBlock) should be softer than obsidian ($obsidian)")
        assertTrue(obsidian <= NavBlockClass.DIG_MAX_BREAKABLE, "obsidian must stay breakable")
    }

    @Test
    fun `bucket to hardness recovers the original within the scale's tolerance`() {
        // The cost model turns buckets back into break times, so the round trip has to be tight
        // enough that the estimate is not the weakest part of the model. Obsidian at hardness 50 is
        // the case that matters: an earlier, narrower scale saturated there and under-estimated
        // mining it by a factor of four.
        for (hardness in listOf(0.5f, 1.5f, 3.0f, 5.0f, 22.5f, 50.0f)) {
            val bucket = NavBlockClass.digBucket(hardnessState(hardness))
            val recovered = NavBlockClass.bucketToHardness(bucket)
            val ratio = recovered / hardness
            assertTrue(
                ratio in 0.75..1.35,
                "hardness $hardness recovered as $recovered (bucket $bucket, ratio $ratio)",
            )
        }
    }

    /** Picks a real block whose hardness matches, so the test exercises the production path. */
    private fun hardnessState(hardness: Float): BlockState = when (hardness) {
        0.5f -> Blocks.DIRT.defaultBlockState()
        1.5f -> Blocks.STONE.defaultBlockState()
        3.0f -> Blocks.IRON_ORE.defaultBlockState()
        5.0f -> Blocks.IRON_BLOCK.defaultBlockState()
        22.5f -> Blocks.ENDER_CHEST.defaultBlockState()
        50.0f -> Blocks.OBSIDIAN.defaultBlockState()
        else -> error("no reference block for hardness $hardness")
    }

    @Test
    fun `unbreakable bucket maps to infinite hardness`() {
        assertEquals(Double.POSITIVE_INFINITY, NavBlockClass.bucketToHardness(NavBlockClass.DIG_UNBREAKABLE))
    }

    // -----------------------------------------------------------------------
    //  Caching
    // -----------------------------------------------------------------------

    @Test
    fun `cached classification agrees with the direct one`() {
        val states = listOf(
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.LAVA.defaultBlockState(),
            Blocks.COBWEB.defaultBlockState(),
            Blocks.MAGMA_BLOCK.defaultBlockState(),
            Blocks.SMOOTH_STONE_SLAB.defaultBlockState(),
            Blocks.BEDROCK.defaultBlockState(),
        )
        for (state in states) {
            assertEquals(
                NavBlockClass.classify(state),
                NavBlockClass.classifyCached(state),
                "cache disagrees for $state",
            )
        }
    }

    @Test
    fun `classification survives dropping the cache`() {
        val state = Blocks.STONE.defaultBlockState()
        val before = NavBlockClass.classifyCached(state)

        NavBlockClass.invalidateClassificationCache()

        assertEquals(before, NavBlockClass.classifyCached(state), "refilling the cache changed the answer")
    }
}
