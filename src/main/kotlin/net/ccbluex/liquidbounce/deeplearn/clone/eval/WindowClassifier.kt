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

import ai.djl.Model
import ai.djl.engine.Engine
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.types.Shape
import ai.djl.nn.SequentialBlock
import ai.djl.nn.core.Linear
import ai.djl.nn.recurrent.GRU
import ai.djl.training.DefaultTrainingConfig
import ai.djl.training.Trainer
import ai.djl.training.dataset.ArrayDataset
import ai.djl.training.initializer.XavierInitializer
import ai.djl.training.loss.Loss
import ai.djl.training.optimizer.Adam
import ai.djl.training.tracker.Tracker
import java.io.Closeable
import kotlin.math.exp
import kotlin.random.Random

/**
 * The shared machinery behind every "can something tell these two streams apart" question.
 *
 * Kept separate from any particular adversary because the interesting comparisons differ only in
 * what the classifier is *allowed to see*: our own check hands it target-relative context and
 * attacks, while [ShardSimulation] restricts it to the two raw rotation deltas a specific
 * anticheat actually collects. Same classifier, different blindfold.
 */
object WindowClassifier {

    private const val BATCH = 64
    private const val LEARNING_RATE = 1e-3f

    /** Below this per class the result is noise rather than a measurement. */
    const val MIN_WINDOWS_PER_CLASS = 32

    class Config(
        val window: Int,
        val stride: Int,
        /** Which channels of the source stream the classifier may look at. */
        val channels: IntArray,
        val hidden: Int,
        val epochs: Int,
        /** Channels per tick in the source stream the windows are cut from. */
        val sourceChannels: Int = CloneEval.CHANNELS
    )

    /**
     * Slices streams into windows, keeping only the permitted channels.
     *
     * [Config.stride] equal to the window means no overlap, which is what an honest train/test
     * split wants; a smaller stride reproduces an anticheat that re-scores a sliding window.
     */
    fun windowsOf(streams: List<FloatArray>, config: Config): List<List<FloatArray>> =
        streams.map { stream ->
            val result = mutableListOf<FloatArray>()
            val ticks = stream.size / config.sourceChannels
            var start = 0

            while (start + config.window <= ticks) {
                result.add(extract(stream, start, config))
                start += config.stride
            }
            result
        }

    /** Copies one window out of a stream, keeping only the permitted channels. */
    fun extract(stream: FloatArray, start: Int, config: Config): FloatArray {
        val window = FloatArray(config.window * config.channels.size)
        for (t in 0 until config.window) {
            for ((index, channel) in config.channels.withIndex()) {
                window[t * config.channels.size + index] =
                    stream[(start + t) * config.sourceChannels + channel]
            }
        }
        return window
    }

    /**
     * A trained classifier, kept open so windows can be scored after the fact.
     *
     * [score] returns the probability that a window came from the *fake* class, which is what an
     * anticheat would call the cheat probability.
     */
    class Scorer(
        private val manager: NDManager,
        private val model: Model,
        private val trainer: Trainer,
        private val scale: FloatArray,
        private val config: Config
    ) : Closeable {

        fun score(windows: List<FloatArray>): FloatArray {
            if (windows.isEmpty()) {
                return FloatArray(0)
            }

            val result = FloatArray(windows.size)
            var index = 0

            while (index < windows.size) {
                val end = minOf(index + BATCH, windows.size)
                val chunk = windows.subList(index, end)
                val data = FloatArray(chunk.size * config.window * config.channels.size)

                for ((i, window) in chunk.withIndex()) {
                    for (j in window.indices) {
                        data[i * window.size + j] = window[j] / scale[j % config.channels.size]
                    }
                }

                val input = manager.create(
                    data,
                    Shape(
                        chunk.size.toLong(),
                        config.window.toLong(),
                        config.channels.size.toLong()
                    )
                )
                val logits = trainer.evaluate(NDList(input))[0].toFloatArray()
                for (i in logits.indices) {
                    // The classifier is trained with 1 = real, so the cheat probability is the
                    // complement.
                    result[index + i] = 1f - sigmoid(logits[i])
                }
                index = end
            }

            return result
        }

        override fun close() {
            trainer.close()
            model.close()
            manager.close()
        }
    }

