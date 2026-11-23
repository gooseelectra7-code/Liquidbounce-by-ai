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
import ai.djl.engine.Engine
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.Shape
import ai.djl.nn.Activation
import ai.djl.training.DefaultTrainingConfig
import ai.djl.training.optimizer.Adam
import ai.djl.training.tracker.Tracker
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.FEATURES
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.HEADS
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.LABELS
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_ATTACKED
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_MOVE_STATE
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_PREV_DX
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema.L_PREV_DY
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import net.ccbluex.liquidbounce.deeplearn.clone.eval.CloneEval
import net.ccbluex.liquidbounce.deeplearn.clone.eval.HitboxHits
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.random.asJavaRandom

/**
 * Fine-tunes an imitation clone to HIT, while a KL penalty keeps it the player's hand.
 *
 * Imitation alone has no notion of a hit: every recorded step, landing or not, is simply "what the
 * player did", and the clone falls short of the player because its own small errors compound into
 * states the recording never shows. This adds the missing signal the way RLHF does for language
 * models - a reward, and a penalty on drifting from the reference policy:
 *
 * - The environment is the evaluation rollout ([CloneEval.fillFeatures]): the target moves as
 *   recorded, the clone aims on its own, and a tick's reward is whether the NEXT row's box is on
 *   the crosshair ([HitboxHits.entersBox], the recorder's own test). The recorded opponent does not
 *   react to the clone - a limit shared with every offline score in this package.
 * - Per tick the reward is `hit - klWeight * (log pi(a) - log pi_ref(a))`, the sampled KL estimate
 *   against the imitation model the run started from; returns are discounted with [Options.discount]
 *   and standardized into advantages, and the policy gradient is `advantage * NLL(own step)`.
 * - The click head gets no reward at all (a hit is an aim property) and is held where imitation
 *   left it by the exact Bernoulli KL to the reference's click probability on the same states.
 * - The imitation game ([Options.discriminatorWeight], on by default): a discriminator retrained on
 *   every batch against the player's own windows scores each tick, and a second, separately
 *   standardized policy gradient pushes the whole movement - step and move/still choice - towards
 *   what it cannot tell apart. With the hit reward alone the adversaries scored the clone 0.83 / 0.89
 *   against the imitation model's 0.78 / 0.84; this brings it back to about 0.80 / 0.83 for about a
 *   point of hits ([DEFAULT_DISCRIMINATOR_WEIGHT]).
 *
 * Rollouts sample at Temperature 1 - the policy-gradient estimate is only unbiased for the
 * distribution it samples from - so what this optimizes is the clone as deployed at Temperature 1.
 *
 * Headless like [CloneTrainer.trainOn]: needs DJL only, never the client.
 */
object CloneReinforce {

    /** 150 iterations of 64 x 128 ticks: about five minutes on a desktop CPU, measured. */
    const val DEFAULT_ITERATIONS = 150

    /**
     * β. Measured 2026-09-29 on the `default` profile (h48 reference, 35 held-out fights, Temperature
     * 1): 0.1 without the frozen move/still choice ended at 75 % hits but 10.7 % still ticks and
     * tree AUC 0.96; with the choice frozen, 0.3 gave 67.4 % hits at 19.0 % still (the player 19.2)
     * and 1.0 gave 59.7 % at momentum 0.645 (the player 0.649), both with adversary AUCs within
     * noise of the reference's. The user picks where on that line to sit; this is the middle.
     */
    const val DEFAULT_KL_WEIGHT = 0.3f

    /**
     * Measured 2026-09-29 (h48 reference, 35 held-out fights, Temperature 1, adversaries trained
     * from scratch on 5 seeds each): the plain hit reward (rl02) scored AUCs 0.83 / 0.89 (GRU window
     * classifier / trees) at 67.2 % hits. With this term at 1, [DEFAULT_WARM_START] and the pause
     * anchor at [GAIL_STATE_KL_WEIGHT], two training seeds scored 0.80 / 0.81 and 0.79 / 0.85 at 66.3
     * and 65.8 % - about where the imitation model itself sits (0.78 / 0.84, at 49 %). Every variant
     * tried (weight 0.5 and 1, anchor 1 and 0.1, cold and warm start) landed below rl02 on both
     * adversaries, by 0.02-0.08. A real but modest gain: the clone stays separable (see the KDoc
     * of [CloneDiscriminator] for what it still gives away).
     */
    const val DEFAULT_DISCRIMINATOR_WEIGHT = 1f

    /** The pause anchor while the imitation game is on; see [Options.stateKlWeight]. */
    const val GAIL_STATE_KL_WEIGHT = 0.1f

    /** See [Options.warmStart]. */
    const val DEFAULT_WARM_START = 32

