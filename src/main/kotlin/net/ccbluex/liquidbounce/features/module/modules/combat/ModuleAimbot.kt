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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRequirements
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleFreeLook
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleZoom
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.InterpolationAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.LinearAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.SigmoidAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.aiming.utils.setRotation
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * Aimbot module
 *
 * Automatically faces selected entities around you.
 *
 * `Mode = BoostSens` (ours, 2026-09-23) does not rotate at all: it scales the player's own mouse
 * delta up while it moves the crosshair towards the target and down while it moves away.
 */
object ModuleAimbot : ClientModule("Aimbot", ModuleCategories.COMBAT, aliases = listOf("AimAssist", "AutoAim")) {

    private val range = float("Range", 4.2f, 1f..8f)

    val targetTracker = tree(TargetTracker(TargetPriority.DIRECTION, range = range))

    init {
        tree(TargetRenderer(this, targetTracker))
    }
    private val pointTracker = tree(PointTracker(this))

    private val requires by multiEnumChoice<KillAuraRequirements>("Requires")

    private val requirementsMet
        get() = mc.screen == null && requires.all { it.asBoolean }

    /**
     * Declared above the values it hides so it sits at the top of the GUI. That is safe because
     * `onChanged` only fires from `set` (`Value.kt:268-270`), which nothing calls during construction;
     * the initial state is covered by the `init` block after the last hidden value.
     */
    private val mode by enumChoice("Mode", AimMode.ROTATE)
        .onChanged { syncModeVisibility(it) }

    /**
     * Settings of [AimMode.BOOST_SENS]. The mode never rotates by itself: it only scales the mouse delta
     * the player produced, up when it moves the crosshair towards the aim point and down when away.
     */
    private object BoostSens : ValueGroup("BoostSens") {
        /** Multiplier for mouse movement towards the target. */
        val boost by float("Boost", 1.5f, 1f..3f)

        /** Multiplier for mouse movement away from the target. */
        val slow by float("Slow", 0.6f, 0.1f..1f)

        /** Angle from the crosshair to the aim point past which the mouse is left untouched. */
        val fov by float("FOV", 60f, 5f..180f)
    }

    init {
        tree(BoostSens)
    }

    private var angleSmooth = modes(this, "AngleSmooth") {
        arrayOf(
            InterpolationAngleSmooth(it),
            SigmoidAngleSmooth(it),
            LinearAngleSmooth(it)
        )
    }

    private val axis by multiEnumChoice<Axis>("Axis", Axis.HORIZONTAL, Axis.VERTICAL)

    private val ignoresValue = multiEnumChoice<IgnoreOpened>("Ignore")
    private val ignores by ignoresValue

    init {
        syncModeVisibility(mode)
    }

    private fun syncModeVisibility(current: AimMode) {
        val boostSens = current == AimMode.BOOST_SENS
        BoostSens.notAnOption(!boostSens)
        angleSmooth.notAnOption(boostSens)
        ignoresValue.notAnOption(boostSens)
    }

    private var targetRotation: Rotation? = null
    private var playerRotation: Rotation? = null

    /**
     * The entity [AimMode.BOOST_SENS] steers towards and the aim point's offset from its position,
     * so the point can be re-projected every frame instead of lagging a tick behind a strafing target.
     */
    private var aimEntity: Entity? = null
    private var aimOffset: Vec3 = Vec3.ZERO

    /** Sub-count remainders the mouse grid rounding carries over to the next frame, per axis. */
    private var carryX = 0.0
    private var carryY = 0.0

    @Suppress("unused", "ComplexCondition")
    private val tickHandler = handler<RotationUpdateEvent> { _ ->
        playerRotation = player.rotation

        if (!requirementsMet) {
            targetTracker.reset()
            targetRotation = null
            clearAim()
            return@handler
        }

        if (mode == AimMode.BOOST_SENS) {
            targetRotation = null

            val next = findNextTargetRotation(maxAngle = BoostSens.fov)
            if (next == null) {
                clearAim()
            } else {
                val (target, rotation) = next
                aimEntity = target
                aimOffset = rotation.vec.subtract(target.position())
            }

            ModuleAutoWeapon.onTarget(targetTracker.target)
            return@handler
        }

        clearAim()
        targetRotation = findNextTargetRotation()?.let { (target, rotation) ->
            angleSmooth.activeMode.process(
                RotationTarget(
                    rotation = rotation.rotation,
                    entity = target,
                    processors = listOf(angleSmooth.activeMode),
                    ticksUntilReset = 1,
                    resetThreshold = 1f,
                    considerInventory = true,
                    movementCorrection = MovementCorrection.CHANGE_LOOK
                ),
                player.rotation,
                rotation.rotation
            )
        }

        // Update Auto Weapon
        ModuleAutoWeapon.onTarget(targetTracker.target)
    }

