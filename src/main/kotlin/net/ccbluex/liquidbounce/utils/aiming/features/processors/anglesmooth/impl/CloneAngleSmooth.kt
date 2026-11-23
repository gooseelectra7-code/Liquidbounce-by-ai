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
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.clone.CloneCombatSignals
import net.ccbluex.liquidbounce.deeplearn.clone.CloneDriftRescue
import net.ccbluex.liquidbounce.deeplearn.clone.CloneModel
import net.ccbluex.liquidbounce.deeplearn.clone.CloneModels
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.lang.translation
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Aims by sampling from a model trained on the player's own recorded aim.
 *
 * Every other smoother here builds a trajectory out of formulas — Fitts's law, pink noise, spring
 * damping — which reproduces how human aim *looks* but not how it is *distributed*. A classifier
 * does not care what the curve looks like; it cares whether the step sizes, the pauses and the
 * corrections come from the same distribution as a person's. This mode has no shape of its own:
 * it draws each tick from what the player actually did in comparable situations.
 *
 * Two consequences fall out of that and are load-bearing:
 *
 * - The output goes onto the sensitivity grid *exactly*, as an integer number of mouse counts,
 *   and is marked normalized so [Rotation.normalize] leaves the replayed steps alone.
 *   Float rounding and physical pitch boundaries can still leave fractional emitted counts.
 * - The post-processors are suppressed. Fail, ShortStop and Jitter add hand-made deviations on top,
 *   which is precisely the kind of signature the model exists to avoid — and the player's own
 *   misses and pauses are already in the sampled distribution.
 */
