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

import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.types.DataType
import ai.djl.ndarray.types.Shape
import ai.djl.nn.AbstractBlock
import ai.djl.nn.Activation
import ai.djl.nn.core.Linear
import ai.djl.nn.recurrent.GRU
import ai.djl.training.ParameterStore
import ai.djl.util.PairList

/**
 * Input projection into a GRU into the distribution heads.
 *
 * This is deliberately not a [ai.djl.nn.SequentialBlock]: that pipes the whole `NDList` through
 * every child, which would feed the recurrent hidden state into the projection layer. Routing the
 * inputs by hand is what lets the caller carry the state across ticks — which is the entire point
 * of the architecture, since the hidden state is the model's memory of the movement it is in the
 * middle of.
 *
 * Input: `[features(B, T, FEATURES)]`, optionally `[features, state(layers, B, hidden)]`.
 * Output: `[heads(B, T, HEADS), state(layers, B, hidden)]`.
 */
class CloneNet(
    private val hidden: Int,
    private val layers: Int
) : AbstractBlock(VERSION) {

    private val projection = addChildBlock(
        "projection",
        Linear.builder().setUnits(hidden.toLong()).build()
    )

    private val gru = addChildBlock(
        "gru",
        GRU.builder()
            .setStateSize(hidden)
            .setNumLayers(layers)
            .optBatchFirst(true)
            .optReturnState(true)
            .build()
    )

    private val heads = addChildBlock(
        "heads",
        Linear.builder().setUnits(FeatureSchema.HEADS.toLong()).build()
    )

    override fun forwardInternal(
        parameterStore: ParameterStore,
        inputs: NDList,
        training: Boolean,
        params: PairList<String, Any>?
    ): NDList {
        val projected = Activation.relu(
            projection.forward(parameterStore, NDList(inputs[0]), training).singletonOrThrow()
        )

        // GRU appends a zero state to the list it is handed when none is given, so it always gets
        // a fresh one rather than the caller's.
        val gruInput = if (inputs.size > 1) NDList(projected, inputs[1]) else NDList(projected)
        val gruOutput = gru.forward(parameterStore, gruInput, training)

        val out = heads.forward(parameterStore, NDList(gruOutput[0]), training).singletonOrThrow()
        return NDList(out, gruOutput[1])
    }

    override fun getOutputShapes(inputShapes: Array<Shape>): Array<Shape> {
        val input = inputShapes[0]
        return arrayOf(
            Shape(input.get(0), input.get(1), FeatureSchema.HEADS.toLong()),
            Shape(layers.toLong(), input.get(0), hidden.toLong())
        )
    }

    override fun initializeChildBlocks(
        manager: NDManager,
        dataType: DataType,
        vararg inputShapes: Shape
    ) {
        val input = inputShapes[0]
        val hiddenShape = Shape(input.get(0), input.get(1), hidden.toLong())

        projection.initialize(manager, dataType, input)
        gru.initialize(manager, dataType, hiddenShape)
        heads.initialize(manager, dataType, hiddenShape)
    }

    companion object {
        private const val VERSION: Byte = 2

        /**
         * Trainable parameters for a given size, without building the net.
         *
         * A GRU carries three gates, each with an input and a recurrent matrix plus two biases,
         * so the recurrent block alone grows as `6 * hidden^2` — the dominant term by far.
         */
        fun parameterCount(hidden: Int, layers: Int): Int {
            val projection = FeatureSchema.FEATURES * hidden + hidden
            val recurrent = layers * (6 * hidden * hidden + 6 * hidden)
            val heads = hidden * FeatureSchema.HEADS + FeatureSchema.HEADS
            return projection + recurrent + heads
        }

        /**
         * The largest size worth training on a given number of ticks.
         *
         * Was one parameter per ten ticks until 2026-09-29, and that was measured wrong: on 84k
         * training ticks 48 units (5 ticks per parameter) beat 32 on every in-game metric, and 64
         * (3 per parameter) still lowered the held-out loss. The held-out loss is what stops
         * following the training loss once a net memorises the recording, so three ticks per
         * parameter is where this now warns.
         */
        fun suggestedHidden(ticks: Int): Int {
            val budget = ticks / SAMPLES_PER_PARAMETER
            return SIZES.lastOrNull { parameterCount(it, 1) <= budget } ?: SIZES.first()
        }

        private const val SAMPLES_PER_PARAMETER = 3
        private val SIZES = intArrayOf(16, 24, 32, 48, 64, 96, 128, 192, 256)
    }
}
