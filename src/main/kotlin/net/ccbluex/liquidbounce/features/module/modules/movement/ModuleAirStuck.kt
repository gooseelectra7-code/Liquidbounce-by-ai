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

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.phys.Vec3

/**
 * AirStuck module
 *
 * Suspends you mid-air by holding back movement packets, so the server keeps you at your
 * last known position.
 *
 * - Grim: only movement packets are dropped; everything else (keep-alives, actions) still
 *   flows, so the connection stays healthy and the server simply never learns a new position.
 * - FunTime: movement packets are dropped and ALL remaining outgoing traffic is queued
 *   through [BlinkManager] (flushed in order on disable). Since keep-alives are held back
 *   too, the server would time the connection out after ~30 s - the module auto-disables
 *   after 28 s.
 */
object ModuleAirStuck : ClientModule(
    "AirStuck",
    ModuleCategories.MOVEMENT,
    disableOnQuit = true,
    aliases = listOf("Freeze")
) {

    private val mode by enumChoice("Mode", Mode.GRIM).apply { tagBy(this) }

    /**
     * Refuse to start while standing on the ground, and skip the velocity restore when
     * the player has landed while stuck.
     */
    private val fallCheck by boolean("FallCheck", true)

    internal val bindInContainers by boolean("BindInContainers", false)

    private val kickTimer = Chronometer()
    private var savedVelocity: Vec3 = Vec3.ZERO

    private const val KICK_PROTECTION_MS = 28_000L

    override fun onEnabled() {
        kickTimer.reset()

        if (!inGame) {
            return
        }

        if (fallCheck && player.onGround()) {
            notification(name, message("mustBeAirborne"), NotificationEvent.Severity.ERROR)
            enabled = false
            return
        }

        savedVelocity = player.deltaMovement
        player.isNoGravity = true
    }

    override fun onDisabled() {
        if (!inGame) {
            return
        }

        player.isNoGravity = false

        // Do not launch the player with the pre-freeze momentum after landing
        if (!(fallCheck && player.onGround())) {
            player.deltaMovement = savedVelocity
        }
    }

    /**
     * The player must be genuinely stationary: with movement packets held back, any
     * client-side motion would desync us from the server-side position.
     */
    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        event.directionalInput = DirectionalInput.NONE
        event.jump = false
        event.sneak = false
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        if (mode == Mode.FUNTIME && kickTimer.hasElapsed(KICK_PROTECTION_MS)) {
            notification(name, message("kickProtection"), NotificationEvent.Severity.INFO)
            enabled = false
            return@handler
        }

        player.deltaMovement = Vec3.ZERO
        player.isNoGravity = true
    }

    /**
     * Both modes drop movement packets entirely - they must never reach the blink queue,
     * or the FunTime flush would walk the server through the held-back path. Runs before
     * BlinkManager's FINAL_DECISION handler, which skips cancelled events.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.OUTGOING && event.packet is ServerboundMovePlayerPacket) {
            event.cancelEvent()
        }
    }

    /**
     * FunTime: hold back the remaining outgoing traffic. BlinkManager flushes the queue
     * automatically once this handler goes inactive on disable, as well as on
     * teleport/death/disconnect.
     */
    @Suppress("unused")
    private val blinkHandler = handler<BlinkPacketEvent>(
        priority = EventPriorityConvention.SAFETY_FEATURE
    ) { event ->
        if (mode == Mode.FUNTIME && event.origin == TransferOrigin.OUTGOING) {
            event.action = BlinkManager.Action.QUEUE
        }
    }

    private enum class Mode(override val tag: String) : Tagged {
        GRIM("Grim"),
        FUNTIME("FunTime"),
    }

}