    class Options(
        val iterations: Int = DEFAULT_ITERATIONS,
        val lanes: Int = 64,
        /** Ticks per rollout window; one reward per tick. */
        val window: Int = 128,
        val learningRate: Float = 1e-4f,
        /** Reward lost per nat the policy moved away from the reference on a tick. */
        val klWeight: Float = DEFAULT_KL_WEIGHT,
        /** Weight of the exact click KL that keeps the swings where imitation put them. */
        val attackKlWeight: Float = 1f,
        /** Return discount; 0.9 is a horizon of about ten ticks, half a second. */
        val discount: Float = 0.9f,
        /**
         * Weight of the imitation-game term (GAIL, see [CloneDiscriminator]): a second policy
         * gradient whose reward is the discriminator's per-tick "this looks like the player" logit.
         * `0` turns the discriminator off entirely. It acts on the step AND on the move/still choice
         * - pauses are among the features adversaries separate clones on first, so an anti-detection
         * term that could not touch them would miss the point - while [stateKlWeight] still anchors
         * that choice to the reference.
         */
        val discriminatorWeight: Float = DEFAULT_DISCRIMINATOR_WEIGHT,
        /**
         * Keep the HIT reward off the move/still choice: only the exact categorical KL to the
         * reference and, when it is on, the imitation game reach it. Measured on the first run (hit
         * reward on everything): the clone's still ticks fell from ~19.5 % to 13.6 % within 50
         * iterations - standing still never earns a hit - and the share of still ticks is one of the
         * features the adversaries separate clones on first.
         */
        val freezeStateChoice: Boolean = true,
        /**
         * Weight of that categorical KL. 1 without the imitation game - the measured recipe, where
         * nothing but drift in the shared GRU moves the choice - and [GAIL_STATE_KL_WEIGHT] with it:
         * at 1 the choice moved 0.0005 nats a tick in 150 iterations, too little for the
         * discriminator to change any pause.
         */
        val stateKlWeight: Float = if (discriminatorWeight > 0f) GAIL_STATE_KL_WEIGHT else 1f,
        /**
         * Weight of a dense tracking term added to the hit reward: how much closer to where the box
         * is on the NEXT row this step put the crosshair, compared with not moving at all, in
         * half-box units OUTSIDE the box (clipped to ±1). Zero inside the box, so it never pays
         * for centring - that would teach a centre lock, which the aim-point adversaries read.
         * The hit alone is a sparse, all-or-nothing signal; this is the cheap form of "train the
         * tracking directly" without back-propagating through the rollout.
         */
        val progressWeight: Float = 0f,
        /**
         * Hold the clone's hit rate to the PLAYER'S, range by range, instead of paying every hit the
         * same. See [RangeMatch] for the mechanism.
         *
         * Why: the flat reward pays each tick on the box alike, and close ticks are both the commonest
         * and the easiest (the box is widest there), so that is where the policy spends its gains.
         * Measured 2026-09-30 on `8gail` (player / clone, share of ticks on the box by range, 35
         * held-out fights): 0-1 72.9 / 83.4, 1-2 66.7 / 73.1, 3-4 59.6 / 57.6, 4-5 39.9 / 34.6,
         * 5-6 35.4 / 23.8. Live AimLog against the player's own recordings on the same server read
         * the same way (space-times 0-1 80-90 % against 77.6, 3-4 44-62 against 64.9): better than
         * the hand where it is easy, worse at the edge of reach, where an exchange's first hit lands.
         *
         * **Off: measured the same day and it did not move the held-out profile.** From the user's
         * model `8`, 121 held-out fights, 5 adversary seeds: player 72.6 / 65.7 / 67.7 / 60.3 / 43.3
         * / 34.4 (0-1 .. 5-6), the default recipe (`8gail`) 79.4 / 71.2 / 67.6 / 57.2 / 36.5 / 21.7,
         * this on 78.3 / 69.9 / 67.2 / 56.5 / 37.8 / 20.5, tree AUC 0.936 against 0.920. The
         * TRAINING rollouts did converge on the targets (3-4 0.59-0.61 against 0.63), but the weights
         * sat on their bounds for most of the run (-1 at 0-2 blocks, +4 at 3-6) - under the KL
         * anchor the policy cannot trade close-range precision for reach in 150 iterations, and what
         * it gains on the training fights does not carry to fights it has not seen. A second seed
         * was trained and never scored (the run was killed for system memory), so this is ONE
         * scored run. The gap is the imitation model's to close: it starts at 46 % at 3-4 blocks.
         */
        val rangeMatching: Boolean = false,
        val discriminatorHidden: Int = 48,
        val discriminatorLearningRate: Float = 1e-3f,
        /** Discriminator steps per policy step: more makes it the stronger player of the game. */
        val discriminatorSteps: Int = 3,
        /**
         * Discriminator steps on the REFERENCE's own rollouts before the policy moves at all. Without
         * them the first run's discriminator still scored the player 0.51 and the clone 0.52 after
         * 50 iterations - one small step per iteration never learned to tell them apart, so its
         * reward was pure noise. The offline adversary it has to anticipate trains 40 epochs.
         */
        val discriminatorWarmup: Int = 400,
        /** Rollout batches pooled for that warm-up, cycled through. */
        val discriminatorWarmupBatches: Int = 8,
        /** The logit reward is clipped to ±this, so one confident verdict cannot dominate a batch. */
        val discriminatorClip: Float = 5f,
        /** Leading ticks of every window neither scored nor trained on: too little history to judge. */
        val discriminatorBurnIn: Int = 16,
        /**
         * Ticks of the player's own recording fed to the policy, teacher-forced, before every
         * rollout window, so the window opens mid-fight: with the step, the swing and the GRU state
         * the player's aim had there. Without it every window opens from rest - no momentum, no
         * target motion, a blank memory - which in a fight happens once per engagement, and here
         * once per window. `0` is the cold start; windows closer than this to their fight's start
         * get what there is.
         *
         * Measured, and not for the reason it was added: the discriminator did NOT lean on the cold
         * start (warm windows it told apart as well or better - 0.85 / 0.24 against 0.76 / 0.25 at
         * the end of its warm-up). What it bought is about a point of hits (65.9 against 64.8 %
         * with the anchor at 1), and the best adversary scores together with the loose anchor.
         * The plain hit-reward recipe (rl02) was measured with a cold start.
         */
        val warmStart: Int = DEFAULT_WARM_START,
        val seed: Long = CloneTrainer.DEFAULT_SEED
    )

