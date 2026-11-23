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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.GridMode
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.RotationMode
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.RotationSettings
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.world.entity.Entity
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Speed-limited turn towards the target rotation; the single processor of every [RotationMode.SMOOTH]
 * target built by [AuraRotation].
 *
 * ### How RotationManager drives it
 * `RotationManager.update()` (RotationManager.kt:176-233) calls `RotationTarget.towards(from, resetting)`
 * once per tick and `normalize()`s the result (RotationManager.kt:185-187). While the request is alive,
 * `towards` hands every processor the requested rotation (RotationTarget.kt:90-105). Once the request has
 * expired (`resetting`, RotationManager.kt:183) it nulls `RotationTarget.entity` and hands ONLY the first
 * processor `player.rotation` as the target (RotationTarget.kt:77-88). This processor is therefore the only
 * code that ever sees its target, on the approach and on the return path alike.
 *
 * ### Reset rule
 * `resetting = rotationTarget.entity == null`. That is the pipeline-wide convention: every AngleSmooth reads
 * it the same way (IntpolAngleSmooth.kt:102, NaturalAngleSmooth.kt:457) and AimSource.kt:195-197 documents
 * a null entity as "hand the rotation back to the player". Callers of [AuraRotation.request] should therefore
 * pass the victim entity whenever they have one; with a null entity the approach falls back to
 * [RotationSettings.returnSpeed] instead of the acceleration envelope - slower, but still monotone.
 *
 * ### Step
 * The yaw delta is wrapped (`RotationUtil.angleDifference` = `Mth.wrapDegrees(a - b)`, RotationUtil.kt:69), the
 * pitch delta is a plain subtraction, `dist = hypot(deltaYaw, deltaPitch)` (the length
 * `RotationDelta.length()` computes, RotationDelta.kt:26). `Rotation.rotationDeltaTo` (Rotation.kt:144-149) is
 * deliberately NOT used here: it wraps BOTH axes, and pitch lives in [-90, 90], so a delta of exactly +180
 * (current -90 -> target +90, both reachable through the +-90 clamps below) wraps to -180
 * (`Mth.wrapDegrees(180f)`: `180 % 360 >= 180` -> `-180`, `net/minecraft/util/Mth.java:215-226`). The step would
 * then push the pitch into the clamp it is already sitting on while the yaw step is zero - a hard stall.
 * Speed per tick: `returnSpeed` when resetting, otherwise `accel * dist` clamped into `[minSpeed, maxSpeed]`.
 * If `dist <= speed` the target itself is returned (exact landing); otherwise the rotation moves `speed`
 * degrees along the straight line. In [GridMode.EXACT] that step is re-expressed as whole gcd counts relative
 * to the current rotation - never zero on the dominant axis - and marked `isNormalized`, so the manager's
 * `normalize()` leaves it untouched (Rotation.kt:108).
 *
 * ### Why the step is strictly monotone
 * The manager releases a silent rotation only once the rotation already on the wire equals the camera
 * EXACTLY (`sentDiff <= 0f`, RotationManager.kt:200-202); short of that it keeps stepping and, within
 * `resetThreshold`, snaps onto the real rotation (RotationManager.kt:216-220). A step rule that could stall
 * (a step rounding to zero counts) or fail to converge would keep the override alive forever and the module
 * could never hand the aim back. Here `dist` shrinks every tick: the step is at least
 * `min(minSpeed, returnSpeed) >= 1 degree` (value ranges, AnchorConfig.kt:420-432), while what is added
 * afterwards is at most 0.5*gcd rounding + 0.4*gcd dither per axis (`Rotation.normalize`,
 * Rotation.kt:54-63,120-127) - about 1.27*gcd <= 0.78 degrees at 100 % sensitivity
 * (`RotationUtil.gcd`, RotationUtil.kt:46-50) - or 0.5*gcd per axis for the EXACT grid step. The final
 * step returns the target verbatim; on the return path that is the `player.rotation` handed in by `towards`,
 * exactly what the zero-delta hand-off needs - see the next section for why that last step alone bypasses
 * `normalize()`.
 *
 * ### Why the reset landing is marked normalized
 * On the RESET path that final landing is additionally marked `isNormalized = true`, which turns the manager's
 * `normalize()` into a no-op (Rotation.kt:107-108). Without the flag the residual is rounded onto the mouse
 * grid (Rotation.kt:117-125), and a residual below 0.5*gcd rounds to zero: `normalize()` then hands back the
 * very rotation the manager already holds, `sentDiff` (RotationManager.kt:202) never reaches zero and the
 * release at RotationManager.kt:204 never fires. The threshold snap (RotationManager.kt:218-219) cannot save it
 * either once [RotationSettings.resetThreshold] is below 0.5*gcd - the setting bottoms out at 0.1 degrees
 * (AnchorConfig.kt:432) while 0.5*gcd reaches ~0.31 degrees at 100 % sensitivity - so the module would
 * hold the player's server-side rotation hostage forever. The approach path is deliberately left unmarked:
 * flagging it would suppress the [GridMode.DITHER] noise that is the whole point of that mode.
 */
