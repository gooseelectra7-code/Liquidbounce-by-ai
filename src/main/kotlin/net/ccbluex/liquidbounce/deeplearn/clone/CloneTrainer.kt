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

import ai.djl.Model
import ai.djl.engine.Engine
import ai.djl.ndarray.NDManager
import ai.djl.ndarray.types.Shape
import ai.djl.training.DefaultTrainingConfig
import ai.djl.training.initializer.XavierInitializer
import ai.djl.training.optimizer.Adam
import ai.djl.training.tracker.Tracker
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.FEATURES
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.MOVE_STATES
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Trains a clone from a recorded profile.
 *
 * Everything runs on the CPU inside the client — the model is small enough (about 100k parameters)
 * that a typical session trains in a couple of minutes, and keeping it in-process means the player
 * never has to move data anywhere to retrain.
 */
object CloneTrainer {

    /**
     * Passes over the recording.
     *
     * Four times the window trainer's forty, because a pass now costs a quarter of what it did: a
     * tick is consumed once per epoch instead of appearing in four overlapping windows at a
     * sixteen-tick stride, so the same number of optimizer updates takes four times the epochs and
     * about the same wall clock.
     */
    const val DEFAULT_EPOCHS = 160

    /**
     * Hidden units unless the caller overrides them. See [CloneNet.suggestedHidden].
     *
     * 48, not 32, since 2026-09-29: trained headlessly on the same 92k ticks and split and scored on
     * the same 35 held-out fights, 48 units hit 58.5 % against 32's 54.9 % at Temperature 0.55
     * (49.1 % against 45.4 % at 1.0) with LOWER adversary AUCs; 64 units lowered the held-out loss
     * further but did not move any in-game metric, so 48 is where the gain stops.
     */
    const val DEFAULT_HIDDEN = 48

    /**
     * Default v7 step-memory lambda. Set near a real player's step autocorrelation (~0.6) so the
     * reparam gives momentum without the undamped v6 spin. Tunable per `.clone train`.
     */
    const val DEFAULT_STEP_MEMORY = 0.5f

    /** Ticks per truncated-BPTT chunk — how far back a gradient reaches, not how much state exists. */
    const val WINDOW = 64

    /** Recordings played in parallel as the batch dimension. See [CloneBptt]. */
    const val BATCH = 64

    private const val VALIDATION_FRACTION = 0.1
    private const val MIN_VALIDATION_SEQUENCES = 1

    /**
     * Updates an epoch must be worth before its held-out score is allowed to choose the weights.
     *
     * A pass over a small profile is a couple of gradient steps, and the difference between two
     * such epochs is noise. Grouping passes until an epoch is this long is what keeps the rollback
     * from landing on a network that has barely left its initialisation.
     */
    private const val MIN_STEPS_PER_EPOCH = 24

    /**
     * Held-out ticks below which the validation loss is reported but never used to pick an epoch.
     * Roughly a couple of training chunks' worth — under that the number moves more between epochs
     * than the model does.
     */
    private const val MIN_VALIDATION_TICKS = 600L

    /**
     * Below this many sequences, holding a whole one out would cost too large a share of the data,
     * so the split happens inside the sequences instead.
     */
    private const val MIN_SEQUENCES_TO_SPLIT_WHOLE = 8

    private const val BASE_LEARNING_RATE = 1e-3f
    private const val FINAL_LEARNING_RATE = 1e-5f
    private const val IMPROVE_LEARNING_RATE = 1e-4f

    class Progress(
        val onMessage: (String) -> Unit,
        val onProgress: (epoch: Int, epochs: Int, fraction: Float) -> Unit
    )

    class Result(
        val metadata: ModelMetadata,
        val trainLoss: Float,
        val validationLoss: Float,
        /** Truncated-BPTT chunks in one pass over the recording. */
        val stepsPerEpoch: Int,
        val scoredTicks: Long
    )

    class NoDataException(message: String) : Exception(message)

    /**
     * Training choices that are deliberately not settings (see the "bake internal knobs into logic"
     * rule): the defaults are what `.clone train` runs, and the fields exist so an offline
     * comparison can train a variant on exactly the same data and split, then have the winner
     * baked in here.
     */
    class Options(
        /** [FeatureSchema.Normalization.actionScale]; `0` keeps plain degrees. */
        val actionScale: Float = 0f,
        /** L2 weight decay handed to Adam; `0` is none. */
        val weightDecay: Float = 0f,
        /** Spread of the previous-action augmentation, see [CloneBptt]. */
        val actionNoise: Float = CloneBptt.ACTION_NOISE,
        /** Multiply the tick weights by [CloneTickWeights.RANGE_EMPHASIS]. Ignored when continuing a model. */
        val rangeEmphasis: Boolean = false
    )