    override fun onDisabled() {
        targetTracker.reset()
        clearAim()
    }

    private fun clearAim() {
        aimEntity = null
        aimOffset = Vec3.ZERO
        carryX = 0.0
        carryY = 0.0
    }

    /**
     * [AimMode.BOOST_SENS]: scales the raw mouse delta before anything turns the player with it.
     *
     * Runs ahead of every other [MouseRotationEvent] handler (they all sit at the default 0) so that
     * the camera, `RotationManager`'s mouse pass-through and this module's own bookkeeping below
     * all see the same scaled delta.
     *
     * The result is snapped back onto vanilla's mouse grid (whole mouse counts times the sensitivity
     * factor, `MouseHandler.turnPlayer`), with the rounding remainder carried to the next frame, so
     * every turn it produces is one a real mouse at this sensitivity could have produced. Grim's
     * only rotation-delta checks are `AimModulo360` (a >320 degree yaw snap) and `AimDuplicateLook`
     * (a rotation packet that does not rotate); neither can be reached by scaling a mouse delta.
     */
    @Suppress("unused")
    private val boostSensHandler = handler<MouseRotationEvent>(
        priority = EventPriorityConvention.CRITICAL_MODIFICATION
    ) { event ->
        if (mode != AimMode.BOOST_SENS || !requirementsMet || event.isCancelled) {
            return@handler
        }

        // FreeLook turns a detached camera, Zoom and the spyglass switch vanilla to a finer grid,
        // cinematic camera smooths the delta off the grid entirely. None of them is aiming.
        if (ModuleFreeLook.running || ModuleZoom.running || player.isScoping || mc.options.smoothCamera) {
            return@handler
        }

        val entity = aimEntity?.takeUnless { it.isRemoved } ?: return@handler

        val partialTicks = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val aimPoint = entity.getPosition(partialTicks).add(aimOffset)
        val remaining = player.rotation.rotationDeltaTo(
            Rotation.lookingAt(aimPoint, from = player.getEyePosition(partialTicks))
        )

        val fov = BoostSens.fov
        val angle = remaining.length()
        if (angle > fov) {
            carryX = 0.0
            carryY = 0.0
            return@handler
        }

        // Full strength inside the cone, fading to none over its outer edge, so the effect does not
        // switch on with a jolt as a target enters it.
        val strength = ((fov - angle) / (fov * EDGE_FADE)).coerceIn(0f, 1f)
        val quantum = mouseGridStep()

        // An axis the mouse did not move on is left alone: adding the carry to it could round up to a
        // whole count and turn the player on an axis the player never touched.
        if (Axis.HORIZONTAL in axis && event.cursorDeltaX != 0.0) {
            val scaled = scaleAxis(event.cursorDeltaX, remaining.deltaYaw, strength) + carryX
            event.cursorDeltaX = snapToGrid(scaled, quantum)
            carryX = scaled - event.cursorDeltaX
        }

        if (Axis.VERTICAL in axis && event.cursorDeltaY != 0.0) {
            val scaled = scaleAxis(event.cursorDeltaY, remaining.deltaPitch, strength) + carryY
            event.cursorDeltaY = snapToGrid(scaled, quantum)
            carryY = scaled - event.cursorDeltaY
        }
    }

    /**
     * Scales one non-zero axis of a mouse delta. [delta] is in `LocalPlayer.turn` units
     * (degrees / 0.15), and a positive value raises yaw / pitch just like a positive [remaining]
     * asks for, so equal signs mean the player is moving towards the aim point.
     */
    private fun scaleAxis(delta: Double, remaining: Float, strength: Float): Double {
        val towards = remaining != 0f && (delta > 0.0) == (remaining > 0f)
        if (!towards) {
            return delta * (1f + (BoostSens.slow - 1f) * strength)
        }

        val boosted = delta * (1f + (BoostSens.boost - 1f) * strength)

        // The boost may bring the crosshair onto the aim point but not throw it past: past the point
        // the same movement counts as "away" and would be slowed, which feels like hitting a wall.
        // The player's own movement is never cut, only the extra the boost adds.
        val left = abs(remaining / TURN_SCALE).toDouble()
        return if (abs(boosted) > left) sign(delta) * max(abs(delta), left) else boosted
    }

