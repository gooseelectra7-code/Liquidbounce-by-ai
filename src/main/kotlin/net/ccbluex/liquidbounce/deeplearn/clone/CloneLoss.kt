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
import ai.djl.nn.Activation
import ai.djl.training.loss.Loss
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_ATTACK
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_ATTACK_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_ATTACK_DY
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.K
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_ATTACKED
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_MOVE_STATE
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.LOG_SIGMA_MAX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.LOG_SIGMA_MIN
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_PREV_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_PREV_DY
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_LOGITS
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_LOG_SIGMA_X
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_LOG_SIGMA_Y
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_MU_X
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_MU_Y
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_RHO
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MOVE_STATE
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.MOVE_STATES

/**
 * Negative log-likelihood of a joint hurdle model: a four-way exact-zero state plus a correlated
 * bivariate Gaussian mixture over the non-zero movement.
 *
 * Training a regression on the mouse deltas with a squared error — as the previous system did —
 * fits the *conditional mean*, which is the smoothest curve consistent with the data and therefore
 * the least human one. A likelihood over the whole distribution is what lets the model reproduce
 * the shape of the player's noise instead of averaging it away.
 *
 * Labels are `[dx, dy, oneHot(still/yaw/pitch/both), attacked]`. The result is the weighted mean
 * over ticks, so a weight of zero drops a warmup or sanitised tick.
 * Predictions: `[(B, T, HEADS) heads, state]` — the state is ignored here.
 */
