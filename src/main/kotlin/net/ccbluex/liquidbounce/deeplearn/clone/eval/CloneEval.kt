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

import net.ccbluex.liquidbounce.deeplearn.clone.CloneDriftRescue
import net.ccbluex.liquidbounce.deeplearn.clone.CloneModel
import net.ccbluex.liquidbounce.deeplearn.clone.CloneTrainer
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import net.minecraft.util.Mth
import java.util.random.RandomGenerator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.random.asJavaRandom

/**
 * Measures how close generated aim is to a recording of the real thing.
 *
 * The summary statistics say *where* it differs — too few pauses, steps that are too large, a
 * smoothness a person does not have — while [AimDiscriminator] answers the only question that
 * ultimately matters, which is whether a classifier can tell at all.
 *
 * What generated the aim is deliberately behind [AimSource]: a clone sampled from the recording
 * and a smoother built out of formulas are worth holding to the same adversaries, and a number
 * that only one of them can be given is not a number worth trusting either of them on.
 */
object CloneEval {

    /** Held-out sequences are replayed at least this long for the numbers to mean anything. */
    private const val MIN_TICKS = AimSequence.MIN_LENGTH

    private const val ON_TARGET_ANGLE = 3f

    /**
     * The stream both the statistics and the discriminator work on — deliberately limited to what
     * a server can observe: the rotation deltas that arrive in movement packets, where the target
     * was relative to the crosshair at the time, and the attack packets.
     *
     * The target-relative channels are the important addition over raw deltas. Aim detection is
     * rarely "these steps look odd" and almost always "the crosshair closes on the target more
     * efficiently, or more consistently, than a hand does" — and that is invisible in the deltas
     * alone, no matter how well their distribution matches.
     */
    const val CHANNELS = 8
    const val C_DX = 0
    const val C_DY = 1
    const val C_DELTA_YAW = 2
    const val C_DELTA_PITCH = 3
    const val C_HAS_TARGET = 4
    const val C_ATTACKED = 5

    /**
     * Where the crosshair sits inside the target's hitbox, as a fraction of its width and height
     * from the centre. This is `hit_point_rel_*` in Shard 2.0's schema — the answer to "does this
     * player aim at the head, the chest, or wherever the bot decided".
     */
    const val C_AIM_REL_X = 6
    const val C_AIM_REL_Y = 7

    private const val HISTOGRAM_BINS = 41

    /** Widest step a histogram bin covers, in degrees; anything larger lands in the end bins. */
    private const val HISTOGRAM_RANGE = 20f

    class AxisStats(
        val zeroFraction: Float,
        val meanAbsStep: Float,
        val autocorrelation: FloatArray
    )

    /**
     * How clicking relates to aiming.
     *
     * [medianAngleAtClick] and [missRate] are the pair that matters: a scheduler clicks whenever
     * its cadence says so, which — because the aura only lets the packet through on a hit —
     * produces a miss rate of zero and an angle distribution clipped to whatever counts as "on
     * target". A hand produces a spread with a tail, and swings at nothing sometimes.
     */
    class ClickStats(
        val clicksPerSecond: Float,
        val medianAngleAtClick: Float,
        val missRate: Float
    )

    /**
     * The shape of the click stream rather than its rate.
     *
     * A rate is the one thing a scheduler matches without trying. What it cannot reproduce is where
     * the swings *sit*: a hard mode in the gaps at the weapon's recharge, and almost no swings at
     * all on a weapon that has not recharged. Both are read directly by an attack-window-centred
     * anticheat, which cuts its features around each attack packet and therefore sees the cooldown
     * a swing went out on before it sees anything about the aim.
     */
    class ClickRhythm(
        /** Share of the gaps between consecutive swings landing in each [INTERVAL_LABELS] bucket. */
        val intervals: FloatArray,
        val gaps: Int,

        /**
         * Share of ticks that carried a swing, per fifth of the attack cooldown, over ticks with a
         * target inside [COOLDOWN_RANGE] blocks. Further out a swing cannot land, so whether one
         * went out says nothing about when this player commits.
         */
        val byCooldown: FloatArray
    )

    /**
     * Where inside the hitbox the crosshair sat when a swing went out, as a fraction of the box
     * from its centre.
     *
     * The mean says *where* on the body this player aims; the spread says how tightly. Both matter:
     * a bot that always takes the exact centre and a bot that scatters uniformly are equally
     * detectable, just in opposite directions, and neither is what a person produces.
     */
    class AimPointStats(
        val meanX: Float,
        val meanY: Float,
        val spreadX: Float,
        val spreadY: Float,
        val samples: Int
    )