    class Iteration(
        val index: Int,
        /** Share of rollout ticks whose next row was on the box. */
        val hitRate: Float,
        /** Mean sampled `log pi - log pi_ref` of the rewarded part, nats per tick. */
        val kl: Float,
        val attackKl: Float,
        val loss: Float,
        /** Mean exact KL of the move/still choice; 0 when it is not frozen. */
        val stateKl: Float = 0f,
        /** Mean P(player) the discriminator gave the player's / the clone's ticks; NaN when it is off. */
        val discriminatorReal: Float = Float.NaN,
        val discriminatorFake: Float = Float.NaN,
        /** [Options.rangeMatching]'s state this iteration; null when it is off. */
        val ranges: RangeMatch.Snapshot? = null
    )

    /**
     * Fine-tunes the model in [reference] on [sequences] and writes the result into [folder].
     *
     * [onIteration] sees every iteration together with a live view of the policy, so a caller can
     * score it mid-run; the view samples from the current weights and must not be kept.
     */
    @Suppress("LongMethod")
    fun train(
        reference: File,
        sequences: List<AimSequence>,
        folder: File,
        options: Options = Options(),
        /** Sees the discriminator's warm-up: step index and how it scored both classes before it. */
        onWarmup: (Int, CloneDiscriminator.Update) -> Unit = { _, _ -> },
        onIteration: (Iteration, CloneModel) -> Unit = { _, _ -> }
    ): ModelMetadata {
        val metadata = checkNotNull(ModelMetadata.load(reference)) { "No compatible model in $reference" }
        val normalization = metadata.normalization()
        val objective = CloneLoss(stepMemory = metadata.stepMemory)
        val lanes = options.lanes
        val window = options.window

        val eligible = sequences.filter { it.ticks.size > window }
        require(eligible.isNotEmpty()) { "No sequence is longer than a $window-tick window" }

        Engine.getInstance().setRandomSeed(options.seed.toInt())
        val random = Random(options.seed)
        val sampling = random.asJavaRandom()

        val referenceModel = checkNotNull(CloneModel.load(reference))
        val net = CloneNet(metadata.hidden, metadata.layers)
        val policy = Model.newInstance("reinforce")
        policy.block = net

        // The player's side of the imitation game: their own aim over the same fights, one stream
        // per eligible sequence, sampled into windows of the rollout's length every iteration.
        val playerStreams = if (options.discriminatorWeight > 0f) {
            eligible.map { CloneDiscriminator.playerStream(it.ticks, it.gcd) }
        } else {
            emptyList()
        }
        val discriminator = if (options.discriminatorWeight > 0f) {
            CloneDiscriminator(
                options.discriminatorHidden,
                options.discriminatorLearningRate,
                CloneDiscriminator.scaleOf(playerStreams),
                options.seed + 1
            )
        } else {
            null
        }

        try {
            policy.load(reference.toPath(), CloneModel.PARAMETER_PREFIX)
            val config = DefaultTrainingConfig(objective).optOptimizer(
                Adam.builder().optLearningRateTracker(Tracker.fixed(options.learningRate)).build()
            )

            policy.newTrainer(config).use { trainer ->
                // Parameters loaded above are already initialized, so this only sets up the trainer.
                trainer.initialize(Shape(lanes.toLong(), window.toLong(), FEATURES.toLong()))
                val view = CloneModel.wrapping("reinforce", metadata, policy, net)

                val rows = FloatArray(lanes * window * FEATURES)
                val labels = FloatArray(lanes * window * LABELS)
                val rewards = FloatArray(lanes * window)
                // The raw hit and the range bucket of every tick, for the hit rate and for RangeMatch.
                val hits = FloatArray(lanes * window)
                val ranges = IntArray(lanes * window)
                val rangeMatch = if (options.rangeMatching) RangeMatch.of(eligible) else null
                val cloneStream = discriminator?.let { FloatArray(lanes * window * CloneDiscriminator.CHANNELS) }
                val realStream = discriminator?.let { FloatArray(lanes * window * CloneDiscriminator.CHANNELS) }

                if (discriminator != null && realStream != null && options.discriminatorWarmup > 0) {
                    // The clone the discriminator has to learn to see first is the reference itself.
                    val pool = Array(options.discriminatorWarmupBatches.coerceAtLeast(1)) {
                        val batchStream = FloatArray(lanes * window * CloneDiscriminator.CHANNELS)
                        labels.fill(0f)
                        for (lane in 0 until lanes) {
                            val sequence = eligible[random.nextInt(eligible.size)]
                            val start = random.nextInt(sequence.ticks.size - window)
                            rollout(
                                view, sequence, start, lane, window, normalization, sampling,
                                options.progressWeight, options.warmStart, rows, labels, rewards, hits, ranges, batchStream
                            )
                        }
                        batchStream
                    }
                    for (step in 0 until options.discriminatorWarmup) {
                        samplePlayerWindows(eligible, playerStreams, lanes, window, random, realStream)
                        val update = discriminator.update(
                            realStream, pool[step % pool.size], lanes, window, options.discriminatorBurnIn
                        )
                        onWarmup(step, update)
                    }
                }

                for (iteration in 1..options.iterations) {
                    labels.fill(0f)
                    for (lane in 0 until lanes) {
                        val sequence = eligible[random.nextInt(eligible.size)]
                        val start = random.nextInt(sequence.ticks.size - window)
                        rollout(
                            view, sequence, start, lane, window, normalization, sampling,
                            options.progressWeight, options.warmStart, rows, labels, rewards, hits, ranges, cloneStream
                        )
                    }
                    val rangeSnapshot = rangeMatch?.shape(rewards, hits, ranges)

                    // The imitation game: the discriminator learns on this batch, then scores it.
                    var discriminatorUpdate: CloneDiscriminator.Update? = null
                    val discriminatorRewards = if (discriminator != null && cloneStream != null && realStream != null) {
                        repeat(options.discriminatorSteps) {
                            samplePlayerWindows(eligible, playerStreams, lanes, window, random, realStream)
                            discriminatorUpdate = discriminator.update(
                                realStream, cloneStream, lanes, window, options.discriminatorBurnIn
                            )
                        }
                        val logits = discriminator.logits(cloneStream, lanes, window)
                        FloatArray(logits.size) { cell ->
                            if (cell % window < options.discriminatorBurnIn) {
                                0f
                            } else {
                                logits[cell].coerceIn(-options.discriminatorClip, options.discriminatorClip)
                            }
                        }
                    } else {
                        null
                    }

                    val referenceHeads = referenceModel.forwardHeads(rows, lanes, window)
                    val report = trainer.manager.newSubManager().use { scratch ->
                        val batch = Shape(lanes.toLong(), window.toLong())
                        val input = scratch.create(rows, Shape(lanes.toLong(), window.toLong(), FEATURES.toLong()))
                        val target = scratch.create(labels, Shape(lanes.toLong(), window.toLong(), LABELS.toLong()))
                        val refHeads = scratch.create(
                            referenceHeads,
                            Shape(lanes.toLong(), window.toLong(), HEADS.toLong())
                        )
                        val refNll = rewardedNll(objective, refHeads, target, options).toFloatArray()
                        val refAttack = objective.attackLogit(refHeads, target)
                        val refStates = objective.stateLogProbabilities(refHeads)

                        var result: Iteration? = null
                        trainer.newGradientCollector().use { collector ->
                            val heads = trainer.forward(NDList(input))[0]
                            val nll = rewardedNll(objective, heads, target, options)
                            val ownNll = nll.toFloatArray()

                            // log pi(a) - log pi_ref(a), per tick: the sampled KL estimate.
                            val kl = FloatArray(ownNll.size) { refNll[it] - ownNll[it] }
                            val advantage = advantages(rewards, kl, lanes, window, options)
                            val policyGradient = scratch.create(advantage, batch).mul(nll).mean()

                            val attackKl = bernoulliKl(refAttack, objective.attackLogit(heads, target)).mean()
                            var loss = policyGradient.add(attackKl.mul(options.attackKlWeight))
                            if (discriminatorRewards != null) {
                                // A separate advantage stream, on the whole movement: step size AND
                                // the move/still choice.
                                val imitation = standardizedReturns(discriminatorRewards, lanes, window, options.discount)
                                loss = loss.add(
                                    scratch.create(imitation, batch)
                                        .mul(objective.movementNll(heads, target))
                                        .mean()
                                        .mul(options.discriminatorWeight)
                                )
                            }
                            var stateKlValue = 0f
                            if (options.freezeStateChoice) {
                                // Exact KL(pi_ref || pi) of the four-way choice on the states visited.
                                val stateKl = refStates.exp()
                                    .mul(refStates.sub(objective.stateLogProbabilities(heads)))
                                    .sum(intArrayOf(-1))
                                    .mean()
                                stateKlValue = stateKl.getFloat()
                                loss = loss.add(stateKl.mul(options.stateKlWeight))
                            }
                            val lossValue = loss.getFloat()
                            if (lossValue.isFinite()) {
                                collector.backward(loss)
                            }
                            result = Iteration(
                                iteration,
                                hits.average().toFloat(),
                                kl.average().toFloat(),
                                attackKl.getFloat(),
                                lossValue,
                                stateKlValue,
                                discriminatorUpdate?.realProbability ?: Float.NaN,
                                discriminatorUpdate?.fakeProbability ?: Float.NaN,
                                rangeSnapshot
                            )
                        }
                        checkNotNull(result)
                    }
                    if (!report.loss.isFinite()) {
                        // Nothing was back-propagated; the weights are the last finite ones.
                        onIteration(report, view)
                        break
                    }
                    trainer.step()
                    onIteration(report, view)
                }

                folder.mkdirs()
                policy.setProperty("Epoch", "0")
                policy.save(folder.toPath(), CloneModel.PARAMETER_PREFIX)
                val result = metadata.copy(createdAt = System.currentTimeMillis())
                result.save(folder)
                return result
            }
        } finally {
            discriminator?.close()
            referenceModel.close()
            policy.close()
        }
    }

