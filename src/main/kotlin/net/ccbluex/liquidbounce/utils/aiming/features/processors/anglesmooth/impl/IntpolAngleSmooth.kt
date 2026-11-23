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
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.math.toDegrees
import net.minecraft.world.entity.Entity
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Intpol" — easing-interpolation aim built to the pattern/easing guide: the acceleration profile
 * of every swing is drawn from a large library of easing curves and never repeats, and the speed
 * is coupled to ordinary player variables so no two turns look alike.
 *
 * How the guide's requirements map onto the implementation:
 *
 *  - **Easing library, constantly switched.** Thirty curves from easings.net ([Curve]): Sine /
 *    Quad / Cubic / Quart / Quint / Expo / Circ / Back / Elastic / Bounce, each In / Out / InOut.
 *    Every *swing* (a few ticks of movement toward the target) rolls a fresh curve — never the
 *    same one twice in a row — and a fresh duration, so the velocity profile is a chain of
 *    differently-shaped segments rather than one repeating template. The user picks the allowed
 *    set via [curves]; the springy In-variants of Elastic/Bounce (which tremble *before* moving)
 *    are off by default but available.
 *  - **The curve is applied to the position along the path, not the raw delta.** Per tick the step
 *    is `span × (E(p₁) − E(p₀))` with the span re-estimated from the live remaining error, which
 *    reproduces the easing exactly on a static target, tracks a moving one, and lets the
 *    over-unity curves (Back / Elastic / Bounce Out) genuinely pass the target and come back —
 *    the guide's "иногда прицел перекручивается" — instead of asymptoting.
 *  - **Player-variable coupling**: the swing speed is scaled by the player's own horizontal speed
 *    (`1 + v·boost`, capped like the guide's 1.5), by fall distance (a falling player turns a
 *    touch worse), and by the attack-swing recharge ([swingProgressBoost] — more attentive as the
 *    weapon comes back).
 *  - **Distance shaping**: slow when point-blank ([closeSpeed]), fastest at mid range
 *    ([midSpeed]), slow again when far ([farSpeed]), with smooth blends between the bands.
 *  - **Error shaping**: below [errorStop] the aim stops entirely (a genuine hold), while a large
 *    error accelerates the turn ([errorAccel]).
 *  - **Pitch is slower than yaw** ([pitchFactor]).
 *  - **Rare random twitches + tiny sine micro-shake** ([twitchChance] / [twitchAmount],
 *    [tremorYaw] / [tremorPitch]) — the tremor is a sine whose frequency is re-rolled every swing
 *    and whose amplitude is re-drawn every tick, per the guide's "микротряска через синус".
 *  - **Keep the old angle when the new one is effectively the same** ([keepSameAngle]) — sub-count
 *    steps are dropped and the tick emits the previous rotation exactly.
 *  - **Floating aim point inside the hitbox** ([driftAmount] / [driftReroll] / [driftSpeed]): a
 *    smoothly wandering offset, scaled to the target's angular size, is layered onto the aim
 *    point so no two hits land on the same spot. Because the float is built in,
 *    [prefersFannedAimPoint] is false — the fanned anchor would be a second randomizer on top.
 *  - **The guide's mandatory delta clamp** ([maxYawSpeed] / [maxPitchSpeed], defaults 60/24 —
 *    the guide's own numbers) so a fresh span estimate can never turn into a snap.
 *  - **First-guide fixes, mirrored from the since-removed MultipointAngleSmooth** (2026-08-26,
 *    that mode deleted 2026-09-13): a visuomotor
 *    delay line + interception lead ([reaction] / [lead] / [leadWindow]) — undelayed, this mode
 *    answered target motion in the very tick it happened, the "нулевая реакция" tell; the lead
 *    pays the delay back in hitrate (the since-removed ShardAngleSmooth measured the pair at 72.3 % on target vs
 *    64.4 % for the delay alone) — and a reaction freeze on wide aim-point jumps
 *    ([turnThreshold] / [turnReactTicks], refractory-guarded, never on first acquisition, so
 *    KillAura's schedule is not surprised on the opening hit). Defaults INFERRED from that
 *    measured sweep, not re-simulated for this class.
 *
 * Honest scope note: this is the guide-faithful *heuristic* mode — varied easing templates plus
 * noise — aimed at anticheats that check smoothness, acceleration patterns and repetition. It is
 * NOT built against distribution-level ML checks: [Rotation.normalize] quantizes its output,
 * but an easing set is
 * still a finite family of profiles, and no statistic here was fitted to a recorded player. For
 * Grim/Shard-class targets use Clone; those trade-offs are documented in its header.
 *
 * On the reset path (`rotationTarget.entity == null`) every guide feature is OFF and the rotation
 * converges monotonically with a minimum step, landing exactly on the target — the same
 * hand-back invariant as the sibling smoothers, without which `RotationManager` never releases
 * the silent rotation.
 */
@Suppress("TooManyFunctions")
class IntpolAngleSmooth(
    parent: ModeValueGroup<*>,
) : AngleSmooth("Intpol", parent, listOf("Easing", "Interpol")) {

    /** The floating point below replaces the fanned anchor — two randomizers would fight. */
    override val prefersFannedAimPoint: Boolean get() = false

    // --- Easing library --------------------------------------------------------------------------

    /**
     * Which curves the swing generator may draw from. Springy In-variants (ElasticIn/InOut,
     * BounceIn/InOut) tremble before the movement starts, which costs hits — off by default.
     */
    private val curves by multiEnumChoice("Curves", DEFAULT_CURVES, canBeNone = false)

    // --- Swing structure -------------------------------------------------------------------------

    /** Base length of one eased swing; a fresh curve and duration are rolled for every swing. */
    private val swingTicks by intRange("SwingTicks", 3..6, 1..15, "ticks")

    /** Extra swing ticks per 90° of error, so wide turns take proportionally longer. */
    private val distanceScaling by float("DistanceScaling", 1.5f, 0f..6f)

    // --- Distance speed shaping (close = slower, mid = faster, far = slower) ---------------------

    private val closeRange by float("CloseRange", 1.5f, 0.5f..4f, "blocks")
    private val closeSpeed by float("CloseSpeed", 0.75f, 0.2f..1.5f, "×")
    private val midSpeed by float("MidSpeed", 1.15f, 0.5f..2f, "×")
    private val farRange by float("FarRange", 4.5f, 2f..10f, "blocks")
    private val farSpeed by float("FarSpeed", 0.7f, 0.2f..1.5f, "×")

    // --- Player-variable coupling ----------------------------------------------------------------

    /** Aim speedup per unit of the player's own horizontal speed (guide: `1 + speed·0.5`, cap 1.5). */
    private val playerSpeedBoost by float("PlayerSpeedBoost", 0.5f, 0f..2f)

    /** How much an ongoing fall slows the turn (scaled by fall distance). */
    private val fallEffect by float("FallEffect", 0.1f, 0f..1f)

    /** Extra attention as the attack swing recharges (0 = ignore the weapon state). */
    private val swingProgressBoost by float("SwingProgressBoost", 0.3f, 0f..1f)

    // --- Error shaping ---------------------------------------------------------------------------

    /** Below this angle to the aim point the hand stops entirely ("при малой разнице — стоп"). */
    private val errorStop by float("ErrorStop", 0.8f, 0f..5f, "°")

    /** Acceleration with error size ("при большой — ускорение"). */
    private val errorAccel by float("ErrorAccel", 0.6f, 0f..2f)

    /** Vertical speed relative to horizontal. */
    private val pitchFactor by float("PitchFactor", 0.65f, 0.2f..1.2f, "×")

    // --- The guide's mandatory delta clamp -------------------------------------------------------

    private val maxYawSpeed by float("MaxYawSpeed", 60f, 10f..180f, "°/tick")
    private val maxPitchSpeed by float("MaxPitchSpeed", 24f, 5f..90f, "°/tick")

    /** Steps smaller than this on both axes keep the old rotation exactly. */
    private val keepSameAngle by float("KeepSameAngle", 0.05f, 0f..0.5f, "°")

    // --- Jitter: rare twitches + tiny sine tremor ------------------------------------------------

    /** Chance per second of a brief 1-2 tick random jerk. */
    private val twitchChance by float("TwitchChance", 0.08f, 0f..1f)

    /** Size of that jerk (sign is random, pitch gets half). */
    private val twitchAmount by floatRange("TwitchAmount", 0.2f..0.7f, 0f..3f, "°")

    /** Sine micro-shake amplitude, yaw / pitch. Very small on purpose. */
    private val tremorYaw by floatRange("TremorYaw", 0.01f..0.05f, 0f..0.5f, "°")
    private val tremorPitch by floatRange("TremorPitch", 0.01f..0.04f, 0f..0.5f, "°")

    // --- Floating aim point inside the hitbox ----------------------------------------------------

    /** Reach of the float as a fraction of the target's angular half-size. 0 = off. */
    private val driftAmount by float("DriftAmount", 0.6f, 0f..1f, "×")

    /** How often the float picks a new spot to wander toward. */
    private val driftReroll by intRange("DriftReroll", 8..20, 2..60, "ticks")

    /** How fast it wanders there (per-tick interpolation fraction). */
    private val driftSpeed by float("DriftSpeed", 0.25f, 0.05f..1f)

    // --- Visuomotor delay & interception (mirrored from the removed MultipointAngleSmooth) -------

    /** Ticks of delay on the observed target position. */
    private val reaction by int("Reaction", 1, 0..MAX_REACTION, "ticks")

    /** Ticks of the target's angular velocity extrapolated past the delayed observation. */
    private val lead by float("Lead", 1.0f, 0f..4f, "ticks")

    /** Ticks the target's angular velocity is averaged over (short = fresh, long = stale). */
    private val leadWindow by int("LeadWindow", 2, 1..MAX_REACTION, "ticks")

    // --- Turn reaction ---------------------------------------------------------------------------

    /** A jump of the requested aim point at least this wide gets a reaction freeze first. */
    private val turnThreshold by float("TurnThreshold", 45f, 15f..180f, "°")

    /** Length of that freeze. Rare (switches, wide jumps) — the hitrate cost is a tick or two. */
    private val turnReactTicks by intRange("TurnReactTicks", 1..2, 0..5, "ticks")

    // --- Swing state -----------------------------------------------------------------------------

    private var swingElapsed = 0
    private var swingLength = 0
    private var curve = Curve.SINE_IN_OUT
    private var lastCurve: Curve? = null

    /** Span estimate frozen at the last tick where the easing denominator was healthy. */
    private var frozenSpanYaw = 0f
    private var frozenSpanPitch = 0f

    /** The aim point the swing was planned against, to notice it jumping away mid-swing. */
    private var plannedAimYaw = 0f
    private var plannedAimPitch = 0f
    private var hasPlan = false

    // --- Jitter state ----------------------------------------------------------------------------

    private var twitchTicksLeft = 0
    private var twitchYaw = 0f
    private var twitchPitch = 0f

    private var tremorPhaseYaw = 0f
    private var tremorPhasePitch = 0f
    private var tremorFreqYaw = 0.3f
    private var tremorFreqPitch = 0.23f

    // --- Floating point state --------------------------------------------------------------------

    private var driftU = 0f
    private var driftV = 0f
    private var driftGoalU = 0f
    private var driftGoalV = 0f
    private var driftTicksLeft = 0

    // Delay line for the observed target (visuomotor delay + velocity estimate for the lead).
    private val delayedYaw = FloatArray(MAX_REACTION + 1)
    private val delayedPitch = FloatArray(MAX_REACTION + 1)
    private var delayHead = 0
    private var delayFill = 0

    // Turn-reaction state: freeze ticks left, refractory, and the previous requested aim point.
    private var reactionLeft = 0
    private var reactionRefractory = 0
    private var engaged = false
    private var prevTargetYaw = 0f
    private var prevTargetPitch = 0f

    private var lastTick = -1

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val entity = rotationTarget.entity
        val rng = ThreadLocalRandom.current()

        forgetStaleState()
        pushTarget(targetRotation)
        updateTurnReaction(targetRotation, entity == null)

        // No entity: the silent rotation is being handed back (or a block is being aimed at).
        // Every guide feature is off here and the path provably terminates.
        if (entity == null) {
            resetSwing()
            return converge(currentRotation, targetRotation)
        }

        // The hand aims at the target as it was [reaction] ticks ago, thrown forward along its
        // recent angular velocity; the floating hitbox point is our own choice of where to look,
        // so it rides on top of the observation.
        val aim = floatingAim(entity, leadTarget(delayedTarget(targetRotation)), rng)

        val errYaw = RotationUtil.angleDifference(aim.yaw, currentRotation.yaw)
        val errPitch = RotationUtil.angleDifference(aim.pitch, currentRotation.pitch)
        val error = hypot(errYaw, errPitch)

        updateTwitch(rng)
        val (tremYaw, tremPitch) = tremor(rng)

        // Reaction freeze after a wide aim-point jump: a person notices first, then flicks.
        if (reactionLeft > 0) {
            reactionLeft--
            resetSwing()
            return emit(currentRotation, tremYaw, tremPitch)
        }

        // Small difference -> full stop. A live twitch overrides the hold so the jerk still shows.
        if (error <= errorStop && twitchTicksLeft <= 0) {
            resetSwing()
            return emit(currentRotation, tremYaw, tremPitch)
        }

        ensureSwing(aim, error, errYaw, errPitch, rng)

        val e0 = curve.value(swingElapsed.toFloat() / swingLength)
        val e1 = curve.value((swingElapsed + 1).toFloat() / swingLength)
        swingElapsed++
        val de = e1 - e0

        // Span from the live error: exact easing on a static target, tracking on a moving one.
        // Near the overshoot crossing (E ~ 1) the estimate blows up, so the last healthy span is
        // used instead - that is also what carries the aim PAST the target on over-unity curves.
        val denom = 1f - e0
        if (abs(denom) >= SPAN_EPS) {
            frozenSpanYaw = errYaw / denom
            frozenSpanPitch = errPitch / denom
        }

        val speed = speedFactor(entity, error)
        var stepYaw = frozenSpanYaw * de * speed
        var stepPitch = frozenSpanPitch * de * speed * pitchFactor

        stepYaw = boundStep(stepYaw, errYaw, maxYawSpeed)
        stepPitch = boundStep(stepPitch, errPitch, maxPitchSpeed)

        if (swingElapsed >= swingLength) {
            resetSwing()
        }

        return emit(currentRotation, stepYaw + twitchYaw + tremYaw, stepPitch + twitchPitch + tremPitch)
    }

    /** Drops sub-[keepSameAngle] movement entirely: "если угол такой же как старый — оставляй старый". */
    private fun emit(currentRotation: Rotation, stepYaw: Float, stepPitch: Float): Rotation {
        if (abs(stepYaw) < keepSameAngle && abs(stepPitch) < keepSameAngle) {
            return currentRotation
        }

        return Rotation(
            currentRotation.yaw + stepYaw,
            (currentRotation.pitch + stepPitch).coerceIn(-90f, 90f)
        )
    }

    // ------------------------------------------------------------------ swing machinery

    /** Starts a new swing when none is running or the aim point jumped away from the planned one. */
    private fun ensureSwing(aim: Rotation, error: Float, errYaw: Float, errPitch: Float, rng: ThreadLocalRandom) {
        val planMoved = hasPlan && hypot(
            RotationUtil.angleDifference(aim.yaw, plannedAimYaw),
            RotationUtil.angleDifference(aim.pitch, plannedAimPitch)
        ) > REGEN_ANGLE
        if (swingLength > 0 && swingElapsed < swingLength && !planMoved) {
            return
        }

        // Fresh curve every swing, never the same one twice in a row.
        val pool = curves.filter { it != lastCurve }.ifEmpty { curves.toList() }
        curve = pool[rng.nextInt(pool.size)]
        lastCurve = curve

        val extra = (distanceScaling * (error / FITTS_REF)).roundToInt()
        swingLength = (swingTicks.random() + extra).coerceIn(1, MAX_SWING)
        swingElapsed = 0
        frozenSpanYaw = errYaw
        frozenSpanPitch = errPitch
        plannedAimYaw = aim.yaw
        plannedAimPitch = aim.pitch
        hasPlan = true

        // Even the micro-shake never repeats: new sine frequencies per swing.
        tremorFreqYaw = TREMOR_FREQ_MIN + rng.nextFloat() * TREMOR_FREQ_SPREAD
        tremorFreqPitch = TREMOR_FREQ_MIN + rng.nextFloat() * TREMOR_FREQ_SPREAD
    }

    private fun resetSwing() {
        swingLength = 0
        swingElapsed = 0
        hasPlan = false
    }

    /**
     * The guide's mandatory clamp (60/24 by default) plus a proportional guard: the step may pass
     * the target — Back/Elastic/Bounce Out are the wanted "перекручивание" — but never by more
     * than the error itself again, so a span estimate near the crossing cannot explode.
     */
    private fun boundStep(step: Float, err: Float, cap: Float): Float {
        val guard = abs(err) * MAX_PASS_FACTOR + PASS_HEADROOM
        return step.coerceIn(-guard, guard).coerceIn(-cap, cap)
    }

    // ------------------------------------------------------------------ speed shaping

    /** All the guide's speed dependencies multiplied together. */
    private fun speedFactor(entity: Entity, error: Float): Float {
        val distance = player.eyePosition.distanceTo(entity.box.center).toFloat()
        var factor = distanceFactor(distance)

        val vel = player.deltaMovement
        val horizontalSpeed = hypot(vel.x, vel.z).toFloat()
        factor *= (1f + horizontalSpeed * playerSpeedBoost).coerceAtMost(SPEED_BOOST_CAP)

        val fall = player.fallDistance.toFloat()
        factor *= 1f - fallEffect * (fall / FALL_REF).coerceIn(0f, 1f) * FALL_MAX_SLOW

        factor *= 1f + swingProgressBoost * (player.getAttackStrengthScale(0f) - COOLDOWN_MIDPOINT)

        factor *= 1f + errorAccel * (error / ERROR_REF).coerceAtMost(1f)

        return factor.coerceIn(MIN_SPEED_FACTOR, MAX_SPEED_FACTOR)
    }

    /** Close = slower, mid = faster, far = slower again, with smooth blends between the bands. */
    private fun distanceFactor(distance: Float): Float = when {
        distance <= closeRange -> closeSpeed
        distance < closeRange + CLOSE_BLEND ->
            lerp(closeSpeed, midSpeed, (distance - closeRange) / CLOSE_BLEND)
        distance <= farRange -> midSpeed
        distance < farRange + FAR_BLEND ->
            lerp(midSpeed, farSpeed, (distance - farRange) / FAR_BLEND)
        else -> farSpeed
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t.coerceIn(0f, 1f)

    // ------------------------------------------------------------------ jitter

    private fun updateTwitch(rng: ThreadLocalRandom) {
        if (twitchTicksLeft > 0) {
            twitchTicksLeft--
            twitchYaw *= TWITCH_DECAY
            twitchPitch *= TWITCH_DECAY
            return
        }

        twitchYaw = 0f
        twitchPitch = 0f
        if (twitchChance > 0f && rng.nextFloat() < twitchChance / TICKS_PER_SECOND) {
            val amount = twitchAmount.random()
            twitchYaw = if (rng.nextBoolean()) amount else -amount
            twitchPitch = (if (rng.nextBoolean()) amount else -amount) * TWITCH_PITCH_SHARE
            twitchTicksLeft = 1 + rng.nextInt(2)
        }
    }

    /** Sine micro-shake, amplitude re-drawn per tick, frequency re-rolled per swing. */
    private fun tremor(rng: ThreadLocalRandom): Pair<Float, Float> {
        tremorPhaseYaw += tremorFreqYaw
        tremorPhasePitch += tremorFreqPitch
        if (tremorPhaseYaw > TWO_PI) tremorPhaseYaw -= TWO_PI
        if (tremorPhasePitch > TWO_PI) tremorPhasePitch -= TWO_PI
        return sin(tremorPhaseYaw) * tremorYaw.random() to sin(tremorPhasePitch) * tremorPitch.random()
    }

    // ------------------------------------------------------------------ floating aim point

    /**
     * The wandering point inside the hitbox: an offset in hitbox-fraction space drifts smoothly
     * toward a goal that is re-rolled every [driftReroll] ticks, mapped onto the target's angular
     * size — so the crosshair floats around the aura's aim point and no two hits land on the same
     * spot. Applied as an offset to the PROVIDED point (which is the visible/raytraced one), not
     * derived from the box centre, so it cannot steer into cover any further than its own reach.
     */
    private fun floatingAim(entity: Entity, targetRotation: Rotation, rng: ThreadLocalRandom): Rotation {
        if (driftAmount <= 0f) {
            return targetRotation
        }

        if (driftTicksLeft <= 0) {
            driftGoalU = rng.nextFloat() * 2f - 1f
            driftGoalV = rng.nextFloat() * 2f - 1f
            driftTicksLeft = driftReroll.random()
        }
        driftTicksLeft--
        driftU += (driftGoalU - driftU) * driftSpeed
        driftV += (driftGoalV - driftV) * driftSpeed

        val box = entity.box
        val distance = player.eyePosition.distanceTo(box.center).toFloat().coerceAtLeast(MIN_DISTANCE)
        val angularHalfWidth = atan(max(box.xsize, box.zsize).toFloat() * 0.5f / distance).toDegrees()
        val angularHalfHeight = atan(box.ysize.toFloat() * 0.5f / distance).toDegrees()

        return Rotation(
            targetRotation.yaw + driftU * driftAmount * angularHalfWidth,
            targetRotation.pitch + driftV * driftAmount * angularHalfHeight
        )
    }

    // ------------------------------------------------------------------ reset path

    /**
     * Monotone hand-back: a fixed fraction of the residual with a minimum step, clamped so it can
     * never pass the target, landing exactly once within [CONVERGE_EPS]. |residual| strictly
     * decreases every tick, so the rotation override is always released.
     */
    private fun converge(currentRotation: Rotation, targetRotation: Rotation): Rotation {
        val deltaYaw = RotationUtil.angleDifference(targetRotation.yaw, currentRotation.yaw)
        val deltaPitch = RotationUtil.angleDifference(targetRotation.pitch, currentRotation.pitch)
        if (max(abs(deltaYaw), abs(deltaPitch)) <= CONVERGE_EPS) {
            return targetRotation
        }

        return Rotation(
            currentRotation.yaw + convergeStep(deltaYaw),
            (currentRotation.pitch + convergeStep(deltaPitch)).coerceIn(-90f, 90f)
        )
    }

    private fun convergeStep(delta: Float): Float {
        if (delta == 0f) {
            return 0f
        }

        val step = max(abs(delta) * CONVERGE_GAIN, CONVERGE_MIN)
        return sign(delta) * min(step, abs(delta))
    }

    /** A gap in the tick stream drops the swing in flight and the transient jitter state. */
    private fun forgetStaleState() {
        val tick = player.tickCount
        if (tick < lastTick || tick - lastTick > 1) {
            resetSwing()
            twitchTicksLeft = 0
            twitchYaw = 0f
            twitchPitch = 0f
            driftTicksLeft = 0
            delayFill = 0
            reactionLeft = 0
            engaged = false
        }
        lastTick = tick
    }

    // ------------------------------------------------------------------ delay line & turn reaction

    /** The requested aim as it arrives at the eye: pushed every tick, read back [reaction] ticks late. */
    private fun pushTarget(targetRotation: Rotation) {
        delayHead = (delayHead + 1) % delayedYaw.size
        delayedYaw[delayHead] = targetRotation.yaw
        delayedPitch[delayHead] = targetRotation.pitch
        if (delayFill < delayedYaw.size) {
            delayFill++
        }
    }

    /**
     * The target as it was [reaction] ticks ago. On a target switch this keeps aiming at the old
     * point for a moment — that is the reaction time a person spends on the switch, not a bug.
     */
    private fun delayedTarget(live: Rotation): Rotation {
        val back = min(reaction, delayFill - 1)
        if (back <= 0) {
            return live
        }

        val index = (delayHead - back + delayedYaw.size) % delayedYaw.size
        return Rotation(delayedYaw[index], delayedPitch[index])
    }

    /**
     * Interception: the observed point extrapolated [lead] ticks along the target's mean angular
     * velocity over the last [leadWindow] ticks — what pays the [reaction] delay back in hitrate.
     * Capped so a velocity spike cannot fling the aim.
     */
    private fun leadTarget(aim: Rotation): Rotation {
        if (lead <= 0f) {
            return aim
        }
        val span = min(leadWindow, delayFill - 1)
        if (span <= 0) {
            return aim
        }

        val oldest = (delayHead - span + delayedYaw.size) % delayedYaw.size
        val velocityYaw = RotationUtil.angleDifference(delayedYaw[delayHead], delayedYaw[oldest]) / span
        val velocityPitch = RotationUtil.angleDifference(delayedPitch[delayHead], delayedPitch[oldest]) / span
        return Rotation(
            aim.yaw + (velocityYaw * lead).coerceIn(-LEAD_CAP, LEAD_CAP),
            aim.pitch + (velocityPitch * lead).coerceIn(-LEAD_CAP, LEAD_CAP)
        )
    }

    /**
     * A jump in the requested aim point wider than [turnThreshold] — a target switch, an enemy
     * blinking behind — is answered with a short freeze first: an instant answer is the snap
     * signature. Never triggers while converging or on first acquisition, and the refractory
     * keeps a point-blank orbit from chain-freezing the hand.
     */
    private fun updateTurnReaction(target: Rotation, converging: Boolean) {
        val previousYaw = prevTargetYaw
        val previousPitch = prevTargetPitch
        prevTargetYaw = target.yaw
        prevTargetPitch = target.pitch

        if (reactionRefractory > 0) {
            reactionRefractory--
        }

        if (converging) {
            reactionLeft = 0
            engaged = false
            return
        }
        if (!engaged) {
            engaged = true
            return
        }

        val jump = hypot(
            RotationUtil.angleDifference(target.yaw, previousYaw),
            RotationUtil.angleDifference(target.pitch, previousPitch)
        )
        if (jump >= turnThreshold && reactionLeft == 0 && reactionRefractory == 0) {
            reactionLeft = turnReactTicks.random()
            reactionRefractory = REACTION_REFRACTORY
        }
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        // One swing closes most of the error; budget swing after swing at the worst-case slowdown
        // so KillAura over-estimates and is never late. The delay and a possible turn-reaction
        // freeze are wall ticks on top.
        var remaining = currentRotation.angleTo(targetRotation)
        val budget = reaction + if (remaining >= turnThreshold) turnReactTicks.last else 0
        val worstSlow = minOf(closeSpeed, farSpeed, 1f).coerceAtLeast(MIN_SPEED_FACTOR)
        var ticks = 0
        while (remaining > ARRIVED_ANGLE && ticks < MAX_TICKS) {
            val swing = swingTicks.last + (distanceScaling * (remaining / FITTS_REF)).roundToInt()
            ticks += ceil(swing / worstSlow).toInt().coerceAtLeast(1)
            remaining *= REMAIN_AFTER_SWING
        }

        return (ticks + budget).coerceIn(1, MAX_TICKS)
    }

    /**
     * The full easings.net library. `value` maps progress `x ∈ [0, 1]` to eased progress; the
     * Back / Elastic / Bounce families exceed 1 on the way, which is what makes the crosshair
     * genuinely pass the target and come back.
     */
    @Suppress("MagicNumber")
    enum class Curve(override val tag: String) : Tagged {
        SINE_IN("EaseInSine") {
            override fun value(x: Float) = 1f - cos(x * HALF_PI)
        },
        SINE_OUT("EaseOutSine") {
            override fun value(x: Float) = sin(x * HALF_PI)
        },
        SINE_IN_OUT("EaseInOutSine") {
            override fun value(x: Float) = -(cos(PI_F * x) - 1f) / 2f
        },
        QUAD_IN("EaseInQuad") {
            override fun value(x: Float) = x * x
        },
        QUAD_OUT("EaseOutQuad") {
            override fun value(x: Float) = 1f - (1f - x) * (1f - x)
        },
        QUAD_IN_OUT("EaseInOutQuad") {
            override fun value(x: Float) =
                if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).pow(2) / 2f
        },
        CUBIC_IN("EaseInCubic") {
            override fun value(x: Float) = x * x * x
        },
        CUBIC_OUT("EaseOutCubic") {
            override fun value(x: Float) = 1f - (1f - x).pow(3)
        },
        CUBIC_IN_OUT("EaseInOutCubic") {
            override fun value(x: Float) =
                if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
        },
        QUART_IN("EaseInQuart") {
            override fun value(x: Float) = x.pow(4)
        },
        QUART_OUT("EaseOutQuart") {
            override fun value(x: Float) = 1f - (1f - x).pow(4)
        },
        QUART_IN_OUT("EaseInOutQuart") {
            override fun value(x: Float) =
                if (x < 0.5f) 8f * x.pow(4) else 1f - (-2f * x + 2f).pow(4) / 2f
        },
        QUINT_IN("EaseInQuint") {
            override fun value(x: Float) = x.pow(5)
        },
        QUINT_OUT("EaseOutQuint") {
            override fun value(x: Float) = 1f - (1f - x).pow(5)
        },
        QUINT_IN_OUT("EaseInOutQuint") {
            override fun value(x: Float) =
                if (x < 0.5f) 16f * x.pow(5) else 1f - (-2f * x + 2f).pow(5) / 2f
        },
        EXPO_IN("EaseInExpo") {
            override fun value(x: Float) = if (x == 0f) 0f else 2f.pow(10f * x - 10f)
        },
        EXPO_OUT("EaseOutExpo") {
            override fun value(x: Float) = if (x == 1f) 1f else 1f - 2f.pow(-10f * x)
        },
        EXPO_IN_OUT("EaseInOutExpo") {
            override fun value(x: Float) = when {
                x == 0f -> 0f
                x == 1f -> 1f
                x < 0.5f -> 2f.pow(20f * x - 10f) / 2f
                else -> (2f - 2f.pow(-20f * x + 10f)) / 2f
            }
        },
        CIRC_IN("EaseInCirc") {
            override fun value(x: Float) = 1f - sqrt(1f - x * x)
        },
        CIRC_OUT("EaseOutCirc") {
            override fun value(x: Float) = sqrt(1f - (x - 1f) * (x - 1f))
        },
        CIRC_IN_OUT("EaseInOutCirc") {
            override fun value(x: Float) =
                if (x < 0.5f) {
                    (1f - sqrt(1f - (2f * x).pow(2))) / 2f
                } else {
                    (sqrt(1f - (-2f * x + 2f).pow(2)) + 1f) / 2f
                }
        },
        BACK_IN("EaseInBack") {
            override fun value(x: Float) = C3 * x * x * x - C1 * x * x
        },
        BACK_OUT("EaseOutBack") {
            override fun value(x: Float) = 1f + C3 * (x - 1f).pow(3) + C1 * (x - 1f).pow(2)
        },
        BACK_IN_OUT("EaseInOutBack") {
            override fun value(x: Float) =
                if (x < 0.5f) {
                    (2f * x).pow(2) * ((C2 + 1f) * 2f * x - C2) / 2f
                } else {
                    ((2f * x - 2f).pow(2) * ((C2 + 1f) * (2f * x - 2f) + C2) + 2f) / 2f
                }
        },
        ELASTIC_IN("EaseInElastic") {
            override fun value(x: Float) = when {
                x == 0f -> 0f
                x == 1f -> 1f
                else -> -2f.pow(10f * x - 10f) * sin((10f * x - 10.75f) * C4)
            }
        },
        ELASTIC_OUT("EaseOutElastic") {
            override fun value(x: Float) = when {
                x == 0f -> 0f
                x == 1f -> 1f
                else -> 2f.pow(-10f * x) * sin((10f * x - 0.75f) * C4) + 1f
            }
        },
        ELASTIC_IN_OUT("EaseInOutElastic") {
            override fun value(x: Float) = when {
                x == 0f -> 0f
                x == 1f -> 1f
                x < 0.5f -> -(2f.pow(20f * x - 10f) * sin((20f * x - 11.125f) * C5)) / 2f
                else -> 2f.pow(-20f * x + 10f) * sin((20f * x - 11.125f) * C5) / 2f + 1f
            }
        },
        BOUNCE_IN("EaseInBounce") {
            override fun value(x: Float) = 1f - bounceOut(1f - x)
        },
        BOUNCE_OUT("EaseOutBounce") {
            override fun value(x: Float) = bounceOut(x)
        },
        BOUNCE_IN_OUT("EaseInOutBounce") {
            override fun value(x: Float) =
                if (x < 0.5f) {
                    (1f - bounceOut(1f - 2f * x)) / 2f
                } else {
                    (1f + bounceOut(2f * x - 1f)) / 2f
                }
        };

        abstract fun value(x: Float): Float

        companion object {
            const val PI_F = 3.1415927f
            const val HALF_PI = PI_F / 2f
            const val C1 = 1.70158f
            const val C2 = C1 * 1.525f
            const val C3 = C1 + 1f

            /** 2π/3 and 2π/4.5 — the elastic periods from easings.net. */
            const val C4 = 2.0943951f
            const val C5 = 1.3962634f

            fun bounceOut(x: Float): Float {
                val n1 = 7.5625f
                val d1 = 2.75f
                return when {
                    x < 1f / d1 -> n1 * x * x
                    x < 2f / d1 -> {
                        val t = x - 1.5f / d1
                        n1 * t * t + 0.75f
                    }
                    x < 2.5f / d1 -> {
                        val t = x - 2.25f / d1
                        n1 * t * t + 0.9375f
                    }
                    else -> {
                        val t = x - 2.625f / d1
                        n1 * t * t + 0.984375f
                    }
                }
            }
        }
    }

    private companion object {
        /** All curves except the springy In-variants, which tremble before the movement starts. */
        val DEFAULT_CURVES: List<Curve> = Curve.entries.filterNot {
            it == Curve.ELASTIC_IN || it == Curve.ELASTIC_IN_OUT ||
                it == Curve.BOUNCE_IN || it == Curve.BOUNCE_IN_OUT
        }

        /** Below this easing denominator the span estimate is unreliable — hold the frozen one. */
        const val SPAN_EPS = 0.05f

        /** The step may pass the target by at most the error again (overshoot guard). */
        const val MAX_PASS_FACTOR = 2f
        const val PASS_HEADROOM = 1f

        /** The aim point moving further than this mid-swing re-plans the swing. */
        const val REGEN_ANGLE = 20f
        const val MAX_SWING = 20

        /** Error size that earns the full [distanceScaling] extra ticks. */
        const val FITTS_REF = 90f

        /** Widths (blocks) of the smooth blends between the distance-speed bands. */
        const val CLOSE_BLEND = 1f
        const val FAR_BLEND = 2f

        /** The guide's own cap on the player-speed boost. */
        const val SPEED_BOOST_CAP = 1.5f

        /** Fall distance (blocks) at which [fallEffect] reaches its full slowdown share. */
        const val FALL_REF = 4f
        const val FALL_MAX_SLOW = 0.3f
        const val COOLDOWN_MIDPOINT = 0.5f
        const val ERROR_REF = 90f
        const val MIN_SPEED_FACTOR = 0.15f
        const val MAX_SPEED_FACTOR = 2.5f

        const val TREMOR_FREQ_MIN = 0.15f
        const val TREMOR_FREQ_SPREAD = 0.35f
        const val TWO_PI = 6.2831855f

        const val TWITCH_DECAY = 0.45f
        const val TWITCH_PITCH_SHARE = 0.5f
        const val TICKS_PER_SECOND = 20f

        const val MIN_DISTANCE = 0.5f

        const val CONVERGE_EPS = 0.1f
        const val CONVERGE_GAIN = 0.4f
        const val CONVERGE_MIN = 0.5f

        /** Longest observation delay the ring buffer can serve; upper bound of Reaction/LeadWindow. */
        const val MAX_REACTION = 4

        /** Ceiling on the lead offset, so a noisy velocity estimate cannot fling the aim. */
        const val LEAD_CAP = 30f

        /** Ticks after a turn reaction before another may trigger (a point-blank orbit anti-chain). */
        const val REACTION_REFRACTORY = 10

        const val ARRIVED_ANGLE = 2f
        const val MAX_TICKS = 80

        /** Error left after one swing, for the tick estimate (undershoot + slowdowns budgeted). */
        const val REMAIN_AFTER_SWING = 0.25f
    }
}
