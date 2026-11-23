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

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ExplosionMathTest {

    private companion object {
        val CENTER = Vec3(0.5, 64.0, 0.5)
    }

    private fun randomPoint(random: Random, spread: Double): Vec3 =
        CENTER.add(
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread),
        )

    @Test
    fun `constants match the crystal explosion`() {
        assertEquals(6f, ExplosionMath.POWER)
        assertEquals(12f, ExplosionMath.DOUBLE_RADIUS)
    }

    @Test
    fun `full exposure at the center deals 85`() {
        assertEquals(85f, ExplosionMath.rawDamage(CENTER, CENTER, 1f))
        assertEquals(85f, ExplosionMath.rawUpperBound(CENTER, CENTER))
    }

    @Test
    fun `half distance fraction with full exposure matches the hand computed value`() {
        val feet = CENTER.add(0.0, 6.0, 0.0)
        assertEquals(0.5, ExplosionMath.distanceFraction(feet, CENTER), 0.0)
        // pow = 0.5: ((0.25 + 0.5) / 2 * 84 + 1) = 32.5
        assertEquals(32.5f, ExplosionMath.rawDamage(feet, CENTER, 1f), 1e-5f)
    }

    @Test
    fun `half exposure at the center is the same as full exposure at half distance`() {
        // dist = 0 -> pow = 0.5
        assertEquals(32.5f, ExplosionMath.rawDamage(CENTER, CENTER, 0.5f), 1e-5f)
    }

    @Test
    fun `zero exposure inside the radius still deals exactly one`() {
        assertEquals(1f, ExplosionMath.rawDamage(CENTER, CENTER, 0f))
        assertEquals(1f, ExplosionMath.rawDamage(CENTER.add(3.0, 1.0, -2.0), CENTER, 0f))
        assertEquals(1f, ExplosionMath.rawDamage(CENTER.add(0.0, -11.9, 0.0), CENTER, 0f))
    }

    @Test
    fun `beyond the double radius nothing is dealt but the boundary itself is hit`() {
        assertTrue(ExplosionMath.distanceFraction(CENTER.add(12.5, 0.0, 0.0), CENTER) > 1.0)
        assertEquals(0f, ExplosionMath.rawDamage(CENTER.add(12.5, 0.0, 0.0), CENTER, 1f))
        assertEquals(0f, ExplosionMath.rawDamage(CENTER.add(0.0, -13.0, 0.0), CENTER, 0f))
        assertEquals(0f, ExplosionMath.rawUpperBound(CENTER.add(8.0, 8.0, 8.0), CENTER))
        // dist == 1.0 is not skipped by vanilla's `dist > 1.0` test: pow = 0 -> 1 damage
        assertEquals(1.0, ExplosionMath.distanceFraction(CENTER.add(12.0, 0.0, 0.0), CENTER), 0.0)
        assertEquals(1f, ExplosionMath.rawDamage(CENTER.add(12.0, 0.0, 0.0), CENTER, 1f))
    }

    @Test
    fun `distance fraction does not depend on the subtraction order`() {
        val random = Random(42)
        repeat(1000) {
            val a = randomPoint(random, 100.0)
            val b = randomPoint(random, 100.0)
            assertEquals(a.distanceToSqr(b), b.distanceToSqr(a), 0.0)
            assertEquals(ExplosionMath.distanceFraction(a, b), ExplosionMath.distanceFraction(b, a), 0.0)
        }
    }

    @Test
    fun `crystal center sits on the block above the obsidian`() {
        assertEquals(Vec3(1.5, 3.0, 3.5), ExplosionMath.crystalCenter(BlockPos(1, 2, 3)))
        assertEquals(Vec3(-3.5, -60.0, 0.5), ExplosionMath.crystalCenter(BlockPos(-4, -61, 0)))
    }

    @Test
    fun `crystal box is the two by two by two entity box around the center`() {
        val box = ExplosionMath.crystalBox(BlockPos(1, 2, 3))
        assertEquals(0.5, box.minX, 0.0)
        assertEquals(3.0, box.minY, 0.0)
        assertEquals(2.5, box.minZ, 0.0)
        assertEquals(2.5, box.maxX, 0.0)
        assertEquals(5.0, box.maxY, 0.0)
        assertEquals(4.5, box.maxZ, 0.0)
        assertEquals(2.0, box.maxX - box.minX, 0.0)
        assertEquals(2.0, box.maxY - box.minY, 0.0)
        assertEquals(2.0, box.maxZ - box.minZ, 0.0)
    }

    @Test
    fun `raw upper bound dominates every exposure`() {
        val random = Random(20260905)
        repeat(2000) {
            val feet = randomPoint(random, 13.0)
            val exposure = random.nextFloat()
            val raw = ExplosionMath.rawDamage(feet, CENTER, exposure)
            val bound = ExplosionMath.rawUpperBound(feet, CENTER)
            assertTrue(bound >= raw, "bound $bound < raw $raw at $feet with exposure $exposure")
            assertTrue(raw >= 0f, "negative raw $raw at $feet")
            assertTrue(bound <= 85f, "bound $bound above 85 at $feet")
        }
    }
}
