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
package net.ccbluex.liquidbounce.features.module.modules.player

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.input.isPressed
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

/**
 * DiveBomb module
 *
 * Port of echo's `DiveBomb` (`hack.echo.client.features.impl.macros.DiveBomb`).
 *
 * Throws an ender pearl straight up, then a wind charge after it. The charge bursts against the
 * still-rising pearl and drives it far higher than a throw ever could, so the teleport drops you
 * into the sky — from where you dive onto whatever you were standing next to.
 *
 * The whole trick rests on the two projectiles meeting, which is why the gap between them is a
 * setting ([tickDelay], echo's default of 2) rather than "as fast as possible": throw the charge too
 * early and it bursts below the pearl, too late and the pearl has outrun it.
 *
 * Kept from echo: aiming straight up while preserving yaw (-89.9°, not -90°, which is the
 * gimbal-locked value where yaw stops meaning anything), the pitch gate before either throw, the
 * auto-jump, the movement freeze that keeps the throws from inheriting your velocity, the cooldown
 * check on both items, and the Switchback slot restore.
 *
 * ## Anti-flag notes
 *
 * `ServerboundUseItemPacket` carries its own yaw/pitch, and Grim's BadPacketsJ
 * (`use_item_rotation_mismatch`, not experimental — on by default) compares them against the
 * rotation of that tick. echo's author hit exactly this and left a comment about it in the source.
 * Two things keep it quiet here:
 *
 * - The aim is **captured once** when the sequence starts and never re-submitted, so the rotation
 *   cannot move between the use packet and the tick's flying. Your yaw at that instant is what the
 *   whole dive uses — the mouse is deliberately not tracked mid-sequence.
 * - The two throws sit [tickDelay] ticks apart, so each gets its own tick-window. Vanilla cannot
 *   emit two use-item packets in one tick, and [Instant] is where that rule is knowingly broken —
 *   see its documentation.
 *
 * Slot changes go through the vanilla tracker and always lead their tick-window, never trail the
 * use (Grim PacketOrderE flags a held-item change that lands behind a use in the same window).
 */
@Suppress("TooManyFunctions")
object ModuleDiveBomb : ClientModule("DiveBomb", ModuleCategories.PLAYER) {

    /** Straight up. -90 exactly is the degenerate pole where yaw stops being meaningful. */
    private const val STRAIGHT_UP_PITCH = -89.9f

    /** How close to vertical the wire rotation has to be before anything is thrown. */
    private const val PITCH_GATE = -89.5f

    private const val STEP_TIMEOUT_TICKS = 10

    // ── Settings ─────────────────────────────────────────────────────────────

    private val activateKey by key("ActivateKey")

    /**
     * Fires the whole combo inside a single tick with the pearl in the main hand and the charge in
     * the offhand, rather than spacing the throws.
     *
     * This emits **two use-item packets in one tick**, which no vanilla client produces — vanilla
     * resolves at most one right-click per tick. It is the fastest possible dive and it is NOT
     * Grim-safe; it exists because echo has it. Prefer the normal key on anticheat servers.
     */
    private val instantKey by key("InstantKey")

    private val autoJump by boolean("AutoJump", true)
    private val silent by boolean("Silent", true)
    private val switchback by boolean("Switchback", true)

    /** Ticks between the pearl and the charge — the two have to actually meet. */
    private val tickDelay by int("TickDelay", 2, 0..6, "ticks")

    // ── State ────────────────────────────────────────────────────────────────

    private enum class Stage { PEARL, WAIT, CHARGE, SETTLE }

    private enum class InstantPhase { EQUIP, AIM, THROW, RESTORE }

    private var active = false
    private var stage = Stage.PEARL
    private var stageTicks = 0

    private var keyWasDown = false
    private var instantKeyWasDown = false

    /** Yaw captured when the dive starts; the aim is frozen to it for the whole sequence. */
    private var lockedYaw = 0f

    private var savedSlot = -1
    private var lastSwapTick = Int.MIN_VALUE

    /**
     * Own tick clock; `player.tickCount` restarts near zero on respawn, dimension change and
     * reconnect, which would leave the one-swap-per-tick cap comparing against a stale number.
     */
    private var clock = 0

    private var instantPhase: InstantPhase? = null
    private var instantTicks = 0
    private var restoreOffhand = false

    override fun onDisabled() {
        finish()
        keyWasDown = false
        instantKeyWasDown = false
        super.onDisabled()
    }

