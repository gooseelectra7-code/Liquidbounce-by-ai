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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.render

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.BlockReduction
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.BlockingState
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimPipeline
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimState
import net.minecraft.world.Difficulty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [AnchorDanger.reachRadius] against the pipeline it is a bisection over.
 *
 * The property under test is not a number but an INVARIANT: the returned radius is the threshold, so an anchor a
 * hair inside it still meets the damage floor and one a hair outside no longer does. That is checked directly
 * rather than against hand-computed distances, because the pipeline the bisection runs over (difficulty scaling,
 * armour, resistance, protection) has too many stages to restate in a test without simply copying it.
 */
class AnchorDangerTest {

    private companion object {
        val FEET: Vec3 = Vec3(0.5, 64.0, 0.5)
        val BOX: AABB = AABB(0.2, 64.0, 0.2, 0.8, 65.8, 0.8)

        /** A hair, in blocks: far below the bisection's own resolution over 10 blocks in 24 halvings. */
        const val EPSILON = 1.0e-3

        const val TOLERANCE = 1.0e-2f
    }

    private val bare = VictimState(
        feet = FEET,
        box = BOX,
        isPlayer = true,
        skip = false,
        armor = 0,
        toughness = 0f,
        resistanceAmplifier = -1,
        protectionPoints = 0f,
        absorption = 0f,
        health = 20f,
        blocking = null,
        invulnerableTime = 0,
        lastHurt = null,
        difficulty = Difficulty.NORMAL,
    )

    /** Effective damage of a fully exposed anchor [distance] blocks due east - what the bisection inverts. */
    private fun damageAt(victim: VictimState, distance: Double): Float {
        val center = victim.feet.add(distance, 0.0, 0.0)
        return VictimPipeline.apply(
            victim = victim,
            raw = AnchorMath.rawDamage(victim.feet, center, 1f),
            exposure = 1f,
            sourcePosition = center,
            respectIFrames = false,
        ).effective
    }

    private fun assertIsThreshold(victim: VictimState, minDamage: Float) {
        val radius = AnchorDanger.reachRadius(victim, minDamage)
        assertTrue(radius > 0f, "expected a non-zero radius for minDamage=$minDamage")
        assertTrue(
            damageAt(victim, radius - EPSILON) >= minDamage,
            "inside the radius the floor must still be met",
        )
        assertTrue(
            damageAt(victim, radius + EPSILON) < minDamage,
            "outside the radius the floor must be missed",
        )
    }

    @Test
    fun `the radius is the exact damage threshold for a bare victim`() {
        assertIsThreshold(bare, 8f)
    }

    @Test
    fun `the radius is the exact damage threshold through armour and protection`() {
        assertIsThreshold(bare.copy(armor = 20, toughness = 8f, protectionPoints = 12f), 4f)
    }

    @Test
    fun `a floor no anchor can reach answers zero rather than a tiny ring`() {
        // 71 raw at zero distance is the anchor's ceiling (`AnchorMathTest`), so nothing can deal 500.
        assertEquals(0f, AnchorDanger.reachRadius(bare, 500f), TOLERANCE)
    }

    @Test
    fun `a floor of zero reaches the whole blast radius`() {
        assertEquals(AnchorMath.DOUBLE_RADIUS, AnchorDanger.reachRadius(bare, 0f), TOLERANCE)
    }

    @Test
    fun `tougher armour shrinks the ring`() {
        val soft = AnchorDanger.reachRadius(bare, 6f)
        val armoured = AnchorDanger.reachRadius(bare.copy(armor = 20, protectionPoints = 16f), 6f)
        assertTrue(armoured < soft, "armour must pull the threshold closer: $armoured !< $soft")
    }

    @Test
    fun `resistance shrinks the ring`() {
        val plain = AnchorDanger.reachRadius(bare, 6f)
        val resisted = AnchorDanger.reachRadius(bare.copy(resistanceAmplifier = 1), 6f)
        assertTrue(resisted < plain, "resistance must pull the threshold closer: $resisted !< $plain")
    }

    /**
     * The shield must not move the ring. `VictimPipeline.blockedDamage` derives its arc from
     * `sourcePosition - blocking.position`, and the probe walks due east - so a shield left in would make the
     * radius depend on which way the victim happens to face, which is exactly what [AnchorDanger] takes it out to
     * avoid. A blocking victim and the same victim not blocking must give the same ring.
     */
    @Test
    fun `the ring ignores the shield`() {
        // A shield that eats the hit entirely, facing +Z while the probe walks +X. Left in, it would make the
        // radius depend on the direction the probe happens to use; taken out, both victims give the same ring.
        val shielded = bare.copy(
            blocking = BlockingState(
                reductions = listOf(BlockReduction(90f, true, 0f, 1f)),
                bypassed = false,
                viewVector = Vec3(0.0, 0.0, 1.0),
                position = FEET,
            ),
        )
        assertEquals(AnchorDanger.reachRadius(bare, 6f), AnchorDanger.reachRadius(shielded, 6f), TOLERANCE)
    }

    @Test
    fun `heat ratio is damage over the whole pool and never leaves zero to one`() {
        val state = bare.copy(health = 12f, absorption = 8f)
        assertEquals(0.5f, AnchorDanger.heatRatio(state, 10f), TOLERANCE)
        assertEquals(1f, AnchorDanger.heatRatio(state, 40f), TOLERANCE)
        assertEquals(0f, AnchorDanger.heatRatio(null, 10f), TOLERANCE)
        assertEquals(0f, AnchorDanger.heatRatio(bare.copy(health = 0f), 10f), TOLERANCE)
    }
}
