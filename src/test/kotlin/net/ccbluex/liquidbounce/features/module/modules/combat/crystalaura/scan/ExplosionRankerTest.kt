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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.PlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.world.Difficulty
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [ExplosionRanker.ranked] and its walk [rankFinalists] against the single-result search they replace.
 *
 * The reference is [oldBest], the loop `ExplosionRanker.best()` ran before the sequence existed: walk the finalists
 * (already in `BY_TARGET` order), stop at the first one whose target damage is strictly below that of an accepted
 * candidate, and keep the `BY_RANK` minimum of everything accepted on the way. The contract the tests hold the new
 * walk to is therefore not just "same head" but "same head for the same self-damage evaluations, in the same
 * order" - one evaluation is 45 rays and the reaction fix must not have made the common tick more expensive.
 *
 * [SelfDamageLog] records those evaluations. The centre of the finalist at index `i` is `(i, 0, 0)` ([finalists]),
 * so the log is the list of finalist indices the walk paid for.
 */
class ExplosionRankerTest {

    private val settings = PlaceSettings()

    private val targetState = victim(Vec3(0.5, 64.0, 0.5))

    private val target = target(targetState)

    @Test
    fun `head is the old selection when the tie group re-orders by self damage`() {
        // A is first by BY_TARGET (equal damage, nearer), B wins by BY_RANK (equal damage, less self damage).
        val cells = listOf(
            Finalist("A", effective = 10f, distSq = 0.5, self = 5f),
            Finalist("B", effective = 10f, distSq = 1.5, self = 1f),
            Finalist("C", effective = 8f, distSq = 0.1, self = 0f),
        )
        val list = finalists(cells, target)
        val log = SelfDamageLog(cells)
        val head = rankFinalists({ list }, log::of, ::acceptsSelf).first()

        assertEquals("B", head.key)
        assertEquals(1f, head.selfDamage.effective)
        // The whole tie group, and only it: C is never evaluated although it is a finalist.
        assertEquals(listOf(0, 1), log.evaluated)
        assertSameAsOld(list, cells, head)
    }

    @Test
    fun `head skips a tie group that fails the self checks`() {
        val cells = listOf(
            Finalist("A", effective = 10f, distSq = 0.5, self = 9f),
            Finalist("B", effective = 10f, distSq = 1.5, self = 7f),
            Finalist("C", effective = 8f, distSq = 0.1, self = 3f),
        )
        val list = finalists(cells, target)
        val log = SelfDamageLog(cells)
        val head = rankFinalists({ list }, log::of, ::acceptsSelf).first()

        assertEquals("C", head.key)
        assertEquals(listOf(0, 1, 2), log.evaluated)
        assertSameAsOld(list, cells, head)
    }

    @Test
    fun `the sequence is ordered and drops what the self checks reject`() {
        val cells = orderedCells()
        val list = finalists(cells, target)
        val log = SelfDamageLog(cells)
        val ranked = rankFinalists({ list }, log::of, ::acceptsSelf).toList()

        assertEquals(listOf("B", "A", "D", "E"), ranked.map { it.key })
        assertEquals(listOf(10f, 10f, 8f, 6f), ranked.map { it.damage.effective })
        assertEquals(listOf(1f, 5f, 2f, 0f), ranked.map { it.selfDamage.effective })
        assertTrue(ranked.zipWithNext().all { (a, b) -> a.damage.effective >= b.damage.effective })
    }

    @Test
    fun `pulling the head evaluates only its own tie group`() {
        val cells = orderedCells()
        val list = finalists(cells, target)
        val headLog = SelfDamageLog(cells)
        val fullLog = SelfDamageLog(cells)

        rankFinalists({ list }, headLog::of, ::acceptsSelf).first()
        rankFinalists({ list }, fullLog::of, ::acceptsSelf).toList()

        assertEquals(listOf(0, 1), headLog.evaluated)
        assertEquals(listOf(0, 1, 2, 3, 4), fullLog.evaluated)
        // The supplier is pulled lazily as well: building the sequence must not drain the ranker's queue.
        var built = false
        rankFinalists({ built = true; list }, headLog::of, ::acceptsSelf)
        assertTrue(!built)
    }

    @Test
    fun `no accepted finalist yields an empty sequence at the old cost`() {
        val cells = listOf(
            Finalist("A", effective = 10f, distSq = 0.5, self = 9f),
            Finalist("B", effective = 8f, distSq = 0.5, self = 9f),
        )
        val list = finalists(cells, target)
        val log = SelfDamageLog(cells)

        assertNull(rankFinalists({ list }, log::of, ::acceptsSelf).firstOrNull())
        assertEquals(listOf(0, 1), log.evaluated)
        assertNull(oldBest(list, SelfDamageLog(cells)::of))
    }

    @Test
    fun `head and cost match the old selection on random rankings`() {
        val random = Random(20260906)
        repeat(RANDOM_RANKINGS) {
            val cells = (0 until random.nextInt(1, MAX_CELLS)).map { index ->
                Finalist(
                    key = "c$index",
                    effective = TIED_DAMAGES.random(random),
                    distSq = random.nextInt(1, 6).toDouble(),
                    self = random.nextInt(0, 10).toFloat(),
                )
            }
            val list = finalists(cells, target)
            val newLog = SelfDamageLog(cells)
            val oldLog = SelfDamageLog(cells)
            val head = rankFinalists({ list }, newLog::of, ::acceptsSelf).firstOrNull()
            val reference = oldBest(list, oldLog::of)

            assertEquals(reference?.key, head?.key)
            assertEquals(reference?.selfDamage?.effective, head?.selfDamage?.effective)
            assertEquals(oldLog.evaluated, newLog.evaluated)
        }
    }

