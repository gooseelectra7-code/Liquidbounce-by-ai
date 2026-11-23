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

import kotlin.math.abs
import kotlin.math.max

/**
 * A learned rotation: a library of movement patterns built from a DATASET of the player's own
 * mouse movement (`.ai start`, usually 15 minutes of manual PvP).
 *
 * Port of `troll.xyz.ai.LearnedRotation` (source supplied by the user on 2026-09-19). Per that
 * client's tutorial ("the evolution of jitter"): PATTERNS INSTEAD OF SINE WAVES. Every trajectory
 * in here is a REAL recorded hand movement, so the microstructure of the deltas (standard
 * deviations, accelerations, pauses) is human by construction, not by formula.
 *
 * The JSON field names are the original's, byte for byte, so a `<name>.json` written by the
 * original client loads here unchanged and vice versa:
 * ```
 * {
 *   "name": "bypass", "createdMs": ..., "sourceTicks": 18000,
 *   "pitchFollow": 0.42,          - how much the vertical follows the horizontal
 *   "pauseRate": 0.31,            - share of ticks where the look stands completely still
 *   "microAmp": 0.55,             - mean amplitude of a micro-movement per tick (degrees)
 *   "durProfile": [1.5,3.0,...],  - mean swing duration per amplitude bucket
 *   "flicks": [{"dy":[...],"dp":[...]}],   - swings (|yaw| >= 8 deg, monotone)
 *   "micros": [{"dy":[...],"dp":[...]}]    - micro-movements (settling / jitter)
 *
 *   v23 - extended handwriting (head turn recording, smoothness / sharpness):
 *   "smoothness": 0.62,     - 0..1: median t50 of the swings (0 sharp jerk, 1 smooth S-swing)
 *   "spikeRate": 0.07,      - share of spike ticks (|dy| far above the local mean)
 *   "dirChangeRate": 6.4,   - yaw direction changes per 100 ticks (a jittery hand)
 *   "jerkAmp": 0.35,        - mean |jerk| = |dy[i] - dy[i-1]| inside swings
 *   "headPitchMean/Std": -8.2 / 14,  - the favourite head pitch zone (from the recording)
 *   "attackSyncRate": 0.71, - share of swings with a click next to them
 *   "attackDelayMin/Max": 1 / 3      - ticks from swing start to the click (attack profile)
 *
 *   v35 - PHRASES:
 *   "phrases": [{"dy":[...],"dp":[...]}]   - WHOLE recorded chunks (reaction -> swing -> settle
 *   -> pauses, up to 28 ticks in one piece). Played 1:1 in a fight: the whole microstructure of
 *   the movement comes from the recording, zero synthesis. Older files load without them and the
 *   engine falls back to flicks / micros.
 * }
 * ```
 *
 * Gson reads and writes the fields directly (the caches are `@Transient`). Every field is nullable
 * on the Kotlin side because Gson bypasses Kotlin null-safety when a key is missing from an older
 * file; [sanitized] fills the gaps exactly the way the original's `loadRotation` does.
 */
class LearnedRotation {

    /** Both axes of one recorded segment: per-tick deltas in degrees. */
    class Curve() {
        var dy: DoubleArray? = null
        var dp: DoubleArray? = null

        /**
         * v23: t50, the fraction of the swing at which half the path is covered (cache, never
         * written). ~0.3 is a sharp spike-like jerk, ~0.5 a symmetric S-swing, > 0.55 a sluggish
         * run-up.
         */
        @Transient
        var t50 = -1.0

        constructor(dy: DoubleArray, dp: DoubleArray) : this() {
            this.dy = dy
            this.dp = dp
        }

        /**
         * t50 of the curve by yaw: where the normalized cumulative path first reaches 0.5, linearly
         * interpolated between the recording's ticks; unsigned deltas (path, not displacement).
         */
        @Suppress("ReturnCount")
        fun computeT50(): Double {
            if (t50 >= 0.0) {
                return t50
            }
            t50 = 0.5
            val dy = dy ?: return t50
            if (dy.size < 2) {
                return t50
            }
            var total = 0.0
            for (v in dy) {
                total += abs(v)
            }
            if (total < 1e-6) {
                return t50
            }
            var acc = 0.0
            for (i in dy.indices) {
                val prev = acc / total
                acc += abs(dy[i])
                val cur = acc / total
                if (prev < 0.5 && cur >= 0.5) {
                    val f = (0.5 - prev) / max(1e-9, cur - prev)
                    t50 = (i + f) / dy.size
                    return t50
                }
            }
            t50 = 1.0
            return t50
        }

