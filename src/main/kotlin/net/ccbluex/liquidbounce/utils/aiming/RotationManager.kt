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
package net.ccbluex.liquidbounce.utils.aiming

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerVelocityStrafe
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.backtrack.ModuleBacktrack
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleAirStuck
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import net.ccbluex.liquidbounce.utils.aiming.utils.setRotation
import net.ccbluex.liquidbounce.utils.aiming.utils.withFixedYaw
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.entity.lastRotation
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.MODEL_STATE
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.client.RequestHandler
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.Entity

/**
 * A rotation manager
 */
object RotationManager : EventListener {

    /**
     * Our final target rotation. This rotation is only used to define our current rotation.
     */
    private val rotationTarget
        get() = rotationTargetHandler.getActiveRequestValue()
    private val rotationTargetHandler = RequestHandler<RotationTarget>()

    val activeRotationTarget: RotationTarget?
        get() = rotationTarget ?: previousRotationTarget

    /**
     * Priority of whoever currently owns the rotation, or null when nobody does.
     *
     * A provider that loses on priority still gets a rotation back from the pipeline, so it cannot
     * otherwise tell that something more important is steering. Cosmetic providers need to know:
     * fighting an aim that is being driven for an ACTION does not just look wrong, it can cost the
     * action, since the click is validated against the rotation actually on the wire.
     */
    val activeRotationPriority: Int?
        get() = rotationTargetHandler.getActiveRequestPriority()
    internal var previousRotationTarget: RotationTarget? = null
        private set

    /**
     * The rotation we want to aim at. This DOES NOT mean that the server already received this rotation.
     */
    var currentRotation: Rotation? = null
        set(value) {
            previousRotation = if (value == null) {
                null
            } else {
                field ?: mc.player?.rotation ?: Rotation.ZERO
            }

            field = value
        }

    // Used for rotation interpolation
    var playerRotation: Rotation? = null
    var previousRotation: Rotation? = null

    private val fakeLagging
        get() = BlinkManager.isLagging || ModuleBacktrack.isLagging()

    private val freezing
        get() = ModuleAirStuck.running

    val serverRotation: Rotation
        get() = if (fakeLagging || freezing) theoreticalServerRotation else actualServerRotation

    /**
     * The rotation that was already sent to the server and is currently active.
     * The value is not being written by the packets, but we gather the Rotation from the last yaw and pitch variables
     * from our player instance handled by the sendMovementPackets() function.
     */
    var actualServerRotation = Rotation.ZERO
        private set

    private var theoreticalServerRotation = Rotation.ZERO

    @Suppress("LongParameterList")
    fun setRotationTarget(
        rotation: Rotation,
        considerInventory: Boolean = true,
        valueGroup: RotationsValueGroup,
        priority: Priority,
        provider: ClientModule,
        whenReached: RestrictedSingleUseAction? = null
    ) {
        setRotationTarget(valueGroup.toRotationTarget(
            rotation, considerInventory = considerInventory, whenReached = whenReached
        ), priority, provider)
    }

    fun setRotationTarget(plan: RotationTarget, priority: Priority, provider: ClientModule) {
        if (!allowedToUpdate()) {
            return
        }

        rotationTargetHandler.request(
            RequestHandler.Request(
                // CHANGE_LOOK moves the visible camera; it expires after a single tick and
                // then snaps back.
                if (plan.movementCorrection.usesVisibleCamera()) 1 else plan.ticksUntilReset,
                priority.priority,
                provider,
                plan
            )
        )
    }

    /**
     * Checks if the rotation is allowed to be updated
     */
    fun isRotatingAllowed(rotationTarget: RotationTarget): Boolean {
        if (!allowedToUpdate()) {
            return false
        }

        if (rotationTarget.considerInventory) {
            if (InventoryManager.isInventoryOpen || mc.screen is ContainerScreen) {
                return false
            }
        }

        return true
    }

