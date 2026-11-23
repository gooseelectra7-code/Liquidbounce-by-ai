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
package net.ccbluex.liquidbounce.utils.aiming.learned

import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotation.Curve
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Turns a recorded dataset into a [LearnedRotation]: the pattern library plus the handwriting
 * statistics. Port of the generation half of `troll.xyz.ai.AiTrainer` (`buildRotation`,
 * `buildPhrases`, `flushPhrase`, `roboticProfile`, `similarCurves`, `dedupe`, `subsample`),
 * numbers and classification rules as written.
 *
 * Segment classification:
 *  - SWING (flick): 3..40 ticks, |sum yaw| >= 8 deg, monotone >= 55% - becomes an aim speed curve
 *    (the normalized cumulative profile at replay time);
 *  - MICRO: 2..14 ticks, path 0.3..12 deg - settling / jitter;
 *  - PHRASE (v35): neighbouring segments glued across gaps of at most [PHRASE_GAP] ticks, cut into
 *    windows of [PHRASE_MAX_LEN]; only DIRECT chunks survive (v36) - most ticks move toward the
 *    chunk's net direction and the vertical does not dominate. The recording's look-arounds and
 *    idle roaming are what used to make the aim "wander along a recorded route instead of aiming";
 *  - everything else (long roaming, noise) is dropped.
 */
object LearnedRotationBuilder {

    /** Library caps so a rotation JSON does not grow to megabytes. */
    const val MAX_FLICKS = 120
    const val MAX_MICROS = 256

    /**
     * v35 phrases: a pause longer than [PHRASE_GAP] ticks breaks a chunk, a chunk is cut into windows
     * of [PHRASE_MAX_LEN] ticks and needs a path of at least 5 deg. v36: 28 instead of 40 - a replay
     * of 30-40 ticks sat on top of the attack window ("hits once in five years").
     */
    const val MAX_PHRASES = 36
    const val PHRASE_MAX_LEN = 28
    const val PHRASE_GAP = 3

    /** Swings shorter than this net yaw are not flicks; the same threshold the replay engine reads. */
    const val FLICK_MIN_YAW = 8.0
    const val FLICK_MIN_TICKS = 3
    const val FLICK_MAX_TICKS = 40
    const val FLICK_MONOTONE_SHARE = 0.55

    const val MICRO_MIN_TICKS = 2
    const val MICRO_MAX_TICKS = 14
    const val MICRO_MIN_PATH = 0.3
    const val MICRO_MAX_PATH = 12.0

    /** A phrase must net at least this much yaw to be "direct"; the replay engine reads it too. */
    const val PHRASE_MIN_YAW = 3.0
    private const val PHRASE_MIN_PATH = 5.0
    private const val PHRASE_MIN_TICKS = 4
    private const val PHRASE_DIRECT_SHARE = 0.55
    private const val PHRASE_SAME_DIR_SHARE = 0.60
    private const val PHRASE_PITCH_SLOPE = 0.9
    private const val PHRASE_PITCH_SLACK = 6.0

    private const val PAUSE_GAP_CAP = 60
    private const val MOVING_TICK = 0.15f
    private const val SPIKE_ABS = 1.2
    private const val SPIKE_LOCAL_FACTOR = 2.2
    private const val ATTACK_LOOKAHEAD = 5

    private const val ROBOTIC_MIN_TICKS = 5
    private const val ROBOTIC_MIN_MEAN = 0.05
    private const val ROBOTIC_CV = 0.18
    private const val SIMILAR_PATH_RATIO = 0.8
    private const val SIMILAR_MAX_DIFF = 0.15

    private const val T50_LOW = 0.22
    private const val T50_SPAN = 0.38

    private val random = Random()

