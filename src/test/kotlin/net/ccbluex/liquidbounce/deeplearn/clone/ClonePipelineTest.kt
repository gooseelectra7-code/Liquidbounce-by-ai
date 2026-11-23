/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.ccbluex.liquidbounce.deeplearn.clone

import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class ClonePipelineTest {

    private companion object {
        /** Short enough that the fixtures cross several chunk boundaries without being long. */
        const val CHUNK = 8

        /** `FeatureSchema` stores pitch divided by this, and the tests read it back out. */
        const val PITCH_SCALE = 90f
    }

    @Test
    fun `target switch is a flag rather than fake target velocity`() {
        val sequence = sequence(
            listOf(
                tick(targetYaw = 10f, targetId = 1),
                tick(targetYaw = 80f, targetId = 2)
            )
        )
        val tensor = SequenceTensor(sequence)
        val offset = FeatureSchema.FEATURES

        assertEquals(1f, tensor.featureRows[offset + FeatureSchema.F_TARGET_CHANGED])
        assertEquals(0f, tensor.featureRows[offset + FeatureSchema.F_TARGET_VEL_YAW])
        assertEquals(0f, tensor.featureRows[offset + FeatureSchema.F_TARGET_VEL_PITCH])
        assertEquals(0f, tensor.featureRows[offset + FeatureSchema.F_RADIAL_VEL])
    }

    @Test
    fun `sustained slow turn-away is cut out of combat`() {
        val ticks = List(200) { index ->
            tick(
                targetYaw = if (index in 70 until 90) 90f else 0f,
                attacked = index == 20 || index == 100 || index == 180
            )
        }

        val result = CloneRecordingSanitizer.sanitize(sequence(ticks))

        assertEquals(20, result.disengagedTicks)
        assertEquals(2, result.sequences.size)
        assertTrue(result.sequences.all { it.ticks.none { tick -> tick.targetYaw == 90f } })
    }

    @Test
    fun `fast emergency turn is retained`() {
        val ticks = List(120) { index ->
            tick(
                targetYaw = if (index in 40 until 60) 90f else 0f,
                dx = if (index in 40 until 60) 100 else 0,
                attacked = index == 20 || index == 100
            )
        }

        val result = CloneRecordingSanitizer.sanitize(sequence(ticks))

        assertEquals(0, result.disengagedTicks)
        assertEquals(1, result.sequences.size)
        assertTrue(result.sequences.single().ticks.any { it.targetYaw == 90f })
    }

    @Test
    fun `practice is told apart by source and, on old recordings, by client-only target ids`() {
        val real = List(4) { tick(targetId = 42) }
        val dummy = List(4) { tick(targetId = -1_000_000) }

        assertTrue(sequence(dummy).isPractice, "legacy: every target is a client-only entity")
        assertTrue(!sequence(real).isPractice, "legacy: a real opponent")
        assertTrue(!sequence(real + dummy).isPractice, "legacy: a real opponent anywhere is a fight")
        assertTrue(sequence(real, AimSequence.SOURCE_GYM).isPractice, "the recorded source wins")
        assertTrue(sequence(real, AimSequence.SOURCE_DUMMY).isPractice)
        assertTrue(!sequence(dummy, "mc.example.net").isPractice)
    }

    @Test
    fun `practice is set aside unless it is all there is`() {
        val fight = sequence(List(4) { tick(targetId = 7) }, "mc.example.net")
        val drill = sequence(List(4) { tick(targetId = -3) })

        val (used, setAside) = AimSequence.selectForTraining(listOf(fight, drill))
        assertEquals(listOf(fight), used)
        assertEquals(listOf(drill), setAside)

        val (onlyDrill, none) = AimSequence.selectForTraining(listOf(drill))
        assertEquals(listOf(drill), onlyDrill)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `asinh action scale round-trips and stays linear near zero`() {
        val normalization = FeatureSchema.Normalization(
            FloatArray(FeatureSchema.FEATURES),
            FloatArray(FeatureSchema.FEATURES) { 1f },
            floatArrayOf(0.3f, -0.1f),
            floatArrayOf(2.5f, 1.5f),
            actionScale = 2f
        )
        for (degrees in floatArrayOf(-170f, -45f, -2f, -0.15f, 0.15f, 1f, 12f, 90f, 175f)) {
            for (axis in 0..1) {
                val back = normalization.denormalizeAction(normalization.normalizeAction(degrees, axis), axis)
                assertEquals(degrees, back, abs(degrees) * 1e-4f + 1e-4f)
            }
        }
        // Linear below the scale: a 0.15 degree step compresses by well under a percent.
        assertEquals(0.15f, FeatureSchema.Normalization.compress(0.15f, 2f), 1e-3f)
        // Logarithmic above it: a 175 degree flick lands near 10, not 175.
        assertTrue(FeatureSchema.Normalization.compress(175f, 2f) < 11f)
        // A wild sample cannot overflow into a non-finite step.
        assertTrue(normalization.denormalizeAction(1e6f, 0).isFinite())
    }

    @Test
    fun `joint loss keeps its closed-form self test`() {
        assertEquals(
            CloneLoss.SELF_TEST_EXPECTED,
            CloneLoss.selfTest(),
            CloneLoss.SELF_TEST_TOLERANCE
        )
    }

    @Test
    fun `bptt plays every tick once, in order, and clears state between recordings`() {
        val recordings = identities(listOf(30, 17, 41))
        val tensors = identifiableTensors(recordings)
        val bptt = bpttOver(tensors, lanes = 2)

        assertEquals(2, bptt.lanes)

        val played = Array(bptt.lanes) { mutableListOf<Int>() }
        val cleared = IntArray(bptt.lanes)

        val chunks = bptt.epoch(Random(1))
        while (chunks.hasNext()) {
            val chunk = chunks.next()
            for (lane in 0 until bptt.lanes) {
                val before = played[lane].size
                for (t in 0 until CHUNK) {
                    val cell = lane * CHUNK + t
                    if (chunk.weight[cell] > 0f) {
                        played[lane].add(identityOf(chunk, cell))
                    }
                }

                // A lane out of work reports a cleared state every step, which is true and
                // meaningless; only a step it actually played counts as picking one up.
                if (chunk.keepState[lane] == 0f && played[lane].size > before) {
                    cleared[lane]++
                }
            }
        }

        // Nothing dropped and nothing played twice.
        assertEquals(recordings.flatten().sorted(), played.flatMap { it }.sorted())

        for (lane in 0 until bptt.lanes) {
            val runs = runsOf(played[lane])
            assertEquals(
                played[lane],
                runs.flatten(),
                "lane $lane replayed its ticks out of order"
            )
            assertTrue(
                runs.all { it in recordings },
                "lane $lane mixed ticks from different recordings into one run"
            )

            // The state is cleared exactly when a lane picks up a recording, never mid-stream.
            assertEquals(runs.size, cleared[lane], "lane $lane cleared its state at the wrong time")
        }
    }

    @Test
    fun `bptt splits a lone recording across lanes and burns in every seam`() {
        val length = 100
        val tensors = identifiableTensors(identities(listOf(length)))
        val bptt = bpttOver(tensors, lanes = 4)

        assertEquals(4, bptt.lanes)

        var scored = 0
        val chunks = bptt.epoch(Random(2))
        while (chunks.hasNext()) {
            val chunk = chunks.next()
            scored += chunk.weight.count { it > 0f }
        }

        // Splitting a recording invents three seams the model has no history for, and the ticks
        // right after each are burn-in rather than examples — everything else is scored.
        val seams = bptt.lanes - 1
        assertEquals(length - seams * FeatureSchema.WARMUP, scored)
    }

    /** Contiguous ascending runs, which is what one recording looks like inside a lane. */
    private fun runsOf(played: List<Int>): List<List<Int>> {
        val runs = mutableListOf<MutableList<Int>>()
        for (id in played) {
            val last = runs.lastOrNull()
            if (last != null && last.last() + 1 == id) {
                last.add(id)
            } else {
                runs.add(mutableListOf(id))
            }
        }
        return runs
    }

    /**
     * A tick identity per recording, with a gap between recordings so that two of them replayed
     * back to back can never be mistaken for one continuous run.
     */
    private fun identities(lengths: List<Int>): List<List<Int>> {
        var next = 0
        return lengths.map { length ->
            val ids = (next until next + length).toList()
            next += length + 1
            ids
        }
    }

    /**
     * Tensors whose every tick carries its own identity, so a scheduler that reordered or
     * duplicated one is caught rather than merely suspected. Pitch is the carrier because the
     * schema passes it through as a plain scale, and [passthrough] leaves it at that scale.
     */
    private fun identifiableTensors(recordings: List<List<Int>>): List<SequenceTensor> =
        recordings.map { ids ->
            SequenceTensor(sequence(ids.map { tick(ownPitch = it.toFloat()) }))
        }

    private fun identityOf(chunk: CloneBptt.Chunk, cell: Int): Int {
        val pitch = chunk.features[cell * FeatureSchema.FEATURES + FeatureSchema.F_OWN_PITCH]
        return Math.round(pitch * PITCH_SCALE)
    }

    private fun bpttOver(tensors: List<SequenceTensor>, lanes: Int) = CloneBptt(
        tensors = tensors,
        marks = CloneDisengagement().markAll(tensors),
        normalization = passthrough(),
        weighting = CloneTickWeights.fit(tensors),
        requestedLanes = lanes,
        chunk = CHUNK
    )

    /** Standardization that changes nothing, so a feature can be read back as it was written. */
    private fun passthrough() = FeatureSchema.Normalization(
        FloatArray(FeatureSchema.FEATURES),
        FloatArray(FeatureSchema.FEATURES) { 1f },
        FloatArray(2),
        FloatArray(2) { 1f }
    )

    private fun sequence(ticks: List<AimTick>, source: String? = null) = AimSequence(
        version = AimSequence.VERSION,
        profile = "test",
        gcd = 0.15,
        sensitivity = 0.5,
        timestamp = 1L,
        ticks = ticks,
        source = source
    )

    @Suppress("LongParameterList")
    private fun tick(
        targetYaw: Float = 0f,
        targetId: Int = 1,
        dx: Int = 0,
        dy: Int = 0,
        attacked: Boolean = false,
        ownPitch: Float = 0f
    ) = AimTick(
        ownYaw = 0f,
        ownPitch = ownPitch,
        dx = dx,
        dy = dy,
        targetYaw = targetYaw,
        targetPitch = ownPitch,
        distance = 3f,
        hurtTime = 0,
        targetWidth = 0.6f,
        targetHeight = 1.8f,
        targetId = targetId,
        moveForward = 0f,
        moveStrafe = 0f,
        onTarget = targetYaw == 0f,
        attacked = attacked,
        hasTarget = true,
        attackCooldown = 1f,
        sprinting = false,
        sneaking = false,
        onGround = true,
        usingItem = false,
        receivedVelocity = false,
        tookDamage = false
    )
}