    /**
     * Trains [name] on [profile] and writes weights plus metadata into the model folder.
     *
     * When [continueFrom] names an existing model its weights are the starting point and the
     * learning rate is lowered, so a second session refines the clone rather than restarting it.
     */
    fun train(
        name: String,
        profile: String,
        epochs: Int = DEFAULT_EPOCHS,
        hidden: Int = DEFAULT_HIDDEN,
        layers: Int = 1,
        seed: Long = DEFAULT_SEED,
        continueFrom: File? = null,
        stepMemory: Float = DEFAULT_STEP_MEMORY,
        progress: Progress
    ): Result {
        require(DeepLearningEngine.isInitialized) { "Deep learning engine is not initialized" }

        val parsed = AimSequence.parseDetailed(profile)
        if (parsed.sequences.isEmpty()) {
            throw NoDataException(
                if (parsed.wrongVersion > 0) {
                    "Profile '$profile' only holds recordings made by a different build " +
                        "(schema v${AimSequence.VERSION} expected) — record again, or update the " +
                        "client that produced them"
                } else {
                    "No usable sequences in profile '$profile'"
                }
            )
        }
        if (parsed.wrongVersion > 0) {
            progress.onMessage(
                "Skipped ${parsed.wrongVersion} sequences written by a build with a different " +
                    "schema — this client expects v${AimSequence.VERSION}."
            )
        }
        if (parsed.unreadableFiles > 0) {
            progress.onMessage(
                "Could not read ${parsed.unreadableFiles} file(s) in the profile — likely a crash " +
                    "mid-write. Whatever they held is not part of this training run."
            )
        }
        if (parsed.practice.isNotEmpty()) {
            progress.onMessage(
                ("Left out %d practice sequence(s), %d ticks, recorded against a dummy (Gym or " +
                    ".fakeplayer) — it trains a different hand than a real fight.").format(
                    parsed.practice.size,
                    parsed.practice.sumOf { it.ticks.size }
                )
            )
        }
        if (parsed.practiceOnly) {
            progress.onMessage(
                "This profile holds nothing but practice against a dummy, so it is trained on as is."
            )
        }

        return trainOn(
            sequences = parsed.sequences,
            name = name,
            profile = profile,
            folder = CloneModels.folderOf(name),
            epochs = epochs,
            hidden = hidden,
            layers = layers,
            seed = seed,
            continueFrom = continueFrom,
            stepMemory = stepMemory,
            progress = progress
        )
    }

