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

import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * [Exposure] against a transcription of `ServerExplosion.getSeenPercent`
 * (`net/minecraft/world/level/ServerExplosion.java:78-109`) that clips with the vanilla `BlockGetter.clip`.
 */
class ExposureTest {

    companion object {
        private const val SCENES = 200
        private const val SIZE = FakeBlockGetter.SIZE

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    private fun emptyWorld(): FakeBlockGetter = FakeBlockGetter(SIZE, SIZE, SIZE)

    private fun source(fake: FakeBlockGetter): ShapeSource = LevelShapeSource(fake, CollisionContext.empty())

    /** Player-sized box (0.6 x 1.8 x 0.6) standing at feet position ([x], [y], [z]). */
    private fun playerBox(x: Double, y: Double, z: Double): AABB =
        AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3)

    /** Victim at (11, 1, 11), centre east of it; a stone wall in the x = 13 plane, z = 10..12, y = 1..[wallTop]. */
    private fun wallScene(wallTop: Int): FakeBlockGetter {
        val fake = emptyWorld()
        fake.fill(BlockPos(13, 1, 10), BlockPos(13, wallTop, 12), Blocks.STONE.defaultBlockState())
        return fake
    }

    /**
     * `ServerExplosion.getSeenPercent` (`ServerExplosion.java:78-109`) transcribed line by line, with
     * `entity.level().clip(...)` replaced by the fake's vanilla `clip` and `CollisionContext.empty()`.
     */
    private fun referenceSeenPercent(center: Vec3, bb: AABB, fake: FakeBlockGetter): Float {
        val xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0)
        val ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0)
        val zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0)
        val xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0
        val zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0
        val valid = !(xs < 0.0) && !(ys < 0.0) && !(zs < 0.0)
        if (!valid) return 0.0f

        var hits = 0
        var count = 0
        var xx = 0.0
        while (xx <= 1.0) {
            var yy = 0.0
            while (yy <= 1.0) {
                var zz = 0.0
                while (zz <= 1.0) {
                    val x = Mth.lerp(xx, bb.minX, bb.maxX)
                    val y = Mth.lerp(yy, bb.minY, bb.maxY)
                    val z = Mth.lerp(zz, bb.minZ, bb.maxZ)
                    val from = Vec3(x + xOffset, y, z + zOffset)
                    val context = ClipContext(
                        from, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()
                    )
                    if (fake.clip(context).type == HitResult.Type.MISS) hits++
                    count++
                    zz += zs
                }
                yy += ys
            }
            xx += xs
        }
        return hits.toFloat() / count
    }

    @Test
    fun `open field exposes the whole box`() {
        val fake = emptyWorld()
        val box = playerBox(10.0, 1.0, 10.0)
        val centre = Vec3(14.5, 1.0, 14.5)

        assertEquals(1.0f, Exposure.seenPercent(centre, box, source(fake)))
        assertEquals(1.0f, referenceSeenPercent(centre, box, fake))
    }

    @Test
    fun `victim fully behind a 3 wide 3 high wall has zero exposure`() {
        val fake = wallScene(3)
        val box = playerBox(11.0, 1.0, 11.0)
        val centre = Vec3(16.5, 2.9, 11.0)

        assertEquals(0.0f, Exposure.seenPercent(centre, box, source(fake)))
        assertEquals(0.0f, referenceSeenPercent(centre, box, fake))
    }

    @Test
    fun `wall covering only the lowest cell leaves a fraction strictly between 0 and 1`() {
        val fake = wallScene(1)
        val box = playerBox(11.0, 1.0, 11.0)
        val centre = Vec3(16.5, 2.9, 11.0)

        val exposure = Exposure.seenPercent(centre, box, source(fake))
        assertTrue(exposure > 0.0f && exposure < 1.0f, "exposure $exposure")
        assertEquals(referenceSeenPercent(centre, box, fake), exposure)
        // Rays rise towards the centre: the two lowest of the five sample rows (y = 1.0 and 1.39) cross the
        // x = 13 plane below y = 2 and hit the wall cell, the other three pass above it: 27 of 45 rays are free.
        assertEquals(27.0f / 45.0f, exposure, "two of five sample rows are behind the single wall cell")
    }

    @Test
    fun `player box is sampled with 45 rays`() {
        assertEquals(45, Exposure.sampleCount(playerBox(11.0, 1.0, 11.0)))
        assertEquals(45, Exposure.sampleCount(AABB(0.2, 64.0, -3.7, 0.8, 65.8, -3.1)))
    }

    @Test
    fun `matches the vanilla reference on random scenes`() {
        val random = Random(0x5EE_D00L)
        var partial = 0
        for (scene in 0 until SCENES) {
            val fake = FakeBlockGetter.randomTerrain(random, 0.04 + random.nextDouble() * 0.2)
            val box = playerBox(
                2.0 + random.nextDouble() * 20.0,
                1.0 + random.nextDouble() * 18.0,
                2.0 + random.nextDouble() * 20.0,
            )
            val centre = Vec3(
                1.0 + random.nextDouble() * 22.0,
                0.5 + random.nextDouble() * 22.0,
                1.0 + random.nextDouble() * 22.0,
            )
            val expected = referenceSeenPercent(centre, box, fake)
            val actual = Exposure.seenPercent(centre, box, source(fake))
            assertEquals(expected, actual, "scene $scene: box $box, centre $centre")
            if (expected > 0.0f && expected < 1.0f) partial++
        }
        assertTrue(partial > 0, "no partially covered scene among $SCENES")
    }
}
