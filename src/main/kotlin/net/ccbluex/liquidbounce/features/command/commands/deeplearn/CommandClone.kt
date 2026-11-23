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
package net.ccbluex.liquidbounce.features.command.commands.deeplearn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine.datasetsFolder
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine.modelsFolder
import net.ccbluex.liquidbounce.deeplearn.clone.CloneCalibration
import net.ccbluex.liquidbounce.deeplearn.clone.CloneModel
import net.ccbluex.liquidbounce.deeplearn.clone.CloneModels
import net.ccbluex.liquidbounce.deeplearn.clone.CloneReinforce
import net.ccbluex.liquidbounce.deeplearn.clone.CloneTrainer
import net.ccbluex.liquidbounce.deeplearn.clone.ModelMetadata
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.deeplearn.clone.eval.CloneEval
import net.ccbluex.liquidbounce.deeplearn.clone.eval.SmoothAimSource
import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.CommandException
import net.ccbluex.liquidbounce.features.command.CommandExecutor.suspendHandler
import net.ccbluex.liquidbounce.features.command.Parameter
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.CloneAngleSmooth
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.clickablePath
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.ccbluex.liquidbounce.utils.text.asText
import net.ccbluex.liquidbounce.utils.text.textOf
import net.minecraft.ChatFormatting
import net.minecraft.util.Util
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.DurationUnit
import kotlin.time.measureTime

/**
 * `.clone ...` — train, inspect and stress-test aim clones.
 *
 * The workflow is: record yourself with the CloneRecorder module, `train` a model from that
 * profile, then `eval` it. The evaluation is not a formality — it trains a classifier to separate
 * the clone's output from the recording, which is the same thing a machine-learning anticheat
 * does, so its verdict is the only real answer to "does this pass".
 */
object CommandClone : Command.Factory {

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    private val NAME_PATTERN = Regex("[^a-zA-Z0-9-]")

    override fun createCommand(): Command = CommandBuilder
        .begin("clone")
        .alias("models")
        .hub()
        .subcommand(trainCommand())
        .subcommand(improveCommand())
        .subcommand(reinforceCommand())
        .subcommand(evalCommand())
        .subcommand(evalModeCommand())
        .subcommand(recalibrateCommand())
        .subcommand(infoCommand())
        .subcommand(listCommand())
        .subcommand(deleteCommand())
        .subcommand(reloadCommand())
        .subcommand(browseCommand())
        .build()

    private fun trainCommand(): Command = CommandBuilder
        .begin("train")
        .parameter(modelParameter(existing = false))
        .parameter(profileParameter())
        .parameter(
            ParameterBuilder
                .begin<Int>("epochs")
                .verifiedBy(ParameterBuilder.POSITIVE_INTEGER_VALIDATOR)
                .optional(CloneTrainer.DEFAULT_EPOCHS)
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Int>("hidden")
                .verifiedBy(ParameterBuilder.POSITIVE_INTEGER_VALIDATOR)
                .optional(CloneTrainer.DEFAULT_HIDDEN)
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Float>("stepMemory")
                .verifiedBy(STEP_MEMORY_VALIDATOR)
                .optional()
                .build()
        )
        .suspendHandler {
            val name = args[0] as String
            val profile = args[1] as String
            val epochs = (args.getOrNull(2) as? Int) ?: CloneTrainer.DEFAULT_EPOCHS
            val hidden = (args.getOrNull(3) as? Int) ?: CloneTrainer.DEFAULT_HIDDEN
            val stepMemory = (args.getOrNull(4) as? Float) ?: CloneTrainer.DEFAULT_STEP_MEMORY

            if (NAME_PATTERN.containsMatchIn(name)) {
                throw CommandException("Model name must be alphanumeric with dashes only.".asText())
            }
            if (CloneModels.folderOf(name).exists()) {
                throw CommandException("Model '$name' already exists — use improve instead.".asText())
            }

            runTraining(name, profile, epochs, hidden, stepMemory, continueTraining = false)
        }
        .build()

