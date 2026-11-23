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

package net.ccbluex.liquidbounce.utils.navigation.pathing.route

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.entity.Entity

/**
 * Lets a ridden animal follow the navigation rotation without the camera having to turn.
 *
 * Vanilla steers a mount from the rider's real head yaw, so a silent rotation — which only changes
 * what the server is told — leaves the animal following the untouched camera. Turning the real
 * camera works but drags the player's view around for the whole journey, which is unpleasant and
 * makes it impossible to look anywhere else while travelling.
 *
 * Instead, the mixin on the mount's own steering asks this for the rotation to use, exactly as the
 * elytra hooks in `MixinLivingEntity` already redirect vanilla's glide maths through
 * [RotationManager]. The camera stays where the player put it, the server is told the same rotation
 * the animal is steering by, and the two remain consistent with each other.
 *
 * Returns null whenever nothing should be overridden, which is the common case.
 */
object NavMountSteering {

    /**
     * Whether silent steering is wanted at all. Owned by the module's setting; false means the
     * mixin leaves vanilla completely alone.
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * The rotation [mount] should steer by, or null to leave vanilla's own behaviour in place.
     *
     * Called from the mount's steering path every tick, so it stays cheap and allocation-free on
     * the overwhelmingly common "not navigating" path.
     */
    @JvmStatic
    fun steeringRotationFor(mount: Entity): Rotation? {
        if (!enabled) return null

        // Only the animal the local player is actually riding; everyone else's mounts are none of
        // our business and must move exactly as the server says.
        val player = mc.player ?: return null
        if (player.vehicle !== mount) return null

        val target = RotationManager.activeRotationTarget ?: return null
        // OFF means the caller explicitly asked for no movement compensation at all, and a visible
        // camera is already steering the mount on its own.
        if (target.movementCorrection == MovementCorrection.OFF) return null
        if (target.movementCorrection.usesVisibleCamera()) return null

        return RotationManager.currentRotation
    }
}
