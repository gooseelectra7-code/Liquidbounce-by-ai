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

import net.minecraft.world.Difficulty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class VictimPipelineTest {

    private companion object {
        const val EPS = 1e-4f
        val FEET = Vec3(3.5, 64.0, 0.5)
        val BOX = AABB(3.2, 64.0, 0.2, 3.8, 65.8, 0.8)
        val SOURCE = Vec3(0.5, 64.0, 0.5)

        /** `calculateViewVector(0, 0)` = `(0, 0, 1)`: the victim looks towards +Z. */
        val LOOK_POS_Z = Vec3(0.0, 0.0, 1.0)
        val DIFFICULTIES = listOf(Difficulty.PEACEFUL, Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)
    }

    /** Non-player, NORMAL, no armor, no effects, no shield, no i-frames, 20 health. */
    private val base = VictimState(
        feet = FEET,
        box = BOX,
        isPlayer = false,
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

    private fun pipeline(victim: VictimState, raw: Float, respectIFrames: Boolean = true, source: Vec3 = SOURCE) =
        VictimPipeline.apply(
            victim = victim,
            raw = raw,
            exposure = 1f,
            sourcePosition = source,
            respectIFrames = respectIFrames,
        )

    private fun shield(vararg reductions: BlockReduction, bypassed: Boolean = false) =
        BlockingState(reductions = reductions.toList(), bypassed = bypassed, viewVector = LOOK_POS_Z, position = FEET)

    /** A source [distance] blocks away from the victim, rotated [degrees] around Y off the +Z view direction. */
    private fun sourceAtYaw(degrees: Double, distance: Double = 5.0): Vec3 {
        val radians = Math.toRadians(degrees)
        return FEET.add(sin(radians) * distance, 0.0, cos(radians) * distance)
    }

    @Test
    fun `skipped victims yield the zero result but keep exposure and raw`() {
        val result = pipeline(base.copy(skip = true), 30f)
        assertEquals(1f, result.exposure)
        assertEquals(30f, result.raw)
        assertEquals(0f, result.afterDifficulty)
        assertEquals(0f, result.effective)
        assertEquals(0f, result.healthLoss)
        assertFalse(result.iFrameGated)
        assertEquals(DamageResult.ZERO.copy(exposure = 1f, raw = 30f), result)
    }

    @Test
    fun `difficulty scales players`() {
        val player = base.copy(isPlayer = true)
        assertEquals(6f, pipeline(player.copy(difficulty = Difficulty.EASY), 10f).afterDifficulty)
        assertEquals(10f, pipeline(player.copy(difficulty = Difficulty.NORMAL), 10f).afterDifficulty)
        assertEquals(15f, pipeline(player.copy(difficulty = Difficulty.HARD), 10f).afterDifficulty)
        assertEquals(15f, pipeline(player.copy(difficulty = Difficulty.HARD), 10f).effective)
        val peaceful = pipeline(player.copy(difficulty = Difficulty.PEACEFUL), 10f)
        assertEquals(0f, peaceful.afterDifficulty)
        assertEquals(0f, peaceful.afterBlocking)
        assertEquals(0f, peaceful.dealt)
        assertEquals(0f, peaceful.effective)
        assertEquals(0f, peaceful.healthLoss)
        assertFalse(peaceful.iFrameGated)
    }

    @Test
    fun `easy keeps small damage unchanged`() {
        // Math.min(damage / 2 + 1, damage) with damage = 1.5 -> min(1.75, 1.5)
        val easy = base.copy(isPlayer = true, difficulty = Difficulty.EASY)
        assertEquals(1.5f, pipeline(easy, 1.5f).afterDifficulty)
        assertEquals(2f, pipeline(easy, 2f).afterDifficulty)
        assertEquals(43.5f, pipeline(easy, 85f).afterDifficulty)
    }

    @Test
    fun `difficulty never scales non-players`() {
        for (difficulty in DIFFICULTIES) {
            val result = pipeline(base.copy(difficulty = difficulty), 10f)
            assertEquals(10f, result.afterDifficulty, "difficulty $difficulty")
            assertEquals(10f, result.effective, "difficulty $difficulty")
        }
    }

    @Test
    fun `shield in the arc blocks everything`() {
        val victim = base.copy(blocking = shield(BlockReduction(90f, true, 0f, 1f)))
        val result = pipeline(victim, 10f, source = sourceAtYaw(0.0))
        assertEquals(10f, result.afterDifficulty)
        assertEquals(0f, result.afterBlocking)
        assertEquals(0f, result.dealt)
        assertEquals(0f, result.effective)
        assertFalse(result.iFrameGated)
    }

    @Test
    fun `shield outside the arc blocks nothing`() {
        val victim = base.copy(blocking = shield(BlockReduction(90f, true, 0f, 1f)))
        assertEquals(10f, pipeline(victim, 10f, source = sourceAtYaw(180.0)).afterBlocking)
        assertEquals(10f, pipeline(victim, 10f, source = sourceAtYaw(120.0)).afterBlocking)
        assertEquals(10f, pipeline(victim, 10f, source = sourceAtYaw(-120.0)).afterBlocking)
    }

    @Test
    fun `blocking angle is compared against the horizontal arc`() {
        val victim = base.copy(blocking = shield(BlockReduction(45f, true, 0f, 1f)))
        assertEquals(0f, pipeline(victim, 10f, source = sourceAtYaw(30.0)).afterBlocking)
        assertEquals(0f, pipeline(victim, 10f, source = sourceAtYaw(-30.0)).afterBlocking)
        assertEquals(10f, pipeline(victim, 10f, source = sourceAtYaw(60.0)).afterBlocking)
        assertEquals(10f, pipeline(victim, 10f, source = sourceAtYaw(-60.0)).afterBlocking)
    }

    @Test
    fun `source straight above counts as a ninety degree angle`() {
        // flattened direction is ZERO -> dot 0 -> acos(0) = PI / 2, compared against the float arc promoted to double
        val above = FEET.add(0.0, 3.0, 0.0)
        val wide = base.copy(blocking = shield(BlockReduction(90f, true, 0f, 1f)))
        assertEquals(0f, pipeline(wide, 10f, source = above).afterBlocking)
        val narrow = base.copy(blocking = shield(BlockReduction(89f, true, 0f, 1f)))
        assertEquals(10f, pipeline(narrow, 10f, source = above).afterBlocking)
    }

    @Test
    fun `partial reductions are base plus factor times damage`() {
        val victim = base.copy(blocking = shield(BlockReduction(90f, true, 2f, 0.5f)))
        assertEquals(3f, pipeline(victim, 10f, source = sourceAtYaw(0.0)).afterBlocking, EPS)
    }

    @Test
    fun `summed reductions are clamped to the damage`() {
        val victim = base.copy(
            blocking = shield(BlockReduction(90f, true, 0f, 0.8f), BlockReduction(90f, true, 0f, 0.8f)),
        )
        assertEquals(0f, pipeline(victim, 10f, source = sourceAtYaw(0.0)).afterBlocking)
    }

    @Test
    fun `bypassed shields and non-matching reduction types block nothing`() {
        val bypassed = base.copy(blocking = shield(BlockReduction(90f, true, 0f, 1f), bypassed = true))
        assertEquals(10f, pipeline(bypassed, 10f, source = sourceAtYaw(0.0)).afterBlocking)
        val wrongType = base.copy(blocking = shield(BlockReduction(90f, false, 0f, 1f)))
        assertEquals(10f, pipeline(wrongType, 10f, source = sourceAtYaw(0.0)).afterBlocking)
    }

    @Test
    fun `armor 20 with toughness 8 reduces 40 to 24`() {
        // toughness = 2 + 8 / 4 = 4; realArmor = clamp(20 - 40 / 4, 4, 20) = 10; 40 * (1 - 10 / 25) = 24
        val result = pipeline(base.copy(armor = 20, toughness = 8f), 40f)
        assertEquals(40f, result.dealt)
        assertEquals(24f, result.afterArmor, EPS)
        assertEquals(24f, result.effective, EPS)
        assertEquals(24f, result.healthLoss, EPS)
    }

    @Test
    fun `armor floor is a fifth of the armor value`() {
        // toughness = 2; realArmor = clamp(20 - 40 / 2, 4, 20) = 4; 40 * (1 - 4 / 25) = 33.6
        assertEquals(33.6f, pipeline(base.copy(armor = 20, toughness = 0f), 40f).afterArmor, EPS)
        // small hits use the full armor: realArmor = clamp(20 - 2 / 2, 4, 20) = 19; 2 * (1 - 19 / 25) = 0.48
        assertEquals(0.48f, pipeline(base.copy(armor = 20, toughness = 0f), 2f).afterArmor, EPS)
    }

    @Test
    fun `resistance removes a fifth per level and caps at zero`() {
        val expected = listOf(16f, 12f, 8f, 4f, 0f)
        for (amplifier in 0..4) {
            val result = pipeline(base.copy(resistanceAmplifier = amplifier), 20f)
            assertEquals(expected[amplifier], result.afterMagic, EPS, "amplifier $amplifier")
        }
        assertEquals(0f, pipeline(base.copy(resistanceAmplifier = 4), 20f).healthLoss)
        assertEquals(20f, pipeline(base.copy(resistanceAmplifier = -1), 20f).afterMagic)
    }

    @Test
    fun `protection points scale by one twenty-fifth up to twenty`() {
        assertEquals(8.4f, pipeline(base.copy(protectionPoints = 4f), 10f).afterMagic, EPS)
        assertEquals(2f, pipeline(base.copy(protectionPoints = 25f), 10f).afterMagic, EPS)
        assertEquals(2f, pipeline(base.copy(protectionPoints = 20f), 10f).afterMagic, EPS)
        assertEquals(10f, pipeline(base.copy(protectionPoints = 0f), 10f).afterMagic)
    }

    @Test
    fun `resistance applies before protection`() {
        // 20 -> resistance I: 16 -> protection 4: 16 * 0.84 = 13.44
        val result = pipeline(base.copy(resistanceAmplifier = 0, protectionPoints = 4f), 20f)
        assertEquals(13.44f, result.afterMagic, EPS)
    }

    @Test
    fun `absorption is consumed before health`() {
        val partial = pipeline(base.copy(absorption = 4f), 10f)
        assertEquals(6f, partial.healthLoss, EPS)
        assertEquals(4f, partial.absorbed, EPS)
        assertEquals(10f, partial.effective, EPS)
        val full = pipeline(base.copy(absorption = 15f), 10f)
        assertEquals(0f, full.healthLoss)
        assertEquals(10f, full.absorbed, EPS)
        assertEquals(10f, full.effective, EPS)
    }

    @Test
    fun `inside the window only the excess over lastHurt is dealt`() {
        val result = pipeline(base.copy(invulnerableTime = 15, lastHurt = 7f), 10f)
        assertFalse(result.iFrameGated)
        assertEquals(10f, result.afterBlocking)
        assertEquals(3f, result.dealt, EPS)
        assertEquals(3f, result.effective, EPS)
        assertEquals(3f, result.healthLoss, EPS)
    }

    @Test
    fun `damage not exceeding lastHurt inside the window is gated`() {
        for (lastHurt in listOf(10f, 12f)) {
            val result = pipeline(base.copy(invulnerableTime = 15, lastHurt = lastHurt), 10f)
            assertTrue(result.iFrameGated, "lastHurt $lastHurt")
            assertEquals(10f, result.afterDifficulty)
            assertEquals(10f, result.afterBlocking)
            assertEquals(0f, result.dealt)
            assertEquals(0f, result.afterArmor)
            assertEquals(0f, result.afterMagic)
            assertEquals(0f, result.absorbed)
            assertEquals(0f, result.healthLoss)
        }
    }

    @Test
    fun `ignoring i-frames deals the full damage`() {
        val victim = base.copy(invulnerableTime = 15, lastHurt = 12f)
        val result = pipeline(victim, 10f, respectIFrames = false)
        assertFalse(result.iFrameGated)
        assertEquals(10f, result.dealt)
        assertEquals(10f, result.effective)
    }

    @Test
    fun `window needs invulnerableTime strictly above ten and unknown lastHurt counts as zero`() {
        assertEquals(5f, pipeline(base.copy(invulnerableTime = 10, lastHurt = 7f), 5f).dealt)
        assertEquals(5f, pipeline(base.copy(invulnerableTime = 0, lastHurt = 7f), 5f).dealt)
        assertEquals(10f, pipeline(base.copy(invulnerableTime = 15, lastHurt = null), 10f).dealt)
        assertEquals(10f, pipeline(base.copy(invulnerableTime = 11, lastHurt = 0f), 10f).dealt)
    }

    @Test
    fun `difficulty applies before the i-frame gate and armor sees the delta`() {
        val easy = base.copy(isPlayer = true, difficulty = Difficulty.EASY, invulnerableTime = 15, lastHurt = 7f)
        assertTrue(pipeline(easy, 10f).iFrameGated) // 10 -> 6 <= 7
        // toughness 2; dealt = 15 - 5 = 10; realArmor = clamp(20 - 10 / 2, 4, 20) = 15; 10 * (1 - 15 / 25) = 4
        val armored = base.copy(armor = 20, invulnerableTime = 15, lastHurt = 5f)
        assertEquals(4f, pipeline(armored, 15f).effective, EPS)
    }

    @Test
    fun `shield reduces the value compared against lastHurt`() {
        // 10 -> blocked 7 (base 2 + 0.5 * 10) -> 3 <= lastHurt 3 -> gated
        val shielded = base.copy(blocking = shield(BlockReduction(90f, true, 2f, 0.5f)))
        val victim = shielded.copy(invulnerableTime = 15, lastHurt = 3f)
        assertTrue(pipeline(victim, 10f, source = sourceAtYaw(0.0)).iFrameGated)
        assertFalse(pipeline(victim, 12f, source = sourceAtYaw(0.0)).iFrameGated)
    }

    @Test
    fun `lethality compares health loss against health`() {
        val fragile = base.copy(health = 5f)
        assertTrue(pipeline(fragile, 10f).isLethalFor(fragile))
        assertFalse(pipeline(base, 10f).isLethalFor(base))
        val absorbing = base.copy(health = 5f, absorption = 6f)
        assertFalse(pipeline(absorbing, 10f).isLethalFor(absorbing))
        val exact = base.copy(health = 10f)
        assertTrue(pipeline(exact, 10f).isLethalFor(exact))
    }

    @Test
    fun `effective damage is monotone in the raw damage`() {
        val random = Random(20260905)
        repeat(500) {
            val victim = randomVictim(random)
            val source = randomSource(random)
            val respect = random.nextBoolean()
            val raw1 = random.nextFloat() * 85f
            val raw2 = raw1 + random.nextFloat() * (85f - raw1)
            val low = pipeline(victim, raw1, respect, source)
            val high = pipeline(victim, raw2, respect, source)
            assertTrue(
                low.effective <= high.effective + EPS,
                "raw $raw1 -> ${low.effective} but raw $raw2 -> ${high.effective} for $victim",
            )
            assertTrue(
                low.healthLoss <= high.healthLoss + EPS,
                "raw $raw1 -> ${low.healthLoss} but raw $raw2 -> ${high.healthLoss} for $victim",
            )
        }
    }

    @Test
    fun `no stage amplifies the damage handed to it`() {
        val random = Random(1337)
        repeat(500) {
            val victim = randomVictim(random)
            val result = pipeline(victim, random.nextFloat() * 85f, random.nextBoolean(), randomSource(random))
            assertTrue(result.afterBlocking <= max(result.afterDifficulty, 0f) + EPS, "blocking $result")
            assertTrue(result.dealt <= result.afterBlocking + EPS, "i-frames $result")
            assertTrue(result.afterArmor <= result.dealt + EPS, "armor $result")
            assertTrue(result.afterMagic <= result.afterArmor + EPS, "magic $result")
            assertEquals(result.afterMagic, result.absorbed + result.healthLoss, EPS, "absorption $result")
            assertTrue(result.healthLoss >= 0f && result.absorbed >= 0f, "signs $result")
            assertTrue(result.absorbed <= victim.absorption + EPS, "absorbed more than available $result")
        }
    }

    private fun randomVictim(random: Random): VictimState =
        base.copy(
            isPlayer = random.nextBoolean(),
            skip = random.nextInt(10) == 0,
            armor = random.nextInt(0, 31),
            toughness = random.nextFloat() * 12f,
            resistanceAmplifier = random.nextInt(-1, 5),
            protectionPoints = random.nextFloat() * 30f,
            absorption = random.nextFloat() * 16f,
            health = 1f + random.nextFloat() * 19f,
            blocking = if (random.nextInt(3) == 0) randomShield(random) else null,
            invulnerableTime = random.nextInt(0, 21),
            lastHurt = if (random.nextBoolean()) null else random.nextFloat() * 30f,
            difficulty = DIFFICULTIES[random.nextInt(DIFFICULTIES.size)],
        )

    private fun randomShield(random: Random): BlockingState {
        val reductions = List(random.nextInt(1, 3)) {
            BlockReduction(
                horizontalBlockingAngle = random.nextFloat() * 180f,
                typeMatches = random.nextInt(4) != 0,
                base = random.nextFloat() * 5f,
                factor = random.nextFloat(),
            )
        }
        val yaw = Math.toRadians(random.nextDouble(0.0, 360.0))
        return BlockingState(
            reductions = reductions,
            bypassed = random.nextInt(4) == 0,
            viewVector = Vec3(sin(yaw), 0.0, cos(yaw)),
            position = FEET,
        )
    }

    private fun randomSource(random: Random): Vec3 =
        FEET.add(random.nextDouble(-6.0, 6.0), random.nextDouble(-3.0, 3.0), random.nextDouble(-6.0, 6.0))
}