    /** Fills [out] with `lanes` random windows of the player's own stream, one per lane. */
    private fun samplePlayerWindows(
        eligible: List<AimSequence>,
        playerStreams: List<FloatArray>,
        lanes: Int,
        window: Int,
        random: Random,
        out: FloatArray
    ) {
        val channels = CloneDiscriminator.CHANNELS
        for (lane in 0 until lanes) {
            val index = random.nextInt(eligible.size)
            val start = random.nextInt(eligible[index].ticks.size - window)
            playerStreams[index].copyInto(
                out,
                lane * window * channels,
                start * channels,
                (start + window) * channels
            )
        }
    }

    /**
     * Rolls the current policy over [window] rows of [sequence] from [start], writing the standardized
     * rows it saw, its own steps as labels, and one reward per tick into [lane] of the batch.
     */
    @Suppress("LongParameterList")
    private fun rollout(
        policy: CloneModel,
        sequence: AimSequence,
        start: Int,
        lane: Int,
        window: Int,
        normalization: FeatureSchema.Normalization,
        sampling: java.util.Random,
        progressWeight: Float,
        warmStart: Int,
        rows: FloatArray,
        labels: FloatArray,
        rewards: FloatArray,
        /** The raw hit of every tick, 1 or 0, before any shaping. */
        hits: FloatArray,
        /** Every tick's [RangeMatch.rangeOf] bucket, judged on the row the hit is judged on. */
        ranges: IntArray,
        /** The clone's stream for the discriminator, `lanes x window x CHANNELS`; null when it is off. */
        stream: FloatArray?
    ) {
        val ticks = sequence.ticks
        val gcd = sequence.gcd
        val anchors = CloneEval.anchors(sequence, CloneEval.CENTRE_AIM_POINT)
        val lead = warmStart.coerceIn(0, start)
        val state = CloneEval.RolloutState(ticks[start - lead].ownYaw, ticks[start - lead].ownPitch)
        val raw = FloatArray(FEATURES)

        policy.newSession().use { session ->
            // The lead-in (Options.warmStart): the player's own rows, exactly as imitation saw them.
            // Only the GRU state and the carried step and swing survive it; its draws are discarded.
            for (index in start - lead until start) {
                val tick = ticks[index]
                state.yaw = tick.ownYaw
                state.pitch = tick.ownPitch
                CloneEval.fillFeatures(raw, ticks, index, state, Rotation(state.yaw, state.pitch), gcd.toFloat(), anchors)
                session.step(raw, gcd, 1f, sampling)
                state.advance((tick.dx * gcd).toFloat(), (tick.dy * gcd).toFloat(), tick.attacked)
            }
            state.yaw = ticks[start].ownYaw
            state.pitch = ticks[start].ownPitch

            for (t in 0 until window) {
                val index = start + t
                val cell = lane * window + t
                CloneEval.fillFeatures(raw, ticks, index, state, Rotation(state.yaw, state.pitch), gcd.toFloat(), anchors)

                raw.copyInto(rows, cell * FEATURES)
                normalization.applyFeatures(rows, cell * FEATURES)

                val step = session.step(raw, gcd, 1f, sampling)
                val dx = ((step.deltaYaw / gcd).roundToInt() * gcd).toFloat()
                val dy = ((step.deltaPitch / gcd).roundToInt() * gcd).toFloat()
                // From the aim this tick STARTED at, like the player's rows of the same stream.
                stream?.let {
                    CloneDiscriminator.write(it, cell * CloneDiscriminator.CHANNELS, ticks[index], state.yaw, state.pitch, dx, dy)
                }

                // Labelled exactly as CloneBptt labels the player's own steps, so the NLL the
                // gradient pushes on is the one imitation training fitted.
                val offset = cell * LABELS
                labels[offset + L_DX] = if (dx == 0f) 0f else normalization.normalizeAction(dx, 0)
                labels[offset + L_DX + 1] = if (dy == 0f) 0f else normalization.normalizeAction(dy, 1)
                val movementState = when {
                    dx == 0f && dy == 0f -> 0
                    dy == 0f -> 1
                    dx == 0f -> 2
                    else -> 3
                }
                labels[offset + L_MOVE_STATE + movementState] = 1f
                labels[offset + L_ATTACKED] = if (step.attack) 1f else 0f
                labels[offset + L_PREV_DX] =
                    if (state.previousDx == 0f) 0f else normalization.normalizeAction(state.previousDx, 0)
                labels[offset + L_PREV_DY] =
                    if (state.previousDy == 0f) 0f else normalization.normalizeAction(state.previousDy, 1)

                val next = ticks[index + 1]
                val outsideBefore = outsideBox(next, state.yaw, state.pitch)
                state.advance(dx, dy, step.attack)
                val hit = if (HitboxHits.entersBox(next, Rotation(state.yaw, state.pitch))) 1f else 0f
                val progress = if (progressWeight > 0f && outsideBefore.isFinite()) {
                    (outsideBefore - outsideBox(next, state.yaw, state.pitch)).coerceIn(-1f, 1f)
                } else {
                    0f
                }
                rewards[cell] = hit + progressWeight * progress
                hits[cell] = hit
                ranges[cell] = RangeMatch.rangeOf(next)
            }
        }
    }

