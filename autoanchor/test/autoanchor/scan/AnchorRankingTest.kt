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

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.FakeBlockGetter
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.LevelShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.OverrideShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.ShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.TargetEntry
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.core.BlockPos
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The two things `AnchorSearch` builds on top of the copied ranker: the `Shapes.empty()` override of the candidate
 * cell, and the ranking walk with the anchor's own tie-break.
 */
class AnchorRankingTest {

    companion object {

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }

        /** Cell the anchor stands in for the override scenes. */
        private val CELL = BlockPos(10, 1, 10)
    }

    /** A 20-health player victim with no armour, no effects, no shield and no i-frames, standing at [feet]. */
    private fun victim(feet: Vec3): VictimState = VictimState(
        feet = feet,
        box = AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3),
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

    /** An otherwise empty world with a respawn anchor standing in [CELL]. */
    private fun sceneWithAnchor(): FakeBlockGetter {
        val fake = FakeBlockGetter(FakeBlockGetter.SIZE, FakeBlockGetter.SIZE, FakeBlockGetter.SIZE)
        fake.set(CELL.x, CELL.y, CELL.z, Blocks.RESPAWN_ANCHOR.defaultBlockState())
        return fake
    }

    /**
     * The regression this whole rule exists for.
     *
     * Vanilla removes the anchor BEFORE the blast - `level.removeBlock(pos, false)`
     * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:151`) runs before `level.explode(...)` (`:169`) -
     * so the exposure rays must see the anchor's own cell as air. Score it with the block still standing and every
     * ray from a victim beside it clips the anchor at its own destination: the exposure collapses to `0`, the raw
     * damage falls to the `1.0F` floor of `getEntityDamageAmount`, and the search rejects the best cell it has.
     * The same mistake was made once before, in the `AutoAnchorFinder` of the old AutoAnchor module; this test
     * is what keeps it from being made a third time now that the module - and its comment - are gone.
     */
    @Test
    fun `overriding the anchor cell to empty is what makes a side placement score`() {
        val fake = sceneWithAnchor()
        val calc = DamageCalculator(respectIFrames = true)
        val center = AnchorMath.anchorCenter(CELL)
        val victim = victim(Vec3(CELL.x + 1.5, CELL.y.toDouble(), CELL.z + 0.5))
        val base: ShapeSource = LevelShapeSource(fake, CollisionContext.empty())
        val emptied = OverrideShapeSource(base = base, x = CELL.x, y = CELL.y, z = CELL.z, shape = Shapes.empty())

        val standing = calc.damage(center, victim, base)
        val removed = calc.damage(center, victim, emptied)

        assertEquals(0f, standing.exposure, "the anchor blocks every ray to its own centre")
        assertEquals(1f, removed.exposure, "with the cell emptied nothing is in the way")
        assertEquals(1f, standing.raw, "exposure 0 leaves the constant 1.0F floor of getEntityDamageAmount")
        assertTrue(removed.raw > 50f, "a foot-adjacent anchor is around 59.7 raw, was ${removed.raw}")
        assertTrue(
            removed.effective > standing.effective * 10f,
            "the override is worth an order of magnitude: ${removed.effective} vs ${standing.effective}",
        )
    }

    /**
     * The bound is computed without rays and must stay an upper bound of BOTH readings - it is what the cell heap
     * is ordered by, so a bound below the real damage would prune the winner.
     */
    @Test
    fun `the ray free bound covers the overridden reading`() {
        val fake = sceneWithAnchor()
        val calc = DamageCalculator(respectIFrames = true)
        val center = AnchorMath.anchorCenter(CELL)
        val victim = victim(Vec3(CELL.x + 1.5, CELL.y.toDouble(), CELL.z + 0.5))
        val base: ShapeSource = LevelShapeSource(fake, CollisionContext.empty())
        val emptied = OverrideShapeSource(base = base, x = CELL.x, y = CELL.y, z = CELL.z, shape = Shapes.empty())

        val bound = calc.upperBound(center, victim)
        assertTrue(bound >= calc.damage(center, victim, emptied).effective, "bound must cover the open reading")
        assertTrue(bound >= calc.damage(center, victim, base).effective, "bound must cover the blocked reading")
    }

    /**
     * `rankFinalists` orders each tie group with the comparator it is given. The anchor's contract adds
     * `AnchorEntry.remaining` between the self damage and the distance, so on a full tie the CHEAPER cycle wins
     * even though it is the farther cell - which the crystal aura's own order would get the other way round.
     */
    @Test
    fun `the rank parameter breaks a full tie by the remaining interactions`() {
        val entries = tiedFinalists()

        val byAnchorRank = rankFinalists(
            finalists = { entries },
            selfDamage = { CONSTANT_SELF },
            acceptsSelf = { true },
            rank = compareByDescending<Ranked<Phase>> { it.damage.effective }
                .thenBy { it.selfDamage.effective }
                .thenBy { it.key.remaining }
                .thenBy { it.distSq },
        ).map { it.key.name }.toList()
        assertEquals(listOf("standing", "fresh"), byAnchorRank)

        val byDefault = rankFinalists(
            finalists = { entries },
            selfDamage = { CONSTANT_SELF },
            acceptsSelf = { true },
        ).map { it.key.name }.toList()
        assertEquals(listOf("fresh", "standing"), byDefault, "the default order is distance, not cost")
    }

    /**
     * The self-damage callback is handed the whole [Bound] and not only its centre, because every anchor candidate
     * needs its OWN cell emptied in the local player's shape source and the cell travels as the key.
     */
    @Test
    fun `the self damage callback receives the candidate key`() {
        val seen = ArrayList<String>()
        rankFinalists(
            finalists = ::tiedFinalists,
            selfDamage = { bound ->
                seen += bound.key.name
                CONSTANT_SELF
            },
            acceptsSelf = { true },
        ).toList()
        assertEquals(listOf("fresh", "standing"), seen, "evaluated in the finalist order, nearest tie member first")
    }

    /** A finalist key carrying the interaction cost the anchor ranking tie-breaks on. */
    private class Phase(val name: String, val remaining: Int)

    /**
     * Two finalists with identical target damage, one cheap and far ("standing", one interaction, distance 5) and
     * one costly and near ("fresh", three interactions, distance 1), sorted by [BY_TARGET] as the walk requires.
     */
    private fun tiedFinalists(): List<Scored<Phase>> = listOf(
        scored(Phase("fresh", FULL_CYCLE), distSq = 1.0),
        scored(Phase("standing", DETONATE_ONLY), distSq = 5.0),
    )

    private fun scored(phase: Phase, distSq: Double): Scored<Phase> = Scored(
        Bound(key = phase, center = Vec3.ZERO, distSq = distSq, target = TIE_TARGET, ub = TIE_DAMAGE),
        DamageResult.ZERO.copy(afterMagic = TIE_DAMAGE),
    )
}