    /**
     * Builds the rotation named [name] from [data]. `sourceTicks`, the time stamp and the name are
     * the caller's to fill in after the flick-count check.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    fun build(name: String, data: List<AimSegment>): LearnedRotation {
        val lr = LearnedRotation()
        lr.name = name
        lr.createdMs = System.currentTimeMillis()

        val flicks = ArrayList<Curve>()
        val micros = ArrayList<Curve>()
        var pitchRatioSum = 0.0
        var pitchRatioN = 0
        var microAmpSum = 0.0
        val durSum = IntArray(LearnedRotation.DURATION_BUCKETS)
        val durCnt = IntArray(LearnedRotation.DURATION_BUCKETS)
        var pauseSum = 0.0
        var pauseN = 0

        // v23: accumulators of the extended handwriting
        val t50s = ArrayList<Double>()
        var spikeTicks = 0
        var flickTicks = 0
        var dirFlips = 0
        var jerkSum = 0.0
        var jerkN = 0
        var headPSum = 0.0
        var headPSqSum = 0.0
        var headPN = 0
        val atkLags = ArrayList<Int>()
        var atkFound = 0

        for (i in data.indices) {
            val s = data[i]
            val n = s.length
            val sumY = s.sumYaw()
            val absSum = s.absSum()

            // pauses: the gap to the next segment
            if (i + 1 < data.size) {
                val gap = data[i + 1].t - (s.t + n)
                if (gap > 0) {
                    pauseSum += min(gap, PAUSE_GAP_CAP)
                    pauseN += min(gap, PAUSE_GAP_CAP) + n
                }
            }

            if (n in FLICK_MIN_TICKS..FLICK_MAX_TICKS && abs(sumY) >= FLICK_MIN_YAW) {
                var same = 0
                for (v in s.dy) {
                    if (sign(v.toDouble()) == sign(sumY)) {
                        same++
                    }
                }
                if (same / n.toDouble() >= FLICK_MONOTONE_SHARE) {
                    val curve = toCurve(s)
                    if (roboticProfile(curve.dy)) {
                        continue // v32: uniform speed is aimbot handwriting, never replayed
                    }
                    flicks.add(curve)
                    t50s.add(curve.computeT50())
                    val b = LearnedRotation.bucket(abs(sumY))
                    durSum[b] += n
                    durCnt[b]++
                    pitchRatioSum += abs(s.sumPitch()) / max(2.0, abs(sumY))
                    pitchRatioN++

                    // v23: sharpness / jerk / direction changes over the body of the flick
                    flickTicks += n
                    var prevSign = 0f
                    var prevAbs = 0f
                    for (j in 0 until n) {
                        val v = s.dy[j]
                        val av = abs(v)
                        // local mean over a 5-tick window of the flick body
                        var loc = 0.0
                        var cnt = 0
                        for (k in max(0, j - 2)..min(n - 1, j + 2)) {
                            loc += abs(s.dy[k])
                            cnt++
                        }
                        loc /= cnt
                        if (av > SPIKE_ABS && av > SPIKE_LOCAL_FACTOR * loc) {
                            spikeTicks++
                        }
                        if (av > MOVING_TICK) {
                            val sgn = sign(v)
                            if (prevSign != 0f && sgn != prevSign) {
                                dirFlips++
                            }
                            prevSign = sgn
                        }
                        if (j > 0) {
                            jerkSum += abs(av - prevAbs)
                            jerkN++
                        }
                        prevAbs = av
                    }
                    // attack profile: a click (FLAG_CLICK) in the flick body or up to 5 ticks after
                    var lag = -1
                    var k = 0
                    while (k < n && lag < 0) {
                        if (s.fl[k] and AimSegment.FLAG_CLICK != 0) {
                            lag = k
                        }
                        k++
                    }
                    if (lag < 0 && i + 1 < data.size) {
                        val next = data[i + 1]
                        var m = 0
                        while (m < min(ATTACK_LOOKAHEAD, next.length) && lag < 0) {
                            if (next.fl[m] and AimSegment.FLAG_CLICK != 0) {
                                lag = n + m
                            }
                            m++
                        }
                    }
                    if (lag >= 0 && lag <= n + ATTACK_LOOKAHEAD) {
                        atkFound++
                        atkLags.add(lag)
                    }
                    continue
                }
            }
            if (n in MICRO_MIN_TICKS..MICRO_MAX_TICKS && absSum >= MICRO_MIN_PATH && absSum <= MICRO_MAX_PATH) {
                micros.add(toCurve(s))
                microAmpSum += absSum / n
            }

            // v23: head pitch over every recorded moving tick that was not a flick (as written)
            val mp = s.mp
            if (mp != null) {
                for (v in mp) {
                    headPSum += v
                    headPSqSum += v.toDouble() * v
                    headPN++
                }
            }
        }

        // v32: dedupe before subsampling so the library slots are not spent on twins
        lr.flicks = subsample(dedupe(flicks), MAX_FLICKS)
        lr.micros = subsample(dedupe(micros), MAX_MICROS)
        lr.phrases = buildPhrases(data)
        lr.pitchFollow = if (pitchRatioN > 0) {
            (pitchRatioSum / pitchRatioN).coerceIn(LearnedRotation.MIN_PITCH_FOLLOW, LearnedRotation.MAX_PITCH_FOLLOW)
        } else {
            LearnedRotation.DEFAULT_PITCH_FOLLOW
        }
        // The mean is over every micro found, not over the deduped library - as written.
        lr.microAmp = if (micros.isNotEmpty()) {
            (microAmpSum / micros.size).coerceIn(LearnedRotation.MIN_MICRO_AMP, LearnedRotation.MAX_MICRO_AMP)
        } else {
            LearnedRotation.DEFAULT_MICRO_AMP
        }
        val durProfile = DoubleArray(LearnedRotation.DURATION_BUCKETS)
        for (b in 0 until LearnedRotation.DURATION_BUCKETS) {
            durProfile[b] = if (durCnt[b] > 0) durSum[b].toDouble() / durCnt[b] else 0.0
        }
        lr.durProfile = durProfile
        val pr = if (pauseN > 0) pauseSum / pauseN else -1.0
        lr.pauseRate = if (pr >= 0.0) {
            pr.coerceIn(LearnedRotation.MIN_PAUSE_RATE, LearnedRotation.MAX_PAUSE_RATE)
        } else {
            LearnedRotation.DEFAULT_PAUSE_RATE
        }

        // ===== v23: extended handwriting (smoothness / sharpness, head, attack profile) =====
        if (t50s.isNotEmpty()) {
            t50s.sort()
            val med = if (t50s.size % 2 == 1) {
                t50s[t50s.size / 2]
            } else {
                0.5 * (t50s[t50s.size / 2 - 1] + t50s[t50s.size / 2])
            }
            lr.smoothness = ((med - T50_LOW) / T50_SPAN).coerceIn(0.0, 1.0)
        }
        lr.spikeRate = if (flickTicks > 0) {
            (spikeTicks / flickTicks.toDouble()).coerceIn(0.0, 0.5)
        } else {
            LearnedRotation.DEFAULT_SPIKE_RATE
        }
        lr.dirChangeRate = if (flickTicks > 0) {
            dirFlips * 100.0 / flickTicks
        } else {
            LearnedRotation.DEFAULT_DIR_CHANGE_RATE
        }
        lr.jerkAmp = if (jerkN > 0) (jerkSum / jerkN).coerceIn(0.05, 4.0) else LearnedRotation.DEFAULT_JERK_AMP
        if (headPN > 0) {
            val mean = headPSum / headPN
            lr.headPitchMean = mean.coerceIn(-90.0, 90.0)
            val variance = max(0.0, headPSqSum / headPN - mean * mean)
            lr.headPitchStd = sqrt(variance).coerceIn(0.5, 90.0)
        }
        lr.attackSyncRate = if (t50s.isEmpty()) 0.0 else atkFound / t50s.size.toDouble()
        if (atkLags.isNotEmpty()) {
            atkLags.sort()
            val lo = atkLags[min(atkLags.size - 1, floor(atkLags.size * 0.25).toInt())]
            val hi = atkLags[min(atkLags.size - 1, ceil(atkLags.size * 0.75).toInt() - 1)]
            lr.attackDelayMin = max(0, lo)
            lr.attackDelayMax = max(lr.attackDelayMin, hi)
        }
        return lr
    }

    // ===================== v35: PHRASES (whole recorded chunks) =====================

    /**
     * v35: glues neighbouring recorded segments into PHRASES - whole chunks of hand movement
     * (reaction -> swing -> settle -> micro-pauses). Before, everything that was not a flick or a
     * micro was thrown away and the in-fight movement was assembled from synthesis - hence "odd
     * movements that were not in the recording". A chunk now plays AS RECORDED (1:1 cumulative
     * profile).
     */
    private fun buildPhrases(data: List<AimSegment>): MutableList<Curve> {
        val out = ArrayList<Curve>()
        val phY = ArrayList<Float>()
        val phP = ArrayList<Float>()
        var prevEnd = Int.MIN_VALUE
        for (i in data.indices) {
            val s = data[i]
            val gap = if (i == 0) Int.MAX_VALUE else s.t - prevEnd
            if (gap > PHRASE_GAP) {
                flushPhrase(out, phY, phP) // a long pause closes the chunk
            }
            for (j in 0 until s.length) {
                phY.add(s.dy[j])
                phP.add(s.dp[j])
                if (phY.size >= PHRASE_MAX_LEN) {
                    flushPhrase(out, phY, phP) // window full - the chunk continues as a new window
                }
            }
            prevEnd = s.t + s.length
        }
        flushPhrase(out, phY, phP)
        return subsample(dedupe(out), MAX_PHRASES) // v32: no twins
    }