        fun length(): Int = dy?.size ?: 0

        /** Total path |yaw| + |pitch| over the segment. */
        fun path(): Double {
            var s = 0.0
            dy?.forEach { s += abs(it) }
            dp?.forEach { s += abs(it) }
            return s
        }

        /** Signed yaw sum: the segment's net turn. */
        fun sumYaw(): Double {
            var s = 0.0
            dy?.forEach { s += it }
            return s
        }

        /** Signed pitch sum: the segment's net tilt. */
        fun sumPitch(): Double {
            var s = 0.0
            dp?.forEach { s += it }
            return s
        }
    }

    var name: String? = null
    var createdMs: Long = 0L

    /** How many recorded ticks went into this rotation (the size of the training sample). */
    var sourceTicks: Int = 0

    /** Mean |pitch| / |yaw| of the swings: 0.1 means the player drives almost only the horizontal. */
    var pitchFollow: Double = DEFAULT_PITCH_FOLLOW

    /** Share of recorded ticks with no mouse movement: the handwriting of pauses. */
    var pauseRate: Double = DEFAULT_PAUSE_RATE

    /** Mean amplitude of a micro-movement per tick (degrees). */
    var microAmp: Double = DEFAULT_MICRO_AMP

    /** Mean swing duration per amplitude bucket <2 / <6 / <15 / <40 / <90 / >=90 deg; 0 = no data. */
    var durProfile: DoubleArray? = DoubleArray(DURATION_BUCKETS)

    var flicks: MutableList<Curve>? = ArrayList()
    var micros: MutableList<Curve>? = ArrayList()

    /** v35: whole recorded chunks (up to 28 ticks): swing + settle + pauses in one piece. */
    var phrases: MutableList<Curve>? = ArrayList()

    // ===== v23: extended handwriting (smoothness / sharpness, head, attack profile) =====

    /**
     * 0..1: the median t50 of the swings mapped 0.22..0.60 -> 0..1. 0 is a hand that "clicks"
     * (sharp jerk), 1 a smooth long S-swing.
     */
    var smoothness: Double = 0.5

    /** Share of swing ticks where |dy| > 2.2x the local mean over a 5-tick window (sharp spikes). */
    var spikeRate: Double = DEFAULT_SPIKE_RATE

    /** Yaw direction changes per 100 swing ticks (a jittery hand twitches often). */
    var dirChangeRate: Double = DEFAULT_DIR_CHANGE_RATE

    /** Mean |jerk| (|dy[i] - dy[i-1]|) inside swings: how sharply the speed changes. */
    var jerkAmp: Double = DEFAULT_JERK_AMP

    /** Mean head pitch while moving: the favourite look zone. */
    var headPitchMean: Double = 0.0

    /** Spread of the head pitch (std): how much the player roams vertically. */
    var headPitchStd: Double = DEFAULT_HEAD_PITCH_STD

    /** Share of swings followed by a click (swing) within 5 ticks. */
    var attackSyncRate: Double = 0.0

    /** Min / max ticks from the start of a swing to the click (attack profile from the dataset). */
    var attackDelayMin: Int = 0
    var attackDelayMax: Int = 0

    /** Median t50 over the flick library (cache, not serialized). */
    @Transient
    var t50Med = -1.0

    fun medianT50(): Double {
        if (t50Med >= 0.0) {
            return t50Med
        }
        val flicks = flicks.orEmpty()
        if (flicks.isEmpty()) {
            t50Med = 0.5
            return t50Med
        }
        val v = DoubleArray(flicks.size) { flicks[it].computeT50() }
        v.sort()
        t50Med = if (v.size and 1 == 1) {
            v[v.size / 2]
        } else {
            0.5 * (v[v.size / 2 - 1] + v[v.size / 2])
        }
        return t50Med
    }