    /**
     * Whether the aim actually arrives and stays, measured longitudinally rather than per tick.
     *
     * This exists because the distribution checks can all pass while the clone is useless: a
     * stream that wanders off the target has plausible step sizes, pauses and momentum — what it
     * lacks is *progress*, and progress only shows in how the residual evolves over an
     * engagement. These are the numbers behind "the clone drifts off and stares at nothing":
     * a generator that does that stalls longer than the player and loses approaches the player
     * always lands.
     */
    class Convergence(
        /** Residual to the box centre over tracked ticks: the middle and the bad tail. */
        val medianResidual: Float,
        val p90Residual: Float,

        /**
         * Runs of ticks past [CloneDriftRescue.STALL_MIN_ANGLE] with no closing progress — the
         * 95th percentile and the single worst, in ticks. The live drift rescue triggers at
         * [CloneDriftRescue.STALL_TICKS]; a clone whose p95 sits near that number is being
         * rescued through fights rather than aiming through them.
         */
        val stallP95: Int,
        val stallMax: Int,

        /**
         * Approaches — the residual crossing [APPROACH_START_ANGLE] until it either lands at
         * [ON_TARGET_ANGLE] or fails to within [APPROACH_TIMEOUT_TICKS]. The lost share is the
         * direct rate of "started turning to the target and never got there".
         */
        val approaches: Int,
        val medianTicksToLand: Float,
        val lostShare: Float,

        /**
         * Pursuit lag in ticks: the offset `k` at which own yaw best matches the target's yaw
         * `k` ticks in the past — the same cross-correlation that measured the player's own
         * visuomotor latency at ~3 ticks (150 ms). This is *effective* lag: a tracker whose steps
         * are too noisy to compound also matches the target's older past best, so the number
         * absorbs both reaction time and gain deficit — which is exactly how far behind the
         * crosshair runs in a fight, the quantity a lagging clone is felt by. NaN when there was
         * nothing to correlate.
         */
        val lagTicks: Float
    )

    class Report(
        val ticks: Int,
        val sequences: Int,

        /** What produced the generated stream, for the header of the printed report. */
        val source: String,

        /**
         * What the model was sampled at, or `null` for a source that has no such setting. Reported
         * because it is not a property of the model: the same clone is a different generator at
         * 0.8 than at 1.0, and a report that did not say which one it described was describing
         * neither.
         */
        val temperature: Float?,

        /**
         * Height on the hitbox the generator was pointed at, `0.5` being the centre the recorder
         * labelled against. Reported because a run at any other value is answering a different
         * question — see [CENTRE_AIM_POINT].
         */
        val aimPoint: Float,

        /**
         * Whether the generated stream's swings were the source's own decision. When false the
         * attack channel was replayed from the recording, so click *cadence* is not a property of
         * the source and only the pairing of aim with those swings means anything.
         */
        val decidesAttacks: Boolean,

        val realYaw: AxisStats,
        val cloneYaw: AxisStats,
        val realPitch: AxisStats,
        val clonePitch: AxisStats,
        val ksYaw: Float,
        val ksPitch: Float,
        val realAxisCorrelation: Float,
        val cloneAxisCorrelation: Float,
        val offGridSteps: Int,
        val realClicks: ClickStats,
        val cloneClicks: ClickStats,
        val realRhythm: ClickRhythm,
        val cloneRhythm: ClickRhythm,

        /**
         * Median crosshair travel per degree of angle actually closed, over completed approaches.
         * A hand arcs and doubles back, so it covers more ground than the straight line; a
         * generator that converges cleanly sits near 1.
         */
        val realPathEfficiency: Float,
        val clonePathEfficiency: Float,

        val realConvergence: Convergence,
        val cloneConvergence: Convergence,

        val realAimPoint: AimPointStats,
        val cloneAimPoint: AimPointStats,

        /**
         * Share of tracked ticks where the view ray was inside the target's hitbox — the one figure
         * here that is about connecting rather than about looking right while doing it.
         */
        val realHitbox: HitboxHits,
        val cloneHitbox: HitboxHits,

        val discriminator: AimDiscriminator.Report?,
        val trees: TreeDiscriminator.Report?,
        val shardV1: ShardSimulation.Report?,
        val shardV2: ShardSimulation.Report?
    ) {
        val verdict: String
            get() {
                val auc = discriminator?.auc ?: return "inconclusive (not enough data)"
                return when {
                    auc.isNaN() -> "inconclusive"
                    auc <= 0.60f -> "pass — indistinguishable"
                    auc <= 0.70f -> "weak — a classifier finds some signal"
                    else -> "fail — clearly separable"
                }
            }
    }

    /**
     * Replays the target motion of [sequences] and lets [model] generate its own aim over it, then
     * compares the two delta streams.
     */
    fun evaluate(
        model: CloneModel,
        sequences: List<AimSequence>,
        temperature: Float = 1f,
        seed: Long = CloneTrainer.DEFAULT_SEED,
        aimPoint: Float = CENTRE_AIM_POINT,
        noiseCorrelation: Float = 0f,
        bestOf: Int = 1
    ): Report? {
        val random: RandomGenerator = Random(seed).asJavaRandom()
        return ModelAimSource(model, temperature, random, noiseCorrelation, bestOf).use { source ->
            evaluate(source, sequences, seed, temperature, aimPoint)
        }
    }