class SmoothProcessor(private val settings: RotationSettings) : RotationProcessor {

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        val resetting = rotationTarget.entity == null
        val deltaYaw = RotationUtil.angleDifference(targetRotation.yaw, currentRotation.yaw)
        // Plain subtraction: wrapping the pitch would turn an exact +-180 delta into a stall (class KDoc).
        val deltaPitch = targetRotation.pitch - currentRotation.pitch
        val dist = hypot(deltaYaw, deltaPitch)
        val speed = if (resetting) settings.returnSpeed else approachSpeed(dist)

        if (dist <= speed) {
            // The hand-back has to land on `player.rotation` EXACTLY, so `normalize()` must not round it
            // back onto the grid (class KDoc). On the approach the dither stays welcome.
            return if (resetting) targetRotation.copy(isNormalized = true) else targetRotation
        }

        val factor = speed / dist
        val stepYaw = deltaYaw * factor
        val stepPitch = deltaPitch * factor

        return if (settings.grid == GridMode.EXACT) {
            gridStep(currentRotation, stepYaw, stepPitch)
        } else {
            Rotation(currentRotation.yaw + stepYaw, (currentRotation.pitch + stepPitch).coerceIn(-90f, 90f))
        }
    }

    /**
     * `accel * dist` clamped into `[minSpeed, maxSpeed]`. The lower bound is capped at the upper one, so a
     * MinSpeed above MaxSpeed degrades to a constant speed instead of `coerceIn` throwing on an empty range.
     */
    private fun approachSpeed(dist: Float): Float {
        val maxSpeed = settings.maxSpeed
        val minSpeed = min(settings.minSpeed, maxSpeed)
        return (settings.accel * dist).coerceIn(minSpeed, maxSpeed)
    }

    /**
     * Re-expresses the step as whole gcd counts relative to [current] (that delta is what the wire carries)
     * and forces at least one count on the dominant axis, so the step can never round to a stand-still.
     * The pitch clamp at +-90 is the same one vanilla and `Rotation.normalize` apply (Rotation.kt:127).
     */
    private fun gridStep(current: Rotation, stepYaw: Float, stepPitch: Float): Rotation {
        val gcd = RotationUtil.gcd
        var yawCounts = Math.round(stepYaw / gcd)
        var pitchCounts = Math.round(stepPitch / gcd)

        if (abs(stepYaw) >= abs(stepPitch)) {
            if (yawCounts == 0L) {
                yawCounts = if (stepYaw < 0f) -1L else 1L
            }
        } else if (pitchCounts == 0L) {
            pitchCounts = if (stepPitch < 0f) -1L else 1L
        }

        return Rotation(
            (current.yaw + yawCounts * gcd).toFloat(),
            (current.pitch + pitchCounts * gcd).toFloat().coerceIn(-90f, 90f),
            isNormalized = true
        )
    }
}

