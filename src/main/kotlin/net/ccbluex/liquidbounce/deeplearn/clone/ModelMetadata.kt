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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.gson.publicGson
import net.ccbluex.liquidbounce.config.gson.util.readJson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Everything needed to reproduce a model's inputs and interpret its outputs, stored next to the
 * weights.
 *
 * The weights alone are meaningless: fed a differently scaled feature vector, or read without the
 * action scale they were trained against, they produce confident nonsense rather than an error.
 * Shipping the schema version and the normalization with them is what makes a stale model
 * detectable instead of silently wrong.
 */
data class ModelMetadata(
    val schemaVersion: Int,
    val profile: String,

    val hidden: Int,
    val layers: Int,
    val window: Int,
    val stride: Int,

    val featureMean: FloatArray,
    val featureStd: FloatArray,
    val actionMean: FloatArray,
    val actionStd: FloatArray,

    val sampleCount: Int,
    val sequenceCount: Int,
    val epochs: Int,

    /** Epoch whose weights were kept — the one with the lowest held-out loss, not the last. */
    val bestEpoch: Int = 0,

    /**
     * Null when there was no number to report — no validation data, or training that produced a
     * NaN. Gson refuses to write NaN, so a "no value" has to be an absent value rather than a
     * special float, and an absent value is the honest thing to show anyway.
     */
    val trainLoss: Float?,
    val validationLoss: Float?,

    val trainGcd: Double,
    val sensitivity: Double,

    /** Largest step seen in training, in degrees, per axis. Bounds what inference may emit. */
    val maxStep: FloatArray,
    val zeroFraction: FloatArray,

    /** Share of training ticks that carried a swing — the click rate the clone should reproduce. */
    val attackRate: Float = 0f,

    /**
     * v7 step-memory lambda: the damping on the output-innovation reparam. `step = lambda*prevStep
     * + sampledInnovation`, so lambda is the sampled step's lag-1 autocorrelation (the momentum) —
     * baked at train time (the label used the same lambda) and reapplied at inference. 1 is the
     * undamped v6 integrator that spun; a value near the player's momentum (~0.6) is the target.
     */
    val stepMemory: Float = 1f,

    /**
     * [FeatureSchema.Normalization.actionScale] the heads were fitted under, or `null` for plain
     * degrees — every model trained before it existed. Boxed for the reason [attackBias] is.
     */
    val actionScale: Float? = null,

    /**
     * The 99th percentile of the angle to the target and of the distance to it, across training.
     *
     * A model asked about geometry well outside this has nothing to go on and will answer anyway,
     * confidently — which is the failure mode that does not announce itself. Recording the reach
     * of the training data is what makes "this profile never saw a 120° flick" something the user
     * can be told rather than something they discover in a fight.
     */
    val coverageAngle: Float = 0f,
    val coverageDistance: Float = 0f,

    /**
     * Median degrees moved towards the target per tick, bucketed by how far away the target was.
     * Bucket `i` covers angles `[i * APPROACH_BUCKET, (i + 1) * APPROACH_BUCKET)`.
     */
    val approachSpeed: FloatArray,

    /**
     * The |yaw error| buckets training weighted its ticks by: upper degree bounds, and the weight
     * each bucket carried.
     *
     * Null on a model that counted every tick alike. Nothing on the inference path reads them —
     * they are stored so that continuing this model trains against the same objective rather than
     * deriving a new one from whatever the recording has grown into since.
     */
    val errorBucketEdges: FloatArray? = null,
    val errorBucketWeights: FloatArray? = null,

    /**
     * The range factor multiplied onto those weights (`CloneTickWeights.RANGE_EMPHASIS`): bucket
     * bounds in blocks and the factor per bucket. Null on a model trained without one, which
     * continuing it then keeps.
     */
    val rangeBucketEdges: FloatArray? = null,
    val rangeBucketWeights: FloatArray? = null,

    /**
     * The disengagement filter training ran under: how a stretch of ticks was recognised as the
     * player having stopped fighting, and what such a tick counted for.
     *
     * Null on a model that scored those stretches like any other. Boxed one by one rather than
     * grouped, for the reason [attackBias] is: an absent field leaves a primitive at a zero that
     * looks like a fitted answer, and a zero here would read as "count them not at all".
     */
    val disengageOpenDegrees: Float? = null,
    val disengageMinTicks: Int? = null,
    val disengageMaxMeanStep: Float? = null,
    val disengageRescueStep: Float? = null,
    val disengageWeight: Float? = null,

    /**
     * Which sequences the training split put where, as [net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence.identity]
     * fingerprints. Evaluation and recalibration rebuild the hold-out from these rather than by
     * re-running the seeded split: the seed only reproduces the split over the *exact same* file
     * set, and a profile that has been recorded into since would silently shuffle trained
     * sequences into the "hold-out" — a contaminated score that always looks excellent.
     *
     * [splitMode] says how to read them: with [SPLIT_WHOLE], [validationKeys] name whole held-out
     * sequences; with [SPLIT_TAIL] they name sequences whose head was trained on and whose tail
     * (the same deterministic cut) is the hold-out. Null on models trained before this was stored.
     */
    val splitMode: String? = null,
    val trainKeys: LongArray? = null,
    val validationKeys: LongArray? = null,

    val seed: Long,
    val createdAt: Long,

    /** Discriminator AUC from the last `.clone eval` run, or `null` if it was never evaluated. */
    var evalAuc: Float? = null,

    /**
     * Logit added to the attack head before it is sampled, fitted by [CloneCalibration].
     *
     * Boxed and nullable on purpose. This is plain Gson with no Kotlin module, so a field absent
     * from the file is left at its JVM default and the Kotlin default argument never runs — for a
     * boxed float that default is `null`, which is what a model built before any of this was
     * stored should read as. A primitive would arrive as a fitted zero.
     */
    var attackBias: Float? = null,

    /** Share of the ticks the fit ran over that the head would swing on without [attackBias]. */
    var attackBiasRate: Float? = null,

    /** Share of those ticks that actually carried a swing — what [attackBias] reproduces. */
    var attackBiasTarget: Float? = null
) {

    /**
     * Every array is null-checked despite the non-null types: this is plain Gson with no Kotlin
     * module, so a field missing from a hand-edited or truncated file arrives as a JVM null and
     * would otherwise surface as an NPE mid-fight rather than as "incompatible" at load.
     */
    val isCompatible: Boolean
        @Suppress("SENSELESS_COMPARISON", "ComplexCondition")
        get() = schemaVersion == FeatureSchema.VERSION &&
            profile != null &&
            featureMean != null && featureMean.size == FeatureSchema.FEATURES &&
            featureStd != null && featureStd.size == FeatureSchema.FEATURES &&
            actionMean != null && actionMean.size == 2 &&
            actionStd != null && actionStd.size == 2 &&
            maxStep != null && maxStep.size == 2 &&
            zeroFraction != null && zeroFraction.size == 2 &&
            approachSpeed != null && approachSpeed.size == APPROACH_BUCKETS &&
            hidden > 0 && layers > 0 &&
            featureMean.all(Float::isFinite) &&
            featureStd.all { it.isFinite() && it > 0f } &&
            actionMean.all(Float::isFinite) &&
            actionStd.all { it.isFinite() && it > 0f } &&
            maxStep.all { it.isFinite() && it >= 0f } &&
            zeroFraction.all { it.isFinite() && it in 0f..1f } &&
            approachSpeed.all { it.isFinite() && it >= 0f }

    fun normalization() = FeatureSchema.Normalization(
        featureMean,
        featureStd,
        actionMean,
        actionStd,
        actionScale?.takeIf { it.isFinite() && it > 0f } ?: 0f
    )

    /**
     * The tick weighting this model was trained against, or `null` when it carries none — which a
     * caller has to answer by fitting one, since a table read from a file it was not written to
     * matches nothing.
     */
    fun tickWeights(): CloneTickWeights? {
        val edges = errorBucketEdges ?: return null
        val weights = errorBucketWeights ?: return null
        if (weights.size != edges.size + 1) {
            return null
        }

        val rangeEdges = rangeBucketEdges
        val rangeWeights = rangeBucketWeights
        return if (rangeEdges != null && rangeWeights != null && rangeWeights.size == rangeEdges.size + 1) {
            CloneTickWeights(edges, weights, rangeEdges, rangeWeights)
        } else {
            CloneTickWeights(edges, weights)
        }
    }

    /**
     * The disengagement filter this model was trained against, or `null` when it carries none.
     *
     * Answered from the file rather than from the current constants for the same reason
     * [tickWeights] is: which ticks count is half of what the loss means, and a second session that
     * quietly aimed at a different objective than the first would leave nothing to compare.
     */
    fun disengagement(): CloneDisengagement? {
        val open = disengageOpenDegrees
        val ticks = disengageMinTicks
        val step = disengageMaxMeanStep
        val rescue = disengageRescueStep
        val weight = disengageWeight
        if (open == null || ticks == null || step == null || rescue == null || weight == null) {
            return null
        }

        return CloneDisengagement(
            openDegrees = open,
            minTicks = ticks,
            maxMeanStep = step,
            rescueStep = rescue,
            weight = weight
        )
    }

    /** Degrees per tick the player typically covers when the target is [angle] degrees away. */
    fun approachSpeedFor(angle: Float): Float {
        if (approachSpeed.isEmpty()) {
            return DEFAULT_APPROACH_SPEED
        }

        val bucket = (angle / APPROACH_BUCKET).toInt().coerceIn(0, approachSpeed.size - 1)
        return approachSpeed[bucket].coerceAtLeast(MIN_APPROACH_SPEED)
    }

    fun save(folder: File) {
        folder.mkdirs()
        write(folder.resolve(FILE_NAME), publicGson.toJson(this))
    }

    /**
     * Data classes with array members get identity equality from the compiler, which is wrong for
     * a value like this. Overridden so a metadata read back from disk compares equal to the one
     * that was written.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ModelMetadata) {
            return false
        }

        return schemaVersion == other.schemaVersion &&
            profile == other.profile &&
            hidden == other.hidden &&
            layers == other.layers &&
            createdAt == other.createdAt &&
            featureMean.contentEquals(other.featureMean) &&
            actionMean.contentEquals(other.actionMean)
    }

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + profile.hashCode()
        result = 31 * result + hidden
        result = 31 * result + layers
        result = 31 * result + createdAt.hashCode()
        return result
    }

    companion object {
        const val FILE_NAME = "metadata.json"

        /** [splitMode] value: [validationKeys] name whole held-out sequences. */
        const val SPLIT_WHOLE = "whole"

        /** [splitMode] value: [validationKeys] name sequences whose tail is the hold-out. */
        const val SPLIT_TAIL = "tail"

        /** Width of an [approachSpeed] bucket, in degrees. */
        const val APPROACH_BUCKET = 15f

        const val APPROACH_BUCKETS = 12

        private const val DEFAULT_APPROACH_SPEED = 12f
        private const val MIN_APPROACH_SPEED = 0.5f

        /**
         * Edits the metadata file in [folder] as a JSON tree and writes it back atomically.
         *
         * A tree edit rather than a round-trip through [ModelMetadata], so a file written by a
         * build that knows more fields than this one keeps them. Every after-the-fact update —
         * calibration, evaluation results — has to come through here for that reason; a full
         * [save] is only for the trainer, which owns the whole file at that moment. The keys are
         * the property names — there are no `@SerializedName` annotations on [ModelMetadata], so
         * renaming a property means renaming the constant at the call site.
         *
         * Returns false when the file is missing or unreadable, in which case nothing is written.
         */
        fun patch(folder: File, mutate: (JsonObject) -> Unit): Boolean {
            val file = folder.resolve(FILE_NAME)
            val root = runCatching {
                file.bufferedReader().use { JsonParser.parseReader(it) }
            }.getOrNull() as? JsonObject ?: return false

            mutate(root)
            write(file, publicGson.toJson(root))
            return true
        }

        /**
         * Replaces [file] with [content] by writing beside it and moving over the top.
         *
         * A truncating write that fails halfway leaves a model that no longer loads, and the
         * weights next to it cost twenty minutes to produce.
         */
        fun write(file: File, content: String) {
            val temporary = file.resolveSibling("$FILE_NAME.tmp")
            temporary.writeText(content)

            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        fun load(folder: File): ModelMetadata? {
            val file = folder.resolve(FILE_NAME)
            if (!file.isFile) {
                return null
            }

            return runCatching { file.readJson<ModelMetadata>() }
                .getOrNull()
                ?.takeIf { it.isCompatible }
        }
    }
}