    class ClickCalibration(
        val bias: Float,
        /** Share of ticks the clone swung on with the bias it had before. */
        val rateBefore: Float,
        val rateAfter: Float,
        /** Share of the same ticks the player swung on. */
        val target: Float
    ) {
        fun describe() = "Closed-loop click calibration: bias %.2f, swinging on %.2f%% of ticks -> %.2f%%, the player %.2f%%"
            .format(bias, rateBefore * 100f, rateAfter * 100f, target * 100f)
    }

    /**
     * Sets the click bias of the model in [folder] so that, aiming on its OWN over [sequences], it
     * swings on as many ticks as the player did on those ticks, and writes it into the metadata.
     *
     * [CloneCalibration] fits the same bias teacher-forced - on the player's crosshair - which is right
     * for a clone that aims like the player. A clone that hits more than the player (a reinforced one,
     * or any with a hit-aware sampler) sits on the box more often, and the click head, conditioned on
     * being on target, then swings more: measured 1.75-2.0 swings a second against the player's 1.19,
     * which the tree adversary ranked among its most telling features. Matching the rate in the closed
     * loop is what puts the swing count back on the player's.
     *
     * Bisection on the bias with the same random stream every probe, so the rate is close to
     * monotonic in it.
     */
    fun calibrateClicks(folder: File, sequences: List<AimSequence>, seed: Long = CloneTrainer.DEFAULT_SEED): ClickCalibration? {
        val model = CloneModel.load(folder) ?: return null
        model.use {
            val ticks = sequences.sumOf { it.ticks.size }
            val target = sequences.sumOf { sequence -> sequence.ticks.count { it.attacked } }.toFloat() / ticks
            if (ticks == 0 || target <= 0f) {
                return null
            }

            fun rate(bias: Float): Float {
                model.attackBiasOverride = bias
                return swingRate(model, sequences, seed)
            }

            val start = model.metadata.attackBias ?: 0f
            val before = rate(start)
            var low = start - BIAS_BRACKET
            var high = start + BIAS_BRACKET
            repeat(BIAS_ITERATIONS) {
                val middle = (low + high) / 2f
                if (rate(middle) > target) high = middle else low = middle
            }
            val bias = (low + high) / 2f
            val after = rate(bias)
            model.attackBiasOverride = null

            ModelMetadata.patch(folder) { root ->
                root.addProperty("attackBias", bias)
                root.addProperty("attackBiasRate", before)
                root.addProperty("attackBiasTarget", target)
            }
            return ClickCalibration(bias, before, after, target)
        }
    }

