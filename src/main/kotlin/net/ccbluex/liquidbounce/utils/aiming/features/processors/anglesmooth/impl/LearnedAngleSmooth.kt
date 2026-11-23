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
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.learned.AiHumanizer
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotation
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotation.Curve
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotationBuilder
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotations
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.entity.box
import net.minecraft.world.entity.Entity
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * "Learned": replays the player's OWN recorded hand movement at a live target.
 *
 * The library it plays from is a [LearnedRotation] - flicks, micro-movements and (v35) whole
 * recorded phrases cut out of a dataset by [LearnedRotationBuilder] - selected in [rotationChoice]
 * and built with `.ai start` / `.ai stop` / `.ai create <name>`. The data model, the recorder and
 * the library builder are 1:1 ports of the `troll.xyz.ai` sources the user supplied on 2026-09-19.
 *
 * ## What is a port and what is a reconstruction
 *
 * The user supplied `AiTrainer`, `LearnedRotation` and `AiHumanizer`, not the playback half of
 * that client's `KillauraModule`. The engine below is therefore RECONSTRUCTED from what those
 * three files say about it, and nothing more:
 *
 *  - "a swing is played as the normalized cumulative profile of a recorded flick, stretched onto
 *    the live target - the turn speed on every tick matches the player's hand, not a
 *    mathematical curve" -> [Replay.step]: the signed cumulative profile of the curve, and on every
 *    tick the REMAINING part of it is mapped onto the CURRENT residual, so a moving target is
 *    tracked while the tick-by-tick shape stays the recording's;
 *  - "micro-settling plays the recorded micro segments" -> [settle] (a convergent replay of a
 *    micro over a residual below the flick threshold) and [onTarget] (raw micro deltas, scaled
 *    to [LearnedRotation.microAmp] and capped in net drift, while the crosshair already sits on
 *    the box);
 *  - "delta clamps |yaw| <= 60 / |pitch| <= 24 and GCD quantization, as in the tutorial" ->
 *    [MAX_YAW_STEP] / [MAX_PITCH_STEP] and [quantize], which rounds onto the mouse grid and
 *    carries the sub-count remainder so a slow phrase is not rounded away;
 *  - "phrases ... are played 1:1 in a fight; older files fall back to flicks / micros" ->
 *    [swing] prefers the phrase library when [usePhrases] is on and the file has one;
 *  - `durProfile` -> the duration hint when choosing a swing, `pauseRate` -> the share of ticks
 *    the look stands completely still ([pauses]), `pitchFollow` -> the per-tick cap on how far
 *    the vertical follows the horizontal when a curve carries no pitch structure of its own;
 *  - `AiHumanizer.adaptiveSpeed` / `clampSpeed` / `jitter` -> the replay rate, its clamp around
 *    the library's median swing speed, and the sub-count jitter on every non-zero step.
 *
 * Every decision above that the sources do not spell out is a GUESS by this port, named in the
 * KDoc of the method that makes it. In particular the attack profile (`attackSyncRate`,
 * `attackDelayMin/Max`) is computed and shown by `.ai create` but NOT wired into KillAura's
 * clicker - that half of the original engine was never seen.
 *
 * ## Invariants
 *
 *  - Output is a whole number of mouse counts with `isNormalized = true` and the post-processors
 *    suppressed (as for Clone and Shard): the recorded microstructure IS the humanization, and
 *    Fail/ShortStop/Jitter would change the replayed movement.
 *  - A pause is exactly zero on the wire - [AiHumanizer.jitter] is applied to non-zero steps only.
 *  - With no entity (the hand-back, or a block) this mode is a linear grid-truncated return with a
 *    guaranteed one-count step, the same reasoning as Shard: a replay that is a fraction of the
 *    residual never lands exactly and `RotationManager` releases the override only once it does.
 *  - A target switch, a skipped tick or a library swap abandons the replay in flight and starts a
 *    fresh reaction; the sub-count carry is dropped with it.
 */
