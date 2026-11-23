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
package net.ccbluex.liquidbounce.deeplearn.clone.eval

import kotlin.math.max

/**
 * Reproduces the collection pipeline and violation arithmetic of the Shard anticheat, so a clone
 * can be judged under the conditions it will actually meet rather than under ours.
 *
 * Two things make this worth running separately from [CloneEval]'s own check. Shard's model is fed
 * *only* the two raw rotation deltas per tick — no target geometry, no attacks, no movement — so
 * it is strictly blinder than our own classifier, and a clone can fail ours while passing this.
 * And its verdict is not a single number but an accumulator: probabilities below 0.9 contribute
 * nothing at all, so "how often does it exceed the threshold, and for how long in a row" is the
 * question that decides a ban, not the average score.
 *
 * The numbers below are read from the plugin's own source and shipped config. They are what the
 * server operator gets out of the box; a server can override the window and step from the cloud,
 * so treat a pass here as evidence rather than a guarantee.
 */
object ShardSimulation {

    /**
     * Which release is being modelled. The two differ so much that a pass against one says
     * nothing about the other.
     *
     * [V1] is the shipped `1.0`: two floats per tick — the raw yaw and pitch deltas — over a
     * sliding 40-tick window, collected only within 40 ticks of an attack.
     *
     * [V2] is the `next/2.0` branch: around a hundred fields per tick, and a window cut *around
     * each attack* (32 before, 32 after) instead of sliding. Most of the new fields describe the
     * attack itself — whether the ray hit, where in the hitbox, how long until the next swing —
     * and the rotation is expressed in mouse counts derived from an inferred sensitivity rather
     * than in degrees.
     */
    enum class Version(val label: String) {
        V1("1.0"),
        V2("next/2.0")
    }

    /** Ticks per window in 1.0 (`DEFAULT_AI_SEQUENCE`). */
    const val SEQUENCE = 40

    /** Ticks between windows in 1.0 (`DEFAULT_AI_STEP`). */
    const val STEP = 10

    /** Ticks captured before each attack in 2.0 (`ai.inference.pre-window`). */
    const val PRE_WINDOW = 32

    /** Ticks captured after each attack in 2.0 (`ai.inference.post-window`). */
    const val POST_WINDOW = 32

    /** Minimum ticks between inference requests in 2.0 (`ai.inference.step`). */
    const val V2_STEP = 32

    /** Violation level at which a flag fires (`ai.buffer.flag`). */
    const val FLAG = 50.0

    /** Violation level the buffer falls back to after a flag (`ai.buffer.reset-on-flag`). */
    const val RESET_ON_FLAG = 25.0

    /** `buffer += (probability - THRESHOLD) * MULTIPLIER` (`ai.buffer.multiplier`). */
    const val MULTIPLIER = 100.0

    /**
     * Subtracted when the probability drops below [LOW_PROBABILITY] (`ai.buffer.decrease`).
     *
     * 1.0 in the shipped `next/2.0` config, read from the source (was 0.25 in the 1.0 line this
     * was first modelled from). Larger means the buffer bleeds down four times faster on a clearly
     * human window, so a clone that produces occasional confident-innocent windows recovers more
     * of its violation level between the suspicious ones — this direction helps a good clone.
     *
     * Not modelled here, and worth knowing: `next/2.0` adds a persistent buffer
     * (`ai.persistent-buffer`) that survives a disconnect for 48 h (restored up to 40, decaying
     * 2/h). Relogging no longer resets accumulated suspicion, so a clone cannot bank on a fresh
     * buffer each session — the run has to stay under the threshold, not merely reach it slowly.
     */
    const val DECREASE = 1.0

    /** Nothing below this contributes anything to the violation level at all. */
    const val THRESHOLD = 0.9

    const val LOW_PROBABILITY = 0.1

    private const val HIDDEN = 32
    private const val EPOCHS = 40
    private const val TICKS_PER_SECOND = 20f

    class Report(
        val auc: Float,
        val windows: Int,
        val aboveThreshold: Int,
        val longestRun: Int,
        val peakBuffer: Double,
        val flaggedRuns: Int,
        val totalRuns: Int,
        val secondsToFirstFlag: Float?,
        /** True when only windows containing an attack were scored, as Shard does by default. */
        val combatOnly: Boolean,
        val version: Version
    ) {
        val thresholdFraction: Float
            get() = if (windows == 0) 0f else aboveThreshold.toFloat() / windows

        val verdict: String
            get() = when {
                flaggedRuns > 0 -> "fail — would be flagged"
                peakBuffer >= FLAG / 2 -> "risky — violation level reached %.0f of %.0f"
                    .format(peakBuffer, FLAG)
                aboveThreshold > 0 -> "pass — crosses the threshold but never accumulates"
                else -> "pass — never crosses the threshold"
            }
    }

