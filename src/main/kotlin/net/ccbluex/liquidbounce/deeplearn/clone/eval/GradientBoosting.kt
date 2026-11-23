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

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Gradient-boosted decision trees for binary classification, in the Newton formulation.
 *
 * Written by hand rather than pulled in as a dependency: it is a few hundred lines, it has to run
 * inside the client, and the alternative would be a library an order of magnitude larger than the
 * thing it classifies.
 *
 * It exists because a sequence model and a tree ensemble fail to notice *different* things. A GRU
 * reads the shape of a trajectory; a tree carves the space of summary statistics into boxes and
 * finds sharp thresholds. If one summary of the clone's behaviour sits in a narrow band no human
 * occupies, a tree splits on it immediately and a recurrent net may never notice. Detection
 * systems for this kind of data are built on tree ensembles for exactly that reason, so testing
 * only against a neural discriminator leaves the more likely adversary unmodelled.
 */
class GradientBoosting(
    private val rounds: Int = 120,
    private val depth: Int = 3,
    private val learningRate: Float = 0.1f,
    private val minSamplesLeaf: Int = 8,
    private val lambda: Float = 1f
) {

    private var baseScore = 0f
    private val trees = mutableListOf<Tree>()

    /** Total split gain attributed to each feature — what actually gave the clone away. */
    var importance: FloatArray = FloatArray(0)
        private set

    private class Node(
        val feature: Int = -1,
        val threshold: Float = 0f,
        val value: Float = 0f,
        val left: Node? = null,
        val right: Node? = null
    ) {
        val isLeaf get() = left == null

        fun predict(row: FloatArray): Float =
            if (isLeaf) value else if (row[feature] <= threshold) left!!.predict(row) else right!!.predict(row)
    }

    private class Tree(val root: Node)

    fun fit(features: Array<FloatArray>, labels: FloatArray) {
        require(features.isNotEmpty()) { "No samples" }
        require(features.size == labels.size) { "Features and labels differ in length" }

        val columns = features[0].size
        importance = FloatArray(columns)

        val positives = labels.count { it > 0.5f }.toFloat()
        val ratio = (positives / labels.size).coerceIn(1e-3f, 1f - 1e-3f)
        baseScore = ln(ratio / (1f - ratio))

        val scores = FloatArray(features.size) { baseScore }
        val gradients = FloatArray(features.size)
        val hessians = FloatArray(features.size)
        val indices = IntArray(features.size) { it }

        repeat(rounds) {
            for (i in features.indices) {
                val p = sigmoid(scores[i])
                // Negative gradient and hessian of the logistic loss.
                gradients[i] = labels[i] - p
                hessians[i] = max(p * (1f - p), 1e-6f)
            }

            val root = build(features, gradients, hessians, indices, 0, indices.size, 0)
            trees.add(Tree(root))

            for (i in features.indices) {
                scores[i] += learningRate * root.predict(features[i])
            }
        }
    }

    fun predict(row: FloatArray): Float {
        var score = baseScore
        for (tree in trees) {
            score += learningRate * tree.root.predict(row)
        }
        return sigmoid(score)
    }

    /**
     * Grows one node over `indices[from until to]`, partitioning that slice in place so the two
     * children own contiguous halves of it.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private fun build(
        features: Array<FloatArray>,
        gradients: FloatArray,
        hessians: FloatArray,
        indices: IntArray,
        from: Int,
        to: Int,
        level: Int
    ): Node {
        val count = to - from
        var gradientSum = 0f
        var hessianSum = 0f
        for (i in from until to) {
            gradientSum += gradients[indices[i]]
            hessianSum += hessians[indices[i]]
        }

        val leaf = Node(value = gradientSum / (hessianSum + lambda))
        if (level >= depth || count < 2 * minSamplesLeaf) {
            return leaf
        }

        val parentScore = score(gradientSum, hessianSum)
        var bestGain = 0f
        var bestFeature = -1
        var bestThreshold = 0f

        val order = IntArray(count) { indices[from + it] }

        for (feature in features[0].indices) {
            order.sortedBy { features[it][feature] }.let { sorted ->
                var leftGradient = 0f
                var leftHessian = 0f

                for (i in 0 until count - 1) {
                    leftGradient += gradients[sorted[i]]
                    leftHessian += hessians[sorted[i]]

                    if (i + 1 < minSamplesLeaf || count - i - 1 < minSamplesLeaf) {
                        continue
                    }

                    val current = features[sorted[i]][feature]
                    val next = features[sorted[i + 1]][feature]
                    if (current == next) {
                        continue
                    }

                    val gain = score(leftGradient, leftHessian) +
                        score(gradientSum - leftGradient, hessianSum - leftHessian) -
                        parentScore

                    if (gain > bestGain) {
                        bestGain = gain
                        bestFeature = feature
                        bestThreshold = (current + next) / 2f
                    }
                }
            }
        }

        if (bestFeature < 0) {
            return leaf
        }

        importance[bestFeature] += bestGain

        // Partition in place: everything at or below the threshold moves to the front of the slice.
        var split = from
        for (i in from until to) {
            if (features[indices[i]][bestFeature] <= bestThreshold) {
                val swap = indices[i]
                indices[i] = indices[split]
                indices[split] = swap
                split++
            }
        }

        if (split == from || split == to) {
            return leaf
        }

        return Node(
            feature = bestFeature,
            threshold = bestThreshold,
            left = build(features, gradients, hessians, indices, from, split, level + 1),
            right = build(features, gradients, hessians, indices, split, to, level + 1)
        )
    }

    private fun score(gradient: Float, hessian: Float) = gradient * gradient / (hessian + lambda)

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
}