    /** Share of ticks the clone swings on, rolled out from each sequence's start at Temperature 1. */
    private fun swingRate(model: CloneModel, sequences: List<AimSequence>, seed: Long): Float {
        val random = Random(seed).asJavaRandom()
        val raw = FloatArray(FEATURES)
        var swings = 0
        var counted = 0
        for (sequence in sequences) {
            val ticks = sequence.ticks
            val gcd = sequence.gcd
            val anchors = CloneEval.anchors(sequence, CloneEval.CENTRE_AIM_POINT)
            val state = CloneEval.RolloutState(ticks[0].ownYaw, ticks[0].ownPitch)
            model.newSession().use { session ->
                for (index in ticks.indices) {
                    CloneEval.fillFeatures(raw, ticks, index, state, Rotation(state.yaw, state.pitch), gcd.toFloat(), anchors)
                    val step = session.step(raw, gcd, 1f, random)
                    if (step.attack) {
                        swings++
                    }
                    counted++
                    state.advance(
                        ((step.deltaYaw / gcd).roundToInt() * gcd).toFloat(),
                        ((step.deltaPitch / gcd).roundToInt() * gcd).toFloat(),
                        step.attack
                    )
                }
            }
        }
        return if (counted == 0) 0f else swings.toFloat() / counted
    }

