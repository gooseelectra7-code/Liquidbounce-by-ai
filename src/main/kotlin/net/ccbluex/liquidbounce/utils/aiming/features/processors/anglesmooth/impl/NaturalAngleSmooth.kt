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
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationDelta
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.boxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor-control rotation smoother built around a **critically-damped 2nd-order controller**
 * (a spring-damper). The head carries an **angular velocity** that persists tick-to-tick and can
 * only change by a bounded acceleration, so hard speed discontinuities (the "резко" tell) are
 * physically impossible — the velocity curve is continuous by construction.
 *
 * **Speed is tuned by Fitts's law, not by raw °/t caps.** The swing duration grows logarithmically
 * with the turn distance (`T = base + scaling·log2(D/W + 1)` ticks) and the spring's ω is derived
 * from it, so small combat flicks are crisp and big turns are proportionally longer and smoother —
 * one intuitive set of knobs ([baseTime]/[distanceScaling]/[targetWidth]) instead of an ω fighting
 * a separate angular cap.
 *
 * Unlike the removed Human / Chaotic smoothers the randomization is **its own**, not a
 * perpendicular gaussian bulge or a per-tick sign-flip:
 *
 *  - **Sub-movements (waypoints).** A swing is decomposed Meyer-style into a large ballistic
 *    throw (~70-90 % of the way) plus 1-3 corrective sub-movements, each a random fraction of the
 *    remaining distance with shrinking lateral scatter. The spring chases the current waypoint, so
 *    the velocity naturally dips and re-launches between sub-movements — the signature structure
 *    of real aiming. (See [buildWaypoints].)
 *  - **Torque noise.** A small low-frequency [ValueNoise]-driven rotation is applied to the
 *    velocity *vector* each tick, so the path meanders organically with no explicit arc/envelope.
 *    Fades to zero near the target → hitrate-neutral.
 *  - **Breathing controller.** ω (speed) and ζ (damping) random-walk during the swing, so the
 *    stiffness "breathes" and no two velocity profiles match.
 *  - **Session identity.** Per-session persistent gains (yaw/pitch/ω) rolled once in [init], so
 *    this "player" reads as one consistent person rather than a per-swing re-randomizer.
 *  - **Value-noise tremor.** Broadband interpolated noise (no single spectral spike a classifier
 *    could fingerprint), replacing the old copied sine tremor.
 *  - **Target prediction.** The aim point is extrapolated forward along the target's low-pass
 *    velocity and the whole controller aims at that predicted point — it leads the opponent
 *    instead of chasing (trailing) their current position.
 *  - **Hold when hitting.** Once settled on a target whose hitbox the crosshair-ray still hits, the
 *    head stops moving entirely and only re-aims when it would miss — minimal motion, like a real
 *    player who arrives and then just clicks. (See [tryHold].)
 *
 * Combined with the swing counter and the continuous [ThreadLocalRandom] stream, no two swings
 * ever repeat. All layers are toggleable; defaults are deliberately weak.
 */
