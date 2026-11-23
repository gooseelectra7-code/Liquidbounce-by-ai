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

import ai.djl.Model
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.index.NDIndex
import ai.djl.ndarray.types.Shape
import ai.djl.training.ParameterStore
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.FEATURES
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_PITCH
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DELTA_YAW
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_DISTANCE
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_HAS_TARGET
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_PREV_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_PREV_DY
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_TARGET_HEIGHT
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_TARGET_VEL_PITCH
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_TARGET_VEL_YAW
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.F_TARGET_WIDTH
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.HEADS
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_ATTACK
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_ATTACK_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_ATTACK_DY
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_LOGITS
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_LOG_SIGMA_X
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_LOG_SIGMA_Y
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_MU_X
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_MU_Y
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MIX_RHO
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.H_MOVE_STATE
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.K
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.LOG_SIGMA_MAX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.LOG_SIGMA_MIN
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.MOVE_STATES
import java.io.Closeable
import java.io.File
import java.util.random.RandomGenerator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/** A trained clone whose immutable weights can serve several independent recurrent sessions. */
class CloneModel private constructor(
    val name: String,
    val metadata: ModelMetadata,
    private val model: Model,
    private val net: CloneNet,
    /** False for [wrapping]: the weights belong to a trainer, and closing this must not free them. */
    private val ownsModel: Boolean = true
) : Closeable {

    val normalization = metadata.normalization()
    private val attackBias = metadata.attackBias ?: 0f

    /**
     * Replaces [attackBias] while set - [CloneReinforce.calibrateClicks] searches the bias with it
     * without writing a metadata file per probe.
     */
    @Volatile
    internal var attackBiasOverride: Float? = null

    /** v7 step-memory lambda — see [ModelMetadata.stepMemory]. Baked at train time, applied here. */
    private val stepMemory = metadata.stepMemory
    private val parameterStore = ParameterStore(model.getNDManager(), false)

    @Volatile
    private var closed = false

    /**
     * One grid-quantized action and its click decision.
     *
     * [rawDeltaYaw]/[rawDeltaPitch] carry the sampled degrees *before* the clamp-and-quantize —
     * the quantized output is on the grid by construction, so only the raw values can say how much
     * the grid is costing (`.clone eval`'s off-grid statistic). Zero on a still axis, which is the
     * truth rather than a sentinel: the movement-state head decided no movement was sampled.
     */
    class Step(
        val deltaYaw: Float,
        val deltaPitch: Float,
        val attack: Boolean,
        val rawDeltaYaw: Float = deltaYaw,
        val rawDeltaPitch: Float = deltaPitch
    )

    /** One sampled movement: on the grid, raw, and the latent draw that produced it. */
    private class Draw(
        val yaw: Float,
        val pitch: Float,
        val rawYaw: Float,
        val rawPitch: Float,
        val zYaw: Float,
        val zPitch: Float,
        val drewNoise: Boolean
    )

    /**
     * Recurrent state belongs to a consumer, never to the shared model.
     *
     * KillAura and SpearElytraTarget can select the same weights. Sharing one hidden state made
     * their calls interleave into a sequence that never existed and let either consumer's reset
     * erase the other's movement. A session isolates both the GRU state and sampling scratch.
     */
    inner class Session internal constructor() : Closeable {
        private val stateManager: NDManager = model.getNDManager().newSubManager()
        private var state: NDArray? = null
        private val heads = FloatArray(HEADS)
        private var sessionClosed = false

        /**
         * Carried latent Gaussians for the temporal-noise AR(1). The model samples an i.i.d.
         * `z ~ N(0,1)` per axis each tick, so consecutive sampled steps share no noise — the
         * crosshair jitters instead of tracking, which is the "drunk" wobble (measured as a low
         * step autocorrelation and a high path-per-degree). A real hand's noise is correlated: its
         * micro-movements smear over ~3 ticks (lag-1 autocorrelation ≈ 0.585). When
         * `noiseCorrelation` > 0 these hold the previous tick's z and each new draw is
         * `z = ar·zPrev + sqrt(1 - ar²)·ε`, which keeps the per-tick marginal exactly N(0,1) (so
         * the step-size distribution the discriminator sees is unchanged) but couples the noise in
         * time toward the player's autocorrelation. Reset on any stream discontinuity, like [state].
         */
        private var zYawPrev = 0f
        private var zPitchPrev = 0f

        @Synchronized
        fun reset() {
            state?.close()
            state = null
            zYawPrev = 0f
            zPitchPrev = 0f
        }

        /** Runs one raw feature row and returns an action already on [gcd]'s mouse-count grid. */
        @Suppress("LongMethod")
        @Synchronized
        fun step(
            features: FloatArray,
            gcd: Double,
            temperature: Float,
            random: RandomGenerator,
            noiseCorrelation: Float = 0f,
            /**
             * Movements drawn per tick; the first that lands on the hitbox is kept, else the one
             * closest to it (see [hitboxError]). `1` is the plain sample. Offline-only for now: it is
             * a probe of how much a hit-aware objective could buy, not a deployed behaviour.
             */
            bestOf: Int = 1
        ): Step {
            require(features.size == FEATURES) { "Expected $FEATURES features, got ${features.size}" }
            require(gcd.isFinite() && gcd > 0.0) { "Invalid sensitivity GCD: $gcd" }
            check(!sessionClosed) { "Session for '$name' is closed" }
            check(features.all(Float::isFinite)) { "Clone input contains a non-finite value" }

            val normalized = features.copyOf()
            normalization.applyFeatures(normalized)
            check(normalized.all(Float::isFinite)) { "Clone normalization produced a non-finite value" }

            synchronized(this@CloneModel) {
                check(!closed) { "Model '$name' is closed" }
                model.getNDManager().newSubManager().use { scratch ->
                    val input = scratch.create(normalized, Shape(1, 1, FEATURES.toLong()))
                    val inputs = state?.let { NDList(input, it) } ?: NDList(input)
                    val output = net.forward(parameterStore, inputs, false)
                    output[0].toFloatArray().copyInto(heads)

                    val nextState = output[1]
                    nextState.detach()
                    nextState.attach(stateManager)
                    state?.close()
                    state = nextState
                }
            }
            check(heads.all(Float::isFinite)) { "Clone produced non-finite distribution heads" }

            val movementState = sampleCategorical(H_MOVE_STATE, MOVE_STATES, 1f, temperature <= 0f, random)
            // v6 reparam baseline: the previous step normalized like the action label (0 when the
            // axis was still), read from the raw feature row. The mixture below samples an
            // INNOVATION, and `step = prevStepNorm + innovation` reconstructs the absolute step —
            // so a continuing motion (`innovation ~= 0`) carries the previous step forward, which
            // is the momentum the absolute-step model lacked.
            val prevYawNorm =
                features[F_PREV_DX].let { if (it == 0f) 0f else normalization.normalizeAction(it, 0) }
            val prevPitchNorm =
                features[F_PREV_DY].let { if (it == 0f) 0f else normalization.normalizeAction(it, 1) }
            // The move/still decision is taken ONCE, before any selection: choosing by closeness to
            // the target would otherwise never keep a pause (standing still never gets closer), and
            // the share of still ticks is one of the features the adversaries separate clones on.
            val candidates = if (
                movementState != 0 && temperature > 0f && bestOf > 1 &&
                hitboxError(features, 0f, 0f).isFinite()
            ) {
                bestOf
            } else {
                1
            }
            var chosen: Draw? = null
            var chosenError = Float.POSITIVE_INFINITY
            for (i in 0 until candidates) {
                val draw = draw(movementState, prevYawNorm, prevPitchNorm, temperature, noiseCorrelation, gcd, random)
                if (candidates == 1) {
                    chosen = draw
                    break
                }
                // The first draw that hits is kept, so a natural sample that already lands on the
                // box is never replaced by a "better" one — the selection only acts on misses.
                val error = hitboxError(features, draw.yaw, draw.pitch)
                if (error <= 1f) {
                    chosen = draw
                    break
                }
                if (error < chosenError) {
                    chosenError = error
                    chosen = draw
                }
            }
            val pick = checkNotNull(chosen)
            if (pick.drewNoise) {
                zYawPrev = pick.zYaw
                zPitchPrev = pick.zPitch
            }
            val rawYaw = pick.rawYaw
            val rawPitch = pick.rawPitch
            val yaw = pick.yaw
            val pitch = pick.pitch

            val attackYaw = if (yaw == 0f) 0f else normalization.normalizeAction(yaw, 0)
            val attackPitch = if (pitch == 0f) 0f else normalization.normalizeAction(pitch, 1)
            return Step(
                yaw,
                pitch,
                sampleAttack(attackYaw, attackPitch, temperature, random),
                rawYaw,
                rawPitch
            )
        }

        /**
         * Draws one movement for [movementState] from the current heads. Pure apart from [random]:
         * the AR(1) memory in [zYawPrev] is read, and written back only by the caller for the draw
         * it keeps.
         */
        @Suppress("LongParameterList")
        private fun draw(
            movementState: Int,
            prevYawNorm: Float,
            prevPitchNorm: Float,
            temperature: Float,
            noiseCorrelation: Float,
            gcd: Double,
            random: RandomGenerator
        ): Draw {
            var normalizedYaw = 0f
            var normalizedPitch = 0f
            var z1 = 0f
            var z2 = 0f
            var drewNoise = false

            if (movementState != 0) {
                val component = sampleCategorical(
                    H_MIX_LOGITS,
                    K,
                    max(temperature, MIN_TEMPERATURE),
                    temperature <= 0f,
                    random
                )
                val meanYaw = heads[H_MIX_MU_X + component]
                val meanPitch = heads[H_MIX_MU_Y + component]

                if (temperature <= 0f) {
                    normalizedYaw = meanYaw
                    normalizedPitch = meanPitch
                } else {
                    val sigmaYaw = exp(
                        heads[H_MIX_LOG_SIGMA_X + component].coerceIn(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
                    ) * temperature
                    val sigmaPitch = exp(
                        heads[H_MIX_LOG_SIGMA_Y + component].coerceIn(LOG_SIGMA_MIN, LOG_SIGMA_MAX)
                    ) * temperature
                    val rho = tanh(heads[H_MIX_RHO + component])
                        .coerceIn(-MAX_CORRELATION, MAX_CORRELATION)
                    // AR(1) on each latent Gaussian across ticks — see [zYawPrev]. `rho` here is
                    // the model's *spatial* yaw/pitch correlation within a tick; `ar` is the new
                    // *temporal* correlation across ticks, orthogonal to it and to temperature
                    // (which only scales sigma). ar = 0 reproduces the original i.i.d. draw exactly.
                    val ar = noiseCorrelation.coerceIn(0f, MAX_NOISE_CORRELATION)
                    val innovation = sqrt(1f - ar * ar)
                    z1 = ar * zYawPrev + innovation * random.nextGaussian().toFloat()
                    z2 = ar * zPitchPrev + innovation * random.nextGaussian().toFloat()
                    drewNoise = true
                    normalizedYaw = meanYaw + sigmaYaw * z1
                    normalizedPitch = meanPitch + sigmaPitch *
                        (rho * z1 + sqrt(1f - rho * rho) * z2)
                }
            }

            // Reconstruct the absolute step from the sampled innovation before de-normalizing:
            // `stepNorm = lambda*prevStepNorm + innovation` (lambda = [stepMemory]). A still axis
            // stays exactly zero.
            val rawYaw = if (movementState == 1 || movementState == 3) {
                normalization.denormalizeAction(stepMemory * prevYawNorm + normalizedYaw, 0)
            } else {
                0f
            }
            val rawPitch = if (movementState == 2 || movementState == 3) {
                normalization.denormalizeAction(stepMemory * prevPitchNorm + normalizedPitch, 1)
            } else {
                0f
            }
            // A still axis quantizes to zero on its own, so it needs no case of its own.
            return Draw(
                quantize(rawYaw, 0, gcd),
                quantize(rawPitch, 1, gcd),
                rawYaw,
                rawPitch,
                z1,
                z2,
                drewNoise
            )
        }

        /**
         * How far the aim would sit from the target's box once this step lands, in half-box units:
         * at or below 1 the crosshair is on the hitbox.
         *
         * The step reaches the wire a tick after it is sampled in the live smoother, and between two
         * rows in the rollout; either way the target has moved by then, so its last angular velocity
         * is added. The box is the angle half its width and half its height subtend at its distance,
         * a rectangle around the centre the residual channels are measured to — an approximation of
         * the ray test (it ignores the box's depth and the pitch foreshortening of its faces).
         * Infinite when the row carries no target geometry to judge against.
         */
        private fun hitboxError(features: FloatArray, yaw: Float, pitch: Float): Float {
            if (features[F_HAS_TARGET] != 1f) {
                return Float.POSITIVE_INFINITY
            }
            val distance = features[F_DISTANCE].toDouble()
            val width = features[F_TARGET_WIDTH].toDouble()
            val height = features[F_TARGET_HEIGHT].toDouble()
            if (!(distance > 0.0) || !(width > 0.0) || !(height > 0.0)) {
                return Float.POSITIVE_INFINITY
            }
            val halfYaw = Math.toDegrees(atan2(width / 2.0, distance)).toFloat()
            val halfPitch = Math.toDegrees(atan2(height / 2.0, distance)).toFloat()
            val yawAfter = features[F_DELTA_YAW] + features[F_TARGET_VEL_YAW] - yaw
            val pitchAfter = features[F_DELTA_PITCH] + features[F_TARGET_VEL_PITCH] - pitch
            return max(abs(yawAfter) / halfYaw, abs(pitchAfter) / halfPitch)
        }

        private fun quantize(value: Float, axis: Int, gcd: Double): Float {
            check(value.isFinite()) { "Clone sampled a non-finite action" }
            val limit = metadata.maxStep[axis] * MAX_STEP_TOLERANCE
            val bounded = value.coerceIn(-limit, limit)
            val counts = (bounded / gcd).roundToInt()
            return (counts * gcd).toFloat()
        }

        private fun sampleAttack(
            actionYaw: Float,
            actionPitch: Float,
            temperature: Float,
            random: RandomGenerator
        ): Boolean {
            val coefficientYaw = heads[H_ATTACK_DX]
                .coerceIn(-MAX_ATTACK_ACTION_COEFFICIENT, MAX_ATTACK_ACTION_COEFFICIENT)
            val coefficientPitch = heads[H_ATTACK_DY]
                .coerceIn(-MAX_ATTACK_ACTION_COEFFICIENT, MAX_ATTACK_ACTION_COEFFICIENT)
            val probability = sigmoid(
                heads[H_ATTACK] + coefficientYaw * actionYaw + coefficientPitch * actionPitch +
                    (attackBiasOverride ?: attackBias)
            )
            return if (temperature <= 0f) probability > 0.5f else random.nextFloat() < probability
        }

        private fun sampleCategorical(
            offset: Int,
            count: Int,
            scale: Float,
            deterministic: Boolean,
            random: RandomGenerator
        ): Int {
            var maximum = Float.NEGATIVE_INFINITY
            for (i in 0 until count) {
                maximum = max(maximum, heads[offset + i] / scale)
            }

            var total = 0f
            val weights = FloatArray(count)
            for (i in 0 until count) {
                val weight = exp(heads[offset + i] / scale - maximum)
                weights[i] = weight
                total += weight
            }
            check(total.isFinite() && total > 0f) { "Clone produced an invalid categorical distribution" }

            if (deterministic) {
                return weights.indices.maxBy { weights[it] }
            }

            var threshold = random.nextFloat() * total
            for (i in weights.indices) {
                threshold -= weights[i]
                if (threshold <= 0f) {
                    return i
                }
            }
            return weights.lastIndex
        }

        @Synchronized
        override fun close() {
            if (sessionClosed) {
                return
            }
            sessionClosed = true
            state?.close()
            state = null
            stateManager.close()
        }
    }

    fun newSession(): Session {
        check(!closed) { "Model '$name' is closed" }
        return Session()
    }

    /** Raw attack logits over teacher-forced rows, conditioned on their paired actions. */
    @Suppress("NestedBlockDepth")
    @Synchronized
    fun attackLogits(rows: FloatArray, actions: FloatArray, rowCount: Int): FloatArray {
        require(rows.size >= rowCount * FEATURES) { "Expected $rowCount rows of $FEATURES features" }
        require(actions.size >= rowCount * 2) { "Expected $rowCount action rows" }
        check(!closed) { "Model '$name' is closed" }

        val result = FloatArray(rowCount)
        val carrier = model.getNDManager().newSubManager()
        var carried: NDArray? = null

        try {
            var start = 0
            while (start < rowCount) {
                val length = min(LOGIT_CHUNK, rowCount - start)
                val chunk = rows.copyOfRange(start * FEATURES, (start + length) * FEATURES)
                for (t in 0 until length) {
                    normalization.applyFeatures(chunk, t * FEATURES)
                }

                model.getNDManager().newSubManager().use { scratch ->
                    val input = scratch.create(chunk, Shape(1, length.toLong(), FEATURES.toLong()))
                    val inputs = carried?.let { NDList(input, it) } ?: NDList(input)
                    val output = net.forward(parameterStore, inputs, false)
                    val attackHeads = output[0]
                        .get(NDIndex(":, :, $H_ATTACK:${H_ATTACK_DY + 1}"))
                        .toFloatArray()

                    for (t in 0 until length) {
                        val actionIndex = (start + t) * 2
                        val yaw = actions[actionIndex]
                        val pitch = actions[actionIndex + 1]
                        val normalizedYaw = if (yaw == 0f) 0f else normalization.normalizeAction(yaw, 0)
                        val normalizedPitch = if (pitch == 0f) 0f else normalization.normalizeAction(pitch, 1)
                        val head = t * 3
                        result[start + t] = attackHeads[head] +
                            attackHeads[head + 1].coerceIn(
                                -MAX_ATTACK_ACTION_COEFFICIENT,
                                MAX_ATTACK_ACTION_COEFFICIENT
                            ) * normalizedYaw +
                            attackHeads[head + 2].coerceIn(
                                -MAX_ATTACK_ACTION_COEFFICIENT,
                                MAX_ATTACK_ACTION_COEFFICIENT
                            ) * normalizedPitch
                    }

                    val nextState = output[1]
                    nextState.detach()
                    nextState.attach(carrier)
                    carried?.close()
                    carried = nextState
                }
                start += length
            }
        } finally {
            carried?.close()
            carrier.close()
        }
        check(result.all(Float::isFinite)) { "Clone produced non-finite attack logits" }
        return result
    }

    /**
     * Distribution heads for `lanes` windows of `length` rows each, run from a cold state, as a flat
     * `(lanes, length, HEADS)` array. [rows] are already standardized. Inference only — no gradient
     * is recorded — which is what the reference model in [CloneReinforce] needs.
     */
    @Synchronized
    internal fun forwardHeads(rows: FloatArray, lanes: Int, length: Int): FloatArray {
        require(rows.size == lanes * length * FEATURES) { "Expected $lanes x $length rows" }
        check(!closed) { "Model '$name' is closed" }
        return model.getNDManager().newSubManager().use { scratch ->
            val input = scratch.create(rows, Shape(lanes.toLong(), length.toLong(), FEATURES.toLong()))
            net.forward(parameterStore, NDList(input), false)[0].toFloatArray()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        if (ownsModel) {
            model.close()
        }
    }

    companion object {
        /**
         * A model view over weights someone else owns and keeps changing — the policy a trainer is
         * updating in place. Sessions opened on it sample from the CURRENT weights, which is what a
         * policy-gradient rollout has to do.
         */
        internal fun wrapping(name: String, metadata: ModelMetadata, model: Model, net: CloneNet) =
            CloneModel(name, metadata, model, net, ownsModel = false)

        private const val MAX_STEP_TOLERANCE = 1.1f
        private const val MAX_CORRELATION = 0.95f

        /** Upper bound on the temporal noise AR(1) coefficient; above this the noise barely renews. */
        private const val MAX_NOISE_CORRELATION = 0.9f
        private const val MAX_ATTACK_ACTION_COEFFICIENT = 3f
        private const val MIN_TEMPERATURE = 1e-3f
        private const val LOGIT_CHUNK = 2048

        const val PARAMETER_PREFIX = "clone"

        private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

        fun isModelFolder(folder: File) = folder.isDirectory && ModelMetadata.load(folder) != null

        fun load(folder: File): CloneModel? {
            val metadata = ModelMetadata.load(folder) ?: return null
            val net = CloneNet(metadata.hidden, metadata.layers)
            val model = Model.newInstance(folder.name)

            return runCatching {
                model.block = net
                model.load(folder.toPath(), PARAMETER_PREFIX)
                CloneModel(folder.name, metadata, model, net)
            }.onFailure {
                model.close()
            }.getOrThrow()
        }
    }
}