/**
 * Bridge between the anchor aura and [RotationManager].
 *
 * ### This copy is deliberate and load-bearing - do NOT replace it with a shared object
 * This is a client-lifetime `object` with MUTABLE state: [lastEntity], the cached [SmoothProcessor] and the
 * [RotationSettings] identity it is keyed on. If the crystal aura and the anchor aura shared ONE instance, the
 * module that requested last would overwrite the other's [lastEntity], and [hold] would then re-request with a
 * foreign victim - or with none, which is the pipeline's RESET signal (see [SmoothProcessor]). A cycle would hand
 * the aim back mid-flight because the other module happened to file a request. The cached processor has the same
 * hazard one level down: it is keyed by `===` on the settings object, so two modules with different speed
 * envelopes would thrash the cache and rebuild the processor on every alternating request.
 *
 * That hazard is exactly why the plan chose copy-not-extract for the aiming machinery: each module owning its own
 * `AuraRotation` object removes it outright, at the price of a duplicated file. An extraction would have had to fix
 * it first, by turning this object into a per-module instance.
 *
 * ### Modes
 * - [RotationMode.SNAP]: the target carries an empty processor list. `RotationTarget.towards` then returns
 *   the requested rotation verbatim (RotationTarget.kt:96-98), so the wire jumps onto it in the same
 *   `RotationManager.update()`; once the request has expired, the manager releases instantly
 *   (`processors.isEmpty()` -> `instantRelease`, RotationManager.kt:192-210) - there is no return path.
 * - [RotationMode.SMOOTH]: the target carries one [SmoothProcessor], which steps the approach and - being
 *   the first (only) processor - the return (RotationTarget.kt:77-88). The manager keeps stepping after the
 *   request expired until the wire rotation equals the camera exactly (RotationManager.kt:200-222).
 *
 * ### Grid
 * The manager `normalize()`s every step (RotationManager.kt:185-187): the delta from the rotation it steps
 * from (`currentRotation ?: player.rotation`) is rounded onto the mouse grid (`RotationUtil.gcd`) and
 * dithered by up to 0.4*gcd (Rotation.kt:54-63,107-128). [GridMode.DITHER] leaves that alone.
 * [GridMode.EXACT] pre-quantises the requested delta from the same origin to whole gcd counts and marks the
 * rotation `isNormalized`, which turns `normalize()` into a no-op (Rotation.kt:108): exact multiples on the
 * wire, no dither. (Per the `normalize` KDoc that exact grid is what Grim's AimProcessor keys on; DITHER is
 * the anticheat-friendlier default.) [slackDegrees] tells the look point search how far the wire may end up
 * from the requested aim per axis: 0.5*gcd rounding + 0.4*gcd dither for DITHER, 0.5*gcd for EXACT.
 *
 * ### Request semantics
 * `RotationManager.setRotationTarget` (RotationManager.kt:138-153) files a `RequestHandler.Request` that is
 * active for [RotationSettings.holdTicks] updates: `request()` replaces the module's previous request and
 * adds `currentTick` to the expiry (RequestHandler.kt:36-41), `getActiveRequestValue` drops expired requests
 * and those of a provider that is not `running` (RequestHandler.kt:43-55), `tick()` runs after every update
 * (RotationManager.kt:232). With `MovementCorrection.CHANGE_LOOK` the lifetime is forced to one tick
 * (RotationManager.kt:145-148), so [hold] has to be called every tick in that mode. The request is silently
 * dropped while `CombatManager.shouldPauseRotation` (RotationManager.kt:139-141, 279) and loses against a
 * higher priority - callers must verify against [outgoing] before acting, never assume the request landed.
 *
 * `considerInventory = false`: with `true`, `isRotatingAllowed` (RotationManager.kt:158-170) skips the
 * whole update - the return path included - while a container screen is open, freezing whatever silent
 * rotation is on the wire. The aura decides itself when it acts, so its aim pipeline must keep stepping
 * regardless of screens.
 *
 * Priority `IMPORTANT_FOR_USAGE_2` (30, Priority.kt:32): deliberately ONE STEP BELOW the crystal aura's
 * `IMPORTANT_FOR_USAGE_3` (35, `crystalaura/aim/AuraRotation.kt`), and far below the
 * `IMPORTANT_FOR_PLAYER_LIFE` (40) ModuleAnchorOptimizer requests with (ModuleAnchorOptimizer.kt:541).
 *
 * The two auras used to share slot 35. `RequestHandler` orders by priority ALONE, so that tie was UNORDERED,
 * and the module hub covered it by standing down for the whole time `ModuleCrystalAura.isRotating` was true.
 * That flag is true on every tick the crystal aura has a target, so the cover was permanent: with both modules
 * enabled this one never received a single tick. Splitting the priorities is what replaced that stand-down -
 * the crystal aura now WINS the aim deterministically whenever it wants it, and this module aims on the ticks
 * it leaves free.
 *
 * The tie this creates with KillAura (30, ModuleKillAura.kt:536,597) needs no cover of its own: whoever loses
 * an arbitration finds out immediately, because every click is verified against `outgoing` - the rotation
 * actually on the wire - and a lost aim therefore sends no packet at all rather than a misaimed one.
 *
 * ### hold
 * Between the three interactions of one cycle (place -> charge -> detonate) there is nothing new to aim at, but
 * if the request expired the manager would start returning to the camera (SNAP: instantly), the server rotation
 * would flip back and forth every cycle and the next interaction would have to travel from the camera again.
 * [hold] re-requests the rotation that is already on the wire ([wire], marked `isNormalized` so both the
 * processor step and `normalize()` are no-ops): the override stays alive and the outgoing packet does not
 * change. That is also what keeps Grim's `DuplicateRotPlace` rewarding rather than arming - see [LookPoints].
 * The wire equals the manager's `currentRotation` because `sendPosition` reads it
 * (MixinLocalPlayer.java:354-373) - which also holds while fake-lagging, where [wire] follows the packets that
 * were built and then held back rather than the last one that actually left.
 *
 * ### The entity
 * `RotationTarget.entity` is read only by the processors of the target's own list (the AngleSmooth
 * implementations under utils/aiming) - here just [SmoothProcessor]; every other reader of
 * `activeRotationTarget` (MixinKeyboardInput, MixinLivingEntity, MixinPlayer, MixinFireworkRocketEntity,
 * NavSteering, NavMountSteering, ElytraTarget, AutoBow, Trajectories) touches only `movementCorrection` or
 * `rotation`. Passing any entity here therefore has no side effect beyond the reset detection above.
 */