    /**
     * Commits the accumulated window to the phrase library. v36: DIRECT chunks only - most ticks
     * move toward the chunk's net direction and the vertical does not dominate; the recording's
     * look-arounds stay out of the fight.
     */
    private fun flushPhrase(out: MutableList<Curve>, phY: MutableList<Float>, phP: MutableList<Float>) {
        val n = phY.size
        if (n >= PHRASE_MIN_TICKS) {
            var path = 0.0
            var sumY = 0.0
            var absSumY = 0.0
            var absSumP = 0.0
            for (i in 0 until n) {
                val dy = phY[i]
                val dp = phP[i]
                path += abs(dy) + abs(dp)
                sumY += dy
                absSumY += abs(dy)
                absSumP += abs(dp)
            }
            var sameDir = 0
            for (i in 0 until n) {
                val dy = phY[i]
                if (abs(dy) > MOVING_TICK && sign(dy.toDouble()) == sign(sumY)) {
                    sameDir++ // the tick moves toward the chunk's net direction
                }
            }
            val direct = abs(sumY) >= PHRASE_MIN_YAW && // the chunk really turns somewhere
                abs(sumY) >= PHRASE_DIRECT_SHARE * absSumY && // not smeared by opposing twitches
                sameDir / n.toDouble() >= PHRASE_SAME_DIR_SHARE && // monotone like a flick (55%+)
                absSumP <= abs(sumY) * PHRASE_PITCH_SLOPE + PHRASE_PITCH_SLACK // vertical not dominant
            if (path >= PHRASE_MIN_PATH && direct) {
                val dy = DoubleArray(n) { phY[it].toDouble() }
                val dp = DoubleArray(n) { phP[it].toDouble() }
                out.add(Curve(dy, dp))
            }
        }
        phY.clear()
        phP.clear()
    }

