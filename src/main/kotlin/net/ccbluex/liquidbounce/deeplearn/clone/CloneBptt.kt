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
package net.ccbluex.liquidbounce.deeplearn.clone

import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.index.NDIndex
import ai.djl.ndarray.types.Shape
import ai.djl.training.Trainer
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.FEATURES
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_PITCH
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_YAW
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_HAS_TARGET
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_PREV_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_PREV_DY
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.LABELS
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_ATTACKED
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_MOVE_STATE
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_PREV_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_PREV_DY
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.MOVE_STATES
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.WARMUP
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Feeds the trainer whole recordings as continuous streams instead of independent windows.
 *
 * The model carries its hidden state for as long as a fight lasts — minutes — while independent
 * 64-tick windows only ever taught it what a state built from at most 64 ticks means. Everything it
 * knew about its own memory came from three seconds of context, and the deeper the state got in
 * play the further it was from anything training had shown it.
 *
 * Here a recording is cut into consecutive [chunk]-tick pieces that are fed in order, with the
 * hidden state carried from one to the next and only the gradient truncated at the boundary. The
 * state the model learns to interpret is therefore the same one it accumulates in play, and the
 * boundaries move no information: [Chunk.keepState] is the only thing that ever clears it.
 *
 * [lanes] such streams run side by side as the batch dimension — lane `l` is always at batch index
 * `l`, which is what makes one carried state tensor correct for all of them at once. Recordings are
 * spread over the lanes and long ones are split, so a profile of three long fights parallelises as
 * well as one of three hundred short ones.
 *
 * Nothing is materialised up front: the old window tensor was the reason for a 40 000 window cap
 * and for the wider stride on large profiles, and it is simply gone — a chunk is built into reused
 * scratch and copied into the engine, so a profile now costs its own size and nothing more.
 */
