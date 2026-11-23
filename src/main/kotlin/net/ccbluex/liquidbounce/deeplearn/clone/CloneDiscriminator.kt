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
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.Shape
import ai.djl.nn.Activation
import ai.djl.nn.SequentialBlock
import ai.djl.nn.core.Linear
import ai.djl.nn.recurrent.GRU
import ai.djl.training.DefaultTrainingConfig
import ai.djl.training.Trainer
import ai.djl.training.initializer.XavierInitializer
import ai.djl.training.loss.Loss
import ai.djl.training.optimizer.Adam
import ai.djl.training.tracker.Tracker
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import java.io.Closeable
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The adversary of [CloneReinforce]'s imitation reward (GAIL): a causal GRU that reads a stream of
 * aim ticks and says, at EVERY tick, how much the trajectory so far looks like the player's rather
 * than the clone's - a logit, positive for "player".
 *
 * It reads the channels the offline adversaries read (`CloneEval`'s stream): the step, the
 * residual to the box centre before it, and where on the box the crosshair sits - minus the swing,
 * which the policy's aim cannot change (the click head is held by its own KL and refitted in the
 * closed loop), so a click tell would only be noise in an aim reward.
 *
 * Per-tick rather than per-window so the reward is dense: the step at `t` changes every verdict
 * from `t` on, and discounted returns carry that back to it. It is retrained alongside the policy
 * on fresh rollouts every iteration, which is what keeps it an adversary and not a fixed target -
 * and it is never the thing a result is judged by: `.clone eval` trains its own adversaries from
 * scratch on the finished model, which cannot have adapted to them.
 *
 * What it did NOT fix, measured 2026-09-29 on 64-tick windows of 35 held-out fights (the tree
 * adversary's own features, effect sizes against the player): the imitation model, the plain
 * reinforced one and the imitation-game one all pause as often as the player overall (~19 % of
 * ticks) but in the wrong shape - 11.8 still runs per 100 ticks against 9.1, mean length 1.55
 * against 2.12, and almost none of 6 ticks or more (1 % of runs against 6 %) - and reverse the yaw
 * direction between consecutive moves more often (0.145 of ticks against 0.114). Both are there
 * before any reinforcement, so they are imitation errors, and this term does not reach them: the
 * per-tick reward has no way to credit a pause for lasting.
 */
class CloneDiscriminator(
    hidden: Int,
    learningRate: Float,
    /** Per-channel RMS of the player's stream; both classes are divided by it. */
    private val scale: FloatArray,
    seed: Long
) : Closeable {

    private val model = Model.newInstance("clone-discriminator")
    private val trainer: Trainer

    init {
        require(scale.size == CHANNELS) { "Expected $CHANNELS scales" }
        Engine.getInstance().setRandomSeed(seed.toInt())
        model.block = SequentialBlock()
            .add(GRU.builder().setStateSize(hidden).setNumLayers(1).optBatchFirst(true).build())
            .add(Linear.builder().setUnits(1).build())
        val config = DefaultTrainingConfig(Loss.sigmoidBinaryCrossEntropyLoss())
            .optInitializer(XavierInitializer(), "weight")
            .optOptimizer(Adam.builder().optLearningRateTracker(Tracker.fixed(learningRate)).build())
        trainer = model.newTrainer(config)
        trainer.initialize(Shape(1, 1, CHANNELS.toLong()))
    }

    class Update(
        /** Mean P(player) the discriminator gave the player's windows, before this step. */
        val realProbability: Float,
        /** The same for the clone's windows; the gap between the two is how well it separates them. */
        val fakeProbability: Float
    )

    /**
     * One gradient step on [real] (label 1) and [fake] (label 0), both `lanes x window x CHANNELS`,
     * ignoring the first [burnIn] ticks of every window - a verdict on a trajectory it has seen two
     * ticks of says nothing.
     */
    fun update(real: FloatArray, fake: FloatArray, lanes: Int, window: Int, burnIn: Int): Update {
        val rows = lanes * 2
        val data = FloatArray(rows * window * CHANNELS)
        scaleInto(real, data, 0)
        scaleInto(fake, data, real.size)
        val labels = FloatArray(rows * window) { if (it < lanes * window) 1f else 0f }
        val mask = FloatArray(window) { if (it < burnIn) 0f else 1f }
        val counted = (rows * (window - burnIn)).toFloat()

        var realProbability = 0f
        var fakeProbability = 0f
        trainer.manager.newSubManager().use { scratch ->
            val input = scratch.create(data, Shape(rows.toLong(), window.toLong(), CHANNELS.toLong()))
            val target = scratch.create(labels, Shape(rows.toLong(), window.toLong()))
            val weights = scratch.create(mask, Shape(1, window.toLong()))
            trainer.newGradientCollector().use { collector ->
                val logits = trainer.forward(NDList(input))[0].squeeze(-1)
                val values = logits.toFloatArray()
                var realCount = 0
                var fakeCount = 0
                for (row in 0 until rows) {
                    for (t in burnIn until window) {
                        val probability = sigmoid(values[row * window + t])
                        if (row < lanes) {
                            realProbability += probability
                            realCount++
                        } else {
                            fakeProbability += probability
                            fakeCount++
                        }
                    }
                }
                realProbability /= realCount.coerceAtLeast(1)
                fakeProbability /= fakeCount.coerceAtLeast(1)

                // Binary cross entropy from logits, the form that stays finite for large |z|.
                val loss = Activation.softPlus(logits.neg()).mul(target)
                    .add(Activation.softPlus(logits).mul(target.neg().add(1f)))
                    .mul(weights)
                    .sum()
                    .div(counted)
                collector.backward(loss)
            }
        }
        trainer.step()
        return Update(realProbability, fakeProbability)
    }

    /** Per-tick "player" logits for `lanes x window x CHANNELS` streams, flat `lanes x window`. */
    fun logits(stream: FloatArray, lanes: Int, window: Int): FloatArray {
        val data = FloatArray(stream.size)
        scaleInto(stream, data, 0)
        return trainer.manager.newSubManager().use { scratch ->
            val input = scratch.create(data, Shape(lanes.toLong(), window.toLong(), CHANNELS.toLong()))
            trainer.evaluate(NDList(input))[0].toFloatArray()
        }
    }

    private fun scaleInto(source: FloatArray, target: FloatArray, offset: Int) {
        for (i in source.indices) {
            target[offset + i] = source[i] / scale[i % CHANNELS]
        }
    }

    override fun close() {
        trainer.close()
        model.close()
    }

    companion object {
        const val CHANNELS = 6
        private const val C_DX = 0
        private const val C_DY = 1
        private const val C_DELTA_YAW = 2
        private const val C_DELTA_PITCH = 3
        private const val C_AIM_X = 4
        private const val C_AIM_Y = 5

        /** Past this many hitbox sizes off-centre every offset reads the same - CloneEval's clamp. */
        private const val AIM_CLAMP = 3f

        private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

        /**
         * Writes one tick of the stream: the step taken ([dx], [dy], degrees) from the aim
         * ([yaw], [pitch]) the tick started at, against [tick]'s box. The same function builds the
         * player's rows (their recorded aim and step) and the clone's (its own), so the two classes
         * differ only in behaviour.
         */
        @Suppress("LongParameterList")
        fun write(out: FloatArray, offset: Int, tick: AimTick, yaw: Float, pitch: Float, dx: Float, dy: Float) {
            val deltaYaw = angleDifference(tick.targetYaw, yaw)
            val deltaPitch = angleDifference(tick.targetPitch, pitch)
            out[offset + C_DX] = dx
            out[offset + C_DY] = dy
            out[offset + C_DELTA_YAW] = deltaYaw
            out[offset + C_DELTA_PITCH] = deltaPitch
            if (tick.hasTarget && tick.targetWidth > 0f && tick.targetHeight > 0f) {
                out[offset + C_AIM_X] = (tick.distance * tan(Math.toRadians(deltaYaw.toDouble())).toFloat() /
                    tick.targetWidth).coerceIn(-AIM_CLAMP, AIM_CLAMP)
                out[offset + C_AIM_Y] = (tick.distance * tan(Math.toRadians(deltaPitch.toDouble())).toFloat() /
                    tick.targetHeight).coerceIn(-AIM_CLAMP, AIM_CLAMP)
            } else {
                out[offset + C_AIM_X] = 0f
                out[offset + C_AIM_Y] = 0f
            }
        }

        /** The player's own stream over a whole recording, `ticks x CHANNELS`. */
        fun playerStream(ticks: List<AimTick>, gcd: Double): FloatArray {
            val out = FloatArray(ticks.size * CHANNELS)
            for ((i, tick) in ticks.withIndex()) {
                write(out, i * CHANNELS, tick, tick.ownYaw, tick.ownPitch, (tick.dx * gcd).toFloat(), (tick.dy * gcd).toFloat())
            }
            return out
        }

        /** Per-channel RMS over [streams], floored so a silent channel cannot blow a scale up. */
        fun scaleOf(streams: List<FloatArray>): FloatArray {
            val sums = DoubleArray(CHANNELS)
            var rows = 0L
            for (stream in streams) {
                for (i in stream.indices) {
                    sums[i % CHANNELS] += (stream[i] * stream[i]).toDouble()
                }
                rows += stream.size / CHANNELS
            }
            return FloatArray(CHANNELS) { sqrt(sums[it] / rows.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-3f) }
        }
    }
}