    // ── Input freeze ─────────────────────────────────────────────────────────

    /**
     * echo freezes the directional keys for the length of the run "to make thrown items less
     * affected by movement" — a pearl inherits the thrower's velocity, and any drift turns a
     * straight-up throw into an arc that the charge then misses.
     */
    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (!active) return@handler

        event.directionalInput = DirectionalInput.NONE
        if (autoJump && instantPhase == null && player.onGround()) {
            event.jump = true
        }
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Suppress("unused")
    private val tickHandler = handler<RotationUpdateEvent> { _ ->
        clock++

        if (mc.screen != null) {
            return@handler
        }

        pollKeys()

        if (!active) {
            return@handler
        }

        // Hold the captured aim for the whole run. Re-submitting the SAME rotation every tick keeps
        // it from expiring, and because the value never changes the use packets and the tick's
        // flying always agree (Grim BadPacketsJ).
        aimStraightUp()

        if (instantPhase != null) {
            tickInstant()
            return@handler
        }

        tickSequence()
    }

    private fun pollKeys() {
        val instantDown = instantKey.isDown()
        if (instantDown && !instantKeyWasDown) {
            startInstant()
        }
        instantKeyWasDown = instantDown

        val keyDown = activateKey.isDown()
        if (keyDown && !keyWasDown && !active && hasItems() && !isCoolingDown()) {
            active = true
            stage = Stage.PEARL
            stageTicks = 0
            lockedYaw = RotationManager.serverRotation.yaw
            savedSlot = player.inventory.selectedSlot
        }
        keyWasDown = keyDown
    }

    /** The spaced sequence: pearl, wait for the charge to be able to catch it, charge. */
    private fun tickSequence() {
        // Nothing is thrown until the rotation the SERVER holds is actually vertical, or the pearl
        // leaves at whatever angle the player happened to be looking at.
        if (RotationManager.serverRotation.pitch > PITCH_GATE) {
            if (++stageTicks > STEP_TIMEOUT_TICKS) finish()
            return
        }

        when (stage) {
            Stage.PEARL -> {
                if (isCoolingDown()) return
                if (!throwFromHotbar(Items.ENDER_PEARL)) {
                    finish()
                    return
                }
                stage = Stage.WAIT
                stageTicks = 0
            }

            Stage.WAIT -> if (++stageTicks > tickDelay) {
                stage = Stage.CHARGE
                stageTicks = 0
            }

            Stage.CHARGE -> {
                if (!throwFromHotbar(Items.WIND_CHARGE)) {
                    finish()
                    return
                }
                stage = Stage.SETTLE
                stageTicks = 0
            }

            // One tick of grace so the slot restore never lands in the throw's tick-window.
            Stage.SETTLE -> if (++stageTicks > 0) finish()
        }
    }

    /**
     * Instant run: pearl in the main hand, charge in the offhand, both thrown in the same tick.
     * See [instantKey] — this is the deliberately non-vanilla path.
     */
    private fun startInstant() {
        if (active || isCoolingDown()) return

        val pearlSlot = findHotbarSlot(Items.ENDER_PEARL)
        val chargeSlot = findHotbarSlot(Items.WIND_CHARGE)
        restoreOffhand = !player.offhandItem.`is`(Items.WIND_CHARGE)

        if (restoreOffhand && chargeSlot == -1) return
        if (!player.mainHandItem.`is`(Items.ENDER_PEARL) && pearlSlot == -1) return

        active = true
        stage = Stage.PEARL
        stageTicks = 0
        lockedYaw = RotationManager.serverRotation.yaw
        savedSlot = player.inventory.selectedSlot
        instantPhase = InstantPhase.EQUIP
        instantTicks = 0
    }

    private fun tickInstant() {
        if (++instantTicks > STEP_TIMEOUT_TICKS) {
            finish()
            return
        }

        when (instantPhase) {
            InstantPhase.EQUIP -> tickEquip()

            InstantPhase.AIM -> if (RotationManager.serverRotation.pitch <= PITCH_GATE) {
                instantPhase = InstantPhase.THROW
            }

            InstantPhase.THROW -> {
                // Both hands in one tick — the non-vanilla part.
                interaction.useItem(player, InteractionHand.MAIN_HAND)
                interaction.useItem(player, InteractionHand.OFF_HAND)
                instantPhase = if (restoreOffhand) InstantPhase.RESTORE else null
                instantTicks = 0
                if (instantPhase == null) finish()
            }

            InstantPhase.RESTORE -> {
                // Swapping back is a fresh tick-window, never behind the throws (PacketOrderE).
                swapOffhand()
                finish()
            }

            null -> finish()
        }
    }