class CloneBptt(
    private val tensors: List<SequenceTensor>,
    private val marks: CloneDisengagement.Marks,
    private val normalization: FeatureSchema.Normalization,
    private val weighting: CloneTickWeights,
    requestedLanes: Int,
    val chunk: Int,
    /** Spread of [perturbPreviousActions], in standardized units. */
    private val actionNoise: Float = ACTION_NOISE
) {

    /**
     * A contiguous run of ticks inside one tensor, which one lane plays start to finish.
     *
     * Recordings are already independent, so a span boundary is a genuine discontinuity; a split
     * *inside* a recording is not, and costs one extra cold start per epoch — paid to keep the
     * batch full when a profile holds fewer recordings than there are lanes.
     */
    private class Span(val tensor: Int, val from: Int, val until: Int) {
        val length get() = until - from
    }

    private val spans: List<Span> = buildSpans(requestedLanes)

    /** How many streams run in parallel; never more than there is data to fill them with. */
    val lanes: Int = min(requestedLanes, spans.size).coerceAtLeast(1)

    /**
     * Chunks in one pass, if the lanes were loaded perfectly evenly. The assignment is balanced by
     * length so the real count lands within a chunk or two of this, which is close enough for the
     * learning-rate horizon and for a progress bar.
     */
    val stepsPerEpoch: Int = if (spans.isEmpty()) {
        0
    } else {
        val total = spans.sumOf { (it.length + chunk - 1) / chunk }
        ((total + lanes - 1) / lanes).coerceAtLeast(1)
    }

    val scoredTicks: Long = tensors.indices.sumOf { index ->
        marks.perTick[index].count { it > 0f }.toLong()
    }

    /**
     * One training step: [lanes] parallel chunks, padded where a lane has run short.
     *
     * The arrays are the iterator's own scratch and are refilled on every [Iterator.next], so a
     * consumer has to copy what it needs before asking for the next one — which the engine does
     * anyway when it builds a tensor out of them.
     */
    class Chunk(lanes: Int, chunk: Int) {
        val features = FloatArray(lanes * chunk * FEATURES)
        val labels = FloatArray(lanes * chunk * LABELS)
        val weight = FloatArray(lanes * chunk)

        /** `0` for a lane whose carried state belongs to a stream that has just ended. */
        val keepState = FloatArray(lanes)
    }

    /**
     * Splits recordings until there are enough streams to fill the lanes.
     *
     * Only spans worth splitting are split — a piece has to stay long enough that its cold start is
     * a small part of it, or the extra lane costs more in unusable warmup than it buys in
     * parallelism.
     */
    private fun buildSpans(requestedLanes: Int): List<Span> {
        val result = tensors.indices
            .filter { tensors[it].rowCount > 0 }
            .mapTo(mutableListOf()) { Span(it, 0, tensors[it].rowCount) }

        while (result.size < requestedLanes) {
            val index = result.indices.maxByOrNull { result[it].length } ?: break
            val span = result[index]
            if (span.length < MIN_SPLITTABLE * chunk) {
                break
            }

            // On a chunk boundary, so the halves keep the alignment the stream would have had.
            val cut = span.from + (span.length / 2 / chunk) * chunk
            result[index] = Span(span.tensor, span.from, cut)
            result.add(Span(span.tensor, cut, span.until))
        }

        return result
    }

    /**
     * One pass over every tick, as a stream of steps.
     *
     * [random] only decides which recordings share a batch and in what order a lane plays them —
     * the ticks inside a stream keep their order, which is the entire point.
     */
    fun epoch(random: Random): Iterator<Chunk> = EpochIterator(random)

    /**
     * Runs one pass through [trainer], carrying the recurrent state from chunk to chunk, and
     * returns the mean loss over the steps it took.
     *
     * `stopGradient` on the state between chunks is what makes this *truncated* BPTT: the state
     * itself passes through untouched, so what the model is optimised to interpret is the same
     * long-lived state it accumulates in a fight, while the graph never grows past one chunk.
     *
     * Lane `l` sits at batch index `l` in every step of a pass, which is the invariant that makes
     * one carried tensor correct for all of them at once; [Chunk.keepState] is the only thing that
     * ever clears a lane, and it does so exactly when that lane starts a new recording.
     */
    @Suppress("LongParameterList")
    fun run(
        trainer: Trainer,
        manager: NDManager,
        random: Random,
        training: Boolean,
        onProgress: (Float) -> Unit
    ): Float {
        val shapes = Shapes(lanes.toLong(), chunk.toLong())

        var total = 0.0
        var steps = 0
        var state: NDArray? = null

        // Outlives the per-step scratch, because the state is the one thing that has to.
        val stateManager = manager.newSubManager()

        try {
            val chunks = epoch(random)
            while (chunks.hasNext()) {
                val batch = chunks.next()

                manager.newSubManager().use { scratch ->
                    val outcome = step(trainer, scratch, batch, shapes, state, training)
                    total += outcome.loss

                    outcome.state.detach()
                    outcome.state.attach(stateManager)
                    state?.close()
                    state = outcome.state
                }

                steps++
                if (stepsPerEpoch > 0) {
                    onProgress((steps.toFloat() / stepsPerEpoch).coerceAtMost(1f))
                }
            }
        } finally {
            state?.close()
            stateManager.close()
        }

        return if (steps == 0) Float.NaN else (total / steps).toFloat()
    }

    /** One forward — and, while training, backward — over a single chunk. */
    @Suppress("LongParameterList")
    private fun step(
        trainer: Trainer,
        scratch: NDManager,
        batch: Chunk,
        shapes: Shapes,
        state: NDArray?,
        training: Boolean
    ): Outcome {
        val features = scratch.create(batch.features, shapes.features)
        if (training) {
            perturbPreviousActions(features)
        }

        val labels = NDList(
            scratch.create(batch.labels, shapes.labels),
            scratch.create(batch.weight, shapes.weight)
        )

        // The mask is the receiver so the product belongs to the scratch manager and dies with the
        // step, rather than piling up in the one holding the state across steps.
        val carried = state?.let { scratch.create(batch.keepState, shapes.keep).mul(it) }
        val input = if (carried == null) NDList(features) else NDList(features, carried)

        if (!training) {
            val output = trainer.evaluate(input)
            return Outcome(trainer.loss.evaluate(labels, output).getFloat().toDouble(), output[1].stopGradient())
        }

        var loss = 0.0
        val next = trainer.newGradientCollector().use { collector ->
            val output = trainer.forward(input)
            val value = trainer.loss.evaluate(labels, output)
            collector.backward(value)
            loss = value.getFloat().toDouble()
            output[1].stopGradient()
        }
        trainer.step()

        return Outcome(loss, next)
    }

    private class Outcome(val loss: Double, val state: NDArray)

    /** Fixed for a whole pass, because every chunk is padded to the same size. */
    private class Shapes(lanes: Long, chunk: Long) {
        val features: Shape = Shape(lanes, chunk, FEATURES.toLong())
        val labels: Shape = Shape(lanes, chunk, LABELS.toLong())
        val weight: Shape = Shape(lanes, chunk, 1)
        val keep: Shape = Shape(1, lanes, 1)
    }

    /**
     * Adds fresh Gaussian noise to the previous-action channels of one training chunk, in place —
     * and moves the residual-to-target channels by the same displacement, the other way.
     *
     * The chunk is built into scratch that dies with the step, so this never touches the tensors it
     * came from — every epoch draws new noise on the same ticks, which is what makes it an
     * augmentation rather than a memorisable offset. At inference the model is fed its own sampled
     * history, which is never exactly what a human would have done, and trained purely on ground
     * truth it would drift the first time its own step differed slightly.
     *
     * The coupled half is what makes the noise the perturbation runtime actually produces. When
     * the clone's previous step lands `n` degrees past the human's, its own yaw sits `n` further —
     * so the residual to the target reads `n` smaller, in the same tick, deterministically.
     * Perturbing the action channel alone fabricates a state no rollout visits (own aim moved, the
     * target conveniently moved with it) and teaches insensitivity to the history instead of
     * consistency with it. The label is deliberately left alone: what the player did from the true
     * state is the only behaviour that is known, and inventing a correction of exactly `n` would
     * put a formula back into the objective.
     *
     * The channels are standardized by this point, so [ACTION_NOISE] means the same fraction of the
     * column's real spread on every profile — the failure of an older version that wrote the noise
     * into the dataset once, where a column kept at its raw degree scale by `MIN_INFORMATIVE_STD`
     * got noise sized for a standardized one, and where every epoch saw the same offset on the same
     * ticks: a memorisable bias rather than an augmentation. The residual channels carry their own
     * scales, so the raw displacement is re-expressed through the two columns' standard deviations;
     * target-less rows hold sentinel zeros there, and the has-target indicator (an unstandardized
     * 0/1 column) gates the coupling off on them. The own-pitch column shifts by the same
     * displacement over ninety degrees — two orders of magnitude below its own spread — and is left
     * alone. Held-out passes do not call this; the validation loss measures the model, not its diet.
     */
    private fun perturbPreviousActions(features: NDArray) {
        val shape = features.shape
        val noise = features.manager.randomNormal(
            0f,
            actionNoise,
            Shape(shape[0], shape[1], (F_PREV_DY - F_PREV_DX + 1).toLong()),
            features.dataType
        )
        val index = NDIndex(":, :, $F_PREV_DX:${F_PREV_DY + 1}")
        features.set(index, features.get(index).add(noise))

        // raw = ε·std(prev axis); residual correction = −raw / std(residual axis), gated by the
        // has-target channel. F_DELTA_YAW/F_DELTA_PITCH are adjacent like F_PREV_DX/F_PREV_DY, so
        // one two-column slice pairs the axes up.
        val scale = features.manager.create(
            floatArrayOf(
                normalization.std[F_PREV_DX] / normalization.std[F_DELTA_YAW],
                normalization.std[F_PREV_DY] / normalization.std[F_DELTA_PITCH]
            ),
            Shape(1, 1, 2)
        )
        val hasTarget = features.get(NDIndex(":, :, $F_HAS_TARGET:${F_HAS_TARGET + 1}"))
        val correction = noise.mul(scale).mul(hasTarget)
        val residualIndex = NDIndex(":, :, $F_DELTA_YAW:${F_DELTA_PITCH + 1}")
        features.set(residualIndex, features.get(residualIndex).sub(correction))
    }

    private inner class EpochIterator(random: Random) : Iterator<Chunk> {

        private val queues = Array(lanes) { ArrayDeque<Span>() }
        private val current = arrayOfNulls<Span>(lanes)
        private val cursor = IntArray(lanes)
        private val chunkBuffer = Chunk(lanes, chunk)
        private val row = FloatArray(FEATURES)

        init {
            // Longest first onto the least-loaded lane: a lane that draws every long recording
            // would still be playing them while the others idle on padding.
            val load = LongArray(lanes)
            for (span in spans.shuffled(random).sortedByDescending { it.length }) {
                val lane = load.indices.minByOrNull { load[it] } ?: 0
                queues[lane].addLast(span)
                load[lane] += span.length.toLong()
            }

            for (lane in 0 until lanes) {
                current[lane] = queues[lane].removeFirstOrNull()
            }
        }

        override fun hasNext(): Boolean = current.any { it != null }

        override fun next(): Chunk {
            if (!hasNext()) {
                throw NoSuchElementException("Every lane has played its recordings out")
            }

            for (lane in 0 until lanes) {
                fill(lane)
            }
            return chunkBuffer
        }

        private fun fill(lane: Int) {
            val span = current[lane]
            val offset = cursor[lane]
            val length = if (span == null) 0 else min(chunk, span.length - offset)

            // A lane out of work still occupies its batch slot; zero weight keeps it out of the
            // loss, and the state it carries is never read again.
            chunkBuffer.keepState[lane] = if (offset == 0) 0f else 1f

            for (t in 0 until chunk) {
                val featureOffset = (lane * chunk + t) * FEATURES
                val labelOffset = (lane * chunk + t) * LABELS
                val weightIndex = lane * chunk + t

                if (span == null || t >= length) {
                    chunkBuffer.features.fill(0f, featureOffset, featureOffset + FEATURES)
                    chunkBuffer.labels.fill(0f, labelOffset, labelOffset + LABELS)
                    chunkBuffer.weight[weightIndex] = 0f
                    continue
                }

                val tensor = tensors[span.tensor]
                val source = span.from + offset + t
                write(tensor, source, featureOffset, labelOffset)

                // A stream that starts inside a recording starts on a state the model would have
                // had a real history for, so its first ticks are guesses from nothing and are not
                // scored. A stream that starts at tick zero is exactly the cold start the runtime
                // performs when it first sees a target, and that one is scored.
                val burnIn = span.from > 0 && offset == 0 && t < WARMUP
                chunkBuffer.weight[weightIndex] = if (burnIn) {
                    0f
                } else {
                    // Combined yaw+pitch angle, matching CloneTickWeights.countBuckets — weighting
                    // by yaw alone starved the look-down (pitch-heavy) ticks the clone is worst at.
                    val error = CloneTickWeights.errorOf(tensor.featureRows, source)
                    val distance = tensor.featureRows[source * FEATURES + FeatureSchema.F_DISTANCE]
                    weighting.weightFor(error, distance) * marks.perTick[span.tensor][source]
                }
            }

            if (span == null) {
                return
            }

            cursor[lane] += length
            if (cursor[lane] >= span.length) {
                current[lane] = queues[lane].removeFirstOrNull()
                cursor[lane] = 0
            }
        }

        /** Copies one tick of features and labels into the batch, standardizing on the way. */
        private fun write(tensor: SequenceTensor, source: Int, featureOffset: Int, labelOffset: Int) {
            tensor.featureRows.copyInto(row, 0, source * FEATURES, (source + 1) * FEATURES)
            row.copyInto(chunkBuffer.features, featureOffset)
            normalization.applyFeatures(chunkBuffer.features, featureOffset)

            val actionYaw = tensor.actionRows[source * 2]
            val actionPitch = tensor.actionRows[source * 2 + 1]

            // Exact zero is represented by the movement-state head. Mapping it to literal zero here
            // also gives the action-conditioned click head the same input inference uses after it
            // samples a still axis.
            chunkBuffer.labels[labelOffset + L_DX] = if (actionYaw == 0f) {
                0f
            } else {
                normalization.normalizeAction(actionYaw, 0)
            }
            chunkBuffer.labels[labelOffset + L_DX + 1] = if (actionPitch == 0f) {
                0f
            } else {
                normalization.normalizeAction(actionPitch, 1)
            }
            tensor.movementStateRows.copyInto(
                chunkBuffer.labels,
                labelOffset + L_MOVE_STATE,
                source * MOVE_STATES,
                (source + 1) * MOVE_STATES
            )
            chunkBuffer.labels[labelOffset + L_ATTACKED] = tensor.attackRows[source]

            // v6 reparam baseline: the previous step, normalized like the action label (0 when the
            // axis was still). Read from the raw feature row `row` (F_PREV_DX/DY are the prior
            // step in degrees) BEFORE the input copy is noise-perturbed, so the innovation the loss
            // scores is against the true prior step, not an augmented one. The loss forms
            // `innovation = L_DX - L_PREV_DX`.
            val prevYaw = row[F_PREV_DX]
            val prevPitch = row[F_PREV_DY]
            chunkBuffer.labels[labelOffset + L_PREV_DX] =
                if (prevYaw == 0f) 0f else normalization.normalizeAction(prevYaw, 0)
            chunkBuffer.labels[labelOffset + L_PREV_DY] =
                if (prevPitch == 0f) 0f else normalization.normalizeAction(prevPitch, 1)
        }
    }

    companion object {
        /** A span is only worth halving while both halves would still be this many chunks long. */
        private const val MIN_SPLITTABLE = 4

        /**
         * Standard deviation of the noise on the previous-action channels, in standardized units.
         * See [perturbPreviousActions] for why it is drawn per chunk rather than once.
         */
        const val ACTION_NOISE = 0.15f
    }
}
