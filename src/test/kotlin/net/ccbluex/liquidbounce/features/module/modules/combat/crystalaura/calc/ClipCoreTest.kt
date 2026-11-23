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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * [ClipCore] against the vanilla DDA: `FakeBlockGetter.clip` is the default `BlockGetter.clip`
 * (`net/minecraft/world/level/BlockGetter.java:65-82`), i.e. the real `traverseBlocks` + `VoxelShape.clip`.
 */
class ClipCoreTest {

    companion object {
        private const val TERRAINS = 4
        private const val SEGMENTS_PER_TERRAIN = 3000
        private const val SIZE = FakeBlockGetter.SIZE

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    private fun emptyWorld(): FakeBlockGetter = FakeBlockGetter(SIZE, SIZE, SIZE)

    /** The oracle: vanilla `clip` with `COLLIDER` / `Fluid.NONE` / `CollisionContext.empty()`. */
    private fun vanillaBlocked(fake: FakeBlockGetter, from: Vec3, to: Vec3): Boolean {
        val context =
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())
        return fake.clip(context).type == HitResult.Type.BLOCK
    }

    /** Shapes resolved exactly like `ClipContext.Block.COLLIDER` does (`ClipContext.java:57`). */
    private fun rawSource(fake: FakeBlockGetter): ShapeSource = ShapeSource { x, y, z ->
        val pos = BlockPos(x, y, z)
        fake.getBlockState(pos).getCollisionShape(fake, pos, CollisionContext.empty())
    }

    @Test
    fun `agrees with the vanilla clip on random segments through mixed terrain`() {
        var blocked = 0
        var total = 0
        for (terrain in 0 until TERRAINS) {
            val seed = 0x5EED_0000L + terrain
            val fake = FakeBlockGetter.randomTerrain(Random(seed))
            val raw = rawSource(fake)
            val memo = LevelShapeSource(fake, CollisionContext.empty())
            val segments = SegmentGenerator(seed xor 0x7A11L)
            for (i in 0 until SEGMENTS_PER_TERRAIN) {
                val from = segments.point()
                val to = segments.end(from)
                val expected = vanillaBlocked(fake, from, to)
                val where = "terrain $terrain, segment $i: $from -> $to"
                assertEquals(expected, ClipCore.isBlocked(from, to, raw), where)
                assertEquals(expected, ClipCore.isBlocked(from, to, memo), "memoized $where")
                if (expected) blocked++
                total++
            }
        }
        assertTrue(blocked in 1 until total, "degenerate sample: $blocked of $total segments blocked")
    }

    @Test
    fun `crystal centre on the top face of its obsidian base is not blocked although the cell is visited`() {
        val fake = emptyWorld()
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState())
        val from = Vec3(2.3, 7.7, 2.1)
        val centre = Vec3(5.5, 6.0, 5.5)
        val raw = rawSource(fake)
        val visited = LongOpenHashSet()
        val counting = ShapeSource { x, y, z ->
            visited.add(BlockPos.asLong(x, y, z))
            raw.collisionShape(x, y, z)
        }