object AuraRotation : MinecraftShortcuts {

    /** Rounding (0.5*gcd) plus dither (0.4*gcd) that `Rotation.normalize` may add per axis. */
    private const val DITHER_SLACK = 0.9f

    /** Rounding only: the delta is pre-quantised and `normalize` is a no-op. */
    private const val EXACT_SLACK = 0.5f

    /** One stateless [SmoothProcessor] per settings object; re-created only if another settings object shows up. */
    private var smoothProcessor: SmoothProcessor? = null
    private var smoothSettings: RotationSettings? = null

    /**
     * Entity of the last [request]; re-used by [hold] so the processor stays on the approach path. Held weakly:
     * this is a client-lifetime `object`, and a strong field would keep the victim - and through it its
     * `ClientLevel` - alive after the module was disabled or the world changed (same reason as the weak level
     * cache in EnchantProtection.kt:129-130). [reset] clears it; the weak reference is the safety net for a
     * missed [reset].
     */
    private var lastEntity: WeakReference<Entity>? = null

    /**
     * The rotation the server currently has for us: `RotationManager.serverRotation`
     * (RotationManager.kt:113-114). That is `theoreticalServerRotation` while Blink / Backtrack / AirStuck hold
     * our packets back (`fakeLagging || freezing`, RotationManager.kt:107-111) and `actualServerRotation`
     * otherwise. Both are gathered from the outgoing move/use packets and server teleports
     * (RotationManager.kt:331-372); the actual one is skipped for cancelled packets
     * (RotationManager.kt:368-370), the theoretical one is not (:371).
     *
     * The theoretical value is the correct reference while fake-lagging: our action packet is queued BEHIND the
     * held move packets, so by the time the server processes it, it holds the rotation those queued packets
     * carry - not the older one that actually reached it so far. Verifying rays against `actualServerRotation`
     * there would check a rotation the server has already moved past.
     */
    val wire: Rotation
        get() = RotationManager.serverRotation