    /**
     * Where on the target the anchor sits, as a fraction of the hitbox height from its bottom.
     *
     * `0.5` is the box centre, which is the only anchor the recorder ever labelled against and
     * therefore the only one the model was trained to close a residual on. In play the aura hands
     * it whatever PointTracker picked, which can be a third of a hitbox away — so this exists to
     * answer, on the same recording, what that displacement actually costs. It moves only the
     * geometry the *generator* is fed; every scored channel stays measured against the box centre,
     * because that is what an anticheat sees regardless of where the aura decided to aim.
     *
     * Vertical only. A player box is three times taller than it is wide and PointTracker's modes
     * differ mostly in height, while its horizontal choice stays near the centre line.
     */
    const val CENTRE_AIM_POINT = 0.5f

    /**
     * Replays the target motion of [sequences] and lets [source] generate its own aim over it, then
     * compares the two delta streams.
     *
     * The replay holds the target's recorded angles fixed rather than re-deriving them from the
     * generator's position. Those angles come from where the player and the target *stood*, which
     * the aim does not change, so the approximation only breaks down where the recorded movement
     * was itself steered by the aim.
     *
     * [seed] fixes the adversaries — their window shuffles, splits and weight initialisation — so
     * two runs over the same streams are comparable. It says nothing about the generator: a source
     * that draws from a thread-local generator, as every hand-written smoother does, produces a
     * different stream each run and its numbers move a little between them.
     */
    fun evaluate(
        source: AimSource,
        sequences: List<AimSequence>,
        seed: Long = CloneTrainer.DEFAULT_SEED,
        temperature: Float? = null,
        aimPoint: Float = CENTRE_AIM_POINT
    ): Report? {
        val usable = sequences.filter { it.ticks.size >= MIN_TICKS }
        if (usable.isEmpty()) {
            return null
        }

        val realStreams = mutableListOf<FloatArray>()
        val cloneStreams = mutableListOf<FloatArray>()
        val gcds = mutableListOf<Double>()
        val hits = Hits()
        var offGrid = 0

        for (sequence in usable) {
            realStreams.add(realStream(sequence, hits.real))

            val rollout = rollout(source, sequence, hits.generated, anchors(sequence, aimPoint))
            cloneStreams.add(rollout.stream)
            gcds.add(sequence.gcd)
            offGrid += countOffGrid(listOf(rollout.rawSteps), sequence.gcd)
        }

        return report(
            source = source,
            sequences = usable,
            realStreams = realStreams,
            cloneStreams = cloneStreams,
            gcds = gcds,
            offGrid = offGrid,
            seed = seed,
            hits = hits,
            temperature = temperature,
            aimPoint = aimPoint
        )
    }

    /**
     * The angles the generator is told to aim at, two floats per tick.
     *
     * At the centre these are simply the recorded target angles. Displaced, the anchor is lifted by
     * its share of the hitbox height and re-projected from the recorded distance, which is the same
     * geometry a point higher up the body subtends. Computed for the whole sequence in one pass
     * because the target-velocity features are differences between *consecutive* anchors, and
     * displacing one tick without the one before it would invent a spike out of nothing.
     */
    internal fun anchors(sequence: AimSequence, aimPoint: Float): FloatArray {
        val ticks = sequence.ticks
        val result = FloatArray(ticks.size * 2)

        for ((i, tick) in ticks.withIndex()) {
            result[i * 2] = tick.targetYaw
            result[i * 2 + 1] = if (aimPoint == CENTRE_AIM_POINT || tick.distance <= 0f) {
                tick.targetPitch
            } else {
                val lift = (aimPoint - CENTRE_AIM_POINT) * tick.targetHeight
                // Pitch counts downwards, so a point higher on the body is a smaller one.
                tick.targetPitch - Math.toDegrees(atan2(lift, tick.distance).toDouble()).toFloat()
            }
        }

        return result
    }

    /** The hitbox counts for both streams, filled in the same pass that builds them. */
    private class Hits(val real: HitboxHits = HitboxHits(), val generated: HitboxHits = HitboxHits())