@Suppress("TooManyFunctions")
class NaturalAngleSmooth(
    parent: ModeValueGroup<*>,
) : AngleSmooth("Natural", parent, listOf("Smooth", "Motor", "Spring")) {

    override val prefersFannedAimPoint: Boolean get() = true

    // --- Core controller : Fitts-law speed ----------------------------------------------------
    //
    // Speed is no longer a stiffness knob fighting a separate °/t cap. Instead the swing *duration*
    // is set by Fitts's law — `T = base + scaling·log2(distance/width + 1)` ticks — and the spring's
    // natural frequency is derived from it (ω ≈ SETTLE_TICKS / T). So small combat flicks finish in
    // a couple of crisp ticks while big turns take proportionally (logarithmically) longer and stay
    // smooth, exactly like a real hand. There is no hard angular cap: the critically-damped spring
    // self-limits its peak velocity, which is the whole point of the model.

    /** Baseline swing time in ticks (Fitts `a`) — the floor regardless of distance. Smaller = snappier. */
    private val baseTime by floatRange("BaseTime", 2.5f..3.5f, 1.0f..20f, "ticks")

    /** Distance scaling (Fitts `b`) — how much extra time a larger turn costs (log of distance). */
    private val distanceScaling by float("DistanceScaling", 1.2f, 0.0f..5.0f)

    /** Assumed target angular size (Fitts `W`) — the smaller this is, the longer big turns take. */
    private val targetWidth by float("TargetWidth", 8f, 1.0f..45f, "°")

    /**
     * Per-swing velocity-profile skew. Each swing rolls a signed skew in `[-amount, amount]`: a
     * positive roll front-loads the swing (fast launch, long homing tail), a negative roll back-loads
     * it (slow start, late peak). Endpoint-neutral — only *when* the peak speed lands changes — so it
     * adds kinematic variety an ML classifier expects without affecting where the swing ends. 0 = off.
     */
    private val velocitySkew by float("VelocitySkew", 0.4f, 0f..1f)

    /**
     * Yaw/pitch speed asymmetry. Real hands turn horizontally faster than vertically, and the ratio
     * isn't fixed — two perfectly-synchronized axes are a strong bot tell. Pitch runs at [pitchRatio]
     * of yaw's ω, both warped by a per-session identity and a per-swing [axisJitter] roll.
     */
    private val pitchRatio by float("PitchRatio", 0.78f, 0.4f..1.2f, "×")
    private val axisJitter by float("AxisJitter", 0.12f, 0f..0.5f)

    /**
     * Engagement adaptation: aim is a touch more careful (slower) at the very start of a fight and
     * ramps to full pace as it goes on — a macro arc over the engagement, not per-tick noise. This
     * is the fraction ω is reduced by on the first tick of a new engagement (0 = off). Hitrate-safe:
     * "careful early" is *more* accurate, and it's a small ω trim that fully recovers within ~2 s.
     */
    private val engagementWarmup by float("EngagementWarmup", 0.15f, 0f..0.5f)

    /** Per-session warping of the persistent identity gains (±fraction). */
    private val sessionWarping by float("SessionWarping", 0.12f, 0f..0.4f)

    // --- Sub-movements (ballistic + corrections) ----------------------------------------------

    private inner class SubMovements : ToggleableValueGroup(this, "SubMovements", true) {
        /** Fraction of the full distance covered by the first ballistic throw. */
        val ballistic by floatRange("Ballistic", 0.70f..0.90f, 0.4f..1.0f)

        /** Number of corrective sub-movements after the ballistic throw. */
        val corrections by intRange("Corrections", 1..3, 0..5)

        /** Fraction of the *remaining* distance each correction covers. */
        val correctionReach by floatRange("CorrectionReach", 0.55f..0.85f, 0.3f..1.0f)

        /** Lateral scatter of a waypoint as a fraction of distance (shrinks per correction). */
        val scatter by floatRange("Scatter", 0.02f..0.07f, 0.0f..0.3f)

        /** Advance to the next waypoint at this fraction of the segment length (keeps momentum). */
        val arrive by float("Arrive", 0.30f, 0.05f..0.9f)
    }

    private val subMovements = tree(SubMovements())

    // --- Torque noise (organic meander on the velocity vector) --------------------------------

    private inner class Torque : ToggleableValueGroup(this, "Torque", true) {
        /** Max rotation applied to the velocity vector per tick (radians). */
        val amplitude by float("Amplitude", 0.07f, 0f..0.5f, "rad")

        /** Value-noise advance per tick — smaller = slower, smoother meander. */
        val rate by float("Rate", 0.10f, 0.01f..1.0f)

        /** Below this angle to target the torque fades out (so it never pushes off the hitbox). */
        val fadeAngle by float("FadeAngle", 7f, 0.0f..45f, "°")
    }

    private val torque = tree(Torque())

    // --- Breathing controller (ω/ζ random-walk) -----------------------------------------------

    private inner class Breathing : ToggleableValueGroup(this, "Breathing", true) {
        /** ω is multiplied by 1 ± this. */
        val omega by float("OmegaWander", 0.20f, 0f..0.6f)

        /** ζ wanders by ± this around 1.0 (dipping below 1 → occasional natural overshoot). */
        val damping by float("DampingWander", 0.18f, 0f..0.4f)

        /** Value-noise advance per tick for the breathing walk. */
        val rate by float("Rate", 0.06f, 0.01f..0.5f)
    }

    private val breathing = tree(Breathing())

    // --- Biomechanics : motor-control laws an ML anticheat looks for ---------------------------
    //
    // Real hands obey kinematic invariants a bot ignores. These reproduce the *correlations* a
    // classifier expects (speed↔curvature, noise↔speed, error↔speed) rather than adding raw noise,
    // which is itself a tell.

    private inner class Biomechanics : ToggleableValueGroup(this, "Biomechanics", true) {
        /** 2/3 power law: the head slows through curves (v ∝ curvature^-1/3). 0 = off. */
        val powerLaw by float("PowerLaw", 0.5f, 0f..1f)

        /** Signal-dependent noise (Harris-Wolpert): tremor amplitude scales with current speed. */
        val signalNoise by float("SignalNoise", 0.6f, 0f..1f)

        /** Speed-accuracy tradeoff: faster swings land with more endpoint error (then corrected). */
        val speedError by float("SpeedError", 0.5f, 0f..1f)
    }

    private val biomech = tree(Biomechanics())

    // --- Movement coupling : aim co-varies with the player's own motion ------------------------
    //
    // A bot's aim is statistically independent of its WASD movement; a real player's isn't — a
    // counter-strafe or a stop jolts the hand, and aim style drifts over a session (warmup →
    // fatigue). ML anticheats cross-correlate aim against movement, so we reproduce that link.

    private inner class MovementCoupling : ToggleableValueGroup(this, "MovementCoupling", true) {
        /** Aim nudge magnitude per unit of the player's own velocity change (strafe/stop). */
        val strafe by float("StrafeJolt", 0.4f, 0f..3f, "°")

        /** Slow per-session drift of speed & aim (warmup ramps in, then a slow wander). */
        val sessionDrift by float("SessionDrift", 0.4f, 0f..1f)
    }

    private val movementCoupling = tree(MovementCoupling())

    // --- Attention : a bot aims at one point forever; a human's gaze shifts --------------------

    private inner class Attention : ToggleableValueGroup(this, "Attention", true) {
        /** Aim point trails the target by a low-pass lag (humans don't lock on instantly). */
        val aimLag by float("AimLag", 0.2f, 0f..0.9f)

        /** Bias aim toward a slowly-switching body part (legs/torso/head) instead of one fixed point. */
        val bodyPartBias by float("BodyPartBias", 0.5f, 0f..1f)

        /** Per-second chance of a brief attention lapse where we stop correcting. */
        val lookAwayChance by float("LookAwayChance", 0.02f, 0f..0.3f)

        /** Length of a look-away lapse. */
        val lookAwayTicks by intRange("LookAwayTicks", 1..3, 1..6, "ticks")
    }

    private val attention = tree(Attention())

    // --- Value-noise tremor --------------------------------------------------------------------

    private inner class Tremor : ToggleableValueGroup(this, "Tremor", true) {
        val yaw by floatRange("Yaw", 0.03f..0.10f, 0.0f..1.0f, "°")
        val pitch by floatRange("Pitch", 0.02f..0.07f, 0.0f..1.0f, "°")
        val rate by float("Rate", 0.20f, 0.01f..1.0f)
    }

    private val tremor = tree(Tremor())

    /** When converged, emit `tremor * IdleDrift` instead of a dead-still crosshair. */
    private val idleDrift by float("IdleDrift", 0.5f, 0f..1f, "×")

    // --- Target prediction (lead the aim point instead of chasing it) --------------------------

    private inner class Prediction : ToggleableValueGroup(this, "Prediction", true) {
        /** How many ticks of the target's motion to extrapolate the aim point forward. */
        val lead by float("Lead", 2.0f, 0f..6f, "ticks")

        /** Hard cap (°) on how far ahead of the real target the prediction may sit. */
        val maxLead by float("MaxLead", 12f, 0f..45f, "°")

        /** Low-pass smoothing of the estimated target velocity (0 = none, 1 = frozen). */
        val smoothing by float("Smoothing", 0.6f, 0f..0.95f)
    }

    private val prediction = tree(Prediction())

    // --- Anti-spin inside the hitbox ----------------------------------------------------------

    private inner class AntiSpin : ToggleableValueGroup(this, "AntiSpin", true) {
        val distance by float("Distance", 1.3f, 0.0f..4.0f, "m")
        val slowdown by float("Slowdown", 0.22f, 0.05f..1.0f, "×")
    }

    private val antiSpin = tree(AntiSpin())

    // --- Hold when hitting --------------------------------------------------------------------
    //
    // Once the swing has settled onto the target, stop moving the crosshair entirely *as long as a
    // ray from the current rotation still connects with the hitbox* — i.e. while we can still hit.
    // We only resume (re-aim) the moment that ray would miss. This minimizes motion to the bare
    // minimum a real player uses: arrive, then sit still and click until the opponent slips off the
    // crosshair. Engaging only once velocity is already low keeps the stop from being an abrupt
    // (and tell-tale) hard brake.

    private inner class HoldWhenHitting : ToggleableValueGroup(this, "HoldWhenHitting", true) {
        /** Max ray distance (blocks) treated as "in range to hit". */
        val reach by float("Reach", 3.0f, 1.0f..6.0f, "m")

        /** Brake velocity to a stop over this many ticks (re-rolled per tick → non-metronomic). */
        val stopTicks by floatRange("StopTicks", 2.0f..3.0f, 1.0f..6.0f, "ticks")

        /** Speed (°/t) below which the brake latches into a full hold. */
        val settleSpeed by float("SettleSpeed", 1.5f, 0.1f..15f, "°/t")

        /** Random human reaction delay (ticks) before re-aiming after the ray slips off. */
        val releaseDelay by intRange("ReleaseDelay", 0..1, 0..4, "ticks")

        /** Inhibition of return: extra latency re-acquiring the spot we just left (attention effect). */
        val iorDelay by intRange("IorDelay", 0..1, 0..3, "ticks")

        /** Sub-degree value-noise wander around the hold anchor (0 = frame-perfect freeze). */
        val microMotion by float("MicroMotion", 0.05f, 0f..1.0f, "°")

        /** Value-noise advance per tick for the hold micro-motion. */
        val microRate by float("MicroRate", 0.15f, 0.01f..1.0f)
    }

    private val hold = tree(HoldWhenHitting())

    // --- Big-turn humanizer (enemy behind you) ------------------------------------------------
    //
    // Neuro-anticheats flag the *signature* of a large turn, not its raw speed: an instant
    // zero-latency reaction and a monotonic stop dead-on-target. So we keep the (Fitts) speed —
    // protecting hitrate — and instead make a big turn read as a human flick: a reaction latency
    // before it starts, and a ballistic overshoot past the target that the correction sub-movements
    // settle back. A real fast 180° has both; a bot snap has neither.

    private inner class BigTurn : ToggleableValueGroup(this, "BigTurn", true) {
        /** Turns wider than this (°) get the human flick treatment. */
        val threshold by float("Threshold", 90f, 20f..180f, "°")

        /** Reaction latency (ticks) before a big turn starts — breaks the instant-snap correlation. */
        val reactionTicks by intRange("ReactionTicks", 1..3, 0..6, "ticks")

        /** Ex-Gaussian tail (ticks) on the reaction — real reaction times have a long right tail. */
        val reactionTail by float("ReactionTail", 1.0f, 0f..4f, "ticks")

        /** Overshoot past the target as a fraction of the turn, settled back by the corrections. */
        val overshoot by floatRange("Overshoot", 0.06f..0.16f, 0.0f..0.5f)

        /** Absolute cap (°) on the overshoot so a huge turn doesn't fling far past then back. */
        val maxOvershoot by float("MaxOvershoot", 10f, 0.0f..45f, "°")

        /** Ease the launch in over this many ticks so the post-reaction start isn't a hard step. */
        val smoothLaunch by intRange("SmoothLaunch", 2..3, 0..6, "ticks")

        /** Mousepad pickup: turns wider than this (°) get a brief mid-swing slowdown (a re-grip). */
        val pickupAngle by float("PickupAngle", 140f, 60f..180f, "°")

        /** Length of the pickup slowdown (ticks). 0 = no pickup. */
        val pickupTicks by intRange("PickupTicks", 1..2, 0..4, "ticks")

        /** ω multiplier during the pickup (lower = a more pronounced re-grip dip). */
        val pickupSlowdown by float("PickupSlowdown", 0.25f, 0.05f..1.0f, "×")
    }

    private val bigTurn = tree(BigTurn())

    /** Min angle (°) the planned target must move before we rebuild the swing. */
    private val regenerateThreshold by float("RegenerateThreshold", 10f, 0.0f..90f, "°")

    // --- Persistent session identity -----------------------------------------------------------

    private val sessionOmegaScale: Float
    private val sessionAxisYaw: Float
    private val sessionAxisPitch: Float

    init {
        val r = ThreadLocalRandom.current()
        sessionOmegaScale = 1f + (r.nextFloat() - 0.5f) * sessionWarping
        // Per-session yaw/pitch identity — this "player" has its own, fixed axis balance.
        sessionAxisYaw = 1f + (r.nextFloat() - 0.5f) * sessionWarping
        sessionAxisPitch = 1f + (r.nextFloat() - 0.5f) * sessionWarping
    }

    // --- Controller state ----------------------------------------------------------------------

    private var velYaw = 0f
    private var velPitch = 0f

    private var plannedTarget: Rotation? = null
    private var swingOmega = 1.1f
    private var swingCounter = 0L

    // Per-swing speed randomization: profile skew, axis gains, and the swing's start distance
    // (for the skew's progress term).
    private var swingSkew = 0f
    private var swingYawGain = 1f
    private var swingPitchGain = 1f
    private var swingStartDistance = 1f
    /** True while we're frozen on a hittable target (see [tryHold]). */
    private var holding = false

    /** The rotation we latched the hold at; micro-motion wanders around it without drifting. */
    private var holdAnchor: Rotation? = null

    /** Human reaction countdown after the ray slips off before we re-aim. */
    private var releaseTicksLeft = 0

    /** Launch ease-in countdown for a big turn (ramps ω up so the start isn't a hard step). */
    private var launchTicksLeft = 0
    private var launchTotal = 0

    /** Mousepad-pickup state: armed for the current big turn, and the mid-swing slowdown countdown. */
    private var pickupArmed = false
    private var pickupTicksLeft = 0

    /** Engagement adaptation: ticks aiming at a target this fight, and whether we had one last tick. */
    private var engagementTicks = 0
    private var hadTargetLastTick = false

    private val waypoints = ArrayList<Rotation>(6)
    private val arriveThresholds = ArrayList<Float>(6)
    private var waypointIndex = 0

    private var reactionTicksLeft = 0

    // Target velocity estimate (prediction).
    private var prevTarget: Rotation? = null
    private var targetVelYaw = 0f
    private var targetVelPitch = 0f

    // Previous-tick velocity + heading change, for the 2/3 power law (biomechanics).
    private var prevVelYaw = 0f
    private var prevVelPitch = 0f
    private var lastTurnRate = 0f

    // Movement coupling state.
    private var prevPlayerVelX = 0.0
    private var prevPlayerVelY = 0.0
    private var prevPlayerVelZ = 0.0
    private var moveJoltYaw = 0f
    private var moveJoltPitch = 0f
    private var sessionTicks = 0L

    // Attention state.
    private var laggedAim: Rotation? = null
    private var lookAwayTicksLeft = 0
    private var bodyFraction = 0.5f
    private var bodyPartTimer = 0

    // Value-noise generators (own, broadband — not Human's sine).
    private val noiseOmega = ValueNoise()
    private val noiseDamp = ValueNoise()
    private val noiseTorque = ValueNoise()
    private val noiseTremorYaw = ValueNoise()
    private val noiseTremorPitch = ValueNoise()
    private val noiseHoldYaw = ValueNoise()
    private val noiseHoldPitch = ValueNoise()
    private val noiseMoveYaw = ValueNoise()
    private val noiseMovePitch = ValueNoise()
    private val noiseSession = ValueNoise()

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        updateTargetVelocity(rotationTarget, targetRotation)
        sessionTicks++

        // Engagement adaptation: reset the careful-start ramp each time a fresh target is acquired.
        val hasTarget = rotationTarget.entity != null
        if (hasTarget && !hadTargetLastTick) engagementTicks = 0
        engagementTicks = if (hasTarget) engagementTicks + 1 else 0
        hadTargetLastTick = hasTarget

        // Hold-when-hitting and look-away lapses take priority over all motion.
        earlyExit(rotationTarget, currentRotation, targetRotation)?.let { return it }

        // Predict where the aim point will be and aim THERE (lead, don't trail), then shape it with
        // the attention model (body-part bias + low-pass lag).
        val aimTarget = applyAttention(rotationTarget, predictedTarget(targetRotation))

        if (currentRotation.approximatelyEquals(aimTarget, NEAR_TARGET_EPS)) {
            decayVelocity()
            plannedTarget = null
            laggedAim = null
            return applyIdleDrift(aimTarget)
        }

        val closeFactor = closeFactor(rotationTarget)
        ensureSwing(currentRotation, aimTarget)

        if (reactionTicksLeft > 0) {
            reactionTicksLeft--
            decayVelocity()
            return applyIdleDrift(currentRotation)
        }

        return drive(currentRotation, aimTarget, closeFactor)
    }

    /** Hold-when-hitting + attention look-away — the two reasons to bypass active motion. */
    private fun earlyExit(rotationTarget: RotationTarget, current: Rotation, target: Rotation): Rotation? {
        if (hold.enabled) tryHold(rotationTarget, current, target)?.let { return it }
        if (attention.enabled) tryLookAway(current)?.let { return it }
        return null
    }

    /** The spring-damper motion tick: integrate toward the current guide and emit the new rotation. */
    private fun drive(currentRotation: Rotation, aimTarget: Rotation, closeFactor: Float): Rotation {
        val guide = currentGuide(aimTarget)
        val err = currentRotation.rotationDeltaTo(guide)
        val distanceToTarget = currentRotation.angleTo(aimTarget)

        // Breathing ω/ζ on top of the per-swing Fitts ω. No hard °/t cap — the critically-damped
        // spring self-limits its peak velocity.
        val rng = ThreadLocalRandom.current()
        val omegaMul = if (breathing.enabled) 1f + breathing.omega * noiseOmega.next(rng, breathing.rate) else 1f
        val zeta = if (breathing.enabled) {
            (1f + breathing.damping * noiseDamp.next(rng, breathing.rate)).coerceIn(MIN_ZETA, MAX_ZETA)
        } else {
            1f
        }
        // All ω modulators fold in here: 2/3 power law, session drift, big-turn launch ramp,
        // per-swing velocity skew, mid-swing mousepad pickup, and the engagement warmup arc.
        val progress = (1f - distanceToTarget / swingStartDistance).coerceIn(0f, 1f)
        val omega = (swingOmega * sessionOmegaScale * omegaMul * sqrt(closeFactor) *
            powerLawFactor() * sessionDrift(rng) * launchFactor() * skewFactor(progress) *
            pickupFactor(progress) * engagementFactor()).coerceAtLeast(MIN_OMEGA)
        // Per-axis ω: pitch is its own (typically slower) frequency so the two axes aren't synced.
        val omegaYaw = (omega * swingYawGain).coerceAtLeast(MIN_OMEGA)
        val omegaPitch = (omega * swingPitchGain).coerceAtLeast(MIN_OMEGA)
        val kY = omegaYaw * omegaYaw
        val cY = 2f * zeta * omegaYaw
        val kP = omegaPitch * omegaPitch
        val cP = 2f * zeta * omegaPitch

        // Torque: rotate the velocity vector by a faded low-frequency noise angle.
        applyTorque(rng, distanceToTarget)

        var stepYaw = 0f
        var stepPitch = 0f
        val h = 1f / SUBSTEPS
        repeat(SUBSTEPS) {
            velYaw += (kY * err.deltaYaw - cY * velYaw) * h
            velPitch += (kP * err.deltaPitch - cP * velPitch) * h
            stepYaw += velYaw * h
            stepPitch += velPitch * h
        }

        // Clamp to the current guide (not the raw target) so a big-turn overshoot waypoint can carry
        // us past the target before the corrections settle back, while preventing a wild over-step.
        stepYaw = clampStep(stepYaw, err.deltaYaw)
        stepPitch = clampStep(stepPitch, err.deltaPitch)

        advanceWaypoint(currentRotation)

        // Signal-dependent noise: tremor grows with speed (and vanishes at the settle, helping aim).
        val (tY, tP) = sampleTremor(rng, signalNoiseScale())
        updateMovementJolt(rng)
        updateCurvature()

        ModuleDebug.debugParameter(this, "Swing", swingCounter)
        ModuleDebug.debugParameter(this, "Omega", omega)
        ModuleDebug.debugParameter(this, "Distance", distanceToTarget)

        return Rotation(
            currentRotation.yaw + stepYaw + tY + moveJoltYaw,
            currentRotation.pitch + stepPitch + tP + moveJoltPitch,
        )
    }

    /** Low-pass estimate of the target's per-tick angular velocity for prediction. */
    private fun updateTargetVelocity(rotationTarget: RotationTarget, targetRotation: Rotation) {
        val prev = prevTarget
        if (rotationTarget.entity != null && prev != null) {
            val raw = prev.rotationDeltaTo(targetRotation)
            val a = prediction.smoothing
            targetVelYaw = targetVelYaw * a + raw.deltaYaw * (1f - a)
            targetVelPitch = targetVelPitch * a + raw.deltaPitch * (1f - a)
        } else {
            targetVelYaw = 0f
            targetVelPitch = 0f
        }
        prevTarget = targetRotation
    }

    /**
     * Extrapolate the aim point forward along the target's (low-passed) angular velocity, capped
     * so a brief twitch can't fling the crosshair off into space. This is the "predict" — the
     * whole controller then aims at this point instead of the target's current position.
     */
    private fun predictedTarget(targetRotation: Rotation): Rotation {
        if (!prediction.enabled || prediction.lead <= 0f) return targetRotation
        var offYaw = targetVelYaw * prediction.lead
        var offPitch = targetVelPitch * prediction.lead
        val mag = hypot(offYaw, offPitch)
        if (prediction.maxLead > 0f && mag > prediction.maxLead) {
            val scale = prediction.maxLead / mag
            offYaw *= scale
            offPitch *= scale
        }
        return Rotation(targetRotation.yaw + offYaw, targetRotation.pitch + offPitch)
    }

    /** The point the spring chases this tick — an intermediate waypoint, or the predicted target. */
    private fun currentGuide(aimTarget: Rotation): Rotation {
        val onFinal = waypointIndex >= waypoints.size - 1 || waypoints.isEmpty()
        return if (onFinal) aimTarget else waypoints[waypointIndex]
    }

    private fun applyTorque(rng: ThreadLocalRandom, distanceToTarget: Float) {
        if (!torque.enabled || torque.amplitude <= 0f) return
        val fade = if (torque.fadeAngle > 0f) (distanceToTarget / torque.fadeAngle).coerceIn(0f, 1f) else 1f
        val theta = torque.amplitude * noiseTorque.next(rng, torque.rate) * fade
        if (theta == 0f) return
        val cs = cos(theta)
        val sn = sin(theta)
        val vy = velYaw
        val vp = velPitch
        velYaw = vy * cs - vp * sn
        velPitch = vy * sn + vp * cs
    }

    private fun advanceWaypoint(currentRotation: Rotation) {
        if (waypointIndex >= waypoints.size - 1) return
        if (currentRotation.angleTo(waypoints[waypointIndex]) <= arriveThresholds[waypointIndex]) {
            waypointIndex++
        }
    }

    /**
     * Stop-when-hitting. The moment a ray from [current] connects with the hitbox we stop driving
     * toward the aim point and instead **brake the velocity to zero over ~[HoldWhenHitting.stopTicks]
     * ticks**, then hold and only re-aim once the ray would miss. Braking from the live velocity
     * (rather than hard-cutting) makes the stop a smooth 2-3 tick settle, not a tell-tale snap.
     *
     * While held the crosshair wanders by a sub-degree [HoldWhenHitting.microMotion] value-noise
     * around the latched anchor (so it isn't frame-perfect still), and after the ray slips off we
     * wait a random [HoldWhenHitting.releaseDelay] before re-aiming (human reaction).
     *
     * @return the rotation to emit (mid-brake / held / reacting), or null to fall through to motion.
     */
    private fun tryHold(rotationTarget: RotationTarget, current: Rotation, targetRotation: Rotation): Rotation? {
        val entity = rotationTarget.entity
        val canHit = entity != null && isLookingAtEntity(entity, hold.reach.toDouble(), current) != null

        if (!canHit) return handleHoldMiss(current)

        if (holding) return heldRotation(current)

        if (hypot(velYaw, velPitch) <= hold.settleSpeed) {
            holding = true
            holdAnchor = current
            velYaw = 0f
            velPitch = 0f
            plannedTarget = null
            return heldRotation(current)
        }

        // Brake: exponential decay landing on ~STOP_RESIDUAL of the speed after stopTicks ticks.
        // stopTicks is re-rolled each tick so the deceleration curve isn't perfectly smooth/metronomic.
        val decay = STOP_RESIDUAL.pow(1f / hold.stopTicks.random().coerceAtLeast(1f))
        velYaw *= decay
        velPitch *= decay
        // Don't coast past the actual target while braking.
        val realDelta = current.rotationDeltaTo(targetRotation)
        return Rotation(
            current.yaw + clampStep(velYaw, realDelta.deltaYaw),
            current.pitch + clampStep(velPitch, realDelta.deltaPitch),
        )
    }

    /** Ray no longer connects: drop the hold and serve out any human reaction delay before re-aiming. */
    private fun handleHoldMiss(current: Rotation): Rotation? {
        if (holding) {
            holding = false
            holdAnchor = null
            // Release delay + inhibition of return (slower to snap back to the spot we just left).
            releaseTicksLeft = (hold.releaseDelay.random() + hold.iorDelay.random()).coerceAtLeast(0)
        }
        if (releaseTicksLeft > 0) {
            releaseTicksLeft--
            decayVelocity()
            return current
        }
        return null
    }

    /** The held crosshair: the latched anchor plus a tiny value-noise wander (kept on the hitbox). */
    private fun heldRotation(current: Rotation): Rotation {
        val anchor = holdAnchor ?: current.also { holdAnchor = it }
        if (hold.microMotion <= 0f) return anchor
        val rng = ThreadLocalRandom.current()
        val y = noiseHoldYaw.next(rng, hold.microRate) * hold.microMotion
        val p = noiseHoldPitch.next(rng, hold.microRate) * hold.microMotion
        return Rotation(anchor.yaw + y, anchor.pitch + p)
    }

    /**
     * Attention shaping of the aim point: bias toward a slowly-switching body part, then trail the
     * result through a low-pass lag. A bot pins one fixed point; a human's gaze shifts and lags.
     */
    private fun applyAttention(rotationTarget: RotationTarget, aim: Rotation): Rotation {
        if (!attention.enabled) return aim
        var result = aim
        val entity = rotationTarget.entity
        if (entity != null && attention.bodyPartBias > 0f) {
            result = biasToBodyPart(entity, result)
        }
        if (attention.aimLag > 0f) {
            val prev = laggedAim
            result = if (prev != null && prev.angleTo(result) <= LAG_RESET_ANGLE) {
                prev.interpolateTo(result, (1f - attention.aimLag).coerceIn(LAG_MIN_FACTOR, 1f))
            } else {
                result
            }
        }
        laggedAim = result
        return result
    }

    /** Bias the aim's pitch toward a slowly-chosen body fraction of the hitbox (legs/torso/head). */
    private fun biasToBodyPart(entity: Entity, aim: Rotation): Rotation {
        if (bodyPartTimer <= 0) {
            val rng = ThreadLocalRandom.current()
            bodyFraction = BODY_MIN + rng.nextFloat() * (BODY_MAX - BODY_MIN)
            bodyPartTimer = BODY_SWITCH_MIN + rng.nextInt(BODY_SWITCH_RANGE)
        }
        bodyPartTimer--
        val box = entity.boundingBox
        val point = Vec3(
            (box.minX + box.maxX) * 0.5,
            box.minY + bodyFraction * (box.maxY - box.minY),
            (box.minZ + box.maxZ) * 0.5,
        )
        val bodyRot = Rotation.lookingAt(point, player.eyePosition)
        val dPitch = aim.rotationDeltaTo(bodyRot).deltaPitch * attention.bodyPartBias
        return Rotation(aim.yaw, aim.pitch + dPitch)
    }

    /** Rare brief attention lapse: stop correcting for a few ticks (gaze drift). */
    private fun tryLookAway(current: Rotation): Rotation? {
        if (lookAwayTicksLeft > 0) {
            lookAwayTicksLeft--
            decayVelocity()
            val (tY, tP) = sampleTremor(ThreadLocalRandom.current())
            return Rotation(current.yaw + tY, current.pitch + tP)
        }
        if (attention.lookAwayChance > 0f &&
            ThreadLocalRandom.current().nextFloat() < attention.lookAwayChance / TICKS_PER_SECOND) {
            lookAwayTicksLeft = attention.lookAwayTicks.random()
            plannedTarget = null
            return current
        }
        return null
    }

    /** 1.0 normally; [AntiSpin.slowdown] when inside the opponent hitbox. */
    private fun closeFactor(rotationTarget: RotationTarget): Float {
        if (!antiSpin.enabled) return 1f
        val entity = rotationTarget.entity ?: return 1f
        return if (player.boxedDistanceTo(entity) <= antiSpin.distance) antiSpin.slowdown else 1f
    }

    private fun clampStep(step: Float, realDelta: Float): Float =
        if (realDelta == 0f) 0f else step.coerceIn(-abs(realDelta), abs(realDelta))

    private fun decayVelocity() {
        velYaw *= VELOCITY_DECAY
        velPitch *= VELOCITY_DECAY
    }

    private fun ensureSwing(currentRotation: Rotation, targetRotation: Rotation) {
        val planned = plannedTarget
        if (planned != null && planned.angleTo(targetRotation) <= regenerateThreshold) {
            return
        }
        startSwing(currentRotation, targetRotation)
    }

    /**
     * Begin a new swing: derive ω from the Fitts duration for this distance and rebuild the
     * waypoint chain. Velocity state is **left untouched**, so a swing change mid-combat keeps
     * momentum instead of snapping.
     */
    private fun startSwing(currentRotation: Rotation, targetRotation: Rotation) {
        val delta = currentRotation.rotationDeltaTo(targetRotation)
        val distance = hypot(delta.deltaYaw, delta.deltaPitch).coerceAtLeast(MIN_DISTANCE)

        swingCounter++
        swingOmega = omegaForDuration(fittsDuration(distance))
        swingStartDistance = distance

        val rng = ThreadLocalRandom.current()
        // Per-swing velocity-profile skew (signed): front- or back-loads the speed peak.
        swingSkew = (rng.nextFloat() - 0.5f) * 2f * velocitySkew
        // Per-swing yaw/pitch gains = session identity × per-swing jitter; pitch additionally slower.
        swingYawGain = sessionAxisYaw * (1f + (rng.nextFloat() - 0.5f) * 2f * axisJitter)
        swingPitchGain = sessionAxisPitch * pitchRatio * (1f + (rng.nextFloat() - 0.5f) * 2f * axisJitter)

        val isBigTurn = bigTurn.enabled && distance >= bigTurn.threshold
        // Big turns overshoot the target (settled back by the corrections) — a human flick signature.
        // The overshoot fraction is capped to an absolute angle so a huge turn doesn't fling far past.
        val overshoot = if (isBigTurn) {
            1f + bigTurn.overshoot.random().coerceAtMost(bigTurn.maxOvershoot / distance)
        } else {
            1f
        }
        buildWaypoints(currentRotation, delta, distance, overshoot)

        // Reaction latency before a big turn starts (ex-Gaussian: bell + long right tail).
        reactionTicksLeft = if (isBigTurn) exGaussianTicks(bigTurn.reactionTicks, bigTurn.reactionTail) else 0
        // Ease the launch in (ramp ω up) so the start after the reaction isn't a hard step.
        launchTotal = if (isBigTurn) bigTurn.smoothLaunch.random().coerceAtLeast(0) else 0
        launchTicksLeft = launchTotal
        // Mousepad pickup: arm a brief mid-swing slowdown for very wide turns (a re-grip).
        pickupArmed = isBigTurn && distance >= bigTurn.pickupAngle && bigTurn.pickupTicks.last > 0
        pickupTicksLeft = 0
        plannedTarget = targetRotation
    }

    /** Fitts's law swing time in ticks: `T = base + scaling·log2(distance/width + 1)`. */
    private fun fittsDuration(distance: Float): Float {
        val index = log2(distance / targetWidth.coerceAtLeast(MIN_WIDTH) + 1f)
        return (baseTime.random() + distanceScaling * index).coerceAtLeast(MIN_DURATION)
    }

    /** ω that makes a critically-damped spring settle in roughly [duration] ticks. */
    private fun omegaForDuration(duration: Float): Float =
        (SETTLE_TICKS / duration.coerceAtLeast(MIN_DURATION)).coerceAtLeast(MIN_OMEGA)

    /**
     * Meyer-style sub-movement chain: a ballistic throw covering most of the distance (×[overshoot],
     * so big turns deliberately fly *past* the target), then a handful of corrections each closing a
     * signed fraction of what's left — which pulls the crosshair back from an overshoot just as a
     * real flick does. The final waypoint is exactly on target so we still converge.
     */
    private fun buildWaypoints(start: Rotation, delta: RotationDelta, distance: Float, overshoot: Float) {
        waypoints.clear()
        arriveThresholds.clear()
        waypointIndex = 0

        if (!subMovements.enabled) {
            waypoints.add(Rotation(start.yaw + delta.deltaYaw, start.pitch + delta.deltaPitch))
            arriveThresholds.add(ARRIVE_MIN_DEG)
            return
        }

        val rng = ThreadLocalRandom.current()
        val perpYaw = -delta.deltaPitch / distance
        val perpPitch = delta.deltaYaw / distance
        // Speed-accuracy: faster swings scatter more at the endpoints.
        val errScale = speedErrorScale()

        var prev = start
        // Ballistic throw, scaled by overshoot so it can land beyond the target (>1).
        var cumulative = subMovements.ballistic.random() * overshoot
        var scatterScale = 1f
        addWaypoint(
            start, delta, perpYaw, perpPitch, cumulative,
            distance * subMovements.scatter.random() * errScale, rng, prev,
        )
        prev = waypoints.last()

        val n = subMovements.corrections.random()
        repeat(n) {
            // Signed remaining → corrections converge to the target from either side (incl. overshoot).
            val remaining = 1f - cumulative
            cumulative += remaining * subMovements.correctionReach.random()
            scatterScale *= SCATTER_SHRINK
            val lateral = distance * subMovements.scatter.random() * scatterScale * errScale
            addWaypoint(start, delta, perpYaw, perpPitch, cumulative, lateral, rng, prev)
            prev = waypoints.last()
        }

        // Final waypoint exactly on target (overridden to the live target at chase time).
        addWaypoint(start, delta, perpYaw, perpPitch, 1f, 0f, rng, prev)
    }

    @Suppress("LongParameterList")
    private fun addWaypoint(
        start: Rotation,
        delta: RotationDelta,
        perpYaw: Float,
        perpPitch: Float,
        fraction: Float,
        lateralMag: Float,
        rng: ThreadLocalRandom,
        prev: Rotation,
    ) {
        val lateral = if (lateralMag > 0f) rng.nextGaussian().toFloat() * lateralMag else 0f
        val point = Rotation(
            start.yaw + delta.deltaYaw * fraction + perpYaw * lateral,
            start.pitch + delta.deltaPitch * fraction + perpPitch * lateral,
        )
        val segment = prev.angleTo(point)
        waypoints.add(point)
        arriveThresholds.add(max(ARRIVE_MIN_DEG, segment * subMovements.arrive))
    }

    private fun applyIdleDrift(rotation: Rotation): Rotation {
        if (idleDrift <= 0f) return rotation
        val (tY, tP) = sampleTremor(ThreadLocalRandom.current())
        return Rotation(rotation.yaw + tY * idleDrift, rotation.pitch + tP * idleDrift)
    }

    private fun sampleTremor(rng: ThreadLocalRandom, scale: Float = 1f): Pair<Float, Float> {
        if (!tremor.enabled) return 0f to 0f
        val y = noiseTremorYaw.next(rng, tremor.rate) * tremor.yaw.random() * scale
        val p = noiseTremorPitch.next(rng, tremor.rate) * tremor.pitch.random() * scale
        return y to p
    }

    /**
     * Big-turn launch ease-in: returns a sub-1 ω multiplier that ramps from [LAUNCH_FLOOR] up to 1
     * over [BigTurn.smoothLaunch] ticks, so a big turn accelerates in over a couple of ticks instead
     * of taking one hard front-loaded step out of the reaction freeze. 1.0 when not launching.
     */
    private fun launchFactor(): Float {
        if (launchTicksLeft <= 0) return 1f
        val ramp = 1f - launchTicksLeft.toFloat() / (launchTotal + 1)
        launchTicksLeft--
        return LAUNCH_FLOOR + (1f - LAUNCH_FLOOR) * ramp
    }

    /**
     * Per-swing velocity-profile skew as an ω multiplier over swing progress: a positive [swingSkew]
     * raises ω early and lowers it late (front-loaded, early peak), a negative one does the reverse.
     * Progress is how far the swing has closed, so the endpoint is unaffected.
     */
    private fun skewFactor(progress: Float): Float {
        if (swingSkew == 0f) return 1f
        return (1f + swingSkew * (0.5f - progress)).coerceAtLeast(SKEW_MIN_FACTOR)
    }

    /**
     * Mousepad pickup: on a very wide turn a real player runs out of pad mid-swing, dips the speed
     * for a tick or two and re-grips. Triggers once, around mid-swing, only if [pickupArmed].
     * Hitrate-safe — it just delays arrival a hair on huge turns, never moves off the target.
     */
    private fun pickupFactor(progress: Float): Float {
        if (pickupTicksLeft > 0) {
            pickupTicksLeft--
            return bigTurn.pickupSlowdown
        }
        if (pickupArmed && progress >= PICKUP_TRIGGER_PROGRESS) {
            pickupArmed = false
            pickupTicksLeft = (bigTurn.pickupTicks.random() - 1).coerceAtLeast(0)
            return bigTurn.pickupSlowdown
        }
        return 1f
    }

    /**
     * Engagement adaptation: a careful (slightly slower) start to each fight that ramps to full
     * pace over [ENGAGEMENT_RAMP_TICKS]. Hitrate-positive — slower-early is more accurate — and it
     * fully recovers within ~2 s, so sustained fights are unaffected.
     */
    private fun engagementFactor(): Float {
        if (engagementWarmup <= 0f) return 1f
        val t = (engagementTicks.toFloat() / ENGAGEMENT_RAMP_TICKS).coerceIn(0f, 1f)
        return (1f - engagementWarmup) + engagementWarmup * t
    }

    /**
     * Ex-Gaussian reaction time: a uniform draw from [range] (the gaussian-ish bulk) plus an
     * exponential right tail of mean [tailMean] ticks — real reaction times occasionally run long,
     * which a flat uniform never reproduces. The tail draw uses inverse-CDF on a uniform sample.
     */
    private fun exGaussianTicks(range: IntRange, tailMean: Float): Int {
        val bulk = range.random()
        val tail = if (tailMean > 0f) {
            val u = ThreadLocalRandom.current().nextDouble().coerceIn(TAIL_EPS, 1.0)
            (-tailMean * ln(u.toFloat())).roundToInt()
        } else {
            0
        }
        return (bulk + tail).coerceAtLeast(0)
    }

    /** 2/3 power law: lower ω the sharper the recent path curvature (heading change last tick). */
    private fun powerLawFactor(): Float {
        if (!biomech.enabled || biomech.powerLaw <= 0f) return 1f
        return 1f / (1f + biomech.powerLaw * (lastTurnRate / HALF_PI))
    }

    /** Signal-dependent noise scale: small at rest, grows with speed (Harris-Wolpert). */
    private fun signalNoiseScale(): Float {
        if (!biomech.enabled || biomech.signalNoise <= 0f) return 1f
        val speed = hypot(velYaw, velPitch)
        return ((1f - biomech.signalNoise) + biomech.signalNoise * (speed / SIGNAL_REF_SPEED))
            .coerceIn(0f, 2f)
    }

    /** Speed-accuracy: faster swings (higher ω) get proportionally more endpoint scatter. */
    private fun speedErrorScale(): Float {
        if (!biomech.enabled || biomech.speedError <= 0f) return 1f
        return 1f + biomech.speedError * (swingOmega / OMEGA_REF)
    }

    /**
     * Update the transient aim jolt from the player's own velocity change. A counter-strafe or a
     * sudden stop produces a momentary nudge whose magnitude tracks the movement change (sign from
     * value-noise), so aim and WASD are correlated the way a real hand's are. Decays each tick.
     */
    private fun updateMovementJolt(rng: ThreadLocalRandom) {
        if (!movementCoupling.enabled || movementCoupling.strafe <= 0f) {
            moveJoltYaw = 0f
            moveJoltPitch = 0f
            return
        }
        val vel = player.deltaMovement
        val ax = (vel.x - prevPlayerVelX).toFloat()
        val ay = (vel.y - prevPlayerVelY).toFloat()
        val az = (vel.z - prevPlayerVelZ).toFloat()
        prevPlayerVelX = vel.x
        prevPlayerVelY = vel.y
        prevPlayerVelZ = vel.z

        val lateral = hypot(ax, az) * movementCoupling.strafe
        moveJoltYaw = moveJoltYaw * MOVE_JOLT_DECAY + lateral * noiseMoveYaw.next(rng, MOVE_JOLT_RATE)
        moveJoltPitch = moveJoltPitch * MOVE_JOLT_DECAY +
            abs(ay) * movementCoupling.strafe * MOVE_PITCH_SCALE * noiseMovePitch.next(rng, MOVE_JOLT_RATE)
    }

    /**
     * Slow per-session ω multiplier: a warmup ramp (start a touch slower, reach full pace over the
     * first [WARMUP_TICKS]) times a very-low-frequency wander (fatigue/drift). Both are sub-tick
     * trends an ML classifier reads as a consistent, slowly-changing person rather than a bot.
     */
    private fun sessionDrift(rng: ThreadLocalRandom): Float {
        if (!movementCoupling.enabled || movementCoupling.sessionDrift <= 0f) return 1f
        val warm = (WARMUP_FLOOR + (1f - WARMUP_FLOOR) * (sessionTicks.toFloat() / WARMUP_TICKS))
            .coerceAtMost(1f)
        val wander = 1f + movementCoupling.sessionDrift * SESSION_DRIFT_AMP *
            noiseSession.next(rng, SESSION_DRIFT_RATE)
        return warm * wander
    }

    /** Measure the heading change of the velocity vector this tick for the next [powerLawFactor]. */
    private fun updateCurvature() {        val pm = hypot(prevVelYaw, prevVelPitch)
        val cm = hypot(velYaw, velPitch)
        lastTurnRate = if (pm > CURV_EPS && cm > CURV_EPS) {
            val dot = (prevVelYaw * velYaw + prevVelPitch * velPitch) / (pm * cm)
            acos(dot.coerceIn(-1f, 1f))
        } else {
            0f
        }
        prevVelYaw = velYaw
        prevVelPitch = velPitch
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        // Fitts duration at the SLOWEST settings (longest base time) so KillAura over-estimates the
        // journey and is never late. The spring's tail adds a little, so round up generously.
        val delta = currentRotation.rotationDeltaTo(targetRotation)
        val distance = hypot(delta.deltaYaw, delta.deltaPitch).coerceAtLeast(MIN_DISTANCE)
        val index = log2(distance / targetWidth.coerceAtLeast(MIN_WIDTH) + 1f)
        val duration = (baseTime.endInclusive + distanceScaling * index).coerceAtLeast(MIN_DURATION)

        // Budget for the big-turn extras so KillAura over-estimates and is never late: reaction
        // (bulk + ~2σ of the ex-Gaussian tail) plus the mousepad-pickup stall on very wide turns.
        var extra = 0
        if (bigTurn.enabled && distance >= bigTurn.threshold) {
            extra += bigTurn.reactionTicks.last + ceil(bigTurn.reactionTail * TAIL_BUDGET_SIGMA).toInt()
            if (distance >= bigTurn.pickupAngle) extra += bigTurn.pickupTicks.last
        }
        return (ceil(duration * SETTLE_MARGIN).toInt() + extra).coerceAtLeast(1)
    }

    /**
     * 1-D interpolated value noise: holds two random lattice values and smootherstep-interpolates
     * between them, drawing a fresh one each time the phase crosses an integer. Broadband and
     * non-repeating — unlike a pure sine it has no single spectral spike to fingerprint. Output
     * is in roughly [-1, 1].
     */
    private class ValueNoise {
        private var cur = 0.0
        private var nxt = 0.0
        private var frac = 0.0
        private var seeded = false

        fun next(rng: ThreadLocalRandom, step: Float): Float {
            if (!seeded) {
                cur = rng.nextDouble() * 2.0 - 1.0
                nxt = rng.nextDouble() * 2.0 - 1.0
                seeded = true
            }
            frac += step.toDouble()
            while (frac >= 1.0) {
                frac -= 1.0
                cur = nxt
                nxt = rng.nextDouble() * 2.0 - 1.0
            }
            val t = frac
            val s = t * t * t * (t * (t * 6.0 - 15.0) + 10.0) // smootherstep
            return (cur + (nxt - cur) * s).toFloat()
        }
    }

    private companion object {
        const val NEAR_TARGET_EPS = 0.5f
        const val MIN_DISTANCE = 0.001f
        const val MIN_OMEGA = 0.1f
        const val MIN_ZETA = 0.6f
        const val MAX_ZETA = 1.15f
        const val MIN_WIDTH = 0.5f
        const val MIN_DURATION = 1.0f
        // A critically-damped spring settles to ~1 % in ≈ 5/ω ticks, so ω = SETTLE_TICKS / duration.
        const val SETTLE_TICKS = 5.0f
        // calculateTicks over-estimates by this factor to cover the spring's homing tail.
        const val SETTLE_MARGIN = 1.3f
        const val VELOCITY_DECAY = 0.5f
        // Residual fraction of speed left after the brake's full duration (≈ fully stopped).
        const val STOP_RESIDUAL = 0.05f
        const val SUBSTEPS = 3
        const val ARRIVE_MIN_DEG = 1.0f
        const val SCATTER_SHRINK = 0.55f
        // Biomechanics references.
        const val HALF_PI = 1.5707964f
        const val CURV_EPS = 0.001f
        const val SIGNAL_REF_SPEED = 20f
        const val OMEGA_REF = 1.2f
        // Movement coupling.
        const val MOVE_JOLT_DECAY = 0.55f
        const val MOVE_JOLT_RATE = 0.40f
        const val MOVE_PITCH_SCALE = 0.5f
        const val WARMUP_TICKS = 600f
        const val WARMUP_FLOOR = 0.8f
        const val SESSION_DRIFT_AMP = 0.15f
        const val SESSION_DRIFT_RATE = 0.01f
        // Attention.
        const val TICKS_PER_SECOND = 20f
        const val LAG_RESET_ANGLE = 30f
        const val LAG_MIN_FACTOR = 0.1f
        const val BODY_MIN = 0.25f
        const val BODY_MAX = 0.9f
        const val BODY_SWITCH_MIN = 40
        const val BODY_SWITCH_RANGE = 60
        // Big-turn launch ease-in floor (ω multiplier at the very start of the ramp).
        const val LAUNCH_FLOOR = 0.35f
        // Floor for the per-swing velocity-skew ω multiplier (keeps it positive).
        const val SKEW_MIN_FACTOR = 0.2f
        // Swing progress at which the mousepad pickup dip fires (mid-swing).
        const val PICKUP_TRIGGER_PROGRESS = 0.45f
        // Engagement warmup ramp length (~2 s at 20 tps).
        const val ENGAGEMENT_RAMP_TICKS = 40f
        // Floor for the ex-Gaussian tail's uniform sample (avoids -ln(0)).
        const val TAIL_EPS = 0.05
        // calculateTicks budgets this many tail-means for the reaction so it's rarely under-time.
        const val TAIL_BUDGET_SIGMA = 2f
    }
}