open class CloneAngleSmooth(
    parent: ModeValueGroup<*>,
    name: String = "Clone",
    aliases: List<String> = arrayListOf("AI", "Minarai"),
    defaultTemperature: Float = 1f,
    defaultDriveClicks: Boolean = true
) : AngleSmooth(name, parent, aliases) {

    private val modelChoice = modes<CloneModels.ModelChoice>("Model", 0) { group ->
        CloneModels.register(group)
    }

    /**
     * Scales both the spread of the sampled step and the sharpness of the mixture choice. `1` is
     * the player as recorded; below that the clone becomes a cleaner, more consistent version of
     * them, above that a sloppier one. It reaches the aim only — see `CloneModel.sampleAttack`.
     *
     * Readable from outside so `.clone eval` can score the clone at the setting it is actually
     * being run at instead of at one nobody selected.
     */
    val temperature by float("Temperature", defaultTemperature, 0f..1.5f)

    /**
     * Temporal correlation of the sampled noise, 0 to 0.9. Default 0.
     *
     * Carries the previous tick's latent Gaussian into the next by `z = ar·zPrev + sqrt(1 - ar²)·ε`,
     * which leaves the per-tick step-size distribution untouched (discriminator marginals stay
     * matched) while smoothing the noise in time toward a human's ~0.585 lag-1 autocorrelation.
     *
     * **Superseded by the v7 step-memory reparam and default OFF because of it.** v7 already carries
     * coherence in the *mean* (`step = lambda·prevStep + delta`), so its momentum matches the player
     * at ar = 0 (measured 0.58 vs 0.57). ar then stacks a *second* memory onto the noise, and
     * through the `lambda·prevStep` feedback the two compound into the runaway 360 spins v7's
     * damping exists to prevent (ar = 0.45 measured momentum 0.71 and spun in play). Leave this at 0
     * for a v7 model; it is only useful on a v5-schema model whose mean has no memory of its own.
     * Read by `.clone eval` so a run is scored as deployed.
     */
    val noiseCorrelation by float("NoiseCorrelation", 0f, 0f..0.9f)

    /**
     * Whether the clone also decides when to click.
     *
     * With this off the aim is the player's but the clicks are still the clicker's own cadence,
     * and the *pairing* of the two — where the crosshair was when the swing went out — stays
     * bot-shaped no matter how good the aim is. That pairing is one of the easiest things for a
     * classifier to check, which is why this defaults on.
     */
    private val driveClicks by boolean("DriveClicks", defaultDriveClicks)

    /**
     * Which point the model is told to measure its error against.
     *
     * The recorder labels every tick against the target's *box centre*, so that is the only anchor
     * the model has ever been asked to close a residual on. What it gets here is whatever the aura
     * decided to aim at, which runs through PointTracker and its processors and can sit
     * most of a hitbox away from the centre — a systematic offset in the two features the whole
     * trajectory is conditioned on.
     *
     * Neither choice is free. [AimAnchor.PROVIDED] keeps the aura's pick, which is the only one
     * that can be trusted when a wall leaves a single corner of the hitbox exposed;
     * [AimAnchor.HITBOX_CENTRE] restores the geometry the model trained on and bypasses
     * PointTracker's own humanization, which is a second hand-written signature layered under a
     * mode whose entire purpose is not having one. Which wins is a measurement rather than an
     * opinion — `.clone eval` takes the anchor as an argument so both can be scored on the same
     * recording.
     */
    private val aimAnchor by enumChoice("AimAnchor", AimAnchor.PROVIDED)

    enum class AimAnchor(override val tag: String) : Tagged {
        PROVIDED("Provided"),
        HITBOX_CENTRE("HitboxCentre")
    }

    /**
     * Wipes the recurrent memory when the aim has made no progress for longer than an engaged
     * player ever sustains — see [CloneDriftRescue] for the thresholds and why a state reset is
     * the one intervention that puts nothing hand-written on the wire.
     */
    private val driftRescue by boolean("DriftRescue", true)

    /**
     * Writes one line to `latest.log` per [AIM_LOG_WINDOW] engaged ticks: how often the rotation
     * going on the wire this tick was on the target's box, by range, split into the first
     * [AIM_LOG_COLD_TICKS] ticks after the model's memory was wiped and the rest, and what wiped
     * it. `.clone eval` scores recorded fights with a single cold start each; this asks the same
     * question of a live fight, in the same range buckets, so the two can be read side by side.
     * Logging only - nothing it counts changes a rotation or a packet.
     */
    private val aimLog by boolean("AimLog", false)

    /**
     * Used for the reset path and whenever no model is available.
     *
     * Explicit turn speeds because the default is 180°/tick — an instant snap. Selecting Clone
     * without having trained anything used to produce exactly that, with the humanization
     * processors suppressed on top of it.
     */
    private val fallback = LinearAngleSmooth(
        parent,
        horizontalTurnSpeed = 18f..32f,
        verticalTurnSpeed = 12f..22f
    )

    /**
     * Only while a model is actually driving. With none selected — or with one that keeps
     * failing, see [reportStepFailure] — the output comes from the fallback, and stripping
     * Fail/ShortStop/Jitter off a plain linear turn would leave the least human option in the
     * client rather than the most.
     */
    override val suppressesPostProcessors: Boolean
        get() = modelChoice.activeMode.model != null && stepFailures < STEP_FAILURE_LIMIT

    /** The passed point may be the only visible part of a partly occluded hitbox; do not replace it. */
    override val derivesTargetFromEntity: Boolean get() = false

    private val features = FloatArray(FeatureSchema.FEATURES)

    private var lastTick = -1
    private var lastEntityId: Int? = null
    private val previousAction = floatArrayOf(0f, 0f)
    private var previousTarget: TargetGeometry? = null
    private var sessionModel: CloneModel? = null
    private var session: CloneModel.Session? = null

    /**
     * The aim step sampled last tick, grid-quantized, waiting to be applied this tick.
     *
     * In the recording, the movement paired with the situation at tick head T happens during the
     * frames *after* T and reaches the wire in the packet of tick T+1. Applying a fresh sample in
     * the same tick it was drawn put it on the wire one tick early — a clone whose every learned
     * reaction ran 50 ms faster than the person it was cloned from. Holding one step restores the
     * training pairing exactly.
     *
     * The attack half of the sample is deliberately NOT delayed with it. In the data, a label row
     * pairs a click *processed at* tick head T with the movement that follows it: vanilla handles
     * the click at the head of tick T (attack packet, then that tick's movement packet carrying
     * the very rotation the row conditioned on), and the row's dx/dy happen in the frames after —
     * reaching the wire one packet later. On the wire a human click therefore reads
     * `ATTACK → mv(conditioning rotation) → mv(joint action)`. Executing the sampled attack on the
     * sampling tick reproduces that order exactly; delaying it alongside the action produced
     * `mv(conditioning) → ATTACK → mv(action)` — every clone click one packet late relative to the
     * joint the head was trained (and is explicitly conditioned) on, which an attack-window-centred
     * detector reads directly off `ticks_to_attack`.
     */
    private var pendingStep: CloneModel.Step? = null

    private var attackTick = -1
    private var attackDecision = false

    /** Consecutive model-session failures; see [reportStepFailure]. */
    private var stepFailures = 0

    private var outOfCoverage = 0
    private var coverageWindowTicks = 0
    private var coverageWindowOut = 0

    private val rescue = CloneDriftRescue()
    private var driftRescues = 0

    private val aimStats = AimStats()

    /** Engaged ticks since the recurrent memory was last wiped, for [aimLog]. */
    private var sinceWipe = 0

    /** The entity of the last engaged tick and the tick it was lost on, for [aimLog]'s loss causes. */
    private var engagedEntity: Entity? = null
    private var lostAtTick = -1

    private class TargetGeometry(val yaw: Float, val pitch: Float, val distance: Float)

    /** One [aimLog] window; see there. */
    private class AimStats {
        var ticks = 0
        var onBox = 0
        val rangeTicks = IntArray(AIM_LOG_RANGES)
        val rangeOnBox = IntArray(AIM_LOG_RANGES)
        var coldTicks = 0
        var coldOnBox = 0
        var lostTarget = 0
        var skippedTick = 0
        var rescues = 0
        var switches = 0

        /** What the lost target was doing when the request lapsed: dead, a screen, eating, or how far. */
        var lostDead = 0
        var lostScreen = 0
        var lostUsing = 0
        val lostRange = IntArray(LOSS_RANGES.size + 1)

        /** Ticks until the aim had a target again. */
        val gaps = IntArray(GAP_BUCKETS.size + 1)

        fun recordLoss(entity: Entity?, screenOpen: Boolean, usingItem: Boolean, boxedDistance: Double) {
            lostTarget++
            when {
                entity == null || !entity.isAlive || entity.isRemoved -> lostDead++
                screenOpen -> lostScreen++
                usingItem -> lostUsing++
                else -> lostRange[LOSS_RANGES.indexOfFirst { boxedDistance < it }.let { if (it < 0) LOSS_RANGES.size else it }]++
            }
        }

        fun recordGap(ticks: Int) {
            gaps[GAP_BUCKETS.indexOfFirst { ticks <= it }.let { if (it < 0) GAP_BUCKETS.size else it }]++
        }

        fun record(distance: Float, hit: Boolean, cold: Boolean) {
            val range = distance.toInt().coerceIn(0, AIM_LOG_RANGES - 1)
            ticks++
            rangeTicks[range]++
            if (cold) coldTicks++
            if (hit) {
                onBox++
                rangeOnBox[range]++
                if (cold) coldOnBox++
            }
        }

        fun describe(): String {
            fun share(hit: Int, of: Int) = if (of == 0) "-" else "%.1f%%".format(hit * 100f / of)
            val ranges = (0 until AIM_LOG_RANGES).joinToString(" ") { range ->
                val label = if (range == AIM_LOG_RANGES - 1) "$range+" else "$range-${range + 1}"
                "$label ${share(rangeOnBox[range], rangeTicks[range])} (${rangeTicks[range]})"
            }
            return "Clone aim over $ticks engaged ticks: on box ${share(onBox, ticks)} | by range $ranges | " +
                "first $AIM_LOG_COLD_TICKS ticks after a wipe ${share(coldOnBox, coldTicks)} of $coldTicks, " +
                "after that ${share(onBox - coldOnBox, ticks - coldTicks)} | wipes: $lostTarget target lost, " +
                "$skippedTick skipped tick, $rescues drift rescue; $switches target switches | " +
                "lost while: dead $lostDead, screen $lostScreen, using item $lostUsing, boxed distance " +
                (LOSS_RANGES.map { "<${it.toInt()}" } + ">=${LOSS_RANGES.last().toInt()}").zip(lostRange.toList())
                    .joinToString(" ") { (label, count) -> "$label $count" } +
                " | back after ticks " +
                (GAP_BUCKETS.map { "<=$it" } + ">${GAP_BUCKETS.last()}").zip(gaps.toList())
                    .joinToString(" ") { (label, count) -> "$label $count" }
        }

        fun clear() {
            ticks = 0
            onBox = 0
            rangeTicks.fill(0)
            rangeOnBox.fill(0)
            coldTicks = 0
            coldOnBox = 0
            lostTarget = 0
            skippedTick = 0
            rescues = 0
            switches = 0
            lostDead = 0
            lostScreen = 0
            lostUsing = 0
            lostRange.fill(0)
            gaps.fill(0)
        }
    }

    /**
     * The model's click decision, or `null` when it has not run this tick and therefore has no
     * opinion. Callers fall back to their own scheduling on `null` rather than freezing.
     */
    val attackDecisionThisTick: Boolean?
        get() = if (driveClicks && attackTick == player.tickCount) attackDecision else null

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val model = modelChoice.activeMode.model
        if (!DeepLearningEngine.isInitialized || model == null) {
            clearSession()
            notifyUnavailable(model == null)
            return fallback.process(rotationTarget, currentRotation, targetRotation)
        }
        bindSession(model)

        val entity = rotationTarget.entity

        // With no entity this is the reset, where the silent rotation is handed back to the
        // player's real one. The fallback drives it, not the model: every target-derived feature
        // would be a fabrication, and the model's output is a sample with real probability mass on
        // exactly zero, so it can park a couple of degrees short forever — RotationManager only
        // releases once the residual reaches zero. The recorded disengage behaviour is trained but
        // deliberately not deployed here; convergence outranks style on the hand-off.
        //
        // Training sequences now end on this exact boundary, so runtime does too. Preserving hidden
        // state across a regime the model never consumes only makes the next cold start depend on
        // an unknown number of skipped ticks.
        if (entity == null) {
            val result = fallback.process(rotationTarget, currentRotation, targetRotation)

            if (lastTick >= 0) {
                val lost = engagedEntity
                aimStats.recordLoss(
                    lost,
                    screenOpen = mc.screen != null,
                    usingItem = player.isUsingItem,
                    boxedDistance = lost?.let { sqrt(it.squaredBoxedDistanceTo(player)) } ?: 0.0
                )
                lostAtTick = player.tickCount
            }
            engagedEntity = null
            sinceWipe = 0
            session?.reset()
            rescue.reset()
            pendingStep = null
            previousTarget = null
            lastEntityId = null
            lastTick = -1
            attackTick = -1

            previousAction.fill(0f)

            return result
        }

        val tick = player.tickCount
        if (lostAtTick >= 0) {
            aimStats.recordGap(tick - lostAtTick)
            lostAtTick = -1
        }
        engagedEntity = entity

        // A respawn or dimension change builds a fresh LocalPlayer whose tickCount restarts near
        // zero, so the gap test below goes negative and never fires. Detect it explicitly.
        if (tick < lastTick) {
            lastTick = -1
            sinceWipe = 0
            session?.reset()
            rescue.reset()
            pendingStep = null
            previousTarget = null
            previousAction.fill(0f)
        }

        // A skipped invocation means the recurrent stream was broken even if this method never saw
        // a target-less reset call. Start cold, matching a new recorded segment.
        if (lastTick >= 0 && tick - lastTick > 1) {
            aimStats.skippedTick++
            sinceWipe = 0
            session?.reset()
            rescue.reset()
            previousAction[0] = 0f
            previousAction[1] = 0f
            previousTarget = null
            pendingStep = null
        }
        lastTick = tick

        // The target's angular velocity is a difference between two consecutive positions *of the
        // same target*. Across a switch there is no such quantity, so the reference is dropped
        // even though the model's memory is not.
        val targetChanged = lastEntityId != null && entity.id != lastEntityId
        if (targetChanged) {
            previousTarget = null
            rescue.reset()
            aimStats.switches++
        }
        val previousTickEntityId = lastEntityId
        lastEntityId = entity.id

        // Apply the step sampled *last* tick — see [pendingStep] for why. What the history sees
        // must be what actually happened to the rotation, so the pitch delta is measured after
        // the clamp rather than assumed.
        val applied = pendingStep
        pendingStep = null
        val appliedYaw = applied?.deltaYaw ?: 0f
        val newYaw = currentRotation.yaw + appliedYaw
        val newPitch = (currentRotation.pitch + (applied?.deltaPitch ?: 0f)).coerceIn(-90f, 90f)
        previousAction[0] = appliedYaw
        previousAction[1] = newPitch - currentRotation.pitch
        val result = Rotation(newYaw, newPitch, isNormalized = true)

        // The situation at this tick head, described with the rotation that is going on the wire
        // this tick — the recorder captured `player.rotation` at exactly this phase.
        val target = when (aimAnchor) {
            AimAnchor.PROVIDED -> targetRotation
            AimAnchor.HITBOX_CENTRE ->
                Rotation.lookingAt(point = entity.box.center, from = player.eyePosition)
        }
        val previous = previousTarget
        val distance = player.eyePosition.distanceTo(entity.box.center).toFloat()
        val onBox = isInCrosshair(entity, result, distance)
        if (aimLog) {
            aimStats.record(distance, onBox, sinceWipe < AIM_LOG_COLD_TICKS)
            if (aimStats.ticks >= AIM_LOG_WINDOW) {
                logger.info(aimStats.describe())
                aimStats.clear()
            }
        }
        sinceWipe++

        // Movement is resolved in the frame of the rotation on the wire. With movement correction
        // on, the player's velocity is built from the silent rotation (RotationManager's strafe
        // handler), and in the recording the wire rotation and the camera were the same thing —
        // the camera frame here would rotate forward/strafe by however far the real look happens
        // to sit from the silent aim.
        val movement = FeatureSchema.toLocalMovement(
            player.x - player.lastPos.x,
            player.z - player.lastPos.z,
            newYaw
        )

        FeatureSchema.fillFromGeometry(
            out = features,
            prevDxDeg = previousAction[0],
            prevDyDeg = previousAction[1],
            ownYaw = newYaw,
            ownPitch = newPitch,
            moveForward = movement[0],
            moveStrafe = movement[1],
            // The real wire, not the model's intention: KillAura's gates can veto a decision, and
            // a history claiming an attack that sent no packet means nothing the model trained on.
            // Filtered to the tracked target the way the recorder filtered its label: the shared
            // signal counts *every* surviving ServerboundAttackPacket, and AutoCrystal breaks
            // crystals with exactly that packet several times a second — without the id check a
            // crystal fight pins this column at "you just swung" on ticks the aura never did,
            // an input regime no recording contains.
            attackedPrev = CloneCombatSignals.attackedLastTick &&
                CloneCombatSignals.attackedEntityIdLastTick == (previousTickEntityId ?: entity.id),
            hasTarget = true,
            targetYaw = target.yaw,
            targetPitch = target.pitch,
            distance = distance,
            hadPrevTarget = previous != null,
            prevTargetYaw = previous?.yaw ?: 0f,
            prevTargetPitch = previous?.pitch ?: 0f,
            prevDistance = previous?.distance ?: 0f,
            hurtTime = (entity as? LivingEntity)?.hurtTime ?: 0,
            onTarget = onBox,
            cooldown = player.getAttackStrengthScale(0f),
            sprinting = player.isSprinting,
            sneaking = player.isShiftKeyDown,
            onGround = player.onGround(),
            usingItem = player.isUsingItem,
            knockedBack = CloneCombatSignals.receivedVelocity,
            tookDamage = CloneCombatSignals.tookDamage,
            targetChanged = targetChanged,
            gcd = RotationUtil.gcd.toFloat(),
            targetWidth = (entity.box.maxX - entity.box.minX).toFloat(),
            targetHeight = (entity.box.maxY - entity.box.minY).toFloat()
        )
        previousTarget = TargetGeometry(target.yaw, target.pitch, distance)

        // Nothing is done about being outside what the profile covers — silently swapping in a
        // hand-written smoother would put its signature back on the wire. It is surfaced instead,
        // because "the clone spends half the fight extrapolating" is a fact about the recording
        // that the user can act on and the model cannot.
        val residual = hypot(features[FeatureSchema.F_DELTA_YAW], features[FeatureSchema.F_DELTA_PITCH])
        trackCoverage(residual > model.metadata.coverageAngle || distance > model.metadata.coverageDistance)

        // A sampled policy can wander into a mode of its own state that generates no approach —
        // the drift the recording's statistics say an engaged hand never sustains. The rescue only
        // clears the recurrent memory: the features stay truthful and the next sample comes from
        // the cold state the model was trained to start engagements from, so nothing hand-written
        // reaches the wire. The same rescue runs inside `.clone eval`'s rollout, so the report
        // scores the policy as deployed.
        if (driftRescue && rescue.shouldReset(residual)) {
            session?.reset()
            driftRescues++
            aimStats.rescues++
            sinceWipe = 0
        }

        // Sample the step for the *next* tick. `.clone reload` can close this model from another
        // thread between ticks, so a failure falls back for the tick — but persistent failure is
        // reported rather than swallowed, see [reportStepFailure].
        val step = runCatching {
            checkNotNull(session).step(
                features,
                RotationUtil.gcd,
                temperature,
                ThreadLocalRandom.current(),
                noiseCorrelation
            )
        }
            .getOrElse { error ->
                reportStepFailure(error)
                session?.reset()
                rescue.reset()
                pendingStep = null
                previousTarget = null
                // [result] contains the already sampled step that was actually applied this tick;
                // returning a fresh fallback here used to make the history lie about that action.
                return result
            }
        stepFailures = 0

        // CloneModel performs the projection itself, so its sampling distribution and the action
        // fed back into the next row agree on the exact current sensitivity grid. The action waits
        // one tick; the click executes now — see [pendingStep] for why the two halves split.
        pendingStep = step
        attackDecision = step.attack
        attackTick = tick

        ModuleDebug.debugParameter(this, "DeltaYaw", previousAction[0])
        ModuleDebug.debugParameter(this, "DeltaPitch", previousAction[1])
        ModuleDebug.debugParameter(this, "Attack", attackDecision)
        ModuleDebug.debugParameter(this, "OutOfCoverage", outOfCoverage)
        ModuleDebug.debugParameter(this, "DriftRescues", driftRescues)

        return result
    }

    /** Drops any state and delayed action that belongs to a different loaded model instance. */
    private fun bindSession(model: CloneModel) {
        if (sessionModel === model && session != null) {
            return
        }

        runCatching { session?.close() }
        sessionModel = model
        session = model.newSession()
        lastTick = -1
        lastEntityId = null
        previousAction.fill(0f)
        previousTarget = null
        pendingStep = null
        attackTick = -1
        stepFailures = 0
        rescue.reset()
    }

    private fun clearSession() {
        runCatching { session?.close() }
        session = null
        sessionModel = null
        lastTick = -1
        lastEntityId = null
        previousAction.fill(0f)
        previousTarget = null
        pendingStep = null
        attackTick = -1
        rescue.reset()
    }

    /**
     * Counts how often the fight sits outside the geometry the profile covers, and tells the user
     * when it is a habit rather than a moment — a fact about the recording only they can fix.
     */
    private fun trackCoverage(outside: Boolean) {
        if (outside) {
            outOfCoverage++
            coverageWindowOut++
        }

        if (++coverageWindowTicks < COVERAGE_WINDOW) {
            return
        }

        val share = coverageWindowOut.toFloat() / coverageWindowTicks
        coverageWindowTicks = 0
        coverageWindowOut = 0

        if (share > COVERAGE_WARN_SHARE && coverageChronometer.hasElapsed(COVERAGE_WARN_INTERVAL)) {
            coverageChronometer.reset()
            chat(
                markAsError(
                    ("Clone: %.0f%% of the last minute of combat sat outside the profile's " +
                        "recorded geometry — the model is extrapolating. Record more fights " +
                        "like this one and retrain.").format(share * 100f)
                )
            )
        }
    }

    /**
     * A failing model must not degrade silently: with the post-processors suppressed, a permanent
     * fallback would run an un-humanized linear aim for the rest of the session with nobody told.
     * The first failure is logged with its cause; past [STEP_FAILURE_LIMIT] in a row the user is
     * told and [suppressesPostProcessors] releases the humanization processors back to the
     * fallback.
     */
    private fun reportStepFailure(error: Throwable) {
        stepFailures++

        if (stepFailures == 1) {
            logger.error("Clone step failed, falling back for this tick.", error)
        }
        if (stepFailures == STEP_FAILURE_LIMIT) {
            chat(
                markAsError(
                    "Clone model keeps failing — running the ${fallback.name} fallback with " +
                        "humanization processors re-enabled. See the log for the cause."
                )
            )
        }
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        val model = modelChoice.activeMode.model ?: return fallback.calculateTicks(
            currentRotation,
            targetRotation
        )

        val angle = currentRotation.angleTo(targetRotation)
        val speed = model.metadata.approachSpeedFor(angle)
        return ceil(angle / speed).toInt().coerceIn(1, MAX_TICKS)
    }

    /**
     * Whether the crosshair is actually on the hitbox, by the same test the recorder applied when it
     * wrote the training label — `ModuleCloneRecorder.isInCrosshair`, down to the range margin.
     *
     * It has to be the same test and not an angular threshold. A hitbox is far more forgiving
     * vertically than horizontally (at three blocks a player's box admits about ±6° of yaw but ±18°
     * of pitch), so a single angle cannot stand in for it at any threshold: 3° was true on 2 % of the
     * recorded combat ticks where the raytrace was true on 44 %. Feeding the model a column that is
     * off almost always, having trained it on one that is on half the time, silences whatever the
     * model learned to condition on it — including when the player swings.
     */
    private fun isInCrosshair(entity: Entity, rotation: Rotation, distance: Float) =
        findEntityInCrosshair(
            range = distance.toDouble() + CROSSHAIR_RANGE_MARGIN,
            rotation = rotation
        ) { it === entity } != null

    private fun notifyUnavailable(missingModel: Boolean) {
        if (!notificationChronometer.hasElapsed(NOTIFICATION_INTERVAL)) {
            return
        }
        notificationChronometer.reset()

        if (missingModel) {
            chat(markAsError(translation("liquidbounce.rotationSystem.angleSmooth.clone.noModel")))
        } else {
            chat(markAsError(translation("liquidbounce.unsupportedDeepLearning")))
        }
        chat(
            markAsError(
                translation("liquidbounce.rotationSystem.angleSmooth.clone.fallback", fallback.name)
            )
        )
    }

    companion object {
        private val logger = clientLogger("AI/CloneSmooth")

        private const val NOTIFICATION_INTERVAL = 5000L

        /** Engaged ticks per [aimLog] line: ten seconds of fighting. */
        private const val AIM_LOG_WINDOW = 200

        /** What [aimLog] counts as "just after a wipe": half a second of fresh memory. */
        private const val AIM_LOG_COLD_TICKS = 10

        /** Range buckets of [aimLog], a block each from the eyes to the box centre; the last is open. */
        private const val AIM_LOG_RANGES = 5

        /** Boxed-distance edges (blocks) [aimLog] sorts losses into: KillAura's reach is 3. */
        private val LOSS_RANGES = listOf(3.0, 4.0, 6.0)

        /** Gap lengths (ticks) [aimLog] sorts re-engagements into. */
        private val GAP_BUCKETS = listOf(2, 5, 20)
        private const val MAX_TICKS = 30

        /** Slack past the box centre so the ray reaches the near face. Matches the recorder's. */
        private const val CROSSHAIR_RANGE_MARGIN = 1.0

        /** Consecutive failures after which the degradation is announced and de-suppressed. */
        private const val STEP_FAILURE_LIMIT = 20

        /** One minute of combat ticks per coverage measurement. */
        private const val COVERAGE_WINDOW = 1200
        private const val COVERAGE_WARN_SHARE = 0.3f
        private const val COVERAGE_WARN_INTERVAL = 300_000L

        // Knockback used to be inferred here from horizontal speed, which ordinary sprint-jumping
        // exceeds — so a column that was near-always zero in training was near-always one in
        // play. Both sides now read CloneCombatSignals; the attack history flag joined it for the
        // same reason.

        private val notificationChronometer = Chronometer()
        private val coverageChronometer = Chronometer()
    }
}