    @Suppress("LongParameterList")
    private fun report(
        source: AimSource,
        sequences: List<AimSequence>,
        realStreams: List<FloatArray>,
        cloneStreams: List<FloatArray>,
        gcds: List<Double>,
        offGrid: Int,
        seed: Long,
        hits: Hits,
        temperature: Float?,
        aimPoint: Float
    ): Report {
        val realYawSteps = axis(realStreams, C_DX)
        val realPitchSteps = axis(realStreams, C_DY)
        val cloneYawSteps = axis(cloneStreams, C_DX)
        val clonePitchSteps = axis(cloneStreams, C_DY)

        return Report(
            ticks = realStreams.sumOf { it.size / CHANNELS },
            sequences = sequences.size,
            source = source.label,
            temperature = temperature,
            aimPoint = aimPoint,
            decidesAttacks = source.decidesAttacks,
            realYaw = statsOf(realYawSteps),
            cloneYaw = statsOf(cloneYawSteps),
            realPitch = statsOf(realPitchSteps),
            clonePitch = statsOf(clonePitchSteps),
            ksYaw = kolmogorovSmirnov(realYawSteps, cloneYawSteps),
            ksPitch = kolmogorovSmirnov(realPitchSteps, clonePitchSteps),
            realAxisCorrelation = correlation(realYawSteps, realPitchSteps),
            cloneAxisCorrelation = correlation(cloneYawSteps, clonePitchSteps),
            offGridSteps = offGrid,
            realClicks = clickStats(realStreams),
            cloneClicks = clickStats(cloneStreams),
            realRhythm = rhythm(realStreams, sequences),
            cloneRhythm = rhythm(cloneStreams, sequences),
            realPathEfficiency = medianEfficiency(realStreams),
            clonePathEfficiency = medianEfficiency(cloneStreams),
            realConvergence = convergence(realStreams),
            cloneConvergence = convergence(cloneStreams),
            realAimPoint = aimPointStats(realStreams),
            cloneAimPoint = aimPointStats(cloneStreams),
            realHitbox = hits.real,
            cloneHitbox = hits.generated,
            discriminator = AimDiscriminator.evaluate(realStreams, cloneStreams, seed),
            trees = TreeDiscriminator.evaluate(realStreams, cloneStreams, seed),
            shardV1 = ShardSimulation.run(
                realStreams,
                cloneStreams,
                seed,
                ShardSimulation.Version.V1,
                gcds
            ),
            shardV2 = ShardSimulation.run(
                realStreams,
                cloneStreams,
                seed,
                ShardSimulation.Version.V2,
                gcds
            )
        )
    }

    private fun medianEfficiency(streams: List<FloatArray>): Float {
        val values = streams.flatMap(AggregateFeatures::approachEfficiencies).sorted()
        return if (values.isEmpty()) Float.NaN else values[values.size / 2]
    }

    /** An approach begins once the crosshair is this far off; it lands at [ON_TARGET_ANGLE]. */
    private const val APPROACH_START_ANGLE = 15f

    /** An approach that has not landed in three seconds is not going to; the target is lost. */
    private const val APPROACH_TIMEOUT_TICKS = 60

    /**
     * One pass per stream over the residual channels. The stall accounting mirrors
     * [CloneDriftRescue] tick for tick — same floor, same progress threshold — but records the
     * run lengths instead of acting on them, so the report can say how often the deployed rescue
     * would have fired and how the player's own numbers compare on the same recording.
     */
    private fun convergence(streams: List<FloatArray>): Convergence {
        val residuals = mutableListOf<Float>()
        val stalls = mutableListOf<Int>()
        val landings = mutableListOf<Int>()
        var approaches = 0
        var lost = 0

        for (stream in streams) {
            var previous = Float.NaN
            var stall = 0
            var approachStart = -1

            for (i in 0 until stream.size / CHANNELS) {
                val offset = i * CHANNELS
                if (stream[offset + C_HAS_TARGET] < 0.5f) {
                    if (stall > 0) {
                        stalls.add(stall)
                    }
                    stall = 0
                    previous = Float.NaN
                    // A target lost to tracking is not an approach the aim failed; drop it.
                    approachStart = -1
                    continue
                }

                val residual = hypot(
                    stream[offset + C_DELTA_YAW],
                    stream[offset + C_DELTA_PITCH]
                )
                residuals.add(residual)

                val progressed = previous.isNaN() ||
                    previous - residual > CloneDriftRescue.STALL_PROGRESS
                if (residual < CloneDriftRescue.STALL_MIN_ANGLE || progressed) {
                    if (stall > 0) {
                        stalls.add(stall)
                    }
                    stall = 0
                } else {
                    stall++
                }
                previous = residual

                if (approachStart < 0) {
                    if (residual >= APPROACH_START_ANGLE) {
                        approachStart = i
                    }
                } else if (residual <= ON_TARGET_ANGLE) {
                    approaches++
                    landings.add(i - approachStart)
                    approachStart = -1
                } else if (i - approachStart >= APPROACH_TIMEOUT_TICKS) {
                    // Counted as lost, then re-armed: a generator parked 40° off produces one
                    // lost approach every three seconds instead of one per fight, which is the
                    // honest weight of being parked there.
                    approaches++
                    lost++
                    approachStart = -1
                }
            }
            if (stall > 0) {
                stalls.add(stall)
            }
        }

        residuals.sort()
        stalls.sort()
        landings.sort()

        return Convergence(
            medianResidual = percentileOf(residuals, 0.5f),
            p90Residual = percentileOf(residuals, 0.9f),
            stallP95 = if (stalls.isEmpty()) 0 else stalls[((stalls.size - 1) * 0.95f).toInt()],
            stallMax = stalls.lastOrNull() ?: 0,
            approaches = approaches,
            medianTicksToLand = if (landings.isEmpty()) {
                Float.NaN
            } else {
                landings[landings.size / 2].toFloat()
            },
            lostShare = if (approaches == 0) 0f else lost.toFloat() / approaches,
            lagTicks = effectiveLag(streams)
        )
    }

    private fun percentileOf(sorted: List<Float>, fraction: Float): Float =
        if (sorted.isEmpty()) Float.NaN else sorted[((sorted.size - 1) * fraction).toInt()]

    /** Offsets tried when locating the pursuit lag. Negative `k` compares against the target's past. */
    private const val LAG_MIN_OFFSET = -15
    private const val LAG_MAX_OFFSET = 2