    @Test
    fun `ranked walks the tie group of a real ranker`() {
        // Two centres exactly 3 blocks from the target's feet - the same damage - and 11.31 / 12.08 blocks from the
        // local player, so only the first of them hurts us at all. BY_TARGET offers the nearer cell first, BY_RANK
        // has to answer with the harmless one.
        val selfState = victim(Vec3(11.5, 64.0, 8.5))
        val view = WorldView(
            tick = 0,
            eye = Vec3(11.5, 65.6, 8.5),
            self = selfState,
            selfSource = emptyShapes(),
            targets = listOf(target),
            crystals = emptyList(),
            difficulty = Difficulty.NORMAL,
        )
        val ranked = ranker(view).also { offerPair(it) }.ranked().toList()

        assertEquals(listOf("far", "near"), ranked.map { it.key })
        assertEquals(ranked[0].damage.effective, ranked[1].damage.effective)
        assertEquals(0f, ranked[0].selfDamage.effective)
        assertTrue(ranked[1].selfDamage.effective > 0f)
        assertEquals("far", ranker(view).also { offerPair(it) }.best()?.key)
    }

    /** The five cells of the ordering / laziness tests: two tie groups, one rejected member, one lone tail. */
    private fun orderedCells(): List<Finalist> = listOf(
        Finalist("A", effective = 10f, distSq = 0.5, self = 5f),
        Finalist("B", effective = 10f, distSq = 1.5, self = 1f),
        Finalist("C", effective = 8f, distSq = 0.1, self = 9f),
        Finalist("D", effective = 8f, distSq = 0.2, self = 2f),
        Finalist("E", effective = 6f, distSq = 0.0, self = 0f),
    )

    /** `selfDamage.effective <= MaxSelfDamage`, the acceptance the walk is given (AntiSuicide cannot bite here). */
    private fun acceptsSelf(damage: DamageResult): Boolean = damage.effective <= settings.maxSelfDamage

    /** [head] is what [oldBest] would have returned, for the very same evaluations in the very same order. */
    private fun assertSameAsOld(list: List<Scored<String>>, cells: List<Finalist>, head: Ranked<String>) {
        val log = SelfDamageLog(cells)
        val reference = oldBest(list, log::of)
        assertEquals(reference?.key, head.key)
        assertEquals(reference?.selfDamage?.effective, head.selfDamage.effective)
    }

    /**
     * The loop `ExplosionRanker.best()` used to be, written out independently of the production comparator: walk
     * the finalists in [ExplosionRanker.BY_TARGET] order, break at the first target damage strictly below that of
     * an accepted candidate, keep the `BY_RANK` minimum of the accepted ones.
     */
    private fun oldBest(finalists: List<Scored<String>>, selfDamage: (Vec3) -> DamageResult): Ranked<String>? {
        var best: Ranked<String>? = null
        for (scored in finalists) {
            if (best != null && scored.damage.effective < best.damage.effective) break
            val self = selfDamage(scored.bound.center)
            if (acceptsSelf(self)) {
                val ranked = Ranked(scored, self)
                if (best == null || BY_RANK.compare(ranked, best) < 0) best = ranked
            }
        }
        return best
    }

    private fun ranker(view: WorldView): ExplosionRanker<String> = ExplosionRanker(
        view = view,
        calc = DamageCalculator(respectIFrames = false),
        settings = settings,
        victimOf = { it.placeState },
        sourceOf = { it.source },
        selfSource = emptyShapes(),
        gate = { true },
    )

    private fun offerPair(ranker: ExplosionRanker<String>) {
        ranker.offer("near", Vec3(3.5, 64.0, 0.5), 1.0)
        ranker.offer("far", Vec3(0.5, 64.0, 3.5), 9.0)
    }

    private companion object {

        const val RANDOM_RANKINGS = 400
        const val MAX_CELLS = 9

        /** Few distinct damages, so that tie groups - the whole point of the walk - occur constantly. */
        val TIED_DAMAGES = listOf(6f, 8f, 10f)

        /** The contract order, spelled out here so the test does not lean on the production comparator. */
        val BY_RANK: Comparator<Ranked<*>> = compareByDescending<Ranked<*>> { it.damage.effective }
            .thenBy { it.selfDamage.effective }
            .thenBy { it.distSq }

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }
}

/**
 * Self damage by explosion centre, recording which finalist each evaluation belonged to. The centre of finalist `i`
 * is `(i, 0, 0)` ([finalists]), so [evaluated] is the list of indices the walk paid a `calc.damage` for.
 */
private class SelfDamageLog(cells: List<Finalist>) {

    private val sorted = cells.sortedWith(compareByDescending<Finalist> { it.effective }.thenBy { it.distSq })

    val evaluated = ArrayList<Int>()

    fun of(center: Vec3): DamageResult {
        val index = center.x.toInt()
        evaluated += index
        return result(sorted[index].self)
    }
}