    private fun snapToGrid(value: Double, quantum: Double): Double =
        if (quantum > 0.0) (value / quantum).roundToLong() * quantum else value

    /**
     * One mouse count in `LocalPlayer.turn` units, exactly as `MouseHandler.turnPlayer` derives it
     * outside of scoping: `(sensitivity * 0.6F + 0.2F)^3 * 8.0`.
     */
    private fun mouseGridStep(): Double {
        val d = mc.options.sensitivity().get() * 0.6F + 0.2F
        return d * d * d * 8.0
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val matrixStack = event.matrixStack
        val partialTicks = event.partialTicks
        val target = targetTracker.target ?: return@handler

        if (IgnoreOpened.SCREEN !in ignores && mc.screen != null) {
            return@handler
        }

        if (IgnoreOpened.CONTAINER !in ignores && (InventoryManager.isInventoryOpen ||
                mc.screen is AbstractContainerScreen<*>)) {
            return@handler
        }

        lookAt(partialTicks)
    }

    @Suppress("unused", "MagicNumber")
    private val mouseMovement = handler<MouseRotationEvent> { event ->
        val f = event.cursorDeltaY.toFloat() * 0.15f
        val g = event.cursorDeltaX.toFloat() * 0.15f

        fun updateRotation(rotation: Rotation): Rotation =
            Rotation(yaw = rotation.yaw + g, pitch = (rotation.pitch + f).coerceIn(-90f, 90f))

        playerRotation?.let { rotation ->
            playerRotation = updateRotation(rotation)
        }

        targetRotation?.let { rotation ->
            targetRotation = updateRotation(rotation)
        }
    }

    /**
     * Looks at the target rotation, with interpolation based on the timer speed and partial ticks to make it smooth.
     */
    private fun lookAt(partialTicks: Float) {
        val playerRotation = playerRotation ?: return
        val targetRotation = targetRotation ?: return
        val timerSpeed = Timer.timerSpeed
        val interpolatedRotation = playerRotation.interpolateTo(targetRotation, timerSpeed * partialTicks)

        player.setRotation(
            Rotation(
                yaw = if (Axis.HORIZONTAL in axis) interpolatedRotation.yaw else playerRotation.yaw,
                pitch = if (Axis.VERTICAL in axis) interpolatedRotation.pitch else playerRotation.pitch,
            )
        )
    }

    /**
     * @param maxAngle targets whose aim point is further than this from the crosshair are skipped
     */
    private fun findNextTargetRotation(maxAngle: Float = 180f): Pair<Entity, RotationWithVector>? {
        for (entity in targetTracker.targets()) {
            val eyes = player.eyePosition
            val point = pointTracker.findPoint(eyes, entity)

            debugGeometry("Box") { ModuleDebug.DebuggedBox(point.box, Color4b.ORANGE.with(a = 90)) }
            debugGeometry("Point") { ModuleDebug.DebuggedPoint(point.pos, Color4b.WHITE, size = 0.1) }

            val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, point.pos)
            val rotation = raytraceBox(
                eyes = eyes,
                box = point.box,
                range = targetTracker.maxRange.toDouble(),
                wallsRange = 0.0,
                rotationPreference = rotationPreference
            ) ?: continue

            if (player.rotation.angleTo(rotation.rotation) > maxAngle) {
                continue
            }

            targetTracker.target = entity
            return entity to rotation
        }

        targetTracker.reset()
        return null
    }

    /** Fraction of [BoostSens.fov], at its outer edge, over which the effect fades out. */
    private const val EDGE_FADE = 0.3f

    /** Degrees per unit of `LocalPlayer.turn` input (`Entity.turn` multiplies by 0.15F). */
    private const val TURN_SCALE = 0.15f

    private enum class AimMode(override val tag: String) : Tagged {
        /** Turns the camera towards the target with the configured angle smoothing. */
        ROTATE("Rotate"),

        /** Leaves the aiming to the player and only scales the mouse towards / away from the target. */
        BOOST_SENS("BoostSens")
    }

    private enum class IgnoreOpened(
        override val tag: String
    ) : Tagged {
        SCREEN("Screen"),
        CONTAINER("Container")
    }

    private enum class Axis(override val tag: String) : Tagged {
        HORIZONTAL("Horizontal"),
        VERTICAL("Vertical")
    }
}