    /**
     * Fills in whatever an older file (or a hand-edited one) left out and pulls the v23 fields back
     * into range, exactly as the original's `loadRotation` does after `fromJson`.
     */
    fun sanitized(name: String): LearnedRotation {
        this.name = name
        if (durProfile?.size != DURATION_BUCKETS) {
            durProfile = DoubleArray(DURATION_BUCKETS)
        }
        val flicks = flicks ?: ArrayList<Curve>().also { flicks = it }
        val micros = micros ?: ArrayList<Curve>().also { micros = it }
        // Rotations from before v35 have no phrases; the engine falls back to flicks.
        val phrases = phrases ?: ArrayList<Curve>().also { phrases = it }

        // A curve with a missing or mismatched axis is unusable; drop the whole entry.
        flicks.removeAll(::isBroken)
        micros.removeAll(::isBroken)
        phrases.removeAll(::isBroken)

        // The three the builder clamps on the way out are clamped on the way in as well: a
        // hand-edited or foreign file with a negative pitchFollow would invert a coerceIn range in
        // the engine (a crash every tick), a negative microAmp mirrors every raw micro.
        pitchFollow = sane(pitchFollow, MIN_PITCH_FOLLOW, MAX_PITCH_FOLLOW, DEFAULT_PITCH_FOLLOW)
        pauseRate = sane(pauseRate, MIN_PAUSE_RATE, MAX_PAUSE_RATE, DEFAULT_PAUSE_RATE)
        microAmp = sane(microAmp, MIN_MICRO_AMP, MAX_MICRO_AMP, DEFAULT_MICRO_AMP)

        if (spikeRate < 0.0 || spikeRate > MAX_SANE_SPIKE_RATE) {
            spikeRate = DEFAULT_SPIKE_RATE
        }
        if (dirChangeRate < 0.0 || dirChangeRate > MAX_SANE_DIR_CHANGE_RATE) {
            dirChangeRate = DEFAULT_DIR_CHANGE_RATE
        }
        if (jerkAmp < 0.0 || jerkAmp > MAX_SANE_JERK_AMP) {
            jerkAmp = DEFAULT_JERK_AMP
        }
        if (attackDelayMin < 0 || attackDelayMax < attackDelayMin) {
            attackDelayMin = 0
            attackDelayMax = 0
        }
        if (attackSyncRate < 0.0 || attackSyncRate > 1.0) {
            attackSyncRate = 0.0
        }
        return this
    }

    private fun isBroken(curve: Curve?): Boolean {
        val dy = curve?.dy ?: return true
        val dp = curve.dp ?: return true
        return dy.size != dp.size || dy.isEmpty()
    }

    /** [value] pulled into [lo]..[hi]; NaN (which no comparison catches) becomes [default]. */
    private fun sane(value: Double, lo: Double, hi: Double, default: Double): Double =
        if (value.isNaN()) default else value.coerceIn(lo, hi)

    companion object {
        const val DURATION_BUCKETS = 6

        const val DEFAULT_PITCH_FOLLOW = 0.35
        const val DEFAULT_PAUSE_RATE = 0.35
        const val DEFAULT_MICRO_AMP = 0.5
        const val DEFAULT_SPIKE_RATE = 0.08
        const val DEFAULT_DIR_CHANGE_RATE = 8.0
        const val DEFAULT_JERK_AMP = 0.35
        const val DEFAULT_HEAD_PITCH_STD = 15.0

        private const val MAX_SANE_SPIKE_RATE = 0.6
        private const val MAX_SANE_DIR_CHANGE_RATE = 120.0
        private const val MAX_SANE_JERK_AMP = 8.0

        /** The builder's output clamps, applied on load too (see [sanitized]). */
        const val MIN_PITCH_FOLLOW = 0.05
        const val MAX_PITCH_FOLLOW = 1.2
        const val MIN_PAUSE_RATE = 0.05
        const val MAX_PAUSE_RATE = 0.9
        const val MIN_MICRO_AMP = 0.12
        const val MAX_MICRO_AMP = 2.0

        /**
         * Amplitude bucket of a swing for [durProfile]: <2 / <6 / <15 / <40 / <90 / >=90 degrees.
         */
        @Suppress("MagicNumber")
        fun bucket(absYaw: Double): Int = when {
            absYaw < 2 -> 0
            absYaw < 6 -> 1
            absYaw < 15 -> 2
            absYaw < 40 -> 3
            absYaw < 90 -> 4
            else -> 5
        }
    }
}
