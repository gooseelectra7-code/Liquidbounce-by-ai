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

import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * Puts the attack head's probabilities back on the scale the player actually clicked at.
 *
 * The head is trained under a class weight `w` on the positive term, because swings are a couple of
 * percent of all ticks and an unweighted cross entropy settles on "never swings". That weight moves
 * where the objective is minimised: `w·p·softplus(−z) + (1−p)·softplus(z)` is smallest at
 * `z = logit(p) + ln(w)`, so every probability the head reports is high by `ln(w)`, and a clone
 * that samples straight off it swings far more often than the person it was cloned from.
 *
 * Subtracting `ln(w)` back off would answer exactly that one term. Solving for the shift against
 * held-out data instead answers it *and* whatever else the run left behind — the kept epoch here is
 * routinely the twelfth of forty, and a head stopped before it converged is not at the weighted
 * optimum either, so the closed form would undercorrect by an amount nothing measures.
 */
object CloneCalibration {

    /**
     * What a calibration pass found.
     *
     * [rateBefore] against [observedRate] is the size of the problem: the first is how often the
     * head would have swung on the held-out ticks, the second how often the player did.
     */
    class Fit(
        val bias: Float,
        val rateBefore: Float,
        val observedRate: Float,
        val ticks: Int
    ) {
        /** False when there was nothing to solve against and [bias] is only the closed form. */
        val isFitted: Boolean get() = ticks > 0

        /** Folds the fit into [metadata], which the caller still has to save. */
        fun applyTo(metadata: ModelMetadata) {
            metadata.attackBias = bias
            metadata.attackBiasRate = rateBefore.takeIf { isFitted }
            metadata.attackBiasTarget = observedRate.takeIf { isFitted }
        }

        fun describe(): String = if (!isFitted) {
            ("No held-out ticks to calibrate the attack head against — biasing it by %+.2f, which " +
                "is only what the class weight accounts for.").format(bias)
        } else {
            ("Calibrated the attack head on %d held-out ticks: bias %+.2f, taking it from swinging " +
                "on %.2f%% of them to the %.2f%% they hold.")
                .format(ticks, bias, rateBefore * 100f, observedRate * 100f)
        }
    }

    /**
     * Fits the bias for the model in [folder] over [holdout] and writes it into the metadata,
     * returning `null` only if the model itself cannot be read.
     *
     * Both `.clone train` and `.clone recalibrate` come through here rather than each fitting their
     * own: a calibration that differed by which command produced it would be invisible from the
     * outside and wrong in exactly the way this whole file exists to prevent.
     */
    fun calibrate(folder: File, holdout: List<AimSequence>): Fit? {
        val fit = CloneModel.load(folder)?.use { fit(it, holdout) } ?: return null
        store(folder, fit)
        return fit
    }

    /**
     * Solves for the bias that makes [model]'s mean attack probability over [holdout] equal the
     * rate those same ticks actually carried.
     *
     * The logits come off the model raw, so this measures the head rather than the correction
     * already sitting in front of it and can be run over and over on the same model.
     */
    private fun fit(model: CloneModel, holdout: List<AimSequence>): Fit {
        val fallback = closedForm(model.metadata.attackRate)

        val blocks = mutableListOf<FloatArray>()
        var attacks = 0
        var ticks = 0

        for (sequence in holdout) {
            val tensor = SequenceTensor(sequence)
            blocks.add(model.attackLogits(tensor.featureRows, tensor.actionRows, tensor.rowCount))

            for (i in 0 until tensor.rowCount) {
                attacks += tensor.attackRows[i].toInt()
            }
            ticks += tensor.rowCount
        }

        if (ticks == 0) {
            return Fit(fallback, Float.NaN, Float.NaN, 0)
        }

        val logits = FloatArray(ticks)
        var offset = 0
        for (block in blocks) {
            block.copyInto(logits, offset)
            offset += block.size
        }

        val observed = attacks.toFloat() / ticks
        return Fit(solve(logits, observed, fallback), meanRate(logits, 0f), observed, ticks)
    }

    /** The shift the class weight alone accounts for, and the starting point for [solve]. */
    private fun closedForm(attackRate: Float): Float = -ln(CloneLoss.weightForRate(attackRate))

    /**
     * The `b` for which `mean(sigmoid(z + b))` over [logits] is [observedRate].
     *
     * That mean rises strictly with `b` from 0 to 1, so a bisection is all this needs and cannot
     * land on a second answer. Falls back to [start] where the bracket does not contain one, which
     * takes a rate of exactly zero or logits far outside anything the head produces.
     */
    private fun solve(logits: FloatArray, observedRate: Float, start: Float): Float {
        var low = start - BRACKET
        var high = start + BRACKET

        if (meanRate(logits, low) >= observedRate || meanRate(logits, high) <= observedRate) {
            return start
        }

        repeat(ITERATIONS) {
            val middle = (low + high) / 2f
            if (meanRate(logits, middle) < observedRate) {
                low = middle
            } else {
                high = middle
            }
        }

        return (low + high) / 2f
    }

    private fun meanRate(logits: FloatArray, bias: Float): Float {
        var total = 0.0
        for (logit in logits) {
            total += 1.0 / (1.0 + exp(-(logit + bias).toDouble()))
        }
        return (total / logits.size).toFloat()
    }

    /** Writes the fit into the model's `metadata.json` through [ModelMetadata.patch]. */
    private fun store(folder: File, fit: Fit) {
        ModelMetadata.patch(folder) { root ->
            root.addProperty(FIELD_BIAS, fit.bias)
            if (fit.isFitted) {
                root.addProperty(FIELD_RATE, fit.rateBefore)
                root.addProperty(FIELD_TARGET, fit.observedRate)
            } else {
                // Gson cannot write a NaN, and their absence is what tells a reader that the bias
                // beside them is the closed form rather than something that was measured.
                root.remove(FIELD_RATE)
                root.remove(FIELD_TARGET)
            }
        }
    }

    private const val FIELD_BIAS = "attackBias"
    private const val FIELD_RATE = "attackBiasRate"
    private const val FIELD_TARGET = "attackBiasTarget"

    /** How far either side of the closed form the answer is looked for, in logits. */
    private const val BRACKET = 40f

    private const val ITERATIONS = 48
}
