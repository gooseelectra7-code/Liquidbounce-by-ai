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
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * "Shard" — javelin's `tech.javelin.features.module.impl.combat.attackaura.rotation.ShardRotation`,
 * supplied inline by the user (Yarn mappings) and ported as written (2026-09-25). It REPLACED
 * `SlothAngleSmooth` in the same change; "Sloth" is kept as an alias so a saved config that had it
 * selected comes up on this mode instead of falling back to the first one.
 *
 * ## What one step does
 *
 *  - **An adaptive fraction of the residual.** The step is `residual · adj` per axis, `adj`
 *    starting at 0.5. While the look ray hits a slightly shrunken target box (or the eyes are
 *    inside it) `adj` bleeds down by `U(0.005, 0.025)` a step; off the box it climbs by
 *    `U(0.001, 0.012)` (yaw and pitch alike, `U(0.001, 0.008)` / `U(0.001, 0.01)` while WE glide).
 *    So a locked aim slows until it drifts off the box, then speeds up again.
 *  - **The box the look is tested against** is the target's own box with 5 % cut off each
 *    horizontal side and 10 % off the top; while the TARGET glides the factors are -0.5, which
 *    GROWS it by 25 % a side and 50 % of its height upwards.
 *  - **Point noise** of ±0.04 / ±0.03 / ±0.04 blocks on the aim point (not on the gliding-target
 *    lead, which the source leaves clean), then an angular jitter of ±0.35° on each axis of the
 *    target rotation.
 *  - **A flick every sixth attack**: for two steps the target rotation is offset by ±10–22° yaw and
 *    ±8° pitch (pitch clamped to ±89°), so the hand throws off the target and comes back.
 *  - **GCD truncation**: each axis' step is cut to a whole number of mouse counts, toward zero, the
 *    way the source does it (`finalYaw -= (finalYaw - lastYaw) % gcd`). Hence
 *    [suppressesPostProcessors] and `isNormalized = true`: Fail/ShortStop/Jitter would change the
 *    truncated step; normalization leaves it alone.
 *
 * The step runs once per TICK. INFERRED, not read: javelin's aura that calls it was not supplied.
 * The premises are the source's own names (`flickTicks` is decremented once per call) and the
 * `RotationHandler.update(..., 1, 1, ...)` it ends with, a request that has to be renewed every
 * call. Unlike Sloth, nothing in the numbers says "per render frame": a 0.5 fraction at frame rate
 * would be a snap.
 *
 * ## What is not the source, and why
 *
 * The source leans on six javelin classes that were never supplied and are not on this machine (the
 * user's pointer, `Stal_DLC_1.21.11_Fabric`, is a different client and has none of them; searched
 * 2026-09-25). Each is replaced below, and each replacement is a DEVIATION:
 *
 *  - `MultipointUtils.getNearestPoint(entity, 0.05)` / `getPointWM` / `getLeashPos`: the aim point is
 *    the one the caller has already resolved and handed in as `targetRotation`. For KillAura that is
 *    `PointTracker` in its default `Optimal` mode — the projected box point nearest to the eyes — ray
 *    traced for visibility and range, which is the role the name `getNearestPoint` describes. The
 *    point is recovered in world space by clipping that rotation's ray against the target's box, so
 *    the source's point noise can be applied to it (see [callerPoint]).
 *  - `PredictUtils.realPredict(entity)` (the gliding target's lead): the target's last position
 *    delta times [ElytraPredict.ticks], from the centre of its box — the substitute Sloth used.
 *  - `AuraTransition.handleTransitions()` / `.process(...)`: unknown, dropped (identity). The target
 *    switch it presumably smoothed is instead a full [reset] — GUESS. The source's `reset()` is
 *    called by an aura that was not supplied; here it runs on a target switch, on any tick the
 *    requests were not continuous (another module held the aim, rotating was not allowed, the aura
 *    stood down), and on the targetless hand-back. The source leaves its state alone on a call with
 *    no entity.
 *  - **After a reset the step does NOT restart from the camera.** The source re-reads
 *    `lastYaw`/`lastPitch` from `mc.player` after `reset()`; here the step always starts from
 *    `currentRotation`, i.e. what `RotationManager` has on the wire (including the player's own mouse
 *    under `CHANGE_LOOK`). Restarting from the camera in a silent rotation would put a jump of
 *    however far apart the two are into a single packet.
 *  - `MouseGCD.getGCDValue()`: [RotationUtil.gcd].
 *  - `RotationHandler.update(rotation, 360, 360, 40, 35, 1, 1, showVisual)`: this returns a rotation
 *    and `RotationManager` decides what reaches the wire and the camera (`MovementCorrection`), so
 *    `showVisual` has no counterpart. Reading 360/360 as the aim speed cap (a no-op) and 40/35 as the
 *    yaw/pitch return speed is a GUESS from the argument order; it sets [RETURN_YAW_SPEED] /
 *    [RETURN_PITCH_SPEED] of the hand-back and nothing else.
 *  - `mc.player.getRotationVec(1.0F)` for the look test is the rotation this mode is driving
 *    (`currentRotation`), the same choice Sloth made: in a silent rotation the camera is not what
 *    the step steers, and an `adj` adapting to where the camera points would adapt to nothing.
 *  - `onAttack()` was called by javelin's aura. Here it is an outgoing [ServerboundAttackPacket]
 *    whose entity is the tracked target, read at [ATTACK_COUNT_PRIORITY], one step ABOVE
 *    `FINAL_DECISION`: an attack a module dropped earlier in the chain does not count, but one that
 *    `BlinkManager` (at `FINAL_DECISION`) cancels only to queue it and send it later does. Read any
 *    later and every attack held by Blink/FakeLag would be invisible, because the queue is flushed
 *    through `sendPacketSilently`, which fires no `PacketEvent`.
 *  - Pitch, both ends: the jittered target pitch is clamped to ±90° and the pitch difference is NOT
 *    run through `wrapDegrees`. The source does neither. Pitch is not circular, and with the current
 *    pitch at -90 and the target at +90 the source's `wrapDegrees(180) = -180` (`Mth.java:217-218`) steps to
 *    `-90 - 180 · adj`, a pitch vanilla can never hold (`Entity.turn` clamps it, `Entity.java:499`).
 *    For every other difference the two are identical.
 *  - **The resulting pitch is clamped to ±90° too, and that clamp is LOAD-BEARING.** Both ends in
 *    ±90 do NOT keep the result there in Float: `90 - c` rounds up by 2^-17 once `|90 - c| >= 128`,
 *    and when the grid truncation keeps that whole difference `c + step` lands on 90.0000076
 *    (reproduced: c = -89.40001678, sensitivity 0.5, adj 1 -> 90.00000762939453). Grim's
 *    `BadPacketsD` (LIVE, `badpackets/BadPacketsD.java:12`, `:26-34`) flags AND cancels any rotation
 *    packet with `pitch > 90`, and at that pitch a sub-count residual truncates to zero, so it would
 *    repeat on every rotation packet. The clamp moves the step off the grid by ~1e-5° at the pole
 *    only, which is what vanilla's own clamp at `Entity.java:499` does to a real mouse there.
 *
 * ## Hand-back
 *
 * With no entity (`RotationTarget.entity == null`, which is how the pipeline says "give the rotation
 * back to the player") the step is NOT this algorithm: a fraction of the residual never lands
 * exactly, and `RotationManager` releases the override only once it does. That path is a linear,
 * grid-truncated return at [RETURN_YAW_SPEED] / [RETURN_PITCH_SPEED] with a guaranteed one-count
 * step, the same shape Sloth had.
 *
 * ## Non-aura callers
 *
 * The elytra chase modules (`ModuleSpearElytraTarget`, `testelytratarget/drive/WingDriver`) hand in
 * the target entity with a STEERING rotation. With [ElytraPredict] on and the target gliding, that
 * rotation is replaced by the lead aim at the target — pure pursuit instead of their guidance — the
 * same thing Sloth's `Predict` did. Select another smoother for those modules, or turn
 * [ElytraPredict] off there.
 *
 * ## Honest scope
 *
 * NOT playtested. Nothing is fitted to a recording; the jitter and the flick are uniform draws on a
 * fixed period (every sixth attack), which a sequence classifier can lock onto. The flick is a real
 * two-step look-away: KillAura only attacks when the rotation hits, so it skips about two ticks of
 * attacks every six hits, as the source does.
 */
class ShardAngleSmooth(parent: ModeValueGroup<*>) : AngleSmooth("Shard", parent, aliases = listOf("Sloth")) {

    /** Every emitted step is a whole number of counts; Fail/ShortStop/Jitter would push it off. */
    override val suppressesPostProcessors: Boolean get() = true

    /** The source's `elytraPredict` argument, with our lead in place of `PredictUtils.realPredict`. */
    private inner class ElytraPredict : ToggleableValueGroup(this, "ElytraPredict", true) {
        /** Ticks of the target's last position delta added to its box centre while it glides. */
        val ticks by float("Ticks", 1f, 0f..5f, "ticks")
    }

    private val elytraPredict = tree(ElytraPredict())

    // --- State -------------------------------------------------------------------------------

    private var tracked: Entity? = null
    private var lastTick = 0

    /** The fraction of the residual taken per step, per axis. */
    private var adjYaw = ADJ_START
    private var adjPitch = ADJ_START

    private var attackCount = 0
    private var flickTicks = 0
    private var flickYawOffset = 0f
    private var flickPitchOffset = 0f

    /** The source's `onAttack()`, driven by our own attack on the tracked target. */
    @Suppress("unused")
    private val attackHandler = handler<PacketEvent>(priority = ATTACK_COUNT_PRIORITY) { event ->
        val packet = event.packet
        if (event.isCancelled || packet !is ServerboundAttackPacket) {
            return@handler
        }

        if (packet.entityId == tracked?.id) {
            onAttack()
        }
    }

    override fun disable() {
        reset()
    }

    private fun reset() {
        tracked = null
        adjYaw = ADJ_START
        adjPitch = ADJ_START
        attackCount = 0
        flickTicks = 0
        flickYawOffset = 0f
        flickPitchOffset = 0f
    }

    private fun onAttack() {
        attackCount++
        if (attackCount % FLICK_EVERY == 0) {
            val rnd = ThreadLocalRandom.current()
            flickTicks = FLICK_TICKS
            flickYawOffset = (if (rnd.nextBoolean()) 1f else -1f) * rnd.nextFloat(FLICK_YAW_MIN, FLICK_YAW_MAX)
            flickPitchOffset = rnd.nextFloat(-FLICK_PITCH, FLICK_PITCH)
        }
    }

    /**
     * One step of the source's `updateRotations`. `currentRotation` plays its `lastYaw` / `lastPitch`:
     * the rotation `RotationManager` holds, which is this mode's own last output while it has been
     * steering uninterrupted and the player's rotation when nothing was. After a reset the source
     * re-reads the camera instead - see the class KDoc for why this does not.
     *
     * The state is reset when the target changes or the requests were not continuous. WHEN the
     * source's `reset()` runs is a GUESS (its aura was not supplied); resetting on a switch is what
     * keeps an `adj` that bled to ~0 on the last target from crawling onto the next one.
     */
    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val entity = rotationTarget.entity
        if (entity == null) {
            reset()
            return converge(currentRotation, targetRotation)
        }

        val tick = player.tickCount
        if (tracked !== entity || tick - lastTick !in 0..1) {
            reset()
            tracked = entity
        }
        lastTick = tick

        val rnd = ThreadLocalRandom.current()
        val eye = player.eyePosition
        val targetGliding = entity is LivingEntity && entity.isFallFlying

        adaptFraction(entity, eye, currentRotation, targetGliding, rnd)

        val aimPoint = if (targetGliding && elytraPredict.enabled) {
            predictGlide(entity)
        } else {
            callerPoint(entity, eye, targetRotation).add(
                rnd.nextDouble(-POINT_NOISE_XZ, POINT_NOISE_XZ),
                rnd.nextDouble(-POINT_NOISE_Y, POINT_NOISE_Y),
                rnd.nextDouble(-POINT_NOISE_XZ, POINT_NOISE_XZ)
            )
        }

        var target = Rotation.lookingAt(aimPoint, eye)
        if (flickTicks > 0) {
            target = Rotation(
                target.yaw + flickYawOffset,
                (target.pitch + flickPitchOffset).coerceIn(-FLICK_PITCH_CLAMP, FLICK_PITCH_CLAMP)
            )
            flickTicks--
        }

        val jitterYaw = rnd.nextFloat(-ANGLE_JITTER, ANGLE_JITTER)
        val jitterPitch = rnd.nextFloat(-ANGLE_JITTER, ANGLE_JITTER)

        val yawDiff = Mth.wrapDegrees(target.yaw + jitterYaw - currentRotation.yaw)
        // Not wrapped: pitch is not circular (class KDoc, "Pitch, both ends").
        val pitchDiff = (target.pitch + jitterPitch).coerceIn(-90f, 90f) - currentRotation.pitch

        ModuleDebug.debugParameter(this, "AdjYaw", adjYaw)
        ModuleDebug.debugParameter(this, "AdjPitch", adjPitch)

        val gcd = RotationUtil.gcd
        return Rotation(
            currentRotation.yaw + truncateToGrid(yawDiff * adjYaw, gcd),
            // Clamped: Float rounding alone can overshoot the pole (class KDoc, BadPacketsD).
            (currentRotation.pitch + truncateToGrid(pitchDiff * adjPitch, gcd)).coerceIn(-90f, 90f),
            isNormalized = true
        )
    }

    /** The source's `adjYaw` / `adjPitch` update: slow down on the box, speed up off it. */
    private fun adaptFraction(
        entity: Entity,
        eye: Vec3,
        currentRotation: Rotation,
        targetGliding: Boolean,
        rnd: ThreadLocalRandom
    ) {
        val box = entity.boundingBox
        val pad = if (targetGliding) GLIDE_PAD else PAD
        val lookBox = AABB(
            box.minX + box.xsize * pad / 2.0,
            box.minY,
            box.minZ + box.zsize * pad / 2.0,
            box.maxX - box.xsize * pad / 2.0,
            box.maxY - box.ysize * pad,
            box.maxZ - box.zsize * pad / 2.0
        )

        val reach = eye.add(currentRotation.directionVector.scale(LOOK_RANGE))
        val onTarget = lookBox.clip(eye, reach).isPresent || lookBox.contains(eye)

        if (onTarget) {
            adjYaw = (adjYaw - rnd.nextFloat(SLOW_MIN, SLOW_MAX)).coerceIn(0f, 1f)
            adjPitch = (adjPitch - rnd.nextFloat(SLOW_MIN, SLOW_MAX)).coerceIn(0f, 1f)
        } else if (player.isFallFlying) {
            adjYaw = (adjYaw + rnd.nextFloat(GROW_MIN, GLIDE_GROW_YAW_MAX)).coerceIn(0f, 1f)
            adjPitch = (adjPitch + rnd.nextFloat(GROW_MIN, GLIDE_GROW_PITCH_MAX)).coerceIn(0f, 1f)
        } else {
            adjYaw = (adjYaw + rnd.nextFloat(GROW_MIN, GROW_MAX)).coerceIn(0f, 1f)
            adjPitch = (adjPitch + rnd.nextFloat(GROW_MIN, GROW_MAX)).coerceIn(0f, 1f)
        }
    }

    /**
     * The caller's aim point in world space: where the ray of [targetRotation] enters the target's
     * pick box. When it does not (the eyes are inside the box, or the caller aimed off it), the
     * point on that ray at the distance of the box centre stands in — the point noise is a few
     * centimetres, so only the distance it is applied at matters.
     */
    private fun callerPoint(entity: Entity, eye: Vec3, targetRotation: Rotation): Vec3 {
        val direction = targetRotation.directionVector
        val box = entity.boundingBox.inflate(entity.pickRadius.toDouble())
        return box.clip(eye, eye.add(direction.scale(LOOK_RANGE))).orElseGet {
            eye.add(direction.scale(eye.distanceTo(box.center)))
        }
    }

    /** Our stand-in for `PredictUtils.realPredict`: the gliding target's last delta, projected. */
    private fun predictGlide(entity: Entity): Vec3 {
        val velocity = Vec3(entity.x - entity.xOld, entity.y - entity.yOld, entity.z - entity.zOld)
        return entity.boundingBox.center.add(velocity.scale(elytraPredict.ticks.toDouble()))
    }

    /** `step - step % gcd`: a whole number of counts, rounded toward zero, as the source does. */
    private fun truncateToGrid(step: Float, gcd: Double): Float {
        val delta = step.toDouble()
        return (delta - delta % gcd).toFloat()
    }

    // --- Hand-back ---------------------------------------------------------------------------

    /**
     * No entity: hand-back, or a block. Linear, truncated onto the grid so it can never pass the
     * target, with one count guaranteed whenever one is available — so the residual strictly
     * decreases and `RotationManager` can land exactly and release the override.
     */
    private fun converge(currentRotation: Rotation, targetRotation: Rotation): Rotation {
        val gcd = RotationUtil.gcd
        val diff = currentRotation.rotationDeltaTo(targetRotation)
        val yaw = currentRotation.yaw + returnStep(diff.deltaYaw, RETURN_YAW_SPEED, gcd)
        val pitch = currentRotation.pitch + returnStep(diff.deltaPitch, RETURN_PITCH_SPEED, gcd)
        return Rotation(yaw, pitch.coerceIn(-90f, 90f), isNormalized = true)
    }

    private fun returnStep(residual: Float, speed: Float, gcd: Double): Float {
        val step = residual.coerceIn(-speed, speed)
        var counts = (step / gcd).toInt()
        if (counts == 0 && abs(residual) >= gcd) {
            counts = sign(residual).toInt()
        }
        return (counts * gcd).toFloat()
    }

    /**
     * Replay of the step from the live `adj`, with the look assumed OFF the box (so `adj` climbs at
     * its mean rate) and no noise, flick or truncation. Off the box is the only branch that
     * approaches the target at all, so this is the state the estimate is about.
     */
    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        val diff = currentRotation.rotationDeltaTo(targetRotation)
        var yaw = abs(diff.deltaYaw)
        var pitch = abs(diff.deltaPitch)
        var fractionYaw = adjYaw
        var fractionPitch = adjPitch
        var ticks = 0

        while (ticks < MAX_TICKS && (yaw > ARRIVED_ANGLE || pitch > ARRIVED_ANGLE)) {
            ticks++
            fractionYaw = min(fractionYaw + MEAN_GROW, 1f)
            fractionPitch = min(fractionPitch + MEAN_GROW, 1f)
            yaw *= 1f - fractionYaw
            pitch *= 1f - fractionPitch
        }

        return ticks.coerceIn(1, MAX_TICKS)
    }

    private companion object {
        /** After every module that drops an attack, before `BlinkManager` queues one (class KDoc). */
        const val ATTACK_COUNT_PRIORITY: Short = (EventPriorityConvention.FINAL_DECISION + 1).toShort()

        // adj
        const val ADJ_START = 0.5f
        const val SLOW_MIN = 0.005f
        const val SLOW_MAX = 0.025f
        const val GROW_MIN = 0.001f
        const val GROW_MAX = 0.012f
        const val GLIDE_GROW_YAW_MAX = 0.008f
        const val GLIDE_GROW_PITCH_MAX = 0.01f

        // the look box
        const val PAD = 0.1
        const val GLIDE_PAD = -0.5
        const val LOOK_RANGE = 999.0

        // noise
        const val POINT_NOISE_XZ = 0.04
        const val POINT_NOISE_Y = 0.03
        const val ANGLE_JITTER = 0.35f

        // flick
        const val FLICK_EVERY = 6
        const val FLICK_TICKS = 2
        const val FLICK_YAW_MIN = 10f
        const val FLICK_YAW_MAX = 22f
        const val FLICK_PITCH = 8f
        const val FLICK_PITCH_CLAMP = 89f

        // hand-back (the source's 40 / 35, see the class KDoc)
        const val RETURN_YAW_SPEED = 40f
        const val RETURN_PITCH_SPEED = 35f

        // calculateTicks
        const val MEAN_GROW = (GROW_MIN + GROW_MAX) / 2f
        const val ARRIVED_ANGLE = 2f
        const val MAX_TICKS = 60
    }
}