    /**
     * Trains a classifier under Shard's input restriction, then replays the clone's own windows
     * through its violation arithmetic.
     *
     * The classifier here is trained specifically to separate *this* clone from *this* player,
     * which makes it a harder adversary than Shard's own model — that one is trained against a
     * corpus of other cheats and has never seen a behavioural clone. Surviving this is therefore
     * the conservative direction.
     */
    fun run(
        real: List<FloatArray>,
        clone: List<FloatArray>,
        seed: Long,
        version: Version = Version.V2,
        /** One sensitivity per stream — a profile may mix them, and the mouse-count channel is
         *  meaningless if every sequence is divided by the first sequence's value. */
        gcds: List<Double>,
        combatOnly: Boolean = true
    ): Report? {
        val config = configFor(version)

        // 1.0 slides a window through combat; 2.0 cuts one around each attack instead. Both stop
        // looking outside combat, which keeps the idle ticks — where any clone looks its most
        // human — from diluting the score.
        val realRuns = runsFor(real, version, config, gcds, combatOnly)
        val cloneRuns = runsFor(clone, version, config, gcds, combatOnly)

        // Hold out whole runs, not windows. Consecutive windows share most of their ticks — 30 of
        // 40 in the sliding case — so fitting and scoring the same list would be asking a
        // classifier about the very windows it memorised, and the violation arithmetic below needs
        // probabilities above 0.9, which is exactly what memorisation produces.
        val split = holdOut(realRuns, cloneRuns)
            ?: return null

        val scorer = WindowClassifier.train(
            split.trainReal,
            split.trainClone,
            config,
            seed
        ) ?: return null

        scorer.use {
            // The scorer returns P(generated), so the generated class is the positive one. Labelling
            // it the other way round reports 1 - AUC, which reads as a flawless clone when the
            // classifier is in fact separating them perfectly.
            val scored = scorer.score(split.testReal).map { it to 0f } +
                scorer.score(split.testClone).map { it to 1f }

            val cloneRuns = split.testCloneRuns

            var aboveThreshold = 0
            var longestRun = 0
            var peakBuffer = 0.0
            var flaggedRuns = 0
            var ticksToFirstFlag: Int? = null
            var total = 0

            for (run in cloneRuns) {
                if (run.isEmpty()) {
                    continue
                }

                val probabilities = scorer.score(run)
                var buffer = 0.0
                var streak = 0
                var flagged = false

                for ((index, probability) in probabilities.withIndex()) {
                    total++

                    if (probability > THRESHOLD) {
                        aboveThreshold++
                        streak++
                        longestRun = max(longestRun, streak)
                        buffer += (probability - THRESHOLD) * MULTIPLIER
                    } else {
                        streak = 0
                        if (probability < LOW_PROBABILITY) {
                            buffer = max(0.0, buffer - DECREASE)
                        }
                    }

                    peakBuffer = max(peakBuffer, buffer)

                    if (buffer >= FLAG) {
                        if (!flagged) {
                            flagged = true
                            flaggedRuns++
                        }
                        if (ticksToFirstFlag == null) {
                            // 1.0 scores on a fixed cadence; 2.0 scores once per swing, so its
                            // configured minimum interval is the closest thing to one.
                            val cadence = if (version == Version.V1) STEP else V2_STEP
                            ticksToFirstFlag = (index + 1) * cadence
                        }
                        buffer = RESET_ON_FLAG
                    }
                }
            }

            return Report(
                auc = WindowClassifier.auc(scored),
                windows = total,
                aboveThreshold = aboveThreshold,
                longestRun = longestRun,
                peakBuffer = peakBuffer,
                flaggedRuns = flaggedRuns,
                totalRuns = cloneRuns.count { it.isNotEmpty() },
                secondsToFirstFlag = ticksToFirstFlag?.let { it / TICKS_PER_SECOND },
                combatOnly = combatOnly,
                version = version
            )
        }
    }

    /** Runs kept for fitting, and the disjoint runs kept for scoring. */
    private class Split(
        val trainReal: List<FloatArray>,
        val trainClone: List<FloatArray>,
        val testReal: List<FloatArray>,
        val testClone: List<FloatArray>,
        val testCloneRuns: List<List<FloatArray>>
    )