    /** How far either side of the current bias the closed-loop search looks, in logits. */
    private const val BIAS_BRACKET = 4f

    /** Halvings of the bracket: 8 / 2^11 is well under a hundredth of a logit. */
    private const val BIAS_ITERATIONS = 11

    /** The part of the step's NLL the reward acts on: the step size alone, or also the move/still choice. */
    private fun rewardedNll(objective: CloneLoss, heads: NDArray, target: NDArray, options: Options): NDArray =
        if (options.freezeStateChoice) objective.stepNll(heads, target) else objective.movementNll(heads, target)

    /**
     * How far outside [tick]'s box the crosshair at ([yaw], [pitch]) sits, in half-box units: the
     * angular half-width and half-height the box subtends at its distance. Zero on or inside it;
     * NaN when the row carries no box to measure against.
     */
    private fun outsideBox(tick: AimTick, yaw: Float, pitch: Float): Float {
        if (!tick.hasTarget || tick.distance <= 0f || tick.targetWidth <= 0f || tick.targetHeight <= 0f) {
            return Float.NaN
        }
        val halfYaw = Math.toDegrees(atan2(tick.targetWidth / 2.0, tick.distance.toDouble())).toFloat()
        val halfPitch = Math.toDegrees(atan2(tick.targetHeight / 2.0, tick.distance.toDouble())).toFloat()
        val error = max(
            abs(angleDifference(tick.targetYaw, yaw)) / halfYaw,
            abs(angleDifference(tick.targetPitch, pitch)) / halfPitch
        )
        return max(0f, error - 1f)
    }

    /** Discounted, KL-shaped returns per lane, standardized over the whole batch. */
    private fun advantages(
        rewards: FloatArray,
        kl: FloatArray,
        lanes: Int,
        window: Int,
        options: Options
    ): FloatArray = standardizedReturns(
        FloatArray(rewards.size) { rewards[it] - options.klWeight * kl[it] },
        lanes,
        window,
        options.discount
    )

    /** Discounted returns of per-tick [rewards] per lane, standardized over the whole batch. */
    private fun standardizedReturns(rewards: FloatArray, lanes: Int, window: Int, discount: Float): FloatArray {
        val returns = FloatArray(rewards.size)
        for (lane in 0 until lanes) {
            var running = 0f
            for (t in window - 1 downTo 0) {
                val cell = lane * window + t
                running = rewards[cell] + discount * running
                returns[cell] = running
            }
        }
        val mean = returns.average().toFloat()
        val std = sqrt(returns.sumOf { ((it - mean) * (it - mean)).toDouble() } / returns.size).toFloat()
        val scale = if (std > 1e-6f) 1f / std else 1f
        return FloatArray(returns.size) { (returns[it] - mean) * scale }
    }

    /**
     * [Options.rangeMatching]: the hit reward reweighted per range bucket so that the clone's hit
     * rate in each bucket settles on the PLAYER'S in that bucket - a constrained objective ("hit like
     * the player at every range") solved by dual ascent on one Lagrange multiplier per bucket.
     *
     * The targets are the player's own rate over the same fights, by exactly the test the reward
     * uses: their aim at the start of the next row against that row's box. Each iteration the batch's
     * hits are paid `weight(bucket)` instead of 1, then every weight moves by [RANGE_STEP] times
     * (target - achieved rate): up where the clone falls short, down where it does better. A weight
     * may go below zero, which makes landing on the box there COST reward - the only lever that
     * pulls a range the clone over-hits back to the hand. Bounded to [[MIN_RANGE_WEIGHT],
     * [MAX_RANGE_WEIGHT]] so a thinly populated bucket cannot run away, and a bucket with fewer than
     * [MIN_RANGE_TICKS] ticks in the batch keeps its weight for the iteration.
     *
     * The achieved rate is the training rollout's, on fights the policy trains on: it runs a few
     * points above what the same model does on held-out fights (measured for the plain recipe:
     * 69-71 % in training against 66.7 held out), so matching it here lands a little under the
     * player out of sample, not over.
     */
    class RangeMatch private constructor(private val targets: FloatArray) {