class CloneLoss(
    /**
     * Weight on attack ticks relative to quiet ones.
     *
     * Attacks are a small minority of all ticks — a profile is mostly walking around — and an
     * unweighted cross entropy on a 2% class settles near "never swings", which is exactly what a
     * clone trained without this does. Set from the observed attack rate so the two classes carry
     * comparable gradient.
     */
    private val attackWeight: Float = 1f,

    /**
     * v7 step-memory lambda: the mixture is scored on `innovation = dx - lambda*prevDx`, so the head
     * predicts what to add on top of `lambda*prevStep`. lambda IS the sampled step's lag-1
     * autocorrelation (an AR(1) step), i.e. the momentum — 1 is the undamped integrator that
     * spins, 0 is the old absolute-step model. Must equal the value inference reconstructs with.
     */
    private val stepMemory: Float = 1f
) : Loss("CloneLoss") {

    override fun evaluate(labels: NDList, predictions: NDList): NDArray {
        val heads = predictions[0]
        val targets = labels[0]
        val weight = labels[1].squeeze(-1)

        val movementState = targets.get(NDIndex(":, :, $L_MOVE_STATE:${L_MOVE_STATE + MOVE_STATES}"))
        val movementLoss = heads
            .get(NDIndex(":, :, $H_MOVE_STATE:${H_MOVE_STATE + MOVE_STATES}"))
            .logSoftmax(-1)
            .mul(movementState)
            .sum(intArrayOf(-1))
            .neg()

        // The attack head is scored on every tick, including the ones where the axes did not move:
        // "held still and did not swing" is as much a part of the click pattern as swinging is.
        //
        // Class-weighted cross entropy from logits, in the form that stays stable for large |z|:
        //   w * t * softplus(-z) + (1 - t) * softplus(z)
        val attackLogit = heads.get(NDIndex(":, :, $H_ATTACK"))
            .add(
                heads.get(NDIndex(":, :, $H_ATTACK_DX"))
                    .clip(-MAX_ATTACK_ACTION_COEFFICIENT, MAX_ATTACK_ACTION_COEFFICIENT)
                    .mul(targets.get(NDIndex(":, :, $L_DX")))
            )
            .add(
                heads.get(NDIndex(":, :, $H_ATTACK_DY"))
                    .clip(-MAX_ATTACK_ACTION_COEFFICIENT, MAX_ATTACK_ACTION_COEFFICIENT)
                    .mul(targets.get(NDIndex(":, :, ${L_DX + 1}")))
            )
        val attacked = targets.get(NDIndex(":, :, $L_ATTACKED"))
        val attackLoss = Activation.softPlus(attackLogit.neg())
            .mul(attacked)
            .mul(attackWeight)
            .add(Activation.softPlus(attackLogit).mul(attacked.neg().add(1f)))

        // v7: the mixture is scored on the DAMPED innovation `dx - lambda*prevDx`, so the head
        // predicts what to add on top of `lambda*prevStep`; inference reconstructs
        // `step = lambda*prevStep + sample`. lambda < 1 makes the step an AR(1) with lag-1
        // autocorrelation lambda (the momentum) — lambda = 1 is the undamped integrator that spun.
        // `prevDx` is the clean prior step (`L_PREV_DX`), so a continuing motion is a near-zero
        // target and the sigmas learn the small innovation spread, not the full step spread.
        val innovationX = targets.get(NDIndex(":, :, $L_DX"))
            .sub(targets.get(NDIndex(":, :, $L_PREV_DX")).mul(stepMemory))
        val innovationY = targets.get(NDIndex(":, :, ${L_DX + 1}"))
            .sub(targets.get(NDIndex(":, :, $L_PREV_DY")).mul(stepMemory))
        val mixtureLoss = mixtureNll(heads, innovationX, innovationY, movementState)

        val total = movementLoss.add(attackLoss).add(mixtureLoss).mul(weight)
        return total.sum().div(weight.sum().maximum(1f))
    }

    /**
     * Per-tick negative log-likelihood of the labelled movement — the move/still choice plus the
     * mixture density of the step — unweighted, shape `(B, T)`.
     *
     * The same arithmetic [evaluate] folds into its mean, kept apart for [CloneReinforce], which
     * scores the clone's OWN sampled steps with it (the policy-gradient term and the KL to the
     * imitation model both need it per tick). [evaluate] is left untouched so imitation training
     * keeps its exact numerics.
     */
    fun movementNll(heads: NDArray, targets: NDArray): NDArray =
        stateNll(heads, targets).add(stepNll(heads, targets))

    /** Per-tick NLL of the labelled move/still choice alone, shape `(B, T)`. */
    fun stateNll(heads: NDArray, targets: NDArray): NDArray =
        stateLogProbabilities(heads)
            .mul(targets.get(NDIndex(":, :, $L_MOVE_STATE:${L_MOVE_STATE + MOVE_STATES}")))
            .sum(intArrayOf(-1))
            .neg()

    /** Log-probabilities of the four move/still states, shape `(B, T, 4)`. */
    fun stateLogProbabilities(heads: NDArray): NDArray =
        heads.get(NDIndex(":, :, $H_MOVE_STATE:${H_MOVE_STATE + MOVE_STATES}")).logSoftmax(-1)

    /** Per-tick NLL of the labelled step's size given its move/still state, shape `(B, T)`. */
    fun stepNll(heads: NDArray, targets: NDArray): NDArray {
        val movementState = targets.get(NDIndex(":, :, $L_MOVE_STATE:${L_MOVE_STATE + MOVE_STATES}"))
        val innovationX = targets.get(NDIndex(":, :, $L_DX"))
            .sub(targets.get(NDIndex(":, :, $L_PREV_DX")).mul(stepMemory))
        val innovationY = targets.get(NDIndex(":, :, ${L_DX + 1}"))
            .sub(targets.get(NDIndex(":, :, $L_PREV_DY")).mul(stepMemory))
        return mixtureNll(heads, innovationX, innovationY, movementState)
    }

    /** The click logit conditioned on the labelled step, shape `(B, T)`, built as [evaluate] builds it. */
    fun attackLogit(heads: NDArray, targets: NDArray): NDArray =
        heads.get(NDIndex(":, :, $H_ATTACK"))
            .add(
                heads.get(NDIndex(":, :, $H_ATTACK_DX"))
                    .clip(-MAX_ATTACK_ACTION_COEFFICIENT, MAX_ATTACK_ACTION_COEFFICIENT)
                    .mul(targets.get(NDIndex(":, :, $L_DX")))
            )
            .add(
                heads.get(NDIndex(":, :, $H_ATTACK_DY"))
                    .clip(-MAX_ATTACK_ACTION_COEFFICIENT, MAX_ATTACK_ACTION_COEFFICIENT)
                    .mul(targets.get(NDIndex(":, :, ${L_DX + 1}")))
            )

    /**
     * Mixture likelihood for the selected movement state. A one-axis state uses the corresponding
     * marginal; `both` uses the correlated bivariate density; `still` carries no size loss.
     */
    private fun mixtureNll(
        heads: NDArray,
        x: NDArray,
        y: NDArray,
        state: NDArray
    ): NDArray {
        val logits = heads.get(NDIndex(":, :, $H_MIX_LOGITS:${H_MIX_LOGITS + K}"))
        val muX = heads.get(NDIndex(":, :, $H_MIX_MU_X:${H_MIX_MU_X + K}"))
        val muY = heads.get(NDIndex(":, :, $H_MIX_MU_Y:${H_MIX_MU_Y + K}"))
        val logSigmaX = heads.get(
            NDIndex(":, :, $H_MIX_LOG_SIGMA_X:${H_MIX_LOG_SIGMA_X + K}")
        ).clip(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
        val logSigmaY = heads.get(
            NDIndex(":, :, $H_MIX_LOG_SIGMA_Y:${H_MIX_LOG_SIGMA_Y + K}")
        ).clip(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
        val rho = Activation.tanh(
            heads.get(NDIndex(":, :, $H_MIX_RHO:${H_MIX_RHO + K}"))
        ).clip(-MAX_CORRELATION, MAX_CORRELATION)

        val zx = x.expandDims(-1).sub(muX).div(logSigmaX.exp())
        val zy = y.expandDims(-1).sub(muY).div(logSigmaY.exp())
        val logWeights = logits.logSoftmax(-1)

        val yawLogProb = logSigmaX.neg()
            .sub(HALF_LOG_TWO_PI)
            .sub(zx.pow(2).mul(0.5f))
        val pitchLogProb = logSigmaY.neg()
            .sub(HALF_LOG_TWO_PI)
            .sub(zy.pow(2).mul(0.5f))

        val oneMinusRho2 = rho.pow(2).neg().add(1f)
        val quadratic = zx.pow(2)
            .sub(zx.mul(zy).mul(rho).mul(2f))
            .add(zy.pow(2))
            .div(oneMinusRho2.mul(2f))
        val bothLogProb = logSigmaX.neg()
            .sub(logSigmaY)
            .sub(LOG_TWO_PI)
            .sub(oneMinusRho2.log().mul(0.5f))
            .sub(quadratic)

        val yawNll = mixtureLogSumExp(logWeights.add(yawLogProb))
        val pitchNll = mixtureLogSumExp(logWeights.add(pitchLogProb))
        val bothNll = mixtureLogSumExp(logWeights.add(bothLogProb))

        return yawNll.mul(state.get(NDIndex(":, :, 1")))
            .add(pitchNll.mul(state.get(NDIndex(":, :, 2"))))
            .add(bothNll.mul(state.get(NDIndex(":, :, 3"))))
    }

    /** Negative log-sum-exp over the mixture-component axis. */
    private fun mixtureLogSumExp(weighted: NDArray): NDArray {
        val maximum = weighted.max(intArrayOf(-1), true)
        val logSumExp = weighted.sub(maximum).exp().sum(intArrayOf(-1), true).log().add(maximum)
        return logSumExp.squeeze(-1).neg()
    }

    companion object {
        private val HALF_LOG_TWO_PI = (0.5 * Math.log(2.0 * Math.PI)).toFloat()
        private val LOG_TWO_PI = Math.log(2.0 * Math.PI).toFloat()
        private const val MAX_CORRELATION = 0.95f
        private const val MAX_ATTACK_ACTION_COEFFICIENT = 3f

        /** Weight used by [selfTest], chosen so the weighting path is actually exercised. */
        private const val SELF_TEST_WEIGHT = 2f

        /**
         * Turns an observed attack rate into a class weight. Balanced weighting is `(1-p)/p`;
         * the cap keeps a profile that barely fought from producing a weight so large that the
         * few attack ticks it does contain drown out everything else.
         */
        fun weightForRate(rate: Float): Float {
            if (rate <= 0f || rate >= 1f) {
                return 1f
            }
            return ((1f - rate) / rate).coerceIn(1f, MAX_ATTACK_WEIGHT)
        }

        private const val MAX_ATTACK_WEIGHT = 25f

        /**
         * Loss of the case built by [selfTest], worked out by hand.
         *
         * The scored tick has every logit at zero, a pitch-only movement state (drawn from three
         * identical unit Gaussian marginals centred on it), and an attack scored at
         * [SELF_TEST_WEIGHT]:
         *
         *   move state = -log(1/4)                        = 2 ln2         = 1.3862944
         *   attack     = 2 * softplus(0)                  = 2 ln2         = 1.3862944
         *   mixture    = 0.5 ln(2 pi)   (pitch axis only)                 = 0.9189385
         * The first tick is masked out entirely.
         */
        const val SELF_TEST_EXPECTED = 3.6915273f

        const val SELF_TEST_TOLERANCE = 1e-4f

        /**
         * Runs the loss over a two-tick batch whose answer is known in closed form.
         *
         * Worth the few milliseconds before every training run: the likely faults here are a
         * flipped sign in the cross entropy, a slice that reads the wrong head, or a warmup mask
         * that does not actually mask. None of those crash — they just train a model that is
         * quietly wrong, and the first sign would be a clone that behaves oddly hours later. The
         * unscored tick is deliberately filled with extreme values so a broken mask blows the
         * number up instead of nudging it.
         */
        fun selfTest(): Float {
            val heads = FloatArray(2 * FeatureSchema.HEADS)
            val labels = FloatArray(2 * FeatureSchema.LABELS)
            // Masked tick: absurd labels that must not reach the result.
            labels[0] = 50f
            labels[1] = 50f
            labels[L_MOVE_STATE + 3] = 1f
            // Scored tick: pitch-only movement at the mean, and an attack.
            val scored = FeatureSchema.LABELS
            labels[scored + L_MOVE_STATE + 2] = 1f
            labels[scored + L_ATTACKED] = 1f
            val mask = floatArrayOf(0f, 1f)

            NDManager.newBaseManager().use { manager ->
                val predictions = NDList(
                    manager.create(heads, Shape(1, 2, FeatureSchema.HEADS.toLong()))
                )
                val labelList = NDList(
                    manager.create(labels, Shape(1, 2, FeatureSchema.LABELS.toLong())),
                    manager.create(mask, Shape(1, 2, 1))
                )

                return CloneLoss(SELF_TEST_WEIGHT).evaluate(labelList, predictions).getFloat()
            }
        }
    }
}