    /**
     * The rotation THIS tick's movement packet will carry: `RotationManager.currentRotation` once the manager's own
     * `update()` has run (RotationManager.kt:92, 178-233), falling back to `player.rotation` while no silent
     * rotation is active - exactly the fallback the `sendPosition` hooks apply
     * (`hookSilentRotationYaw` / `hookSilentRotationPitch`, MixinLocalPlayer.java:354-374, which return the vanilla
     * `getYRot()` / `getXRot()` for a null `currentRotation`).
     *
     * ### Why it is already known at EXECUTE time
     * `RotationManager`'s `GameTickEvent` handler fires `RotationUpdateEvent` - the aura's PLAN - and calls
     * `update()` immediately after, at `FIRST_PRIORITY` (RotationManager.kt:319-324). `GameTickEvent` is the HEAD of
     * `Minecraft.tick` (MixinMinecraft.java:290-295), `InputHandleEvent` - the aura's EXECUTE - the RETURN of
     * `handleKeybinds` (MixinMinecraft.java:332-334). So at EXECUTE this value is final and post-`normalize()`
     * (RotationManager.kt:185-187): no guessing and no slack, it is bit-for-bit what `sendPosition` will send a few
     * lines later in the same `Minecraft.tick()`.
     *
     * ### Why aiming an action with it is not more aggressive than vanilla
     * `MouseHandler.handleAccumulatedMovement()` is the only caller of `turnPlayer`
     * (`client/MouseHandler.java:334-336`) and runs in the render section (`client/Minecraft.java:1321`), AFTER the
     * tick loop (`:1301`). Within one `Minecraft.tick()` the rotation is therefore constant: `pick(1.0F)`
     * (`:1823`), the clicks inside `handleKeybinds` (`:1865`) and `sendPosition` all read the same value. A vanilla
     * click is aimed with the rotation the SAME tick's movement packet carries, and that packet leaves after the
     * click - so verifying against [wire] (the rotation already sent) was strictly more conservative than vanilla
     * and cost one client tick per re-aim.
     *
     * ### Why Grim reads it the same way
     * Placement, dig and attack checks are all deferred to the flying packet and re-evaluated with the NEW rotation:
     * the queued places and breaks are drained at `CheckManagerListener.java:680-681`, after `player.yaw/pitch` were
     * shifted; the placement check itself is `RotationPlace.onPostFlyingBlockPlace`, whose own comment at
     * `scaffolding/RotationPlace.java:52` reads *"Use post flying because it has the correct rotation, and can't
     * false easily."* The same-tick rotation is its FIRST raytrace candidate.
     *
     * Exactness is not optional here: `RotationPlace`'s `flagBuffer` is asymmetric - one post-flying MISS arms the
     * pre-flying path, which CANCELS placements (`place.resync()`) for the next 10 interactions instead of merely
     * flagging them. An action may therefore only be sent in the same tick when the ray provably hits with this
     * exact value; never with a widened tolerance. The fallback is simply to wait for the next tick, where [wire]
     * has caught up.
     *
     * Use [wire], not this, for anything that reasons about what the server ALREADY has.
     */
    val outgoing: Rotation
        get() = RotationManager.currentRotation ?: player.rotation