    /**
     * The `k` minimising `mean |angleDifference(targetYaw[t+k], ownYaw[t])|`, aggregated over every
     * stream, returned as a positive "ticks behind". The absolute yaw series are rebuilt from the
     * stream itself — own yaw by accumulating the emitted steps, the target's by adding the
     * residual measured at each tick head — so both sides are reconstructed the same way and the
     * comparison needs nothing the anticheat could not also derive.
     */
    private fun effectiveLag(streams: List<FloatArray>): Float {
        val offsets = LAG_MAX_OFFSET - LAG_MIN_OFFSET + 1
        val sums = DoubleArray(offsets)
        val counts = LongArray(offsets)

        for (stream in streams) {
            val ticks = stream.size / CHANNELS
            if (ticks < 2) {
                continue
            }

            // Anchored at zero: only differences are ever taken, so the anchor cancels.
            val own = FloatArray(ticks)
            val target = FloatArray(ticks)
            var accumulated = 0f
            for (i in 0 until ticks) {
                val offset = i * CHANNELS
                own[i] = accumulated
                target[i] = if (stream[offset + C_HAS_TARGET] >= 0.5f) {
                    accumulated + stream[offset + C_DELTA_YAW]
                } else {
                    Float.NaN
                }
                accumulated += stream[offset + C_DX]
            }

            for (t in 0 until ticks) {
                for (index in 0 until offsets) {
                    val j = t + LAG_MIN_OFFSET + index
                    if (j < 0 || j >= ticks || target[j].isNaN()) {
                        continue
                    }
                    sums[index] += abs(angleDifference(target[j], own[t])).toDouble()
                    counts[index]++
                }
            }
        }

        var bestIndex = -1
        var best = Double.MAX_VALUE
        for (index in 0 until offsets) {
            if (counts[index] == 0L) {
                continue
            }
            val mean = sums[index] / counts[index]
            if (mean < best) {
                best = mean
                bestIndex = index
            }
        }

        return if (bestIndex < 0) Float.NaN else -(LAG_MIN_OFFSET + bestIndex).toFloat()
    }

    /** Aim point across the ticks that carried a swing — the moment an anticheat records it. */
    private fun aimPointStats(streams: List<FloatArray>): AimPointStats {
        val x = mutableListOf<Float>()
        val y = mutableListOf<Float>()

        for (stream in streams) {
            for (i in 0 until stream.size / CHANNELS) {
                val offset = i * CHANNELS
                if (stream[offset + C_ATTACKED] < 0.5f || stream[offset + C_HAS_TARGET] < 0.5f) {
                    continue
                }
                x.add(stream[offset + C_AIM_REL_X])
                y.add(stream[offset + C_AIM_REL_Y])
            }
        }

        if (x.isEmpty()) {
            return AimPointStats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0)
        }