        private val weights = FloatArray(RANGE_BUCKETS) { 1f }

        class Snapshot(
            /** The player's rate per bucket; NaN where they have too few ticks to state one. */
            val targets: FloatArray,
            /** The clone's rate per bucket in this batch; NaN where it had no ticks. */
            val rates: FloatArray,
            /** The weight each bucket's hits were paid in this batch. */
            val weights: FloatArray
        )

        /**
         * Pays this batch's hits their bucket's weight (adding to [rewards], which already hold the
         * plain hit), then moves the weights by this batch's shortfall for the next one.
         */
        fun shape(rewards: FloatArray, hits: FloatArray, ranges: IntArray): Snapshot {
            val hitCount = IntArray(RANGE_BUCKETS)
            val tickCount = IntArray(RANGE_BUCKETS)
            for (cell in rewards.indices) {
                val range = ranges[cell]
                if (range < 0) {
                    continue
                }
                tickCount[range]++
                if (hits[cell] > 0f) {
                    hitCount[range]++
                }
                rewards[cell] += (weights[range] - 1f) * hits[cell]
            }

            val paid = weights.copyOf()
            val rates = FloatArray(RANGE_BUCKETS) {
                if (tickCount[it] == 0) Float.NaN else hitCount[it].toFloat() / tickCount[it]
            }
            for (range in 0 until RANGE_BUCKETS) {
                if (targets[range].isNaN() || tickCount[range] < MIN_RANGE_TICKS) {
                    continue
                }
                weights[range] = (weights[range] + RANGE_STEP * (targets[range] - rates[range]))
                    .coerceIn(MIN_RANGE_WEIGHT, MAX_RANGE_WEIGHT)
            }
            return Snapshot(targets.copyOf(), rates, paid)
        }

        companion object {
            /** Whole blocks of range from the eyes to the box centre, the last one open: 0-1 .. 6+. */
            const val RANGE_BUCKETS = 7

            /** Weight moved per unit of rate shortfall per iteration: 0.1 short is +0.1 a step. */
            private const val RANGE_STEP = 1f
            private const val MIN_RANGE_WEIGHT = -1f
            private const val MAX_RANGE_WEIGHT = 4f

            /** A batch bucket this thin says too little about the rate to move its weight. */
            private const val MIN_RANGE_TICKS = 64

            /** Player ticks a bucket needs before its rate is used as a target at all. */
            private const val MIN_TARGET_TICKS = 200

            /** [row]'s bucket, or -1 when it has no box to hit - such a tick is not a miss. */
            fun rangeOf(row: AimTick): Int =
                if (!row.hasTarget || row.targetWidth <= 0f || row.targetHeight <= 0f) {
                    -1
                } else {
                    row.distance.toInt().coerceIn(0, RANGE_BUCKETS - 1)
                }

            /** The player's own rate per bucket over [sequences], by the reward's test. */
            fun of(sequences: List<AimSequence>): RangeMatch {
                val hit = IntArray(RANGE_BUCKETS)
                val total = IntArray(RANGE_BUCKETS)
                for (sequence in sequences) {
                    val ticks = sequence.ticks
                    for (index in 0 until ticks.size - 1) {
                        val next = ticks[index + 1]
                        val range = rangeOf(next)
                        if (range < 0) {
                            continue
                        }
                        total[range]++
                        if (HitboxHits.entersBox(next, Rotation(next.ownYaw, next.ownPitch))) {
                            hit[range]++
                        }
                    }
                }
                return RangeMatch(FloatArray(RANGE_BUCKETS) {
                    if (total[it] < MIN_TARGET_TICKS) Float.NaN else hit[it].toFloat() / total[it]
                })
            }
        }
    }

    /** `KL(Bernoulli(sigmoid(reference)) || Bernoulli(sigmoid(policy)))` per element, from logits. */
    private fun bernoulliKl(reference: NDArray, policy: NDArray): NDArray {
        val p = Activation.sigmoid(reference)
        val logP = Activation.softPlus(reference.neg()).neg()
        val log1mP = Activation.softPlus(reference).neg()
        val logQ = Activation.softPlus(policy.neg()).neg()
        val log1mQ = Activation.softPlus(policy).neg()
        return p.mul(logP.sub(logQ)).add(p.neg().add(1f).mul(log1mP.sub(log1mQ)))
    }
}