/** Target damage both members of the tie group deal, so only the later keys can order them. */
private const val TIE_DAMAGE = 12f

/** Self damage every member of the tie group takes: equal, so it cannot order them either. */
private val CONSTANT_SELF: DamageResult = DamageResult.ZERO.copy(afterMagic = 2f)

/**
 * A [TargetEntry] the ranking walk never reads.
 *
 * [TargetEntry.entity] is a bare [ArmorStand] with no level. Nothing in `rankFinalists` touches it - the entity
 * only matters to the caller that turns a ranking into an `AnchorCandidate` - and a real level would mean a real
 * world for a value that is never used. `Entity`'s constructor stores the level, reads
 * `EntityType.getDimensions()` and calls `setPos(0, 0, 0)`, which is a no-op at the position it already has, so a
 * null level never reaches a dereference (`net/minecraft/world/entity/Entity.java:315-335`).
 */
private val TIE_TARGET: TargetEntry by lazy {
    TargetEntry(
        ArmorStand(EntityType.ARMOR_STAND, noLevel()),
        VictimState(
            feet = Vec3.ZERO,
            box = AABB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3),
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
        ),
        ShapeSource { _, _, _ -> Shapes.empty() },
    )
}

/** `null` typed as anything: the cast is erased, so no runtime check rejects it (see [TIE_TARGET]). */
@Suppress("UNCHECKED_CAST")
private fun <T> noLevel(): T = null as T
