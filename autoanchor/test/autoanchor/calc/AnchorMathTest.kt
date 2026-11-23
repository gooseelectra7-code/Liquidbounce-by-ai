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
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [AnchorMath] against the hand-evaluated vanilla formula for power `5.0F`.
 *
 * Every expected number below is `(pow * pow + pow) / 2 * 7 * 10 + 1` with `pow = (1 - d / 10) * exposure`
 * (`ExplosionDamageCalculator.getEntityDamageAmount`, `:29-35`, with `doubleRadius = 2 * 5.0F`), i.e. arithmetic a
 * reader can redo on paper - no oracle is needed here, unlike the raycast tests.
 */
class AnchorMathTest {

    private companion object {
        /** Centre of an anchor standing at `(0, 64, 0)`: `RespawnAnchorBlock.java:168` `pos.getCenter()`. */
        val CENTER: Vec3 = AnchorMath.anchorCenter(BlockPos(0, 64, 0))
    }

    private fun randomPoint(random: Random, spread: Double): Vec3 =
        CENTER.add(
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread),
            random.nextDouble(-spread, spread),
        )

    @Test
    fun `constants match the respawn anchor explosion`() {
        assertEquals(5f, AnchorMath.POWER)
        assertEquals(10f, AnchorMath.DOUBLE_RADIUS)
        assertEquals(AnchorMath.POWER * 2f, AnchorMath.DOUBLE_RADIUS)
    }

    @Test
    fun `anchor centre is the volumetric centre of its own cell`() {
        assertEquals(Vec3(1.5, 64.5, 0.5), AnchorMath.anchorCenter(BlockPos(1, 64, 0)))
        assertEquals(Vec3(1.5, 2.5, 3.5), AnchorMath.anchorCenter(BlockPos(1, 2, 3)))
        assertEquals(Vec3(-3.5, -60.5, 0.5), AnchorMath.anchorCenter(BlockPos(-4, -61, 0)))
        // Contrast with a crystal, whose centre is one FULL block above its pedestal, never +0.5 inside it.
        assertEquals(0.5, AnchorMath.anchorCenter(BlockPos(0, 0, 0)).y, 0.0)
    }

    @Test
    fun `full exposure at the centre deals 71`() {
        // pow = 1 -> (1 + 1) / 2 * 7 * 10 + 1 = 71
        assertEquals(71f, AnchorMath.rawDamage(CENTER, CENTER, 1f))
        assertEquals(71f, AnchorMath.rawUpperBound(CENTER, CENTER))
    }

    @Test
    fun `half distance fraction with full exposure matches the hand computed value`() {
        val feet = CENTER.add(0.0, 5.0, 0.0)
        assertEquals(0.5, AnchorMath.distanceFraction(feet, CENTER), 0.0)
        // pow = 0.5: (0.25 + 0.5) / 2 * 70 + 1 = 27.25
        assertEquals(27.25f, AnchorMath.rawDamage(feet, CENTER, 1f), 1e-5f)
    }

    @Test
    fun `half exposure at the centre is the same as full exposure at half distance`() {
        // dist = 0 -> pow = 0.5
        assertEquals(27.25f, AnchorMath.rawDamage(CENTER, CENTER, 0.5f), 1e-5f)
    }

    @Test
    fun `zero exposure inside the radius still deals exactly one`() {
        assertEquals(1f, AnchorMath.rawDamage(CENTER, CENTER, 0f))
        assertEquals(1f, AnchorMath.rawDamage(CENTER.add(3.0, 1.0, -2.0), CENTER, 0f))
        assertEquals(1f, AnchorMath.rawDamage(CENTER.add(0.0, -9.9, 0.0), CENTER, 0f))
    }

    @Test
    fun `beyond ten blocks nothing is dealt but the boundary itself is hit`() {
        assertTrue(AnchorMath.distanceFraction(CENTER.add(10.5, 0.0, 0.0), CENTER) > 1.0)
        assertEquals(0f, AnchorMath.rawDamage(CENTER.add(10.5, 0.0, 0.0), CENTER, 1f))
        assertEquals(0f, AnchorMath.rawDamage(CENTER.add(0.0, -11.0, 0.0), CENTER, 0f))
        assertEquals(0f, AnchorMath.rawUpperBound(CENTER.add(6.0, 6.0, 6.0), CENTER))
        // The crystal's 12-block reach is dead here: 11 blocks away is inside 12 but outside 10.
        assertEquals(0f, AnchorMath.rawUpperBound(CENTER.add(11.0, 0.0, 0.0), CENTER))
        // dist == 1.0 is not skipped by vanilla's `dist > 1.0` test: pow = 0 -> 1 damage
        assertEquals(1.0, AnchorMath.distanceFraction(CENTER.add(10.0, 0.0, 0.0), CENTER), 0.0)
        assertEquals(1f, AnchorMath.rawDamage(CENTER.add(10.0, 0.0, 0.0), CENTER, 1f))
    }

    @Test
    fun `the foot cell outranks the head cell for a victim standing next to it`() {
        // Victim feet at (0.5, 64, 0.5); the anchor goes in the cell beside their feet or beside their head.
        val feet = Vec3(0.5, 64.0, 0.5)
        val footCell = AnchorMath.anchorCenter(BlockPos(1, 64, 0))
        val headCell = AnchorMath.anchorCenter(BlockPos(1, 65, 0))

        // d = sqrt(1.25) = 1.118034 -> pow = 0.8881966 -> 59.698
        assertEquals(59.698f, AnchorMath.rawDamage(feet, footCell, 1f), 1e-3f)
        // d = sqrt(3.25) = 1.802776 -> pow = 0.8197224 -> 53.208
        assertEquals(53.208f, AnchorMath.rawDamage(feet, headCell, 1f), 1e-3f)
        assertTrue(AnchorMath.rawDamage(feet, footCell, 1f) > AnchorMath.rawDamage(feet, headCell, 1f))
    }

    @Test
    fun `distance fraction does not depend on the subtraction order`() {
        val random = Random(42)
        repeat(1000) {
            val a = randomPoint(random, 100.0)
            val b = randomPoint(random, 100.0)
            assertEquals(a.distanceToSqr(b), b.distanceToSqr(a), 0.0)
            assertEquals(AnchorMath.distanceFraction(a, b), AnchorMath.distanceFraction(b, a), 0.0)
        }
    }

    @Test
    fun `raw upper bound dominates every exposure`() {
        val random = Random(20260907)
        repeat(2000) {
            val feet = randomPoint(random, 11.0)
            val exposure = random.nextFloat()
            val raw = AnchorMath.rawDamage(feet, CENTER, exposure)
            val bound = AnchorMath.rawUpperBound(feet, CENTER)
            assertTrue(bound >= raw, "bound $bound < raw $raw at $feet with exposure $exposure")
            assertTrue(raw >= 0f, "negative raw $raw at $feet")
            assertTrue(bound <= 71f, "bound $bound above 71 at $feet")
        }
    }
}
