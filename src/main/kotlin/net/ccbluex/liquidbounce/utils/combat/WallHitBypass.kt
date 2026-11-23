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
package net.ccbluex.liquidbounce.utils.combat

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.raytracing.hasLineOfSight
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * True when the straight line from our eyes to [hitLocation] is blocked by a block, i.e. the
 * hit this tick is going to be a through-wall hit.
 */
fun isHitBehindWall(hitLocation: Vec3): Boolean = !hasLineOfSight(player.eyePosition, hitLocation)

/**
 * Disguises a strike on a target that has no line of sight.
 *
 * The rotation packet sent ahead of the strike carries the pitch mirrored and the yaw pushed
 * sideways by a sector-dependent offset, so the server-side trace from the "look" it just
 * received does not pass through the block in front of us; an interact on the target then
 * primes the entity-interaction path before the attack, and the real rotation is restored right
 * after. Shared by KillAura and AutoClicker, each hanging its own instance in its settings tree.
 */
open class WallHitBypass(
    parent: EventListener,
    name: String = "WallBypass",
    enabled: Boolean = false,
) : ToggleableValueGroup(parent, name, enabled) {

    /** Yaw offset applied when the look lands in one of the two side sectors. */
    private val yawOffset by float("YawOffset", 50f, 0f..90f, "°")

    /** Yaw offset applied everywhere else. */
    private val minorYawOffset by float("MinorYawOffset", 25f, 0f..90f, "°")

    /** Sends the spoofed look with the pitch sign flipped. */
    private val mirrorPitch by boolean("MirrorPitch", true)

    /** Sends an interact on the target right before the attack packet. */
    private val interact by boolean("Interact", true)

    /**
     * Sends an attack on [target] aimed with [rotation]. [send] performs the actual attack and
     * must put the attack packet itself on the wire. Disabled group = plain [send].
     *
     * Sequence when enabled:
     *  1. move packet with the spoofed yaw / mirrored pitch
     *  2. interact on the target (one packet: the protocol merged `interact` and `interactAt`
     *     into a single [ServerboundInteractPacket] carrying the hit location)
     *  3. the attack itself
     *  4. move packet with the real [rotation]
     */
    fun attack(target: Entity, rotation: Rotation, send: () -> Unit) {
        if (!enabled) {
            send()
            return
        }

        val yawSector = Mth.wrapDegrees(rotation.yaw) + 180f
        val spoofedYaw = when {
            yawSector > 100f && yawSector < 200f -> rotation.yaw - yawOffset
            yawSector >= 200f && yawSector < 240f -> rotation.yaw + yawOffset
            else -> rotation.yaw + minorYawOffset
        }
        val spoofedPitch = if (mirrorPitch) -rotation.pitch else rotation.pitch

        sendLook(spoofedYaw, spoofedPitch)
        if (interact) {
            network.send(ServerboundInteractPacket(target.id, InteractionHand.MAIN_HAND, Vec3.ZERO, false))
        }

        send()

        sendLook(rotation.yaw, rotation.pitch)
    }

    private fun sendLook(yaw: Float, pitch: Float) {
        network.send(
            PosRot(
                player.x, player.y, player.z,
                yaw, pitch,
                player.onGround(), player.horizontalCollision
            )
        )
    }

}