    /**
     * Splits by run rather than by window, so no tick is on both sides. The violation buffer is
     * then replayed over held-out runs only, which is what a server would actually be scoring.
     */
    private fun holdOut(
        realRuns: List<List<FloatArray>>,
        cloneRuns: List<List<FloatArray>>
    ): Split? {
        val realUsable = realRuns.filter { it.isNotEmpty() }
        val cloneUsable = cloneRuns.filter { it.isNotEmpty() }
        if (realUsable.size < 2 || cloneUsable.size < 2) {
            return null
        }

        val realTestCount = max(1, realUsable.size / TEST_RUN_DIVISOR)
        val cloneTestCount = max(1, cloneUsable.size / TEST_RUN_DIVISOR)

        val split = Split(
            trainReal = realUsable.drop(realTestCount).flatten(),
            trainClone = cloneUsable.drop(cloneTestCount).flatten(),
            testReal = realUsable.take(realTestCount).flatten(),
            testClone = cloneUsable.take(cloneTestCount).flatten(),
            testCloneRuns = cloneUsable.take(cloneTestCount)
        )

        val enough = minOf(split.trainReal.size, split.trainClone.size) >=
            WindowClassifier.MIN_WINDOWS_PER_CLASS &&
            minOf(split.testReal.size, split.testClone.size) > 0

        return split.takeIf { enough }
    }

    /** A quarter of the runs are held out. */
    private const val TEST_RUN_DIVISOR = 4

    private fun configFor(version: Version) = when (version) {
        Version.V1 -> WindowClassifier.Config(
            window = SEQUENCE,
            stride = STEP,
            // All 1.0 ever sees: the raw yaw and pitch deltas.
            channels = intArrayOf(CloneEval.C_DX, CloneEval.C_DY),
            hidden = HIDDEN,
            epochs = EPOCHS
        )

        Version.V2 -> WindowClassifier.Config(
            window = PRE_WINDOW + POST_WINDOW,
            stride = PRE_WINDOW + POST_WINDOW,
            channels = IntArray(V2Stream.CHANNELS) { it },
            hidden = HIDDEN,
            epochs = EPOCHS,
            sourceChannels = V2Stream.CHANNELS
        )
    }

    private fun runsFor(
        streams: List<FloatArray>,
        version: Version,
        config: WindowClassifier.Config,
        gcds: List<Double>,
        combatOnly: Boolean
    ): List<List<FloatArray>> = when (version) {
        Version.V1 -> slidingRuns(streams, config, combatOnly)
        Version.V2 -> streams.mapIndexed { index, stream ->
            attackWindows(V2Stream.of(stream, gcds.getOrElse(index) { 0.0 }), config)
        }
    }

    /**
     * Sliding windows, optionally keeping only those that contain an attack — 1.0's default
     * `continuous: false` drops everything outside combat.
     */
    private fun slidingRuns(
        streams: List<FloatArray>,
        config: WindowClassifier.Config,
        combatOnly: Boolean
    ): List<List<FloatArray>> {
        val windows = WindowClassifier.windowsOf(streams, config)
        if (!combatOnly) {
            return windows
        }

        return streams.mapIndexed { index, stream ->
            windows[index].filterIndexed { window, _ ->
                hasAttack(stream, window * config.stride, config.window, CloneEval.CHANNELS)
            }
        }
    }

    /**
     * One window per attack, cut [PRE_WINDOW] ticks before it and [POST_WINDOW] after — 2.0 scores
     * each swing in its own context rather than sweeping a window along.
     *
     * That change is what makes the click/aim relationship the centre of the measurement instead
     * of an incidental part of it.
     */
    private fun attackWindows(
        stream: FloatArray,
        config: WindowClassifier.Config
    ): List<FloatArray> {
        val ticks = stream.size / V2Stream.CHANNELS
        val result = mutableListOf<FloatArray>()

        for (i in 0 until ticks) {
            if (stream[i * V2Stream.CHANNELS + V2Stream.C_ATTACK] <= 0.5f) {
                continue
            }

            val start = i - PRE_WINDOW
            if (start < 0 || start + config.window > ticks) {
                continue
            }
            result.add(WindowClassifier.extract(stream, start, config))
        }
        return result
    }

    private fun hasAttack(stream: FloatArray, start: Int, length: Int, channels: Int): Boolean {
        for (t in start until start + length) {
            if (stream[t * channels + CloneEval.C_ATTACKED] > 0.5f) {
                return true
            }
        }
        return false
    }
}
