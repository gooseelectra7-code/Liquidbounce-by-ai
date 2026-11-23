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

package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.network.sendHeldItemChange
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.world.item.BowItem
import org.lwjgl.glfw.GLFW
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Automatic bow self-boost on a trigger keybind.
 *
 * Fires a weak, nearly-vertical arrow with a slight forward lean. The arrow arcs back and hits the
 * shooter; with the **Punch** (Отдача) enchantment this applies a horizontal knockback impulse to us.
 * Because arrow knockback normalizes the horizontal direction and scales only by the Punch level
 * (`level * 0.6`, plus `+0.1` up), even a weak shot with a tiny lean yields the full boost in the
 * facing direction while dealing minimal self-damage. After the impulse lands we optionally rotate
 * forward so the velocity carries us along our heading (e.g. converts into forward glide on elytra).
 *
 * Not tied to elytra in any way — works wherever the arrow can return and hit the player.
 *
 * Modeled after the existing
 * [net.ccbluex.liquidbounce.features.module.modules.movement.longjump.modes.nocheatplus.NoCheatPlusBow].
 */
@Suppress("MagicNumber")
object ModuleBowBoost : ClientModule("BowBoost", ModuleCategories.MOVEMENT) {

    private val trigger by key("Trigger")
    private val charge by int("Charge", 4, 1..20, suffix = "ticks")
    private val forwardLean by float("ForwardLean", 12f, 0f..40f, suffix = "°")
    private val predict by boolean("Predict", true)
    private val predictStrength by float("PredictStrength", 1f, 0f..2f)
    private val rotateForward by boolean("RotateForward", true)
    private val forwardPitch by float("ForwardPitch", 0f, -30f..30f, suffix = "°")
    private val autoSwitch by boolean("AutoSwitchBow", true)
    private val timeout by int("Timeout", 60, 20..200, suffix = "ticks")

    private val rotations = tree(RotationsValueGroup(this))

    /**
     * Keep the arrow steep enough to always loft and fall back down: never spend more than this
     * fraction of the arrow's speed on the horizontal lead.
     */
    private const val MAX_HORIZONTAL_FRACTION = 0.6

    /** Release once the server rotation is within this many degrees of the aim. */
    private const val AIM_THRESHOLD = 6f

    /** Hard cap on how many extra ticks we hold the draw waiting for the aim to settle. */
    private const val MAX_AIM_WAIT = 8

    private enum class State { IDLE, DRAWING, WAITING }

    private var state = State.IDLE
    private var forceUseKey = false
    private var boostYaw = 0f
    private var aimTarget = Rotation.ZERO
    private var prevSlot: Int? = null
    private var ticksWaited = 0
    private var boostReceived = false

    /**
     * Switch to a bow in the hotbar (remembering the previous slot). Returns false if none found.
     */
    private fun selectBow(): Boolean {
        if (player.mainHandItem.item is BowItem) {
            return true
        }

        if (!autoSwitch) {
            return false
        }

        val slot = Slots.Hotbar.findSlot { it.item is BowItem } ?: return false
        val index = slot.hotbarIndex ?: return false

        prevSlot = player.inventory.selectedSlot
        player.inventory.selectedSlot = index
        network.sendHeldItemChange(index)
        return true
    }

    private fun restoreSlot() {
        prevSlot?.let { slot ->
            player.inventory.selectedSlot = slot
            network.sendHeldItemChange(slot)
        }
        prevSlot = null
    }

    /** Vanilla bow arrow speed (blocks/tick) for a given draw time. */
    private fun arrowSpeedFor(chargeTicks: Int): Float {
        val t = chargeTicks.coerceIn(0, 20)
        var power = t / 20f
        power = (power * power + power * 2f) / 3f
        return power.coerceAtMost(1f) * 3.0f
    }

