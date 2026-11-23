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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.launch

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.movement.elytrafly.ModuleElytraFly
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket

/**
 * Getting off the ground.
 *
 * A chase module that only works once the player has already launched is half an autopilot, so this
 * does the jump and the opening of the wings itself. Two steps, because vanilla needs them in order:
 * the elytra can only be deployed while falling, so on the ground we ask for a jump and wait, and
 * only once there is air underneath do we deploy.
 *
 * Whether flying is possible at all is [ModuleElytraFly.shouldNotOperate]'s question rather than one
 * asked again here. It already covers the vehicle, creative-flight and levitation cases, and checks
 * that the chest slot holds an elytra that is not one hit from breaking, which is the check that
 * keeps this from launching us to our death on a ruined wing.
 */
class AutoLaunch(module: ClientModule) : ToggleableValueGroup(module, "AutoLaunch", true) {

    /**
     * How long to fall before deploying. Deploying on the very first airborne tick sometimes races
     * the server's own idea of whether we are still standing, and the rejection costs a whole
     * re-launch; a couple of ticks of clearance is cheaper than that.
     */
    private val minAirTicks by int("MinAirTicks", 2, 0..20, "ticks")

    /** True while we are stuck on the ground and need a jump to get the launch started. */
    fun wantsJump(): Boolean = running && !player.isFallFlying && player.onGround() && canLaunch()

    /**
     * Deploys the wings if this is the moment for it.
     *
     * Both halves are sent: the client-side call so our own prediction starts gliding immediately,
     * and the packet so the server agrees. Sending only one produces a player who is flying on
     * exactly one side of the connection.
     *
     * @return true if a launch was issued this tick
     */
    fun tryLaunch(): Boolean {
        if (!running || player.isFallFlying || !canLaunch()) {
            return false
        }
        if (player.onGround() || player.airTicks < minAirTicks) {
            return false
        }

        player.startFallFlying()
        network.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
        )
        return true
    }

    private fun canLaunch(): Boolean = !ModuleElytraFly.shouldNotOperate() && !player.isInLiquid
}
