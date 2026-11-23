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
import kotlin.random.Random

/**
 * The tree-ensemble adversary: boosted stumps over hand-engineered summary statistics.
 *
 * Complementary to [AimDiscriminator] rather than a replacement for it. A recurrent net reads the
 * shape of a trajectory and can miss a summary that sits in an impossible band; a tree finds that
 * band with one split and is blind to structure nobody engineered a feature for. Passing one says
 * little; passing both is a real statement.
 *
 * Its most useful output is not the AUC but [Report.topFeatures] — a tree ensemble can say *which*
 * statistic betrayed the clone, which turns "it fails" into something that can be fixed.
 */
object TreeDiscriminator {

    private const val WINDOW = 64
    // Adjacent 64-tick windows used to overlap by 32 ticks and were shuffled across train/test,
    // giving the tree half of every held-out example during fitting. Non-overlap removes that
    // direct leakage; sequence-level splitting would leave too few test runs on small profiles.
    private const val STRIDE = WINDOW
    private const val TEST_FRACTION = 0.25
    private const val MIN_WINDOWS_PER_CLASS = 24

    class Report(
        val auc: Float,
        val windows: Int,
        /** Feature name and its share of total split gain, most telling first. */
        val topFeatures: List<Pair<String, Float>>
    ) {
        val verdict: String
            get() = when {
                auc.isNaN() -> "inconclusive"
                auc <= 0.60f -> "pass — summary statistics look human"
                auc <= 0.70f -> "weak — some statistic is off"
                else -> "fail — a tree separates them easily"
            }
    }

    fun evaluate(real: List<FloatArray>, clone: List<FloatArray>, seed: Long): Report? {
        val realRows = rowsOf(real)
        val cloneRows = rowsOf(clone)

        val perClass = minOf(realRows.size, cloneRows.size)
        if (perClass < MIN_WINDOWS_PER_CLASS) {
            return null
        }

        val random = Random(seed)
        val samples = (realRows.shuffled(random).take(perClass).map { it to 0f } +
            cloneRows.shuffled(random).take(perClass).map { it to 1f }).shuffled(random)

        val testCount = max(4, (samples.size * TEST_FRACTION).toInt())
        val test = samples.take(testCount)
        val train = samples.drop(testCount)
        if (train.size < MIN_WINDOWS_PER_CLASS) {
            return null
        }

        val model = GradientBoosting()
        model.fit(train.map { it.first }.toTypedArray(), train.map { it.second }.toFloatArray())

        val scored = test.map { (row, label) -> model.predict(row) to label }

        val total = model.importance.sum()
        val top = if (total <= 0f) {
            emptyList()
        } else {
            model.importance.withIndex()
                .sortedByDescending { it.value }
                .take(TOP_FEATURES)
                .filter { it.value > 0f }
                .map { AggregateFeatures.NAMES[it.index] to it.value / total }
        }

        return Report(WindowClassifier.auc(scored), samples.size, top)
    }

    private const val TOP_FEATURES = 4

    private fun rowsOf(streams: List<FloatArray>): List<FloatArray> {
        val rows = mutableListOf<FloatArray>()

        for (stream in streams) {
            val ticks = stream.size / CloneEval.CHANNELS
            var start = 0
            while (start + WINDOW <= ticks) {
                rows.add(AggregateFeatures.of(stream, start, WINDOW))
                start += STRIDE
            }
        }
        return rows
    }
}