    /**
     * Trains a classifier to separate [real] from [fake] windows. Returns `null` when there is not
     * enough of either class for the answer to mean anything.
     *
     * The classes are balanced deliberately: on an unbalanced set a classifier can score well by
     * leaning on the prior instead of on the signal, which reads as a detection that is not there.
     */
    @Suppress("LongMethod")
    fun train(
        real: List<FloatArray>,
        fake: List<FloatArray>,
        config: Config,
        seed: Long
    ): Scorer? {
        val perClass = minOf(real.size, fake.size)
        if (perClass < MIN_WINDOWS_PER_CLASS) {
            return null
        }

        val random = Random(seed)
        val samples = (real.shuffled(random).take(perClass).map { it to 1f } +
            fake.shuffled(random).take(perClass).map { it to 0f }).shuffled(random)

        val scale = scaleOf(samples.map { it.first }, config.channels.size)
        val manager = NDManager.newBaseManager()

        return runCatching {
            // Kotlin's shuffle seed does not seed DJL's Xavier initializer. Without this, two eval
            // runs with the advertised same seed trained different adversaries and could move the
            // verdict across a threshold for no change in the clone.
            Engine.getInstance().setRandomSeed(seed.toInt())
            val block = SequentialBlock()
                .add(
                    GRU.builder()
                        .setStateSize(config.hidden)
                        .setNumLayers(1)
                        .optBatchFirst(true)
                        .build()
                )
                // Mean over time: the verdict should follow the character of the whole window, not
                // whichever single tick happened to land last.
                .add { list -> NDList(list.singletonOrThrow().mean(intArrayOf(1))) }
                .add(Linear.builder().setUnits(1).build())

            val model = Model.newInstance("window-classifier")
            model.block = block

            val trainingConfig = DefaultTrainingConfig(Loss.sigmoidBinaryCrossEntropyLoss())
                .optInitializer(XavierInitializer(), "weight")
                .optOptimizer(
                    Adam.builder()
                        .optLearningRateTracker(Tracker.fixed(LEARNING_RATE))
                        .build()
                )

            val trainer = model.newTrainer(trainingConfig)
            trainer.initialize(
                Shape(BATCH.toLong(), config.window.toLong(), config.channels.size.toLong())
            )

            val dataset = datasetOf(manager, samples, scale, config)
            repeat(config.epochs) {
                for (batch in trainer.iterateDataset(dataset)) {
                    trainer.newGradientCollector().use { collector ->
                        val predictions = trainer.forward(batch.data, batch.labels)
                        collector.backward(trainer.loss.evaluate(batch.labels, predictions))
                    }
                    trainer.step()
                    batch.close()
                }
            }

            Scorer(manager, model, trainer, scale, config)
        }.onFailure { manager.close() }.getOrThrow()
    }

    /**
     * Per-channel scale shared by both classes. Scaling each class on its own would erase any
     * difference in magnitude, which is one of the things worth catching.
     */
    private fun scaleOf(windows: List<FloatArray>, channels: Int): FloatArray {
        val scale = FloatArray(channels) { 1f }

        for (channel in 0 until channels) {
            var sum = 0.0
            var count = 0
            for (window in windows) {
                var i = channel
                while (i < window.size) {
                    sum += window[i] * window[i]
                    count++
                    i += channels
                }
            }
            if (count > 0) {
                scale[channel] = Math.sqrt(sum / count).toFloat().coerceAtLeast(1e-3f)
            }
        }
        return scale
    }

    private fun datasetOf(
        manager: NDManager,
        samples: List<Pair<FloatArray, Float>>,
        scale: FloatArray,
        config: Config
    ): ArrayDataset {
        val channels = config.channels.size
        val data = FloatArray(samples.size * config.window * channels)
        val labels = FloatArray(samples.size)

        for ((index, sample) in samples.withIndex()) {
            val (window, label) = sample
            for (i in window.indices) {
                data[index * window.size + i] = window[i] / scale[i % channels]
            }
            labels[index] = label
        }

        return ArrayDataset.Builder()
            .setData(
                manager.create(
                    data,
                    Shape(samples.size.toLong(), config.window.toLong(), channels.toLong())
                )
            )
            .optLabels(manager.create(labels, Shape(samples.size.toLong(), 1)))
            .setSampling(BATCH, true)
            .build()
    }

    /**
     * Area under the ROC curve — the probability that a random positive scores above a random
     * negative, with ties counting as half.
     *
     * Tied scores get the average of the ranks they span. Without that a saturated classifier —
     * which is what `1 - sigmoid` produces the moment a logit passes about 17, and which a
     * memorising net reaches easily — hands every window a bit-identical score, and positional
     * ranks then report whichever class the caller happened to append last as perfectly
     * separated. A constant classifier scored 1.000 before this.
     *
     * The result is NOT folded into `[0.5, 1]`. The label direction is fixed here, not arbitrary:
     * the classifier is trained with real as the positive class and its score is complemented into
     * P(fake), so an honest pipeline yields at least 0.5 by construction. A value below 0.5 is
     * evidence of a wiring bug and has to stay visible, and folding also drags the expected value
     * for a genuinely indistinguishable clone above 0.5 on small samples.
     */
    fun auc(scores: List<Pair<Float, Float>>): Float {
        val positives = scores.count { it.second > 0.5f }
        val negatives = scores.size - positives
        if (positives == 0 || negatives == 0) {
            return Float.NaN
        }

        val sorted = scores.sortedBy { it.first }
        var rankSum = 0.0
        var index = 0

        while (index < sorted.size) {
            var end = index
            while (end + 1 < sorted.size && sorted[end + 1].first == sorted[index].first) {
                end++
            }

            // Ranks are 1-based; every member of a tied block takes the block's midpoint.
            val midRank = (index + end + 2) / 2.0
            for (i in index..end) {
                if (sorted[i].second > 0.5f) {
                    rankSum += midRank
                }
            }
            index = end + 1
        }

        return ((rankSum - positives * (positives + 1) / 2.0) /
            (positives.toDouble() * negatives)).toFloat()
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
}