        assertFalse(ClipCore.isBlocked(from, centre, counting), "s == 1.0 on the top face must be rejected")
        assertTrue(visited.contains(BlockPos.asLong(5, 5, 5)), "the DDA must have visited the obsidian cell")
        assertFalse(vanillaBlocked(fake, from, centre), "vanilla must agree")
    }

    @Test
    fun `stone between the eye and the crystal centre blocks the ray`() {
        val fake = emptyWorld()
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState())
        fake.set(4, 6, 4, Blocks.STONE.defaultBlockState())
        val from = Vec3(2.3, 7.7, 2.1)
        val centre = Vec3(5.5, 6.0, 5.5)

        assertTrue(ClipCore.isBlocked(from, centre, rawSource(fake)))
        assertTrue(vanillaBlocked(fake, from, centre))
    }

    @Test
    fun `sample point inside a block is blocked`() {
        val fake = emptyWorld()
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState())
        val inside = Vec3(5.5, 5.5, 5.5)
        val target = Vec3(8.5, 6.0, 8.5)

        assertTrue(ClipCore.isBlocked(inside, target, rawSource(fake)))
        assertTrue(vanillaBlocked(fake, inside, target))
    }

    @Test
    fun `identical endpoints never block even inside a block`() {
        val fake = emptyWorld()
        fake.set(5, 5, 5, Blocks.OBSIDIAN.defaultBlockState())
        val inside = Vec3(5.5, 5.5, 5.5)

        assertFalse(ClipCore.isBlocked(inside, Vec3(5.5, 5.5, 5.5), rawSource(fake)))
        assertFalse(vanillaBlocked(fake, inside, Vec3(5.5, 5.5, 5.5)))
    }

    @Test
    fun `override source injects and removes single cells`() {
        val fake = emptyWorld()
        fake.set(4, 6, 4, Blocks.STONE.defaultBlockState())
        val raw = rawSource(fake)
        val from = Vec3(2.3, 7.7, 2.1)
        val centre = Vec3(5.5, 6.0, 5.5)

        val futureBase = OverrideShapeSource(base = raw, x = 5, y = 5, z = 5, shape = Shapes.block())
        assertTrue(ClipCore.isBlocked(from, centre, futureBase), "the stone still blocks")
        val stoneRemoved = OverrideShapeSource(base = raw, x = 4, y = 6, z = 4, shape = Shapes.empty())
        assertFalse(ClipCore.isBlocked(from, centre, stoneRemoved), "without the stone the ray is free")
        val baseOnly = OverrideShapeSource(base = stoneRemoved, x = 5, y = 5, z = 5, shape = Shapes.block())
        assertFalse(ClipCore.isBlocked(from, centre, baseOnly), "a cube whose top face holds the centre is no hit")
    }

    @Test
    fun `level source memoizes until cleared`() {
        val fake = emptyWorld()
        val memo = LevelShapeSource(fake, CollisionContext.empty())
        assertTrue(memo.collisionShape(4, 6, 4).isEmpty)

        fake.set(4, 6, 4, Blocks.STONE.defaultBlockState())
        assertTrue(memo.collisionShape(4, 6, 4).isEmpty, "stale by design until clear()")
        memo.clear()
        assertFalse(memo.collisionShape(4, 6, 4).isEmpty)
    }
}

/** Seeded generator of the endpoint mix the contract asks for. */
private class SegmentGenerator(seed: Long) {

    private val random = Random(seed)

    /** Coordinate in [-1, 25): 1/8 exactly on an integer plane, 1/8 (only when [horizontal]) on a .5 plane. */
    fun coordinate(horizontal: Boolean): Double {
        val raw = random.nextDouble() * 26.0 - 1.0
        return when (random.nextInt(8)) {
            0 -> Math.floor(raw)
            1 -> if (horizontal) Math.floor(raw) + 0.5 else raw
            else -> raw
        }
    }

    fun point(): Vec3 = Vec3(coordinate(true), coordinate(false), coordinate(true))

    /** Unit direction: 1/6 axis-aligned (exercises the `sign == 0` / `Double.MAX_VALUE` branches), else isotropic. */
    fun direction(): Vec3 {
        if (random.nextInt(6) == 0) {
            val sign = if (random.nextBoolean()) 1.0 else -1.0
            return when (random.nextInt(3)) {
                0 -> Vec3(sign, 0.0, 0.0)
                1 -> Vec3(0.0, sign, 0.0)
                else -> Vec3(0.0, 0.0, sign)
            }
        }
        var v = Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
        while (v.lengthSqr() < 1.0E-6) {
            v = Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
        }
        return v.normalize()
    }

    /** End point for [from]: 2 % identical, 3 % closer than 1e-4, else 0.05..14 blocks away (1/4 snapped). */
    fun end(from: Vec3): Vec3 {
        val roll = random.nextInt(100)
        return when {
            roll < 2 -> from
            roll < 5 -> from.add(1.0E-5 * (random.nextDouble() * 5.0 + 1.0), -2.0E-5, 3.0E-6)
            else -> snap(from.add(direction().scale(0.05 + random.nextDouble() * 13.95)))
        }
    }

    /** 1/8 all coordinates on integer planes, 1/8 x/z on .5 planes, else unchanged. */
    private fun snap(p: Vec3): Vec3 = when (random.nextInt(8)) {
        0 -> Vec3(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z))
        1 -> Vec3(Math.floor(p.x) + 0.5, p.y, Math.floor(p.z) + 0.5)
        else -> p
    }
}
