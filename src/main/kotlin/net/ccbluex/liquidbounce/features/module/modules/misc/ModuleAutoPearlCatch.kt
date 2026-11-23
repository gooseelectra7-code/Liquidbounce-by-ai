/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redististribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * AutoPearlCatch — throws a wind charge at your own ender pearl mid-air to catch it.
 *
 * The module does not react to a render/renderable pearl on the world — it catches the pearl the
 * MOMENT you throw it: the spawn packet the server sends for the pearl [ClientboundAddEntityPacket]
 * carries the spawn position and the post-roll velocity, which is enough to predict where it is at
 * every tick. The velocity steps forward each tick with generic throwable physics —
 * [TrajectoryInfo.GENERIC]: gravity 0.03, drag 0.99 per tick, pos += vel, then vel = vel * drag −
 * gravity. The first tick the pearl is within [Range] of the eye with a clear flight line to it is
 * the intercept; a wind charge (speed ≈ [TrajectoryInfo.WIND_CHARGE]'s model, drag 1.0) is thrown
 * with a normal interaction (hotbar → silent announce → use), the same shape
 * [ModuleAutoPearl]'s throws use.
 *
 * Prediction is best-effort: the pearl's real spin/dive are never read back client-side, and the
 * wind charge has a 5-block blast radius, so aiming at the pearl is the throw, not the guarantee.
 * Known limitation: the pearl's own use key is not gated during the throw.
 */
object ModuleAutoPearlCatch : ClientModule("AutoPearlCatch", ModuleCategories.MISC) {

    private val range by float("Range", 6f, 2f..10f, "m")
    private val maxTicks by int("MaxTicks", 60, 10..200, "ticks")
    private val cooldown by int("Cooldown", 15, 0..60, "ticks")
    private val aimHeight by float("AimHeight", 0.25f, 0f..1.5f, "m")

    @Volatile
    private var pearlFlight: Pair<Vec3, Vec3>? = null

    @Volatile
    private var cooldownUntil = 0L

    @Suppress("unused")
    private val pearlSpawnHandler = handler<PacketEvent> { event ->
        val packet = event.packet as? ClientboundAddEntityPacket ?: return@handler
        if (packet.type != EntityType.ENDER_PEARL) return@handler

        pearlFlight = Vec3(packet.x, packet.y, packet.z) to
            Vec3(packet.movement.x, packet.movement.y, packet.movement.z)
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val flight = pearlFlight ?: return@tickHandler
        pearlFlight = null

        val now = System.currentTimeMillis()
        if (now < cooldownUntil) return@tickHandler
        cooldownUntil = now + cooldown.toLong()

        val pearlSlot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL) ?: return@tickHandler

        val eye = player.eyePosition
        var pos = flight.first
        var velocity = flight.second

        for (t in 0..maxTicks) {
            if (eye.distanceTo(pos) <= range.toDouble() && flightLineFree(eye, pos)) {
                throwWindCharge(pearlSlot, pos)
                return@tickHandler
            }
            if (pos.y < -32.0) return@tickHandler

            pos = pos.add(velocity)
            velocity = velocity.scale(PEARL_DRAG).subtract(0.0, PEARL_GRAVITY, 0.0)
        }
    }

    /**
     * Straight flight line from the eye to [target]: the pearl must not be clipped by anything
     * before the charge reaches it (that's the point of intercepting mid-air).
     */
    private fun flightLineFree(eye: Vec3, target: Vec3): Boolean {
        if (eye.distanceTo(target) < 0.5) return false
        val hit = world.clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type != HitResult.Type.BLOCK
    }

    private fun throwWindCharge(pearlSlot: HotbarItemSlot, target: Vec3) {
        val chargeSlot = Slots.OffhandWithHotbar.findSlot(Items.WIND_CHARGE) ?: return

        val rotation = Rotation.lookingAt(target.add(0.0, aimHeight.toDouble(), 0.0), player.eyePosition)
        val (yaw, pitch) = rotation.normalize()

        useHotbarSlotOrOffhand(chargeSlot, 1, yaw, pitch)
    }

    private val PEARL_GRAVITY = TrajectoryInfo.GENERIC.gravity
    private val PEARL_DRAG = TrajectoryInfo.GENERIC.drag
}