@Suppress("TooManyFunctions")
class LearnedAngleSmooth(parent: ModeValueGroup<*>) : AngleSmooth(
    "Learned",
    parent,
    arrayListOf("AI", "Trained", "Dataset")
) {

    /** Which library file drives the aim; rebuilt by [LearnedRotations] on every reload. */
    private val rotationChoice = modes<LearnedRotations.Choice>("Rotation", 0) { group ->
        LearnedRotations.register(group)
    }

    /** Recorded ticks consumed per real tick: 1 replays the hand at the speed it was recorded. */
    private val speed by float("Speed", 1f, 0.25f..3f)

    /** [AiHumanizer.adaptiveSpeed]: far / steep targets are snapped faster, close ones stay smooth. */
    private val adaptive by boolean("Adaptive", true)

    /** Play whole recorded phrases (v35) when the file has them, instead of composing flicks. */
    private val usePhrases by boolean("Phrases", true)

    /** Stand completely still for the recording's share of still ticks (`pauseRate`). */
    private val pauses by boolean("Pauses", true)

    /** [AiHumanizer.jitter] on every non-zero step so the aim never sits on a perfect line. */
    private val microJitter by boolean("MicroJitter", true)

    /**
     * Linear speed of the two paths that are not a replay: the hand-back with no entity, and the
     * plain step taken when the library has nothing that fits (an empty or broken file).
     */
    private val returnSpeed by float("ReturnSpeed", 45f, 5f..180f, "°/tick")

    /**
     * Used whenever no rotation is selected. Explicit turn speeds because the default is a
     * 180°/tick snap, which is what "Learned with nothing recorded yet" would otherwise be.
     */
    private val fallback = LinearAngleSmooth(
        parent,
        horizontalTurnSpeed = 18f..32f,
        verticalTurnSpeed = 12f..22f
    )

    /** The selected library, or null (then [fallback] drives and the post-processors are back). */
    val library: LearnedRotation?
        get() = rotationChoice.activeMode.rotation

    override val suppressesPostProcessors: Boolean
        get() = library != null

    /** The passed point may be the only visible part of a partly occluded hitbox; do not replace it. */
    override val derivesTargetFromEntity: Boolean
        get() = false

    // --- State -------------------------------------------------------------------------------

    private enum class Phase { IDLE, SWING, SETTLE, JITTER, PAUSE }

    private enum class Kind { PHRASE, FLICK, MICRO }

    private var boundLibrary: LearnedRotation? = null
    private var stats: LibraryStats? = null
    private var tracked: Entity? = null
    private var lastTick = -1
    private var replay: Replay? = null
    private var pauseTicks = 0
    private var carryYaw = 0.0
    private var carryPitch = 0.0
    private var lastCurve: Curve? = null
    private var phase = Phase.IDLE

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val lr = library
        if (lr == null) {
            resetState()
            notifyUnavailable()
            return fallback.process(rotationTarget, currentRotation, targetRotation)
        }
        bind(lr)

        val entity = rotationTarget.entity
        if (entity == null) {
            resetState()
            return converge(currentRotation, targetRotation)
        }

        val tick = player.tickCount
        // A respawn restarts tickCount; a skipped invocation broke the stream. Both start cold.
        if (lastTick >= 0 && (tick < lastTick || tick - lastTick > 1)) {
            resetState()
        }
        lastTick = tick
        if (tracked !== entity) {
            // A new target is a new reaction: whatever segment was playing is abandoned.
            tracked = entity
            replay = null
            pauseTicks = 0
            carryYaw = 0.0
            carryPitch = 0.0
        }

        val gcd = RotationUtil.gcd
        val residualYaw = RotationUtil.angleDifference(targetRotation.yaw, currentRotation.yaw).toDouble()
        val residualPitch = (targetRotation.pitch - currentRotation.pitch).toDouble()
        val distance = player.eyePosition.distanceTo(entity.box.center)

        var stepYaw = 0.0
        var stepPitch = 0.0
        if (pauseTicks > 0) {
            pauseTicks--
            phase = Phase.PAUSE
        } else {
            var current = replay
            // A raw on-target micro does not track: if the target has walked out from under it,
            // the hand notices and starts a real correction instead of finishing the twitch.
            if (current != null && current.raw &&
                max(abs(residualYaw), abs(residualPitch)) > RAW_ABORT_RESIDUAL
            ) {
                current = null
            }
            if (current == null || current.done) {
                current = nextSegment(lr, residualYaw, residualPitch, distance, gcd)
                replay = current
            }
            if (current == null) {
                if (pauseTicks > 0) {
                    // nextSegment scheduled a pause: this tick is its first still tick.
                    phase = Phase.PAUSE
                } else if (max(abs(residualYaw), abs(residualPitch)) > max(gcd, ON_TARGET_TOLERANCE)) {
                    // Nothing in the library fits (an empty or broken file): plain linear so the
                    // aim still arrives rather than freezing.
                    stepYaw = residualYaw.coerceIn(-returnSpeed.toDouble(), returnSpeed.toDouble())
                    stepPitch = residualPitch.coerceIn(-returnSpeed.toDouble(), returnSpeed.toDouble())
                    phase = Phase.IDLE
                } else {
                    phase = Phase.IDLE
                }
            } else {
                val step = current.step(residualYaw, residualPitch, lr.pitchFollow)
                stepYaw = step[0]
                stepPitch = step[1]
                phase = when {
                    current.raw -> Phase.JITTER
                    current.kind == Kind.MICRO -> Phase.SETTLE
                    else -> Phase.SWING
                }
                if (current.done) {
                    replay = null
                    // The recording's still ticks are not only on target: a short stop between
                    // two segments with the same probability. GUESS - the sources give the share,
                    // not where it sits.
                    if (pauses && !current.raw && ThreadLocalRandom.current().nextDouble() < lr.pauseRate) {
                        pauseTicks = 1
                    }
                }
            }
        }

        // Per axis, not per pair: a recorded tick that moved the pitch only had a yaw of exactly
        // zero, and a real mouse produces exactly that, so the jitter must not touch it.
        if (microJitter && stepYaw != 0.0) {
            stepYaw = AiHumanizer.jitterYaw(stepYaw)
        }
        if (microJitter && stepPitch != 0.0) {
            stepPitch = AiHumanizer.jitterPitch(stepPitch)
        }
        stepYaw = stepYaw.coerceIn(-MAX_YAW_STEP, MAX_YAW_STEP)
        stepPitch = stepPitch.coerceIn(-MAX_PITCH_STEP, MAX_PITCH_STEP)

        val quantizedYaw = quantize(stepYaw, gcd, yaw = true)
        // The pitch is bounded in COUNTS, not clamped after the fact: a clamp on the sum would put
        // a fractional delta on the wire at the poles.
        val quantizedPitch = quantize(
            stepPitch, gcd, yaw = false,
            minDelta = -PITCH_CLAMP - currentRotation.pitch.toDouble(),
            maxDelta = PITCH_CLAMP - currentRotation.pitch.toDouble()
        )

        ModuleDebug.debugParameter(this, "Phase", phase)
        ModuleDebug.debugParameter(this, "Segment", replay?.let { "${it.kind} ${it.length}t @${"%.1f".format(it.cursor)}" } ?: "-")
        ModuleDebug.debugParameter(this, "Step", "%.2f / %.2f".format(quantizedYaw, quantizedPitch))

        return Rotation(
            currentRotation.yaw + quantizedYaw,
            (currentRotation.pitch + quantizedPitch).coerceIn(-PITCH_CLAMP, PITCH_CLAMP),
            isNormalized = true
        )
    }

    private fun resetState() {
        tracked = null
        lastTick = -1
        replay = null
        pauseTicks = 0
        carryYaw = 0.0
        carryPitch = 0.0
        phase = Phase.IDLE
    }

    /**
     * The stats of [lr], computed once per library. A different library than last time is a
     * reload or a reselect, and any replay in flight belonged to the old one.
     */
    private fun bind(lr: LearnedRotation): LibraryStats {
        val current = stats
        if (lr === boundLibrary && current != null) {
            return current
        }
        val fresh = LibraryStats.of(lr)
        boundLibrary = lr
        stats = fresh
        resetState()
        return fresh
    }

    /**
     * Rounds a step onto the mouse grid and carries the remainder into the next tick. Without the
     * carry a phrase whose per-tick deltas sit below half a count - the settle part of most of
     * them - would be rounded to nothing and the profile would never reach its end.
     */
    private fun quantize(
        step: Double,
        gcd: Double,
        yaw: Boolean,
        minDelta: Double = Double.NEGATIVE_INFINITY,
        maxDelta: Double = Double.POSITIVE_INFINITY
    ): Float {
        val total = step + if (yaw) carryYaw else carryPitch
        val minCounts = if (minDelta.isInfinite()) Int.MIN_VALUE else ceil(minDelta / gcd).toInt()
        val maxCounts = if (maxDelta.isInfinite()) Int.MAX_VALUE else floor(maxDelta / gcd).toInt()
        val rawCounts = (total / gcd).roundToInt()
        val counts = rawCounts.coerceIn(min(minCounts, maxCounts), max(minCounts, maxCounts))
        val quantized = counts * gcd
        // A bound that bit ate the excess: carrying it would replay it as a jump once the bound
        // relaxes, so only an unclamped step keeps its sub-count remainder.
        val carry = if (counts == rawCounts) total - quantized else 0.0
        if (yaw) {
            carryYaw = carry
        } else {
            carryPitch = carry
        }
        return quantized.toFloat()
    }

    // --- Segment choice ----------------------------------------------------------------------

    /**
     * What to play next for the residual in front of us. The amplitude is the larger residual, and
     * the classes mirror the builder's: at or above the flick threshold a SWING, below it down to
     * the on-target tolerance a SETTLE, on target a pause or a raw micro.
     */
    private fun nextSegment(
        lr: LearnedRotation,
        residualYaw: Double,
        residualPitch: Double,
        distance: Double,
        gcd: Double
    ): Replay? {
        val amplitude = max(abs(residualYaw), abs(residualPitch))
        val tolerance = max(gcd, ON_TARGET_TOLERANCE)
        return when {
            amplitude >= LearnedRotationBuilder.FLICK_MIN_YAW -> swing(lr, amplitude, distance)
            amplitude > tolerance -> settle(lr, amplitude, distance)
            else -> onTarget(lr)
        }
    }

    /**
     * A swing: a phrase when the file has them and [usePhrases] is on, else a flick, else - for a
     * pre-v35 file with no flicks either - a micro stretched over the residual. The candidate is
     * picked by net amplitude and by the library's duration hint for that amplitude bucket.
     */
    private fun swing(lr: LearnedRotation, amplitude: Double, distance: Double): Replay? {
        val phrases = if (usePhrases) lr.phrases.orEmpty() else emptyList()
        // Every phrase nets at least PHRASE_MIN_YAW (3 deg) and a swing starts at FLICK_MIN_YAW
        // (8 deg), so a phrase always qualifies here by amplitude.
        val (candidates, kind) = when {
            phrases.isNotEmpty() -> phrases to Kind.PHRASE
            !lr.flicks.isNullOrEmpty() -> lr.flicks!! to Kind.FLICK
            !lr.micros.isNullOrEmpty() -> lr.micros!! to Kind.MICRO
            else -> return null
        }
        val duration = durationHint(lr, amplitude)
        val curve = pick(candidates) { c ->
            val net = max(abs(c.sumYaw()), MIN_AMPLITUDE)
            abs(ln(net / amplitude)) + (duration?.let { abs(c.length() - it) / it * DURATION_WEIGHT } ?: 0.0)
        } ?: return null
        return convergent(curve, kind, amplitude, distance)
    }

    /** Settling below the flick threshold: a micro (or, failing that, a flick) over the residual. */
    private fun settle(lr: LearnedRotation, amplitude: Double, distance: Double): Replay? {
        val (candidates, kind) = when {
            !lr.micros.isNullOrEmpty() -> lr.micros!! to Kind.MICRO
            !lr.flicks.isNullOrEmpty() -> lr.flicks!! to Kind.FLICK
            else -> return null
        }
        val curve = pick(candidates) { c ->
            abs(ln(max(c.path(), MIN_AMPLITUDE) / amplitude))
        } ?: return null
        return convergent(curve, kind, amplitude, distance)
    }

    /**
     * On target: with probability `pauseRate` the look stands still for one to three ticks; the
     * rest of the time a recorded micro plays RAW - its own deltas, scaled down to the library's
     * mean micro amplitude and capped at [RAW_MAX_NET] of net drift so the crosshair never
     * wanders off the box on its own. GUESS: the sources say micros are the settle / jitter, not
     * how much of one is played on target.
     */
    private fun onTarget(lr: LearnedRotation): Replay? {
        val random = ThreadLocalRandom.current()
        if (pauses && random.nextDouble() < lr.pauseRate) {
            pauseTicks = random.nextInt(PAUSE_MAX_TICKS)
            return null
        }
        val micros = lr.micros.orEmpty()
        if (micros.isEmpty()) {
            return null
        }
        val curve = micros[random.nextInt(micros.size)]
        val dy = curve.dy ?: return null
        val dp = curve.dp ?: return null
        if (dy.isEmpty()) {
            return null
        }
        val net = max(max(abs(curve.sumYaw()), abs(curve.sumPitch())), MIN_AMPLITUDE)
        val path = max(curve.path(), MIN_AMPLITUDE)
        val scale = min(1.0, RAW_MAX_NET / net) * min(1.0, lr.microAmp * dy.size / path)
        lastCurve = curve
        return Replay(
            curve = curve,
            kind = Kind.MICRO,
            yawProfile = pathProfile(dy),
            pitchProfile = pathProfile(dp),
            advance = speed.toDouble(),
            raw = true,
            rawScale = scale
        )
    }

    /** A profile replay of [curve] over the residual, at the adaptive, clamped rate. */
    private fun convergent(curve: Curve, kind: Kind, amplitude: Double, distance: Double): Replay? {
        val dy = curve.dy ?: return null
        val dp = curve.dp ?: return null
        if (dy.isEmpty()) {
            return null
        }
        lastCurve = curve
        return Replay(
            curve = curve,
            kind = kind,
            yawProfile = signedProfile(dy, YAW_STRUCTURE_MIN) ?: pathProfile(dy),
            pitchProfile = signedProfile(dp, PITCH_STRUCTURE_MIN),
            advance = advanceFor(amplitude, distance, curve.length()),
            raw = false,
            rawScale = 1.0
        )
    }

    /**
     * Ranks [candidates] by [score] (lower is better), takes the [PICK_POOL] best, avoids the curve
     * played last when there is a choice, and draws one at random - so the same geometry does not
     * replay the same curve every time.
     */
    private fun pick(candidates: List<Curve>, score: (Curve) -> Double): Curve? {
        if (candidates.isEmpty()) {
            return null
        }
        val pool = candidates.asSequence()
            .map { it to score(it) }
            .sortedBy { it.second }
            .map { it.first }
            .take(PICK_POOL)
            .toMutableList()
        if (pool.size > 1) {
            pool.remove(lastCurve)
        }
        return pool[ThreadLocalRandom.current().nextInt(pool.size)]
    }

    /**
     * Recorded ticks per real tick for a swing of [amplitude]: [speed], scaled by
     * [AiHumanizer.adaptiveSpeed] when [adaptive], then pulled back so the implied turn speed sits
     * within [AiHumanizer.clampSpeed]'s band around the library's median swing speed - a 5-degree
     * phrase stretched over 120 degrees would otherwise be a one-tick snap.
     */
    private fun advanceFor(amplitude: Double, distance: Double, length: Int): Double {
        var advance = if (adaptive) {
            AiHumanizer.adaptiveSpeed(speed, distance.toFloat(), amplitude.toFloat()).toDouble()
        } else {
            speed.toDouble()
        }
        val median = stats?.medianSpeed ?: LibraryStats.DEFAULT_SPEED
        val implied = amplitude * advance / max(length, 1)
        if (implied > 1e-6) {
            val clamped = AiHumanizer.clampSpeed(implied.toFloat(), median.toFloat()).toDouble()
            advance *= clamped / implied
        }
        return advance.coerceIn(MIN_ADVANCE, MAX_ADVANCE)
    }

    /** `durProfile` for the amplitude's bucket, or the nearest bucket that has data, or null. */
    private fun durationHint(lr: LearnedRotation, amplitude: Double): Double? {
        val profile = lr.durProfile ?: return null
        val bucket = LearnedRotation.bucket(amplitude)
        if (profile.getOrElse(bucket) { 0.0 } > 0.0) {
            return profile[bucket]
        }
        for (offset in 1 until profile.size) {
            val below = bucket - offset
            val above = bucket + offset
            if (below >= 0 && profile[below] > 0.0) {
                return profile[below]
            }
            if (above < profile.size && profile[above] > 0.0) {
                return profile[above]
            }
        }
        return null
    }

    // --- Profiles ----------------------------------------------------------------------------

    /**
     * The signed cumulative profile of [deltas], `n + 1` points from 0 to exactly 1, or null when
     * the curve has no net structure on this axis (net below [minimum] or below half its path - a
     * wobble, not a turn). A signed profile can overshoot 1 and come back, which is the recorded
     * overshoot-and-correct of the hand; [fraction] caps what that can do to a live residual.
     */
    private fun signedProfile(deltas: DoubleArray, minimum: Double): DoubleArray? {
        var sum = 0.0
        var path = 0.0
        for (v in deltas) {
            sum += v
            path += abs(v)
        }
        if (abs(sum) < minimum || abs(sum) < STRUCTURE_SHARE * path) {
            return null
        }
        val profile = DoubleArray(deltas.size + 1)
        var acc = 0.0
        for (i in deltas.indices) {
            acc += deltas[i]
            profile[i + 1] = acc / sum
        }
        profile[deltas.size] = 1.0
        return profile
    }

    /** The unsigned (path) cumulative profile: monotone from 0 to 1, a linear ramp for a flat curve. */
    private fun pathProfile(deltas: DoubleArray): DoubleArray {
        var path = 0.0
        for (v in deltas) {
            path += abs(v)
        }
        val profile = DoubleArray(deltas.size + 1)
        if (deltas.isEmpty()) {
            // Unreachable through sanitized() / the builder, but 0 / 0 would poison the carry
            // with NaN for the rest of the engagement.
            return profile
        }
        if (path < 1e-9) {
            for (i in profile.indices) {
                profile[i] = i / deltas.size.toDouble()
            }
            return profile
        }
        var acc = 0.0
        for (i in deltas.indices) {
            acc += abs(deltas[i])
            profile[i + 1] = acc / path
        }
        profile[deltas.size] = 1.0
        return profile
    }

    /** One segment in flight: a curve, its profiles and a fractional cursor over its ticks. */
    private inner class Replay(
        val curve: Curve,
        val kind: Kind,
        private val yawProfile: DoubleArray,
        private val pitchProfile: DoubleArray?,
        private val advance: Double,
        val raw: Boolean,
        private val rawScale: Double
    ) {
        val length = curve.length()
        var cursor = 0.0
            private set

        val done: Boolean
            get() = cursor >= length - CURSOR_EPSILON

        /**
         * The step for this tick. Raw: the recorded deltas between the old and the new cursor,
         * scaled. Profile: the share of the curve's remaining profile covered by this tick, applied
         * to the CURRENT residual - which is what stretches the recording onto a moving target. A
         * curve with no pitch structure lets the pitch follow the yaw profile, capped at
         * `pitchFollow` of the yaw step while the yaw is the larger residual (the recording's
         * habit of driving the horizontal first and correcting the vertical after).
         */
        fun step(residualYaw: Double, residualPitch: Double, pitchFollow: Double): DoubleArray {
            val from = cursor
            cursor = min(length.toDouble(), cursor + advance)
            if (raw) {
                return doubleArrayOf(
                    integrate(curve.dy!!, from, cursor) * rawScale,
                    integrate(curve.dp!!, from, cursor) * rawScale
                )
            }
            val yawFraction = fraction(yawProfile, from, cursor)
            val stepYaw = residualYaw * yawFraction
            val stepPitch = if (pitchProfile != null) {
                residualPitch * fraction(pitchProfile, from, cursor)
            } else {
                var following = residualPitch * yawFraction
                if (abs(residualPitch) <= abs(residualYaw)) {
                    // abs() on the product: sanitized() keeps pitchFollow positive, and coerceIn
                    // throws on an inverted range, so the cap is never allowed to go negative here.
                    val cap = abs(stepYaw * pitchFollow)
                    following = following.coerceIn(-cap, cap)
                }
                following
            }
            return doubleArrayOf(stepYaw, stepPitch)
        }

        /**
         * Share of the remaining profile between two cursor positions. When the profile has already
         * arrived (remaining share ~0) the replay is over - the hand thinks it is done and the
         * next tick settles whatever the target moved by. The ratio is capped so a profile that
         * touches 1 mid-way cannot blow a small residual up into a snap.
         */
        private fun fraction(profile: DoubleArray, from: Double, to: Double): Double {
            val before = at(profile, from)
            val after = at(profile, to)
            val remaining = 1.0 - before
            if (abs(remaining) < ARRIVED_EPSILON) {
                cursor = length.toDouble()
                return 0.0
            }
            return ((after - before) / remaining).coerceIn(-FRACTION_CAP, FRACTION_CAP)
        }
    }

    // --- Hand-back ---------------------------------------------------------------------------

    /**
     * No entity: hand-back, or a block. Linear at [returnSpeed], truncated onto the grid so it can
     * never pass the target, with one count guaranteed whenever one is available - so the residual
     * strictly decreases and `RotationManager` can land exactly and release the override.
     */
    private fun converge(currentRotation: Rotation, targetRotation: Rotation): Rotation {
        val gcd = RotationUtil.gcd
        val diff = currentRotation.rotationDeltaTo(targetRotation)
        val yaw = currentRotation.yaw + returnStep(diff.deltaYaw, gcd)
        val pitch = currentRotation.pitch + returnStep(diff.deltaPitch, gcd)
        return Rotation(yaw, pitch.coerceIn(-PITCH_CLAMP, PITCH_CLAMP), isNormalized = true)
    }

    private fun returnStep(residual: Float, gcd: Double): Float {
        val step = residual.coerceIn(-returnSpeed, returnSpeed)
        var counts = (step / gcd).toInt()
        if (counts == 0 && abs(residual) >= gcd) {
            counts = sign(residual).toInt()
        }
        return (counts * gcd).toFloat()
    }

    /**
     * The library's duration hint for the angle's bucket at the configured [speed], else the angle
     * over the library's median swing speed. The adaptive scaling is left out: it needs the
     * distance, which a bare angle does not carry, so the estimate errs slow rather than fast.
     */
    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        val lr = library ?: return fallback.calculateTicks(currentRotation, targetRotation)
        val angle = currentRotation.angleTo(targetRotation).toDouble()
        if (angle <= ON_TARGET_TOLERANCE) {
            return 0
        }
        val median = bind(lr).medianSpeed
        val duration = durationHint(lr, angle) ?: (angle / median)
        val advance = speed.toDouble().coerceAtLeast(MIN_ADVANCE)
        return ceil(duration / advance).toInt().coerceIn(1, MAX_TICKS)
    }

    private fun notifyUnavailable() {
        if (!notificationChronometer.hasElapsed(NOTIFICATION_INTERVAL)) {
            return
        }
        notificationChronometer.reset()
        chat(
            markAsError(
                "Learned: no rotation selected, aiming with ${fallback.name}. Record with .ai start, " +
                    "build with .ai create <name>."
            )
        )
    }

    /** What the engine needs from a library once, not per tick. */
    private class LibraryStats(val medianSpeed: Double) {
        companion object {
            /** Degrees per tick assumed for a library whose swings carry no usable speed. */
            const val DEFAULT_SPEED = 4.0

            fun of(lr: LearnedRotation): LibraryStats {
                val source = lr.flicks.orEmpty().ifEmpty { lr.phrases.orEmpty() }
                val speeds = source.mapNotNull { c ->
                    val n = c.length()
                    if (n == 0) null else abs(c.sumYaw()) / n
                }.filter { it > 1e-6 }.sorted()
                if (speeds.isEmpty()) {
                    return LibraryStats(DEFAULT_SPEED)
                }
                val median = if (speeds.size % 2 == 1) {
                    speeds[speeds.size / 2]
                } else {
                    0.5 * (speeds[speeds.size / 2 - 1] + speeds[speeds.size / 2])
                }
                return LibraryStats(median)
            }
        }
    }

    companion object {
        private val notificationChronometer = Chronometer()
        private const val NOTIFICATION_INTERVAL = 5000L

        /** "Delta clamps |yaw| <= 60 / |pitch| <= 24, as in the tutorial." */
        private const val MAX_YAW_STEP = 60.0
        private const val MAX_PITCH_STEP = 24.0
        private const val PITCH_CLAMP = 90f

        /** Residual below which the crosshair counts as on target and only pauses / raw micros play. */
        private const val ON_TARGET_TOLERANCE = 0.35

        /** Net drift a raw on-target micro may accumulate, degrees. */
        private const val RAW_MAX_NET = 1.5

        /** Residual at which a raw micro is abandoned for a correction; above the drift it can cause. */
        private const val RAW_ABORT_RESIDUAL = 2.0

        /** Pause length on target: `nextInt(PAUSE_MAX_TICKS)` further still ticks after the first. */
        private const val PAUSE_MAX_TICKS = 3

        /** A curve's net turn / tilt must be at least this and half its path to count as structure. */
        private const val YAW_STRUCTURE_MIN = 1.0
        private const val PITCH_STRUCTURE_MIN = 0.75
        private const val STRUCTURE_SHARE = 0.5

        private const val MIN_AMPLITUDE = 0.05
        private const val DURATION_WEIGHT = 0.5
        private const val PICK_POOL = 4

        private const val MIN_ADVANCE = 0.25
        private const val MAX_ADVANCE = 4.0

        private const val CURSOR_EPSILON = 1e-9
        private const val ARRIVED_EPSILON = 1e-3
        private const val FRACTION_CAP = 1.5

        private const val MAX_TICKS = 40

        /** Linear interpolation of a profile at a fractional tick index. */
        private fun at(profile: DoubleArray, x: Double): Double {
            val last = profile.size - 1
            if (x >= last) {
                return profile[last]
            }
            if (x <= 0.0) {
                return profile[0]
            }
            val i = floor(x).toInt()
            val f = x - i
            return profile[i] + (profile[i + 1] - profile[i]) * f
        }

        /** Sum of per-tick [deltas] over the fractional index range [from, to). */
        private fun integrate(deltas: DoubleArray, from: Double, to: Double): Double {
            var sum = 0.0
            val first = floor(from).toInt().coerceAtLeast(0)
            val last = ceil(to).toInt().coerceAtMost(deltas.size)
            for (i in first until last) {
                val overlap = min(to, i + 1.0) - max(from, i.toDouble())
                if (overlap > 0.0) {
                    sum += deltas[i] * overlap
                }
            }
            return sum
        }
    }
}