    @Suppress("ReturnCount")
    private fun tickEquip() {
        if (restoreOffhand && !player.offhandItem.`is`(Items.WIND_CHARGE)) {
            val chargeSlot = findHotbarSlot(Items.WIND_CHARGE)
            when {
                chargeSlot == -1 -> finish()
                swapTo(chargeSlot) -> swapOffhand()
            }
            return
        }

        if (!player.mainHandItem.`is`(Items.ENDER_PEARL)) {
            val pearlSlot = findHotbarSlot(Items.ENDER_PEARL)
            if (pearlSlot == -1) finish() else swapTo(pearlSlot)
            return
        }

        instantPhase = InstantPhase.AIM
        instantTicks = 0
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /** Swap to [item] and throw it. The swap leads the window, the use follows — vanilla order. */
    private fun throwFromHotbar(item: net.minecraft.world.item.Item): Boolean {
        if (player.mainHandItem.`is`(item)) {
            interaction.useItem(player, InteractionHand.MAIN_HAND)
            return true
        }

        val slot = findHotbarSlot(item)
        if (slot == -1) return false
        if (!swapTo(slot)) return true // swap budget spent this tick; throw on the next one
        if (!player.mainHandItem.`is`(item)) return true

        interaction.useItem(player, InteractionHand.MAIN_HAND)
        return true
    }

    private fun swapOffhand() {
        network.send(
            ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO,
                Direction.DOWN
            )
        )
    }

    /**
     * Real swap through the vanilla tracker, capped at one per tick — vanilla resolves the hotbar
     * selection once per tick in `handleKeybinds`, and a duplicate announce is impossible because
     * `ensureHasSentCarriedItem` only emits on a real change (Grim BadPacketsA).
     */
    private fun swapTo(slot: Int): Boolean {
        if (slot !in 0..8) return false
        if (player.inventory.selectedSlot == slot) return true
        if (clock == lastSwapTick) return false

        lastSwapTick = clock
        player.inventory.selectedSlot = slot
        interaction.ensureHasSentCarriedItem()
        return true
    }

    private fun aimStraightUp() {
        RotationManager.setRotationTarget(
            plan = RotationTarget(
                rotation = Rotation(lockedYaw, STRAIGHT_UP_PITCH, isNormalized = true),
                entity = null,
                processors = emptyList(),
                ticksUntilReset = 2,
                resetThreshold = 1f,
                considerInventory = false,
                movementCorrection = if (silent) MovementCorrection.SILENT else MovementCorrection.CHANGE_LOOK
            ),
            priority = Priority.IMPORTANT_FOR_PLAYER_LIFE,
            provider = this
        )
    }

    private fun finish() {
        if (switchback && savedSlot in 0..8 && mc.player != null &&
            player.inventory.selectedSlot != savedSlot
        ) {
            player.inventory.selectedSlot = savedSlot
            interaction.ensureHasSentCarriedItem()
        }
        active = false
        stage = Stage.PEARL
        stageTicks = 0
        instantPhase = null
        instantTicks = 0
        savedSlot = -1
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun hasItems(): Boolean =
        findHotbarSlot(Items.WIND_CHARGE) != -1 && findHotbarSlot(Items.ENDER_PEARL) != -1

    private fun isCoolingDown(): Boolean =
        player.cooldowns.isOnCooldown(ItemStack(Items.ENDER_PEARL)) ||
            player.cooldowns.isOnCooldown(ItemStack(Items.WIND_CHARGE))

    private fun findHotbarSlot(item: net.minecraft.world.item.Item): Int {
        for (i in 0..<9) {
            if (player.inventory.getItem(i).`is`(item)) return i
        }
        return -1
    }

    private fun InputConstants.Key.isDown(): Boolean {
        if (this == InputConstants.UNKNOWN || value == GLFW.GLFW_KEY_UNKNOWN) return false
        return if (type == InputConstants.Type.MOUSE) {
            GLFW.glfwGetMouseButton(mc.window.handle(), value) == GLFW.GLFW_PRESS
        } else {
            isPressed
        }
    }

}