    private fun toCurve(s: AimSegment): Curve {
        val dy = DoubleArray(s.dy.size) { s.dy[it].toDouble() }
        val dp = DoubleArray(s.dp.size) { s.dp[it].toDouble() }
        return Curve(dy, dp)
    }

    private fun <T> subsample(list: MutableList<T>, cap: Int): MutableList<T> {
        if (list.size <= cap) {
            return list
        }
        val out = ArrayList<T>(cap)
        repeat(cap) {
            out.add(list.removeAt(random.nextInt(list.size)))
        }
        return out
    }

    // ===================== v32: LIBRARY QUALITY =====================

    /**
     * v32: a "robotic" swing profile - almost CONSTANT speed over the body of the swing
     * (CV = sigma / mean |dy| < 0.18 with n >= 5). A human hand draws a bell: run-up -> peak ->
     * braking (CV usually 0.3-0.8); uniform deltas are the handwriting of an aimbot or a script,
     * and never enter the library so the replay does not reproduce machine monotony.
     */
    private fun roboticProfile(dy: DoubleArray?): Boolean {
        if (dy == null || dy.size < ROBOTIC_MIN_TICKS) {
            return false // short segments are not judged
        }
        val n = dy.size
        var mean = 0.0
        for (v in dy) {
            mean += abs(v)
        }
        mean /= n
        if (mean < ROBOTIC_MIN_MEAN) {
            return false // microscopic noise is not a swing
        }
        var variance = 0.0
        for (v in dy) {
            val d = abs(v) - mean
            variance += d * d
        }
        return sqrt(variance / n) / mean < ROBOTIC_CV
    }

    /**
     * v32: whether two curves are alike - same length, total amplitudes within 20%, normalized
     * cumulative profiles never more than 15% of the path apart.
     */
    private fun similarCurves(a: Curve, b: Curve): Boolean {
        val ady = a.dy ?: return false
        val bdy = b.dy ?: return false
        if (ady.size != bdy.size) {
            return false
        }
        val n = ady.size
        val pa = a.path()
        val pb = b.path()
        if (pa < 1e-6 || pb < 1e-6) {
            return false
        }
        if (min(pa, pb) / max(pa, pb) < SIMILAR_PATH_RATIO) {
            return false
        }
        var ca = 0.0
        var cb = 0.0
        var maxDiff = 0.0
        val adp = a.dp
        val bdp = b.dp
        for (i in 0 until n) {
            var addA = abs(ady[i])
            var addB = abs(bdy[i])
            if (adp != null) {
                addA += abs(adp[i])
            }
            if (bdp != null) {
                addB += abs(bdp[i])
            }
            ca += addA / pa
            cb += addB / pb
            maxDiff = max(maxDiff, abs(ca - cb))
        }
        return maxDiff < SIMILAR_MAX_DIFF
    }

    /**
     * v32: library deduplication - the first curve seen wins, twins ([similarCurves]) are dropped.
     * The MAX_* slots go to DIFFERENT movements, not to ten copies of the same swing.
     */
    private fun dedupe(input: List<Curve>): MutableList<Curve> {
        val out = ArrayList<Curve>(input.size)
        for (c in input) {
            var dup = false
            var i = 0
            while (i < out.size && !dup) {
                dup = similarCurves(c, out[i])
                i++
            }
            if (!dup) {
                out.add(c)
            }
        }
        return out
    }
}