        val meanX = x.average().toFloat()
        val meanY = y.average().toFloat()
        return AimPointStats(
            meanX = meanX,
            meanY = meanY,
            spreadX = sqrt(x.sumOf { ((it - meanX) * (it - meanX)).toDouble() } / x.size).toFloat(),
            spreadY = sqrt(y.sumOf { ((it - meanY) * (it - meanY)).toDouble() } / y.size).toFloat(),
            samples = x.size
        )
    }

    /**
     * Where the crosshair was, in degrees off the target, on the ticks that carried an attack.
     */
    private fun clickStats(streams: List<FloatArray>): ClickStats {
        val angles = mutableListOf<Float>()
        var clicks = 0
        var misses = 0
        var ticks = 0

        for (stream in streams) {
            for (i in 0 until stream.size / CHANNELS) {
                val offset = i * CHANNELS
                ticks++
                if (stream[offset + C_ATTACKED] < 0.5f) {
                    continue
                }

                clicks++
                if (stream[offset + C_HAS_TARGET] < 0.5f) {
                    misses++
                    continue
                }

                val angle = hypot(stream[offset + C_DELTA_YAW], stream[offset + C_DELTA_PITCH])
                angles.add(angle)
                if (angle > ON_TARGET_ANGLE) {
                    misses++
                }
            }
        }

        if (ticks == 0) {
            return ClickStats(0f, Float.NaN, 0f)
        }

        angles.sort()
        return ClickStats(
            clicksPerSecond = clicks.toFloat() / ticks * TICKS_PER_SECOND,
            medianAngleAtClick = if (angles.isEmpty()) Float.NaN else angles[angles.size / 2],
            missRate = if (clicks == 0) 0f else misses.toFloat() / clicks
        )
    }

    private const val TICKS_PER_SECOND = 20f

    /**
     * Both halves of [ClickRhythm] in one pass over the streams.
     *
     * The cooldown and the range come off the recording rather than out of a stream channel,
     * because they are the same on both sides: the replay holds the target's geometry fixed and the
     * weapon recharges on its own schedule, so a generated swing sits in exactly the situation the
     * recorded tick describes. [sequences] must be the streams' own, in order.
     */
    private fun rhythm(streams: List<FloatArray>, sequences: List<AimSequence>): ClickRhythm {
        val intervals = IntArray(INTERVAL_LABELS.size)
        val attacks = IntArray(COOLDOWN_BUCKETS)
        val inRange = IntArray(COOLDOWN_BUCKETS)
        var gaps = 0

        for ((index, stream) in streams.withIndex()) {
            gaps += countIntervals(stream, intervals)
            countCooldowns(stream, sequences[index].ticks, attacks, inRange)
        }

        return ClickRhythm(
            intervals = FloatArray(intervals.size) {
                if (gaps == 0) Float.NaN else intervals[it].toFloat() / gaps
            },
            gaps = gaps,
            byCooldown = FloatArray(COOLDOWN_BUCKETS) {
                if (inRange[it] == 0) Float.NaN else attacks[it].toFloat() / inRange[it]
            }
        )
    }

    /** Buckets the gap before each swing that had one before it, and returns how many there were. */
    private fun countIntervals(stream: FloatArray, into: IntArray): Int {
        var previous = -1
        var gaps = 0

        for (i in 0 until stream.size / CHANNELS) {
            if (stream[i * CHANNELS + C_ATTACKED] < 0.5f) {
                continue
            }

            if (previous >= 0) {
                into[intervalBucket(i - previous)]++
                gaps++
            }
            previous = i
        }

        return gaps
    }

    private fun countCooldowns(
        stream: FloatArray,
        ticks: List<AimTick>,
        attacks: IntArray,
        inRange: IntArray
    ) {
        for (i in ticks.indices) {
            val tick = ticks[i]
            if (!tick.hasTarget || tick.distance > COOLDOWN_RANGE) {
                continue
            }

            val bucket = (tick.attackCooldown * COOLDOWN_BUCKETS).toInt()
                .coerceIn(0, COOLDOWN_BUCKETS - 1)
            inRange[bucket]++

            if (stream[i * CHANNELS + C_ATTACKED] >= 0.5f) {
                attacks[bucket]++
            }
        }
    }

    private fun intervalBucket(interval: Int): Int {
        for (i in INTERVAL_BOUNDS.indices) {
            if (interval <= INTERVAL_BOUNDS[i]) {
                return i
            }
        }
        return INTERVAL_LABELS.size - 1
    }

    /** Widest gap each bucket admits, in ticks. Everything above the last of them shares a bucket. */
    private val INTERVAL_BOUNDS = intArrayOf(3, 6, 9, 12, 16, 25, 40)

    val INTERVAL_LABELS = arrayOf("1-3", "4-6", "7-9", "10-12", "13-16", "17-25", "26-40", "41+")

    const val COOLDOWN_BUCKETS = 5

    /** Range a swing can still reach the box from, in blocks. */
    const val COOLDOWN_RANGE = 4f

    /** The generated stream, plus the raw pre-quantization steps for the grid statistic. */
    private class Rollout(val stream: FloatArray, val rawSteps: FloatArray)

    /**
     * The generator's own rotation, which drifts away from the recording from tick zero onwards,
     * together with what it did on the previous tick.
     */
    internal class RolloutState(var yaw: Float, var pitch: Float) {
        var previousDx = 0f
        var previousDy = 0f
        var previousAttack = false
        var hadPrevious = false

        fun advance(dx: Float, dy: Float, attack: Boolean) {
            yaw = Mth.wrapDegrees(yaw + dx)
            pitch = (pitch + dy).coerceIn(-90f, 90f)
            previousDx = dx
            previousDy = dy
            previousAttack = attack
            hadPrevious = true
        }
    }

    /**
     * Runs [source] closed-loop over one sequence: every step it takes changes the geometry the
     * next step sees, exactly as in game. Errors compound here in a way they never do during
     * teacher-forced training, which is precisely what makes this worth measuring.
     */
    private fun rollout(
        source: AimSource,
        sequence: AimSequence,
        hits: HitboxHits,
        anchors: FloatArray
    ): Rollout {
        source.onDiscontinuity()

        val ticks = sequence.ticks
        val gcd = sequence.gcd
        val features = FloatArray(FeatureSchema.FEATURES)
        val output = FloatArray(ticks.size * CHANNELS)
        val raw = FloatArray(ticks.size * 2)
        val state = RolloutState(ticks[0].ownYaw, ticks[0].ownPitch)

        for (i in ticks.indices) {
            val tick = ticks[i]

            // Measured from the generator's own rotation rather than the recorded one, and against
            // the box centre rather than the anchor the generator was given: that drift is the
            // error an anticheat reads, and it never appears while a model is being trained
            // against ticks that always start where the player was.
            val deltaYaw = angleDifference(tick.targetYaw, state.yaw)
            val deltaPitch = angleDifference(tick.targetPitch, state.pitch)

            val current = Rotation(state.yaw, state.pitch)
            fillFeatures(features, ticks, i, state, current, gcd.toFloat(), anchors)
            hits.record(tick, current)

            val step = source.step(
                AimSource.Context(
                    features = features,
                    current = current,
                    target = Rotation(anchors[i * 2], anchors[i * 2 + 1]),
                    hasTarget = tick.hasTarget,
                    distance = tick.distance,
                    gcd = gcd
                )
            )
            raw[i * 2] = step.rawDeltaYaw
            raw[i * 2 + 1] = step.rawDeltaPitch

            val dx = (step.deltaYaw / gcd).roundToInt() * gcd
            val dy = (step.deltaPitch / gcd).roundToInt() * gcd

            // A source with no click decision would otherwise leave this channel constant, which
            // every classifier below would seize on: a column that is one somewhere in the
            // recording and zero everywhere in the generated stream separates the two perfectly
            // without looking at the aim at all, and the aim is the entire question. Replaying the
            // recording's own swings makes the channel identical on both sides and therefore
            // uninformative, which leaves the adversaries judging what they are meant to judge.
            val attack = if (source.decidesAttacks) step.attack else tick.attacked

            write(
                output, i, dx.toFloat(), dy.toFloat(), deltaYaw, deltaPitch,
                tick.hasTarget, attack, tick
            )

            state.advance(dx.toFloat(), dy.toFloat(), attack)
        }

        source.onDiscontinuity()
        return Rollout(output, raw)
    }

    @Suppress("LongParameterList")
    internal fun fillFeatures(
        out: FloatArray,
        ticks: List<AimTick>,
        index: Int,
        state: RolloutState,
        aim: Rotation,
        gcd: Float,
        anchors: FloatArray
    ) {
        val tick = ticks[index]
        val previous = if (index > 0) ticks[index - 1] else null

        FeatureSchema.fillFromGeometry(
            out = out,
            prevDxDeg = state.previousDx,
            prevDyDeg = state.previousDy,
            ownYaw = state.yaw,
            ownPitch = state.pitch,
            moveForward = tick.moveForward,
            moveStrafe = tick.moveStrafe,
            attackedPrev = state.previousAttack,
            hasTarget = tick.hasTarget,
            targetYaw = anchors[index * 2],
            targetPitch = anchors[index * 2 + 1],
            distance = tick.distance,
            hadPrevTarget = state.hadPrevious && previous?.hasTarget == true,
            prevTargetYaw = if (previous == null) 0f else anchors[(index - 1) * 2],
            prevTargetPitch = if (previous == null) 0f else anchors[(index - 1) * 2 + 1],
            prevDistance = previous?.distance ?: 0f,
            hurtTime = tick.hurtTime,
            onTarget = HitboxHits.entersBox(tick, aim),
            cooldown = tick.attackCooldown,
            sprinting = tick.sprinting,
            sneaking = tick.sneaking,
            onGround = tick.onGround,
            usingItem = tick.usingItem,
            knockedBack = previous?.receivedVelocity == true,
            tookDamage = previous?.tookDamage == true,
            targetChanged = previous != null && tick.targetId != previous.targetId,
            gcd = gcd,
            targetWidth = tick.targetWidth,
            targetHeight = tick.targetHeight
        )
    }

    private fun realStream(sequence: AimSequence, hits: HitboxHits): FloatArray {
        val gcd = sequence.gcd.toFloat()
        val output = FloatArray(sequence.ticks.size * CHANNELS)

        for ((i, tick) in sequence.ticks.withIndex()) {
            hits.record(tick, Rotation(tick.ownYaw, tick.ownPitch))
            write(
                output,
                i,
                tick.dx * gcd,
                tick.dy * gcd,
                angleDifference(tick.targetYaw, tick.ownYaw),
                angleDifference(tick.targetPitch, tick.ownPitch),
                tick.hasTarget,
                tick.attacked,
                tick
            )
        }
        return output
    }

    @Suppress("LongParameterList")
    private fun write(
        out: FloatArray,
        index: Int,
        dx: Float,
        dy: Float,
        deltaYaw: Float,
        deltaPitch: Float,
        hasTarget: Boolean,
        attacked: Boolean,
        tick: AimTick
    ) {
        val offset = index * CHANNELS
        out[offset + C_DX] = dx
        out[offset + C_DY] = dy
        out[offset + C_DELTA_YAW] = if (hasTarget) deltaYaw else 0f
        out[offset + C_DELTA_PITCH] = if (hasTarget) deltaPitch else 0f
        out[offset + C_HAS_TARGET] = if (hasTarget) 1f else 0f
        out[offset + C_ATTACKED] = if (attacked) 1f else 0f

        // An angular offset seen from `distance` away lands this far off the box centre, which
        // divided by the hitbox size is the fraction an anticheat records.
        if (hasTarget && tick.targetWidth > 0f && tick.targetHeight > 0f) {
            val lateral = tick.distance * tan(deltaYaw * DEGREES_TO_RADIANS)
            val vertical = tick.distance * tan(deltaPitch * DEGREES_TO_RADIANS)
            out[offset + C_AIM_REL_X] = (lateral / tick.targetWidth).coerceIn(-CLAMP, CLAMP)
            out[offset + C_AIM_REL_Y] = (vertical / tick.targetHeight).coerceIn(-CLAMP, CLAMP)
        }
    }

    private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

    /** Well outside the hitbox is all the same thing; the interesting range is inside it. */
    private const val CLAMP = 3f

    /**
     * Counts steps the model asked for that could not be expressed as a whole number of mouse
     * counts, measured on the raw sampled value before quantization.
     *
     * Checking the quantized output instead would be a tautology — it is produced by rounding onto
     * the grid, so it is trivially on it — and it was one until this was fixed. What is worth
     * knowing is how much the grid is *costing*: a model whose steps routinely fall between counts
     * loses that remainder every tick, and a large share here means the sampled distribution and
     * the player's sensitivity disagree.
     */
    private fun countOffGrid(raw: List<FloatArray>, gcd: Double): Int {
        val tolerance = gcd * OFF_GRID_TOLERANCE
        var count = 0

        for (stream in raw) {
            for (value in stream) {
                if (abs(value - (value / gcd).roundToInt() * gcd) > tolerance) {
                    count++
                }
            }
        }
        return count
    }

    /** Fraction of a grid step the raw sample may sit off before it counts as wasted. */
    private const val OFF_GRID_TOLERANCE = 0.25

    private fun axis(streams: List<FloatArray>, channel: Int): FloatArray {
        val total = streams.sumOf { it.size / CHANNELS }
        val result = FloatArray(total)
        var index = 0

        for (stream in streams) {
            for (i in 0 until stream.size / CHANNELS) {
                result[index++] = stream[i * CHANNELS + channel]
            }
        }
        return result
    }

    private fun statsOf(steps: FloatArray): AxisStats {
        if (steps.isEmpty()) {
            return AxisStats(0f, 0f, FloatArray(AUTOCORRELATION_LAGS))
        }

        val zeros = steps.count { it == 0f }
        val meanAbs = steps.sumOf { abs(it).toDouble() } / steps.size

        return AxisStats(
            zeroFraction = zeros.toFloat() / steps.size,
            meanAbsStep = meanAbs.toFloat(),
            autocorrelation = FloatArray(AUTOCORRELATION_LAGS) { lag ->
                autocorrelation(steps, lag + 1)
            }
        )
    }

    /**
     * How strongly a step predicts the step [lag] ticks later. Human aim is strongly correlated at
     * short lags — a movement continues — while a memoryless generator is not, so this catches a
     * clone that produces the right step *sizes* in the wrong *order*.
     */
    private fun autocorrelation(values: FloatArray, lag: Int): Float {
        if (values.size <= lag + 1) {
            return 0f
        }

        var mean = 0.0
        for (value in values) {
            mean += value
        }
        mean /= values.size

        var covariance = 0.0
        var variance = 0.0
        for (i in values.indices) {
            val d = values[i] - mean
            variance += d * d
            if (i + lag < values.size) {
                covariance += d * (values[i + lag] - mean)
            }
        }

        return if (variance <= 0.0) 0f else (covariance / variance).toFloat()
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) {
            return 0f
        }

        var meanA = 0.0
        var meanB = 0.0
        for (i in 0 until size) {
            meanA += a[i]
            meanB += b[i]
        }
        meanA /= size
        meanB /= size

        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        for (i in 0 until size) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            covariance += da * db
            varianceA += da * da
            varianceB += db * db
        }

        val denominator = sqrt(varianceA * varianceB)
        return if (denominator <= 0.0) 0f else (covariance / denominator).toFloat()
    }

    /**
     * Largest gap between the two step-size distributions, in `[0, 1]`. Zero means the clone's
     * steps are distributed exactly like the player's.
     */
    private fun kolmogorovSmirnov(real: FloatArray, generated: FloatArray): Float {
        if (real.isEmpty() || generated.isEmpty()) {
            return Float.NaN
        }

        val realBins = histogram(real)
        val generatedBins = histogram(generated)

        var realSum = 0f
        var generatedSum = 0f
        var maximum = 0f
        for (i in 0 until HISTOGRAM_BINS) {
            realSum += realBins[i]
            generatedSum += generatedBins[i]
            maximum = max(maximum, abs(realSum - generatedSum))
        }
        return maximum
    }

    private fun histogram(values: FloatArray): FloatArray {
        val bins = FloatArray(HISTOGRAM_BINS)
        val half = HISTOGRAM_BINS / 2

        for (value in values) {
            val scaled = (value / HISTOGRAM_RANGE * half).roundToInt()
            bins[(scaled + half).coerceIn(0, HISTOGRAM_BINS - 1)]++
        }

        for (i in bins.indices) {
            bins[i] /= values.size
        }
        return bins
    }

    private const val AUTOCORRELATION_LAGS = 5

    /**
     * Roughly how many recorded ticks a profile needs before the discriminator can say anything.
     *
     * It wants a balanced set of non-overlapping windows on each side, drawn from the held-out
     * tenth of the profile — so the total requirement is an order of magnitude larger than what
     * the classifier itself consumes.
     */
    fun ticksNeededForDiscriminator(): Int =
        (WindowClassifier.MIN_WINDOWS_PER_CLASS * AimDiscriminator.WINDOW / VALIDATION_SHARE).toInt()

    private const val VALIDATION_SHARE = 0.1
}