    /**
     * Everything past loading the profile: trains on [sequences] and publishes into [folder].
     *
     * Split from [train] so it needs neither the client nor its config folders — only the DJL
     * engine — and a variant can be trained headlessly on the same recordings and scored against
     * the same hold-out as the model it would replace.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
    fun trainOn(
        sequences: List<AimSequence>,
        name: String,
        profile: String,
        folder: File,
        epochs: Int = DEFAULT_EPOCHS,
        hidden: Int = DEFAULT_HIDDEN,
        layers: Int = 1,
        seed: Long = DEFAULT_SEED,
        continueFrom: File? = null,
        stepMemory: Float = DEFAULT_STEP_MEMORY,
        progress: Progress,
        options: Options = Options()
    ): Result {
        val selfTest = CloneLoss.selfTest()
        if (abs(selfTest - CloneLoss.SELF_TEST_EXPECTED) > CloneLoss.SELF_TEST_TOLERANCE) {
            throw IllegalStateException(
                "Loss self-test failed: got $selfTest, expected ${CloneLoss.SELF_TEST_EXPECTED}. " +
                    "Refusing to train against a broken objective."
            )
        }
        if (sequences.isEmpty()) {
            throw NoDataException("No usable sequences in profile '$profile'")
        }

        val loadedTicks = sequences.sumOf { it.ticks.size }
        val combatTicks = sequences.sumOf { sequence -> sequence.ticks.count { it.hasTarget } }
        progress.onMessage(
            "Loaded %d sequences, %d ticks, %.0f%% with a target".format(
                sequences.size,
                loadedTicks,
                if (loadedTicks == 0) 0f else combatTicks.toFloat() / loadedTicks * 100f
            )
        )

        // The engine RNG drives the weight initialisation (and the batch noise below); without
        // this the recorded seed reproduced the data split but not the model.
        Engine.getInstance().setRandomSeed(seed.toInt())

        val previous = continueFrom?.let { folder ->
            ModelMetadata.load(folder)
                ?: throw IllegalArgumentException("Cannot improve an incompatible or incomplete model")
        }
        // Improve must keep the original lambda: the loaded weights were fit against innovations
        // damped by it, and re-labelling with a different lambda would move the target under them.
        val effectiveStepMemory = (previous?.stepMemory ?: stepMemory).coerceIn(0f, 1f)
        if (previous != null && (previous.hidden != hidden || previous.layers != layers)) {
            throw IllegalArgumentException(
                "Existing model is ${previous.hidden}x${previous.layers}, requested ${hidden}x$layers"
            )
        }

        val random = Random(seed)
        val split = previous?.let { continueSplit(sequences, it) } ?: split(sequences, seed)
        if (split.train.isEmpty()) {
            throw NoDataException("Profile '$profile' has too few sequences to train on")
        }

        val trainTensors = split.train.map(::SequenceTensor)
        val validationTensors = split.validation.map(::SequenceTensor)

        // Existing weights encode the old feature/action coordinate system. Re-fitting these
        // numbers while loading those weights changes the meaning of every input and output under
        // them, so improve must keep the original normalization exactly.
        val normalization = previous?.normalization()
            ?: FeatureSchema.Normalization.fit(trainTensors, options.actionScale)
        val totalTicks = trainTensors.sumOf { it.rowCount }

        // Attacks are a small minority of ticks, so the attack head is weighted by how rare they
        // actually were. Without this the head settles on "never swings" and the aura goes quiet.
        val attackRate = attackRate(trainTensors)
        val attackWeight = CloneLoss.weightForRate(attackRate)
        progress.onMessage(
            "Attacks on %.1f%% of ticks — weighting them %.1fx".format(attackRate * 100f, attackWeight)
        )

        // Continuing a model keeps its buckets instead of refitting them: the weights *are* half the
        // objective, and a recording that has grown since would otherwise silently move the target
        // the second run is aiming at. The same holds for which ticks are scored at all.
        val weighting = previous?.tickWeights() ?: CloneTickWeights.fit(trainTensors, byRange = options.rangeEmphasis)
        val heaviest = weighting.heaviest
        val disengaged = weighting.weights.lastIndex
        progress.onMessage(
            ("Leaning on the committed turn — %s counts %.1fx, %s only %.1fx. Train and validation " +
                "are weighted means from here, so they only line up roughly with what earlier runs " +
                "reported.").format(
                weighting.describe(heaviest),
                weighting.weights[heaviest],
                weighting.describe(disengaged),
                weighting.weights[disengaged]
            )
        )
        if (weighting.weighsRange) {
            progress.onMessage(
                "Range factor per block, 0-1 .. 6+: " +
                    weighting.rangeWeights.joinToString(" ") { "%.2fx".format(it) }
            )
        }

        val disengagement = previous?.disengagement() ?: CloneDisengagement()
        val trainMarks = disengagement.markAll(trainTensors)
        progress.onMessage(
            ("%.1f%% of the training ticks sit in a stretch where the player had stopped fighting " +
                "— turned away, idle, walking off with a target still in range. Excluding them from loss.")
                .format(trainMarks.share * 100f)
        )

        val parameters = CloneNet.parameterCount(hidden, layers)
        val suggested = CloneNet.suggestedHidden(totalTicks)
        progress.onMessage("Model has $parameters parameters for $totalTicks ticks")
        if (hidden > suggested) {
            progress.onMessage(
                "That is oversized for this much data — ${suggested} hidden units " +
                    "(${CloneNet.parameterCount(suggested, layers)} parameters) fits it. " +
                    "Watch whether validation loss follows training loss."
            )
        }

        val trainStream = CloneBptt(
            tensors = trainTensors,
            marks = trainMarks,
            normalization = normalization,
            weighting = weighting,
            requestedLanes = BATCH,
            chunk = WINDOW,
            actionNoise = options.actionNoise
        )
        val validationStream = CloneBptt(
            tensors = validationTensors,
            // Held out under the same objective. A validation loss that scored the disengaged
            // stretches training refused to learn would not be measuring the same thing.
            marks = disengagement.markAll(validationTensors),
            normalization = normalization,
            weighting = weighting,
            requestedLanes = BATCH,
            chunk = WINDOW
        ).takeIf { it.stepsPerEpoch > 0 }

        if (trainStream.stepsPerEpoch == 0) {
            throw NoDataException("Profile '$profile' holds no usable recording after sanitizing")
        }
        if (trainStream.scoredTicks == 0L) {
            throw NoDataException("Profile '$profile' contains no engaged combat ticks after sanitizing")
        }
        // One pass over a small profile is a handful of updates, and an epoch that short makes the
        // held-out score a coin flip between neighbouring epochs — which is what the best-epoch
        // rollback then picks from. Passes are grouped until an epoch is worth measuring.
        val passes = max(1, MIN_STEPS_PER_EPOCH / trainStream.stepsPerEpoch)
        val stepsPerEpoch = trainStream.stepsPerEpoch * passes
        val totalSteps = max(1, epochs * stepsPerEpoch)

        progress.onMessage(
            ("Streaming %d recordings over %d lanes, %d chunks of %d ticks; %d updates per epoch, " +
                "%d in total. The hidden state carries across chunks, so the model is trained on " +
                "the same long-lived state it accumulates in a fight.").format(
                trainTensors.size,
                trainStream.lanes,
                trainStream.stepsPerEpoch,
                WINDOW,
                stepsPerEpoch,
                totalSteps
            )
        )

        // The held-out split has to be big enough that a difference between two epochs means
        // something. Below that the rollback would keep whichever epoch noise happened to favour —
        // on a first, minute-long profile that landed on epoch 19 of 160, which is a network still
        // near its initialisation, and it aimed like one.
        val validationTicks = validationStream?.scoredTicks ?: 0L
        val selectByValidation = validationTicks >= MIN_VALIDATION_TICKS
        if (validationStream != null && !selectByValidation) {
            progress.onMessage(
                ("Only %d held-out ticks — too few to choose an epoch by, so the last one is kept. " +
                    "The reported validation loss is still printed, but do not read much into it.")
                    .format(validationTicks)
            )
        }

        val net = CloneNet(hidden, layers)
        val model = Model.newInstance(name)
        model.block = net

        continueFrom?.let { folder ->
            runCatching { model.load(folder.toPath(), CloneModel.PARAMETER_PREFIX) }
                .getOrElse { error ->
                    model.close()
                    throw IllegalStateException("Could not load existing weights; model was not changed", error)
                }
        }

        var trainLoss = Float.NaN
        var validationLoss = Float.NaN

        var bestValidation = Float.POSITIVE_INFINITY
        var bestTrain = Float.NaN
        var bestEpoch = 0
        var divergedAt = 0
        val checkpoint = Files.createTempDirectory("clone-checkpoint")

        // The finally is what keeps an engine error mid-epoch from leaving checkpoint folders
        // behind in the temp directory for every failed run.
        try {
            NDManager.newBaseManager().use { manager ->
                val baseRate = if (continueFrom == null) BASE_LEARNING_RATE else IMPROVE_LEARNING_RATE
                val config = DefaultTrainingConfig(CloneLoss(attackWeight, effectiveStepMemory))
                    .optInitializer(XavierInitializer(), "weight")
                    .optOptimizer(
                        Adam.builder()
                            .optLearningRateTracker(
                                Tracker.cosine()
                                    .setBaseValue(baseRate)
                                    .optFinalValue(FINAL_LEARNING_RATE)
                                    .setMaxUpdates(totalSteps)
                                    .build()
                            )
                            .optWeightDecays(options.weightDecay)
                            .build()
                    )

                model.newTrainer(config).use { trainer ->
                    trainer.initialize(
                        Shape(trainStream.lanes.toLong(), WINDOW.toLong(), FEATURES.toLong())
                    )

                    // Overwrite one checkpoint rather than accumulating numbered ones: DJL derives
                    // the file's epoch from what is already in the folder unless this property
                    // pins it.
                    model.setProperty("Epoch", "0")

                    for (epoch in 1..epochs) {
                        var passLoss = 0.0
                        repeat(passes) { pass ->
                            passLoss += trainStream.run(trainer, manager, random, training = true) {
                                progress.onProgress(epoch, epochs, (pass + it) / passes)
                            }
                        }
                        trainLoss = (passLoss / passes).toFloat()
                        validationLoss = validationStream?.run(
                            trainer,
                            manager,
                            random,
                            training = false
                        ) {} ?: Float.NaN

                        // A non-finite epoch poisons every weight after it, but not the checkpoint of
                        // the best epoch before it. Throwing that away turned one late blow-up into
                        // a lost run (reported: 107 good epochs of 160, then NaN, then no model), so
                        // the run stops here and the rollback below restores the checkpoint. Only a
                        // run with nothing validated yet has nothing to keep.
                        if (!trainLoss.isFinite() || (validationStream != null && !validationLoss.isFinite())) {
                            if (bestEpoch == 0) {
                                throw IllegalStateException(
                                    "Training diverged at epoch $epoch; existing model was not changed"
                                )
                            }
                            divergedAt = epoch
                            progress.onMessage(
                                "Training diverged at epoch %d — stopping and keeping epoch %d (validation %.4f)"
                                    .format(epoch, bestEpoch, bestValidation)
                            )
                            break
                        }

                        // The last epoch is rarely the best one. Training loss keeps falling long
                        // past the point where the model stops learning the behaviour and starts
                        // learning the particular windows it was shown; only the held-out loss can
                        // tell those apart, so the weights that scored best on it are kept — but
                        // only while that score is measured on enough ticks to be a measurement.
                        if (selectByValidation && validationLoss.isFinite() && validationLoss < bestValidation) {
                            bestValidation = validationLoss
                            bestTrain = trainLoss
                            bestEpoch = epoch
                            model.save(checkpoint, CloneModel.PARAMETER_PREFIX)
                        }

                        progress.onMessage(
                            "Epoch %d/%d — train %.4f, validation %s%s".format(
                                epoch,
                                epochs,
                                trainLoss,
                                if (validationLoss.isNaN()) "n/a" else "%.4f".format(validationLoss),
                                if (bestEpoch == epoch) "  <- best" else ""
                            )
                        )
                    }
                }
            }

            // Roll back to the best epoch. Nothing to roll back to when there was no validation
            // data — then the last epoch is all there is, and the user is told the choice was not
            // made.
            if (bestEpoch in 1 until epochs) {
                runCatching { model.load(checkpoint, CloneModel.PARAMETER_PREFIX) }
                    .onSuccess {
                        trainLoss = bestTrain
                        validationLoss = bestValidation
                        if (divergedAt == 0) {
                            progress.onMessage(
                                "Kept epoch %d (validation %.4f) — later epochs were fitting the recording"
                                    .format(bestEpoch, bestValidation)
                            )
                        }
                    }
                    .getOrElse { error ->
                        throw IllegalStateException(
                            "Could not restore the best checkpoint; model was not changed",
                            error
                        )
                    }
            } else if (bestEpoch == epochs) {
                progress.onMessage("Validation was still improving at the last epoch — try more")
            }
        } catch (error: Throwable) {
            model.close()
            throw error
        } finally {
            checkpoint.toFile().deleteRecursively()
        }

        check(trainLoss.isFinite()) { "Training diverged; refusing to save non-finite weights" }
        if (!validationLoss.isFinite()) {
            progress.onMessage(
                "No validation data — record more, or in more separate sessions, to get a held-out score."
            )
        }

        val staging = Files.createTempDirectory("clone-output-$name-").toFile()
        val metadata = ModelMetadata(
            schemaVersion = FeatureSchema.VERSION,
            profile = profile,
            hidden = hidden,
            layers = layers,
            window = WINDOW,
            // The chunks are consecutive rather than overlapping now, so the stride the model was
            // trained under is its own length.
            stride = WINDOW,
            featureMean = normalization.mean,
            featureStd = normalization.std,
            actionMean = normalization.actionMean,
            actionStd = normalization.actionStd,
            sampleCount = totalTicks,
            sequenceCount = split.train.size,
            epochs = epochs,
            bestEpoch = bestEpoch,
            trainLoss = trainLoss.takeIf { it.isFinite() },
            validationLoss = validationLoss.takeIf { it.isFinite() },
            trainGcd = sequences.first().gcd,
            sensitivity = sequences.first().sensitivity,
            maxStep = maxStep(trainTensors),
            zeroFraction = zeroFraction(trainTensors),
            attackRate = attackRate,
            stepMemory = effectiveStepMemory,
            actionScale = normalization.actionScale.takeIf { it > 0f },
            coverageAngle = percentile(trainTensors) { it.targetAngle },
            coverageDistance = percentile(trainTensors) { tensor ->
                // Target-less ticks carry distance 0, and 0 passes the >= 0 filter the percentile
                // uses to drop sentinels — they were dragging the reported range down. Reuse the
                // angle sentinel, which is -1 on exactly those ticks.
                FloatArray(tensor.rowCount) { i ->
                    if (tensor.targetAngle[i] < 0f) {
                        -1f
                    } else {
                        tensor.featureRows[i * FEATURES + FeatureSchema.F_DISTANCE]
                    }
                }
            },
            approachSpeed = approachSpeed(trainTensors, trainMarks),
            errorBucketEdges = weighting.edges,
            errorBucketWeights = weighting.weights,
            rangeBucketEdges = weighting.rangeEdges.takeIf { weighting.weighsRange },
            rangeBucketWeights = weighting.rangeWeights.takeIf { weighting.weighsRange },
            disengageOpenDegrees = disengagement.openDegrees,
            disengageMinTicks = disengagement.minTicks,
            disengageMaxMeanStep = disengagement.maxMeanStep,
            disengageRescueStep = disengagement.rescueStep,
            disengageWeight = disengagement.weight,
            splitMode = split.mode,
            trainKeys = split.trainKeys,
            validationKeys = split.validationKeys,
            seed = seed,
            createdAt = System.currentTimeMillis()
        )

        try {
            // Build a complete, reloadable model away from the live folder. An improve that fails
            // while saving or calibrating must leave the old weights and metadata as one coherent
            // pair, not overwrite half of them and report failure afterwards.
            model.save(staging.toPath(), CloneModel.PARAMETER_PREFIX)
            model.close()
            metadata.save(staging)

            val calibration = CloneCalibration.calibrate(staging, split.validation)
            if (calibration == null) {
                throw IllegalStateException("Could not reload the staged model to calibrate it")
            }
            calibration.applyTo(metadata)
            progress.onMessage(calibration.describe())

            publish(staging, folder)
            return Result(
                metadata,
                trainLoss,
                validationLoss,
                trainStream.stepsPerEpoch,
                trainStream.scoredTicks
            )
        } finally {
            runCatching { model.close() }
            staging.deleteRecursively()
        }
    }

    /** Moves parameters first and metadata last, so a folder is never discoverable half-built. */
    private fun publish(staging: File, folder: File) {
        folder.mkdirs()
        val files = staging.listFiles()?.sortedBy { it.name == ModelMetadata.FILE_NAME }.orEmpty()
        for (source in files) {
            val target = folder.resolve(source.name).toPath()
            try {
                Files.move(
                    source.toPath(),
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * Splits a profile into training and held-out data.
     *
     * Deterministic in [seed], but determinism only reproduces the split over the *exact same*
     * file set — which is why the split's membership is also recorded as sequence fingerprints
     * ([Split.trainKeys]/[Split.validationKeys]) and stored in the model's metadata. Evaluation
     * rebuilds the hold-out from those through [holdoutFor]; re-running the seeded split over a
     * profile that has been recorded into since silently shuffles trained sequences into the
     * "hold-out", and a score on training data always looks excellent.
     *
     * With enough sequences the split is by whole sequence. With only a handful — one long
     * uninterrupted recording is the normal first case — holding a whole sequence out would cost
     * most of the data, so instead a contiguous tail is carved off each. Contiguous matters:
     * windows overlap by design, and a cut anywhere but a boundary would put near-identical rows
     * on both sides.
     */
    fun split(sequences: List<AimSequence>, seed: Long): Split {
        val shuffled = sequences.shuffled(Random(seed))
        if (shuffled.isEmpty()) {
            return Split(emptyList(), emptyList(), ModelMetadata.SPLIT_WHOLE, LongArray(0), LongArray(0))
        }

        if (shuffled.size >= MIN_SEQUENCES_TO_SPLIT_WHOLE) {
            val count = max(MIN_VALIDATION_SEQUENCES, (shuffled.size * VALIDATION_FRACTION).toInt())
                .coerceAtMost(shuffled.size - 1)
            val validation = shuffled.take(count)
            val train = shuffled.drop(count)
            return Split(
                train = train,
                validation = validation,
                mode = ModelMetadata.SPLIT_WHOLE,
                trainKeys = LongArray(train.size) { train[it].identity },
                validationKeys = LongArray(validation.size) { validation[it].identity }
            )
        }

        val train = mutableListOf<AimSequence>()
        val validation = mutableListOf<AimSequence>()
        val trainKeys = mutableListOf<Long>()
        val validationKeys = mutableListOf<Long>()

        for (sequence in shuffled) {
            val ticks = sequence.ticks
            val holdout = (ticks.size * VALIDATION_FRACTION).toInt()

            // Both halves have to be able to hold at least one window, or the cut buys nothing.
            if (holdout < WINDOW || ticks.size - holdout < WINDOW) {
                train.add(sequence)
                trainKeys.add(sequence.identity)
                continue
            }

            val cut = ticks.size - holdout
            train.add(sequence.copy(ticks = ticks.subList(0, cut).toList()))
            validation.add(sequence.copy(ticks = ticks.subList(cut, ticks.size).toList()))
            // The *original* sequence's fingerprint: that is what sits on disk at eval time, and
            // the tail can be re-carved from it deterministically — see [tailOf].
            validationKeys.add(sequence.identity)
        }

        return Split(
            train = train,
            validation = validation,
            mode = ModelMetadata.SPLIT_TAIL,
            trainKeys = trainKeys.toLongArray(),
            validationKeys = validationKeys.toLongArray()
        )
    }

    /**
     * Rebuilds the exact split an existing model used and assigns newly recorded sequences to
     * training only. Its validation data therefore remains data the weights have never seen, while
     * improve can consume everything added since the previous run without moving an old training
     * sequence across the boundary.
     */
    private fun continueSplit(sequences: List<AimSequence>, metadata: ModelMetadata): Split {
        val oldTrain = metadata.trainKeys?.toHashSet()
            ?: throw IllegalArgumentException("Existing model has no pinned training split")
        val oldValidation = metadata.validationKeys?.toHashSet()
            ?: throw IllegalArgumentException("Existing model has no pinned validation split")
        val tail = metadata.splitMode == ModelMetadata.SPLIT_TAIL

        val train = mutableListOf<AimSequence>()
        val validation = mutableListOf<AimSequence>()
        val trainKeys = oldTrain.toMutableSet()

        for (sequence in sequences) {
            when {
                sequence.identity in oldValidation && tail -> {
                    val holdout = (sequence.ticks.size * VALIDATION_FRACTION).toInt()
                    val cut = sequence.ticks.size - holdout
                    if (holdout >= WINDOW && cut >= WINDOW) {
                        train += sequence.copy(ticks = sequence.ticks.subList(0, cut).toList())
                        validation += sequence.copy(ticks = sequence.ticks.subList(cut, sequence.ticks.size).toList())
                    }
                }
                sequence.identity in oldValidation -> validation += sequence
                else -> {
                    train += sequence
                    trainKeys += sequence.identity
                }
            }
        }

        return Split(
            train = train,
            validation = validation,
            mode = metadata.splitMode ?: ModelMetadata.SPLIT_WHOLE,
            trainKeys = trainKeys.toLongArray(),
            validationKeys = oldValidation.toLongArray()
        )
    }

    class Split(
        val train: List<AimSequence>,
        val validation: List<AimSequence>,
        /** [ModelMetadata.SPLIT_WHOLE] or [ModelMetadata.SPLIT_TAIL]. */
        val mode: String,
        val trainKeys: LongArray,
        val validationKeys: LongArray
    )

    /** The deterministic tail [split] holds out of [sequence] in [ModelMetadata.SPLIT_TAIL] mode. */
    private fun tailOf(sequence: AimSequence): AimSequence {
        val holdout = (sequence.ticks.size * VALIDATION_FRACTION).toInt()
        val cut = sequence.ticks.size - holdout
        return sequence.copy(ticks = sequence.ticks.subList(cut, sequence.ticks.size).toList())
    }

    /**
     * What the model described by [metadata] may honestly be scored or calibrated on, out of the
     * profile as it exists *now*.
     *
     * Membership comes from the fingerprints stored at training time, so a profile that has grown
     * since cannot leak trained sequences into the hold-out. Sequences recorded after training are
     * included — the model has never seen them, which makes them the best held-out data there is —
     * and counted separately so the caller can say so. Returns `null` for a model whose metadata
     * predates the stored split, which the caller answers with the legacy seed-split plus a
     * warning.
     */
    fun holdoutFor(
        sequences: List<AimSequence>,
        metadata: ModelMetadata,
        /** On disk but deliberately not scored (practice); counted as present, never held out. */
        setAside: List<AimSequence> = emptyList()
    ): HoldoutSelection? {
        val validationKeys = metadata.validationKeys ?: return null
        val trainKeys = metadata.trainKeys ?: return null
        val tail = metadata.splitMode == ModelMetadata.SPLIT_TAIL

        val validation = validationKeys.toHashSet()
        val train = trainKeys.toHashSet()

        val holdout = mutableListOf<AimSequence>()
        val present = HashSet<Long>(sequences.size + setAside.size)
        setAside.forEach { present.add(it.identity) }
        var unseen = 0

        for (sequence in sequences) {
            val id = sequence.identity
            present.add(id)
            when {
                id in validation -> holdout.add(if (tail) tailOf(sequence) else sequence)
                id in train -> Unit
                else -> {
                    holdout.add(sequence)
                    unseen++
                }
            }
        }

        return HoldoutSelection(
            sequences = holdout,
            unseen = unseen,
            missing = validation.count { it !in present } + train.count { it !in present }
        )
    }

    class HoldoutSelection(
        val sequences: List<AimSequence>,
        /** Sequences recorded after training — never seen by the model, fair and said so. */
        val unseen: Int,
        /** Training-time sequences whose files are no longer on disk. */
        val missing: Int
    )

    /**
     * The 99th percentile of some per-tick quantity, ignoring the sentinel `-1` that marks ticks
     * with no target. A percentile rather than the maximum, so one freak moment does not claim the
     * profile covers ground it saw exactly once.
     */
    private fun percentile(
        tensors: List<SequenceTensor>,
        select: (SequenceTensor) -> FloatArray
    ): Float {
        val values = tensors.flatMap { tensor -> select(tensor).filter { it >= 0f } }
        if (values.isEmpty()) {
            return 0f
        }

        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * COVERAGE_PERCENTILE).toInt()]
    }

    private const val COVERAGE_PERCENTILE = 0.99

    private fun maxStep(tensors: List<SequenceTensor>): FloatArray {
        val result = FloatArray(2)
        for (tensor in tensors) {
            for (i in 0 until tensor.rowCount) {
                result[0] = max(result[0], abs(tensor.actionRows[i * 2]))
                result[1] = max(result[1], abs(tensor.actionRows[i * 2 + 1]))
            }
        }
        return result
    }

    /** Share of ticks that carried a swing. */
    private fun attackRate(tensors: List<SequenceTensor>): Float {
        var attacks = 0L
        var total = 0L

        for (tensor in tensors) {
            for (i in 0 until tensor.rowCount) {
                attacks += tensor.attackRows[i].toLong()
            }
            total += tensor.rowCount
        }

        return if (total == 0L) 0f else attacks.toFloat() / total
    }

    private fun zeroFraction(tensors: List<SequenceTensor>): FloatArray {
        val counts = LongArray(2)
        var total = 0L

        for (tensor in tensors) {
            for (i in 0 until tensor.rowCount) {
                val offset = i * MOVE_STATES
                counts[0] += (tensor.movementStateRows[offset] +
                    tensor.movementStateRows[offset + 2]).toLong()
                counts[1] += (tensor.movementStateRows[offset] +
                    tensor.movementStateRows[offset + 1]).toLong()
            }
            total += tensor.rowCount
        }

        if (total == 0L) {
            return FloatArray(2)
        }
        return floatArrayOf(counts[0].toFloat() / total, counts[1].toFloat() / total)
    }

    /**
     * Median degrees of angle closed per tick, bucketed by how far the target was. Used to answer
     * "how many ticks until this rotation lands", which callers ask before committing to an attack.
     *
     * Disengaged stretches are excluded through [marks]: a player idling with their back turned
     * closes a fraction of a degree per tick, and folding those ticks in dragged the high-angle
     * medians far below what the same player's committed turns actually cover — so the estimate
     * said "this flick takes thirty ticks" about a hand that does it in five.
     */
    private fun approachSpeed(
        tensors: List<SequenceTensor>,
        marks: CloneDisengagement.Marks
    ): FloatArray {
        val buckets = List(ModelMetadata.APPROACH_BUCKETS) { mutableListOf<Float>() }

        for ((index, tensor) in tensors.withIndex()) {
            val engaged = marks.perTick[index]
            for (i in 0 until tensor.rowCount - 1) {
                if (engaged[i] < 1f || engaged[i + 1] < 1f) {
                    continue
                }

                val angle = tensor.targetAngle[i]
                val next = tensor.targetAngle[i + 1]
                if (angle < 0f || next < 0f) {
                    continue
                }

                val progress = angle - next
                if (progress <= 0f) {
                    continue
                }

                val bucket = (angle / ModelMetadata.APPROACH_BUCKET).toInt()
                    .coerceIn(0, ModelMetadata.APPROACH_BUCKETS - 1)
                buckets[bucket].add(progress)
            }
        }

        return FloatArray(ModelMetadata.APPROACH_BUCKETS) { index ->
            val samples = buckets[index]
            if (samples.isEmpty()) {
                0f
            } else {
                samples.sort()
                samples[samples.size / 2]
            }
        }
    }

    const val DEFAULT_SEED = 0x1BADB002L
}