    /**
     * Update current rotation to a new rotation step
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    fun update() {
        val playerRotation = player.rotation.also { this.playerRotation = it }
        val activeRotationTarget = this.activeRotationTarget ?: return
        val rotationTarget = this.rotationTarget

        // Prevents any rotation changes when inventory is opened
        if (isRotatingAllowed(activeRotationTarget)) {
            val resetting = rotationTarget == null
            val fromRotation = currentRotation ?: playerRotation
            val rotation = activeRotationTarget.towards(fromRotation, resetting)
                // After generating the next rotation, we need to normalize it
                .normalize()

            if (resetting) {
                // CHANGE_LOOK uses the actual camera and an empty processor list means no
                // smoothing was requested - both release instantly.
                val instantRelease = activeRotationTarget.movementCorrection == MovementCorrection.CHANGE_LOOK
                    || activeRotationTarget.processors.isEmpty()

                // Difference between the rotation that is ALREADY being sent to the server and the
                // player's real rotation. We only release once this is exactly zero, so the packet
                // sent right after the release carries the same rotation that was already on the
                // wire - no real-rotation packet is ever sent before the silent rotation has fully
                // returned, and the hand-off introduces no extra (potentially non-GCD) jump.
                val sentDiff = (currentRotation ?: playerRotation).angleTo(playerRotation)

                if (instantRelease || sentDiff <= 0f) {
                    currentRotation?.let { currentRotation ->
                        player.yRot = player.withFixedYaw(currentRotation)
                        player.yBob = player.yRot
                        player.yBobO = player.yRot
                    }

                    currentRotation = null
                    previousRotationTarget = null
                } else {
                    // Some smoothers own the exact endpoint and must retain their return limits.
                    currentRotation = if (!activeRotationTarget.preservesResetSteps &&
                        rotation.angleTo(playerRotation) <= activeRotationTarget.resetThreshold) {
                        realRotationContinuousWith(fromRotation, playerRotation)
                    } else {
                        rotation
                    }
                    previousRotationTarget = activeRotationTarget
                }
            } else {
                currentRotation = rotation
                previousRotationTarget = activeRotationTarget

                rotationTarget?.whenReached?.invoke()
            }
        }

        // Update reset ticks
        rotationTargetHandler.tick()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val activeRotationTarget = this.activeRotationTarget ?: return@handler

        val matrixStack = event.matrixStack
        val partialTicks = event.partialTicks

        if (isRotatingAllowed(activeRotationTarget)
            && activeRotationTarget.movementCorrection.usesVisibleCamera()) {
            val playerRotation = playerRotation ?: return@handler
            val currentRotation = currentRotation ?: return@handler
            val timerSpeed = Timer.timerSpeed

            val interpolated = playerRotation.interpolateTo(currentRotation, timerSpeed * partialTicks)
            player.setRotation(interpolated)
        }
    }

    @Suppress("unused", "MagicNumber")
    private val mouseMovement = handler<MouseRotationEvent> { event ->
        val activeRotationTarget = this.activeRotationTarget ?: return@handler
        if (!isRotatingAllowed(activeRotationTarget) ||
            !activeRotationTarget.movementCorrection.usesVisibleCamera()) {
            return@handler
        }

        val f = event.cursorDeltaY.toFloat() * 0.15f
        val g = event.cursorDeltaX.toFloat() * 0.15f

        fun adjustRotation(rotation: Rotation): Rotation =
            Rotation(yaw = rotation.yaw + g, pitch = (rotation.pitch + f).coerceIn(-90f, 90f))

        playerRotation?.let { rotation ->
            playerRotation = adjustRotation(rotation)
        }

        currentRotation?.let { rotation ->
            currentRotation = adjustRotation(rotation)
        }
    }

    /**
     * Checks if it should update the server-side rotations
     */
    private fun allowedToUpdate() = !CombatManager.shouldPauseRotation

    /**
     * Builds the player's real [target] rotation while keeping the yaw continuous with [previous]
     * (the rotation that is currently on the wire). The returned rotation is numerically equal to
     * the real rotation (same pitch, yaw ≡ target.yaw mod 360°) but expressed so that the delta to
     * [previous] is the short way around - this avoids a 360°-style yaw jump on the final reset
     * step while landing exactly on the real rotation.
     */
    private fun realRotationContinuousWith(previous: Rotation, target: Rotation): Rotation {
        val yaw = previous.yaw + angleDifference(target.yaw, previous.yaw)
        return Rotation(yaw, target.pitch, isNormalized = true)
    }

    fun rotationMatchesPreviousRotation(): Boolean {
        val player = mc.player ?: return false

        currentRotation?.let {
            return it == previousRotation
        }

        return player.rotation == player.lastRotation
    }

    @Suppress("unused")
    private val velocityHandler = handler<PlayerVelocityStrafe>(priority = MODEL_STATE) { event ->
        if (activeRotationTarget?.movementCorrection != MovementCorrection.OFF) {
            val rotation = currentRotation ?: return@handler

            event.velocity = Entity.getInputVector(
                event.movementInput,
                event.speed,
                rotation.yaw
            )
        }
    }

    @Suppress("unused")
    private val gameTickHandler = handler<GameTickEvent>(
        priority = FIRST_PRIORITY
    ) { event ->
        EventManager.callEvent(RotationUpdateEvent)
        update()
    }

    /**
     * Track rotation changes
     *
     * We cannot only rely on player.lastYaw and player.lastPitch because
     * sometimes we update the rotation off chain (e.g. on interactItem)
     * and the player.lastYaw and player.lastPitch are not updated.
     */
    @Suppress("unused")
    val packetHandler = handler<PacketEvent>(
        priority = EventPriorityConvention.READ_FINAL_STATE
    ) { event ->
        val rotation = when (val packet = event.packet) {
            is ServerboundMovePlayerPacket -> {
                // If we are not changing the look, we don't need to update the rotation
                if (!packet.hasRot) {
                    return@handler
                }

                // We trust that we have sent a normalized rotation, if not, ... why?
                Rotation(packet.yRot, packet.xRot, isNormalized = true)
            }
            is ClientboundPlayerPositionPacket -> {
                // The rotation fields of the packet are DELTAS whenever the matching Relative flag is set - vanilla
                // adds the previous rotation back in PositionMoveRotation.calculateAbsolute (yRot/xRot at
                // PositionMoveRotation.java:44-48, pitch clamped to +-90). Reading them as absolute made a routine
                // relative setback (change = 0, relatives = {Y_ROT, X_ROT}) report a server rotation of (0, 0), which
                // no later packet corrects because the client's own rotation never changed.
                val relatives = packet.relatives
                val baseYaw = if (Relative.Y_ROT in relatives) actualServerRotation.yaw else 0f
                val basePitch = if (Relative.X_ROT in relatives) actualServerRotation.pitch else 0f

                Rotation(
                    baseYaw + packet.change.yRot,
                    Mth.clamp(basePitch + packet.change.xRot, -90f, 90f),
                    isNormalized = true
                )
            }
            is ServerboundUseItemPacket -> Rotation(packet.yRot, packet.xRot, isNormalized = true)
            else -> return@handler
        }

        // This normally applies to Modules like Blink, BadWifi, etc.
        if (!event.isCancelled) {
            actualServerRotation = rotation
        }
        theoreticalServerRotation = rotation
    }

    override val running: Boolean
        get() = inGame

}