    /** Allowed deviation of a look point from the exact aim, in degrees: 0.9*gcd (Dither) or 0.5*gcd (Exact). */
    fun slackDegrees(settings: RotationSettings): Float {
        val gcd = RotationUtil.gcd.toFloat()
        return if (settings.grid == GridMode.EXACT) EXACT_SLACK * gcd else DITHER_SLACK * gcd
    }

    /**
     * Files [rotation] with the manager for [RotationSettings.holdTicks] ticks at priority
     * `IMPORTANT_FOR_USAGE_3`. Pass the victim [entity] whenever there is one: a null entity is the
     * pipeline's reset signal (see [SmoothProcessor]). In [GridMode.EXACT] the rotation is quantised
     * relative to the rotation the manager will step from; an already normalized input passes through.
     */
    fun request(module: ClientModule, rotation: Rotation, entity: Entity?, settings: RotationSettings) {
        lastEntity = entity?.let(::WeakReference)

        val processors: List<RotationProcessor> = if (settings.mode == RotationMode.SNAP) {
            emptyList()
        } else {
            listOf(smoothProcessorFor(settings))
        }
        val target = RotationTarget(
            rotation = if (settings.grid == GridMode.EXACT) quantiseExact(rotation) else rotation,
            entity = entity,
            processors = processors,
            ticksUntilReset = settings.holdTicks,
            resetThreshold = settings.resetThreshold,
            considerInventory = false,
            movementCorrection = settings.movementCorrection
        )
        RotationManager.setRotationTarget(target, Priority.IMPORTANT_FOR_USAGE_2, module)
    }

    /**
     * Re-requests [wire] (see the class KDoc) with the entity of the last [request]; an entity that has since
     * been removed from the level or already been collected is dropped (null), which only changes the speed of
     * a zero-length step.
     */
    fun hold(module: ClientModule, settings: RotationSettings) {
        val entity = lastEntity?.get()?.takeUnless { it.isRemoved }
        request(module, wire.copy(isNormalized = true), entity, settings)
    }

    /**
     * Drops everything this client-lifetime `object` retains between module runs: the cached victim of the last
     * [request] and the [SmoothProcessor] / [RotationSettings] pair cached for it. Call it from the module's own
     * state reset (disable, world change); the next [request] rebuilds the processor. It does not touch the
     * manager - an in-flight request expires on its own after [RotationSettings.holdTicks], and
     * `RequestHandler` drops the request of a provider that is no longer `running` anyway
     * (RequestHandler.kt:43-55).
     */
    fun reset() {
        lastEntity = null
        smoothProcessor = null
        smoothSettings = null
    }

    private fun smoothProcessorFor(settings: RotationSettings): SmoothProcessor {
        val cached = smoothProcessor
        if (cached != null && smoothSettings === settings) {
            return cached
        }

        return SmoothProcessor(settings).also {
            smoothProcessor = it
            smoothSettings = settings
        }
    }

    /**
     * Rounds the delta from the rotation the manager will step from (`currentRotation ?: player.rotation`,
     * the same origin `Rotation.normalize` uses, Rotation.kt:112-121) to whole gcd counts and marks the result
     * normalized. Yaw is wrapped through `RotationUtil.angleDifference`; the pitch clamp at +-90 mirrors
     * `normalize` (Rotation.kt:127). Already normalized rotations (e.g. the wire copy from [hold]) are
     * returned untouched, mirroring Rotation.kt:108.
     */
    private fun quantiseExact(rotation: Rotation): Rotation {
        if (rotation.isNormalized) {
            return rotation
        }

        val from = RotationManager.currentRotation ?: player.rotation
        val gcd = RotationUtil.gcd
        val yawCounts = Math.round(RotationUtil.angleDifference(rotation.yaw, from.yaw) / gcd)
        val pitchCounts = Math.round((rotation.pitch - from.pitch) / gcd)

        return Rotation(
            (from.yaw + yawCounts * gcd).toFloat(),
            (from.pitch + pitchCounts * gcd).toFloat().coerceIn(-90f, 90f),
            isNormalized = true
        )
    }
}