    private fun improveCommand(): Command = CommandBuilder
        .begin("improve")
        .parameter(modelParameter(existing = true))
        .parameter(
            ParameterBuilder
                .begin<Int>("epochs")
                .verifiedBy(ParameterBuilder.POSITIVE_INTEGER_VALIDATOR)
                .optional(CloneTrainer.DEFAULT_EPOCHS)
                .build()
        )
        .suspendHandler {
            val name = args[0] as String
            val epochs = (args.getOrNull(1) as? Int) ?: CloneTrainer.DEFAULT_EPOCHS

            val metadata = ModelMetadata.load(CloneModels.folderOf(name))
                ?: throw CommandException("Model '$name' not found.".asText())

            // The existing model's width, not the default. DJL loads parameters by name without
            // checking their shape, so a mismatch does not throw — it quietly trains the old,
            // narrower network while reporting the default size everywhere.
            // stepMemory is ignored on improve — the trainer keeps the existing model's lambda.
            runTraining(
                name, metadata.profile, epochs, metadata.hidden,
                CloneTrainer.DEFAULT_STEP_MEMORY, continueTraining = true
            )
        }
        .build()

    /**
     * `.clone reinforce <model> <newName> [klWeight] [iterations]` - fine-tunes an imitation model to
     * hit (see [CloneReinforce]) and fits its clicks in the closed loop.
     *
     * Only the reference's own TRAINING fights are used, so the new model keeps the reference's
     * split and `.clone eval <newName>` scores it on the same hold-out neither of them trained on.
     */
    private fun reinforceCommand(): Command = CommandBuilder
        .begin("reinforce")
        .parameter(modelParameter(existing = true))
        .parameter(
            ParameterBuilder
                .begin<String>("newName")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                .required()
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Float>("klWeight")
                .verifiedBy(KL_WEIGHT_VALIDATOR)
                .optional()
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Int>("iterations")
                .verifiedBy(ParameterBuilder.POSITIVE_INTEGER_VALIDATOR)
                .optional(CloneReinforce.DEFAULT_ITERATIONS)
                .build()
        )
        .suspendHandler {
            val reference = args[0] as String
            val name = args[1] as String
            val klWeight = (args.getOrNull(2) as? Float) ?: CloneReinforce.DEFAULT_KL_WEIGHT
            val iterations = (args.getOrNull(3) as? Int) ?: CloneReinforce.DEFAULT_ITERATIONS

            if (NAME_PATTERN.containsMatchIn(name)) {
                throw CommandException("Model name must be alphanumeric with dashes only.".asText())
            }
            if (CloneModels.folderOf(name).exists()) {
                throw CommandException("Model '$name' already exists.".asText())
            }
            val referenceFolder = CloneModels.folderOf(reference)
            val metadata = ModelMetadata.load(referenceFolder)
                ?: throw CommandException("Model '$reference' not found.".asText())
            val trainKeys = metadata.trainKeys?.toHashSet()
                ?: throw CommandException("Model '$reference' predates the stored split — retrain it first.".asText())
            if (metadata.splitMode == ModelMetadata.SPLIT_TAIL) {
                // Its training fights share their tails with the hold-out, so there is no clean
                // training set to reinforce on - and a profile that small has nothing to gain.
                throw CommandException("Model '$reference' was trained on too few fights to reinforce.".asText())
            }
            requireEngine()

            val training = AimSequence.parse(metadata.profile).filter { it.identity in trainKeys }
            if (training.isEmpty()) {
                throw CommandException("None of the fights '$reference' trained on are on disk any more.".asText())
            }

            chat(
                regular("Reinforcing "), variable(reference), regular(" into "), variable(name),
                regular(" on ${training.size} fights: $iterations iterations, KL weight $klWeight...")
            )
            val folder = CloneModels.folderOf(name)
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    var calibration: CloneReinforce.ClickCalibration? = null
                    val time = measureTime {
                        CloneReinforce.train(
                            reference = referenceFolder,
                            sequences = training,
                            folder = folder,
                            options = CloneReinforce.Options(iterations = iterations, klWeight = klWeight)
                        ) { iteration, _ ->
                            if (iteration.index == 1 || iteration.index % REINFORCE_REPORT_EVERY == 0) {
                                chat(regular("Iteration %d/%d — on target %.1f%% of rollout ticks, %.3f nats from the imitation model"
                                    .format(iteration.index, iterations, iteration.hitRate * 100f, iteration.kl)))
                            }
                        }
                        calibration = CloneReinforce.calibrateClicks(
                            folder,
                            training.shuffled(kotlin.random.Random(metadata.seed)).take(CLICK_CALIBRATION_FIGHTS)
                        )
                    }
                    calibration to time
                }
            }

            outcome.onFailure { error ->
                folder.deleteRecursively()
                chat(markAsError("Reinforcing failed: ${error.localizedMessage}"))
            }.onSuccess { (calibration, time) ->
                CloneModels.reload()
                calibration?.let { chat(regular(it.describe())) }
                chat(regular("Reinforced "), variable(name), regular(" in "),
                    variable(time.toString(DurationUnit.MINUTES, decimals = 2)), regular("."))
                chat(regular("Now run "), variable(".clone eval $name 1"), regular(" — it was optimized at Temperature 1."))
            }
        }
        .build()

    private fun evalCommand(): Command = CommandBuilder
        .begin("eval")
        .parameter(modelParameter(existing = true))
        .parameter(
            ParameterBuilder
                .begin<Float>("temperature")
                .verifiedBy(TEMPERATURE_VALIDATOR)
                .optional()
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<Float>("aimPoint")
                .verifiedBy(AIM_POINT_VALIDATOR)
                .optional()
                .build()
        )
        .suspendHandler {
            val name = args[0] as String
            val folder = CloneModels.folderOf(name)
            val metadata = ModelMetadata.load(folder)
                ?: throw CommandException("Model '$name' not found.".asText())

            // Whatever the Clone smoother is set to, unless the user named one. The clone at 0.8 is
            // a different generator than the clone at 1.0, and a report produced at a default the
            // client is not running describes something nobody is playing.
            val temperature = (args.getOrNull(1) as? Float) ?: liveTemperature()

            // The box centre, which is what the recorder labelled against, unless the user asks for
            // a displaced anchor to find out what the aura's own aim point costs.
            val aimPoint = (args.getOrNull(2) as? Float) ?: CloneEval.CENTRE_AIM_POINT

            requireEngine()
            chat(regular("Evaluating "), variable(name), regular(" against profile "),
                variable(metadata.profile), regular("..."))

            withContext(Dispatchers.Default) {
                runCatching { evaluate(name, folder, metadata, temperature, aimPoint) }
                    .onFailure { error ->
                        chat(markAsError("Evaluation failed: ${error.localizedMessage}"))
                    }
            }
        }
        .build()

    /**
     * The same measurement applied to a hand-written smoother, so "is the clone better than what
     * we already had" has an answer instead of an opinion.
     */
    private fun evalModeCommand(): Command = CommandBuilder
        .begin("evalmode")
        .parameter(smootherParameter())
        .parameter(profileParameter())
        .suspendHandler {
            val name = args[0] as String
            val profile = args[1] as String

            val smooth = smootherNamed(name)
                ?: throw CommandException(
                    "No aim smoother named '$name'. Known: ${smootherNames().joinToString(", ")}."
                        .asText()
                )

            if (smooth.derivesTargetFromEntity || smooth.requiresLiveTick || smooth is CloneAngleSmooth) {
                throw CommandException(
                    if (smooth is CloneAngleSmooth) {
                        "Score the selected Clone model with .clone eval, not .clone evalmode."
                            .asText()
                    } else if (smooth.requiresLiveTick) {
                        ("'${smooth.name}' requires live ticks and cannot be advanced by an offline replay " +
                            "on the same instance.").asText()
                    } else {
                        ("'${smooth.name}' takes its aim point from the live target rather than from the " +
                            "angle it is given, so it cannot be replayed over a recording.").asText()
                    }
                )
            }

            // Every smoother reads live client state as it runs — sensitivity, positions, the
            // player's own rotation — so the evaluation only means anything in a world, and
            // resolving this up front turns a stray null dereference deep in the rollout into an
            // answer the user can act on.
            val anchor = mc.player
                ?: throw CommandException("Join a world first — a smoother needs a live client.".asText())

            requireEngine()
            chat(regular("Evaluating smoother "), variable(smooth.name), regular(" against profile "),
                variable(profile), regular("..."))

            withContext(Dispatchers.Default) {
                runCatching { evaluateSmoother(SmoothAimSource(smooth, anchor), profile) }
                    .onFailure { error ->
                        chat(markAsError("Evaluation failed: ${error.localizedMessage}"))
                    }
            }
        }
        .build()

    /**
     * Fits the attack head's sampling bias on a model that already exists.
     *
     * Calibration needs nothing from training — a forward pass over held-out ticks and a bisection
     * — so a model that took twenty minutes to produce does not have to be produced again to get
     * one. Safe to repeat: the fit reads the head's raw logits, never the corrected ones.
     */
    private fun recalibrateCommand(): Command = CommandBuilder
        .begin("recalibrate")
        .parameter(modelParameter(existing = true))
        .parameter(profileParameter(required = false))
        .suspendHandler {
            val name = args[0] as String
            val folder = CloneModels.folderOf(name)
            val metadata = ModelMetadata.load(folder)
                ?: throw CommandException("Model '$name' not found.".asText())
            val profile = (args.getOrNull(1) as? String) ?: metadata.profile

            requireEngine()
            chat(regular("Recalibrating "), variable(name), regular(" against profile "),
                variable(profile), regular("..."))

            val outcome = withContext(Dispatchers.Default) {
                runCatching { recalibrate(name, folder, metadata, profile) }
            }
            outcome.onFailure { error ->
                chat(markAsError("Recalibration failed: ${error.localizedMessage}"))
            }.onSuccess {
                CloneModels.reload()
            }
        }
        .build()

    private fun infoCommand(): Command = CommandBuilder
        .begin("info")
        .parameter(modelParameter(existing = true))
        .handler {
            val name = args[0] as String
            val metadata = ModelMetadata.load(CloneModels.folderOf(name))
                ?: throw CommandException("Model '$name' not found.".asText())

            chat(regular("Model "), variable(name), regular(":"))
            line("Profile", metadata.profile)
            line("Trained", TIMESTAMP.format(Instant.ofEpochMilli(metadata.createdAt)))
            line("Architecture", "GRU ${metadata.hidden}x${metadata.layers}, window ${metadata.window}")
            line("Data", "${metadata.sampleCount} ticks in ${metadata.sequenceCount} sequences")
            line(
                "Loss",
                "train ${formatLoss(metadata.trainLoss)}, validation ${formatLoss(metadata.validationLoss)}"
            )
            line(
                "Kept epoch",
                if (metadata.bestEpoch > 0) {
                    "${metadata.bestEpoch} of ${metadata.epochs}"
                } else {
                    "last (${metadata.epochs}) — no validation data to choose by"
                }
            )
            line(
                "Still ticks",
                "yaw %.1f%%, pitch %.1f%%".format(
                    metadata.zeroFraction[0] * 100f,
                    metadata.zeroFraction[1] * 100f
                )
            )
            line(
                "Swing rate",
                "%.1f%% of ticks (%.1f cps)".format(
                    metadata.attackRate * 100f,
                    metadata.attackRate * 20f
                )
            )
            line("Attack bias", describeBias(metadata))
            line("Max step", "yaw %.2f°, pitch %.2f°".format(metadata.maxStep[0], metadata.maxStep[1]))
            line(
                "Covers",
                "angles up to %.0f°, ranges up to %.1f blocks".format(
                    metadata.coverageAngle,
                    metadata.coverageDistance
                )
            )
            line("Sensitivity", "%.3f (gcd %.4f°)".format(metadata.sensitivity, metadata.trainGcd))
            line("Discriminator", metadata.evalAuc?.let { "AUC %.3f".format(it) } ?: "not evaluated")
        }
        .build()

    private fun listCommand(): Command = CommandBuilder
        .begin("list")
        .handler {
            val folders = modelsFolder.listFiles { file -> CloneModel.isModelFolder(file) }
                ?.sortedBy { it.name }
                .orEmpty()

            if (folders.isEmpty()) {
                chat(regular("No models yet. Record a profile, then run "), variable(".clone train"), regular("."))
            } else {
                chat(regular("Models:"))
                for (folder in folders) {
                    val metadata = ModelMetadata.load(folder)
                    val loaded = if (CloneModels[folder.name] != null) "loaded" else "on disk"
                    line(
                        folder.name,
                        "profile ${metadata?.profile ?: "?"}, ${metadata?.sampleCount ?: 0} ticks, $loaded"
                    )
                }
            }

            val profiles = AimSequence.profiles()
            if (profiles.isEmpty()) {
                chat(regular("No recorded profiles. Enable the "), variable("CloneRecorder"), regular(" module."))
                return@handler
            }

            chat(regular("Profiles:"))
            for (name in profiles) {
                val parsed = AimSequence.parseDetailed(name)
                val files = AimSequence.folderOf(name)
                    .listFiles { file -> file.isFile && file.extension == "json" }
                    ?.size ?: 0

                // Dropped recordings are called out by name: a shared dataset made with a
                // different build is silently unusable otherwise, and "it just did not train on
                // it" is a hard thing to notice.
                val dropped = buildList {
                    if (parsed.wrongVersion > 0) {
                        add("${parsed.wrongVersion} from a different build")
                    }
                    if (parsed.tooShort > 0) {
                        add("${parsed.tooShort} too short")
                    }
                    if (parsed.practice.isNotEmpty()) {
                        add("${parsed.practice.size} practice vs a dummy " +
                            "(${parsed.practice.sumOf { it.ticks.size }} ticks)")
                    }
                }

                val ticks = parsed.sequences.sumOf { it.ticks.size }
                val withTarget = parsed.sequences.sumOf { s -> s.ticks.count { it.hasTarget } }

                line(
                    name,
                    "%d files, %d sequences, %d ticks, %.0f%% with a target%s".format(
                        files,
                        parsed.sequences.size,
                        ticks,
                        if (ticks == 0) 0f else withTarget.toFloat() / ticks * 100f,
                        if (dropped.isEmpty()) "" else " — dropped: ${dropped.joinToString(", ")}"
                    )
                )
            }
        }
        .build()

    private fun deleteCommand(): Command = CommandBuilder
        .begin("delete")
        .parameter(modelParameter(existing = true))
        .handler {
            val name = args[0] as String
            val folder = CloneModels.folderOf(name)
            if (!folder.isDirectory) {
                throw CommandException("Model '$name' not found.".asText())
            }

            CloneModels.unload()
            folder.deleteRecursively()
            CloneModels.load()

            chat(regular("Deleted model "), variable(name), regular("."))
        }
        .build()

    private fun reloadCommand(): Command = CommandBuilder
        .begin("reload")
        .handler {
            CloneModels.reload()
            chat(regular("Reloaded "), variable("${CloneModels.names.size}"), regular(" model(s)."))
        }
        .build()

    private fun browseCommand(): Command = CommandBuilder
        .begin("browse")
        .handler {
            Util.getPlatform().openFile(modelsFolder)
            chat(regular("Models: "), clickablePath(modelsFolder))
            chat(regular("Datasets: "), clickablePath(datasetsFolder))
        }
        .build()

    private suspend fun runTraining(
        name: String,
        profile: String,
        epochs: Int,
        hidden: Int = CloneTrainer.DEFAULT_HIDDEN,
        stepMemory: Float = CloneTrainer.DEFAULT_STEP_MEMORY,
        continueTraining: Boolean
    ) {
        requireEngine()

        chat(regular("Training "), variable(name), regular(" on profile "), variable(profile), regular("..."))

        val outcome = withContext(Dispatchers.Default) {
            runCatching {
                val progress = CloneTrainer.Progress(
                    onMessage = { message -> chat(regular(message)) },
                    onProgress = ::showProgress
                )

                var result: CloneTrainer.Result? = null
                val time = measureTime {
                    result = CloneTrainer.train(
                        name = name,
                        profile = profile,
                        epochs = epochs,
                        hidden = hidden,
                        continueFrom = CloneModels.folderOf(name).takeIf { continueTraining },
                        stepMemory = stepMemory,
                        progress = progress
                    )
                }
                result!! to time
            }
        }

        outcome.onFailure { error ->
            chat(markAsError("Training failed: ${error.localizedMessage}"))
        }.onSuccess { (result, time) ->
            // Selector rebuilding and old-session disposal touch live config/UI state and must
            // happen back on the command's game-thread context, never the training dispatcher.
            CloneModels.reload()

            chat(
                regular("Trained "),
                variable(name),
                regular(" on "),
                variable("${result.scoredTicks}"),
                regular(" scored ticks ("),
                variable("${result.stepsPerEpoch}"),
                regular(" chunks per epoch) in "),
                variable(time.toString(DurationUnit.MINUTES, decimals = 2)),
                regular(".")
            )
            chat(regular("Now run "), variable(".clone eval $name"), regular(" to see if it holds up."))
        }
    }

    /** Scores a model over its held-out split at [temperature] and prints what came out. */
    private fun evaluate(
        name: String,
        folder: java.io.File,
        metadata: ModelMetadata,
        temperature: Float,
        aimPoint: Float
    ) {
        // Score at the live NoiseCorrelation too, for the same reason as Temperature: the clone at
        // ar=0.45 is a different generator than at 0, and a report has to describe the one being run.
        val noiseCorrelation = liveNoiseCorrelation()
        val holdout = holdoutOf(metadata.profile, metadata) ?: return

        val model = CloneModel.load(folder)
        if (model == null) {
            chat(markAsError("Model '$name' could not be loaded."))
            return
        }

        model.use {
            val report = CloneEval.evaluate(
                model = model,
                sequences = holdout.sequences,
                temperature = temperature,
                aimPoint = aimPoint,
                noiseCorrelation = noiseCorrelation
            )
            if (report == null) {
                chat(markAsError("Not enough held-out data to evaluate."))
                return
            }

            chat(regular("Evaluated on "), variable("${report.ticks}"), regular(" ticks from "),
                variable("${report.sequences}"), regular(" held-out sequences."))
            CloneReportPrinter.print(report, holdout.profileTicks)

            val auc = report.discriminator?.auc
            if (auc != null && !auc.isNaN()) {
                metadata.evalAuc = auc
                // A tree patch rather than metadata.save(): a full serialize of the in-memory
                // object would drop any field a newer build has written into the file.
                ModelMetadata.patch(folder) { root -> root.addProperty(FIELD_EVAL_AUC, auc) }
            }
        }
    }

    /** The held-out slice of a profile, and how many ticks the whole of it holds. */
    private class Holdout(val sequences: List<AimSequence>, val profileTicks: Int)

    /**
     * The held-out sequences for a model, or `null` after saying in chat why there are none.
     *
     * Membership comes from the split fingerprints stored in the model's metadata, so it stays the
     * training-time hold-out even after the profile has been recorded into — re-running the seeded
     * split over a grown file set silently shuffles trained sequences into the "hold-out", and a
     * score on training data always looks excellent. Sequences recorded after training are
     * included and announced: the model has never seen them. Only a model that predates the stored
     * split falls back to the seed, with a warning about what that means.
     */
    private fun holdoutOf(profile: String, metadata: ModelMetadata): Holdout? {
        val parsed = AimSequence.parseDetailed(profile)
        val sequences = parsed.sequences
        if (sequences.isEmpty()) {
            chat(markAsError("Profile '$profile' has no usable recordings."))
            return null
        }
        if (parsed.practice.isNotEmpty()) {
            chat(
                regular("Leaving out "),
                variable("${parsed.practice.size}"),
                regular(" practice sequence(s) against a dummy — the clone is scored on real fights.")
            )
        }

        // Practice a model was trained on is set aside, not lost: it must not be reported as
        // missing from disk.
        val selection = CloneTrainer.holdoutFor(sequences, metadata, setAside = parsed.practice)
        val holdout: List<AimSequence>

        if (selection == null) {
            chat(
                regular(
                    "This model predates the stored train/hold-out split, so it is rebuilt from " +
                        "the seed — only valid if nothing was recorded into '$profile' since " +
                        "training. Retrain to pin the split."
                )
            )
            holdout = CloneTrainer.split(sequences, metadata.seed).validation
        } else {
            if (selection.missing > 0) {
                chat(
                    regular(
                        "${selection.missing} training-time sequence(s) are no longer on disk — " +
                            "the hold-out is what remains of it."
                    )
                )
            }
            if (selection.unseen > 0) {
                chat(
                    regular("Including "),
                    variable("${selection.unseen}"),
                    regular(" sequence(s) recorded after training — fully unseen data.")
                )
            }
            holdout = selection.sequences
        }

        if (holdout.isEmpty()) {
            chat(markAsError("Profile '$profile' is too short to hold anything out."))
            chat(regular("Record at least a few minutes in one go, then train again."))
            return null
        }

        return Holdout(holdout, sequences.sumOf { it.ticks.size })
    }

    /**
     * Fits the attack head's bias against the held-out split and writes it into the model.
     *
     * The reload afterwards is not cosmetic: a loaded model reads its bias once, when it is
     * constructed, so without it the clone in play would keep sampling off the old calibration
     * while the file on disk said otherwise.
     */
    private fun recalibrate(
        name: String,
        folder: java.io.File,
        metadata: ModelMetadata,
        profile: String
    ) {
        val holdout = holdoutOf(profile, metadata) ?: return

        val fit = CloneCalibration.calibrate(folder, holdout.sequences)
        if (fit == null) {
            chat(markAsError("Model '$name' could not be loaded."))
            return
        }

        if (!fit.isFitted) {
            line("Attack bias", "%+.2f logits — no held-out ticks, so this is only the class weight"
                .format(fit.bias))
            return
        }

        line("Attack bias", "%+.2f logits over %d held-out ticks".format(fit.bias, fit.ticks))
        line(
            "Swing rate",
            ("the head wanted %.2f%% of those ticks; the player swung on %.2f%%, which is what it " +
                "produces with the bias").format(fit.rateBefore * 100f, fit.observedRate * 100f)
        )
        chat(regular("Run "), variable(".clone eval $name"), regular(" to see what it changed."))
    }

    /**
     * The whole profile, not a held-out slice of it: nothing about a hand-written smoother was
     * fitted to this recording, so every tick of it is unseen data and the adversaries want all of
     * them.
     */
    private fun evaluateSmoother(source: SmoothAimSource, profile: String) {
        val sequences = AimSequence.parse(profile)
        if (sequences.isEmpty()) {
            chat(markAsError("Profile '$profile' has no usable recordings."))
            return
        }

        val report = CloneEval.evaluate(source, sequences)
        if (report == null) {
            chat(markAsError("Not enough recorded data to evaluate."))
            return
        }

        chat(regular("Evaluated "), variable(report.source), regular(" on "),
            variable("${report.ticks}"), regular(" ticks from "), variable("${report.sequences}"),
            regular(" sequences."))
        line(
            "Reproducibility",
            "the classifiers are seeded, the smoother's own draws are not — repeat runs move a little"
        )
        line(
            "Replayed geometry",
            "the target's range and hitbox are the recording's, so a smoother that modulates by " +
                "range is scored on the ranges the player actually fought at. The attack cooldown " +
                "is not: it comes off your own weapon as the run goes past, so whatever a smoother " +
                "does with it here is not a measurement"
        )
        CloneReportPrinter.print(report, sequences.sumOf { it.ticks.size })
    }

    private fun requireEngine() {
        if (!DeepLearningEngine.isInitialized) {
            throw CommandException("Deep learning engine is not available.".asText())
        }
    }

    private fun formatLoss(value: Float?) = value?.let { "%.4f".format(it) } ?: "n/a"

    /**
     * A model with no stored bias gets no correction at all, so it says that rather than printing
     * a zero, which would read as a calibration that found nothing to fix.
     */
    private fun describeBias(metadata: ModelMetadata): String {
        val bias = metadata.attackBias
            ?: return "none — this model predates calibration; run .clone recalibrate on it"

        val before = metadata.attackBiasRate
        val target = metadata.attackBiasTarget
        if (before == null || target == null) {
            return "%+.2f logits — the class weight alone; there was no held-out data to fit against"
                .format(bias)
        }

        return "%+.2f logits — the head wanted %.2f%% of the held-out ticks, fitted to %.2f%%".format(
            bias,
            before * 100f,
            target * 100f
        )
    }

    private fun showProgress(epoch: Int, epochs: Int, fraction: Float) {
        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
        val filled = percent / 4

        val bar = textOf(
            regular("Training epoch "),
            variable("$epoch/$epochs"),
            regular(" — "),
            variable("$percent%"),
            regular("\n"),
            "[".asPlainText(ChatFormatting.GRAY),
            "█".repeat(filled).asPlainText(ChatFormatting.GREEN),
            "░".repeat(25 - filled).asPlainText(ChatFormatting.DARK_GRAY),
            "]".asPlainText(ChatFormatting.GRAY)
        )

        mc.execute {
            mc.gui.setOverlayMessage(bar, false)
        }
    }

    private fun modelParameter(existing: Boolean) = ParameterBuilder
        .begin<String>("name")
        .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
        .autocompletedFrom {
            if (existing) {
                modelsFolder.listFiles { file -> CloneModel.isModelFolder(file) }?.map { it.name }
            } else {
                emptyList()
            }
        }
        .required()
        .build()

    private fun profileParameter(required: Boolean = true) = ParameterBuilder
        .begin<String>("profile")
        .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
        .autocompletedFrom { AimSequence.profiles() }
        .apply { if (required) required() else optional() }
        .build()

    /**
     * The Temperature the Clone smoother is set to right now, which is what the client will be
     * running at. Its own default only if the smoother is somehow not built yet.
     */
    private fun liveTemperature(): Float = liveClone()?.temperature ?: DEFAULT_TEMPERATURE

    /** The NoiseCorrelation the Clone smoother is set to right now — the same live-generator logic. */
    private fun liveNoiseCorrelation(): Float = liveClone()?.noiseCorrelation ?: 0f

    /**
     * The clone smoother the client is actually running, read from the *selected* mode so a report
     * scores the Temperature that is deployed rather than a default nobody chose. Falls back to the
     * first registered [CloneAngleSmooth] when none is selected, so a report is still possible from
     * outside combat. (CloneVrock, the second variant this used to disambiguate, was removed on
     * 2026-09-19.)
     */
    private fun liveClone(): CloneAngleSmooth? =
        (KillAuraRotationsValueGroup.activeAngleSmooth as? CloneAngleSmooth)
            ?: smoothers().filterIsInstance<CloneAngleSmooth>().firstOrNull()

    private const val DEFAULT_TEMPERATURE = 1f

    /** Property name in `metadata.json`; must match [ModelMetadata.evalAuc]. */
    private const val FIELD_EVAL_AUC = "evalAuc"

    /** Progress lines of `.clone reinforce`: often enough to see it move, rarely enough to read. */
    private const val REINFORCE_REPORT_EVERY = 25

    /** Training fights the closed-loop click fit rolls over: ~10k ticks, a few seconds per probe. */
    private const val CLICK_CALIBRATION_FIGHTS = 40

    private val KL_WEIGHT_VALIDATOR: Parameter.Verificator<Float> = Parameter.Verificator { text ->
        val value = text.toFloatOrNull()
        when {
            value == null -> Parameter.Verificator.Result.Error("'$text' is not a number")
            value > 0f && value <= MAX_KL_WEIGHT -> Parameter.Verificator.Result.Ok(value)
            else -> Parameter.Verificator.Result.Error("KL weight must be above 0 and at most $MAX_KL_WEIGHT")
        }
    }

    /** Far past where the reinforced model stops moving away from the imitation one at all. */
    private const val MAX_KL_WEIGHT = 10f

    private val TEMPERATURE_VALIDATOR: Parameter.Verificator<Float> = Parameter.Verificator { text ->
        val value = text.toFloatOrNull()
        when {
            value == null -> Parameter.Verificator.Result.Error("'$text' is not a number")
            value in 0f..MAX_TEMPERATURE -> Parameter.Verificator.Result.Ok(value)
            else -> Parameter.Verificator.Result.Error("Temperature must be 0 to $MAX_TEMPERATURE")
        }
    }

    /** Upper bound of the Temperature slider on the smoother. */
    private const val MAX_TEMPERATURE = 1.5f

    /**
     * v7 step-memory lambda for `.clone train`: the AR(1) damping = the target step autocorrelation
     * (momentum). 0 is the absolute-step model; 1 is the undamped integrator that spins; ~0.5-0.6
     * matches a real player. Must be in [0, 1).
     */
    private val STEP_MEMORY_VALIDATOR: Parameter.Verificator<Float> = Parameter.Verificator { text ->
        val value = text.toFloatOrNull()
        when {
            value == null -> Parameter.Verificator.Result.Error("'$text' is not a number")
            value in 0f..0.95f -> Parameter.Verificator.Result.Ok(value)
            else -> Parameter.Verificator.Result.Error("StepMemory (momentum) must be 0 to 0.95")
        }
    }

    /**
     * Height on the hitbox to point the clone at, as a fraction from its feet. `0.5` is the box
     * centre the recorder labelled against; anything else measures what the aura's own aim point
     * costs the model — see [CloneEval.CENTRE_AIM_POINT].
     */
    private val AIM_POINT_VALIDATOR: Parameter.Verificator<Float> = Parameter.Verificator { text ->
        val value = text.toFloatOrNull()
        when {
            value == null -> Parameter.Verificator.Result.Error("'$text' is not a number")
            value in 0f..1f -> Parameter.Verificator.Result.Ok(value)
            else -> Parameter.Verificator.Result.Error("AimPoint is a hitbox fraction, 0 to 1")
        }
    }

    private fun smootherParameter() = ParameterBuilder
        .begin<String>("smoother")
        .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
        .autocompletedFrom { smootherNames() }
        .required()
        .build()

    /**
     * KillAura's rotation config, because it is the one built with `combatSpecific` and therefore
     * the only one carrying every smoother the client has. Its live instance rather than a fresh
     * one: the sliders the user tuned in the menu are part of what is being scored, and a smoother
     * evaluated at its defaults would answer a question nobody asked.
     */
    private fun smoothers() = KillAuraRotationsValueGroup.angleSmoothModes

    private fun smootherNames() = smoothers().map { it.name }

    private fun smootherNamed(name: String) = smoothers().firstOrNull { smooth ->
        smooth.tag.equals(name, ignoreCase = true) ||
            smooth.tagAliases.any { it.equals(name, ignoreCase = true) }
    }
}
