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
 * Our own classifier: the strictest adversary we can build, and the one whose verdict the clone is
 * held to.
 *
 * Unlike a real anticheat this one is handed everything a server could in principle derive — the
 * rotation deltas, where the target was relative to the crosshair, and the attacks — because the
 * point is not to model any specific detector but to find out whether the signal is there *at
 * all*. A detector that looks at less can only do worse.
 */
object AimDiscriminator {

    const val WINDOW = 64

    /**
     * Raised along with the channel count: with target-relative context to correlate against the
     * deltas there is genuinely more structure to find, and too small a state would fail to find
     * it and report a reassuring AUC for the wrong reason.
     */
    private const val HIDDEN = 48

    /**
     * The adversary has to be given a real chance to find something, or a reassuring AUC just
     * means it never trained. At five passes over a few dozen batches this was tens of gradient
     * steps from a random initialisation — a random function, which scores at chance no matter
     * what the clone does, and the headline verdict is read from exactly this number.
     */
    private const val EPOCHS = 40
    private const val TEST_FRACTION = 0.2

    val CONFIG = WindowClassifier.Config(
        window = WINDOW,
        // No overlap: overlapping windows are near-duplicates, and letting them straddle a
        // train/test split inflates the score for a reason that has nothing to do with the clone.
        stride = WINDOW,
        channels = IntArray(CloneEval.CHANNELS) { it },
        hidden = HIDDEN,
        epochs = EPOCHS
    )

    class Report(val auc: Float, val trainWindows: Int, val testWindows: Int)

    /**
     * Trains on [real] and [fake] streams and returns the AUC it reaches on windows it was not
     * trained on.
     *
     * Each stream is flat, [CloneEval.CHANNELS] floats per tick, one list per continuous run; runs
     * are not stitched together because a discontinuity would be an artefact rather than
     * behaviour.
     */
    fun evaluate(real: List<FloatArray>, fake: List<FloatArray>, seed: Long): Report? {
        val random = Random(seed)
        val realWindows = WindowClassifier.windowsOf(real, CONFIG).flatten().shuffled(random)
        val fakeWindows = WindowClassifier.windowsOf(fake, CONFIG).flatten().shuffled(random)

        val perClass = minOf(realWindows.size, fakeWindows.size)
        if (perClass < WindowClassifier.MIN_WINDOWS_PER_CLASS) {
            return null
        }

        val testCount = max(2, (perClass * TEST_FRACTION).toInt())
        val realTest = realWindows.take(testCount)
        val fakeTest = fakeWindows.take(testCount)
        val realTrain = realWindows.drop(testCount).take(perClass)
        val fakeTrain = fakeWindows.drop(testCount).take(perClass)

        if (minOf(realTrain.size, fakeTrain.size) < WindowClassifier.MIN_WINDOWS_PER_CLASS) {
            return null
        }

        val scorer = WindowClassifier.train(realTrain, fakeTrain, CONFIG, seed) ?: return null

        scorer.use {
            val scored = scorer.score(realTest).map { it to 0f } +
                scorer.score(fakeTest).map { it to 1f }

            return Report(
                auc = WindowClassifier.auc(scored),
                trainWindows = realTrain.size + fakeTrain.size,
                testWindows = realTest.size + fakeTest.size
            )
        }
    }
}