    /**
     * Aim for the boost, computed ONCE at the start of the draw (the velocity/charge are frozen so
     * the smoothed rotation has a stable target to converge on — recomputing every tick made the aim
     * chase a moving target and dragged the draw out). [chargeTicks] is the intended release charge,
     * so the arrow speed matches the actual shot.
     *
     * With [predict], the arrow is given a horizontal velocity matching the player's current
     * horizontal movement, so it travels *with* the player and falls back onto the predicted future
     * position instead of where the player stood at the shot. The vertical component is whatever
     * speed is left after the lead (always ≥ 40% of the arrow speed, see [MAX_HORIZONTAL_FRACTION]),
     * keeping the shot steep enough to return. [forwardLean] is enforced as a minimum tilt below
     * vertical in both modes, so the shot is never aimed perfectly straight up. When standing still
     * (or with prediction off) it falls back to that fixed lean from the captured facing.
     */
    private fun computeAimRotation(chargeTicks: Int): Rotation {
        val vx = player.deltaMovement.x
        val vz = player.deltaMovement.z
        val hLen = hypot(vx, vz)
        val arrowSpeed = arrowSpeedFor(chargeTicks).toDouble()

        if (!predict || hLen < 1e-3 || arrowSpeed < 1e-3) {
            return Rotation(boostYaw, -90f + forwardLean)
        }

        val desiredH = (hLen * predictStrength).coerceAtMost(arrowSpeed * MAX_HORIZONTAL_FRACTION)
        val scale = desiredH / hLen
        val vertical = sqrt(max(0.0, arrowSpeed * arrowSpeed - desiredH * desiredH))

        val rot = Rotation.fromRotationVec(vx * scale, vertical, vz * scale)
        // Never steeper than `-90 + forwardLean` so the shot keeps a bit of lean off vertical.
        return Rotation(rot.yaw, rot.pitch.coerceAtLeast(-90f + forwardLean))
    }

    private fun resetCycle() {
        forceUseKey = false
        if (player.isUsingItem) {
            interaction.releaseUsingItem(player)
        }
        restoreSlot()
        state = State.IDLE
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyEvent> { event ->
        if (event.key == trigger && event.action == GLFW.GLFW_PRESS && state == State.IDLE) {
            boostYaw = player.yRot

            if (!selectBow()) {
                return@handler
            }

            // Freeze the aim now (using the intended release charge) so the rotation has a stable
            // target to converge on instead of chasing the live velocity every tick.
            aimTarget = computeAimRotation(charge)
            boostYaw = aimTarget.yaw
            state = State.DRAWING
        }
    }

    @Suppress("unused")
    private val keybindIsPressedHandler = handler<KeybindIsPressedEvent> { event ->
        if (event.keyBinding == mc.options.keyUse && forceUseKey) {
            event.isPressed = true
        }
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        when (state) {
            State.IDLE -> {}

            State.DRAWING -> {
                forceUseKey = true

                RotationManager.setRotationTarget(
                    aimTarget,
                    valueGroup = rotations,
                    priority = Priority.IMPORTANT_FOR_USAGE_2,
                    provider = this@ModuleBowBoost
                )

                // Don't fire until the bow is charged AND the aim has actually reached the target
                // (otherwise a quick press releases mid-rotation and the arrow flies off-course and
                // never returns). [MAX_AIM_WAIT] guarantees the shot still happens eventually.
                val aimed = RotationManager.serverRotation.angleTo(aimTarget) <= AIM_THRESHOLD
                if (player.ticksUsingItem >= charge &&
                    (aimed || player.ticksUsingItem >= charge + MAX_AIM_WAIT)) {
                    interaction.releaseUsingItem(player)
                    forceUseKey = false
                    boostReceived = false
                    ticksWaited = 0
                    state = State.WAITING
                }
            }

            State.WAITING -> {
                ticksWaited++

                if (boostReceived) {
                    if (rotateForward) {
                        RotationManager.setRotationTarget(
                            Rotation(boostYaw, forwardPitch),
                            valueGroup = rotations,
                            priority = Priority.IMPORTANT_FOR_USAGE_2,
                            provider = this@ModuleBowBoost
                        )
                    }
                    restoreSlot()
                    state = State.IDLE
                } else if (ticksWaited > timeout) {
                    restoreSlot()
                    state = State.IDLE
                }
            }
        }
    }

    @Suppress("unused")
    private val velocityHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet is ClientboundSetEntityMotionPacket && packet.id == player.id && state == State.WAITING) {
            boostReceived = true
        }
    }

    override fun onDisabled() {
        resetCycle()
        super.onDisabled()
    }
}
